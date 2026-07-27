// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.camera;

import dan200.computercraft.shared.peripheral.camera.CameraBlockEntity;
import dan200.computercraft.shared.turtle.blocks.TurtleBlockEntity;
import dan200.computercraft.shared.turtle.upgrades.TurtleCamera;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the chunks around broadcasting cameras loaded, across restarts and regardless of viewers.
 * <p>
 * A camera in an unvisited dimension has a bootstrap problem: its block entity cannot tick (and so cannot keep
 * itself loaded) until its chunk is loaded. Broadcasting cameras are therefore registered here (persistently), and
 * the server re-arms a loading ticket around each one every second.
 */
public final class CameraChunkLoader extends SavedData {
    private static final String ID = "computercraft_cameras";
    private static final int REARM_INTERVAL = 20;

    // The fix type is a formality: our tags are always written at the current data version, so no fixes apply.
    private static final Factory<CameraChunkLoader> FACTORY =
        new Factory<>(CameraChunkLoader::new, CameraChunkLoader::load, net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Set<GlobalPos> cameras = new HashSet<>();

    private CameraChunkLoader() {
    }

    private static CameraChunkLoader load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new CameraChunkLoader();
        for (var entry : tag.getList("Cameras", Tag.TAG_COMPOUND)) {
            var camera = (CompoundTag) entry;
            var dimension = ResourceLocation.tryParse(camera.getString("Dimension"));
            if (dimension == null) continue;
            data.cameras.add(GlobalPos.of(
                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension),
                BlockPos.of(camera.getLong("Pos"))
            ));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var camera : cameras) {
            var entry = new CompoundTag();
            entry.putString("Dimension", camera.dimension().location().toString());
            entry.putLong("Pos", camera.pos().asLong());
            list.add(entry);
        }
        tag.put("Cameras", list);
        return tag;
    }

    private static CameraChunkLoader get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /**
     * Record that a camera has started broadcasting, keeping its surroundings loaded.
     *
     * @param level The camera's level.
     * @param pos   The camera's position.
     */
    public static void add(ServerLevel level, BlockPos pos) {
        var data = get(level.getServer());
        if (data.cameras.add(GlobalPos.of(level.dimension(), pos.immutable()))) data.setDirty();
        // Arm immediately, so the camera starts ticking without waiting for the next sweep.
        armTicket(level, pos);
    }

    /**
     * Record that a camera has stopped broadcasting.
     *
     * @param level The camera's level.
     * @param pos   The camera's position.
     */
    public static void remove(ServerLevel level, BlockPos pos) {
        remove(level.getServer(), GlobalPos.of(level.dimension(), pos.immutable()));
    }

    /**
     * Record that a camera has stopped broadcasting (or moved away), by its recorded position. Used by moving
     * sources, whose old registration may be in another dimension entirely.
     *
     * @param server The current server.
     * @param pos    The registration to remove.
     */
    public static void remove(MinecraftServer server, GlobalPos pos) {
        var data = get(server);
        if (data.cameras.remove(pos)) data.setDirty();
    }

    /**
     * Re-arm the loading tickets of all broadcasting cameras, dropping registrations whose camera is gone (broken
     * turtles, exploded blocks and similar leave no other trace). Called every server tick.
     *
     * @param server The current server.
     */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % REARM_INTERVAL != 0) return;

        var data = get(server);
        if (data.cameras.isEmpty()) return;

        for (var iterator = data.cameras.iterator(); iterator.hasNext(); ) {
            var camera = iterator.next();
            var level = server.getLevel(camera.dimension());
            if (level == null) continue;

            // Only judge a registration once its chunk has actually loaded; before that the block entity is
            // simply not there to be asked.
            var pos = camera.pos();
            if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null && !isBroadcaster(level, pos)) {
                iterator.remove();
                data.setDirty();
                continue;
            }

            armTicket(level, pos);
        }
    }

    private static boolean isBroadcaster(ServerLevel level, BlockPos pos) {
        return switch (level.getBlockEntity(pos)) {
            case CameraBlockEntity camera -> camera.getChannel() != CameraSource.NO_CHANNEL;
            case TurtleBlockEntity turtle -> TurtleCamera.isBroadcasting(turtle.getAccess());
            case null, default -> false;
        };
    }

    private static void armTicket(ServerLevel level, BlockPos pos) {
        level.getChunkSource().addRegionTicket(
            BroadcastChannels.CAMERA_TICKET, new ChunkPos(pos),
            BroadcastChannels.streamRadius(level) + 1, Unit.INSTANCE
        );
    }
}
