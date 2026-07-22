// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

// ccluau: JNI bridge between CC: Tweaked's ILuaMachine and the Luau VM.
//
// Values cross the JNI boundary as a compact little-endian binary encoding
// (one buffer per call) rather than individual stack operations. See
// LuauValueCodec.java for the Java half of the protocol.
//
// Error-safety rules: Luau raises errors as C++ exceptions (or longjmp),
// which must never escape a JNI entry point. The value codec is therefore
// written to be no-throw (failures set a flag and push nil), and anything
// that can allocate outside lua_resume runs inside lua_pcall.

#include <jni.h>

#include <atomic>
#include <cmath>
#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <chrono>
#include <map>
#include <string>
#include <vector>

#include "lua.h"
#include "lualib.h"
#include "luacode.h"
#include "luacodegen.h"

// VM internals, for luaG_isnative: break/resume is not reliable for
// natively-compiled (JIT) frames, so we never suspend those.
#include "ldebug.h"

// ---------------------------------------------------------------------------
// Value protocol tags. Must match LuauValueCodec.java.
// ---------------------------------------------------------------------------
enum Tag : uint8_t {
    T_NIL = 0,
    T_TRUE = 1,
    T_FALSE = 2,
    T_DOUBLE = 3,   // f64
    T_STRING = 4,   // i32 length + bytes
    T_TABLE = 5,    // i32 id, i32 pair count, then (key, value)*
    T_REF = 6,      // i32 id of previously decoded/encoded table
    T_OBJECT = 7,      // i32 id, i32 handle, i32 method count, (i32 len + bytes)* method names
    T_FUNCTION = 8,    // i32 id, i32 handle: a bare ILuaFunction
    T_NAMED_TABLE = 9, // i32 id, i32 len + bytes (__name), i32 pair count, then (key, value)*
};

// Response statuses from Java method invocation. Must match LuauMachine.java.
enum InvokeStatus : uint8_t {
    INVOKE_RETURN = 0, // payload: encoded varargs
    INVOKE_ERROR = 1,  // payload: i32 level, i32 len + message bytes
    INVOKE_YIELD = 2,  // payload: encoded varargs
};

// Resume statuses returned to Java. Must match LuauNative.java.
enum ResumeStatus : uint8_t {
    RESUME_DEAD = 0,  // thread finished: payload = encoded varargs (return values)
    RESUME_YIELD = 1, // thread yielded: payload = encoded varargs (yield values)
    RESUME_BREAK = 2, // interrupted (pause or hard abort): no payload
    RESUME_ERROR = 3, // runtime error: payload = i32 len + message bytes
};

// Interrupt flag bits, set from Java. Must match LuauNative.java.
enum Flags : int {
    FLAG_SOFT_ABORT = 1 << 0,
    FLAG_HARD_ABORT = 1 << 1,
    FLAG_PAUSE = 1 << 2,
};

static const int MAX_DEPTH = 128;

// Size of the shared direct buffers used for the fast call path. Payloads
// exceeding this fall back to the byte[] path. Sized to hold a full terminal
// sync at the maximum resolution (10x: 510x190 cells is ~300KB of line data).
static const size_t FAST_BUFFER_SIZE = 1024 * 1024;

// ---------------------------------------------------------------------------
// Per-machine state
// ---------------------------------------------------------------------------

// A native shadow of the computer's terminal. All term.* methods operate on
// this without crossing into Java; the state is synced to the Java Terminal
// after each resume (and periodically from the interrupt callback while
// long-running code draws without yielding).
struct NativeTerm {
    int width = 0, height = 0;
    int baseWidth = 0, baseHeight = 0;
    bool colour = true;
    bool resizeDirty = false;
    bool mouseCapture = false;
    bool mouseCaptureDirty = false;

    // Row-major width*height byte planes. fg/bg hold raw bytes (usually hex
    // digits), mirroring Java's TextBuffer semantics.
    std::vector<uint8_t> text, fg, bg;

    int cursorX = 0, cursorY = 0; // 0-based; may be out of bounds
    int curFg = 0, curBg = 15;    // palette indices, 0 = white .. 15 = black
    bool blink = false;

    double palette[16][3] = {};
    double nativePalette[16][3] = {};

    std::vector<uint8_t> lineDirty;
    bool cursorDirty = false;
    bool paletteDirty = false;
    bool anyLineDirty = false;
    double lastSync = 0;

    bool anyDirty() const {
        return cursorDirty || paletteDirty || anyLineDirty || resizeDirty || mouseCaptureDirty;
    }

    void markLine(int y) {
        if (y >= 0 && y < height) {
            lineDirty[y] = 1;
            anyLineDirty = true;
        }
    }

    void markAllLines() {
        for (int y = 0; y < height; y++) lineDirty[y] = 1;
        anyLineDirty = height > 0;
    }
};

// A native mirror of the computer's redstone state. Inputs are pushed from
// Java when they change; outputs are written natively and synced back to Java
// alongside the terminal.
struct NativeRedstone {
    int input[6] = {};
    int bundledInput[6] = {};
    int output[6] = {};
    int bundledOutput[6] = {};
    bool outputDirty = false;
};

struct MachineState {
    JavaVM* jvm = nullptr;
    jobject machine = nullptr; // global ref to the LuauMachine
    lua_State* L = nullptr;    // main state
    std::atomic<int> flags{ 0 };
    std::atomic<bool> softThrown{ false };

    // Shared direct buffers for the fast (zero-copy) call path.
    uint8_t* fastArgs = nullptr;
    uint8_t* fastResp = nullptr;
    jobject fastArgsRef = nullptr;
    jobject fastRespRef = nullptr;

    NativeTerm* term = nullptr;
    NativeRedstone* redstone = nullptr;

    // The bitmap font used by the native pixel-graphics helpers, registered
    // from Lua (mineos.gfx).
    std::string fontData;
    int fontWidth = 0;
    int fontHeight = 0;

    // Whether Luau's native code generator (JIT) is active for this state.
    bool codegen = false;

    // The chain of threads suspended by a pause break, innermost first,
    // outermost (main) last. A pause interrupts the running (leaf) thread, and
    // the break then propagates up through every parent coroutine.resume. To
    // resume, we must run the leaf first: Luau's coresumecont re-breaks a
    // parent whose child is still LUA_BREAK, so the child's break must clear
    // before its parent is resumed.
    //
    // A new break can fire while an earlier chain is still draining (the
    // resumed leaf runs its timeslice and pauses again, possibly inside a
    // freshly-entered child coroutine). The suspended outer links are still
    // live, so new entries are inserted at the front rather than resetting
    // the chain. breakInsert tracks where newly-interrupted parents slot in,
    // preserving inner-to-outer order.
    std::vector<lua_State*> breakChain;
    size_t breakInsert = 0;

    bool anyStateDirty() const {
        return (term != nullptr && term->anyDirty()) || (redstone != nullptr && redstone->outputDirty);
    }
};

static MachineState* getMachine(lua_State* L);

// Record the innermost broken thread (the one the interrupt fired on). If it
// is already part of a draining chain (a re-break of the same leaf), keep the
// chain as-is; otherwise it becomes the new innermost link.
static void recordBreakLeaf(MachineState* m, lua_State* L) {
    for (size_t i = 0; i < m->breakChain.size(); i++) {
        if (m->breakChain[i] == L) {
            m->breakInsert = i + 1;
            return;
        }
    }
    m->breakChain.insert(m->breakChain.begin(), L);
    m->breakInsert = 1;
}

// Record a parent thread as the break propagates up through it. Called from
// the debuginterrupt callback, which fires once per interrupted parent,
// innermost first. Parents already in the chain (suspended by an earlier
// break) keep their position; propagation above them is already recorded.
static void recordBreakParent(MachineState* m, lua_State* L) {
    for (size_t i = 0; i < m->breakChain.size(); i++) {
        if (m->breakChain[i] == L) {
            m->breakInsert = i + 1;
            return;
        }
    }
    m->breakChain.insert(m->breakChain.begin() + m->breakInsert, L);
    m->breakInsert++;
}

static void debugInterruptCallback(lua_State* L, lua_Debug* ar) {
    (void) ar;
    recordBreakParent(getMachine(L), L);
}

static jmethodID g_invokeMethod = nullptr;     // LuauMachine.invoke(int, int, long, byte[]) -> byte[]
static jmethodID g_resumeMethod = nullptr;     // LuauMachine.resumeCallback(long, byte[]) -> byte[]
static jmethodID g_invokeFastMethod = nullptr; // LuauMachine.invokeFast(int, int, long, int) -> int
static jmethodID g_resumeFastMethod = nullptr; // LuauMachine.resumeFast(long, int) -> int
static jmethodID g_takeLargeMethod = nullptr;  // LuauMachine.takeLargeResponse() -> byte[]
static jmethodID g_syncTermMethod = nullptr;   // LuauMachine.syncTermNow() -> void

static MachineState* getMachine(lua_State* L) {
    return static_cast<MachineState*>(lua_callbacks(lua_mainthread(L))->userdata);
}

static JNIEnv* getEnv(MachineState* m) {
    JNIEnv* env = nullptr;
    m->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
    return env; // Always called from a JVM-originated thread.
}

// ---------------------------------------------------------------------------
// Buffer reader/writer
// ---------------------------------------------------------------------------
struct Reader {
    const uint8_t* data;
    size_t size;
    size_t pos = 0;
    bool ok = true;

    Reader(const uint8_t* d, size_t s) : data(d), size(s) {}

    uint8_t u8() {
        if (pos + 1 > size) { ok = false; return 0; }
        return data[pos++];
    }
    int32_t i32() {
        if (pos + 4 > size) { ok = false; return 0; }
        int32_t v;
        memcpy(&v, data + pos, 4);
        pos += 4;
        return v;
    }
    double f64() {
        if (pos + 8 > size) { ok = false; return 0; }
        double v;
        memcpy(&v, data + pos, 8);
        pos += 8;
        return v;
    }
    const uint8_t* bytes(size_t n) {
        if (pos + n > size) { ok = false; return nullptr; }
        const uint8_t* p = data + pos;
        pos += n;
        return p;
    }
};

struct Writer {
    std::vector<uint8_t> buf;

    void u8(uint8_t v) { buf.push_back(v); }
    void i32(int32_t v) {
        size_t n = buf.size();
        buf.resize(n + 4);
        memcpy(buf.data() + n, &v, 4);
    }
    void f64(double v) {
        size_t n = buf.size();
        buf.resize(n + 8);
        memcpy(buf.data() + n, &v, 8);
    }
    void bytes(const void* p, size_t len) {
        size_t n = buf.size();
        buf.resize(n + len);
        memcpy(buf.data() + n, p, len);
    }
    void str(const char* s, size_t len) {
        i32((int32_t) len);
        bytes(s, len);
    }

    size_t mark() { return buf.size(); }
    void patchI32(size_t at, int32_t v) { memcpy(buf.data() + at, &v, 4); }
};

// A bounds-checked writer over a fixed buffer. On overflow, sets a flag and
// discards further writes; the caller then retries with the byte[] path.
struct FixedWriter {
    uint8_t* buf;
    size_t cap;
    size_t pos = 0;
    bool overflow = false;

    FixedWriter(uint8_t* b, size_t c) : buf(b), cap(c) {}

    void bytes(const void* p, size_t len) {
        if (overflow || pos + len > cap) {
            overflow = true;
            return;
        }
        memcpy(buf + pos, p, len);
        pos += len;
    }
    void u8(uint8_t v) { bytes(&v, 1); }
    void i32(int32_t v) { bytes(&v, 4); }
    void f64(double v) { bytes(&v, 8); }
    void str(const char* s, size_t len) {
        i32((int32_t) len);
        bytes(s, len);
    }

    size_t mark() { return pos; }
    void patchI32(size_t at, int32_t v) {
        if (!overflow && at + 4 <= cap) memcpy(buf + at, &v, 4);
    }
};

// ---------------------------------------------------------------------------
// Value decoding (Java -> Lua stack). No-throw: on failure sets r.ok = false
// and ensures exactly one value (nil) is still pushed, so the caller's stack
// discipline holds either way.
//
// regIdx: absolute stack index of a table mapping id -> decoded table, used
// to preserve sharing/cycles.
// ---------------------------------------------------------------------------
static int javaTrampoline(lua_State* L);
static int javaContinuation(lua_State* L, int status);

static void decodeValue(lua_State* L, Reader& r, int regIdx, int depth) {
    if (depth > MAX_DEPTH || !lua_checkstack(L, 4)) {
        r.ok = false;
        lua_pushnil(L);
        return;
    }

    uint8_t tag = r.u8();
    if (!r.ok) {
        lua_pushnil(L);
        return;
    }

    switch (tag) {
    case T_NIL:
        lua_pushnil(L);
        break;
    case T_TRUE:
        lua_pushboolean(L, 1);
        break;
    case T_FALSE:
        lua_pushboolean(L, 0);
        break;
    case T_DOUBLE:
        lua_pushnumber(L, r.f64());
        break;
    case T_STRING: {
        int32_t len = r.i32();
        const uint8_t* p = r.bytes(len < 0 ? (size_t) -1 : (size_t) len);
        if (!r.ok) {
            lua_pushnil(L);
            return;
        }
        lua_pushlstring(L, reinterpret_cast<const char*>(p), (size_t) len);
        break;
    }
    case T_TABLE: {
        int32_t id = r.i32();
        int32_t pairs = r.i32();
        if (!r.ok || pairs < 0) {
            r.ok = false;
            lua_pushnil(L);
            return;
        }
        lua_createtable(L, 0, pairs);
        // Remember for future T_REFs.
        lua_pushvalue(L, -1);
        lua_rawseti(L, regIdx, id);
        for (int32_t i = 0; i < pairs && r.ok; i++) {
            decodeValue(L, r, regIdx, depth + 1); // key
            decodeValue(L, r, regIdx, depth + 1); // value
            if (lua_isnil(L, -2) || lua_isnil(L, -1)) {
                lua_pop(L, 2); // nil keys/values cannot be set
            } else {
                lua_rawset(L, -3);
            }
        }
        break;
    }
    case T_REF: {
        int32_t id = r.i32();
        lua_rawgeti(L, regIdx, id);
        break;
    }
    case T_OBJECT: {
        int32_t id = r.i32();
        int32_t handle = r.i32();
        int32_t methods = r.i32();
        if (!r.ok || methods < 0) {
            r.ok = false;
            lua_pushnil(L);
            return;
        }
        lua_createtable(L, 0, methods);
        lua_pushvalue(L, -1);
        lua_rawseti(L, regIdx, id);
        for (int32_t i = 0; i < methods && r.ok; i++) {
            int32_t nameLen = r.i32();
            const uint8_t* name = r.bytes(nameLen < 0 ? (size_t) -1 : (size_t) nameLen);
            if (!r.ok) break;
            lua_pushlightuserdata(L, (void*) (intptr_t) handle);
            lua_pushlightuserdata(L, (void*) (intptr_t) i);
            lua_pushcclosurek(L, javaTrampoline, nullptr, 2, javaContinuation);
            lua_pushlstring(L, reinterpret_cast<const char*>(name), (size_t) nameLen);
            lua_insert(L, -2);
            lua_rawset(L, -3);
        }
        break;
    }
    case T_FUNCTION: {
        int32_t id = r.i32();
        int32_t handle = r.i32();
        lua_pushlightuserdata(L, (void*) (intptr_t) handle);
        lua_pushlightuserdata(L, (void*) (intptr_t) 0);
        lua_pushcclosurek(L, javaTrampoline, nullptr, 2, javaContinuation);
        lua_pushvalue(L, -1);
        lua_rawseti(L, regIdx, id);
        break;
    }
    default:
        r.ok = false;
        lua_pushnil(L);
        break;
    }
}

