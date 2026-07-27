// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.peripheral.camera;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.shared.camera.BroadcastChannels;
import dan200.computercraft.shared.camera.CameraHolder;
import dan200.computercraft.shared.camera.CameraSnapshots;
import org.jspecify.annotations.Nullable;

/**
 * The camera broadcasts a live view of the world around it on a numbered channel, which screens (such as the
 * billboard) can tune into with {@code display.setChannel}. The picture is rendered client-side from streamed world
 * data, so it works across any distance - and across dimensions.
 * <p>
 * The camera is fixed to its mounting (a camera block, or the side of a turtle), but the view can be steered:
 * {@link #setRotation} pans and tilts relative to the mounting's facing, and {@link #setFov} zooms.
 *
 * @cc.module camera
 */
public final class CameraPeripheral implements IPeripheral {
    private final CameraHolder camera;

    public CameraPeripheral(CameraHolder camera) {
        this.camera = camera;
    }

    @Override
    public String getType() {
        return "camera";
    }

    /**
     * Start broadcasting on a channel.
     * <p>
     * Screens tuned to this channel will show this camera's view. If another camera is already broadcasting on the
     * channel, this camera takes it over.
     *
     * @param channel The channel to broadcast on, between 0 and 65535.
     * @throws LuaException If the channel is out of range.
     */
    @LuaFunction(mainThread = true)
    public void setChannel(int channel) throws LuaException {
        if (channel < 0 || channel > CameraHolder.MAX_CHANNEL) {
            throw new LuaException("Channel out of range (expected 0-" + CameraHolder.MAX_CHANNEL + ")");
        }
        camera.setChannel(channel);
    }

    /**
     * Get the channel this camera is broadcasting on.
     *
     * @return The current channel, or {@code nil} if not broadcasting.
     */
    @LuaFunction(mainThread = true)
    public Object @Nullable [] getChannel() {
        var channel = camera.getChannel();
        return channel == CameraHolder.NO_CHANNEL ? null : new Object[]{ channel };
    }

    /**
     * Stop broadcasting.
     */
    @LuaFunction(mainThread = true)
    public void clearChannel() {
        camera.setChannel(CameraHolder.NO_CHANNEL);
    }

    /**
     * Point the camera, relative to the way the block is facing.
     *
     * @param yaw   The pan angle in degrees (positive is to the camera's right).
     * @param pitch The tilt angle in degrees, between -89 and 89 (positive is downwards).
     */
    @LuaFunction(mainThread = true)
    public void setRotation(double yaw, double pitch) {
        camera.setRotation((float) yaw, (float) pitch);
    }

    /**
     * Get the camera's current rotation.
     *
     * @return The pan and tilt angles, in degrees.
     * @cc.treturn number The yaw offset.
     * @cc.treturn number The pitch offset.
     */
    @LuaFunction(mainThread = true)
    public Object[] getRotation() {
        return new Object[]{ (double) camera.getYaw(), (double) camera.getLocalPitch() };
    }

    /**
     * Set the camera's field of view, i.e. zoom.
     *
     * @param fov The field of view in degrees, between 30 (telephoto) and 110 (wide angle).
     */
    @LuaFunction(mainThread = true)
    public void setFov(double fov) {
        camera.setFov((float) fov);
    }

    /**
     * Get the camera's field of view.
     *
     * @return The field of view, in degrees.
     */
    @LuaFunction(mainThread = true)
    public double getFov() {
        return camera.getFov();
    }

    /**
     * Capture a single frame of the camera's view, as seen by a live renderer.
     * <p>
     * The camera must be {@linkplain #setChannel broadcasting on a channel} first. The frame is rendered by the
     * client of an online player (preferring one already watching the channel), so capturing takes a moment - from
     * a few hundred milliseconds when the channel is already streaming, up to several seconds for a cold start -
     * and fails if nobody is online.
     * <p>
     * The result is a frame table (with {@code width}, {@code height}, {@code format} and {@code data} fields)
     * which the {@code cc.frames} module can draw to terminals and monitors, scale, or read pixels from.
     *
     * @param computer The computer capturing the frame.
     * @param width    The frame width in pixels, at most 1280.
     * @param height   The frame height in pixels, at most 720.
     * @return The captured frame, or {@code nil} and an error message.
     * @throws LuaException If the resolution is out of range.
     * @cc.treturn [1] table The captured frame.
     * @cc.treturn [2] nil When the capture fails.
     * @cc.treturn [2] string The reason the capture failed.
     * @cc.usage Capture a frame and draw it on the terminal.
     * <pre>{@code
     * local frames = require "cc.frames"
     * local camera = peripheral.find("camera")
     * camera.setChannel(7)
     * local frame = assert(camera.capture(320, 180))
     * local w, h = term.getSize()
     * frames.draw(frames.render(frame, w, h))
     * }</pre>
     */
    @LuaFunction
    public MethodResult capture(IComputerAccess computer, int width, int height) throws LuaException {
        return CameraSnapshots.capture(camera, computer, width, height);
    }

    /**
     * Get the number of players currently watching this camera's channel.
     * <p>
     * The world stream only runs while somebody is watching, so this is useful for pausing expensive camera
     * movements when there is no audience.
     *
     * @return The number of watching players.
     */
    @LuaFunction(mainThread = true)
    public int getViewers() {
        var level = camera.cameraLevel();
        var channel = camera.getChannel();
        if (level == null || channel == CameraHolder.NO_CHANNEL) return 0;
        return BroadcastChannels.get(level.getServer()).getViewerCount(channel);
    }

    /**
     * The holder this peripheral steers, so its owner (e.g. a turtle upgrade) can drive the broadcast pump.
     *
     * @return The peripheral's camera holder.
     */
    public CameraHolder holder() {
        return camera;
    }

    @Override
    public Object getTarget() {
        return camera;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return this == other || (other instanceof CameraPeripheral o && camera.equals(o.camera));
    }
}
