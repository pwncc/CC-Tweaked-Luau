// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin.client;

import dan200.computercraft.client.render.remoteview.RemoteViewRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Compatibility with Create: Cosmonautics' sky handler.
 * <p>
 * Cosmonautics decides "the player is in space" from global state ({@code Minecraft.getInstance().player}, the
 * current level, static planet-texture caches) inside its render event handlers. Our remote views run full level
 * render passes of <em>other</em> places - a camera in orbit while the player stands on a beach, or vice versa -
 * so those handlers fire mid-pass with mixed-up context: the planet cache re-centres on the camera, space fog
 * leaks onto the player's screen, and the two views flicker as they fight over the shared state each frame.
 * <p>
 * The fix is isolation: while a remote view is drawing, Cosmonautics' full-screen handlers simply don't run. Each
 * view then shows its own dimension's ordinary look, and the player's own view keeps the full Cosmonautics
 * treatment driven purely by their real situation.
 */
@Pseudo
@Mixin(targets = "dev.devce.rocketnautics.client.SkyHandler", remap = false)
public class CosmonauticsSkyHandlerMixin {
    @Inject(method = "onRenderLevelStage", at = @At("HEAD"), cancellable = true, require = 0, expect = 0, remap = false)
    private static void computercraft$skipForRemoteViews(CallbackInfo ci) {
        if (RemoteViewRenderer.isDrawing()) ci.cancel();
    }

    @Inject(method = "onComputeFogColor", at = @At("HEAD"), cancellable = true, require = 0, expect = 0, remap = false)
    private static void computercraft$skipFogColorForRemoteViews(CallbackInfo ci) {
        if (RemoteViewRenderer.isDrawing()) ci.cancel();
    }

    @Inject(method = "onRenderFog", at = @At("HEAD"), cancellable = true, require = 0, expect = 0, remap = false)
    private static void computercraft$skipFogForRemoteViews(CallbackInfo ci) {
        if (RemoteViewRenderer.isDrawing()) ci.cancel();
    }
}
