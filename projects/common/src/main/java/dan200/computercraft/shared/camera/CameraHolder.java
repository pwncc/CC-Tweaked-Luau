// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.camera;

import dan200.computercraft.shared.peripheral.camera.CameraPeripheral;

/**
 * A {@link CameraSource} that a {@link CameraPeripheral} can steer: the peripheral's Lua methods write through this
 * interface, so camera blocks and turtle upgrades share one peripheral implementation.
 */
public interface CameraHolder extends CameraSource {
    /**
     * Start (or stop, with {@link #NO_CHANNEL}) broadcasting on a channel.
     *
     * @param channel The channel to broadcast on.
     */
    void setChannel(int channel);

    /**
     * The peripheral-set yaw offset in degrees, relative to the source's own facing.
     *
     * @return The relative yaw.
     */
    float getYaw();

    /**
     * The peripheral-set pitch in degrees, before any world transform (such as a physics structure's rotation)
     * is applied. This is what {@code getRotation()} reports back to Lua.
     *
     * @return The peripheral-set pitch.
     */
    float getLocalPitch();

    /**
     * Point the view, relative to the source's own facing.
     *
     * @param yaw   The yaw offset in degrees.
     * @param pitch The pitch in degrees, positive downwards.
     */
    void setRotation(float yaw, float pitch);

    /**
     * Set the vertical field of view.
     *
     * @param fov The field of view in degrees.
     */
    void setFov(float fov);
}
