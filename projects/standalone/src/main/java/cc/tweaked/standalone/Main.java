// SPDX-FileCopyrightText: 2023 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package cc.tweaked.standalone;


import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.core.ComputerContext;
import dan200.computercraft.core.CoreConfig;
import dan200.computercraft.core.apis.IAPIEnvironment;
import dan200.computercraft.core.apis.http.options.Action;
import dan200.computercraft.core.apis.http.options.AddressRule;
import dan200.computercraft.core.computer.Computer;
import dan200.computercraft.core.filesystem.FileMount;
import dan200.computercraft.core.filesystem.FileSystemException;
import dan200.computercraft.core.filesystem.WritableFileMount;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.core.terminal.TextBuffer;
import dan200.computercraft.core.util.Colour;
import org.apache.commons.cli.*;
import org.apache.commons.cli.help.HelpFormatter;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Checks;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * A standalone UI for CC: Tweaked computers.
 * <p>
 * This displays a computer terminal using OpenGL and GLFW, without having to load all of Minecraft.
 * <p>
 * The rendering code largely follows that of monitors: we store the terminal data in a TBO, performing the bulk of the
 * rendering logic within the fragment shader ({@code terminal.fsh}).
 */
public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final boolean DEBUG = Checks.DEBUG;

    private static Path parsePath(String path) throws ParseException {
        try {
            return Path.of(path);
        } catch (InvalidPathException e) {
            throw new ParseException("'" + path + "' is not a valid path (" + e.getReason() + ")");
        }
    }

    private record TermSize(int width, int height) {
        private static final TermSize DEFAULT = new TermSize(51, 19);
        private static final Pattern PATTERN = Pattern.compile("^(\\d+)x(\\d+)$");

        private static TermSize parse(String value) throws ParseException {
            var matcher = TermSize.PATTERN.matcher(value);
            if (!matcher.matches()) throw new ParseException("'" + value + "' is not a valid terminal size.");

            return new TermSize(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        }
    }

    private record MountPaths(Path src, String dest) {
        private static final Pattern PATTERN = Pattern.compile("^([^:]+):([^:]+)$");

        private static MountPaths parse(String value) throws ParseException {
            var matcher = MountPaths.PATTERN.matcher(value);
            if (!matcher.matches()) throw new ParseException("'" + value + "' is not a mount spec.");

            return new MountPaths(parsePath(matcher.group(1)), matcher.group(2));
        }
    }

    private interface ValueParser<T> {
        T parse(String path) throws ParseException;
    }

    @Contract("_, _, _, !null -> !null")
    private static <T> @Nullable T getParsedOptionValue(CommandLine cli, Option opt, ValueParser<T> parser, @Nullable T defaultValue) throws ParseException {
        return cli.hasOption(opt) ? parser.parse(cli.getOptionValue(opt)) : defaultValue;
    }

    private static <T> List<T> getParsedOptionValues(CommandLine cli, Option opt, ValueParser<T> parser) throws ParseException {
        var values = cli.getOptionValues(opt);
        if (values == null) return List.of();

        List<T> parsedValues = new ArrayList<>(values.length);
        for (var value : values) parsedValues.add(parser.parse(value));
        return List.copyOf(parsedValues);
    }

    public static void main(String[] args) throws InterruptedException {
        var options = new Options();
        Option resourceOpt, computerOpt, termSizeOpt, allowLocalDomainsOpt, helpOpt, mountOpt, mountRoOpt;
        options.addOption(resourceOpt = Option.builder("r").argName("PATH").longOpt("resources").hasArg()
            .desc("The path to the resources directory")
            .get());
        options.addOption(computerOpt = Option.builder("c").argName("PATH").longOpt("computer").hasArg()
            .desc("The root directory of the computer. Defaults to a temporary directory.")
            .get());
        options.addOption(termSizeOpt = Option.builder("t").argName("WIDTHxHEIGHT").longOpt("term-size").hasArg()
            .desc("The size of the terminal, defaults to 51x19.")
            .get());
        options.addOption(allowLocalDomainsOpt = Option.builder("L").longOpt("allow-local-domains")
            .desc("Allow accessing local domains with the HTTP API.")
            .get());
        options.addOption(mountOpt = Option.builder().longOpt("mount").hasArg().argName("SRC:DEST")
            .desc("Mount a folder SRC at directory DEST on the computer.")
            .get());
        options.addOption(mountRoOpt = Option.builder().longOpt("mount-ro").hasArg().argName("SRC:DEST")
            .desc("Mount a read-only folder SRC at directory DEST on the computer.")
            .get());

        options.addOption(helpOpt = Option.builder("h").longOpt("help")
            .desc("Print help message")
            .get());

        Path resourcesDirectory;
        Path computerDirectory;
        TermSize termSize;
        boolean allowLocalDomains;
        List<MountPaths> mounts, readOnlyMounts;
        try {
            var cli = new DefaultParser().parse(options, args);
            if (cli.hasOption(helpOpt)) {
                try {
                    HelpFormatter.builder().get().printHelp("standalone.jar", "", options, "", true);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return;
            }
            if (!cli.hasOption(resourceOpt)) throw new ParseException("--resources directory is required");

            resourcesDirectory = parsePath(cli.getOptionValue(resourceOpt));
            computerDirectory = getParsedOptionValue(cli, computerOpt, Main::parsePath, null);
            termSize = getParsedOptionValue(cli, termSizeOpt, TermSize::parse, TermSize.DEFAULT);
            allowLocalDomains = cli.hasOption(allowLocalDomainsOpt);
            mounts = getParsedOptionValues(cli, mountOpt, MountPaths::parse);
            readOnlyMounts = getParsedOptionValues(cli, mountRoOpt, MountPaths::parse);
        } catch (ParseException e) {
            System.err.println(e.getLocalizedMessage());
            System.err.println(HelpFormatter.builder().get().toSyntaxOptions(options));

            System.exit(1);
            return;
        }

        if (allowLocalDomains) {
            CoreConfig.httpRules = List.of(AddressRule.parse("*", OptionalInt.empty(), Action.ALLOW.toPartial()));
        }

        var context = ComputerContext.builder(new StandaloneGlobalEnvironment(resourcesDirectory)).build();
        try (var gl = new GLObjects()) {
            var isDirty = new AtomicBoolean(true);
            var computer = new Computer(
                context,
                new StandaloneComputerEnvironment(computerDirectory),
                new Terminal(termSize.width(), termSize.height(), true, () -> isDirty.set(true)),
                0
            );
            computer.addApi(new FileMounter(computer.getAPIEnvironment(), readOnlyMounts, mounts));
            computer.turnOn();

            runAndInit(gl, computer, isDirty);
        } catch (Exception e) {
            LOG.error("A fatal error occurred", e);
            System.exit(1);
        } finally {
            context.ensureClosed(1, TimeUnit.SECONDS);
        }
    }

    /**
     * An {@link ILuaAPI} which is used to mount additional files, but does not expose any new globals/methods.
     */
    private static final class FileMounter implements ILuaAPI {
        private final IAPIEnvironment environment;
        private final List<MountPaths> readOnlyMounts;
        private final List<MountPaths> mounts;

        FileMounter(IAPIEnvironment environment, List<MountPaths> readOnlyMounts, List<MountPaths> mounts) {
            this.environment = environment;
            this.readOnlyMounts = readOnlyMounts;
            this.mounts = mounts;
        }

        @Override
        public String[] getNames() {
            return new String[0];
        }

        @Override
        public void startup() {
            try {
                var fs = environment.getFileSystem();
                for (var mount : readOnlyMounts) {
                    fs.mount(mount.dest(), mount.dest(), new FileMount(mount.src()));
                }
                for (var mount : mounts) {
                    fs.mount(mount.dest(), mount.dest(), new WritableFileMount(mount.src().toFile(), 1_000_000));
                }
            } catch (FileSystemException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final int SCALE = 2;
    private static final int MARGIN = 2;
    private static final int PIXEL_WIDTH = 6;
    private static final int PIXEL_HEIGHT = 9;

    // Offsets for our shader attributes - see also terminal.vsh.
    private static final int ATTRIBUTE_POSITION = 0;
    private static final int ATTRIBUTE_UV = 1;

    // Offsets for our shader uniforms - see also terminal.fsh.
    private static final int UNIFORM_FONT = 0;
    private static final int UNIFORM_TERMINAL = 1;
    private static final int UNIFORM_TERMINAL_DATA = 0;
    private static final int UNIFORM_CURSOR_BLINK = 2;

    // Offsets for our textures.
    private static final int TEXTURE_FONT = 0;
    private static final int TEXTURE_TBO = 1;

    /**
     * Size of the terminal UBO.
     *
     * @see #setUniformData(ByteBuffer, Terminal)
     * @see #UNIFORM_TERMINAL_DATA
     */
    private static final int TERMINAL_DATA_SIZE = 4 * 4 * 16 + 4 + 4 + 2 * 4 + 4;

    private static void runAndInit(GLObjects gl, Computer computer, AtomicBoolean isDirty) throws IOException {
        var terminal = computer.getEnvironment().getTerminal();
        var inputState = new InputState(computer);

        // Setup an error callback.
        GLFWErrorCallback.createPrint(System.err).set();
        gl.add(() -> Objects.requireNonNull(glfwSetErrorCallback(null)).free());

        // Initialize GLFW.
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");
        gl.add(GLFW::glfwTerminate);

        // Configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // Hide the window - we manually show it later.

        // Configure OpenGL
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_API);
        glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_NATIVE_CONTEXT_API);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        if (DEBUG) glfwWindowHint(GLFW_CONTEXT_DEBUG, GLFW_TRUE);

        // The base (boot-time) terminal size determines the window's aspect ratio. The terminal may
        // later be resized (term.setResolution); the renderer squeezes more cells into the same
        // window, so mouse positions are mapped through the current terminal size.
        var baseWidth = terminal.getWidth();
        var baseHeight = terminal.getHeight();
        var basePixelWidth = MARGIN * 2 + PIXEL_WIDTH * baseWidth;
        var basePixelHeight = MARGIN * 2 + PIXEL_HEIGHT * baseHeight;

        // Pick the largest integer scale which fits comfortably on the primary monitor.
        var initialScale = SCALE;
        var videoMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (videoMode != null) {
            initialScale = Math.max(SCALE, Math.min(
                (int) (videoMode.width() * 0.8 / basePixelWidth),
                (int) (videoMode.height() * 0.8 / basePixelHeight)
            ));
        }

        var window = glfwCreateWindow(
            initialScale * basePixelWidth, initialScale * basePixelHeight,
            "CC: Tweaked - Standalone", NULL, NULL
        );
        if (window == NULL) throw new RuntimeException("Failed to create the GLFW window");
        gl.add(() -> {
            glfwFreeCallbacks(window);
            glfwDestroyWindow(window);
        });

        // The window is freely resizable, but always keeps the terminal's aspect ratio.
        glfwSetWindowAspectRatio(window, basePixelWidth, basePixelHeight);
        glfwSetWindowSizeLimits(window, basePixelWidth, basePixelHeight, GLFW_DONT_CARE, GLFW_DONT_CARE);

        // Get the window size so we can centre it.
        try (var stack = MemoryStack.stackPush()) {
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            glfwGetWindowSize(window, width, height);

            if (videoMode != null) {
                glfwSetWindowPos(window, (videoMode.width() - width.get(0)) / 2, (videoMode.height() - height.get(0)) / 2);
            }
        }

        // Live window size (in screen coordinates, which cursor positions are also measured in).
        var windowSize = new int[]{ initialScale * basePixelWidth, initialScale * basePixelHeight };

        // Add all our callbacks
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> inputState.onKeyEvent(w, key, action, mods));
        glfwSetCharModsCallback(window, (w, codepoint, mods) -> inputState.onCharEvent(codepoint));
        glfwSetDropCallback(window, (w, count, files) -> inputState.onFileDrop(count, files));
        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> inputState.onMouseClick(button, action));
        glfwSetCursorPosCallback(window, (w, x, y) -> {
            // Map through the quad's UV extents: the terminal (plus scaled margins) spans the window.
            double termW = terminal.getWidth(), termH = terminal.getHeight();
            var marginX = MARGIN * termW / baseWidth;
            var marginY = MARGIN * termH / baseHeight;
            var rawX = (x / windowSize[0] * (PIXEL_WIDTH * termW + 2 * marginX) - marginX) / PIXEL_WIDTH;
            var rawY = (y / windowSize[1] * (PIXEL_HEIGHT * termH + 2 * marginY) - marginY) / PIXEL_HEIGHT;
            var subX = Math.min(Math.max((int) ((rawX - Math.floor(rawX)) * 2), 0), 1);
            var subY = Math.min(Math.max((int) ((rawY - Math.floor(rawY)) * 3), 0), 2);
            inputState.onMouseMove((int) rawX, (int) rawY, subX, subY);
        });
        glfwSetCursorEnterCallback(window, (w, entered) -> {
            if (!entered) inputState.onMouseLeave();
        });
        glfwSetScrollCallback(window, (w, xOffset, yOffset) -> inputState.onMouseScroll(yOffset));
        glfwSetWindowSizeCallback(window, (w, newWidth, newHeight) -> {
            windowSize[0] = newWidth;
            windowSize[1] = newHeight;
        });

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1); // Enable v-sync
        glfwShowWindow(window);

        // Initialise the OpenGL state
        GL.createCapabilities();

        // Registered only once the context is ready: resizes update the viewport and force a redraw.
        glfwSetFramebufferSizeCallback(window, (w, fbWidth, fbHeight) -> {
            glViewport(0, 0, fbWidth, fbHeight);
            isDirty.set(true);
        });
        if (DEBUG) {
            GLUtil.setupDebugMessageCallback();
            glDebugMessageControl(GL_DONT_CARE, GL_DONT_CARE, GL_DONT_CARE, (int[]) null, true);
        }

        // Load the font texture and bind it.
        var fontTexture = gl.loadTexture("assets/computercraft/textures/gui/term_font.png");
        glBindTextureUnit(TEXTURE_FONT, fontTexture);

        // Create a texture and backing buffer for our TBO and bind it.
        var termBuffer = gl.createBuffer("Terminal TBO");
        var termTexture = gl.createTexture(GL_TEXTURE_BUFFER, "Terminal TBO");
        glTextureBuffer(termTexture, GL_R8UI, termBuffer);
        glBindTextureUnit(TEXTURE_TBO, termTexture);

        // Load the main terminal shader.
        var termProgram = compileProgram(gl);
        glProgramUniform1i(termProgram, UNIFORM_FONT, TEXTURE_FONT);
        glProgramUniform1i(termProgram, UNIFORM_TERMINAL, TEXTURE_TBO);
        glProgramUniform1i(termProgram, UNIFORM_CURSOR_BLINK, 0);

        // Create a backing buffer for our UBO and bind it.
        var termDataBuffer = gl.createBuffer("Terminal Data");
        glBindBufferBase(GL_UNIFORM_BUFFER, UNIFORM_TERMINAL_DATA, termDataBuffer);

        // Create our vertex buffer object. This is just a simple triangle strip of our four corners.
        // The UVs span the terminal's current pixel extent: when the terminal is resized we rebuild
        // them, squeezing more (smaller) cells into the same window.
        var termVertices = gl.createBuffer("Terminal Vertices");
        glNamedBufferData(termVertices, new float[]{
            -1.0f, 1.0f, -MARGIN, -MARGIN,
            -1.0f, -1.0f, -MARGIN, PIXEL_HEIGHT * terminal.getHeight() + MARGIN,
            1.0f, 1.0f, PIXEL_WIDTH * terminal.getWidth() + MARGIN, -MARGIN,
            1.0f, -1.0f, PIXEL_WIDTH * terminal.getWidth() + MARGIN, PIXEL_HEIGHT * terminal.getHeight() + MARGIN,
        }, GL_STATIC_DRAW);
        int quadWidth = terminal.getWidth(), quadHeight = terminal.getHeight();

        // And our VBA
        var termVertexArray = gl.createVertexArray("Terminal VAO");
        glEnableVertexArrayAttrib(termVertexArray, ATTRIBUTE_POSITION);
        glVertexArrayAttribFormat(termVertexArray, ATTRIBUTE_POSITION, 2, GL_FLOAT, false, 0); // Position
        glEnableVertexArrayAttrib(termVertexArray, ATTRIBUTE_UV);
        glVertexArrayAttribFormat(termVertexArray, ATTRIBUTE_UV, 2, GL_FLOAT, false, 8); // UV
        // FIXME: Can we merge this into one call?
        glVertexArrayVertexBuffer(termVertexArray, ATTRIBUTE_POSITION, termVertices, 0, 16);
        glVertexArrayVertexBuffer(termVertexArray, ATTRIBUTE_UV, termVertices, 0, 16);

        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

        // Pixel-art arrow cursors, swapped in while the program captures the mouse (and draws its
        // own UI around it). Rendering the pointer at this layer keeps it perfectly smooth,
        // whatever the terminal's cell grid looks like.
        var arrowCursors = new HashMap<Integer, Long>();
        gl.add(() -> {
            for (var cursor : arrowCursors.values()) {
                if (cursor != NULL) glfwDestroyCursor(cursor);
            }
        });

        // We run a single loop for both rendering and ticking computers. The computer ticks at a
        // fixed 20Hz, but events are pumped and the terminal redrawn far more often, so the
        // display follows input with only a few milliseconds of latency.
        var lastTickTime = GLFW.glfwGetTime();
        var lastCursorBlink = false;
        var cursorScale = 0; // 0 = the normal system cursor
        while (!glfwWindowShouldClose(window)) {
            var now = GLFW.glfwGetTime();
            if (now - lastTickTime >= 0.05) {
                lastTickTime = now;
                computer.tick();
                inputState.update();
            }

            var needRedraw = false;

            // Update the terminal data if needed.
            if (isDirty.getAndSet(false)) {
                needRedraw = true;

                // Remap the quad if the terminal has been resized (term.setResolution).
                if (terminal.getWidth() != quadWidth || terminal.getHeight() != quadHeight) {
                    quadWidth = terminal.getWidth();
                    quadHeight = terminal.getHeight();
                    var marginX = (float) MARGIN * quadWidth / baseWidth;
                    var marginY = (float) MARGIN * quadHeight / baseHeight;
                    glNamedBufferData(termVertices, new float[]{
                        -1.0f, 1.0f, -marginX, -marginY,
                        -1.0f, -1.0f, -marginX, PIXEL_HEIGHT * quadHeight + marginY,
                        1.0f, 1.0f, PIXEL_WIDTH * quadWidth + marginX, -marginY,
                        1.0f, -1.0f, PIXEL_WIDTH * quadWidth + marginX, PIXEL_HEIGHT * quadHeight + marginY,
                    }, GL_STATIC_DRAW);
                }

                try (var stack = MemoryStack.stackPush()) {
                    var buffer = stack.malloc(terminal.getWidth() * terminal.getHeight() * 3);
                    writeTerminalContents(buffer, terminal);
                    glNamedBufferData(termBuffer, buffer, GL_STATIC_DRAW);
                }

                try (var stack = MemoryStack.stackPush()) {
                    var buffer = stack.malloc(TERMINAL_DATA_SIZE);
                    setUniformData(buffer, terminal);
                    glNamedBufferData(termDataBuffer, buffer, GL_STATIC_DRAW);
                }
            }

            // Swap in the arrow cursor while the program captures the mouse (term.setMouseCapture),
            // scaled with the window so it keeps a sensible physical size.
            var wantScale = terminal.getMouseCapture() ? Math.max(2, Math.round(windowSize[1] / 400f)) : 0;
            if (wantScale != cursorScale) {
                cursorScale = wantScale;
                var cursor = wantScale == 0 ? NULL : arrowCursors.computeIfAbsent(wantScale, Main::createArrowCursor);
                glfwSetCursor(window, cursor);
            }

            // Update the cursor blink if needed.
            var cursorBlink = terminal.getCursorBlink() && (int) (now * 20 / 8) % 2 == 0;
            if (cursorBlink != lastCursorBlink) {
                needRedraw = true;
                glProgramUniform1i(termProgram, UNIFORM_CURSOR_BLINK, cursorBlink ? 1 : 0);
                lastCursorBlink = cursorBlink;
            }

            // Redraw the terminal if needed.
            if (needRedraw) {
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear the framebuffer

                glUseProgram(termProgram);
                glBindVertexArray(termVertexArray);
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

                glfwSwapBuffers(window); // swap the color buffers
            }

            // Pump events (waking early on input) and loop again; the 5ms timeout bounds both input
            // latency and idle CPU usage.
            GLFW.glfwWaitEventsTimeout(0.005);
        }
    }

    /**
     * The classic arrow pointer. {@code X} is the black outline, {@code o} the white fill.
     */
    private static final String[] ARROW_PATTERN = {
        "X          ",
        "XX         ",
        "XoX        ",
        "XooX       ",
        "XoooX      ",
        "XooooX     ",
        "XoooooX    ",
        "XooooooX   ",
        "XoooooooX  ",
        "XooooooooX ",
        "XoooooXXXXX",
        "XooXooX    ",
        "XoX XooX   ",
        "XX  XooX   ",
        "X    XooX  ",
        "     XooX  ",
        "      XX   ",
    };

    private static long createArrowCursor(int scale) {
        var width = ARROW_PATTERN[0].length() * scale;
        var height = ARROW_PATTERN.length * scale;
        try (var stack = MemoryStack.stackPush()) {
            var pixels = stack.malloc(width * height * 4);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var kind = ARROW_PATTERN[y / scale].charAt(x / scale);
                    var fill = kind == 'o' ? (byte) 0xFF : (byte) 0;
                    pixels.put(fill).put(fill).put(fill).put(kind != ' ' ? (byte) 0xFF : (byte) 0);
                }
            }
            pixels.flip();
            var image = GLFWImage.malloc(stack).width(width).height(height).pixels(pixels);
            return glfwCreateCursor(image, 0, 0);
        }
    }

    private static int compileProgram(GLObjects gl) throws IOException {
        try (var shaders = new GLObjects()) {
            var vertexShader = shaders.compileShader(GL_VERTEX_SHADER, "terminal.vsh");
            var fragmentShader = shaders.compileShader(GL_FRAGMENT_SHADER, "terminal.fsh");

            var program = gl.createProgram("Terminal program");
            glAttachShader(program, vertexShader);
            glAttachShader(program, fragmentShader);

            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
                LOG.warn("Error encountered when linking shader: {}", glGetProgramInfoLog(program, 32768));
            }

            return program;
        }
    }

    /**
     * Write the current contents of the terminal to a buffer, ready to be copied to our TBO.
     * <p>
     * Each cell is stored as three packed bytes - character, foreground, background. This is then bound to the
     * {@code Tbo} uniform within the shader, and read to lookup specific the current pixel.
     *
     * @param buffer   The buffer to write to.
     * @param terminal The current terminal.
     */
    private static void writeTerminalContents(ByteBuffer buffer, Terminal terminal) {
        int width = terminal.getWidth(), height = terminal.getHeight();

        var pos = 0;
        for (var y = 0; y < height; y++) {
            TextBuffer text = terminal.getLine(y), textColour = terminal.getTextColourLine(y), background = terminal.getBackgroundColourLine(y);
            for (var x = 0; x < width; x++) {
                buffer.put(pos, (byte) (text.charAt(x) & 0xFF));
                buffer.put(pos + 1, (byte) (15 - Terminal.getColour(textColour.charAt(x), Colour.WHITE)));
                buffer.put(pos + 2, (byte) (15 - Terminal.getColour(background.charAt(x), Colour.BLACK)));
                pos += 3;
            }
        }

        buffer.limit(pos);
    }

    /**
     * Write the additional terminal properties (palette, size, cursor) to a buffer, ready to be copied to our UBO.
     * <p>
     * This is bound to the {@code TermData} uniform, and read to look up terminal-wide properties.
     *
     * @param buffer   The buffer to write to.
     * @param terminal The current terminal.
     */
    private static void setUniformData(ByteBuffer buffer, Terminal terminal) {
        var pos = 0;
        var palette = terminal.getPalette();
        for (var i = 0; i < 16; i++) {
            var colour = palette.getColour(i);
            buffer.putFloat(pos, (float) colour[0]).putFloat(pos + 4, (float) colour[1]).putFloat(pos + 8, (float) colour[2]);

            pos += 4 * 4; // std140 requires these are 4-wide
        }

        var cursorX = terminal.getCursorX();
        var cursorY = terminal.getCursorY();
        var showCursor = terminal.getCursorBlink() && cursorX >= 0 && cursorX < terminal.getWidth() && cursorY >= 0 && cursorY < terminal.getHeight();

        buffer
            .putInt(pos, terminal.getWidth()).putInt(pos + 4, terminal.getHeight())
            .putInt(pos + 8, showCursor ? cursorX : -2)
            .putInt(pos + 12, showCursor ? cursorY : -2)
            .putInt(pos + 16, 15 - terminal.getTextColour());

        buffer.limit(TERMINAL_DATA_SIZE);
    }
}
