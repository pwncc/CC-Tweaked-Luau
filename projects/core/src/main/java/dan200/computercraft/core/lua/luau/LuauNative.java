// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.core.lua.luau;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/**
 * JNI bindings to the {@code ccluau} native library, which embeds the Luau VM and compiler.
 * <p>
 * The native library is loaded from the {@code cc.luau.native} system property if set, and otherwise extracted from
 * the {@code lib/ccluau/} classpath resources for the current platform.
 *
 * @see LuauMachine
 */
final class LuauNative {
    private static final Logger LOG = LoggerFactory.getLogger(LuauNative.class);

    static final int FLAG_SOFT_ABORT = 1;
    static final int FLAG_HARD_ABORT = 2;
    static final int FLAG_PAUSE = 4;

    static final byte RESUME_DEAD = 0;
    static final byte RESUME_YIELD = 1;
    static final byte RESUME_BREAK = 2;
    static final byte RESUME_ERROR = 3;

    private static volatile @Nullable Boolean loaded;

    /**
     * Why the library could not be loaded, if it could not be. Held as a description rather than the {@link Throwable}
     * itself, so we do not pin a stack trace (and its class loaders) in a static field for the life of the process. The
     * full trace is logged when the load fails. Written under the {@code LuauNative} monitor before {@link #loaded}, so
     * any thread that reads {@code loaded == false} also sees this.
     */
    private static @Nullable String failure;

    private LuauNative() {
    }

    /**
     * Attempt to load the native library, returning whether it is available on this platform.
     * <p>
     * This is only for callers that must tolerate its absence, such as tests that skip themselves. Production code
     * should use {@link #checkAvailable()}: there is no fallback runtime, so a missing library is a fatal error rather
     * than something to degrade around.
     *
     * @return Whether the Luau runtime can be used.
     */
    static boolean isAvailable() {
        var state = loaded;
        if (state != null) return state;

        synchronized (LuauNative.class) {
            state = loaded;
            if (state != null) return state;

            try {
                load();
                loaded = true;
                return true;
            } catch (Throwable e) {
                LOG.error("Cannot load the Luau native library for platform {}.", platform(), e);
                failure = e.toString();
                loaded = false;
                return false;
            }
        }
    }

    /**
     * Ensure the native library is loaded, throwing a descriptive error if it is not.
     * <p>
     * This mod runs Lua exclusively on the Luau runtime. If the library cannot be loaded there is nothing to fall back
     * to, so we fail loudly at startup rather than letting every computer break at boot.
     *
     * @throws IllegalStateException If the library could not be loaded.
     */
    static void checkAvailable() {
        if (isAvailable()) return;

        throw new IllegalStateException(
            "Cannot load the Luau native library for platform " + platform() + " (looked for the classpath resource "
                + libraryResource() + "). This mod has no other Lua runtime, so computers cannot be started.\n"
                + "Cause: " + failure + " (see the log above for the full stack trace).\n"
                + "If this platform is unsupported, a library must be built for it; see "
                + "projects/core/src/main/native/ccluau/README.md.\n"
                + "If the library is present but will not load, the extraction directory may be mounted noexec: set "
                + "-Dcc.luau.cache=<dir> to a writable directory that permits execution, or -Dcc.luau.native=<path> to "
                + "load a library directly."
        );
    }

    private static void load() throws IOException {
        var override = System.getProperty("cc.luau.native");
        if (override != null) {
            System.load(Path.of(override).toAbsolutePath().toString());
            return;
        }

        var resource = libraryResource();
        var url = LuauNative.class.getClassLoader().getResource(resource);
        if (url == null) throw new IOException("No such resource " + resource);

        byte[] library;
        try (var stream = url.openStream()) {
            library = stream.readAllBytes();
        }

        // System.load requires an on-disk path, so extract to a stable content-addressed cache.
        // A loaded DLL cannot be deleted on Windows, so a fresh temporary file would leak one copy
        // per launch; reusing one file per library version also avoids re-triggering virus
        // scanners with a brand-new executable on every run.
        var cacheRoot = Path.of(System.getProperty("cc.luau.cache", System.getProperty("java.io.tmpdir")));
        var target = cacheRoot.resolve("ccluau").resolve(hash(library)).resolve(libraryName());
        if (!isCached(target, library)) extract(target, library);
        System.load(target.toAbsolutePath().toString());
    }

    private static boolean isCached(Path target, byte[] library) throws IOException {
        return Files.exists(target) && Arrays.equals(Files.readAllBytes(target), library);
    }

    private static void extract(Path target, byte[] library) throws IOException {
        Files.createDirectories(target.getParent());
        var temp = Files.createTempFile(target.getParent(), "extract", ".tmp");
        try {
            Files.write(temp, library);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // Another process may have extracted (and locked) the library while we were
                // writing. If the target now has the right contents, use it.
                if (!isCached(target, library)) throw e;
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String hash(byte[] contents) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(contents);
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }

    private static String platform() {
        var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        var arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

        String osName;
        if (os.contains("win")) {
            osName = "windows";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osName = "macos";
        } else {
            osName = "linux";
        }

        var archName = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "arm64";
            default -> arch;
        };

        return osName + "-" + archName;
    }

