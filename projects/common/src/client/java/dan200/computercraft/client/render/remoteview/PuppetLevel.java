// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.render.remoteview;

import com.mojang.authlib.GameProfile;
import dan200.computercraft.mixin.client.ParticleEngineAccessor;
import dan200.computercraft.shared.camera.BroadcastChannels;
import dan200.computercraft.shared.network.client.RemoteViewConfigMessage;
import dan200.computercraft.shared.network.client.RemoteViewEffectMessage;
import dan200.computercraft.shared.network.client.RemoteViewEnvironmentMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TrackingEmitter;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;

/**
 * A stand-in client level for a dimension the player is not in, fed by the camera stream: real chunks (with block
 * entities and light), real entities, time and weather. A dedicated {@link LevelRenderer} then draws it through the
 * ordinary vanilla pipeline, so a cross-dimension view is indistinguishable from a same-dimension one - particles,
 * animations, sky and all.
 */
public final class PuppetLevel {
    private static final Logger LOG = LoggerFactory.getLogger(PuppetLevel.class);

    /** The render order of the vanilla particle sheets; unknown (modded) types sort after them. */
    private static final List<ParticleRenderType> PARTICLE_ORDER = List.of(
        ParticleRenderType.TERRAIN_SHEET,
        ParticleRenderType.PARTICLE_SHEET_OPAQUE,
        ParticleRenderType.PARTICLE_SHEET_LIT,
        ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT,
        ParticleRenderType.CUSTOM
    );

    private final ClientLevel level;
    private final LevelRenderer renderer;
    private final RenderBuffers renderBuffers;

    // The puppet's particles live in the *global* particle engine's storage slots while it spawns, ticks or
    // renders (see installParticles): a second ParticleEngine is not an option, as constructing one replaces (and
    // destroys) the stitched particle atlas.
    private final Map<ParticleRenderType, Queue<Particle>> particles = new TreeMap<>(
        Comparator.<ParticleRenderType>comparingInt(type -> {
            var index = PARTICLE_ORDER.indexOf(type);
            return index == -1 ? PARTICLE_ORDER.size() : index;
        }).thenComparingInt(System::identityHashCode)
    );
    private final Queue<Particle> particlesToAdd = new ArrayDeque<>();
    private final Queue<TrackingEmitter> trackingEmitters = new ArrayDeque<>();

    private Map<ParticleRenderType, Queue<Particle>> mainParticles = Map.of();
    private Queue<Particle> mainParticlesToAdd = new ArrayDeque<>();
    private Queue<TrackingEmitter> mainTrackingEmitters = new ArrayDeque<>();
    private @Nullable ClientLevel mainParticleLevel;
    private boolean particlesInstalled = false;

    private int chunksReceived = 0;
    private long lastStats = 0;

    private PuppetLevel(ClientLevel level, LevelRenderer renderer, RenderBuffers renderBuffers) {
        this.level = level;
        this.renderer = renderer;
        this.renderBuffers = renderBuffers;
    }

    /**
     * Point the global particle engine's storage (and level) at this puppet's, so particles spawned, ticked or
     * rendered while installed belong to (and see) the puppet. Callers must {@link #restoreParticles()} in a
     * {@code finally}.
     */
    void installParticles() {
        if (particlesInstalled) return;
        particlesInstalled = true;
        var engine = (ParticleEngineAccessor) Minecraft.getInstance().particleEngine;
        mainParticles = engine.computercraft$getParticles();
        mainParticlesToAdd = engine.computercraft$getParticlesToAdd();
        mainTrackingEmitters = engine.computercraft$getTrackingEmitters();
        mainParticleLevel = engine.computercraft$getLevel();
        engine.computercraft$setParticles(particles);
        engine.computercraft$setParticlesToAdd(particlesToAdd);
        engine.computercraft$setTrackingEmitters(trackingEmitters);
        engine.computercraft$setLevel(level);
    }

    /** Put the global particle engine's own storage back; see {@link #installParticles()}. */
    void restoreParticles() {
        if (!particlesInstalled) return;
        particlesInstalled = false;
        var engine = (ParticleEngineAccessor) Minecraft.getInstance().particleEngine;
        engine.computercraft$setParticles(mainParticles);
        engine.computercraft$setParticlesToAdd(mainParticlesToAdd);
        engine.computercraft$setTrackingEmitters(mainTrackingEmitters);
        engine.computercraft$setLevel(mainParticleLevel);
    }

