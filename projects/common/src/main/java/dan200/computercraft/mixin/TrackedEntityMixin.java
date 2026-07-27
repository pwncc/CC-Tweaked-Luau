// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin;

import dan200.computercraft.shared.camera.CameraViewZones;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Makes entities inside a player's {@linkplain CameraViewZones camera zones} track to them, even though they are
 * far outside the normal per-player tracking range. The chunk-tracked condition is already satisfied by
 * {@code ChunkMapMixin}; this lifts the distance-to-player check.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class TrackedEntityMixin {
    @Shadow
    @Final
    Entity entity;

    @ModifyVariable(method = "updatePlayer", at = @At("STORE"), ordinal = 0)
    private boolean computercraft$trackZoneEntities(boolean visible, ServerPlayer player) {
        if (visible) return true;

        var zones = CameraViewZones.getZones(player);
        if (zones.isEmpty()) return false;

        var chunk = entity.chunkPosition();
        for (var zone : zones) {
            if (zone.contains(chunk.x, chunk.z)) return entity.broadcastToPlayer(player);
        }
        return false;
    }
}