// Decode all values in a buffer onto the stack (with a temporary registry
// table that is removed afterwards). Returns the number of values, or -1 on a
// malformed buffer (stack is restored).
static int decodeAll(lua_State* L, Reader& r) {
    int base = lua_gettop(L);
    if (!lua_checkstack(L, 4)) return -1;
    lua_createtable(L, 0, 0);
    int regIdx = base + 1;

    int count = 0;
    while (r.pos < r.size && r.ok) {
        decodeValue(L, r, regIdx, 0);
        count++;
    }

    if (!r.ok) {
        lua_settop(L, base);
        return -1;
    }

    lua_remove(L, regIdx); // Results shift down over the registry table.
    return count;
}

// ---------------------------------------------------------------------------
// Value encoding (Lua stack -> Java). No-throw: unsupported/too-deep values
// are encoded as nil.
// ---------------------------------------------------------------------------
struct EncodeContext {
    std::vector<const void*> seen;

    int lookup(const void* p) {
        for (size_t i = 0; i < seen.size(); i++) {
            if (seen[i] == p) return (int) i;
        }
        return -1;
    }
    int add(const void* p) {
        seen.push_back(p);
        return (int) seen.size() - 1;
    }
};

template<typename W>
static void encodeValue(lua_State* L, W& w, EncodeContext& ctx, int idx, int depth) {
    if (depth > MAX_DEPTH || !lua_checkstack(L, 4)) {
        w.u8(T_NIL);
        return;
    }

    idx = lua_absindex(L, idx);
    switch (lua_type(L, idx)) {
    case LUA_TNIL:
    case LUA_TNONE:
        w.u8(T_NIL);
        break;
    case LUA_TBOOLEAN:
        w.u8(lua_toboolean(L, idx) ? T_TRUE : T_FALSE);
        break;
    case LUA_TNUMBER:
        w.u8(T_DOUBLE);
        w.f64(lua_tonumber(L, idx));
        break;
    case LUA_TINTEGER:
        w.u8(T_DOUBLE);
        w.f64((double) lua_tointeger64(L, idx, nullptr));
        break;
    case LUA_TSTRING: {
        size_t len;
        const char* s = lua_tolstring(L, idx, &len);
        w.u8(T_STRING);
        w.str(s, len);
        break;
    }
    case LUA_TTABLE: {
        const void* p = lua_topointer(L, idx);
        int existing = ctx.lookup(p);
        if (existing >= 0) {
            w.u8(T_REF);
            w.i32(existing);
            break;
        }
        int id = ctx.add(p);

        // Include the metatable's __name (if any), so Java-side error
        // messages can use custom type names.
        bool named = false;
        if (lua_getmetatable(L, idx)) {
            lua_rawgetfield(L, -1, "__name");
            if (lua_type(L, -1) == LUA_TSTRING) {
                size_t nameLen;
                const char* name = lua_tolstring(L, -1, &nameLen);
                w.u8(T_NAMED_TABLE);
                w.i32(id);
                w.str(name, nameLen);
                named = true;
            }
            lua_pop(L, 2);
        }
        if (!named) {
            w.u8(T_TABLE);
            w.i32(id);
        }

        // Reserve space for the pair count and patch it afterwards.
        size_t countPos = w.mark();
        w.i32(0);
        int32_t count = 0;

        lua_pushnil(L);
        while (lua_next(L, idx) != 0) {
            encodeValue(L, w, ctx, -2, depth + 1); // key
            encodeValue(L, w, ctx, -1, depth + 1); // value
            count++;
            lua_pop(L, 1);
        }
        w.patchI32(countPos, count);
        break;
    }
    default:
        // Functions, userdata, threads, buffers etc. have no Java equivalent.
        w.u8(T_NIL);
        break;
    }
}

template<typename W>
static void encodeStack(lua_State* L, W& w, int start, int count) {
    // Note: each top-level value gets a fresh context, so sharing is only
    // preserved within a single value, not across arguments. This mirrors
    // CobaltLuaMachine's Lua -> Java conversion.
    for (int i = 0; i < count; i++) {
        EncodeContext ctx;
        encodeValue(L, w, ctx, start + i, 0);
    }
}

// ---------------------------------------------------------------------------
// Java object trampolines
// ---------------------------------------------------------------------------

// Handle a response buffer from LuauMachine. Returns the number of results
// (for return), yields, or raises a Lua error. Runs in a protected context
// (inside lua_resume), so raising errors is safe.
static int handleResponse(lua_State* L, Reader& r) {
    uint8_t status = r.u8();

    switch (status) {
    case INVOKE_ERROR: {
        int32_t level = r.i32();
        int32_t msgLen = r.i32();
        const uint8_t* msg = r.bytes(msgLen < 0 ? (size_t) -1 : (size_t) msgLen);
        if (!r.ok) luaL_error(L, "ccluau: malformed error response");

        if (level > 0) {
            luaL_where(L, (unsigned) level);
            lua_pushlstring(L, reinterpret_cast<const char*>(msg), (size_t) msgLen);
            lua_concat(L, 2);
        } else {
            lua_pushlstring(L, reinterpret_cast<const char*>(msg), (size_t) msgLen);
        }
        lua_error(L);
    }
    case INVOKE_RETURN:
    case INVOKE_YIELD: {
        int count = decodeAll(L, r);
        if (count < 0) luaL_error(L, "ccluau: malformed response payload");
        if (status == INVOKE_RETURN) return count;

        // Drop everything below the yield values (typically the method's own
        // arguments) before suspending: lua_yield protects the slots below it,
        // and on resume javaContinuation encodes the whole stack as the
        // resumption arguments, so anything left here would be prepended to
        // the event arguments passed to ILuaCallback.resume.
        int extra = lua_gettop(L) - count;
        for (int i = 0; i < extra; i++) lua_remove(L, 1);
        return lua_yield(L, count);
    }
    default:
        luaL_error(L, "ccluau: unknown response status %d", (int) status);
    }
    return 0; // unreachable
}

static int handleInvokeResponse(lua_State* L, JNIEnv* env, jbyteArray response) {
    if (response == nullptr) {
        // A pending Java exception: convert to a Lua error.
        env->ExceptionClear();
        luaL_error(L, "Java Exception Thrown");
    }

    jsize len = env->GetArrayLength(response);
    std::vector<uint8_t> data((size_t) len);
    env->GetByteArrayRegion(response, 0, len, reinterpret_cast<jbyte*>(data.data()));
    env->DeleteLocalRef(response);

    Reader r(data.data(), data.size());
    return handleResponse(L, r);
}

// The slow path: arguments and response cross the boundary as byte[].
static int invokeJavaLegacy(lua_State* L, MachineState* m, JNIEnv* env, bool isResume, int nargs) {
    Writer w;
    encodeStack(L, w, 1, nargs);

    jbyteArray args = env->NewByteArray((jsize) w.buf.size());
    if (args == nullptr) {
        env->ExceptionClear();
        luaL_error(L, "ccluau: out of memory");
    }
    env->SetByteArrayRegion(args, 0, (jsize) w.buf.size(), reinterpret_cast<const jbyte*>(w.buf.data()));

    jbyteArray response;
    if (isResume) {
        response = (jbyteArray) env->CallObjectMethod(m->machine, g_resumeMethod, (jlong) (uintptr_t) L, args);
    } else {
        int handle = (int) (intptr_t) lua_tolightuserdata(L, lua_upvalueindex(1));
        int method = (int) (intptr_t) lua_tolightuserdata(L, lua_upvalueindex(2));
        response = (jbyteArray) env->CallObjectMethod(
            m->machine, g_invokeMethod, (jint) handle, (jint) method, (jlong) (uintptr_t) L, args);
    }
    env->DeleteLocalRef(args);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (response != nullptr) env->DeleteLocalRef(response);
        luaL_error(L, "Java Exception Thrown");
    }

    if (isResume) lua_settop(L, 0);
    return handleInvokeResponse(L, env, response);
}

// Encode the arguments, call into Java, and interpret the response. Small
// payloads (the overwhelmingly common case) travel through the machine's
// shared direct buffers with no copies or allocations; anything larger falls
// back to the byte[] path.
static int invokeJava(lua_State* L, bool isResume) {
    MachineState* m = getMachine(L);
    JNIEnv* env = getEnv(m);
    if (env == nullptr) luaL_error(L, "ccluau: no JNI environment");

    int nargs = lua_gettop(L);

    FixedWriter w(m->fastArgs, FAST_BUFFER_SIZE);
    encodeStack(L, w, 1, nargs);
    if (w.overflow) return invokeJavaLegacy(L, m, env, isResume, nargs);

    jint respLen;
    if (isResume) {
        respLen = env->CallIntMethod(m->machine, g_resumeFastMethod, (jlong) (uintptr_t) L, (jint) w.pos);
    } else {
        int handle = (int) (intptr_t) lua_tolightuserdata(L, lua_upvalueindex(1));
        int method = (int) (intptr_t) lua_tolightuserdata(L, lua_upvalueindex(2));
        respLen = env->CallIntMethod(
            m->machine, g_invokeFastMethod, (jint) handle, (jint) method, (jlong) (uintptr_t) L, (jint) w.pos);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        luaL_error(L, "Java Exception Thrown");
    }

    if (isResume) lua_settop(L, 0);

    if (respLen >= 0) {
        Reader r(m->fastResp, (size_t) respLen);
        return handleResponse(L, r);
    }

    // Response too large for the shared buffer: fetch it as a byte[].
    jbyteArray response = (jbyteArray) env->CallObjectMethod(m->machine, g_takeLargeMethod);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (response != nullptr) env->DeleteLocalRef(response);
        luaL_error(L, "Java Exception Thrown");
    }
    return handleInvokeResponse(L, env, response);
}

static int javaTrampoline(lua_State* L) {
    return invokeJava(L, false);
}

// Continuation after a Java-initiated yield: forward the resume arguments to
// the ILuaCallback stack for this thread on the Java side.
static int javaContinuation(lua_State* L, int status) {
    (void) status;
    return invokeJava(L, true);
}

// ---------------------------------------------------------------------------
// load/loadstring implementation
// ---------------------------------------------------------------------------
static int ccluauLoadChunk(lua_State* L, const char* chunk, size_t chunkLen, const char* chunkname, int envIdx) {
    lua_CompileOptions opts = {};
    opts.optimizationLevel = 1;
    opts.debugLevel = 1;

    size_t bytecodeSize = 0;
    char* bytecode = luau_compile(chunk, chunkLen, &opts, &bytecodeSize);
    if (bytecode == nullptr) {
        lua_pushnil(L);
        lua_pushstring(L, "ccluau: compilation failed");
        return 2;
    }

    int status = luau_load(L, chunkname, bytecode, bytecodeSize, envIdx);
    free(bytecode);

    if (status != 0) {
        // Error message is on the stack.
        lua_pushnil(L);
        lua_insert(L, -2);
        return 2;
    }

    if (getMachine(L)->codegen) luau_codegen_compile(L, -1);
    return 1;
}

// load(chunk [, chunkname [, mode [, env]]])
static int ccluauLoad(lua_State* L) {
    size_t modeLen = 0;
    const char* mode = luaL_optlstring(L, 3, "bt", &modeLen);

    // Reject binary chunks: Luau bytecode is not safe to load from untrusted
    // sources, and CC has never guaranteed binary chunk support.
    bool allowText = false;
    for (size_t i = 0; i < modeLen; i++) {
        if (mode[i] == 't') allowText = true;
    }
    if (!allowText) {
        lua_pushnil(L);
        lua_pushstring(L, "attempt to load a binary chunk (binary chunks are not supported)");
        return 2;
    }

    int envIdx = 0;
    if (!lua_isnoneornil(L, 4)) {
        luaL_checktype(L, 4, LUA_TTABLE);
        lua_pushvalue(L, 4);
        envIdx = lua_gettop(L);

        // Emulate Lua 5.2's _ENV: chunks loaded with a custom environment
        // expect to be able to read _ENV. Luau resolves that as a plain global
        // lookup, so define it as a field on the environment (CraftOS already
        // follows this convention, see os.loadAPI in bios.lua).
        lua_pushliteral(L, "_ENV");
        if (lua_rawget(L, envIdx) == LUA_TNIL && lua_getreadonly(L, envIdx) == 0) {
            lua_pop(L, 1);
            lua_pushliteral(L, "_ENV");
            lua_pushvalue(L, envIdx);
            lua_rawset(L, envIdx);
        } else {
            lua_pop(L, 1);
        }
    }

    if (lua_isstring(L, 1)) {
        size_t len;
        const char* chunk = lua_tolstring(L, 1, &len);
        const char* chunkname = luaL_optstring(L, 2, chunk);
        return ccluauLoadChunk(L, chunk, len, chunkname, envIdx);
    }

    if (lua_isfunction(L, 1)) {
        // Accumulate the chunk by repeatedly calling the reader function.
        std::string source;
        const char* chunkname = luaL_optstring(L, 2, "=(load)");
        while (true) {
            lua_pushvalue(L, 1);
            lua_call(L, 0, 1);
            if (lua_isnil(L, -1)) {
                lua_pop(L, 1);
                break;
            }
            if (!lua_isstring(L, -1)) {
                lua_pop(L, 1);
                lua_pushnil(L);
                lua_pushstring(L, "reader function must return a string");
                return 2;
            }
            size_t len;
            const char* part = lua_tolstring(L, -1, &len);
            if (len == 0) {
                lua_pop(L, 1);
                break;
            }
            source.append(part, len);
            lua_pop(L, 1);
        }
        return ccluauLoadChunk(L, source.data(), source.size(), chunkname, envIdx);
    }

    luaL_typeerror(L, 1, "string or function");
}

// loadstring(chunk [, chunkname]): Lua 5.1 compatibility.
static int ccluauLoadstring(lua_State* L) {
    size_t len;
    const char* chunk = luaL_checklstring(L, 1, &len);
    const char* chunkname = luaL_optstring(L, 2, chunk);
    return ccluauLoadChunk(L, chunk, len, chunkname, 0);
}

// ---------------------------------------------------------------------------
// Native terminal (term API)
// ---------------------------------------------------------------------------
static const char* HEX_DIGITS = "0123456789abcdef";

