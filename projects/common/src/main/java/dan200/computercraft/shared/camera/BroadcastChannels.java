// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.camera;

import dan200.computercraft.shared.config.ConfigSpec;
import dan200.computercraft.shared.network.client.RemoteViewChunkMessage;
import dan200.computercraft.shared.network.client.RemoteViewConfigMessage;
import dan200.computercraft.shared.network.client.RemoteViewEffectMessage;
import dan200.computercraft.shared.network.client.RemoteViewEntitiesMessage;
import dan200.computercraft.shared.network.client.RemoteViewEnvironmentMessage;
import dan200.computercraft.shared.network.client.RemoteViewStopMessage;
import dan200.computercraft.shared.network.server.ServerNetworking;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The server-side registry of camera broadcasts.
 * <p>
 * Cameras {@linkplain #updateCamera pump} their channel every tick while broadcasting. When a channel has viewers
 * (players whose client has {@linkplain #watch requested} it), the pump streams the world sections around the camera
 * to them: nearest sections first when a viewer tunes in, then a continuous rotating refresh, so the picture forms
 * quickly and stays live without needing block-update hooks.
 * <p>
 * All state is transient: subscriptions vanish on disconnect, and a channel with no ticking camera goes dark.
 */
public final class BroadcastChannels {
    private static final Logger LOG = LoggerFactory.getLogger(BroadcastChannels.class);

    /** Keeps the camera's surroundings loaded while it is broadcasting. Expires on its own if not refreshed. */
    static final TicketType<Unit> CAMERA_TICKET = TicketType.create("computercraft:camera", (a, b) -> 0, 60);

    private static final Map<MinecraftServer, BroadcastChannels> instances = new WeakHashMap<>();

    public static BroadcastChannels get(MinecraftServer server) {
        synchronized (instances) {
            return instances.computeIfAbsent(server, s -> new BroadcastChannels());
        }
    }

    /**
     * The radius (in chunks) streamed around a camera: the server's own view distance, so views reach as far as
     * anyone standing there would see, unless the config asks for even more.
     *
     * @param level The camera's level.
     * @return The streamed radius, in chunks.
     */
    public static int streamRadius(ServerLevel level) {
        return Math.max(ConfigSpec.cameraStreamRadius.get(), level.getServer().getPlayerList().getViewDistance());
    }

    /**
     * The modems a camera can transmit through: any modem block touching it, plus any modem equipped on it (a
     * turtle's modem upgrade).
     *
     * @param camera The broadcasting camera.
     * @return The camera's modems.
     */
    public static List<VideoLinks.Modem> transmitters(CameraSource camera) {
        var level = camera.cameraLevel();
        if (level == null) return List.of();
        // Scan where the camera's blocks actually live: on a physics structure that is the structure's own grid,
        // not the world-space spot the view looks out from.
        var pos = camera.sourcePosition();
        var modems = new ArrayList<>(VideoLinks.modemsForRig(level, List.of(pos)));
        var equipped = camera.equippedModem();
        if (equipped >= 0) modems.add(new VideoLinks.Modem(equipped, BlockPos.containing(camera.getViewPosition()), null));
        return modems;
    }

    /**
     * Whether a camera can transmit its broadcast: it needs a modem (unless the requirement is disabled).
     *
     * @param camera The broadcasting camera.
     * @return Whether the camera's signal goes out.
     */
    public static boolean canTransmit(CameraSource camera) {
        if (!ConfigSpec.cameraRequireModem.get()) return true;
        return !transmitters(camera).isEmpty();
    }

    /**
     * Whether a viewer can receive a camera's broadcast: either they stand right next to the camera, they are a
     * leased capture renderer, or the screen they watch through has a modem linked to one of the camera's (wired
     * networks connect privately; wireless in radio range; ender anywhere and across dimensions).
     *
     * @param viewer The watching player.
     * @param screen The screen the viewer watches through, if known.
     * @param camera The broadcasting camera.
     * @return Whether the viewer picks up the signal.
     */
    public static boolean canReceive(ServerPlayer viewer, @Nullable BlockPos screen, CameraSource camera) {
        if (!ConfigSpec.cameraRequireModem.get()) return true;

        var directRange = ConfigSpec.cameraDirectRange.get();
        if (directRange > 0 && viewer.level() == camera.cameraLevel()
            && viewer.position().distanceToSqr(camera.getViewPosition()) <= (double) directRange * directRange
        ) {
            return true;
        }

        // Capture leases are machinery, not viewers: the server picked this client to render for a computer.
        if (CameraSnapshots.isLeased(viewer.getUUID())) return true;

        var cameraLevel = camera.cameraLevel();
        if (screen == null || cameraLevel == null) return false;
        // The client nominates which screen it watches through; don't let it claim one from across the map. The
        // screen's position is where its blocks live, so map it through any physics structure it is riding.
        if (!(viewer.level() instanceof ServerLevel viewerLevel)) return false;
        var screenPos = SableSupport.toWorldPosition(viewerLevel, Vec3.atCenterOf(screen));
        if (viewer.position().distanceToSqr(screenPos) > 64 * 64) return false;

        var receivers = receiversAround(viewerLevel, screen);
        return VideoLinks.linked(cameraLevel, transmitters(camera), viewerLevel, receivers);
    }

    /**
     * The modems serving a screen: those touching any of its blocks (every block of a multiblock monitor counts,
     * not just the origin corner the client nominates), or touching a computer attached to it.
     *
     * @param level  The screen's level.
     * @param screen The screen's (origin) position.
     * @return The modems serving the screen.
     */
    private static List<VideoLinks.Modem> receiversAround(ServerLevel level, BlockPos screen) {
        var blocks = new ArrayList<BlockPos>();
        if (level.getBlockEntity(screen) instanceof dan200.computercraft.shared.peripheral.monitor.MonitorBlockEntity monitor
            && (monitor.getWidth() > 1 || monitor.getHeight() > 1)
        ) {
            var right = monitor.getRight();
            var down = monitor.getDown();
            for (var x = 0; x < monitor.getWidth(); x++) {
                for (var y = 0; y < monitor.getHeight(); y++) {
                    blocks.add(screen.relative(right, x).relative(down, y));
                }
            }
        } else {
            blocks.add(screen);
        }
        return VideoLinks.modemsForRig(level, blocks);
    }

    /**
     * Whether a player is currently receiving a camera broadcast from near a position in <em>their own</em>
     * level. Sable's structure tracking uses this to keep a physics structure synced to players who watch it
     * through a camera from beyond normal tracking range - a rocket must not vanish out of its own camera view.
     *
     * @param viewer The player to check.
     * @param x      The position's x coordinate (typically a structure's origin).
     * @param y      The position's y coordinate.
     * @param z      The position's z coordinate.
     * @return Whether the player watches a live camera near that position.
     */
    public static boolean isWatchingNear(ServerPlayer viewer, double x, double y, double z) {
        BroadcastChannels instance;
        synchronized (instances) {
            instance = instances.get(viewer.server);
        }
        if (instance == null) return false;

        var intent = instance.viewerIntents.get(viewer);
        if (intent == null) return false;
        var channel = instance.channels.get(intent.channel());
        if (channel == null || !channel.receiving.contains(viewer) || channel.camera.isSourceRemoved()) return false;
        if (channel.camera.cameraLevel() != viewer.level()) return false;

        // Cameras ride anywhere on a structure, so allow a generous radius around its origin.
        return channel.camera.getViewPosition().distanceToSqr(x, y, z) < 512 * 512;
    }

    /**
     * Mirror a transient effect packet (particles, level events, block events, block cracking) to the
     * cross-dimension viewers of any camera whose streamed area contains it. Called from mixins on the vanilla
     * broadcast paths; a no-op unless a camera is actually streaming that spot to somebody remote.
     *
     * @param level  The level broadcasting the effect.
     * @param x      The effect's x position.
     * @param y      The effect's y position.
     * @param z      The effect's z position.
     * @param packet The vanilla packet describing the effect.
     */
    public static void forwardEffect(ServerLevel level, double x, double y, double z, net.minecraft.network.protocol.Packet<?> packet) {
        BroadcastChannels instance;
        synchronized (instances) {
            instance = instances.get(level.getServer());
        }
        if (instance == null || instance.channels.isEmpty()) return;

        var kind = -1;
        byte[] encoded = null;
        var chunkX = SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(x));
        var chunkZ = SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(z));
        for (var entry : instance.channels.entrySet()) {
            var channel = entry.getValue();
            if (channel.lastLevel != level || channel.lastCentre == null) continue;
            var radius = streamRadius(level);
            if (Math.abs(chunkX - channel.lastCentre.x) > radius || Math.abs(chunkZ - channel.lastCentre.z) > radius) continue;

            for (var viewer : channel.viewers.keySet()) {
                if (viewer.level() == level || !channel.receiving.contains(viewer)) continue;
                var data = encoded;
                if (data == null) {
                    kind = RemoteViewEffectMessage.kindOf(packet);
                    if (kind < 0) return;
                    encoded = data = RemoteViewEffectMessage.encode(level, kind, packet);
                }
                ServerNetworking.sendToPlayer(new RemoteViewEffectMessage(entry.getKey(), (byte) kind, data), viewer);
            }
        }
    }

    private final Map<Integer, Channel> channels = new HashMap<>();
    private final Map<ServerPlayer, Intent> viewerIntents = new HashMap<>();

    /**
     * What a player asked to watch: the channel, and the screen they are watching it through (used to find the
     * receiving modems).
     *
     * @param channel The watched channel.
     * @param screen  The screen's position, or {@code null} when watching without one (e.g. capture leases).
     */
    private record Intent(int channel, @Nullable BlockPos screen) {
    }

    private BroadcastChannels() {
    }

    /**
     * A player's client has asked to watch a channel (or to stop, with {@link CameraSource#NO_CHANNEL}).
     * A player watches at most one channel at a time.
     *
     * @param player  The watching player.
     * @param channel The channel to watch.
     * @param screen  The screen the player watches through, used to find the receiving modems.
     */
    public void watch(ServerPlayer player, int channel, @Nullable BlockPos screen) {
        LOG.info("[camera] {} asked to watch channel {}", player.getScoreboardName(), channel);
        viewerIntents.remove(player);
        for (var state : channels.values()) state.viewers.remove(player);

        if (channel >= 0 && channel <= CameraSource.MAX_CHANNEL) {
            viewerIntents.put(player, new Intent(channel, screen));
        } else {
            CameraViewZones.setZones(player, List.of());
        }
    }

    public void removePlayer(ServerPlayer player) {
        watch(player, CameraSource.NO_CHANNEL, null);
        CameraViewZones.removePlayer(player);
    }

    public int getViewerCount(int channel) {
        var count = 0;
        for (var watched : viewerIntents.values()) {
            if (watched.channel() == channel) count++;
        }
        return count;
    }

    /**
     * Get the channel a player is currently watching.
     *
     * @param player The player to look up.
     * @return Their watched channel, or {@code null} if they are not watching anything.
     */
    public @Nullable Integer getIntent(ServerPlayer player) {
        var intent = viewerIntents.get(player);
        return intent == null ? null : intent.channel();
    }

    public void removeCamera(int channel, CameraSource camera) {
        var state = channels.get(channel);
        if (state == null || !state.camera.equals(camera)) return;
        channels.remove(channel);
        for (var player : state.viewers.keySet()) {
            ServerNetworking.sendToPlayer(new RemoteViewStopMessage(channel), player);
        }
    }

    /**
     * Called every tick by a broadcasting camera: adopts the channel if free, and streams to its viewers.
     *
     * @param channelId The channel being broadcast on.
     * @param camera    The broadcasting camera.
     */
    public void updateCamera(int channelId, CameraSource camera) {
        var level = camera.cameraLevel();
        if (level == null) return;

        var channel = channels.get(channelId);
        if (channel == null || channel.camera.isSourceRemoved() || channel.camera.equals(camera)) {
            if (channel == null || !channel.camera.equals(camera)) {
                channel = new Channel(camera);
                channels.put(channelId, channel);
            }
        } else {
            return; // Another live camera owns this channel.
        }

        // Sync the channel's viewer set with current intents, dropping disconnected players.
        channel.viewers.keySet().removeIf(p -> {
            var intent = viewerIntents.get(p);
            return p.hasDisconnected() || intent == null || intent.channel() != channelId;
        });
        for (var entry : viewerIntents.entrySet()) {
            if (entry.getValue().channel() == channelId && !entry.getKey().hasDisconnected()) {
                channel.viewers.computeIfAbsent(entry.getKey(), p -> new ViewerState());
            }
        }

        if (channel.viewers.isEmpty()) return;

        // Keep the camera's surroundings loaded while watched. The ticket times out on its own, so we just
        // re-arm it every second.
        var chunkPos = new ChunkPos(BlockPos.containing(camera.getViewPosition()));
        if (channel.ticketCooldown-- <= 0) {
            level.getChunkSource().addRegionTicket(CAMERA_TICKET, chunkPos, streamRadius(level) + 1, Unit.INSTANCE);
            channel.ticketCooldown = 20;
        }

        // The broadcast only flows between modem-served ends (or to viewers stood right at the camera —
        // canReceive's direct-range bypass works even when the camera itself has no modem).
        var transmitting = canTransmit(camera);
        if (!transmitting && !channel.noTransmitLogged) {
            channel.noTransmitLogged = true;
            LOG.info("[camera] Channel {}: no modem on the camera rig at {} (camera or an attached computer); only direct-range viewers can see it", channelId, camera.getViewPosition());
        } else if (transmitting && channel.noTransmitLogged) {
            channel.noTransmitLogged = false;
            LOG.info("[camera] Channel {} is transmitting again", channelId);
        }

        channel.receiving.clear();
        for (var entry : channel.viewers.entrySet()) {
            var viewer = entry.getKey();
            var state = entry.getValue();
            var intent = viewerIntents.get(viewer);
            var ok = intent != null && canReceive(viewer, intent.screen(), camera);
            if (ok) {
                channel.receiving.add(viewer);
                state.blockedLogged = false;
                continue;
            }

            if (state.configSent) {
                // The viewer just lost the signal: tear their view down so the screen drops to NO SIGNAL instead
                // of freezing on the last streamed pose, and reset their stream state for a clean re-entry.
                state.configSent = false;
                state.environmentSent = false;
                state.entitiesSynced = false;
                state.catchUp = 0;
                ServerNetworking.sendToPlayer(new RemoteViewStopMessage(channelId), viewer);
            }
            if (!state.blockedLogged) {
                state.blockedLogged = true;
                LOG.info(
                    "[camera] {} cannot receive channel {}: no modem link between the camera and their screen at {}",
                    viewer.getScoreboardName(), channelId, intent == null ? null : intent.screen()
                );
            }
        }

        // Same-dimension viewers get a vanilla chunk-sync zone around the camera, for the full-fidelity renderer.
        // A moving camera may leave a viewer's dimension entirely, so clear the zones of everyone else (a no-op
        // for viewers who never had any).
        var zone = new CameraViewZones.Zone(chunkPos.x, chunkPos.z, streamRadius(level));
        for (var viewer : channel.viewers.keySet()) {
            var inZone = viewer.level() == level && channel.receiving.contains(viewer);
            CameraViewZones.setZones(viewer, inZone ? List.of(zone) : List.of());
        }

        // Send pose updates when the view changes, and the initial configuration to new viewers.
        var config = RemoteViewConfigMessage.of(channelId, camera);
        var configChanged = !config.equals(channel.lastConfig);
        channel.lastConfig = config;
        for (var entry : channel.viewers.entrySet()) {
            if (!channel.receiving.contains(entry.getKey())) continue;
            if (configChanged || !entry.getValue().configSent) {
                entry.getValue().configSent = true;
                LOG.info("[camera] Sending view config for channel {} to {}", channelId, entry.getKey().getScoreboardName());
                ServerNetworking.sendToPlayer(config, entry.getKey());
            }
        }

        // Viewers in other dimensions cannot see the real chunks and entities, so those are streamed to a puppet
        // level on their client instead.
        var hasRemoteViewer = false;
        for (var viewer : channel.receiving) {
            if (viewer.level() != level) {
                hasRemoteViewer = true;
                break;
            }
        }
        if (hasRemoteViewer) {
            streamEnvironment(channelId, channel, level);
            streamChunks(channelId, channel, level, chunkPos);
            streamEntities(channelId, channel, level, camera);
        } else if (!channel.trackedEntities.isEmpty()) {
            // Nobody remote is left: forget the tracked entities, so a returning viewer gets a fresh snapshot.
            channel.trackedEntities.clear();
        }
    }

    /**
     * Keep remote viewers' puppet levels in step with the camera dimension's time and weather.
     *
     * @param channelId The channel being broadcast on.
     * @param channel   The channel state.
     * @param level     The camera's level.
     */
    private void streamEnvironment(int channelId, Channel channel, ServerLevel level) {
        var environment = RemoteViewEnvironmentMessage.of(channelId, level);
        // Puppet clocks advance client-side too, so only correct meaningful drift (or weather changes).
        var last = channel.lastEnvironment;
        var changed = last == null
            || Math.abs(environment.dayTime() - last.dayTime()) > 100
            || environment.rainLevel() != last.rainLevel()
            || environment.thunderLevel() != last.thunderLevel();
        if (changed) channel.lastEnvironment = environment;

        for (var entry : channel.viewers.entrySet()) {
            if (entry.getKey().level() == level || !channel.receiving.contains(entry.getKey())) continue;
            if (changed || !entry.getValue().environmentSent) {
                entry.getValue().environmentSent = true;
                ServerNetworking.sendToPlayer(environment, entry.getKey());
            }
        }
    }

    /** Opcodes of the {@link RemoteViewEntitiesMessage} payload. */
    public static final int ENTITY_OP_REMOVE = 0;
    public static final int ENTITY_OP_ADD = 1;
    public static final int ENTITY_OP_MOVE = 2;
    public static final int ENTITY_OP_DATA = 3;
    public static final int ENTITY_OP_EQUIPMENT = 4;

    private static final int MAX_TRACKED_ENTITIES = 200;
    /** How often (in ticks) an entity's metadata and equipment are re-checked for changes. */
    private static final int ENTITY_REFRESH_INTERVAL = 10;
    private static final int CHUNK_CATCH_UP_BUDGET = 4;

    /**
     * Stream the entities around the camera to viewers in <em>other</em> dimensions, as adds, removes, absolute
     * moves, and (vanilla-encoded) metadata and equipment updates for their puppet levels. Same-dimension viewers
     * see the real entities through their camera zone.
     *
     * @param channelId The channel being broadcast on.
     * @param channel   The channel state.
     * @param level     The camera's level.
     * @param camera    The broadcasting camera.
     */
    private void streamEntities(int channelId, Channel channel, ServerLevel level, CameraSource camera) {
        var radius = streamRadius(level) * 16 + 16;
        var box = new AABB(BlockPos.containing(camera.getViewPosition())).inflate(radius);

        var seen = new HashMap<Integer, Entity>();
        for (var entity : level.getEntities((Entity) null, box, e -> !e.isSpectator() && e.getType().canSerialize() || e instanceof ServerPlayer)) {
            if (seen.size() >= MAX_TRACKED_ENTITIES) break;
            seen.put(entity.getId(), entity);
        }

        var out = RegistryFriendlyByteBuf.decorator(level.registryAccess()).apply(Unpooled.buffer());
        try {
            for (var iterator = channel.trackedEntities.entrySet().iterator(); iterator.hasNext(); ) {
                var tracked = iterator.next();
                if (!seen.containsKey(tracked.getKey())) {
                    iterator.remove();
                    out.writeByte(ENTITY_OP_REMOVE);
                    out.writeVarInt(tracked.getKey());
                }
            }

            for (var entity : seen.values()) {
                var state = channel.trackedEntities.get(entity.getId());
                if (state == null) {
                    channel.trackedEntities.put(entity.getId(), state = new TrackedEntity());
                    writeEntityAdd(out, entity, state);
                } else {
                    writeEntityMove(out, entity, state);
                }

                if (--state.refreshCooldown <= 0) {
                    state.refreshCooldown = ENTITY_REFRESH_INTERVAL;
                    writeEntityData(out, level, entity, state);
                    writeEntityEquipment(out, level, entity, state);
                }
            }

            // Fresh viewers get a full snapshot of everything already tracked; everyone else just the changes.
            byte[] delta = null, snapshot = null;
            for (var entry : channel.viewers.entrySet()) {
                if (entry.getKey().level() == level || !channel.receiving.contains(entry.getKey())) continue;
                if (entry.getValue().entitiesSynced) {
                    if (out.writerIndex() == 0) continue;
                    if (delta == null) {
                        delta = new byte[out.writerIndex()];
                        out.getBytes(0, delta);
                    }
                    ServerNetworking.sendToPlayer(new RemoteViewEntitiesMessage(channelId, delta), entry.getKey());
                } else {
                    entry.getValue().entitiesSynced = true;
                    if (snapshot == null) snapshot = buildEntitySnapshot(level, channel, seen);
                    if (snapshot.length > 0) {
                        ServerNetworking.sendToPlayer(new RemoteViewEntitiesMessage(channelId, snapshot), entry.getKey());
                    }
                }
            }
        } finally {
            out.release();
        }
    }

    /**
     * Serialise every currently-tracked entity from scratch, for a viewer who has seen nothing yet.
     *
     * @param level   The camera's level.
     * @param channel The channel state.
     * @param seen    The entities visible this tick, by id.
     * @return The serialised snapshot payload.
     */
    private static byte[] buildEntitySnapshot(ServerLevel level, Channel channel, Map<Integer, Entity> seen) {
        var out = RegistryFriendlyByteBuf.decorator(level.registryAccess()).apply(Unpooled.buffer());
        try {
            for (var entry : channel.trackedEntities.entrySet()) {
                var entity = seen.get(entry.getKey());
                if (entity == null) continue;
                var state = entry.getValue();
                writeEntityAdd(out, entity, state);
                if (state.data != null) {
                    out.writeByte(ENTITY_OP_DATA);
                    out.writeByteArray(state.data);
                }
                if (state.equipment != null) {
                    out.writeByte(ENTITY_OP_EQUIPMENT);
                    out.writeByteArray(state.equipment);
                }
            }
            var payload = new byte[out.writerIndex()];
            out.getBytes(0, payload);
            return payload;
        } finally {
            out.release();
        }
    }

    private static void writeEntityAdd(FriendlyByteBuf out, Entity entity, TrackedEntity state) {
        out.writeByte(ENTITY_OP_ADD);
        out.writeVarInt(entity.getId());
        out.writeVarInt(BuiltInRegistries.ENTITY_TYPE.getId(entity.getType()));
        if (entity instanceof ServerPlayer player) {
            out.writeBoolean(true);
            out.writeUUID(player.getUUID());
            out.writeUtf(player.getGameProfile().getName());
        } else {
            out.writeBoolean(false);
            out.writeUUID(entity.getUUID());
        }
        writePose(out, entity, state);
    }

    /**
     * Write an absolute position/rotation update if the entity moved.
     *
     * @param out    The buffer to write to.
     * @param entity The entity to check.
     * @param state  The entity's tracked state.
     * @return Whether an update was written.
     */
    private static boolean writeEntityMove(FriendlyByteBuf out, Entity entity, TrackedEntity state) {
        var head = entity instanceof LivingEntity living ? living.yHeadRot : entity.getYRot();
        if (state.pos != null && state.pos.distanceToSqr(entity.position()) < 1e-6
            && entity.getYRot() == state.yRot && entity.getXRot() == state.xRot && head == state.headRot) {
            return false;
        }

        out.writeByte(ENTITY_OP_MOVE);
        out.writeVarInt(entity.getId());
        writePose(out, entity, state);
        return true;
    }

    private static void writePose(FriendlyByteBuf out, Entity entity, TrackedEntity state) {
        state.pos = entity.position();
        state.yRot = entity.getYRot();
        state.xRot = entity.getXRot();
        state.headRot = entity instanceof LivingEntity living ? living.yHeadRot : entity.getYRot();
        out.writeDouble(state.pos.x);
        out.writeDouble(state.pos.y);
        out.writeDouble(state.pos.z);
        out.writeFloat(state.yRot);
        out.writeFloat(state.xRot);
        out.writeFloat(state.headRot);
    }

    private static void writeEntityData(RegistryFriendlyByteBuf out, ServerLevel level, Entity entity, TrackedEntity state) {
        var values = entity.getEntityData().getNonDefaultValues();
        if (values == null) return;

        var bytes = encodePacket(level, buf -> ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buf, new ClientboundSetEntityDataPacket(entity.getId(), values)));
        if (Arrays.equals(bytes, state.data)) return;
        state.data = bytes;
        out.writeByte(ENTITY_OP_DATA);
        out.writeByteArray(bytes);
    }

    private static void writeEntityEquipment(RegistryFriendlyByteBuf out, ServerLevel level, Entity entity, TrackedEntity state) {
        if (!(entity instanceof LivingEntity living)) return;

        List<com.mojang.datafixers.util.Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> slots = new ArrayList<>();
        for (var slot : EquipmentSlot.values()) {
            var stack = living.getItemBySlot(slot);
            if (!stack.isEmpty()) slots.add(com.mojang.datafixers.util.Pair.of(slot, stack));
        }
        if (slots.isEmpty() && state.equipment == null) return;

        var bytes = slots.isEmpty()
            ? new byte[0]
            : encodePacket(level, buf -> ClientboundSetEquipmentPacket.STREAM_CODEC.encode(buf, new ClientboundSetEquipmentPacket(entity.getId(), slots)));
        if (Arrays.equals(bytes, state.equipment)) return;
        state.equipment = bytes;
        if (bytes.length > 0) {
            out.writeByte(ENTITY_OP_EQUIPMENT);
            out.writeByteArray(bytes);
        }
    }

    private static byte[] encodePacket(ServerLevel level, java.util.function.Consumer<RegistryFriendlyByteBuf> writer) {
        var buf = RegistryFriendlyByteBuf.decorator(level.registryAccess()).apply(Unpooled.buffer());
        try {
            writer.accept(buf);
            var bytes = new byte[buf.writerIndex()];
            buf.getBytes(0, bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    /**
     * Stream the chunks around the camera to viewers in other dimensions, as full vanilla chunk packets for their
     * puppet level. The existing section scan spots changes cheaply; a changed section re-sends its whole column,
     * which carries block entities and light along for free.
     *
     * @param channelId The channel being broadcast on.
     * @param channel   The channel state.
     * @param level     The camera's level.
     * @param centre    The camera's chunk position.
     */
    private void streamChunks(int channelId, Channel channel, ServerLevel level, ChunkPos centre) {
        var sections = channel.sectionOrder;
        if (sections.isEmpty() || !centre.equals(channel.lastCentre) || level != channel.lastLevel) {
            channel.lastCentre = centre;
            channel.lastLevel = level;
            sections.clear();
            channel.sent.clear();
            channel.chunkPackets.clear();
            channel.scanCursor = 0;
            var columns = channel.chunkOrder;
            columns.clear();
            var radius = streamRadius(level);
            for (var x = centre.x - radius; x <= centre.x + radius; x++) {
                for (var z = centre.z - radius; z <= centre.z + radius; z++) {
                    columns.add(new ChunkPos(x, z));
                    for (var y = level.getMinSection(); y < level.getMaxSection(); y++) {
                        sections.add(SectionPos.of(x, y, z));
                    }
                }
            }
            // Nearest first, so a fresh picture forms outwards from the camera.
            var eye = channel.camera.getViewPosition();
            columns.sort(Comparator.comparingDouble(pos -> pos.getMiddleBlockPosition(0).getCenter().multiply(1, 0, 1).distanceToSqr(eye.x, 0, eye.z)));
            sections.sort(Comparator.comparingDouble(pos -> pos.origin().getCenter().distanceToSqr(eye)));
            for (var viewer : channel.viewers.values()) viewer.catchUp = 0;
        }
        if (sections.isEmpty()) return;

        // Scan a window of sections for changes. Only genuinely changed columns cost any bandwidth, so block
        // (and light) updates reach viewers within a fraction of a second.
        var scanBudget = Math.min(Math.max(1, ConfigSpec.cameraScanPerTick.get()), sections.size());
        var changedColumns = new HashSet<Long>();
        for (var i = 0; i < scanBudget; i++) {
            var index = (channel.scanCursor + i) % sections.size();
            var pos = sections.get(index);
            var entry = serialiseEntry(level, pos);
            if (entry == null) continue;

            var previous = channel.sent.get(pos.asLong());
            if (previous == null || !Arrays.equals(previous, entry)) {
                var fresh = previous == null;
                channel.sent.put(pos.asLong(), entry);
                // A never-scanned section is only "changed" if a viewer has already been sent this column.
                if (!fresh || channel.chunkPackets.containsKey(ChunkPos.asLong(pos.x(), pos.z()))) {
                    changedColumns.add(ChunkPos.asLong(pos.x(), pos.z()));
                }
            }
        }
        channel.scanCursor = (channel.scanCursor + scanBudget) % sections.size();
        for (var column : changedColumns) channel.chunkPackets.remove(column);

        for (var entry : channel.viewers.entrySet()) {
            if (entry.getKey().level() == level || !channel.receiving.contains(entry.getKey())) continue;
            var viewer = entry.getValue();

            if (viewer.catchUp >= channel.chunkOrder.size()) {
                for (var column : changedColumns) {
                    var message = chunkMessage(channelId, channel, level, new ChunkPos(column));
                    if (message != null) ServerNetworking.sendToPlayer(message, entry.getKey());
                }
                continue;
            }

            // Still catching up: changed columns will arrive freshly-built when their turn comes.
            var sentThisTick = 0;
            while (viewer.catchUp < channel.chunkOrder.size() && sentThisTick < CHUNK_CATCH_UP_BUDGET) {
                var pos = channel.chunkOrder.get(viewer.catchUp);
                var message = chunkMessage(channelId, channel, level, pos);
                if (message == null) break; // Not loaded yet: retry next tick, keeping the picture hole-free.
                viewer.catchUp++;
                sentThisTick++;
                ServerNetworking.sendToPlayer(message, entry.getKey());
            }
        }
    }

    private static @Nullable RemoteViewChunkMessage chunkMessage(int channelId, Channel channel, ServerLevel level, ChunkPos pos) {
        var cached = channel.chunkPackets.get(pos.toLong());
        if (cached != null) return new RemoteViewChunkMessage(channelId, cached);

        var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) return null;

        var message = RemoteViewChunkMessage.of(channelId, level, chunk);
        channel.chunkPackets.put(pos.toLong(), message.data());
        // Baseline the change detector for this column, so the scan's first pass over it (or a rebuild after a
        // change) doesn't count what was just sent as changed all over again.
        for (var y = level.getMinSection(); y < level.getMaxSection(); y++) {
            var section = SectionPos.of(pos.x, y, pos.z);
            var entry = serialiseEntry(level, section);
            if (entry != null) channel.sent.put(section.asLong(), entry);
        }
        return message;
    }

    /**
     * Serialise one section (contents and light) as a self-contained payload entry.
     *
     * @param level The level to read from.
     * @param pos   The section to serialise.
     * @return The serialised entry, or {@code null} if the chunk is not loaded.
     */
    private static byte @Nullable [] serialiseEntry(ServerLevel level, SectionPos pos) {
        var chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
        if (chunk == null) return null;

        var sectionIndex = pos.y() - level.getMinSection();
        var sections = chunk.getSections();
        if (sectionIndex < 0 || sectionIndex >= sections.length) return null;

        var out = new FriendlyByteBuf(Unpooled.buffer());
        var section = sections[sectionIndex];
        out.writeLong(pos.asLong());
        if (section.hasOnlyAir()) {
            out.writeVarInt(0);
        } else {
            var scratch = new FriendlyByteBuf(Unpooled.buffer());
            section.write(scratch);
            out.writeVarInt(scratch.writerIndex());
            out.writeBytes(scratch, 0, scratch.writerIndex());
        }
        writeLight(out, level, pos);

        var payload = new byte[out.writerIndex()];
        out.getBytes(0, payload);
        return payload;
    }

    /**
     * Append the block and sky light of a section as two 2048-byte nibble arrays. Air sections carry light too:
     * block faces sample the light of the (air) block they face.
     *
     * @param out   The buffer to write to.
     * @param level The level to read light from.
     * @param pos   The section to serialise.
     */
    private static void writeLight(FriendlyByteBuf out, ServerLevel level, SectionPos pos) {
        var engine = level.getLightEngine();
        var blockLight = engine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
        var skyLight = engine.getLayerListener(net.minecraft.world.level.LightLayer.SKY);

        var cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
        var block = new byte[2048];
        var sky = new byte[2048];
        var baseX = pos.minBlockX();
        var baseY = pos.minBlockY();
        var baseZ = pos.minBlockZ();
        for (var y = 0; y < 16; y++) {
            for (var z = 0; z < 16; z++) {
                for (var x = 0; x < 16; x++) {
                    cursor.set(baseX + x, baseY + y, baseZ + z);
                    var index = y << 8 | z << 4 | x;
                    var half = index >> 1;
                    var shift = (index & 1) << 2;
                    block[half] |= (byte) (blockLight.getLightValue(cursor) << shift);
                    sky[half] |= (byte) (skyLight.getLightValue(cursor) << shift);
                }
            }
        }
        out.writeBytes(block);
        out.writeBytes(sky);
    }

    private static final class Channel {
        final CameraSource camera;
        final Map<ServerPlayer, ViewerState> viewers = new HashMap<>();
        /** The viewers who currently pick up the signal (modem link or direct range); rebuilt every pump. */
        final Set<ServerPlayer> receiving = new HashSet<>();
        final ArrayList<SectionPos> sectionOrder = new ArrayList<>();
        final ArrayList<ChunkPos> chunkOrder = new ArrayList<>();
        /** The serialised state of each section when last scanned, for change detection. */
        final Map<Long, byte[]> sent = new HashMap<>();
        /** Serialised chunk packets by column, invalidated when a section in the column changes. */
        final Map<Long, byte[]> chunkPackets = new HashMap<>();
        /** The state each remote-streamed entity was last sent with. */
        final Map<Integer, TrackedEntity> trackedEntities = new HashMap<>();
        @Nullable ChunkPos lastCentre = null;
        @Nullable ServerLevel lastLevel = null;
        @Nullable RemoteViewConfigMessage lastConfig = null;
        @Nullable RemoteViewEnvironmentMessage lastEnvironment = null;
        int ticketCooldown = 0;
        int scanCursor = 0;
        boolean noTransmitLogged = false;

        Channel(CameraSource camera) {
            this.camera = camera;
        }
    }

    private static final class ViewerState {
        int catchUp = 0;
        boolean configSent = false;
        boolean environmentSent = false;
        boolean entitiesSynced = false;
        boolean blockedLogged = false;
    }

    private static final class TrackedEntity {
        @Nullable Vec3 pos;
        float yRot;
        float xRot;
        float headRot;
        byte @Nullable [] data;
        byte @Nullable [] equipment;
        int refreshCooldown = 0;
    }
}
