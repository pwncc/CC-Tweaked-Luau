// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin.client;

import dan200.computercraft.client.render.remoteview.RemoteViewRenderOverride;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code LevelRenderer.setupRender} positions the grid of renderable sections around the player. A camera view's
 * puppet renderer must follow the camera instead: the player is in another dimension, so their coordinates leave
 * the grid centred on nothing and no terrain ever compiles.
 */
@Mixin(LevelRenderer.class)
class CameraLevelRendererMixin implements RemoteViewRenderOverride {
    @Unique
    private @Nullable Vec3 cameraOverride;

    @Override
    public void computercraft$setCameraOverride(@Nullable Vec3 position) {
        cameraOverride = position;
    }

    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"))
    @SuppressWarnings("UnusedMethod")
    private double computercraft$sectionAnchorX(LocalPlayer player) {
        var override = cameraOverride;
        return override != null ? override.x : player.getX();
    }

    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"))
    @SuppressWarnings("UnusedMethod")
    private double computercraft$sectionAnchorY(LocalPlayer player) {
        var override = cameraOverride;
        return override != null ? override.y : player.getY();
    }

    @Redirect(method = "setupRender", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"))
    @SuppressWarnings("UnusedMethod")
    private double computercraft$sectionAnchorZ(LocalPlayer player) {
        var override = cameraOverride;
        return override != null ? override.z : player.getZ();
    }
}