static NativeTerm* getTerm(lua_State* L) {
    NativeTerm* term = getMachine(L)->term;
    if (term == nullptr) luaL_error(L, "Terminal unavailable");
    return term;
}

// The display name of a value's type, following Java-side conventions
// (LuaValues.getType), including custom __name metatable entries.
static const char* displayTypeName(lua_State* L, int idx) {
    int t = lua_type(L, idx);
    if ((t == LUA_TTABLE || t == LUA_TUSERDATA) && lua_getmetatable(L, idx)) {
        lua_rawgetfield(L, -1, "__name");
        if (lua_type(L, -1) == LUA_TSTRING) {
            // The string stays anchored by the metatable, so this is safe to
            // return after popping.
            const char* name = lua_tostring(L, -1);
            lua_pop(L, 2);
            return name;
        }
        lua_pop(L, 2);
    }

    switch (t) {
    case LUA_TNONE:
    case LUA_TNIL:
        return "nil";
    case LUA_TBOOLEAN:
        return "boolean";
    case LUA_TNUMBER:
    case LUA_TINTEGER:
        return "number";
    case LUA_TSTRING:
        return "string";
    case LUA_TTABLE:
        return "table";
    default:
        return luaL_typename(L, idx);
    }
}

// Mirrors IArguments.getFiniteDouble: a strict (finite) number.
static double checkJavaFiniteNumber(lua_State* L, int idx) {
    int t = lua_type(L, idx);
    if (t != LUA_TNUMBER && t != LUA_TINTEGER) {
        luaL_error(L, "bad argument #%d (number expected, got %s)", idx, displayTypeName(L, idx));
    }
    double value = lua_tonumber(L, idx);
    if (!std::isfinite(value)) {
        luaL_error(L, "bad argument #%d (number expected, got %s)", idx, std::isnan(value) ? "nan" : (value > 0 ? "inf" : "-inf"));
    }
    return value;
}

// Mirrors IArguments.getInt: a (finite) number, truncated to int.
static int checkJavaInt(lua_State* L, int idx) {
    return (int) (long long) checkJavaFiniteNumber(L, idx);
}

// Mirrors IArguments.getBoolean: a strict boolean.
static bool checkJavaBoolean(lua_State* L, int idx) {
    if (lua_type(L, idx) != LUA_TBOOLEAN) {
        luaL_error(L, "bad argument #%d (boolean expected, got %s)", idx, displayTypeName(L, idx));
    }
    return lua_toboolean(L, idx) != 0;
}

// Mirrors IArguments.getBytes: a strict string (no number coercion).
static const char* checkJavaString(lua_State* L, int idx, size_t* len) {
    if (lua_type(L, idx) != LUA_TSTRING) {
        luaL_error(L, "bad argument #%d (string expected, got %s)", idx, displayTypeName(L, idx));
    }
    return lua_tolstring(L, idx, len);
}

// Mirrors TermMethods.parseColour: a colour group to a palette index (0-15).
static int parseColour(lua_State* L, int idx) {
    int group = checkJavaInt(L, idx);
    if (group <= 0) luaL_error(L, "Colour out of range");
    // getHighestBit(group) - 1
    int colour = 31;
    while (colour > 0 && !((unsigned) group >> colour)) colour--;
    if (colour > 15) luaL_error(L, "Colour out of range");
    return colour;
}

static int termWrite(lua_State* L) {
    NativeTerm* t = getTerm(L);

    size_t len;
    const char* s;
    if (lua_isnone(L, 1)) {
        s = "nil";
        len = 3;
    } else {
        s = luaL_tolstring(L, 1, &len); // Lua tostring semantics, honours __tostring.
    }

    int x = t->cursorX, y = t->cursorY;
    if (y >= 0 && y < t->height && len > 0) {
        int start = x < 0 ? 0 : x;
        long long endL = (long long) x + (long long) len;
        int end = endL > t->width ? t->width : (int) endL;
        if (start < end) {
            size_t row = (size_t) y * t->width;
            memcpy(t->text.data() + row + start, s + (start - x), (size_t) (end - start));
            memset(t->fg.data() + row + start, HEX_DIGITS[t->curFg], (size_t) (end - start));
            memset(t->bg.data() + row + start, HEX_DIGITS[t->curBg], (size_t) (end - start));
            t->markLine(y);
        }
    }

    if (len > 0) {
        t->cursorX = (int) ((long long) x + (long long) len > INT32_MAX ? INT32_MAX : x + (long long) len);
        t->cursorDirty = true;
    }
    return 0;
}

static int termBlit(lua_State* L) {
    NativeTerm* t = getTerm(L);

    size_t textLen, fgLen, bgLen;
    const char* text = checkJavaString(L, 1, &textLen);
    const char* fg = checkJavaString(L, 2, &fgLen);
    const char* bg = checkJavaString(L, 3, &bgLen);
    if (fgLen != textLen || bgLen != textLen) luaL_error(L, "Arguments must be the same length");

    int x = t->cursorX, y = t->cursorY;
    if (y >= 0 && y < t->height && textLen > 0) {
        int start = x < 0 ? 0 : x;
        long long endL = (long long) x + (long long) textLen;
        int end = endL > t->width ? t->width : (int) endL;
        if (start < end) {
            size_t row = (size_t) y * t->width;
            memcpy(t->text.data() + row + start, text + (start - x), (size_t) (end - start));
            memcpy(t->fg.data() + row + start, fg + (start - x), (size_t) (end - start));
            memcpy(t->bg.data() + row + start, bg + (start - x), (size_t) (end - start));
            t->markLine(y);
        }
    }

    if (textLen > 0) {
        t->cursorX = (int) ((long long) x + (long long) textLen > INT32_MAX ? INT32_MAX : x + (long long) textLen);
        t->cursorDirty = true;
    }
    return 0;
}

static void termFillLine(NativeTerm* t, int y) {
    size_t row = (size_t) y * t->width;
    memset(t->text.data() + row, ' ', (size_t) t->width);
    memset(t->fg.data() + row, HEX_DIGITS[t->curFg], (size_t) t->width);
    memset(t->bg.data() + row, HEX_DIGITS[t->curBg], (size_t) t->width);
}

static int termClear(lua_State* L) {
    NativeTerm* t = getTerm(L);
    for (int y = 0; y < t->height; y++) termFillLine(t, y);
    t->markAllLines();
    return 0;
}

static int termClearLine(lua_State* L) {
    NativeTerm* t = getTerm(L);
    int y = t->cursorY;
    if (y >= 0 && y < t->height) {
        termFillLine(t, y);
        t->markLine(y);
    }
    return 0;
}

static int termScroll(lua_State* L) {
    NativeTerm* t = getTerm(L);
    int diff = checkJavaInt(L, 1);
    if (diff == 0 || t->height == 0) return 0;

    std::vector<uint8_t> newText(t->text.size()), newFg(t->fg.size()), newBg(t->bg.size());
    for (int y = 0; y < t->height; y++) {
        long long oldY = (long long) y + diff;
        size_t row = (size_t) y * t->width;
        if (oldY >= 0 && oldY < t->height) {
            size_t oldRow = (size_t) oldY * t->width;
            memcpy(newText.data() + row, t->text.data() + oldRow, (size_t) t->width);
            memcpy(newFg.data() + row, t->fg.data() + oldRow, (size_t) t->width);
            memcpy(newBg.data() + row, t->bg.data() + oldRow, (size_t) t->width);
        } else {
            memset(newText.data() + row, ' ', (size_t) t->width);
            memset(newFg.data() + row, HEX_DIGITS[t->curFg], (size_t) t->width);
            memset(newBg.data() + row, HEX_DIGITS[t->curBg], (size_t) t->width);
        }
    }
    t->text.swap(newText);
    t->fg.swap(newFg);
    t->bg.swap(newBg);
    t->markAllLines();
    return 0;
}

static int termGetCursorPos(lua_State* L) {
    NativeTerm* t = getTerm(L);
    lua_pushinteger(L, t->cursorX + 1);
    lua_pushinteger(L, t->cursorY + 1);
    return 2;
}

static int termSetCursorPos(lua_State* L) {
    NativeTerm* t = getTerm(L);
    int x = checkJavaInt(L, 1) - 1;
    int y = checkJavaInt(L, 2) - 1;
    if (x != t->cursorX || y != t->cursorY) {
        t->cursorX = x;
        t->cursorY = y;
        t->cursorDirty = true;
    }
    return 0;
}

static int termGetSize(lua_State* L) {
    NativeTerm* t = getTerm(L);
    lua_pushinteger(L, t->width);
    lua_pushinteger(L, t->height);
    return 2;
}

static int termGetCursorBlink(lua_State* L) {
    lua_pushboolean(L, getTerm(L)->blink);
    return 1;
}

static int termSetCursorBlink(lua_State* L) {
    NativeTerm* t = getTerm(L);
    bool blink = checkJavaBoolean(L, 1);
    if (blink != t->blink) {
        t->blink = blink;
        t->cursorDirty = true;
    }
    return 0;
}

static int termGetTextColour(lua_State* L) {
    lua_pushinteger(L, 1 << getTerm(L)->curFg);
    return 1;
}

static int termSetTextColour(lua_State* L) {
    NativeTerm* t = getTerm(L);
    int colour = parseColour(L, 1);
    if (colour != t->curFg) {
        t->curFg = colour;
        t->cursorDirty = true;
    }
    return 0;
}

static int termGetBackgroundColour(lua_State* L) {
    lua_pushinteger(L, 1 << getTerm(L)->curBg);
    return 1;
}

static int termSetBackgroundColour(lua_State* L) {
    NativeTerm* t = getTerm(L);
    int colour = parseColour(L, 1);
    if (colour != t->curBg) {
        t->curBg = colour;
        t->cursorDirty = true;
    }
    return 0;
}

static int termIsColour(lua_State* L) {
    lua_pushboolean(L, getTerm(L)->colour);
    return 1;
}

static int termSetPaletteColour(lua_State* L) {
    NativeTerm* t = getTerm(L);
    int index = 15 - parseColour(L, 1);

    double r, g, b;
    if (lua_gettop(L) == 2) {
        int hex = checkJavaInt(L, 2);
        // Mirrors Palette.decodeRGB8, including its float division.
        r = (double) ((float) ((hex >> 16) & 0xFF) / 255.0f);
        g = (double) ((float) ((hex >> 8) & 0xFF) / 255.0f);
        b = (double) ((float) (hex & 0xFF) / 255.0f);
    } else {
        r = checkJavaFiniteNumber(L, 2);
        g = checkJavaFiniteNumber(L, 3);
        b = checkJavaFiniteNumber(L, 4);
    }

    t->palette[index][0] = r;
    t->palette[index][1] = g;
    t->palette[index][2] = b;
    t->paletteDirty = true;
    return 0;
}

static int termGetPaletteColour(lua_State* L) {
    NativeTerm* t = getTerm(L);
    int index = 15 - parseColour(L, 1);
    lua_pushnumber(L, t->palette[index][0]);
    lua_pushnumber(L, t->palette[index][1]);
    lua_pushnumber(L, t->palette[index][2]);
    return 3;
}

static int termNativePaletteColour(lua_State* L) {
    NativeTerm* t = getTerm(L);
    int index = 15 - parseColour(L, 1);
    lua_pushnumber(L, t->nativePalette[index][0]);
    lua_pushnumber(L, t->nativePalette[index][1]);
    lua_pushnumber(L, t->nativePalette[index][2]);
    return 3;
}

static int termSetResolution(lua_State* L) {
    NativeTerm* t = getTerm(L);
    int scale = checkJavaInt(L, 1);
    if (scale < 1 || scale > 15) luaL_error(L, "Expected scale in range 1-15");

    int newWidth = t->baseWidth * scale;
    int newHeight = t->baseHeight * scale;
    if (newWidth == t->width && newHeight == t->height) return 0;

    // Resize, preserving the overlapping content (as Terminal.resize does).
    size_t size = (size_t) newWidth * newHeight;
    std::vector<uint8_t> newText(size, ' ');
    std::vector<uint8_t> newFg(size, (uint8_t) HEX_DIGITS[t->curFg]);
    std::vector<uint8_t> newBg(size, (uint8_t) HEX_DIGITS[t->curBg]);
    int copyW = newWidth < t->width ? newWidth : t->width;
    int copyH = newHeight < t->height ? newHeight : t->height;
    for (int y = 0; y < copyH; y++) {
        memcpy(newText.data() + (size_t) y * newWidth, t->text.data() + (size_t) y * t->width, (size_t) copyW);
        memcpy(newFg.data() + (size_t) y * newWidth, t->fg.data() + (size_t) y * t->width, (size_t) copyW);
        memcpy(newBg.data() + (size_t) y * newWidth, t->bg.data() + (size_t) y * t->width, (size_t) copyW);
    }
    t->text.swap(newText);
    t->fg.swap(newFg);
    t->bg.swap(newBg);
    t->width = newWidth;
    t->height = newHeight;
    t->lineDirty.assign((size_t) newHeight, 1);
    t->anyLineDirty = true;
    t->resizeDirty = true;
    return 0;
}

static int termGetResolution(lua_State* L) {
    NativeTerm* t = getTerm(L);
    int scale = t->baseWidth > 0 ? t->width / t->baseWidth : 1;
    lua_pushinteger(L, scale < 1 ? 1 : scale);
    return 1;
}

static int termSetMouseCapture(lua_State* L) {
    NativeTerm* t = getTerm(L);
    bool capture = checkJavaBoolean(L, 1);
    if (t->mouseCapture != capture) {
        t->mouseCapture = capture;
        t->mouseCaptureDirty = true;
    }
    return 0;
}

static int termGetMouseCapture(lua_State* L) {
    lua_pushboolean(L, getTerm(L)->mouseCapture);
    return 1;
}

static const luaL_Reg TERM_METHODS[] = {
    { "write", termWrite },
    { "blit", termBlit },
    { "clear", termClear },
    { "clearLine", termClearLine },
    { "scroll", termScroll },
    { "getCursorPos", termGetCursorPos },
    { "setCursorPos", termSetCursorPos },
    { "getSize", termGetSize },
    { "getCursorBlink", termGetCursorBlink },
    { "setCursorBlink", termSetCursorBlink },
    { "getTextColour", termGetTextColour },
    { "getTextColor", termGetTextColour },
    { "setTextColour", termSetTextColour },
    { "setTextColor", termSetTextColour },
    { "getBackgroundColour", termGetBackgroundColour },
    { "getBackgroundColor", termGetBackgroundColour },
    { "setBackgroundColour", termSetBackgroundColour },
    { "setBackgroundColor", termSetBackgroundColour },
    { "isColour", termIsColour },
    { "isColor", termIsColour },
    { "setPaletteColour", termSetPaletteColour },
    { "setPaletteColor", termSetPaletteColour },
    { "getPaletteColour", termGetPaletteColour },
    { "getPaletteColor", termGetPaletteColour },
    { "nativePaletteColour", termNativePaletteColour },
    { "nativePaletteColor", termNativePaletteColour },
    { "setResolution", termSetResolution },
    { "getResolution", termGetResolution },
    { "setMouseCapture", termSetMouseCapture },
    { "getMouseCapture", termGetMouseCapture },
    { nullptr, nullptr },
};

