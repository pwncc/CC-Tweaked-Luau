// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.camera;

import dan200.computercraft.shared.network.client.CameraZonesMessage;
import dan200.computercraft.shared.network.server.ServerNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Extra per-player chunk-sending zones around watched cameras.
 * <p>
 * When a player watches a camera in their own dimension, the chunks around that camera are added to the player's
 * {@linkplain CameraZoneTrackingView chunk tracking view} (see {@code ChunkMapMixin}), so the camera's surroundings
 * sync to their client through the ordinary vanilla pipeline - blocks, block entities and entities alike. The
 * full-fidelity camera renderer then draws them straight from the client's own world.
 */
public final class CameraViewZones {
    private record PlayerZones(ResourceKey<Level> dimension, List<Zone> zones) {
    }

    private static final Map<UUID, PlayerZones> zones = new ConcurrentHashMap<>();

    private CameraViewZones() {
    }

    /**
     * A square chunk zone to keep synced to a player.
     *
     * @param centerX The zone's centre chunk x.
     * @param centerZ The zone's centre chunk z.
     * @param radius  The zone's radius in chunks.
     */
    public record Zone(int centerX, int centerZ, int radius) {
        /**
         * Whether a chunk is inside this zone.
         *
         * @param x The chunk's x position.
         * @param z The chunk's z position.
         * @return Whether the chunk is inside the zone.
         */
        public boolean contains(int x, int z) {
            return Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius;
        }

        /**
         * Visit every chunk in this zone.
         *
         * @param action The action to run for each chunk.
         */
        public void forEach(Consumer<ChunkPos> action) {
            for (var x = centerX - radius; x <= centerX + radius; x++) {
                for (var z = centerZ - radius; z <= centerZ + radius; z++) {
                    action.accept(new ChunkPos(x, z));
                }
            }
        }
    }

    /**
     * Get the camera zones active for a player in their current dimension.
     *
     * @param player The player to look up.
     * @return The player's zones, possibly empty.
     */
    public static List<Zone> getZones(ServerPlayer player) {
        var entry = zones.get(player.getUUID());
        return entry == null || entry.dimension() != player.level().dimension() ? List.of() : entry.zones();
    }

    /**
     * Replace a player's camera zones (in their current dimension), re-applying their chunk tracking and entity
     * visibility when something changed.
     *
     * @param player   The player to update.
     * @param newZones The zones to sync to them.
     */
    public static void setZones(ServerPlayer player, List<Zone> newZones) {
        var entry = newZones.isEmpty() ? null : new PlayerZones(player.level().dimension(), List.copyOf(newZones));
        var previous = entry == null ? zones.remove(player.getUUID()) : zones.put(player.getUUID(), entry);
        if (previous == null ? entry == null : previous.equals(entry)) return;

        // The client must know its zones before the chunks start arriving, so it retains them.
        ServerNetworking.sendToPlayer(new CameraZonesMessage(newZones), player);

        if (player.level().getChunkSource() instanceof ServerChunkCache chunkCache) {
            // Re-apply the chunk tracking view, and re-evaluate entity visibility for the new zones.
            ((ChunkMapAccess) chunkCache.chunkMap).computercraft$updateChunkTracking(player);
            chunkCache.chunkMap.move(player);
        }
    }

    /**
     * Drop all state for a player, e.g. on disconnect.
     *
     * @param player The player to forget.
     */
    public static void removePlayer(ServerPlayer player) {
        zones.remove(player.getUUID());
    }

    /**
     * The mixin-implemented view of {@code ChunkMap}'s internals we need.
     */
    public interface ChunkMapAccess {
        /**
         * Recompute and re-apply the player's chunk tracking view.
         *
         * @param player The player to update.
         */
        void computercraft$updateChunkTracking(ServerPlayer player);
    }
}
