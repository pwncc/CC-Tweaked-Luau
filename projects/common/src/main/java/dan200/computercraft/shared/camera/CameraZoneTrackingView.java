// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.camera;

import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * A player's chunk tracking view with {@linkplain CameraViewZones camera zones} unioned in.
 * <p>
 * Installed by {@code ChunkMapMixin} whenever a player has active zones. {@link ChunkTrackingView#difference}
 * diff-syncs arbitrary view implementations through {@link #forEach}, so wrapping the vanilla view is all it takes
 * for zone chunks to be sent (and dropped) like any others.
 *
 * @param vanilla The vanilla view being wrapped.
 * @param zones   The extra zones to keep synced.
 */
public record CameraZoneTrackingView(ChunkTrackingView.Positioned vanilla, List<CameraViewZones.Zone> zones) implements ChunkTrackingView {
    @Override
    public boolean contains(int x, int z, boolean includeOuterChunksAdjacentToViewBorder) {
        if (vanilla.contains(x, z, includeOuterChunksAdjacentToViewBorder)) return true;
        for (var zone : zones) {
            if (zone.contains(x, z)) return true;
        }
        return false;
    }

    @Override
    public void forEach(Consumer<ChunkPos> action) {
        vanilla.forEach(action);

        // Zones may overlap each other or the vanilla view; visit every chunk exactly once.
        var seen = new HashSet<Long>();
        for (var zone : zones) {
            zone.forEach(pos -> {
                if (!vanilla.contains(pos.x, pos.z, true) && seen.add(pos.toLong())) action.accept(pos);
            });
        }
    }
}