// ---------------------------------------------------------------------------
// Native windows: a C++ implementation of window.create with the exact
// semantics of rom/apis/window.lua (the reference implementation). Windows
// buffer their contents and write through to their parent when visible.
// Parents may be the machine's native terminal or another native window
// (both handled entirely in C++), or any Lua redirect object (monitors,
// mirrors, ...), reached by calling its methods.
//
// Dispatch helpers take `tbl`, the (pseudo-)stack index of the window's own
// table; the parent redirect lives in a field of that table, which both
// anchors the ancestor chain against GC during a call and lets write-through
// recursion locate each level's parent without upvalue tricks.
// ---------------------------------------------------------------------------
struct NativeWindow {
    int x = 1, y = 1;             // 1-based position within the parent
    int width = 0, height = 0;
    bool visible = true;
    bool colour = true;
    int cursorX = 1, cursorY = 1; // 1-based; may be out of bounds
    bool blink = false;
    int curFg = 0, curBg = 15;    // palette indices
    std::string text, fg, bg;     // row-major width*height
    double palette[16][3] = {};

    // Fast-path parents, resolved at creation/reposition. When both are
    // unset, the parent is a Lua redirect table.
    bool parentIsTerm = false;
    NativeWindow* parentWin = nullptr;
};

static const char* NW_SELF_FIELD = "__ccluau_window";
static const char* NW_PARENT_FIELD = "__ccluau_parent";

// Closure upvalues on every window method: 1 = the userdata, 2 = the table.
static NativeWindow* nwSelf(lua_State* L) {
    return static_cast<NativeWindow*>(lua_touserdata(L, lua_upvalueindex(1)));
}

// Call parent.<name>(...) on a Lua-redirect parent. nargs arguments must
// already be pushed; the parent table is read from the window's table.
static void nwCallLuaParent(lua_State* L, int tbl, const char* name, int nargs) {
    lua_getfield(L, tbl, NW_PARENT_FIELD);
    lua_getfield(L, -1, name);
    lua_remove(L, -2);
    lua_insert(L, -nargs - 1);
    lua_call(L, nargs, 0);
}

static void nwFillLine(NativeWindow* w, int line, char fgChar, char bgChar) {
    size_t row = (size_t) (line - 1) * w->width;
    memset(&w->text[row], ' ', (size_t) w->width);
    memset(&w->fg[row], fgChar, (size_t) w->width);
    memset(&w->bg[row], bgChar, (size_t) w->width);
}

static void nwBlitAt(lua_State* L, NativeWindow* w, int tbl, int atX, int atY, const char* txt, const char* fgs, const char* bgs, int len);

// Write a run of cells to the parent at a 1-based parent position.
static void nwParentBlit(lua_State* L, NativeWindow* w, int tbl, int px, int py, const char* txt, const char* fgs, const char* bgs, int len) {
    if (w->parentIsTerm) {
        NativeTerm* t = getMachine(L)->term;
        if (t == nullptr) return;
        int x = px - 1, y = py - 1;
        if (y < 0 || y >= t->height || len <= 0) return;
        int start = x < 0 ? 0 : x;
        long long endL = (long long) x + len;
        int end = endL > t->width ? t->width : (int) endL;
        if (start >= end) return;
        size_t row = (size_t) y * t->width;
        memcpy(t->text.data() + row + start, txt + (start - x), (size_t) (end - start));
        memcpy(t->fg.data() + row + start, fgs + (start - x), (size_t) (end - start));
        memcpy(t->bg.data() + row + start, bgs + (start - x), (size_t) (end - start));
        t->markLine(y);
    } else if (w->parentWin != nullptr) {
        lua_getfield(L, tbl, NW_PARENT_FIELD);
        nwBlitAt(L, w->parentWin, lua_gettop(L), px, py, txt, fgs, bgs, len);
        lua_pop(L, 1);
    } else {
        lua_pushinteger(L, px);
        lua_pushinteger(L, py);
        nwCallLuaParent(L, tbl, "setCursorPos", 2);
        lua_pushlstring(L, txt, (size_t) len);
        lua_pushlstring(L, fgs, (size_t) len);
        lua_pushlstring(L, bgs, (size_t) len);
        nwCallLuaParent(L, tbl, "blit", 3);
    }
}

static void nwParentSetCursor(lua_State* L, NativeWindow* w, int tbl, int px, int py) {
    if (w->parentIsTerm) {
        NativeTerm* t = getMachine(L)->term;
        if (t == nullptr) return;
        if (t->cursorX != px - 1 || t->cursorY != py - 1) {
            t->cursorX = px - 1;
            t->cursorY = py - 1;
            t->cursorDirty = true;
        }
    } else if (w->parentWin != nullptr) {
        NativeWindow* p = w->parentWin;
        p->cursorX = px;
        p->cursorY = py;
        if (p->visible) {
            lua_getfield(L, tbl, NW_PARENT_FIELD);
            int ptbl = lua_gettop(L);
            if (px >= 1 && py >= 1 && px <= p->width && py <= p->height) {
                nwParentSetCursor(L, p, ptbl, p->x + px - 1, p->y + py - 1);
            } else {
                nwParentSetCursor(L, p, ptbl, 0, 0);
            }
            lua_pop(L, 1);
        }
    } else {
        lua_pushinteger(L, px);
        lua_pushinteger(L, py);
        nwCallLuaParent(L, tbl, "setCursorPos", 2);
    }
}

static void nwParentSetTextColour(lua_State* L, NativeWindow* w, int tbl, int idx) {
    if (w->parentIsTerm) {
        NativeTerm* t = getMachine(L)->term;
        if (t == nullptr) return;
        if (t->curFg != idx) {
            t->curFg = idx;
            t->cursorDirty = true;
        }
    } else if (w->parentWin != nullptr) {
        NativeWindow* p = w->parentWin;
        p->curFg = idx;
        if (p->visible) {
            lua_getfield(L, tbl, NW_PARENT_FIELD);
            nwParentSetTextColour(L, p, lua_gettop(L), idx);
            lua_pop(L, 1);
        }
    } else {
        lua_pushnumber(L, (double) (1 << idx));
        nwCallLuaParent(L, tbl, "setTextColor", 1);
    }
}

static void nwParentSetBlink(lua_State* L, NativeWindow* w, int tbl, bool blink) {
    if (w->parentIsTerm) {
        NativeTerm* t = getMachine(L)->term;
        if (t == nullptr) return;
        if (t->blink != blink) {
            t->blink = blink;
            t->cursorDirty = true;
        }
    } else if (w->parentWin != nullptr) {
        NativeWindow* p = w->parentWin;
        p->blink = blink;
        if (p->visible) {
            lua_getfield(L, tbl, NW_PARENT_FIELD);
            nwParentSetBlink(L, p, lua_gettop(L), blink);
            lua_pop(L, 1);
        }
    } else {
        lua_pushboolean(L, blink);
        nwCallLuaParent(L, tbl, "setCursorBlink", 1);
    }
}

static void nwParentSetPalette(lua_State* L, NativeWindow* w, int tbl, int idx, double r, double g, double b) {
    if (w->parentIsTerm) {
        NativeTerm* t = getMachine(L)->term;
        if (t == nullptr) return;
        t->palette[idx][0] = r;
        t->palette[idx][1] = g;
        t->palette[idx][2] = b;
        t->paletteDirty = true;
    } else if (w->parentWin != nullptr) {
        NativeWindow* p = w->parentWin;
        p->palette[idx][0] = r;
        p->palette[idx][1] = g;
        p->palette[idx][2] = b;
        if (p->visible) {
            lua_getfield(L, tbl, NW_PARENT_FIELD);
            nwParentSetPalette(L, p, lua_gettop(L), idx, r, g, b);
            lua_pop(L, 1);
        }
    } else {
        lua_pushnumber(L, (double) (1 << idx));
        lua_pushnumber(L, r);
        lua_pushnumber(L, g);
        lua_pushnumber(L, b);
        nwCallLuaParent(L, tbl, "setPaletteColour", 4);
    }
}

// Write a run of cells into a window's buffer at a 1-based position, without
// touching its cursor, and forward it to its parent if visible. This is the
// write-through path taken when a child window renders into this one.
static void nwBlitAt(lua_State* L, NativeWindow* w, int tbl, int atX, int atY, const char* txt, const char* fgs, const char* bgs, int len) {
    if (atY < 1 || atY > w->height || len <= 0) return;
    int x = atX - 1; // 0-based
    int start = x < 0 ? 0 : x;
    long long endL = (long long) x + len;
    int end = endL > w->width ? w->width : (int) endL;
    if (start >= end) return;
    size_t row = (size_t) (atY - 1) * w->width;
    memcpy(&w->text[row + start], txt + (start - x), (size_t) (end - start));
    memcpy(&w->fg[row + start], fgs + (start - x), (size_t) (end - start));
    memcpy(&w->bg[row + start], bgs + (start - x), (size_t) (end - start));
    if (w->visible) {
        nwParentBlit(L, w, tbl, w->x + start, w->y + atY - 1, &w->text[row + start], &w->fg[row + start], &w->bg[row + start], end - start);
    }
}

static void nwRedrawLine(lua_State* L, NativeWindow* w, int tbl, int line) {
    size_t row = (size_t) (line - 1) * w->width;
    nwParentBlit(L, w, tbl, w->x, w->y + line - 1, &w->text[row], &w->fg[row], &w->bg[row], w->width);
}

static void nwRedrawLines(lua_State* L, NativeWindow* w, int tbl) {
    for (int line = 1; line <= w->height; line++) nwRedrawLine(L, w, tbl, line);
}

static void nwUpdateCursorPos(lua_State* L, NativeWindow* w, int tbl) {
    if (w->cursorX >= 1 && w->cursorY >= 1 && w->cursorX <= w->width && w->cursorY <= w->height) {
        nwParentSetCursor(L, w, tbl, w->x + w->cursorX - 1, w->y + w->cursorY - 1);
    } else {
        nwParentSetCursor(L, w, tbl, 0, 0);
    }
}

// The full redraw: lines, palette, cursor blink/colour/position.
static void nwRedraw(lua_State* L, NativeWindow* w, int tbl) {
    if (!w->visible) return;
    nwRedrawLines(L, w, tbl);
    for (int i = 0; i < 16; i++) {
        nwParentSetPalette(L, w, tbl, i, w->palette[i][0], w->palette[i][1], w->palette[i][2]);
    }
    nwParentSetBlink(L, w, tbl, w->blink);
    nwParentSetTextColour(L, w, tbl, w->curFg);
    nwUpdateCursorPos(L, w, tbl);
}

// The equivalent of window.lua's internalBlit: write at the cursor, advance
// it, and refresh the parent cursor state.
static void nwInternalBlit(lua_State* L, NativeWindow* w, int tbl, const char* txt, const char* fgs, const char* bgs, int len) {
    nwBlitAt(L, w, tbl, w->cursorX, w->cursorY, txt, fgs, bgs, len);
    w->cursorX += len;
    if (w->visible) {
        nwParentSetTextColour(L, w, tbl, w->curFg);
        nwUpdateCursorPos(L, w, tbl);
    }
}

static int nwWrite(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    size_t len;
    const char* s;
    if (lua_isnone(L, 1)) {
        s = "nil";
        len = 3;
    } else {
        s = luaL_tolstring(L, 1, &len);
    }
    std::string fgs(len, HEX_DIGITS[w->curFg]);
    std::string bgs(len, HEX_DIGITS[w->curBg]);
    nwInternalBlit(L, w, lua_upvalueindex(2), s, fgs.data(), bgs.data(), (int) len);
    return 0;
}

static int nwBlit(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    size_t textLen, fgLen, bgLen;
    const char* txt = checkJavaString(L, 1, &textLen);
    const char* fgs = checkJavaString(L, 2, &fgLen);
    const char* bgs = checkJavaString(L, 3, &bgLen);
    if (fgLen != textLen || bgLen != textLen) luaL_error(L, "Arguments must be the same length");

    std::string fgLower(fgs, fgLen), bgLower(bgs, bgLen);
    for (auto& c : fgLower) c = (char) tolower((unsigned char) c);
    for (auto& c : bgLower) c = (char) tolower((unsigned char) c);
    nwInternalBlit(L, w, lua_upvalueindex(2), txt, fgLower.data(), bgLower.data(), (int) textLen);
    return 0;
}

static int nwClear(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    for (int line = 1; line <= w->height; line++) {
        nwFillLine(w, line, HEX_DIGITS[w->curFg], HEX_DIGITS[w->curBg]);
    }
    if (w->visible) {
        int tbl = lua_upvalueindex(2);
        nwRedrawLines(L, w, tbl);
        nwParentSetTextColour(L, w, tbl, w->curFg);
        nwUpdateCursorPos(L, w, tbl);
    }
    return 0;
}

static int nwClearLine(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    if (w->cursorY >= 1 && w->cursorY <= w->height) {
        nwFillLine(w, w->cursorY, HEX_DIGITS[w->curFg], HEX_DIGITS[w->curBg]);
        if (w->visible) {
            int tbl = lua_upvalueindex(2);
            nwRedrawLine(L, w, tbl, w->cursorY);
            nwParentSetTextColour(L, w, tbl, w->curFg);
            nwUpdateCursorPos(L, w, tbl);
        }
    }
    return 0;
}

static int nwGetCursorPos(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    lua_pushinteger(L, w->cursorX);
    lua_pushinteger(L, w->cursorY);
    return 2;
}

static int nwSetCursorPos(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    w->cursorX = (int) std::floor(checkJavaFiniteNumber(L, 1));
    w->cursorY = (int) std::floor(checkJavaFiniteNumber(L, 2));
    if (w->visible) nwUpdateCursorPos(L, w, lua_upvalueindex(2));
    return 0;
}

static int nwSetCursorBlink(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    w->blink = checkJavaBoolean(L, 1);
    if (w->visible) nwParentSetBlink(L, w, lua_upvalueindex(2), w->blink);
    return 0;
}

static int nwGetCursorBlink(lua_State* L) {
    lua_pushboolean(L, nwSelf(L)->blink);
    return 1;
}

static int nwIsColour(lua_State* L) {
    lua_pushboolean(L, nwSelf(L)->colour);
    return 1;
}

static int nwSetTextColour(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    w->curFg = parseColour(L, 1);
    if (w->visible) nwParentSetTextColour(L, w, lua_upvalueindex(2), w->curFg);
    return 0;
}

static int nwSetBackgroundColour(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    w->curBg = parseColour(L, 1);
    return 0;
}

static int nwGetTextColour(lua_State* L) {
    lua_pushnumber(L, (double) (1 << nwSelf(L)->curFg));
    return 1;
}

