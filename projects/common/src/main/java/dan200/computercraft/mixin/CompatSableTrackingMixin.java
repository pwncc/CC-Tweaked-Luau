// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin;

import dan200.computercraft.shared.camera.BroadcastChannels;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Compatibility with Sable's physics structures: keep a structure synced to players who are <em>watching it
 * through a camera</em>, not just those standing near it.
 * <p>
 * Sable only syncs a sub-level to players within its tracking range - a sensible optimisation that breaks remote
 * piloting: fly a camera-carrying rocket away and the watcher's client deletes the whole structure, so it vanishes
 * from the world and from its own camera view. Sable decides both acquiring and dropping trackers through one
 * distance check, {@code shouldLoad(player, position)}; this treats "receiving a live broadcast from a camera
 * riding that structure" as being in range.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem", remap = false)
class CompatSableTrackingMixin {
    @Inject(method = "shouldLoad", at = @At("HEAD"), cancellable = true, require = 0, expect = 0, remap = false)
    @SuppressWarnings("UnusedMethod")
    private void computercraft$trackForRemoteViewers(Player player, Vector3dc position, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof ServerPlayer viewer
            && BroadcastChannels.isWatchingNear(viewer, position.x(), position.y(), position.z())
        ) {
            cir.setReturnValue(true);
        }
    }
}
