// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin.client;

import dan200.computercraft.client.camera.ClientCameraZones;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/**
 * Lets the client keep chunks for {@linkplain ClientCameraZones camera zones}.
 * <p>
 * The vanilla storage is a fixed ring around the player and ignores anything outside it; chunks inside a camera
 * zone are stored in a side-store instead, where block updates and lookups find them as usual.
 */
@Mixin(ClientChunkCache.class)
abstract class ClientChunkCacheMixin {
    @Shadow
    @Final
    ClientLevel level;

    /**
     * Runs where vanilla ignores an out-of-range chunk (its first {@code return null}): if the chunk belongs to a
     * camera zone, build and store it ourselves instead.
     *
     * @param x        The chunk's x position.
     * @param z        The chunk's z position.
     * @param buffer   The chunk data.
     * @param tag      The chunk's heightmap tag.
     * @param consumer The block entity output.
     * @param cir      The callback info.
     */
    @Inject(method = "replaceWithPacketData", at = @At(value = "RETURN", ordinal = 0), cancellable = true)
    private void computercraft$loadZoneChunk(
        int x, int z, FriendlyByteBuf buffer, CompoundTag tag,
        Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
        CallbackInfoReturnable<LevelChunk> cir
    ) {
        if (!ClientCameraZones.isInZone(x, z)) return;

        var pos = new ChunkPos(x, z);
        var chunk = ClientCameraZones.getChunk(x, z);
        if (chunk == null) {
            chunk = new LevelChunk(level, pos);
            chunk.replaceWithPacketData(buffer, tag, consumer);
            ClientCameraZones.putChunk(chunk);
        } else {
            chunk.replaceWithPacketData(buffer, tag, consumer);
        }
        level.onChunkLoaded(pos);
        cir.setReturnValue(chunk);
    }

    @Inject(method = "drop", at = @At("HEAD"))
    private void computercraft$dropZoneChunk(ChunkPos pos, CallbackInfo ci) {
        ClientCameraZones.removeChunk(pos);
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("HEAD"), cancellable = true)
    private void computercraft$getZoneChunk(int x, int z, ChunkStatus status, boolean requireChunk, CallbackInfoReturnable<LevelChunk> cir) {
        var chunk = ClientCameraZones.getChunk(x, z);
        if (chunk != null) cir.setReturnValue(chunk);
    }
}