static int nwGetBackgroundColour(lua_State* L) {
    lua_pushnumber(L, (double) (1 << nwSelf(L)->curBg));
    return 1;
}

static int nwSetPaletteColour(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    int idx = parseColour(L, 1);
    double r, g, b;
    if (lua_type(L, 2) == LUA_TNUMBER && lua_isnoneornil(L, 3) && lua_isnoneornil(L, 4)) {
        // A packed 24-bit RGB value (colours.unpackRGB).
        int rgb = checkJavaInt(L, 2);
        r = ((rgb >> 16) & 0xFF) / 255.0;
        g = ((rgb >> 8) & 0xFF) / 255.0;
        b = (rgb & 0xFF) / 255.0;
    } else {
        r = checkJavaFiniteNumber(L, 2);
        g = checkJavaFiniteNumber(L, 3);
        b = checkJavaFiniteNumber(L, 4);
    }
    w->palette[idx][0] = r;
    w->palette[idx][1] = g;
    w->palette[idx][2] = b;
    if (w->visible) nwParentSetPalette(L, w, lua_upvalueindex(2), idx, r, g, b);
    return 0;
}

static int nwGetPaletteColour(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    int idx = parseColour(L, 1);
    lua_pushnumber(L, w->palette[idx][0]);
    lua_pushnumber(L, w->palette[idx][1]);
    lua_pushnumber(L, w->palette[idx][2]);
    return 3;
}

static int nwGetSize(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    lua_pushinteger(L, w->width);
    lua_pushinteger(L, w->height);
    return 2;
}

static int nwScroll(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    int diff = checkJavaInt(L, 1);
    if (diff == 0 || w->height == 0) return 0;

    std::string newText(w->text.size(), ' ');
    std::string newFg(w->fg.size(), HEX_DIGITS[w->curFg]);
    std::string newBg(w->bg.size(), HEX_DIGITS[w->curBg]);
    for (int line = 0; line < w->height; line++) {
        long long from = (long long) line + diff;
        if (from >= 0 && from < w->height) {
            size_t row = (size_t) line * w->width, fromRow = (size_t) from * w->width;
            memcpy(&newText[row], &w->text[fromRow], (size_t) w->width);
            memcpy(&newFg[row], &w->fg[fromRow], (size_t) w->width);
            memcpy(&newBg[row], &w->bg[fromRow], (size_t) w->width);
        }
    }
    w->text.swap(newText);
    w->fg.swap(newFg);
    w->bg.swap(newBg);
    if (w->visible) {
        int tbl = lua_upvalueindex(2);
        nwRedrawLines(L, w, tbl);
        nwParentSetTextColour(L, w, tbl, w->curFg);
        nwUpdateCursorPos(L, w, tbl);
    }
    return 0;
}

static int nwGetLine(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    int y = (int) std::floor(checkJavaFiniteNumber(L, 1));
    if (y < 1 || y > w->height) luaL_error(L, "Line is out of range.");
    size_t row = (size_t) (y - 1) * w->width;
    lua_pushlstring(L, &w->text[row], (size_t) w->width);
    lua_pushlstring(L, &w->fg[row], (size_t) w->width);
    lua_pushlstring(L, &w->bg[row], (size_t) w->width);
    return 3;
}

static int nwSetVisible(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    bool visible = checkJavaBoolean(L, 1);
    if (w->visible != visible) {
        w->visible = visible;
        if (visible) nwRedraw(L, w, lua_upvalueindex(2));
    }
    return 0;
}

static int nwIsVisible(lua_State* L) {
    lua_pushboolean(L, nwSelf(L)->visible);
    return 1;
}

static int nwRedrawMethod(lua_State* L) {
    nwRedraw(L, nwSelf(L), lua_upvalueindex(2));
    return 0;
}

static int nwRestoreCursor(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    if (w->visible) {
        int tbl = lua_upvalueindex(2);
        nwParentSetBlink(L, w, tbl, w->blink);
        nwParentSetTextColour(L, w, tbl, w->curFg);
        nwUpdateCursorPos(L, w, tbl);
    }
    return 0;
}

static int nwGetPosition(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    lua_pushinteger(L, w->x);
    lua_pushinteger(L, w->y);
    return 2;
}

// Resolve the fast-path parent pointers for a parent table at the given index.
static void nwResolveParent(lua_State* L, NativeWindow* w, int parentIdx) {
    w->parentIsTerm = false;
    w->parentWin = nullptr;
    lua_getfield(L, parentIdx, "__ccluau_term");
    if (lua_toboolean(L, -1)) w->parentIsTerm = true;
    lua_pop(L, 1);
    if (!w->parentIsTerm) {
        lua_getfield(L, parentIdx, NW_SELF_FIELD);
        if (lua_isuserdata(L, -1)) w->parentWin = static_cast<NativeWindow*>(lua_touserdata(L, -1));
        lua_pop(L, 1);
    }
}

static int nwReposition(lua_State* L) {
    NativeWindow* w = nwSelf(L);
    int newX = (int) std::floor(checkJavaFiniteNumber(L, 1));
    int newY = (int) std::floor(checkJavaFiniteNumber(L, 2));

    bool resize = !lua_isnoneornil(L, 3) || !lua_isnoneornil(L, 4);
    int newWidth = w->width, newHeight = w->height;
    if (resize) {
        newWidth = checkJavaInt(L, 3);
        newHeight = checkJavaInt(L, 4);
        if (newWidth < 0) newWidth = 0;
        if (newHeight < 0) newHeight = 0;
    }
    if (!lua_isnoneornil(L, 5)) {
        if (lua_type(L, 5) != LUA_TTABLE) {
            luaL_error(L, "bad argument #5 (table expected, got %s)", displayTypeName(L, 5));
        }
        nwResolveParent(L, w, 5);
        lua_pushvalue(L, 5);
        lua_setfield(L, lua_upvalueindex(2), NW_PARENT_FIELD);
    }

    w->x = newX;
    w->y = newY;

    if (resize && (newWidth != w->width || newHeight != w->height)) {
        size_t newSize = (size_t) newWidth * newHeight;
        std::string newText(newSize, ' ');
        std::string newFg(newSize, HEX_DIGITS[w->curFg]);
        std::string newBg(newSize, HEX_DIGITS[w->curBg]);
        int copyW = newWidth < w->width ? newWidth : w->width;
        int copyH = newHeight < w->height ? newHeight : w->height;
        for (int line = 0; line < copyH; line++) {
            memcpy(&newText[(size_t) line * newWidth], &w->text[(size_t) line * w->width], (size_t) copyW);
            memcpy(&newFg[(size_t) line * newWidth], &w->fg[(size_t) line * w->width], (size_t) copyW);
            memcpy(&newBg[(size_t) line * newWidth], &w->bg[(size_t) line * w->width], (size_t) copyW);
        }
        w->text.swap(newText);
        w->fg.swap(newFg);
        w->bg.swap(newBg);
        w->width = newWidth;
        w->height = newHeight;
    }

    if (w->visible) nwRedraw(L, w, lua_upvalueindex(2));
    return 0;
}

static const luaL_Reg NW_METHODS[] = {
    { "write", nwWrite },
    { "blit", nwBlit },
    { "clear", nwClear },
    { "clearLine", nwClearLine },
    { "getCursorPos", nwGetCursorPos },
    { "setCursorPos", nwSetCursorPos },
    { "setCursorBlink", nwSetCursorBlink },
    { "getCursorBlink", nwGetCursorBlink },
    { "isColor", nwIsColour },
    { "isColour", nwIsColour },
    { "setTextColor", nwSetTextColour },
    { "setTextColour", nwSetTextColour },
    { "setBackgroundColor", nwSetBackgroundColour },
    { "setBackgroundColour", nwSetBackgroundColour },
    { "getTextColor", nwGetTextColour },
    { "getTextColour", nwGetTextColour },
    { "getBackgroundColor", nwGetBackgroundColour },
    { "getBackgroundColour", nwGetBackgroundColour },
    { "setPaletteColor", nwSetPaletteColour },
    { "setPaletteColour", nwSetPaletteColour },
    { "getPaletteColor", nwGetPaletteColour },
    { "getPaletteColour", nwGetPaletteColour },
    { "getSize", nwGetSize },
    { "scroll", nwScroll },
    { "getLine", nwGetLine },
    { "setVisible", nwSetVisible },
    { "isVisible", nwIsVisible },
    { "redraw", nwRedrawMethod },
    { "restoreCursor", nwRestoreCursor },
    { "getPosition", nwGetPosition },
    { "reposition", nwReposition },
    { nullptr, nullptr },
};

// window.create(parent, x, y, width, height [, visible])
static int nwCreate(lua_State* L) {
    if (lua_type(L, 1) != LUA_TTABLE) {
        luaL_error(L, "bad argument #1 (table expected, got %s)", displayTypeName(L, 1));
    }
    int x = (int) std::floor(checkJavaFiniteNumber(L, 2));
    int y = (int) std::floor(checkJavaFiniteNumber(L, 3));
    int width = checkJavaInt(L, 4);
    int height = checkJavaInt(L, 5);
    bool visible = true;
    if (!lua_isnoneornil(L, 6)) visible = checkJavaBoolean(L, 6);
    if (width < 0) width = 0;
    if (height < 0) height = 0;

    lua_getglobal(L, "term");
    if (lua_rawequal(L, 1, -1)) {
        luaL_error(L, "term is not a recommended window parent, try term.current() instead");
    }
    lua_pop(L, 1);

    // The userdata holding the window state; its destructor releases the buffers.
    auto* w = static_cast<NativeWindow*>(lua_newuserdatadtor(L, sizeof(NativeWindow), [](void* ud) {
        static_cast<NativeWindow*>(ud)->~NativeWindow();
    }));
    new (w) NativeWindow();
    int udIdx = lua_gettop(L);

    w->x = x;
    w->y = y;
    w->width = width;
    w->height = height;
    w->visible = visible;
    size_t size = (size_t) width * height;
    w->text.assign(size, ' ');
    w->fg.assign(size, '0');
    w->bg.assign(size, 'f');

    nwResolveParent(L, w, 1);

    // Read the initial palette and colour support from the parent.
    if (w->parentIsTerm) {
        NativeTerm* t = getMachine(L)->term;
        if (t != nullptr) {
            memcpy(w->palette, t->palette, sizeof(w->palette));
            w->colour = t->colour;
        }
    } else if (w->parentWin != nullptr) {
        memcpy(w->palette, w->parentWin->palette, sizeof(w->palette));
        w->colour = w->parentWin->colour;
    } else {
        for (int i = 0; i < 16; i++) {
            lua_getfield(L, 1, "getPaletteColour");
            lua_pushnumber(L, (double) (1 << i));
            lua_call(L, 1, 3);
            w->palette[i][0] = lua_tonumber(L, -3);
            w->palette[i][1] = lua_tonumber(L, -2);
            w->palette[i][2] = lua_tonumber(L, -1);
            lua_pop(L, 3);
        }
        lua_getfield(L, 1, "isColour");
        lua_call(L, 0, 1);
        w->colour = lua_toboolean(L, -1) != 0;
        lua_pop(L, 1);
    }

    // Build the window table: method closures with (userdata, table) upvalues,
    // plus hidden fields tying the parent and state lifetimes to the table.
    lua_createtable(L, 0, 36);
    int tblIdx = lua_gettop(L);

    lua_pushvalue(L, udIdx);
    lua_setfield(L, tblIdx, NW_SELF_FIELD);
    lua_pushvalue(L, 1);
    lua_setfield(L, tblIdx, NW_PARENT_FIELD);

    for (const luaL_Reg* reg = NW_METHODS; reg->name != nullptr; reg++) {
        lua_pushvalue(L, udIdx);
        lua_pushvalue(L, tblIdx);
        lua_pushcclosure(L, reg->func, reg->name, 2);
        lua_setfield(L, tblIdx, reg->name);
    }

    if (visible) nwRedraw(L, w, tblIdx);
    return 1;
}

// ---------------------------------------------------------------------------
// Native pixel graphics: fast paths for mineos.gfx and mineos.vterm. These
// rasterise the CraftOS font (registered from Lua via setFont) directly into
// a native window or the machine terminal, replacing per-glyph Lua loops.
// Each function returns false when the target is not native, letting the Lua
// side fall back to its reference implementation.
// ---------------------------------------------------------------------------

// Resolve a term-like target table at idx into the machine terminal or a
// native window. Returns false for foreign redirects.
static bool gfxResolveTarget(lua_State* L, int idx, NativeTerm** term, NativeWindow** win) {
    *term = nullptr;
    *win = nullptr;
    if (lua_type(L, idx) != LUA_TTABLE) return false;
    lua_getfield(L, idx, "__ccluau_term");
    bool isTerm = lua_toboolean(L, -1) != 0;
    lua_pop(L, 1);
    if (isTerm) {
        *term = getMachine(L)->term;
        return *term != nullptr;
    }
    lua_getfield(L, idx, NW_SELF_FIELD);
    if (lua_isuserdata(L, -1)) *win = static_cast<NativeWindow*>(lua_touserdata(L, -1));
    lua_pop(L, 1);
    return *win != nullptr;
}

// Write a row of cells to the resolved target at a 1-based position.
static void gfxBlitRow(lua_State* L, int targetIdx, NativeTerm* t, NativeWindow* w, int x, int y, const char* txt, const char* fgs, const char* bgs, int len) {
    if (t != nullptr) {
        int x0 = x - 1, y0 = y - 1;
        if (y0 < 0 || y0 >= t->height || len <= 0) return;
        int start = x0 < 0 ? 0 : x0;
        long long endL = (long long) x0 + len;
        int end = endL > t->width ? t->width : (int) endL;
        if (start >= end) return;
        size_t row = (size_t) y0 * t->width;
        memcpy(t->text.data() + row + start, txt + (start - x0), (size_t) (end - start));
        memcpy(t->fg.data() + row + start, fgs + (start - x0), (size_t) (end - start));
        memcpy(t->bg.data() + row + start, bgs + (start - x0), (size_t) (end - start));
        t->markLine(y0);
    } else {
        nwBlitAt(L, w, targetIdx, x, y, txt, fgs, bgs, len);
    }
}

// _CC_NATIVE_GFX.setFont(data, glyphWidth, glyphHeight)
static int gfxSetFont(lua_State* L) {
    size_t len;
    const char* data = checkJavaString(L, 1, &len);
    int glyphW = checkJavaInt(L, 2);
    int glyphH = checkJavaInt(L, 3);
    if (glyphW < 1 || glyphW > 8 || glyphH < 1 || glyphH > 32 || len != (size_t) 256 * glyphH) {
        luaL_error(L, "Invalid font data");
    }
    MachineState* m = getMachine(L);
    m->fontData.assign(data, len);
    m->fontWidth = glyphW;
    m->fontHeight = glyphH;
    return 0;
}

