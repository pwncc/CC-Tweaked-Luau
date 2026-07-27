// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin.client;

import dan200.computercraft.client.render.remoteview.RemoteViewCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block and light changes mark sections dirty on the <em>player's</em> level renderer only. Same-dimension camera
 * views render the same level through their own dedicated renderer (see {@code LocalRenderer}), so those dirty
 * marks are mirrored across - otherwise a camera would show stale terrain whenever the world changes.
 */
@Mixin(LevelRenderer.class)
class CameraSectionDirtyMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    @SuppressWarnings("UnusedMethod")
    private void computercraft$forwardSectionDirty(int x, int y, int z, boolean important, CallbackInfo ci) {
        // Only marks on the player's own renderer fan out, which also keeps the mirrored call from recursing.
        if ((Object) this == Minecraft.getInstance().levelRenderer) {
            RemoteViewCache.forwardSectionDirty(x, y, z, important);
        }
    }
}