    /**
     * Build a puppet level for a view's dimension.
     *
     * @param config The view being watched.
     * @return The new puppet, or {@code null} if it cannot be built (e.g. an unknown dimension type).
     */
    static @Nullable PuppetLevel create(RemoteViewConfigMessage config) {
        var minecraft = Minecraft.getInstance();
        var connection = minecraft.getConnection();
        var mainLevel = minecraft.level;
        if (connection == null || mainLevel == null) return null;

        var dimensionType = connection.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
            .getHolder(ResourceKey.create(Registries.DIMENSION_TYPE, config.dimensionType()))
            .orElse(null);
        if (dimensionType == null) {
            LOG.warn("[camera] Unknown dimension type {} for channel {}", config.dimensionType(), config.channel());
            return null;
        }

        // The renderer gets its own RenderBuffers: section meshing runs on background threads, and the main
        // renderer's builders are not safe to share.
        var renderBuffers = new RenderBuffers(1);
        var renderer = new LevelRenderer(minecraft, minecraft.getEntityRenderDispatcher(), minecraft.getBlockEntityRenderDispatcher(), renderBuffers);
        var viewDistance = config.streamRadius() + 3;
        var level = new ClientLevel(
            connection, new ClientLevel.ClientLevelData(Difficulty.NORMAL, false, false),
            ResourceKey.create(Registries.DIMENSION, config.dimension()), dimensionType,
            viewDistance, viewDistance, minecraft::getProfiler, renderer, false, config.biomeSeed()
        );
        renderer.setLevel(level);
        // setLevel repoints the *shared* entity render dispatcher at the puppet; put it back immediately.
        minecraft.getEntityRenderDispatcher().setLevel(minecraft.level);
        // The renderer's section grid must follow the camera, not the player (who is in another dimension).
        ((RemoteViewRenderOverride) renderer).computercraft$setCameraOverride(config.cameraPos());

        LOG.info("[camera] Created puppet level for {} (channel {})", config.dimension(), config.channel());
        return new PuppetLevel(level, renderer, renderBuffers);
    }

    ClientLevel level() {
        return level;
    }

    LevelRenderer renderer() {
        return renderer;
    }

    /**
     * Whether any world data has arrived yet.
     *
     * @return Whether the puppet has something to draw.
     */
    boolean ready() {
        return chunksReceived > 0;
    }

    /**
     * The number of chunk columns received so far, for capture readiness.
     *
     * @return The received column count.
     */
    int chunksReceived() {
        return chunksReceived;
    }

    /**
     * Keep the chunk storage centred on the camera, so streamed chunks fit in the cache's window.
     *
     * @param config The current view configuration.
     */
    void recentre(RemoteViewConfigMessage config) {
        var centre = new ChunkPos(BlockPos.containing(config.cameraPos()));
        level.getChunkSource().updateViewCenter(centre.x, centre.z);
        ((RemoteViewRenderOverride) renderer).computercraft$setCameraOverride(config.cameraPos());
    }

    /**
     * Apply one streamed chunk column: blocks, block entities and light. Mirrors what the vanilla packet listener
     * does for the real level.
     *
     * @param data The serialised {@link ClientboundLevelChunkWithLightPacket}.
     */
    void applyChunk(byte[] data) {
        var buf = RegistryFriendlyByteBuf.decorator(level.registryAccess()).apply(Unpooled.wrappedBuffer(data));
        ClientboundLevelChunkWithLightPacket packet;
        try {
            packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }

        var x = packet.getX();
        var z = packet.getZ();
        var chunkData = packet.getChunkData();
        level.getChunkSource().replaceWithPacketData(x, z, chunkData.getReadBuffer(), chunkData.getHeightmaps(), chunkData.getBlockEntitiesTagsConsumer(x, z));
        applyLight(x, z, packet.getLightData());
        var chunk = level.getChunkSource().getChunk(x, z, false);
        if (chunk != null) enableChunkLight(chunk, x, z);
        chunksReceived++;
    }

    private void applyLight(int x, int z, ClientboundLightUpdatePacketData data) {
        var engine = level.getChunkSource().getLightEngine();
        readSectionList(x, z, engine, LightLayer.SKY, data.getSkyYMask(), data.getEmptySkyYMask(), data.getSkyUpdates().iterator());
        readSectionList(x, z, engine, LightLayer.BLOCK, data.getBlockYMask(), data.getEmptyBlockYMask(), data.getBlockUpdates().iterator());
        engine.setLightEnabled(new ChunkPos(x, z), true);
    }

    private void readSectionList(int x, int z, LevelLightEngine engine, LightLayer layer, BitSet mask, BitSet emptyMask, Iterator<byte[]> updates) {
        for (var i = 0; i < engine.getLightSectionCount(); i++) {
            var y = engine.getMinLightSection() + i;
            var present = mask.get(i);
            var empty = emptyMask.get(i);
            if (present || empty) {
                engine.queueSectionData(layer, SectionPos.of(x, y, z), present ? new DataLayer(updates.next().clone()) : new DataLayer());
                level.setSectionDirtyWithNeighbors(x, y, z);
            }
        }
    }

