// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.computer.apis;

import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.shared.computer.blocks.BillboardBlockEntity;
import dan200.computercraft.shared.display.PixelBuffer;
import dan200.computercraft.shared.peripheral.camera.CameraBlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * The display API controls what a billboard shows on its in-world screen: its own terminal (the default), or the
 * live feed of a {@linkplain dan200.computercraft.shared.peripheral.camera.CameraPeripheral camera} broadcasting on
 * a channel.
 *
 * @cc.module display
 */
public class DisplayAPI implements ILuaAPI {
    private final BillboardBlockEntity billboard;

    public DisplayAPI(BillboardBlockEntity billboard) {
        this.billboard = billboard;
    }

    @Override
    public String[] getNames() {
        return new String[]{ "display" };
    }

    /**
     * Show a camera channel on this billboard's screen instead of the terminal.
     *
     * @param channel The channel to tune to, between 0 and 65535.
     * @throws LuaException If the channel is out of range.
     */
    @LuaFunction(mainThread = true)
    public final void setChannel(int channel) throws LuaException {
        if (channel < 0 || channel > CameraBlockEntity.MAX_CHANNEL) {
            throw new LuaException("Channel out of range (expected 0-" + CameraBlockEntity.MAX_CHANNEL + ")");
        }
        billboard.setViewChannel(channel);
    }

    /**
     * Get the channel this billboard is tuned to.
     *
     * @return The current channel, or {@code nil} when showing the terminal.
     */
    @LuaFunction(mainThread = true)
    public final Object @Nullable [] getChannel() {
        var channel = billboard.getViewChannel();
        return channel == BillboardBlockEntity.NO_CHANNEL ? null : new Object[]{ channel };
    }

    /**
     * Switch the billboard back to showing its own terminal.
     */
    @LuaFunction(mainThread = true)
    public final void clearChannel() {
        billboard.setViewChannel(BillboardBlockEntity.NO_CHANNEL);
    }

    /**
     * Put the billboard's screen into graphics mode: a pixel framebuffer drawn with {@link #drawFrame}, shown
     * instead of the terminal.
     *
     * @param width  The buffer width in pixels, at most 640.
     * @param height The buffer height in pixels, at most 360.
     * @throws LuaException If the size is out of range.
     */
    @LuaFunction(mainThread = true)
    public final void setGraphicsMode(int width, int height) throws LuaException {
        PixelBuffer.checkSize(width, height);
        billboard.setGraphicsMode(width, height);
    }

    /**
     * Leave graphics mode, returning to the terminal (or tuned channel).
     */
    @LuaFunction(mainThread = true)
    public final void clearGraphicsMode() {
        billboard.clearGraphicsMode();
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
        var graphics = billboard.getGraphics();
        return graphics == null ? null : new Object[]{ graphics.width(), graphics.height() };
    }

    /**
     * Draw a frame of pixels into the graphics mode buffer.
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
        var graphics = billboard.getGraphics();
        if (graphics == null) throw new LuaException("Not in graphics mode (call setGraphicsMode first)");

        var width = arguments.getInt(0);
        var height = arguments.getInt(1);
        var format = arguments.getString(2);
        var data = arguments.getBytes(3);
        var x = arguments.optInt(4, 1);
        var y = arguments.optInt(5, 1);
        if (width < 1 || height < 1) throw new LuaException("Frame size out of range");

        graphics.blit(x - 1, y - 1, width, height, format, data);
    }
}
