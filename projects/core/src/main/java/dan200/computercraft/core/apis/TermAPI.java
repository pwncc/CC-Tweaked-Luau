// Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
//
// SPDX-License-Identifier: LicenseRef-CCPL

package dan200.computercraft.core.apis;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.core.util.Colour;


/**
 * Interact with a computer's terminal or monitors, writing text and drawing ASCII graphics.
 *
 * <h2>Writing to the terminal</h2>
 * The simplest operation one can perform on a terminal is displaying (or writing) some text. This can be performed with
 * the [`term.write`] method.
 *
 * <pre>{@code
 * term.write("Hello, world!")
 * }</pre>
 * <p>
 * When you write text, this advances the cursor, so the next call to [`term.write`] will write text immediately after
 * the previous one.
 *
 * <pre>{@code
 * term.write("Hello, world!")
 * term.write("Some more text")
 * }</pre>
 * <p>
 * [`term.getCursorPos`] and [`term.setCursorPos`] can be used to manually change the cursor's position.
 * <p>
 * <pre>{@code
 * term.clear()
 *
 * term.setCursorPos(1, 1) -- The first column of line 1
 * term.write("First line")
 *
 * term.setCursorPos(20, 2) -- The 20th column of line 2
 * term.write("Second line")
 * }</pre>
 * <p>
 * [`term.write`] is a relatively basic and low-level function, and does not handle more advanced features such as line
 * breaks or word wrapping. If you just want to display text to the screen, you probably want to use [`print`] or
 * [`write`] instead.
 *
 * <h2>Colours</h2>
 * So far we've been writing text in black and white. However, advanced computers are also capable of displaying text
 * in a variety of colours, with the [`term.setTextColour`] and [`term.setBackgroundColour`] functions.
 *
 * <pre>{@code
 * print("This text is white")
 * term.setTextColour(colours.green)
 * print("This text is green")
 * }</pre>
 * <p>
 * These functions accept any of the constants from the [`colors`] API. [Combinations of colours][`colors.combine`] may
 * be accepted, but will only display a single colour (typically following the behaviour of [`colors.toBlit`]).
 * <p>
 * The [`paintutils`] API provides several helpful functions for displaying graphics using [`term.setBackgroundColour`].
 *
 * @cc.module term
 */
public class TermAPI extends TermMethods implements ILuaAPI {
    private final Terminal terminal;
    private final IAPIEnvironment environment;
    private final int baseWidth;
    private final int baseHeight;

    public TermAPI(IAPIEnvironment environment) {
        terminal = environment.getTerminal();
        this.environment = environment;
        baseWidth = terminal.getWidth();
        baseHeight = terminal.getHeight();
    }

    @Override
    public String[] getNames() {
        return new String[]{ "term" };
    }

    /**
     * Get the environment this API is bound to. This is used by the Luau runtime, which implements the term API
     * natively.
     *
     * @return The API environment.
     */
    public IAPIEnvironment environment() {
        return environment;
    }

    /**
     * Set the resolution of this terminal. A scale of 1 is the standard terminal size, while higher values multiply
     * the number of rows and columns, rendering more (smaller) characters in the same screen space.
     * <p>
     * Programs should check this function exists before calling it, and reset the resolution before exiting. A
     * {@code term_resize} event is queued after the resolution changes.
     *
     * @param scale The resolution multiplier, between 1 and 10.
     * @throws LuaException If the scale is out of range.
     * @cc.since 1.121.0
     */
    @LuaFunction
    public final void setResolution(int scale) throws LuaException {
        if (scale < 1 || scale > 10) throw new LuaException("Expected scale in range 1-10");
        synchronized (terminal) {
            terminal.resize(baseWidth * scale, baseHeight * scale);
        }
        environment.queueEvent("term_resize");
    }

    /**
     * Request the hardware mouse pointer be hidden while it is over this terminal. Programs which enable this
     * should draw their own pointer, following {@code mouse_move} and {@code mouse_leave} events.
     *
     * @param capture Whether to hide the hardware pointer.
     * @cc.since 1.121.0
     */
    @LuaFunction
    public final void setMouseCapture(boolean capture) {
        terminal.setMouseCapture(capture);
    }

    /**
     * Get whether mouse capture is enabled, as set by {@link #setMouseCapture(boolean)}.
     *
     * @return Whether the hardware pointer is hidden over this terminal.
     * @cc.since 1.121.0
     */
    @LuaFunction
    public final boolean getMouseCapture() {
        return terminal.getMouseCapture();
    }

    /**
     * Get the current resolution multiplier of this terminal.
     *
     * @return The resolution multiplier.
     * @cc.since 1.121.0
     * @see #setResolution(int)
     */
    @LuaFunction
    public final int getResolution() {
        return Math.max(1, terminal.getWidth() / baseWidth);
    }

    /**
     * Get the base terminal size, as used by {@link #setResolution(int)}.
     *
     * @return The base width and height.
     */
    public int baseWidth() {
        return baseWidth;
    }

    /**
     * Get the base terminal height.
     *
     * @return The base height.
     */
    public int baseHeight() {
        return baseHeight;
    }

    /**
     * Get the default palette value for a colour.
     *
     * @param colour The colour whose palette should be fetched.
     * @return The RGB values.
     * @throws LuaException When given an invalid colour.
     * @cc.treturn number The red channel, will be between 0 and 1.
     * @cc.treturn number The green channel, will be between 0 and 1.
     * @cc.treturn number The blue channel, will be between 0 and 1.
     * @cc.since 1.81.0
     * @see TermMethods#setPaletteColour(IArguments) To change the palette colour.
     */
    @LuaFunction({ "nativePaletteColour", "nativePaletteColor" })
    public final Object[] nativePaletteColour(int colour) throws LuaException {
        var actualColour = 15 - parseColour(colour);
        var c = Colour.fromInt(actualColour);
        return new Object[]{ c.getR(), c.getG(), c.getB() };
    }

    @Override
    public Terminal getTerminal() {
        return terminal;
    }
}