// _CC_NATIVE_GFX.fillRect(target, x, y, width, height, colourHex) -> boolean
static int gfxFillRect(lua_State* L) {
    NativeTerm* t;
    NativeWindow* w;
    if (!gfxResolveTarget(L, 1, &t, &w)) {
        lua_pushboolean(L, 0);
        return 1;
    }
    int x = checkJavaInt(L, 2), y = checkJavaInt(L, 3);
    int width = checkJavaInt(L, 4), height = checkJavaInt(L, 5);
    size_t hexLen;
    const char* hex = checkJavaString(L, 6, &hexLen);
    if (hexLen < 1) luaL_error(L, "bad argument #6 (expected a colour character)");
    if (width > 0 && height > 0) {
        std::string spaces((size_t) width, ' ');
        std::string colour((size_t) width, hex[0]);
        for (int row = 0; row < height; row++) {
            gfxBlitRow(L, 1, t, w, x, y + row, spaces.data(), colour.data(), colour.data(), width);
        }
    }
    lua_pushboolean(L, 1);
    return 1;
}

// _CC_NATIVE_GFX.drawText(target, x, y, text, fgHex, bgHex, pxW, pxH) -> false | widthInCells
static int gfxDrawText(lua_State* L) {
    NativeTerm* t;
    NativeWindow* w;
    if (!gfxResolveTarget(L, 1, &t, &w)) {
        lua_pushboolean(L, 0);
        return 1;
    }
    MachineState* m = getMachine(L);
    if (m->fontData.empty()) luaL_error(L, "No font registered");

    int x = checkJavaInt(L, 2), y = checkJavaInt(L, 3);
    size_t textLen, fgLen, bgLen;
    const char* text = checkJavaString(L, 4, &textLen);
    const char* fgHex = checkJavaString(L, 5, &fgLen);
    const char* bgHex = checkJavaString(L, 6, &bgLen);
    if (fgLen < 1 || bgLen < 1) luaL_error(L, "bad argument (expected a colour character)");
    int pxW = checkJavaInt(L, 7), pxH = checkJavaInt(L, 8);
    if (pxW < 1 || pxW > 64 || pxH < 1 || pxH > 64) luaL_error(L, "Pixel size out of range");

    int glyphW = m->fontWidth, glyphH = m->fontHeight;
    size_t rowLen = textLen * glyphW * pxW;
    std::string spaces(rowLen, ' ');
    std::string colours(rowLen, bgHex[0]);

    for (int fontRow = 0; fontRow < glyphH; fontRow++) {
        size_t at = 0;
        for (size_t i = 0; i < textLen; i++) {
            int mask = m->fontData[(size_t) (unsigned char) text[i] * glyphH + fontRow] - 35;
            for (int fx = 0; fx < glyphW; fx++) {
                char colour = (mask >> fx) & 1 ? fgHex[0] : bgHex[0];
                for (int sub = 0; sub < pxW; sub++) colours[at++] = colour;
            }
        }
        for (int sub = 0; sub < pxH; sub++) {
            gfxBlitRow(L, 1, t, w, x, y + fontRow * pxH + sub, spaces.data(), colours.data(), colours.data(), (int) rowLen);
        }
    }

    lua_pushinteger(L, (int) (textLen * glyphW * pxW));
    return 1;
}

// _CC_NATIVE_GFX.drawGlyphRow(target, x, y, text, fgStr, bgStr, pxW, pxH) -> boolean
//
// Renders a row of terminal cells as font pixels, with per-cell colours: the
// mineos.vterm hot path.
static int gfxDrawGlyphRow(lua_State* L) {
    NativeTerm* t;
    NativeWindow* w;
    if (!gfxResolveTarget(L, 1, &t, &w)) {
        lua_pushboolean(L, 0);
        return 1;
    }
    MachineState* m = getMachine(L);
    if (m->fontData.empty()) luaL_error(L, "No font registered");

    int x = checkJavaInt(L, 2), y = checkJavaInt(L, 3);
    size_t textLen, fgLen, bgLen;
    const char* text = checkJavaString(L, 4, &textLen);
    const char* fgs = checkJavaString(L, 5, &fgLen);
    const char* bgs = checkJavaString(L, 6, &bgLen);
    if (fgLen != textLen || bgLen != textLen) luaL_error(L, "Arguments must be the same length");
    int pxW = checkJavaInt(L, 7), pxH = checkJavaInt(L, 8);
    if (pxW < 1 || pxW > 64 || pxH < 1 || pxH > 64) luaL_error(L, "Pixel size out of range");

    int glyphW = m->fontWidth, glyphH = m->fontHeight;
    size_t rowLen = textLen * glyphW * pxW;
    std::string spaces(rowLen, ' ');
    std::string colours(rowLen, 'f');

    for (int fontRow = 0; fontRow < glyphH; fontRow++) {
        size_t at = 0;
        for (size_t i = 0; i < textLen; i++) {
            int mask = m->fontData[(size_t) (unsigned char) text[i] * glyphH + fontRow] - 35;
            for (int fx = 0; fx < glyphW; fx++) {
                char colour = (mask >> fx) & 1 ? fgs[i] : bgs[i];
                for (int sub = 0; sub < pxW; sub++) colours[at++] = colour;
            }
        }
        for (int sub = 0; sub < pxH; sub++) {
            gfxBlitRow(L, 1, t, w, x, y + fontRow * pxH + sub, spaces.data(), colours.data(), colours.data(), (int) rowLen);
        }
    }

    lua_pushboolean(L, 1);
    return 1;
}

static const luaL_Reg GFX_METHODS[] = {
    { "setFont", gfxSetFont },
    { "fillRect", gfxFillRect },
    { "drawText", gfxDrawText },
    { "drawGlyphRow", gfxDrawGlyphRow },
    { nullptr, nullptr },
};

// ---------------------------------------------------------------------------
// Native textutils.serialize: identical output to the reference Lua
// implementation (rom/apis/textutils.lua serialize_impl), but built with
// linear appends rather than quadratic string concatenation. Errors unwind
// as C++ exceptions (LUA_USE_LONGJMP=0), so the std::string state is safe.
// ---------------------------------------------------------------------------

static bool serializeIsKeyword(const char* s, size_t len) {
    static const char* KEYWORDS[] = {
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function",
        "if", "in", "local", "nil", "not", "or", "repeat", "return", "then",
        "true", "until", "while", nullptr,
    };
    for (int i = 0; KEYWORDS[i] != nullptr; i++) {
        if (strlen(KEYWORDS[i]) == len && memcmp(KEYWORDS[i], s, len) == 0) return true;
    }
    return false;
}

static bool serializeIsIdentifier(const char* s, size_t len) {
    if (len == 0) return false;
    if (!isalpha((unsigned char) s[0]) && s[0] != '_') return false;
    for (size_t i = 1; i < len; i++) {
        if (!isalnum((unsigned char) s[i]) && s[i] != '_') return false;
    }
    return !serializeIsKeyword(s, len);
}

struct SerializeState {
    lua_State* L;
    std::string out;
    bool compact;
    bool allowRepetitions;
    // Table pointer -> true while being serialised, false once completed
    // (matching the tracking table of the Lua implementation).
    std::map<const void*, bool> tracking;
    int formatIdx; // Absolute stack index of string.format.
};

// Serialize the value at the given absolute stack index.
static void serializeValue(SerializeState& s, int idx, const std::string& indent, int depth) {
    lua_State* L = s.L;
    int type = lua_type(L, idx);

    switch (type) {
    case LUA_TTABLE: {
        if (depth > 512) luaL_error(L, "Cannot serialize table with recursive entries");
        luaL_checkstack(L, 6, "table is too deeply nested");

        const void* ptr = lua_topointer(L, idx);
        auto existing = s.tracking.find(ptr);
        if (existing != s.tracking.end()) {
            if (!existing->second) luaL_error(L, "Cannot serialize table with repeated entries");
            luaL_error(L, "Cannot serialize table with recursive entries");
        }
        s.tracking[ptr] = true;

        // Empty tables are simple.
        lua_pushnil(L);
        if (lua_next(L, idx) == 0) {
            s.out += "{}";
        } else {
            lua_pop(L, 2);

            std::string subIndent = s.compact ? "" : indent + "  ";
            const char* open = s.compact ? "{" : "{\n";
            const char* openKey = s.compact ? "[" : "[ ";
            const char* closeKey = s.compact ? "]=" : " ] = ";
            const char* equal = s.compact ? "=" : " = ";
            const char* comma = s.compact ? "," : ",\n";

            s.out += open;

            // The array part (an ipairs walk ignoring metamethods).
            long long lastArray = 0;
            for (long long i = 1; ; i++) {
                lua_rawgeti(L, idx, (int) i);
                if (lua_isnil(L, -1)) {
                    lua_pop(L, 1);
                    break;
                }
                lastArray = i;
                s.out += subIndent;
                serializeValue(s, lua_gettop(L), subIndent, depth + 1);
                s.out += comma;
                lua_pop(L, 1);
            }

            // The remaining keys.
            lua_pushnil(L);
            while (lua_next(L, idx) != 0) {
                bool seen = false;
                if (lua_type(L, -2) == LUA_TNUMBER || lua_type(L, -2) == LUA_TINTEGER) {
                    double key = lua_tonumber(L, -2);
                    seen = key == std::floor(key) && key >= 1 && key <= (double) lastArray;
                }
                if (!seen) {
                    s.out += subIndent;
                    size_t keyLen;
                    const char* key = lua_type(L, -2) == LUA_TSTRING ? lua_tolstring(L, -2, &keyLen) : nullptr;
                    if (key != nullptr && serializeIsIdentifier(key, keyLen)) {
                        s.out.append(key, keyLen);
                        s.out += equal;
                        serializeValue(s, lua_gettop(L), subIndent, depth + 1);
                    } else {
                        s.out += openKey;
                        serializeValue(s, lua_gettop(L) - 1, subIndent, depth + 1);
                        s.out += closeKey;
                        serializeValue(s, lua_gettop(L), subIndent, depth + 1);
                    }
                    s.out += comma;
                }
                lua_pop(L, 1);
            }

            s.out += indent;
            s.out += "}";
        }

        if (s.allowRepetitions) {
            s.tracking.erase(ptr);
        } else {
            s.tracking[ptr] = false;
        }
        break;
    }

    case LUA_TSTRING: {
        // string.format("%q", value): quoting must match the runtime exactly.
        lua_pushvalue(L, s.formatIdx);
        lua_pushliteral(L, "%q");
        lua_pushvalue(L, idx);
        lua_call(L, 2, 1);
        size_t len;
        const char* quoted = lua_tolstring(L, -1, &len);
        s.out.append(quoted, len);
        lua_pop(L, 1);
        break;
    }

    case LUA_TNUMBER:
    case LUA_TINTEGER: {
        double value = lua_tonumber(L, idx);
        if (value != value) {
            s.out += "0/0";
        } else if (value == HUGE_VAL) {
            s.out += "1/0";
        } else if (value == -HUGE_VAL) {
            s.out += "-1/0";
        } else {
            lua_pushvalue(L, idx);
            size_t len;
            const char* text = lua_tolstring(L, -1, &len);
            s.out.append(text, len);
            lua_pop(L, 1);
        }
        break;
    }

    case LUA_TBOOLEAN:
        s.out += lua_toboolean(L, idx) ? "true" : "false";
        break;

    case LUA_TNIL:
        s.out += "nil";
        break;

    default:
        luaL_error(L, "Cannot serialize type %s", luaL_typename(L, idx));
    }
}

// _CC_NATIVE_TEXTUTILS.serialize(value, compact, allowRepetitions) -> string
static int textutilsSerialize(lua_State* L) {
    lua_settop(L, 3);

    SerializeState s{};
    s.L = L;
    s.compact = lua_toboolean(L, 2) != 0;
    s.allowRepetitions = lua_toboolean(L, 3) != 0;
    s.out.reserve(64);

    lua_getglobal(L, "string");
    lua_getfield(L, -1, "format");
    lua_remove(L, -2);
    s.formatIdx = lua_gettop(L);

    serializeValue(s, 1, "", 0);

    lua_pushlstring(L, s.out.data(), s.out.size());
    return 1;
}

// ---------------------------------------------------------------------------
// Native redstone (redstone/rs API)
// ---------------------------------------------------------------------------
static const char* SIDE_NAMES[6] = { "bottom", "top", "back", "front", "right", "left" };

static NativeRedstone* getRedstone(lua_State* L) {
    NativeRedstone* redstone = getMachine(L)->redstone;
    if (redstone == nullptr) luaL_error(L, "Redstone unavailable");
    return redstone;
}

// Mirrors IArguments.getEnum(index, ComputerSide.class): a case-insensitive
// side name.
static int parseSide(lua_State* L, int idx) {
    if (lua_type(L, idx) != LUA_TSTRING) {
        luaL_error(L, "bad argument #%d (string expected, got %s)", idx, displayTypeName(L, idx));
    }

    size_t len;
    const char* s = lua_tolstring(L, idx, &len);
    if (len <= 6) {
        char lower[8];
        for (size_t i = 0; i < len; i++) lower[i] = (char) tolower((unsigned char) s[i]);
        lower[len] = '\0';
        for (int i = 0; i < 6; i++) {
            if (strcmp(lower, SIDE_NAMES[i]) == 0) return i;
        }
    }
    luaL_error(L, "bad argument #%d (unknown option %s)", idx, s);
    return 0; // unreachable
}

static int rsGetSides(lua_State* L) {
    lua_createtable(L, 6, 0);
    for (int i = 0; i < 6; i++) {
        lua_pushstring(L, SIDE_NAMES[i]);
        lua_rawseti(L, -2, i + 1);
    }
    return 1;
}

static int rsGetInput(lua_State* L) {
    NativeRedstone* r = getRedstone(L);
    lua_pushboolean(L, r->input[parseSide(L, 1)] > 0);
    return 1;
}

static int rsGetAnalogInput(lua_State* L) {
    NativeRedstone* r = getRedstone(L);
    lua_pushinteger(L, r->input[parseSide(L, 1)]);
    return 1;
}

static int rsGetOutput(lua_State* L) {
    NativeRedstone* r = getRedstone(L);
    lua_pushboolean(L, r->output[parseSide(L, 1)] > 0);
    return 1;
}

static int rsGetAnalogOutput(lua_State* L) {
    NativeRedstone* r = getRedstone(L);
    lua_pushinteger(L, r->output[parseSide(L, 1)]);
    return 1;
}

static int rsSetOutput(lua_State* L) {
    NativeRedstone* r = getRedstone(L);
    int side = parseSide(L, 1);
    int value = checkJavaBoolean(L, 2) ? 15 : 0;
    if (r->output[side] != value) {
        r->output[side] = value;
        r->outputDirty = true;
    }
    return 0;
}

static int rsSetAnalogOutput(lua_State* L) {
    NativeRedstone* r = getRedstone(L);
    int side = parseSide(L, 1);
    int value = checkJavaInt(L, 2);
    if (value < 0 || value > 15) luaL_error(L, "Expected number in range 0-15");
    if (r->output[side] != value) {
        r->output[side] = value;
        r->outputDirty = true;
    }
    return 0;
}

