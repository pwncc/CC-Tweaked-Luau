// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.camera;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Support for cameras riding <a href="https://modrinth.com/mod/sable">Sable</a> physics structures (as used by
 * Create: Aeronautics and friends): a camera on a moving sub-level reports its <em>world-space</em> pose, so views
 * fly along with the structure.
 * <p>
 * This goes through the MIT-licensed Sable companion library, which no-ops when Sable itself is not installed -
 * every helper here then degrades to the identity transform.
 */
public final class SableSupport {
    private SableSupport() {
    }

    /**
     * The pose of the physics structure containing a position, if any.
     *
     * @param level    The level to look in.
     * @param position The (structure-local) position to look up.
     * @return The structure's pose, or {@code null} when the position is not inside one.
     */
    public static @Nullable Pose3dc poseAt(@Nullable Level level, Vec3 position) {
        if (level == null) return null;
        var subLevel = SableCompanion.INSTANCE.getContaining(level, position);
        return subLevel == null ? null : subLevel.logicalPose();
    }

    /**
     * The <em>render-time</em> pose of the physics structure containing a position on this client, if any. This
     * is the interpolated pose the structure is actually drawn with each frame - a camera glued to a structure
     * must use it, or the view lags (or leads) the structure at speed: the logical pose runs on the snapshot
     * timeline, several frames apart from what is on screen.
     *
     * @param position The (structure-local) position to look up.
     * @return The structure's render pose, or {@code null} when the position is not inside a tracked structure.
     */
    public static @Nullable Pose3dc clientRenderPoseAt(Vec3 position) {
        var subLevel = SableCompanion.INSTANCE.getContainingClient(position);
        return subLevel == null ? null : subLevel.renderPose();
    }

    /**
     * Transform a structure-local position to world space.
     *
     * @param level The level the position is in.
     * @param local The local position.
     * @return The world-space position (identical when not on a structure).
     */
    public static Vec3 toWorldPosition(@Nullable Level level, Vec3 local) {
        var pose = poseAt(level, local);
        return pose == null ? local : pose.transformPosition(local);
    }

    /**
     * Transform a structure-local position to world space through a known pose.
     *
     * @param pose  The structure's pose.
     * @param local The local position.
     * @return The world-space position.
     */
    public static Vec3 worldPosition(Pose3dc pose, Vec3 local) {
        return pose.transformPosition(local);
    }

    /**
     * Transform a structure-local view rotation to a world-space yaw.
     *
     * @param level The level the view is in.
     * @param local The view's local position.
     * @param yaw   The local yaw, in degrees.
     * @param pitch The local pitch, in degrees.
     * @return The world-space yaw, in degrees.
     */
    public static float toWorldYaw(@Nullable Level level, Vec3 local, float yaw, float pitch) {
        var pose = poseAt(level, local);
        if (pose == null) return yaw;
        return worldYaw(pose, yaw, pitch);
    }

    /**
     * Transform a structure-local view rotation to a world-space yaw through a known pose.
     *
     * @param pose  The structure's pose.
     * @param yaw   The local yaw, in degrees.
     * @param pitch The local pitch, in degrees.
     * @return The world-space yaw, in degrees.
     */
    public static float worldYaw(Pose3dc pose, float yaw, float pitch) {
        var forward = pose.transformNormal(Vec3.directionFromRotation(pitch, yaw));
        return (float) Math.toDegrees(Mth.atan2(-forward.x, forward.z));
    }

    /**
     * Transform a structure-local view rotation to a world-space pitch.
     *
     * @param level The level the view is in.
     * @param local The view's local position.
     * @param yaw   The local yaw, in degrees.
     * @param pitch The local pitch, in degrees.
     * @return The world-space pitch, in degrees.
     */
    public static float toWorldPitch(@Nullable Level level, Vec3 local, float yaw, float pitch) {
        var pose = poseAt(level, local);
        if (pose == null) return pitch;
        return worldPitch(pose, yaw, pitch);
    }

    /**
     * Transform a structure-local view rotation to a world-space pitch through a known pose.
     *
     * @param pose  The structure's pose.
     * @param yaw   The local yaw, in degrees.
     * @param pitch The local pitch, in degrees.
     * @return The world-space pitch, in degrees.
     */
    public static float worldPitch(Pose3dc pose, float yaw, float pitch) {
        var forward = pose.transformNormal(Vec3.directionFromRotation(pitch, yaw));
        var length = forward.length();
        return length < 1e-7 ? pitch : (float) -Math.toDegrees(Math.asin(Mth.clamp(forward.y / length, -1, 1)));
    }

    /**
     * The world-space roll of a structure-local view, in degrees. Yaw and pitch only describe where the view's
     * forward vector points; when the structure banks or tips over, the view's <em>up</em> vector tilts away from
     * where a level camera's would be, and this is that tilt (positive when the camera's top leans to its right).
     *
     * @param level The level the view is in.
     * @param local The view's local position.
     * @param yaw   The local yaw, in degrees.
     * @param pitch The local pitch, in degrees.
     * @return The world-space roll, in degrees (0 when not on a structure).
     */
    public static float toWorldRoll(@Nullable Level level, Vec3 local, float yaw, float pitch) {
        var pose = poseAt(level, local);
        if (pose == null) return 0;
        return worldRoll(pose, yaw, pitch);
    }

    /**
     * The world-space roll of a structure-local view through a known pose; see
     * {@link #toWorldRoll(Level, Vec3, float, float)}.
     *
     * @param pose  The structure's pose.
     * @param yaw   The local yaw, in degrees.
     * @param pitch The local pitch, in degrees.
     * @return The world-space roll, in degrees.
     */
    public static float worldRoll(Pose3dc pose, float yaw, float pitch) {
        var forward = pose.transformNormal(Vec3.directionFromRotation(pitch, yaw));
        var length = forward.length();
        if (length < 1e-7) return 0;
        forward = forward.scale(1 / length);

        // Where a roll-less camera looking along the transformed forward would have its up vector...
        var worldYaw = (float) Math.toDegrees(Mth.atan2(-forward.x, forward.z));
        var worldPitch = (float) -Math.toDegrees(Math.asin(Mth.clamp(forward.y, -1, 1)));
        var naturalUp = Vec3.directionFromRotation(worldPitch - 90, worldYaw);

        // ... versus where the structure actually put it: the signed angle between them, about the view axis.
        var up = pose.transformNormal(Vec3.directionFromRotation(pitch - 90, yaw)).normalize();
        return (float) Math.toDegrees(Math.atan2(naturalUp.cross(up).dot(forward), naturalUp.dot(up)));
    }
}
