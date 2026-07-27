// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin;

import dan200.computercraft.shared.camera.CameraViewZones;
import dan200.computercraft.shared.camera.CameraZoneTrackingView;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds {@linkplain CameraViewZones camera zones} to players' chunk tracking views, so the chunks around watched
 * cameras sync to viewers through the vanilla pipeline.
 * <p>
 * When zones are involved this takes over {@code applyChunkTrackingView} entirely: vanilla's diff only has a fast
 * path for two {@code Positioned} views and otherwise drops and re-sends <em>everything</em> - which, with a
 * composite view installed, would resend the whole view distance on every chunk border crossing.
 */
@Mixin(ChunkMap.class)
abstract class CameraChunkMapMixin implements CameraViewZones.ChunkMapAccess {
    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    protected abstract void markChunkPendingToSend(ServerPlayer player, ChunkPos chunkPos);

    @Shadow
    private static void dropChunk(ServerPlayer player, ChunkPos chunkPos) {
    }

    @Invoker("updateChunkTracking")
    public abstract void computercraft$callUpdateChunkTracking(ServerPlayer player);

    @Override
    public void computercraft$updateChunkTracking(ServerPlayer player) {
        computercraft$callUpdateChunkTracking(player);
    }

    @Inject(method = "applyChunkTrackingView", at = @At("HEAD"), cancellable = true)
    private void computercraft$applyWithZones(ServerPlayer player, ChunkTrackingView newView, CallbackInfo ci) {
        if (player.level() != level) return;

        var oldView = player.getChunkTrackingView();
        var zones = CameraViewZones.getZones(player);
        if (zones.isEmpty() && !(oldView instanceof CameraZoneTrackingView)) return; // Pure vanilla: leave it alone.

        var wrapped = newView instanceof ChunkTrackingView.Positioned positioned && !zones.isEmpty()
            ? new CameraZoneTrackingView(positioned, zones)
            : newView;

        // Mirror vanilla's cache-centre update, which it only performs for plain Positioned views.
        var newCentre = computercraft$centreOf(wrapped);
        if (newCentre != null && !newCentre.equals(computercraft$centreOf(oldView))) {
            player.connection.send(new ClientboundSetChunkCacheCenterPacket(newCentre.x, newCentre.z));
        }

        if (!oldView.equals(wrapped)) {
            wrapped.forEach(pos -> {
                if (!oldView.contains(pos.x, pos.z)) markChunkPendingToSend(player, pos);
            });
            oldView.forEach(pos -> {
                if (!wrapped.contains(pos.x, pos.z)) dropChunk(player, pos);
            });
        }

        player.setChunkTrackingView(wrapped);
        ci.cancel();
    }

    @Nullable
    private static ChunkPos computercraft$centreOf(ChunkTrackingView view) {
        if (view instanceof ChunkTrackingView.Positioned positioned) return positioned.center();
        if (view instanceof CameraZoneTrackingView composite) return composite.vanilla().center();
        return null;
    }
}
