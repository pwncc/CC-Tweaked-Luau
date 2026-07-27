// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.render.remoteview;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Implemented on {@link net.minecraft.client.renderer.LevelRenderer} by mixin: vanilla positions its grid of
 * renderable sections around <em>the player</em>, but a puppet level's renderer must position it around the camera
 * being looked through - the player is in another dimension entirely.
 */
public interface RemoteViewRenderOverride {
    /**
     * Override the position the renderer's section grid follows, or clear the override with {@code null}.
     *
     * @param position The camera position to follow.
     */
    void computercraft$setCameraOverride(@Nullable Vec3 position);
}
