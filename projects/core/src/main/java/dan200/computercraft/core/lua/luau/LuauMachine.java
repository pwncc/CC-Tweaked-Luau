// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.core.lua.luau;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.IDynamicLuaObject;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.ILuaCallback;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.ILuaFunction;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.core.CoreConfig;
import dan200.computercraft.core.Logging;
import dan200.computercraft.core.apis.IAPIEnvironment;
import dan200.computercraft.core.apis.RedstoneAPI;
import dan200.computercraft.core.apis.TermAPI;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.core.computer.TimeoutState;
import dan200.computercraft.core.redstone.RedstoneAccess;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.core.util.Colour;
import dan200.computercraft.core.lua.ILuaMachine;
import dan200.computercraft.core.lua.MachineEnvironment;
import dan200.computercraft.core.lua.MachineException;
import dan200.computercraft.core.lua.MachineResult;
import dan200.computercraft.core.methods.LuaMethod;
import dan200.computercraft.core.methods.MethodSupplier;
import dan200.computercraft.core.util.LuaUtil;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An implementation of {@link ILuaMachine} which runs on the Luau VM, via the {@code ccluau} native library.
 * <p>
 * Values are exchanged with the native side using a compact binary encoding (see the {@code T_*} tags), one buffer per
 * call. Calls from Lua into Java arrive via {@link #invoke(int, int, long, byte[])}; when a Java method yields (i.e.
 * returns a {@link dan200.computercraft.api.lua.MethodResult} with a callback), the callback is pushed onto a
 * per-coroutine stack and the native side yields with a continuation which later calls
 * {@link #resumeCallback(long, byte[])}.
 *
 * @see dan200.computercraft.core.lua.CobaltLuaMachine The equivalent (pure-Java) Cobalt implementation.
 */
public final class LuauMachine implements ILuaMachine {
    private static final Logger LOG = LoggerFactory.getLogger(LuauMachine.class);
    private static final LuaMethod FUNCTION_METHOD = (target, context, args) -> ((ILuaFunction) target).call(args);

    // Value tags. Must match ccluau.cpp.
    private static final byte T_NIL = 0;
    private static final byte T_TRUE = 1;
    private static final byte T_FALSE = 2;
    private static final byte T_DOUBLE = 3;
    private static final byte T_STRING = 4;
    private static final byte T_TABLE = 5;
    private static final byte T_REF = 6;
    private static final byte T_OBJECT = 7;
    private static final byte T_FUNCTION = 8;
    private static final byte T_NAMED_TABLE = 9;

    // Invoke response statuses. Must match ccluau.cpp.
    private static final byte INVOKE_RETURN = 0;
    private static final byte INVOKE_ERROR = 1;
    private static final byte INVOKE_YIELD = 2;

    private final TimeoutState timeout;
    private final Runnable timeoutListener = this::updateTimeout;
    private final ILuaContext context;
    private final MethodSupplier<LuaMethod> luaMethods;

    private final long state;
    private final long mainThread;

    /**
     * Shared direct buffers for the fast call path, and a reusable scratch writer for building responses. Calls from
     * Lua only ever happen on the computer thread, so these are safe to reuse.
     */
    private final ByteBuffer fastArgs;
    private final ByteBuffer fastResp;
    private final ValueWriter scratch = new ValueWriter();
    private byte @Nullable [] largeResponse;

    /**
     * The terminal this machine's native term API is bound to, if any.
     */
    private @Nullable Terminal terminal;
    private @Nullable IAPIEnvironment termEnvironment;
    private int termWidth = -1;
    private int termHeight = -1;

    /**
     * The redstone state this machine's native redstone API is bound to, if any.
     */
    private @Nullable RedstoneAccess redstone;
    private final int[] redstoneInputs = new int[12];
    private final int[] redstoneScratch = new int[12];

    /**
     * Objects exposed to Lua, indexed by handle. Never shrinks over the machine's lifetime; entries are released when
     * the machine is closed.
     */
    private final List<BoundObject> boundObjects = new ArrayList<>();
    private final IdentityHashMap<Object, Integer> boundHandles = new IdentityHashMap<>();

    /**
     * Pending {@link ILuaCallback}s for Java methods which have yielded, keyed by the coroutine which called them.
     */
    private final Map<Long, ArrayDeque<CallbackFrame>> callbacks = new HashMap<>();

    private volatile boolean isDisposed = false;
    private boolean isClosed = false;
    private boolean isExecuting = false;

    private @Nullable String eventFilter = null;
    private boolean resumeFromBreak = false;

    public LuauMachine(MachineEnvironment environment, InputStream bios) throws MachineException, IOException {
        if (!LuauNative.isAvailable()) throw new MachineException("The Luau runtime is not available on this platform");

        timeout = environment.timeout();
        context = environment.context();
        luaMethods = environment.luaMethods();

        state = LuauNative.createState(this);
        if (state == 0) throw new MachineException("Cannot create Luau state");

        fastArgs = LuauNative.getBuffer(state, true).order(ByteOrder.LITTLE_ENDIAN);
        fastResp = LuauNative.getBuffer(state, false).order(ByteOrder.LITTLE_ENDIAN);

        try {
            setGlobal("_HOST", environment.hostString());
            setGlobal("_CC_DEFAULT_SETTINGS", CoreConfig.defaultComputerSettings);
            setGlobal("_VERSION", "Luau");

            // Add default APIs.
            Map<Object, Object> modules = new HashMap<>();
            for (var api : environment.apis()) addAPI(api, modules);

            // Expose modules registered by APIs (e.g. via ILuaAPI.getModuleName()) so that the ROM's require can pick
            // them up.
            if (!modules.isEmpty()) setGlobal("_CC_NATIVE_MODULES", modules);

            // Wrap os.epoch/os.time/os.day with native implementations.
            LuauNative.installFastOs(state);

            mainThread = LuauNative.loadBios(state, bios.readAllBytes(), "@bios.lua");
        } catch (MachineException | IOException | RuntimeException e) {
            LuauNative.closeState(state);
            throw e;
        }

        timeout.addListener(timeoutListener);
    }

    /**
     * Determine whether the Luau runtime is available on this platform.
     *
     * @return Whether {@link LuauMachine}s can be created.
     */
    public static boolean isAvailable() {
        return LuauNative.isAvailable();
    }

    private void addAPI(ILuaAPI api, Map<Object, Object> modules) {
        // The term API is implemented natively: every method runs inside the Luau VM against a shadow terminal,
        // which is synced back to the Java terminal after execution.
        if (api instanceof TermAPI termApi) {
            termEnvironment = termApi.environment();
            installTerm(termApi.getTerminal());
            return;
        }

        // Likewise redstone: inputs are mirrored into the VM, outputs are synced back out.
        if (api instanceof RedstoneAPI redstoneApi) {
            installRedstone(redstoneApi.redstoneAccess());
            return;
        }

        for (var name : api.getNames()) setGlobal(name, api);

        var moduleName = api.getModuleName();
        if (moduleName != null) modules.put(moduleName, api);
    }

    private void installTerm(Terminal terminal) {
        this.terminal = terminal;
        termWidth = terminal.getWidth();
        termHeight = terminal.getHeight();

        var palette = new double[48];
        var nativePalette = new double[48];
        for (var i = 0; i < 16; i++) {
            var colours = terminal.getPalette().getColour(i);
            palette[i * 3] = colours[0];
            palette[i * 3 + 1] = colours[1];
            palette[i * 3 + 2] = colours[2];

            var colour = Colour.fromInt(i);
            nativePalette[i * 3] = colour.getR();
            nativePalette[i * 3 + 1] = colour.getG();
            nativePalette[i * 3 + 2] = colour.getB();
        }

        var contents = snapshotTerminal(terminal);
        LuauNative.initTerm(
            state, termWidth, termHeight, terminal.isColour(),
            terminal.getCursorX(), terminal.getCursorY(), terminal.getTextColour(), terminal.getBackgroundColour(),
            terminal.getCursorBlink(), palette, nativePalette,
            contents[0], contents[1], contents[2]
        );
    }

    private void installRedstone(RedstoneAccess redstone) {
        this.redstone = redstone;
        readRedstoneInputs(redstoneInputs);

        var outputs = new int[12];
        for (var i = 0; i < 6; i++) {
            var side = ComputerSide.valueOf(i);
            outputs[i] = redstone.getOutput(side);
            outputs[6 + i] = redstone.getBundledOutput(side);
        }
        LuauNative.installRedstone(state, redstoneInputs, outputs);
    }

    private void readRedstoneInputs(int[] into) {
        var redstone = Objects.requireNonNull(this.redstone);
        for (var i = 0; i < 6; i++) {
            var side = ComputerSide.valueOf(i);
            into[i] = redstone.getInput(side);
            into[6 + i] = redstone.getBundledInput(side);
        }
    }

    /**
     * Push the current redstone inputs into the VM if they have changed since the last resume.
     */
    private void updateRedstoneInputs() {
        if (redstone == null) return;

        readRedstoneInputs(redstoneScratch);
        if (!Arrays.equals(redstoneScratch, redstoneInputs)) {
            System.arraycopy(redstoneScratch, 0, redstoneInputs, 0, 12);
            LuauNative.setRedstoneInput(state, redstoneInputs);
        }
    }

    /**
     * Capture the terminal's contents as {@code height * width} byte planes (text, foreground, background).
     */
    private byte[][] snapshotTerminal(Terminal terminal) {
        var width = terminal.getWidth();
        var height = terminal.getHeight();
        var text = new byte[width * height];
        var fg = new byte[width * height];
        var bg = new byte[width * height];
        synchronized (terminal) {
            for (var y = 0; y < height; y++) {
                var textLine = terminal.getLine(y);
                var fgLine = terminal.getTextColourLine(y);
                var bgLine = terminal.getBackgroundColourLine(y);
                for (var x = 0; x < width; x++) {
                    text[y * width + x] = (byte) textLine.charAt(x);
                    fg[y * width + x] = (byte) fgLine.charAt(x);
                    bg[y * width + x] = (byte) bgLine.charAt(x);
                }
            }
        }
        return new byte[][]{ text, fg, bg };
    }

    /**
     * Sync the native terminal's changes back to the Java terminal. Called after each resume, and from the native
     * interrupt callback while long-running code is drawing.
     */
    void syncTermNow() {
        var terminal = this.terminal;
        if (terminal == null && redstone == null) return;

        int length;
        synchronized (this) {
            if (isClosed) return;
            length = LuauNative.syncTerm(state);
        }
        if (length <= 0) return;

        fastResp.clear().limit(length);
        var flags = fastResp.get();

        // The Lua side changed resolution: resize the Java terminal to match
        // before applying the (new-width) line data below.
        if ((flags & 16) != 0 && terminal != null) {
            var width = fastResp.getInt();
            var height = fastResp.getInt();
            synchronized (terminal) {
                terminal.resize(width, height);
            }
            termWidth = width;
            termHeight = height;
            var environment = termEnvironment;
            if (environment != null) environment.queueEvent("term_resize");
        }

        if ((flags & 32) != 0) {
            var capture = fastResp.get() != 0;
            if (terminal != null) terminal.setMouseCapture(capture);
        }

        if (terminal != null) synchronized (terminal) {
            if ((flags & 1) != 0) {
                var x = fastResp.getInt();
                var y = fastResp.getInt();
                var fg = fastResp.getInt();
                var bg = fastResp.getInt();
                var blink = fastResp.get() != 0;
                terminal.setCursorPos(x, y);
                terminal.setTextColour(fg);
                terminal.setBackgroundColour(bg);
                terminal.setCursorBlink(blink);
            }

            if ((flags & 2) != 0) {
                var palette = terminal.getPalette();
                for (var i = 0; i < 16; i++) {
                    palette.setColour(i, fastResp.getDouble(), fastResp.getDouble(), fastResp.getDouble());
                }
                terminal.setChanged();
            }

            if ((flags & 4) != 0) {
                var count = fastResp.getInt();
                var line = new byte[termWidth];
                for (var i = 0; i < count; i++) {
                    var y = fastResp.getInt();
                    fastResp.get(line);
                    var text = new String(line, StandardCharsets.ISO_8859_1);
                    fastResp.get(line);
                    var fg = new String(line, StandardCharsets.ISO_8859_1);
                    fastResp.get(line);
                    var bg = new String(line, StandardCharsets.ISO_8859_1);
                    if (y >= 0 && y < terminal.getHeight()) terminal.setLine(y, text, fg, bg);
                }
            }
        }

        if ((flags & 8) != 0) {
            var redstone = this.redstone;
            for (var i = 0; i < 6; i++) {
                var value = fastResp.getInt();
                if (redstone != null) redstone.setOutput(ComputerSide.valueOf(i), value);
            }
            for (var i = 0; i < 6; i++) {
                var value = fastResp.getInt();
                if (redstone != null) redstone.setBundledOutput(ComputerSide.valueOf(i), value);
            }
        }
    }

    private void setGlobal(String name, @Nullable Object value) {
        LuauNative.setGlobal(state, encodeString(name), encodeValues(new Object[]{ value }));
    }

    private void updateTimeout() {
        if (isDisposed) return;
        LuauNative.setFlags(state, currentFlags());
    }

    private int currentFlags() {
        var flags = 0;
        if (timeout.isSoftAborted()) flags |= LuauNative.FLAG_SOFT_ABORT;
        if (timeout.isHardAborted() || isDisposed) flags |= LuauNative.FLAG_HARD_ABORT;
        if (timeout.isPaused()) flags |= LuauNative.FLAG_PAUSE;
        return flags;
    }

    @Override
    public MachineResult handleEvent(@Nullable String eventName, @Nullable Object @Nullable [] arguments) {
        synchronized (this) {
            if (isClosed) throw new IllegalStateException("Machine has been closed");
            isExecuting = true;
        }

        try {
            if (eventFilter != null && eventName != null && !eventName.equals(eventFilter) && !eventName.equals("terminate")) {
                return MachineResult.OK;
            }

            byte[] args;
            if (resumeFromBreak) {
                // Continue a paused machine exactly where it stopped.
                resumeFromBreak = false;
                args = null;
            } else if (eventName == null) {
                args = new byte[0];
            } else {
                var count = arguments == null ? 0 : arguments.length;
                var values = new Object[count + 1];
                values[0] = eventName;
                if (arguments != null) System.arraycopy(arguments, 0, values, 1, count);
                args = encodeValues(values);
            }

            // Refresh the native mirrors of Java-owned state.
            updateRedstoneInputs();
            var terminal = this.terminal;
            if (terminal != null && (terminal.getWidth() != termWidth || terminal.getHeight() != termHeight)) {
                termWidth = terminal.getWidth();
                termHeight = terminal.getHeight();
                var contents = snapshotTerminal(terminal);
                LuauNative.termSetContent(state, termWidth, termHeight, contents[0], contents[1], contents[2]);
            }

            LuauNative.setFlags(state, currentFlags());
            var response = LuauNative.resume(state, mainThread, args);

            // Push any native terminal/redstone changes back to Java before we
            // potentially tear the machine down.
            syncTermNow();

            if (timeout.isHardAborted() || isDisposed) {
                closeInternal();
                return MachineResult.TIMEOUT;
            }

            if (response == null || response.length == 0) {
                closeInternal();
                return MachineResult.GENERIC_ERROR;
            }

            var buffer = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN);
            var status = buffer.get();
            switch (status) {
                case LuauNative.RESUME_YIELD -> {
                    var values = decodeValues(buffer);
                    eventFilter = values.length > 0 && values[0] instanceof String filter ? filter : null;
                    return MachineResult.OK;
                }
                case LuauNative.RESUME_BREAK -> {
                    resumeFromBreak = true;
                    return MachineResult.PAUSE;
                }
                case LuauNative.RESUME_DEAD -> {
                    closeInternal();
                    return MachineResult.GENERIC_ERROR;
                }
                case LuauNative.RESUME_ERROR -> {
                    var message = decodeString(buffer);
                    closeInternal();
                    LOG.warn("Top level coroutine errored: {}", message);
                    return MachineResult.error(message);
                }
                default -> {
                    closeInternal();
                    LOG.error("Unknown resume status {}", status);
                    return MachineResult.GENERIC_ERROR;
                }
            }
        } finally {
            boolean shouldClose;
            synchronized (this) {
                isExecuting = false;
                shouldClose = isDisposed && !isClosed;
            }
            if (shouldClose) closeInternal();
        }
    }

    @Override
    public void printExecutionState(StringBuilder out) {
        synchronized (this) {
            if (isClosed || isExecuting) return;
            out.append(LuauNative.debugTrace(mainThread));
        }
    }

    @Override
    public void close() {
        isDisposed = true;

        synchronized (this) {
            // If we're currently executing then the interrupt will stop the machine, and handleEvent will finish the
            // close for us.
            if (isExecuting) {
                LuauNative.setFlags(state, currentFlags());
                return;
            }
        }

        closeInternal();
    }

    private synchronized void closeInternal() {
        isDisposed = true;
        if (isClosed) return;
        isClosed = true;

        timeout.removeListener(timeoutListener);
        LuauNative.closeState(state);

        boundObjects.clear();
        boundHandles.clear();
        callbacks.clear();
    }

    // ------------------------------------------------------------------
    // Calls from Lua into Java. These are invoked by the native side while
    // inside LuauNative.resume.
    // ------------------------------------------------------------------

    /**
     * Invoke a method on a bound object, using the shared direct buffers. Called from native code.
     *
     * @param handle  The handle of the bound object.
     * @param method  The index of the method to call.
     * @param thread  The coroutine this call occurs on.
     * @param argsLen The length of the encoded arguments in the argument buffer.
     * @return The length of the response in the response buffer, or -1 if the response must instead be fetched with
     * {@link #takeLargeResponse()}.
     */
    int invokeFast(int handle, int method, long thread, int argsLen) {
        fastArgs.clear().limit(argsLen);
        return finishFast(invokeImpl(handle, method, thread, fastArgs));
    }

    /**
     * Invoke a method on a bound object. Called from native code when the arguments do not fit the shared buffer.
     *
     * @param handle The handle of the bound object.
     * @param method The index of the method to call.
     * @param thread The coroutine this call occurs on.
     * @param args   The encoded arguments.
     * @return The encoded response.
     */
    byte[] invoke(int handle, int method, long thread, byte[] args) {
        return invokeImpl(handle, method, thread, ByteBuffer.wrap(args).order(ByteOrder.LITTLE_ENDIAN)).toArray();
    }

    private ValueWriter invokeImpl(int handle, int method, long thread, ByteBuffer args) {
        BoundObject bound;
        LuaMethod luaMethod;
        try {
            bound = boundObjects.get(handle);
            luaMethod = bound.methods.get(method);
        } catch (IndexOutOfBoundsException e) {
            return encodeError(0, "ccluau: unknown method");
        }

        dan200.computercraft.api.lua.MethodResult result;
        try {
            var arguments = new LuauArguments(decodeValues(args, true), 0);
            result = luaMethod.apply(bound.targets.get(method), context, arguments);
        } catch (LuaException e) {
            return encodeException(e, 0);
        } catch (Throwable t) {
            LOG.error(Logging.JAVA_ERROR, "Error calling {} on {}", bound.names.get(method), bound.targets.get(method), t);
            return encodeError(0, "Java Exception Thrown: " + t);
        }

        return encodeMethodResult(result, thread);
    }

    /**
     * Resume the topmost pending {@link ILuaCallback} for a coroutine, using the shared direct buffers. Called from
     * native code when a Java-initiated yield is resumed.
     *
     * @param thread  The coroutine being resumed.
     * @param argsLen The length of the encoded arguments in the argument buffer.
     * @return The length of the response in the response buffer, or -1 for a large response.
     */
    int resumeFast(long thread, int argsLen) {
        fastArgs.clear().limit(argsLen);
        return finishFast(resumeImpl(thread, fastArgs));
    }

    /**
     * Resume the topmost pending {@link ILuaCallback} for a coroutine. Called from native code when the arguments do
     * not fit the shared buffer.
     *
     * @param thread The coroutine being resumed.
     * @param args   The encoded resumption arguments.
     * @return The encoded response.
     */
    byte[] resumeCallback(long thread, byte[] args) {
        return resumeImpl(thread, ByteBuffer.wrap(args).order(ByteOrder.LITTLE_ENDIAN)).toArray();
    }

    private ValueWriter resumeImpl(long thread, ByteBuffer args) {
        var stack = callbacks.get(thread);
        var frame = stack == null ? null : stack.peek();
        if (frame == null) return encodeError(0, "ccluau: no pending callback");

        dan200.computercraft.api.lua.MethodResult result;
        try {
            var arguments = decodeValues(args);
            result = frame.callback.resume(arguments);
        } catch (LuaException e) {
            popCallback(thread);
            return encodeException(e, frame.errorAdjust);
        } catch (Throwable t) {
            LOG.error(Logging.JAVA_ERROR, "Error resuming callback {}", frame.callback, t);
            popCallback(thread);
            return encodeError(0, "Java Exception Thrown: " + t);
        }

        var callback = result.getCallback();
        if (callback == null) {
            popCallback(thread);
        } else {
            frame.callback = callback;
        }

        var out = scratch.reset();
        out.u8(callback == null ? INVOKE_RETURN : INVOKE_YIELD);
        writeValues(out, result.getResult());
        return out;
    }

    /**
     * Copy a response into the shared response buffer, or stash it for {@link #takeLargeResponse()} if too large.
     */
    private int finishFast(ValueWriter out) {
        if (out.length() <= fastResp.capacity()) {
            fastResp.clear();
            fastResp.put(out.array(), 0, out.length());
            return out.length();
        }

        largeResponse = out.toArray();
        return -1;
    }

    /**
     * Fetch a response which was too large for the shared buffer. Called from native code.
     *
     * @return The encoded response.
     */
    byte[] takeLargeResponse() {
        var response = largeResponse;
        largeResponse = null;
        return response == null ? new byte[0] : response;
    }

    private ValueWriter encodeMethodResult(dan200.computercraft.api.lua.MethodResult result, long thread) {
        var callback = result.getCallback();
        if (callback != null) {
            callbacks.computeIfAbsent(thread, t -> new ArrayDeque<>()).push(new CallbackFrame(callback, result.getErrorAdjust()));
        }

        var out = scratch.reset();
        out.u8(callback == null ? INVOKE_RETURN : INVOKE_YIELD);
        writeValues(out, result.getResult());
        return out;
    }

    private void popCallback(long thread) {
        var stack = callbacks.get(thread);
        if (stack == null) return;
        stack.poll();
        if (stack.isEmpty()) callbacks.remove(thread);
    }

    private ValueWriter encodeException(LuaException exception, int adjust) {
        var level = exception.hasLevel() ? exception.getLevel() : 1;
        if (level > 0) level += adjust;
        var message = exception.getMessage();
        return encodeError(level, message == null ? "null" : message);
    }

    private ValueWriter encodeError(int level, String message) {
        var messageBytes = encodeString(message);
        var out = scratch.reset();
        out.u8(INVOKE_ERROR);
        out.i32(level);
        out.i32(messageBytes.length);
        out.bytes(messageBytes);
        return out;
    }

    // ------------------------------------------------------------------
    // Value encoding
    // ------------------------------------------------------------------

    private byte[] encodeValues(@Nullable Object @Nullable [] values) {
        var out = new ValueWriter();
        writeValues(out, values);
        return out.toArray();
    }

    private void writeValues(ValueWriter out, @Nullable Object @Nullable [] values) {
        if (values == null || values.length == 0) return;

        // Common case: only primitive values, no container bookkeeping needed.
        IdentityHashMap<Object, Integer> ids = null;
        var counter = NO_COUNTER;
        for (var value : values) {
            if (ids == null && isContainer(value)) {
                ids = new IdentityHashMap<>();
                counter = new int[1];
            }

            if (ids == null) {
                writePrimitive(out, value);
            } else {
                writeValue(out, value, ids, counter, 0);
            }
        }
    }

    private static boolean isContainer(@Nullable Object value) {
        return !(value == null || value instanceof Boolean || value instanceof Number || value instanceof String
            || value instanceof byte[] || value instanceof ByteBuffer);
    }

    private static void writePrimitive(ValueWriter out, @Nullable Object value) {
        if (value == null) {
            out.u8(T_NIL);
        } else if (value instanceof Boolean bool) {
            out.u8(bool ? T_TRUE : T_FALSE);
        } else if (value instanceof Number number) {
            out.u8(T_DOUBLE);
            out.f64(number.doubleValue());
        } else if (value instanceof String string) {
            out.u8(T_STRING);
            out.str(encodeString(string));
        } else if (value instanceof byte[] bytes) {
            out.u8(T_STRING);
            out.str(bytes);
        } else if (value instanceof ByteBuffer buffer) {
            var bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            out.u8(T_STRING);
            out.str(bytes);
        } else {
            throw new IllegalArgumentException("Not a primitive");
        }
    }

    private void writeValue(ValueWriter out, @Nullable Object value, IdentityHashMap<Object, Integer> ids, int[] counter, int depth) {
        if (value == null || depth > 64) {
            out.u8(T_NIL);
            return;
        }

        if (value instanceof Boolean bool) {
            out.u8(bool ? T_TRUE : T_FALSE);
        } else if (value instanceof Number number) {
            out.u8(T_DOUBLE);
            out.f64(number.doubleValue());
        } else if (value instanceof String string) {
            out.u8(T_STRING);
            out.str(encodeString(string));
        } else if (value instanceof byte[] bytes) {
            out.u8(T_STRING);
            out.str(bytes);
        } else if (value instanceof ByteBuffer buffer) {
            var bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            out.u8(T_STRING);
            out.str(bytes);
        } else {
            var existing = ids.get(value);
            if (existing != null) {
                out.u8(T_REF);
                out.i32(existing);
                return;
            }

            if (value instanceof Map<?, ?> map) {
                if (LuaUtil.isSingletonMap(map)) {
                    writeEmptyTable(out, counter);
                    return;
                }

                var id = counter[0]++;
                ids.put(value, id);
                out.u8(T_TABLE);
                out.i32(id);

                // Count encodable pairs first: nil keys/values are skipped.
                var count = 0;
                for (var entry : map.entrySet()) {
                    if (isEncodable(entry.getKey()) && isEncodable(entry.getValue())) count++;
                }
                out.i32(count);
                for (var entry : map.entrySet()) {
                    if (!isEncodable(entry.getKey()) || !isEncodable(entry.getValue())) continue;
                    writeValue(out, entry.getKey(), ids, counter, depth + 1);
                    writeValue(out, entry.getValue(), ids, counter, depth + 1);
                }
            } else if (value instanceof Collection<?> objects) {
                if (LuaUtil.isSingletonCollection(objects)) {
                    writeEmptyTable(out, counter);
                    return;
                }

                var id = counter[0]++;
                ids.put(value, id);
                out.u8(T_TABLE);
                out.i32(id);
                out.i32(objects.size());
                var i = 0;
                for (var child : objects) {
                    writeValue(out, (double) ++i, ids, counter, depth + 1);
                    writeValue(out, child, ids, counter, depth + 1);
                }
            } else if (value instanceof Object[] objects) {
                var id = counter[0]++;
                ids.put(value, id);
                out.u8(T_TABLE);
                out.i32(id);
                out.i32(objects.length);
                for (var i = 0; i < objects.length; i++) {
                    writeValue(out, (double) (i + 1), ids, counter, depth + 1);
                    writeValue(out, objects[i], ids, counter, depth + 1);
                }
            } else if (value instanceof ILuaFunction) {
                var id = counter[0]++;
                ids.put(value, id);
                out.u8(T_FUNCTION);
                out.i32(id);
                out.i32(getFunctionHandle(value));
            } else {
                var handle = getObjectHandle(value);
                if (handle < 0) {
                    LOG.warn(Logging.JAVA_ERROR, "Received unknown type '{}', returning nil.", value.getClass().getName());
                    out.u8(T_NIL);
                    return;
                }

                var id = counter[0]++;
                ids.put(value, id);
                var bound = boundObjects.get(handle);
                out.u8(T_OBJECT);
                out.i32(id);
                out.i32(handle);
                out.i32(bound.names.size());
                for (var name : bound.names) out.str(encodeString(name));
            }
        }
    }

    private void writeEmptyTable(ValueWriter out, int[] counter) {
        var id = counter[0]++;
        out.u8(T_TABLE);
        out.i32(id);
        out.i32(0);
    }

    private static boolean isEncodable(@Nullable Object value) {
        // Values which encode to nil are skipped when writing table pairs, mirroring CobaltLuaMachine.
        return value != null;
    }

    /**
     * Get (or create) the handle for an {@link ILuaFunction}.
     */
    private int getFunctionHandle(Object function) {
        var existing = boundHandles.get(function);
        if (existing != null) return existing;

        var bound = new BoundObject();
        bound.names.add(function.toString());
        bound.methods.add(FUNCTION_METHOD);
        bound.targets.add(function);
        var handle = boundObjects.size();
        boundObjects.add(bound);
        boundHandles.put(function, handle);
        return handle;
    }

    /**
     * Get (or create) the handle for an object with {@link LuaMethod}s (such as an {@link IDynamicLuaObject} or an
     * {@link ILuaAPI}), returning -1 if the object exposes no methods.
     */
    private int getObjectHandle(Object object) {
        var existing = boundHandles.get(object);
        if (existing != null) return existing;

        var bound = new BoundObject();
        var found = luaMethods.forEachMethod(object, (target, name, method, info) -> {
            bound.names.add(name);
            bound.methods.add(method);
            bound.targets.add(target);
        });
        if (!found) return -1;

        var handle = boundObjects.size();
        boundObjects.add(bound);
        boundHandles.put(object, handle);
        return handle;
    }

    // ------------------------------------------------------------------
    // Value decoding
    // ------------------------------------------------------------------

    private static final Object[] EMPTY_VALUES = new Object[0];
    private static final Map<Integer, Object> NO_REGISTRY = Map.of();
    private static final int[] NO_COUNTER = new int[0];

    private Object[] decodeValues(ByteBuffer buffer) {
        return decodeValues(buffer, false);
    }

    /**
     * Decode a buffer of values. When {@code rawStrings} is set, top-level strings are decoded as {@link LuaBytes}
     * rather than {@link String}, for consumption by {@link LuauArguments}.
     */
    private Object[] decodeValues(ByteBuffer buffer, boolean rawStrings) {
        if (!buffer.hasRemaining()) return EMPTY_VALUES;

        // Only allocate the sharing registry when a table is actually present.
        Map<Integer, Object> registry = null;
        var values = new Object[4];
        var count = 0;
        while (buffer.hasRemaining()) {
            var tag = buffer.get(buffer.position());
            if (registry == null && (tag == T_TABLE || tag == T_NAMED_TABLE || tag == T_REF)) registry = new HashMap<>();

            if (count == values.length) values = Arrays.copyOf(values, count * 2);
            if (rawStrings && tag == T_STRING) {
                buffer.get();
                values[count++] = decodeBytes(buffer);
            } else {
                values[count++] = decodeValue(buffer, registry == null ? NO_REGISTRY : registry);
            }
        }
        return count == values.length ? values : Arrays.copyOf(values, count);
    }

    private @Nullable Object decodeValue(ByteBuffer buffer, Map<Integer, Object> registry) {
        var tag = buffer.get();
        switch (tag) {
            case T_NIL:
                return null;
            case T_TRUE:
                return true;
            case T_FALSE:
                return false;
            case T_DOUBLE:
                return buffer.getDouble();
            case T_STRING:
                return decodeString(buffer);
            case T_TABLE:
            case T_NAMED_TABLE: {
                var id = buffer.getInt();
                var typeName = tag == T_NAMED_TABLE ? decodeString(buffer) : null;
                var pairs = buffer.getInt();
                var map = typeName == null ? new HashMap<Object, Object>(pairs) : new NamedMap(pairs, typeName);
                registry.put(id, map);
                for (var i = 0; i < pairs; i++) {
                    var key = decodeValue(buffer, registry);
                    var value = decodeValue(buffer, registry);
                    if (key != null && value != null) map.put(key, value);
                }
                return map;
            }
            case T_REF:
                return registry.get(buffer.getInt());
            default:
                throw new IllegalStateException("Malformed value buffer (tag " + tag + ")");
        }
    }

    // ------------------------------------------------------------------
    // Primitive helpers
    // ------------------------------------------------------------------

    /**
     * Encode a string using CC's Lua string conventions: characters are truncated to bytes, mirroring
     * {@link dan200.computercraft.api.lua.LuaValues#encode(String)}.
     */
    private static byte[] encodeString(String string) {
        var bytes = new byte[string.length()];
        for (var i = 0; i < bytes.length; i++) {
            var c = string.charAt(i);
            bytes[i] = c < 256 ? (byte) c : 63;
        }
        return bytes;
    }

    /**
     * A Lua string decoded as raw bytes, converting to a Java {@link String} only on demand. Byte access methods on
     * {@link LuauArguments} use the bytes directly, avoiding round-tripping through {@link String}.
     */
    static final class LuaBytes {
        final byte[] bytes;
        private @Nullable String string;

        LuaBytes(byte[] bytes) {
            this.bytes = bytes;
        }

        String string() {
            var s = string;
            if (s == null) s = string = new String(bytes, StandardCharsets.ISO_8859_1);
            return s;
        }
    }

    /**
     * A small direct-mapped cache of decoded strings: method names and arguments (sides, colours, "utc", ...) are
     * highly repetitive, so this avoids an allocation on almost every call.
     */
    private final byte[][] stringCacheKeys = new byte[128][];
    private final LuaBytes[] stringCacheValues = new LuaBytes[128];
    private final byte[] stringScratch = new byte[64];

    private String decodeString(ByteBuffer buffer) {
        return decodeBytes(buffer).string();
    }

    private LuaBytes decodeBytes(ByteBuffer buffer) {
        var length = buffer.getInt();
        if (length > stringScratch.length) {
            var bytes = new byte[length];
            buffer.get(bytes);
            return new LuaBytes(bytes);
        }

        buffer.get(stringScratch, 0, length);
        var hash = 1;
        for (var i = 0; i < length; i++) hash = hash * 31 + stringScratch[i];
        var slot = hash & 127;

        var key = stringCacheKeys[slot];
        if (key != null && key.length == length && Arrays.equals(key, 0, length, stringScratch, 0, length)) {
            return stringCacheValues[slot];
        }

        var bytes = Arrays.copyOf(stringScratch, length);
        var value = new LuaBytes(bytes);
        stringCacheKeys[slot] = bytes;
        stringCacheValues[slot] = value;
        return value;
    }

    /**
     * A reusable, growable little-endian binary writer.
     */
    private static final class ValueWriter {
        private byte[] buffer = new byte[8192];
        private int position;

        ValueWriter reset() {
            position = 0;
            return this;
        }

        private void ensure(int extra) {
            if (position + extra > buffer.length) {
                buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, position + extra));
            }
        }

        void u8(int value) {
            ensure(1);
            buffer[position++] = (byte) value;
        }

        void i32(int value) {
            ensure(4);
            buffer[position] = (byte) value;
            buffer[position + 1] = (byte) (value >> 8);
            buffer[position + 2] = (byte) (value >> 16);
            buffer[position + 3] = (byte) (value >> 24);
            position += 4;
        }

        void f64(double value) {
            ensure(8);
            var bits = Double.doubleToLongBits(value);
            for (var i = 0; i < 8; i++) buffer[position + i] = (byte) (bits >> (8 * i));
            position += 8;
        }

        void bytes(byte[] value) {
            ensure(value.length);
            System.arraycopy(value, 0, buffer, position, value.length);
            position += value.length;
        }

        void str(byte[] value) {
            i32(value.length);
            bytes(value);
        }

        int length() {
            return position;
        }

        byte[] array() {
            return buffer;
        }

        byte[] toArray() {
            return Arrays.copyOf(buffer, position);
        }
    }

    private static final class BoundObject {
        final List<String> names = new ArrayList<>();
        final List<LuaMethod> methods = new ArrayList<>();
        final List<Object> targets = new ArrayList<>();
    }

    /**
     * A table with a custom type name (from its metatable's {@code __name} field), used in "bad argument" messages.
     */
    private static final class NamedMap extends HashMap<Object, Object> {
        private final String typeName;

        NamedMap(int capacity, String typeName) {
            super(capacity);
            this.typeName = typeName;
        }

        String typeName() {
            return typeName;
        }
    }

    private static final class CallbackFrame {
        ILuaCallback callback;
        final int errorAdjust;

        CallbackFrame(ILuaCallback callback, int errorAdjust) {
            this.callback = callback;
            this.errorAdjust = errorAdjust;
        }
    }

    /**
     * A simple {@link IArguments} implementation over eagerly-converted values.
     */
    private static final class LuauArguments implements IArguments {
        private final Object[] values;
        private final int offset;

        LuauArguments(Object[] values, int offset) {
            this.values = values;
            this.offset = offset;
        }

        @Override
        public int count() {
            return Math.max(values.length - offset, 0);
        }

        /**
         * Get a value without materialising {@link LuaBytes} into {@link String}s.
         */
        private @Nullable Object raw(int index) {
            var i = offset + index;
            return index < 0 || i >= values.length ? null : values[i];
        }

        @Nullable
        @Override
        public Object get(int index) {
            var value = raw(index);
            if (value instanceof LuaBytes bytes) {
                var string = bytes.string();
                values[offset + index] = string;
                return string;
            }
            return value;
        }

        @Override
        public String getType(int index) {
            var value = raw(index);
            if (value instanceof LuaBytes) return "string";
            if (value instanceof NamedMap named) return named.typeName();
            return dan200.computercraft.api.lua.LuaValues.getType(value);
        }

        @Override
        public IArguments drop(int count) {
            if (count < 0) throw new IllegalStateException("count cannot be negative");
            if (count == 0) return this;
            return new LuauArguments(values, offset + count);
        }

        @Override
        public ByteBuffer getBytes(int index) throws LuaException {
            var value = raw(index);
            if (value instanceof LuaBytes bytes) return ByteBuffer.wrap(bytes.bytes).asReadOnlyBuffer();
            // Strings are decoded as ISO-8859-1, so this round-trips the original bytes exactly.
            return ByteBuffer.wrap(getString(index).getBytes(StandardCharsets.ISO_8859_1)).asReadOnlyBuffer();
        }

        @Override
        public Optional<ByteBuffer> optBytes(int index) throws LuaException {
            return raw(index) == null ? Optional.empty() : Optional.of(getBytes(index));
        }

        @Override
        public ByteBuffer getBytesCoerced(int index) throws LuaException {
            var value = raw(index);
            if (value instanceof LuaBytes bytes) return ByteBuffer.wrap(bytes.bytes).asReadOnlyBuffer();
            return IArguments.super.getBytesCoerced(index);
        }

        @Override
        public String getStringCoerced(int index) throws LuaException {
            var value = raw(index);
            if (value instanceof LuaBytes bytes) return bytes.string();
            return IArguments.super.getStringCoerced(index);
        }

        @Override
        public Object[] getAll() {
            var count = count();
            var result = new Object[count];
            for (var i = 0; i < count; i++) result[i] = get(i);
            return result;
        }
    }
}