static int rsGetBundledInput(lua_State* L) {
    NativeRedstone* r = getRedstone(L);
    lua_pushinteger(L, r->bundledInput[parseSide(L, 1)]);
    return 1;
}

static int rsGetBundledOutput(lua_State* L) {
    NativeRedstone* r = getRedstone(L);
    lua_pushinteger(L, r->bundledOutput[parseSide(L, 1)]);
    return 1;
}

static int rsSetBundledOutput(lua_State* L) {
    NativeRedstone* r = getRedstone(L);
    int side = parseSide(L, 1);
    int value = checkJavaInt(L, 2);
    if (r->bundledOutput[side] != value) {
        r->bundledOutput[side] = value;
        r->outputDirty = true;
    }
    return 0;
}

static int rsTestBundledInput(lua_State* L) {
    NativeRedstone* r = getRedstone(L);
    int side = parseSide(L, 1);
    int mask = checkJavaInt(L, 2);
    lua_pushboolean(L, (r->bundledInput[side] & mask) == mask);
    return 1;
}

static const luaL_Reg REDSTONE_METHODS[] = {
    { "getSides", rsGetSides },
    { "getInput", rsGetInput },
    { "getAnalogInput", rsGetAnalogInput },
    { "getAnalogueInput", rsGetAnalogInput },
    { "getOutput", rsGetOutput },
    { "getAnalogOutput", rsGetAnalogOutput },
    { "getAnalogueOutput", rsGetAnalogOutput },
    { "setOutput", rsSetOutput },
    { "setAnalogOutput", rsSetAnalogOutput },
    { "setAnalogueOutput", rsSetAnalogOutput },
    { "getBundledInput", rsGetBundledInput },
    { "getBundledOutput", rsGetBundledOutput },
    { "setBundledOutput", rsSetBundledOutput },
    { "testBundledInput", rsTestBundledInput },
    { nullptr, nullptr },
};

// ---------------------------------------------------------------------------
// Native os time functions
// ---------------------------------------------------------------------------

// Call the original Java-backed function (stored as upvalue 1) with our
// arguments. Only used for functions which never yield.
static int osFallback(lua_State* L) {
    int nargs = lua_gettop(L);
    lua_pushvalue(L, lua_upvalueindex(1));
    lua_insert(L, 1);
    lua_call(L, nargs, LUA_MULTRET);
    return lua_gettop(L);
}

// Determine which locale is requested: 0 = fallback to Java (ingame/table/
// none), 1 = utc, 2 = local. Raises on unsupported locale strings.
static int osLocale(lua_State* L) {
    if (lua_isnoneornil(L, 1)) return 0;
    if (lua_type(L, 1) != LUA_TSTRING) return 0; // Let Java produce the error/table handling.

    size_t len;
    const char* s = lua_tolstring(L, 1, &len);
    char lower[8];
    if (len < sizeof(lower)) {
        for (size_t i = 0; i < len; i++) lower[i] = (char) tolower((unsigned char) s[i]);
        lower[len] = '\0';
        if (strcmp(lower, "utc") == 0) return 1;
        if (strcmp(lower, "local") == 0) return 2;
        if (strcmp(lower, "ingame") == 0) return 0;
    }
    luaL_error(L, "Unsupported operation");
    return 0; // unreachable
}

static int64_t systemMillis() {
    return (int64_t) std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}

static void brokenDownTime(bool utc, struct tm* out) {
    time_t now = time(nullptr);
#ifdef _WIN32
    if (utc) gmtime_s(out, &now); else localtime_s(out, &now);
#else
    if (utc) gmtime_r(&now, out); else localtime_r(&now, out);
#endif
}

static int osEpoch(lua_State* L) {
    int locale = osLocale(L);
    if (locale == 0) return osFallback(L);
    // Note: Java's Calendar.getTimeInMillis() is timezone-independent, so
    // "utc" and "local" both return the UTC epoch. We match that.
    lua_pushnumber(L, (double) systemMillis());
    return 1;
}

static int osTime(lua_State* L) {
    int locale = osLocale(L);
    if (locale == 0) return osFallback(L);

    struct tm t;
    brokenDownTime(locale == 1, &t);
    // Mirror Java's float arithmetic.
    float result = (float) t.tm_hour;
    result += t.tm_min / 60.0f;
    result += t.tm_sec / (60.0f * 60.0f);
    lua_pushnumber(L, (double) result);
    return 1;
}

static int osDay(lua_State* L) {
    int locale = osLocale(L);
    if (locale == 0) return osFallback(L);

    struct tm t;
    brokenDownTime(locale == 1, &t);
    // Mirrors OSAPI.getDayForCalendar: whole years since 1970 plus the
    // (1-based) day of the year.
    int year = t.tm_year + 1900;
    int day = 0;
    for (int y = 1970; y < year; y++) {
        bool leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0);
        day += leap ? 366 : 365;
    }
    day += t.tm_yday + 1;
    lua_pushinteger(L, day);
    return 1;
}

// ---------------------------------------------------------------------------
// Interrupt handling
// ---------------------------------------------------------------------------
static void interruptCallback(lua_State* L, int gc) {
    if (gc >= 0) return; // Don't interrupt during GC.

    MachineState* m = getMachine(L);

    // Periodically flush native terminal/redstone changes to Java, so
    // long-running code which draws without yielding still updates the world.
    if (m->anyStateDirty()) {
        NativeTerm* term = m->term;
        double now = lua_clock();
        double last = term != nullptr ? term->lastSync : 0;
        if (now - last > 0.05) {
            if (term != nullptr) term->lastSync = now;
            JNIEnv* env = getEnv(m);
            if (env != nullptr) {
                env->CallVoidMethod(m->machine, g_syncTermMethod);
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
        }
    }

    int flags = m->flags.load(std::memory_order_relaxed);
    if (flags == 0) return;

    // lua_break raises an error when the thread cannot yield (e.g. inside a
    // metamethod), so only break at yieldable safepoints; otherwise we try
    // again at the next one.
    if (flags & FLAG_HARD_ABORT) {
        if (lua_isyieldable(L) && !luaG_isnative(L, 0)) {
            recordBreakLeaf(m, L);
            lua_break(L);
        } else if (luaG_isnative(L, 0)) {
            // Native frames cannot be suspended; unwind with an error
            // instead. The machine is being destroyed either way.
            luaL_error(L, "Too long without yielding");
        }
        return;
    }
    if (flags & FLAG_SOFT_ABORT) {
        bool expected = false;
        if (m->softThrown.compare_exchange_strong(expected, true)) {
            // Include the current position, as a level-1 Lua error would.
            lua_Debug ar;
            if (lua_getinfo(L, 0, "sl", &ar) && ar.currentline > 0) {
                luaL_error(L, "%s:%d: Too long without yielding", ar.short_src, ar.currentline);
            } else {
                luaL_error(L, "Too long without yielding");
            }
        }
    }
    if ((flags & FLAG_PAUSE) && lua_isyieldable(L) && !luaG_isnative(L, 0)) {
        recordBreakLeaf(m, L);
        lua_break(L);
    }
}

// ---------------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT jlong JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_createState(JNIEnv* env, jclass, jobject machine) {
    auto* m = new MachineState();
    env->GetJavaVM(&m->jvm);
    m->machine = env->NewGlobalRef(machine);

    if (g_invokeMethod == nullptr) {
        jclass cls = env->GetObjectClass(machine);
        g_invokeMethod = env->GetMethodID(cls, "invoke", "(IIJ[B)[B");
        g_resumeMethod = env->GetMethodID(cls, "resumeCallback", "(J[B)[B");
        g_invokeFastMethod = env->GetMethodID(cls, "invokeFast", "(IIJI)I");
        g_resumeFastMethod = env->GetMethodID(cls, "resumeFast", "(JI)I");
        g_takeLargeMethod = env->GetMethodID(cls, "takeLargeResponse", "()[B");
        g_syncTermMethod = env->GetMethodID(cls, "syncTermNow", "()V");
        env->DeleteLocalRef(cls);
        if (g_invokeMethod == nullptr || g_resumeMethod == nullptr || g_invokeFastMethod == nullptr
            || g_resumeFastMethod == nullptr || g_takeLargeMethod == nullptr || g_syncTermMethod == nullptr) {
            env->DeleteGlobalRef(m->machine);
            delete m;
            return 0;
        }
    }

    // Allocate the shared direct buffers for the fast call path.
    m->fastArgs = static_cast<uint8_t*>(malloc(FAST_BUFFER_SIZE));
    m->fastResp = static_cast<uint8_t*>(malloc(FAST_BUFFER_SIZE));
    jobject argsBuf = env->NewDirectByteBuffer(m->fastArgs, FAST_BUFFER_SIZE);
    jobject respBuf = env->NewDirectByteBuffer(m->fastResp, FAST_BUFFER_SIZE);
    if (m->fastArgs == nullptr || m->fastResp == nullptr || argsBuf == nullptr || respBuf == nullptr) {
        env->ExceptionClear();
        free(m->fastArgs);
        free(m->fastResp);
        env->DeleteGlobalRef(m->machine);
        delete m;
        return 0;
    }
    m->fastArgsRef = env->NewGlobalRef(argsBuf);
    m->fastRespRef = env->NewGlobalRef(respBuf);

    lua_State* L = luaL_newstate();
    m->L = L;

    lua_Callbacks* cb = lua_callbacks(L);
    cb->userdata = m;
    cb->interrupt = interruptCallback;
    cb->debuginterrupt = debugInterruptCallback;

    // Luau's native code generator (JIT) gives a further ~30% speedup on
    // compute-heavy code. Set CC_LUAU_NOJIT to fall back to the interpreter.
    if (luau_codegen_supported() && getenv("CC_LUAU_NOJIT") == nullptr) {
        luau_codegen_create(L);
        m->codegen = true;
    }

    luaL_openlibs(L);

    // Install load and loadstring: Luau's runtime does not provide them, but
    // CraftOS requires both.
    lua_pushcfunction(L, ccluauLoad, "load");
    lua_setglobal(L, "load");
    lua_pushcfunction(L, ccluauLoadstring, "loadstring");
    lua_setglobal(L, "loadstring");

    // The native window factory; rom/apis/window.lua delegates to this when
    // present (see that file for the reference Lua implementation).
    lua_pushcfunction(L, nwCreate, "window.create");
    lua_setglobal(L, "_CC_NATIVE_WINDOW");

    // Native pixel-graphics helpers for mineos.gfx / mineos.vterm.
    lua_createtable(L, 0, 4);
    for (const luaL_Reg* reg = GFX_METHODS; reg->name != nullptr; reg++) {
        lua_pushcfunction(L, reg->func, reg->name);
        lua_setfield(L, -2, reg->name);
    }
    lua_setglobal(L, "_CC_NATIVE_GFX");

    // Native textutils fast paths.
    lua_createtable(L, 0, 1);
    lua_pushcfunction(L, textutilsSerialize, "textutils.serialize");
    lua_setfield(L, -2, "serialize");
    lua_setglobal(L, "_CC_NATIVE_TEXTUTILS");

    // Emulate Lua 5.2's _ENV for chunks running in the default environment.
    // Chunks loaded with a custom environment get their own _ENV field (see
    // ccluauLoad).
    lua_pushvalue(L, LUA_GLOBALSINDEX);
    lua_setglobal(L, "_ENV");

    return (jlong) (uintptr_t) m;
}

JNIEXPORT void JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_closeState(JNIEnv* env, jclass, jlong ptr) {
    auto* m = reinterpret_cast<MachineState*>((uintptr_t) ptr);
    lua_close(m->L);
    env->DeleteGlobalRef(m->machine);
    if (m->fastArgsRef != nullptr) env->DeleteGlobalRef(m->fastArgsRef);
    if (m->fastRespRef != nullptr) env->DeleteGlobalRef(m->fastRespRef);
    free(m->fastArgs);
    free(m->fastResp);
    delete m->term;
    delete m->redstone;
    delete m;
}

// Returns the shared direct buffer for the fast call path.
JNIEXPORT jobject JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_getBuffer(JNIEnv*, jclass, jlong ptr, jboolean args) {
    auto* m = reinterpret_cast<MachineState*>((uintptr_t) ptr);
    return args ? m->fastArgsRef : m->fastRespRef;
}

JNIEXPORT void JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_setFlags(JNIEnv*, jclass, jlong ptr, jint flags) {
    auto* m = reinterpret_cast<MachineState*>((uintptr_t) ptr);
    m->flags.store(flags, std::memory_order_relaxed);
    if ((flags & FLAG_SOFT_ABORT) == 0) m->softThrown.store(false, std::memory_order_relaxed);
}

// Helper run via lua_pcall to decode a buffer in a protected context. The
// Reader is passed as a lightuserdata argument; decoded values are returned.
static int protectedDecode(lua_State* L) {
    Reader* r = static_cast<Reader*>(lua_tolightuserdata(L, 1));
    lua_settop(L, 0);
    int count = decodeAll(L, *r);
    if (count < 0) luaL_error(L, "ccluau: malformed value buffer");
    return count;
}

// Set a global to a decoded value. Returns false on failure.
JNIEXPORT jboolean JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_setGlobal(JNIEnv* env, jclass, jlong ptr, jbyteArray nameArr, jbyteArray valueArr) {
    auto* m = reinterpret_cast<MachineState*>((uintptr_t) ptr);
    lua_State* L = m->L;

    jsize nameLen = env->GetArrayLength(nameArr);
    std::string name((size_t) nameLen, '\0');
    env->GetByteArrayRegion(nameArr, 0, nameLen, reinterpret_cast<jbyte*>(&name[0]));

    jsize valueLen = env->GetArrayLength(valueArr);
    std::vector<uint8_t> value((size_t) valueLen);
    env->GetByteArrayRegion(valueArr, 0, valueLen, reinterpret_cast<jbyte*>(value.data()));

    Reader r(value.data(), value.size());
    int base = lua_gettop(L);
    lua_pushcfunction(L, protectedDecode, nullptr);
    lua_pushlightuserdata(L, &r);
    if (lua_pcall(L, 1, 1, 0) != 0) {
        lua_settop(L, base);
        return JNI_FALSE;
    }
    lua_setglobal(L, name.c_str());
    return JNI_TRUE;
}