    private void enableChunkLight(LevelChunk chunk, int x, int z) {
        var engine = level.getChunkSource().getLightEngine();
        var sections = chunk.getSections();
        var pos = chunk.getPos();
        for (var i = 0; i < sections.length; i++) {
            var y = level.getSectionYFromSectionIndex(i);
            engine.updateSectionStatus(SectionPos.of(pos, y), sections[i].hasOnlyAir());
            level.setSectionDirtyWithNeighbors(x, y, z);
        }
    }

    /**
     * Apply a batch of entity updates; see the {@code ENTITY_OP_*} opcodes in {@link BroadcastChannels}.
     *
     * @param payload The serialised entity updates.
     */
    void applyEntities(byte[] payload) {
        var buf = RegistryFriendlyByteBuf.decorator(level.registryAccess()).apply(Unpooled.wrappedBuffer(payload));
        try {
            while (buf.isReadable()) {
                switch (buf.readByte()) {
                    case BroadcastChannels.ENTITY_OP_REMOVE -> level.removeEntity(buf.readVarInt(), Entity.RemovalReason.DISCARDED);
                    case BroadcastChannels.ENTITY_OP_ADD -> readAdd(buf);
                    case BroadcastChannels.ENTITY_OP_MOVE -> readMove(buf);
                    case BroadcastChannels.ENTITY_OP_DATA -> readData(buf.readByteArray());
                    case BroadcastChannels.ENTITY_OP_EQUIPMENT -> readEquipment(buf.readByteArray());
                    default -> {
                        LOG.warn("[camera] Malformed entity stream for {}", level.dimension().location());
                        return;
                    }
                }
            }
        } finally {
            buf.release();
        }
    }

    private void readAdd(RegistryFriendlyByteBuf buf) {
        var id = buf.readVarInt();
        var typeId = buf.readVarInt();
        Entity entity;
        if (buf.readBoolean()) {
            var uuid = buf.readUUID();
            var name = buf.readUtf();
            var player = new RemotePlayer(level, new GameProfile(uuid, name));
            player.setUUID(uuid);
            entity = player;
        } else {
            var uuid = buf.readUUID();
            entity = BuiltInRegistries.ENTITY_TYPE.byId(typeId).create(level);
            if (entity != null) entity.setUUID(uuid);
        }

        var x = buf.readDouble();
        var y = buf.readDouble();
        var z = buf.readDouble();
        var yRot = buf.readFloat();
        var xRot = buf.readFloat();
        var headRot = buf.readFloat();
        if (entity == null) return;

        entity.setId(id);
        entity.moveTo(x, y, z, yRot, xRot);
        entity.setYHeadRot(headRot);
        if (entity instanceof LivingEntity living) living.yBodyRot = yRot;
        entity.setOldPosAndRot();
        level.addEntity(entity);
    }

    private void readMove(RegistryFriendlyByteBuf buf) {
        var id = buf.readVarInt();
        var x = buf.readDouble();
        var y = buf.readDouble();
        var z = buf.readDouble();
        var yRot = buf.readFloat();
        var xRot = buf.readFloat();
        var headRot = buf.readFloat();

        var entity = level.getEntity(id);
        if (entity == null) return;
        entity.lerpTo(x, y, z, yRot, xRot, 3);
        entity.lerpHeadTo(headRot, 3);
    }

