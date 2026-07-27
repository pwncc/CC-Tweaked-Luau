// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.camera;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Something a camera broadcast can look out from: a camera block, a turtle upgrade, or (one day) a camera riding a
 * physics structure.
 * <p>
 * The pose is sampled fresh every tick by {@link BroadcastChannels}, so sources are free to move - viewers follow
 * along automatically.
 */
public interface CameraSource {
    int NO_CHANNEL = -1;
    int MAX_CHANNEL = 65535;

    /**
     * The level this source lives in, or {@code null} when it is not in a server level.
     *
     * @return The source's level.
     */
    @Nullable
    ServerLevel cameraLevel();

    /**
     * The eye position of the view, in world space.
     *
     * @return The view's eye position.
     */
    Vec3 getViewPosition();

    /**
     * The absolute yaw of the view in degrees.
     *
     * @return The view's world-space yaw.
     */
    float getAbsoluteYaw();

    /**
     * The pitch of the view in degrees, positive downwards.
     *
     * @return The view's pitch.
     */
    float getPitch();

    /**
     * The roll of the view in degrees, positive when the camera's top leans to its right. Non-zero only for
     * cameras riding a tilted physics structure.
     *
     * @return The view's roll.
     */
    default float getRoll() {
        return 0;
    }

    /**
     * The block this source physically occupies, in <em>its own</em> level's coordinates. For a camera riding a
     * physics structure this is where the block actually lives (and where its neighbouring modems are found) -
     * unlike {@link #getViewPosition()}, which is transformed into world space.
     *
     * @return The source's own block position.
     */
    default BlockPos sourcePosition() {
        return BlockPos.containing(getViewPosition());
    }

    /**
     * The vertical field of view in degrees.
     *
     * @return The view's field of view.
     */
    float getFov();

    /**
     * The channel this source is broadcasting on, or {@link #NO_CHANNEL}.
     *
     * @return The broadcast channel.
     */
    int getChannel();

    /**
     * Whether this source is gone (broken, unloaded or otherwise dead), freeing its channel for adoption.
     *
     * @return Whether the source has been removed.
     */
    boolean isSourceRemoved();

    /**
     * The kind of modem equipped directly on this source (e.g. a turtle's modem upgrade), or {@code -1} when it
     * carries none. Block cameras find their modems by adjacency instead.
     *
     * @return A {@link VideoLinks} modem kind, or -1.
     */
    default int equippedModem() {
        return -1;
    }
}
