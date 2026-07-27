// Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
//
// SPDX-License-Identifier: LicenseRef-CCPL

package dan200.computercraft.shared.peripheral.monitor;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.LuaValues;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.core.apis.TermMethods;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.shared.display.PixelBuffer;
import org.jspecify.annotations.Nullable;

/**
 * Monitors are a block which act as a terminal, displaying information on one side. This allows them to be read and
 * interacted with in-world without opening a GUI.
 * <p>
 * Monitors act as [terminal redirects][`term.Redirect`] and so expose the same methods, as well as several additional
 * ones, which are documented below.
 * <p>
 * If the monitor is resized (by adding new blocks to the monitor, or by calling {@link setTextScale}), then a
 * [`monitor_resize`] event will be queued.
 * <p>
 * Like computers, monitors come in both normal (no colour) and advanced (colour) varieties. Advanced monitors be right
 * clicked, which will trigger a [`monitor_touch`] event.
 * <p>
 * ## Recipes
 * <div class="recipe-container">
 *     <mc-recipe recipe="computercraft:monitor_normal"></mc-recipe>
 *     <mc-recipe recipe="computercraft:monitor_advanced"></mc-recipe>
 * </div>
 *
 * @cc.module monitor
 * @cc.usage Write "Hello, world!" to an adjacent monitor:
 *
 * <pre>{@code
 * local monitor = peripheral.find("monitor")
 * monitor.setCursorPos(1, 1)
 * monitor.write("Hello, world!")
 * }</pre>
 * @cc.see monitor_resize Queued when a monitor is resized.
 * @cc.see monitor_touch Queued when an advanced monitor is clicked.
 */
public class MonitorPeripheral extends TermMethods implements IPeripheral {
    private final MonitorBlockEntity monitor;

    public MonitorPeripheral(MonitorBlockEntity monitor) {
        this.monitor = monitor;
    }

    @Override
    public String getType() {
        return "monitor";
    }

    /**
     * Show a camera broadcast on this monitor instead of its terminal.
     * <p>
     * The whole monitor shows the live view of whichever camera is broadcasting on the channel (see the
     * {@code camera} peripheral). The terminal keeps working underneath - drawing to it is simply not visible
     * until {@link #clearChannel} is called.
     *
     * @param channel The channel to show, between 0 and 65535.
     * @throws LuaException If the channel is out of range.
     */
    @LuaFunction(mainThread = true)
    public final void setChannel(int channel) throws LuaException {
        if (channel < 0 || channel > 65535) throw new LuaException("Channel out of range (expected 0-65535)");
        monitor.setViewChannel(channel);
    }

    /**
     * Get the camera channel this monitor is showing.
     *
     * @return The current channel, or {@code nil} when showing the terminal.
     */
    @LuaFunction(mainThread = true)
    public final Object @Nullable [] getChannel() {
        var channel = monitor.resolveViewChannel();
        return channel == MonitorBlockEntity.NO_CHANNEL ? null : new Object[]{ channel };
    }

    /**
     * Stop showing a camera and return to the terminal.
     */
    @LuaFunction(mainThread = true)
    public final void clearChannel() {
        monitor.setViewChannel(MonitorBlockEntity.NO_CHANNEL);
    }

    /**
     * Put this monitor into graphics mode: a pixel framebuffer the computer draws into with {@link #drawFrame},
     * shown instead of the terminal.
     * <p>
     * The buffer starts black. The terminal keeps working underneath, and reappears after
     * {@link #clearGraphicsMode}.
     *
     * @param width  The buffer width in pixels, at most 640.
     * @param height The buffer height in pixels, at most 360.
     * @throws LuaException If the size is out of range.
     * @cc.usage Show a camera frame on a monitor at full fidelity.
     * <pre>{@code
     * local camera = peripheral.find("camera")
     * local monitor = peripheral.find("monitor")
     * monitor.setGraphicsMode(320, 180)
     * while true do
     *     local frame = assert(camera.capture(320, 180))
     *     monitor.drawFrame(frame.width, frame.height, frame.format, frame.data)
     * end
     * }</pre>
     */
    @LuaFunction(mainThread = true)
    public final void setGraphicsMode(int width, int height) throws LuaException {
        PixelBuffer.checkSize(width, height);
        monitor.setGraphicsMode(width, height);
    }