    private void readData(byte[] bytes) {
        var buf = RegistryFriendlyByteBuf.decorator(level.registryAccess()).apply(Unpooled.wrappedBuffer(bytes));
        try {
            var packet = ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buf);
            var entity = level.getEntity(packet.id());
            if (entity != null) entity.getEntityData().assignValues(packet.packedItems());
        } finally {
            buf.release();
        }
    }

    private void readEquipment(byte[] bytes) {
        var buf = RegistryFriendlyByteBuf.decorator(level.registryAccess()).apply(Unpooled.wrappedBuffer(bytes));
        try {
            var packet = ClientboundSetEquipmentPacket.STREAM_CODEC.decode(buf);
            if (level.getEntity(packet.getEntity()) instanceof LivingEntity living) {
                for (var slot : packet.getSlots()) living.setItemSlot(slot.getFirst(), slot.getSecond());
            }
        } finally {
            buf.release();
        }
    }

    /**
     * Apply a mirrored effect packet: particles, a level event, a block event or block cracking. Anything that
     * spawns particles must run with the global particle engine swapped to ours, or the particles appear in the
     * viewer's own world.
     *
     * @param message The mirrored effect.
     */
    void applyEffect(RemoteViewEffectMessage message) {
        if (!ready()) return;

        installParticles();
        try {
            var buf = RegistryFriendlyByteBuf.decorator(level.registryAccess()).apply(Unpooled.wrappedBuffer(message.data()));
            try {
                switch (message.kind()) {
                    case RemoteViewEffectMessage.KIND_PARTICLES -> applyParticles(ClientboundLevelParticlesPacket.STREAM_CODEC.decode(buf));
                    case RemoteViewEffectMessage.KIND_LEVEL_EVENT -> {
                        var packet = ClientboundLevelEventPacket.STREAM_CODEC.decode(buf);
                        level.levelEvent(packet.getType(), packet.getPos(), packet.getData());
                    }
                    case RemoteViewEffectMessage.KIND_BLOCK_EVENT -> {
                        var packet = ClientboundBlockEventPacket.STREAM_CODEC.decode(buf);
                        level.blockEvent(packet.getPos(), packet.getBlock(), packet.getB0(), packet.getB1());
                    }
                    case RemoteViewEffectMessage.KIND_BLOCK_DESTRUCTION -> {
                        var packet = ClientboundBlockDestructionPacket.STREAM_CODEC.decode(buf);
                        level.destroyBlockProgress(packet.getId(), packet.getPos(), packet.getProgress());
                    }
                    default -> LOG.warn("[camera] Unknown effect kind {}", message.kind());
                }
            } finally {
                buf.release();
            }
        } finally {
            restoreParticles();
        }
    }

    private void applyParticles(ClientboundLevelParticlesPacket packet) {
        // Mirrors the vanilla packet listener's particle handling.
        if (packet.getCount() == 0) {
            level.addParticle(
                packet.getParticle(), packet.isOverrideLimiter(),
                packet.getX(), packet.getY(), packet.getZ(),
                packet.getMaxSpeed() * packet.getXDist(), packet.getMaxSpeed() * packet.getYDist(), packet.getMaxSpeed() * packet.getZDist()
            );
            return;
        }

        var random = level.getRandom();
        for (var i = 0; i < packet.getCount(); i++) {
            level.addParticle(
                packet.getParticle(), packet.isOverrideLimiter(),
                packet.getX() + random.nextGaussian() * packet.getXDist(),
                packet.getY() + random.nextGaussian() * packet.getYDist(),
                packet.getZ() + random.nextGaussian() * packet.getZDist(),
                random.nextGaussian() * packet.getMaxSpeed(),
                random.nextGaussian() * packet.getMaxSpeed(),
                random.nextGaussian() * packet.getMaxSpeed()
            );
        }
    }

    /**
     * Apply streamed time and weather.
     *
     * @param message The environment update.
     */
    void applyEnvironment(RemoteViewEnvironmentMessage message) {
        level.setGameTime(message.dayTime());
        level.setDayTime(message.dayTime());
        level.setRainLevel(message.rainLevel());
        level.setThunderLevel(message.thunderLevel());
    }

    /**
     * Advance the puppet by one tick: entity interpolation and animations, block entities, ambient particles and
     * the particle engine itself. Called once per client tick.
     *
     * @param cameraPos The camera's eye position, around which ambient effects play.
     */
    void tick(Vec3 cameraPos) {
        if (!ready()) return;

        // Entity and block ticking spawn particles, so the puppet's particle storage must be installed throughout.
        installParticles();
        try {
            level.tick(() -> true);
            level.tickEntities();
            var pos = BlockPos.containing(cameraPos);
            level.animateTick(pos.getX(), pos.getY(), pos.getZ());
            Minecraft.getInstance().particleEngine.tick();
            // Rain and cloud animation run off the renderer's own tick counter, which nothing else advances.
            renderer.tick();
        } finally {
            restoreParticles();
        }

        var time = level.getGameTime();
        if (time - lastStats >= 100) {
            lastStats = time;
            LOG.info(
                "[camera] Puppet {}: {} chunks received, renderer [{}], {} entities",
                level.dimension().location(), chunksReceived, renderer.getSectionStatistics(), level.getEntityCount()
            );
        }
    }

    /**
     * Tear the puppet down, releasing its renderer's GPU resources.
     */
    void close() {
        var minecraft = Minecraft.getInstance();
        restoreParticles();
        renderer.setLevel(null);
        minecraft.getEntityRenderDispatcher().setLevel(minecraft.level);
        renderBuffers.fixedBufferPack().discardAll();
        LOG.info("[camera] Closed puppet level for {}", level.dimension().location());
    }

    /**
     * The level's dimension id, for sanity checks.
     *
     * @return The puppet's dimension.
     */
    ResourceKey<Level> dimension() {
        return level.dimension();
    }
}