    /**
     * The classpath resource holding the library for the current platform.
     *
     * @return The resource path.
     */
    private static String libraryResource() {
        return "lib/ccluau/" + platform() + "/" + libraryName();
    }

    private static String libraryName() {
        var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "ccluau.dll";
        if (os.contains("mac") || os.contains("darwin")) return "libccluau.dylib";
        return "libccluau.so";
    }

    /**
     * Create a new Luau state. The returned pointer must be freed with {@link #closeState(long)}.
     *
     * @param machine The machine to dispatch {@code invoke}/{@code resumeCallback} calls to.
     * @return An opaque pointer to the native machine state, or 0 on failure.
     */
    static native long createState(LuauMachine machine);

    /**
     * Destroy a state created with {@link #createState(LuauMachine)}. Must not be called while a
     * {@link #resume(long, long, byte[])} is in progress.
     *
     * @param state The state to destroy.
     */
    static native void closeState(long state);

    /**
     * Get one of the shared direct buffers used for the fast call path.
     *
     * @param state The current state.
     * @param args  {@code true} for the argument buffer, {@code false} for the response buffer.
     * @return The shared buffer.
     */
    static native java.nio.ByteBuffer getBuffer(long state, boolean args);

    /**
     * Update the interrupt flags for a state. Safe to call from any thread.
     *
     * @param state The current state.
     * @param flags A combination of {@code FLAG_*} bits.
     */
    static native void setFlags(long state, int flags);

    /**
     * Set a global variable to an encoded value.
     *
     * @param state The current state.
     * @param name  The name of the global, as Lua-encoded bytes.
     * @param value A single encoded value.
     * @return Whether the global was set successfully.
     */
    static native boolean setGlobal(long state, byte[] name, byte[] value);

    /**
     * Compile and load the BIOS, creating the machine's main coroutine.
     *
     * @param state     The current state.
     * @param bios      The BIOS source code.
     * @param chunkName The chunk name, typically {@code @bios.lua}.
     * @return A pointer to the main coroutine.
     * @throws dan200.computercraft.core.lua.MachineException If the BIOS could not be compiled.
     */
    static native long loadBios(long state, byte[] bios, String chunkName) throws dan200.computercraft.core.lua.MachineException;

    /**
     * Resume a coroutine.
     *
     * @param state  The current state.
     * @param thread The coroutine to resume.
     * @param args   The encoded arguments, or {@code null} to continue from a break.
     * @return A response buffer, starting with a {@code RESUME_*} status byte.
     */
    static native byte @Nullable [] resume(long state, long thread, byte @Nullable [] args);

    /**
     * Get a traceback of the given thread, for debugging.
     *
     * @param thread The thread to inspect.
     * @return The traceback.
     */
    static native String debugTrace(long thread);

    /**
     * Create the native terminal for this machine and install the {@code term} global. All {@code term} methods then
     * execute natively, without crossing into Java.
     *
     * @param state         The current state.
     * @param width         The terminal width.
     * @param height        The terminal height.
     * @param colour        Whether the terminal supports colour.
     * @param cursorX       The cursor's (0-based) x position.
     * @param cursorY       The cursor's (0-based) y position.
     * @param curFg         The current text colour (palette index).
     * @param curBg         The current background colour (palette index).
     * @param blink         Whether the cursor is blinking.
     * @param palette       The current palette, as 16 x 3 doubles.
     * @param nativePalette The default palette, as 16 x 3 doubles.
     * @param text          The current text contents, {@code height * width} bytes.
     * @param fg            The current text colours.
     * @param bg            The current background colours.
     */
    static native void initTerm(
        long state, int width, int height, boolean colour,
        int cursorX, int cursorY, int curFg, int curBg, boolean blink,
        double[] palette, double[] nativePalette,
        byte[] text, byte[] fg, byte[] bg
    );

    /**
     * Refresh the native terminal's size and contents from Java, e.g. after a resize.
     *
     * @param state  The current state.
     * @param width  The new width.
     * @param height The new height.
     * @param text   The text contents, {@code height * width} bytes.
     * @param fg     The text colours.
     * @param bg     The background colours.
     */
    static native void termSetContent(long state, int width, int height, byte[] text, byte[] fg, byte[] bg);

    /**
     * Encode the native terminal's dirty state into the shared response buffer, clearing the dirty flags.
     *
     * @param state The current state.
     * @return The number of encoded bytes, or 0 if nothing has changed.
     */
    static native int syncTerm(long state);

    /**
     * Wrap {@code os.epoch}/{@code os.time}/{@code os.day} with native implementations of the utc/local locales.
     *
     * @param state The current state.
     */
    static native void installFastOs(long state);

    /**
     * Create the native redstone mirror and install the {@code redstone}/{@code rs} globals.
     *
     * @param state   The current state.
     * @param inputs  The current inputs: 6 analog levels, then 6 bundled masks.
     * @param outputs The current outputs, in the same layout.
     */
    static native void installRedstone(long state, int[] inputs, int[] outputs);

    /**
     * Update the native redstone input mirror.
     *
     * @param state  The current state.
     * @param inputs The current inputs: 6 analog levels, then 6 bundled masks.
     */
    static native void setRedstoneInput(long state, int[] inputs);
}