// Compile and load the BIOS into a new coroutine; returns a pointer to the
// thread (pinned with lua_ref). On failure, throws MachineException.
JNIEXPORT jlong JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_loadBios(JNIEnv* env, jclass, jlong ptr, jbyteArray biosArr, jstring chunkNameStr) {
    auto* m = reinterpret_cast<MachineState*>((uintptr_t) ptr);
    lua_State* L = m->L;

    jsize biosLen = env->GetArrayLength(biosArr);
    std::string bios((size_t) biosLen, '\0');
    env->GetByteArrayRegion(biosArr, 0, biosLen, reinterpret_cast<jbyte*>(&bios[0]));

    const char* chunkName = env->GetStringUTFChars(chunkNameStr, nullptr);

    lua_State* thread = lua_newthread(L);
    lua_ref(L, -1); // Pin the thread.
    lua_pop(L, 1);

    lua_CompileOptions opts = {};
    opts.optimizationLevel = 1;
    opts.debugLevel = 1;

    size_t bytecodeSize = 0;
    char* bytecode = luau_compile(bios.data(), bios.size(), &opts, &bytecodeSize);
    int status = bytecode == nullptr ? 1 : luau_load(thread, chunkName, bytecode, bytecodeSize, 0);
    if (bytecode != nullptr) free(bytecode);
    env->ReleaseStringUTFChars(chunkNameStr, chunkName);

    if (status != 0) {
        size_t len = 0;
        const char* err = lua_gettop(thread) > 0 ? lua_tolstring(thread, -1, &len) : nullptr;
        std::string msg(err != nullptr ? err : "unknown error", err != nullptr ? len : 13);
        jclass exCls = env->FindClass("dan200/computercraft/core/lua/MachineException");
        if (exCls != nullptr) env->ThrowNew(exCls, msg.c_str());
        return 0;
    }

    if (m->codegen) luau_codegen_compile(thread, -1);

    return (jlong) (uintptr_t) thread;
}

// Resume a thread with the given encoded varargs (or continue from a break if
// args is null). Returns the response buffer.
JNIEXPORT jbyteArray JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_resume(JNIEnv* env, jclass, jlong ptr, jlong threadPtr, jbyteArray argsArr) {
    auto* m = reinterpret_cast<MachineState*>((uintptr_t) ptr);
    lua_State* thread = reinterpret_cast<lua_State*>((uintptr_t) threadPtr);

    int status;
    if (argsArr == nullptr && !m->breakChain.empty()) {
        // Continuing from a pause break. Resume the innermost suspended thread
        // first, then unwind outwards: each parent's coresumecont only makes
        // progress once its child is no longer LUA_BREAK.
        status = LUA_BREAK;
        while (!m->breakChain.empty()) {
            lua_State* current = m->breakChain.front();
            status = lua_resume(current, nullptr, 0);
            if (status == LUA_BREAK) break; // Broke again; keep the remaining chain.
            m->breakChain.erase(m->breakChain.begin());
        }
        // When the chain fully drains, the last thread resumed is the main
        // thread, whose stack now holds the yield/return values read below.
    } else {
        // A fresh event (or a break with no chain): fully unwind and resume the
        // main thread with the decoded arguments.
        m->breakChain.clear();
        m->breakInsert = 0;

        int nargs = 0;
        if (argsArr != nullptr) {
            jsize argsLen = env->GetArrayLength(argsArr);
            std::vector<uint8_t> args((size_t) argsLen);
            env->GetByteArrayRegion(argsArr, 0, argsLen, reinterpret_cast<jbyte*>(args.data()));

            // Decode the arguments on the (idle) main state - it is not legal
            // to run code on a suspended coroutine's stack - then move across.
            lua_State* host = m->L;
            Reader r(args.data(), args.size());
            int base = lua_gettop(host);
            lua_pushcfunction(host, protectedDecode, nullptr);
            lua_pushlightuserdata(host, &r);
            if (lua_pcall(host, 1, LUA_MULTRET, 0) != 0) {
                lua_settop(host, base);
                // Malformed arguments: resume with none rather than crash.
            } else {
                nargs = lua_gettop(host) - base;
                if (nargs > 0) {
                    if (lua_checkstack(thread, nargs + 1)) {
                        lua_xmove(host, thread, nargs);
                    } else {
                        lua_settop(host, base);
                        nargs = 0;
                    }
                }
            }
        }

        status = lua_resume(thread, nullptr, nargs);
    }

    Writer w;
    switch (status) {
    case LUA_OK: {
        w.u8(RESUME_DEAD);
        int nres = lua_gettop(thread);
        encodeStack(thread, w, 1, nres);
        lua_settop(thread, 0);
        break;
    }
    case LUA_YIELD: {
        w.u8(RESUME_YIELD);
        int nres = lua_gettop(thread);
        encodeStack(thread, w, 1, nres);
        lua_settop(thread, 0);
        break;
    }
    case LUA_BREAK:
        w.u8(RESUME_BREAK);
        break;
    default: {
        w.u8(RESUME_ERROR);
        size_t len = 0;
        const char* err = lua_gettop(thread) > 0 ? lua_tolstring(thread, -1, &len) : nullptr;
        if (err == nullptr) {
            err = "unknown error";
            len = strlen(err);
        }
        w.str(err, len);
        lua_settop(thread, 0);
        break;
    }
    }

    jbyteArray result = env->NewByteArray((jsize) w.buf.size());
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, (jsize) w.buf.size(), reinterpret_cast<const jbyte*>(w.buf.data()));
    return result;
}

// Returns a debug traceback of the given thread (for printExecutionState).
JNIEXPORT jstring JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_debugTrace(JNIEnv* env, jclass, jlong threadPtr) {
    lua_State* thread = reinterpret_cast<lua_State*>((uintptr_t) threadPtr);
    return env->NewStringUTF(lua_debugtrace(thread));
}

// Set the native terminal's dimensions and contents from the Java Terminal
// (the authority at init/resize time). text/fg/bg are height*width blobs.
static void termLoadContent(JNIEnv* env, NativeTerm* term, jint width, jint height, jbyteArray text, jbyteArray fg, jbyteArray bg) {
    term->width = width;
    term->height = height;
    size_t size = (size_t) width * height;
    term->text.assign(size, ' ');
    term->fg.assign(size, '0');
    term->bg.assign(size, 'f');
    term->lineDirty.assign((size_t) height, 0);
    term->cursorDirty = false;
    term->paletteDirty = false;
    term->anyLineDirty = false;

    if ((size_t) env->GetArrayLength(text) >= size && (size_t) env->GetArrayLength(fg) >= size
        && (size_t) env->GetArrayLength(bg) >= size && size > 0) {
        env->GetByteArrayRegion(text, 0, (jsize) size, reinterpret_cast<jbyte*>(term->text.data()));
        env->GetByteArrayRegion(fg, 0, (jsize) size, reinterpret_cast<jbyte*>(term->fg.data()));
        env->GetByteArrayRegion(bg, 0, (jsize) size, reinterpret_cast<jbyte*>(term->bg.data()));
    }
}

// Create the native terminal and install the "term" global. palette and
// nativePalette are 48 doubles (16 x rgb), indexed by palette index.
JNIEXPORT void JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_initTerm(
    JNIEnv* env, jclass, jlong ptr, jint width, jint height, jboolean colour,
    jint cursorX, jint cursorY, jint curFg, jint curBg, jboolean blink,
    jdoubleArray palette, jdoubleArray nativePalette,
    jbyteArray text, jbyteArray fg, jbyteArray bg
) {
    auto* m = reinterpret_cast<MachineState*>((uintptr_t) ptr);
    lua_State* L = m->L;

    if (m->term == nullptr) m->term = new NativeTerm();
    NativeTerm* term = m->term;
    term->colour = colour;
    term->cursorX = cursorX;
    term->cursorY = cursorY;
    term->curFg = curFg;
    term->curBg = curBg;
    term->blink = blink;

    if (env->GetArrayLength(palette) >= 48) {
        env->GetDoubleArrayRegion(palette, 0, 48, &term->palette[0][0]);
    }
    if (env->GetArrayLength(nativePalette) >= 48) {
        env->GetDoubleArrayRegion(nativePalette, 0, 48, &term->nativePalette[0][0]);
    }

    termLoadContent(env, term, width, height, text, fg, bg);
    term->baseWidth = width;
    term->baseHeight = height;

    // Install the term global.
    lua_createtable(L, 0, 26);
    for (const luaL_Reg* reg = TERM_METHODS; reg->name != nullptr; reg++) {
        lua_pushcfunction(L, reg->func, reg->name);
        lua_setfield(L, -2, reg->name);
    }
    // Mark the table so native windows can recognise it as a fast-path
    // parent. term.lua only wraps function fields, so the flag is inert.
    lua_pushboolean(L, 1);
    lua_setfield(L, -2, "__ccluau_term");
    lua_setglobal(L, "term");
}

// Refresh the native terminal's size/contents from Java (used on resize).
JNIEXPORT void JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_termSetContent(
    JNIEnv* env, jclass, jlong ptr, jint width, jint height, jbyteArray text, jbyteArray fg, jbyteArray bg
) {
    auto* m = reinterpret_cast<MachineState*>((uintptr_t) ptr);
    if (m->term == nullptr) return;
    termLoadContent(env, m->term, width, height, text, fg, bg);
}

// Encode the terminal's dirty state into the response buffer, clearing the
// dirty flags. Returns the encoded length (0 = nothing to sync).
//
// Format: u8 flags (1 = cursor, 2 = palette, 4 = lines);
//   cursor: i32 x, i32 y, i32 fg, i32 bg, u8 blink
//   palette: 48 f64
//   lines: i32 count, then per line: i32 y, width bytes text, width fg, width bg
JNIEXPORT jint JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_syncTerm(JNIEnv*, jclass, jlong ptr) {
    auto* m = reinterpret_cast<MachineState*>((uintptr_t) ptr);
    if (!m->anyStateDirty()) return 0;

    static NativeTerm emptyTerm;
    NativeTerm* term = m->term != nullptr ? m->term : &emptyTerm;
    NativeRedstone* redstone = m->redstone;

    FixedWriter w(m->fastResp, FAST_BUFFER_SIZE);
    uint8_t flags = 0;
    if (term->cursorDirty) flags |= 1;
    if (term->paletteDirty) flags |= 2;
    if (term->anyLineDirty) flags |= 4;
    if (redstone != nullptr && redstone->outputDirty) flags |= 8;
    if (term->resizeDirty) flags |= 16;
    if (term->mouseCaptureDirty) flags |= 32;
    w.u8(flags);

    // The resize must come first: line payloads below use the new width.
    if (term->resizeDirty) {
        w.i32(term->width);
        w.i32(term->height);
    }

    if (term->mouseCaptureDirty) w.u8(term->mouseCapture ? 1 : 0);

    if (term->cursorDirty) {
        w.i32(term->cursorX);
        w.i32(term->cursorY);
        w.i32(term->curFg);
        w.i32(term->curBg);
        w.u8(term->blink ? 1 : 0);
    }

    if (term->paletteDirty) {
        w.bytes(&term->palette[0][0], 48 * sizeof(double));
    }

    if (term->anyLineDirty) {
        int count = 0;
        for (int y = 0; y < term->height; y++) {
            if (term->lineDirty[y]) count++;
        }
        w.i32(count);
        for (int y = 0; y < term->height; y++) {
            if (!term->lineDirty[y]) continue;
            size_t row = (size_t) y * term->width;
            w.i32(y);
            w.bytes(term->text.data() + row, (size_t) term->width);
            w.bytes(term->fg.data() + row, (size_t) term->width);
            w.bytes(term->bg.data() + row, (size_t) term->width);
        }
    }

    if ((flags & 8) != 0) {
        for (int i = 0; i < 6; i++) w.i32(redstone->output[i]);
        for (int i = 0; i < 6; i++) w.i32(redstone->bundledOutput[i]);
    }

    if (w.overflow) {
        // Should not happen for any reasonable terminal size; leave dirty and
        // report nothing rather than send a corrupt delta.
        return 0;
    }

    term->cursorDirty = false;
    term->paletteDirty = false;
    term->anyLineDirty = false;
    term->resizeDirty = false;
    term->mouseCaptureDirty = false;
    std::fill(term->lineDirty.begin(), term->lineDirty.end(), 0);
    if (redstone != nullptr) redstone->outputDirty = false;
    return (jint) w.pos;
}

// Create the native redstone mirror and install the "redstone"/"rs" globals.
// inputs and outputs are 12 ints: 6 analog levels then 6 bundled masks.
JNIEXPORT void JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_installRedstone(
    JNIEnv* env, jclass, jlong ptr, jintArray inputs, jintArray outputs
) {
    auto* m = reinterpret_cast<MachineState*>((uintptr_t) ptr);
    lua_State* L = m->L;

    if (m->redstone == nullptr) m->redstone = new NativeRedstone();
    NativeRedstone* redstone = m->redstone;

    jint values[12];
    if (env->GetArrayLength(inputs) >= 12) {
        env->GetIntArrayRegion(inputs, 0, 12, values);
        for (int i = 0; i < 6; i++) redstone->input[i] = values[i];
        for (int i = 0; i < 6; i++) redstone->bundledInput[i] = values[6 + i];
    }
    if (env->GetArrayLength(outputs) >= 12) {
        env->GetIntArrayRegion(outputs, 0, 12, values);
        for (int i = 0; i < 6; i++) redstone->output[i] = values[i];
        for (int i = 0; i < 6; i++) redstone->bundledOutput[i] = values[6 + i];
    }

    lua_createtable(L, 0, 14);
    for (const luaL_Reg* reg = REDSTONE_METHODS; reg->name != nullptr; reg++) {
        lua_pushcfunction(L, reg->func, reg->name);
        lua_setfield(L, -2, reg->name);
    }
    lua_pushvalue(L, -1);
    lua_setglobal(L, "redstone");
    lua_setglobal(L, "rs");
}

// Update the native redstone input mirror (6 analog levels + 6 bundled masks).
JNIEXPORT void JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_setRedstoneInput(JNIEnv* env, jclass, jlong ptr, jintArray inputs) {
    auto* m = reinterpret_cast<MachineState*>((uintptr_t) ptr);
    if (m->redstone == nullptr || env->GetArrayLength(inputs) < 12) return;

    jint values[12];
    env->GetIntArrayRegion(inputs, 0, 12, values);
    for (int i = 0; i < 6; i++) m->redstone->input[i] = values[i];
    for (int i = 0; i < 6; i++) m->redstone->bundledInput[i] = values[6 + i];
}

// Wrap os.epoch/os.time/os.day with native implementations for the utc/local
// locales, keeping the original Java-backed functions as fallbacks.
JNIEXPORT void JNICALL Java_dan200_computercraft_core_lua_luau_LuauNative_installFastOs(JNIEnv*, jclass, jlong ptr) {
    auto* m = reinterpret_cast<MachineState*>((uintptr_t) ptr);
    lua_State* L = m->L;

    lua_getglobal(L, "os");
    if (!lua_istable(L, -1)) {
        lua_pop(L, 1);
        return;
    }

    const struct { const char* name; lua_CFunction fn; } wrappers[] = {
        { "epoch", osEpoch },
        { "time", osTime },
        { "day", osDay },
    };
    for (const auto& wrapper : wrappers) {
        lua_getfield(L, -1, wrapper.name);
        if (lua_isfunction(L, -1)) {
            lua_pushcclosure(L, wrapper.fn, wrapper.name, 1); // Original function becomes upvalue 1.
            lua_setfield(L, -2, wrapper.name);
        } else {
            lua_pop(L, 1);
        }
    }
    lua_pop(L, 1);
}

} // extern "C"
