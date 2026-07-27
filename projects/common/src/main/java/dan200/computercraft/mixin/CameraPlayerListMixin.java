// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin;

import dan200.computercraft.shared.camera.BroadcastChannels;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors position-broadcast effect packets (level events such as block-break debris, block events such as chest
 * lids, and block cracking) to cross-dimension camera viewers, whose puppet level the vanilla broadcast cannot
 * reach.
 */
@Mixin(PlayerList.class)
class CameraPlayerListMixin {
    @Shadow
    @Final
    private net.minecraft.server.MinecraftServer server;

    @Inject(method = "broadcast", at = @At("HEAD"))
    @SuppressWarnings("UnusedMethod")
    private void computercraft$mirrorBroadcast(
        @Nullable Player except, double x, double y, double z, double radius,
        ResourceKey<Level> dimension, Packet<?> packet, CallbackInfo ci
    ) {
        var level = server.getLevel(dimension);
        if (level != null) BroadcastChannels.forwardEffect(level, x, y, z, packet);
    }
}
