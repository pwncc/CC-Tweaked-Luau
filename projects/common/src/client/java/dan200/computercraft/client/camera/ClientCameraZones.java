// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.camera;

import dan200.computercraft.shared.camera.CameraViewZones;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client half of {@linkplain CameraViewZones camera zones}: which extra chunk zones the server is syncing to
 * us, and the chunks received for them.
 * <p>
 * The vanilla client chunk storage is a fixed ring around the player and silently discards anything outside it, so
 * zone chunks live in this side-store instead (see {@code ClientChunkCacheMixin}). They are still ordinary
 * {@link LevelChunk}s in the ordinary client level - block updates and block entity data apply to them exactly as
 * usual, and the camera renderer draws them like any other part of the world.
 */
public final class ClientCameraZones {
    private static List<CameraViewZones.Zone> zones = List.of();
    private static final Map<Long, LevelChunk> chunks = new HashMap<>();

    private ClientCameraZones() {
    }

    /**
     * Update the zones the server is syncing to this client.
     *
     * @param newZones The active zones.
     */
    public static void setZones(List<CameraViewZones.Zone> newZones) {
        zones = List.copyOf(newZones);
        // Chunks for removed zones are dropped explicitly by the server's tracking diff, so nothing to clean here.
    }

    /**
     * Whether a chunk position is inside one of our zones.
     *
     * @param x The chunk's x position.
     * @param z The chunk's z position.
     * @return Whether the chunk should be retained.
     */
    public static boolean isInZone(int x, int z) {
        for (var zone : zones) {
            if (zone.contains(x, z)) return true;
        }
        return false;
    }

    /**
     * Store a zone chunk.
     *
     * @param chunk The received chunk.
     */
    public static void putChunk(LevelChunk chunk) {
        chunks.put(chunk.getPos().toLong(), chunk);
    }

    /**
     * Get a stored zone chunk.
     *
     * @param x The chunk's x position.
     * @param z The chunk's z position.
     * @return The stored chunk, if any.
     */
    public static @Nullable LevelChunk getChunk(int x, int z) {
        if (chunks.isEmpty()) return null;
        return chunks.get(ChunkPos.asLong(x, z));
    }

    /**
     * Remove a zone chunk, e.g. when the server drops it.
     *
     * @param pos The chunk's position.
     * @return The removed chunk, if any.
     */
    public static @Nullable LevelChunk removeChunk(ChunkPos pos) {
        if (chunks.isEmpty()) return null;
        return chunks.remove(pos.toLong());
    }

    /** Reset all state, e.g. when leaving a world. */
    public static void clear() {
        zones = List.of();
        chunks.clear();
    }
}