    /**
     * Leave graphics mode, returning to the terminal (or tuned camera channel).
     */
    @LuaFunction(mainThread = true)
    public final void clearGraphicsMode() {
        monitor.clearGraphicsMode();
    }

    /**
     * Get the size of the graphics mode buffer, if any.
     *
     * @return The buffer size.
     * @cc.treturn number|nil The buffer width, or {@code nil} when not in graphics mode.
     * @cc.treturn number|nil The buffer height.
     */
    @LuaFunction(mainThread = true)
    public final Object @Nullable [] getGraphicsSize() {
        var graphics = monitor.getGraphics();
        return graphics == null ? null : new Object[]{ graphics.width(), graphics.height() };
    }

    /**
     * Draw a frame of pixels into the graphics mode buffer.
     * <p>
     * The frame fields are the same shape {@code camera.capture} returns, passed as separate arguments.
     *
     * @param arguments The frame to draw: width, height, format ({@code "rgb332"} or {@code "rgb888"}), the pixel
     *                  data, and optionally the 1-based x and y position to draw at.
     * @throws LuaException If not in graphics mode, or the frame is malformed.
     * @cc.tparam number width The frame width in pixels.
     * @cc.tparam number height The frame height in pixels.
     * @cc.tparam string format The frame format, "rgb332" or "rgb888".
     * @cc.tparam string data The packed pixel data.
     * @cc.tparam[opt=1] number x The x position to draw at.
     * @cc.tparam[opt=1] number y The y position to draw at.
     */
    @LuaFunction(mainThread = true)
    public final void drawFrame(IArguments arguments) throws LuaException {
        var graphics = monitor.getGraphics();
        if (graphics == null) throw new LuaException("Not in graphics mode (call setGraphicsMode first)");

        var width = arguments.getInt(0);
        var height = arguments.getInt(1);
        var format = arguments.getString(2);
        var data = arguments.getBytes(3);
        var x = arguments.optInt(4, 1);
        var y = arguments.optInt(5, 1);
        if (width < 1 || height < 1) throw new LuaException("Frame size out of range");

        graphics.blit(x - 1, y - 1, width, height, format, data);
        monitor.schedulePixelSync();
    }

    /**
     * Set the scale of this monitor. A larger scale will result in the monitor having a lower resolution, but display
     * text much larger.
     *
     * @param scaleArg The monitor's scale. This must be a multiple of 0.5 between 0.5 and 5.
     * @throws LuaException If the scale is out of range.
     * @see #getTextScale()
     */
    @LuaFunction
    public final void setTextScale(double scaleArg) throws LuaException {
        var scale = (int) (LuaValues.checkFinite(0, scaleArg) * 2.0);
        if (scale < 1 || scale > 10) throw new LuaException("Expected number in range 0.5-5");
        getMonitor().setTextScale(scale);
    }

    /**
     * Get the monitor's current text scale.
     *
     * @return The monitor's current scale.
     * @throws LuaException If the monitor cannot be found.
     * @cc.since 1.81.0
     */
    @LuaFunction
    public final double getTextScale() throws LuaException {
        return getMonitor().getTextScale() / 2.0;
    }

    @Override
    public void attach(IComputerAccess computer) {
        monitor.addComputer(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        monitor.removeComputer(computer);
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof MonitorPeripheral o && monitor == o.monitor;
    }

    private ServerMonitor getMonitor() throws LuaException {
        var monitor = this.monitor.getCachedServerMonitor();
        if (monitor == null) throw new LuaException("Monitor has been detached");
        return monitor;
    }

    @Override
    public Terminal getTerminal() throws LuaException {
        Terminal terminal = getMonitor().getTerminal();
        if (terminal == null) throw new LuaException("Monitor has been detached");
        return terminal;
    }

    @Nullable
    @Override
    public Object getTarget() {
        return monitor;
    }
}
