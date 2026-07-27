// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.render.remoteview;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

/**
 * A camera posable from code, for rendering the world from a camera block's viewpoint.
 */
final class ViewCamera extends Camera {
    /**
     * Move this camera to an arbitrary pose.
     *
     * @param position The world position of the eye.
     * @param yRot     The yaw, in entity convention.
     * @param xRot     The pitch.
     */
    void moveTo(Vec3 position, float yRot, float xRot) {
        setPosition(position);
        setRotation(yRot, xRot);
    }
}
