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
 * Compatibility with Create: Cosmonautics' deep-space renderer, which draws the custom starfield sky and re-entry
 * effects from render events. Like {@link CosmonauticsSkyHandlerMixin}, those handlers mix the real player's
 * situation into whatever render pass happens to be running, so they sit out our remote-view passes.
 */
@Pseudo
@Mixin(targets = "dev.devce.rocketnautics.client.DeepSpaceHandler", remap = false)
public class CosmonauticsDeepSpaceHandlerMixin {
    @Inject(method = "onRenderLevelStage", at = @At("HEAD"), cancellable = true, require = 0, expect = 0, remap = false)
    private static void computercraft$skipForRemoteViews(CallbackInfo ci) {
        if (RemoteViewRenderer.isDrawing()) ci.cancel();
    }
}
