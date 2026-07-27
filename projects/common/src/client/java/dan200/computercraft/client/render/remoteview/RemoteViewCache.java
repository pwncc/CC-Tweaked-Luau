// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.render.remoteview;

import dan200.computercraft.client.FrameInfo;
import dan200.computercraft.client.network.ClientNetworking;
import dan200.computercraft.shared.network.client.RemoteViewChunkMessage;
import dan200.computercraft.shared.network.client.RemoteViewConfigMessage;
import dan200.computercraft.shared.network.client.RemoteViewEffectMessage;
import dan200.computercraft.shared.network.client.RemoteViewEntitiesMessage;
import dan200.computercraft.shared.network.client.RemoteViewEnvironmentMessage;
import dan200.computercraft.shared.network.client.RemoteViewSectionsMessage;
import dan200.computercraft.shared.network.server.CameraFrameMessage;
import dan200.computercraft.shared.network.server.WatchChannelMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side state for camera broadcasts: which channel this client is watching (the nearest tuned screen wins),
 * and the streamed world data for it.
 * <p>
 * Screens {@linkplain #requestView express interest} every frame they are rendered; the winner is re-evaluated each
 * frame, and watching stops shortly after no screen wants a view (for instance, every tuned screen is off-camera).
 */
public final class RemoteViewCache {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteViewCache.class);

    /** How long (in frames) after the last screen wanted a view before we stop the server streaming it. */
    private static final int STOP_AFTER_FRAMES = 600;
    /** How long after streaming stops before the cached picture (and its GL resources) are thrown away. */
    private static final int DISCARD_AFTER_FRAMES = 1800;

    /** How long a capture may wait for a complete, freshly-meshed picture before giving up. */
    private static final int CAPTURE_TIMEOUT_TICKS = 300;
    /** After this long, a capture settles for whatever has been meshed so far. */
    private static final int CAPTURE_BEST_EFFORT_TICKS = 100;

    private static final Map<Integer, ChannelView> views = new HashMap<>();
    private static final List<CaptureJob> captures = new ArrayList<>();

    private static int watching = -1;
    private static long lastRequestFrame = -1;
    private static long candidateFrame = -1;
    private static int candidateChannel = -1;
    private static @Nullable BlockPos candidateScreen = null;
    private static double candidateDistance = Double.MAX_VALUE;
    private static @Nullable BlockPos watchingScreen = null;

    private RemoteViewCache() {
    }

    /**
     * Called by screen renderers each frame they draw a tuned screen: nominate a channel to watch.
     *
     * @param channel    The channel the screen is tuned to.
     * @param distanceSq The squared distance from the player to the screen.
     * @param screen     The screen's position, passed to the server to find the receiving modems.
     */
    public static void requestView(int channel, double distanceSq, @Nullable BlockPos screen) {
        // While a capture is running it owns the watch slot; screens would otherwise fight it for the
        // stream every frame, and neither picture would ever finish forming.
        if (!captures.isEmpty()) return;

        var frame = FrameInfo.getRenderFrame();
        if (frame != candidateFrame) {
            // A new frame: the previous frame's winner becomes the watch target.
            commitCandidate();
            candidateFrame = frame;
            candidateChannel = channel;
            candidateScreen = screen;
            candidateDistance = distanceSq;
        } else if (distanceSq < candidateDistance) {
            candidateChannel = channel;
            candidateScreen = screen;
            candidateDistance = distanceSq;
        }
        lastRequestFrame = frame;
    }

    private static void commitCandidate() {
        if (candidateChannel != -1 && (candidateChannel != watching || !java.util.Objects.equals(candidateScreen, watchingScreen))) {
            setWatching(candidateChannel, candidateScreen);
        }
        candidateChannel = -1;
        candidateScreen = null;
        candidateDistance = Double.MAX_VALUE;
    }

    private static void setWatching(int channel, @Nullable BlockPos screen) {
        LOG.info("[camera] Now watching channel {}", channel);
        watching = channel;
        watchingScreen = screen;
        ClientNetworking.sendToServer(WatchChannelMessage.of(channel, screen));

        // Drop the views (and their render targets) of channels we no longer watch.
        for (var iterator = views.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (entry.getKey() != channel) {
                entry.getValue().close();
                iterator.remove();
            }
        }
    }

    /**
     * Called once per client tick: advance captures, stop watching when nothing has requested a view recently, and
     * drop stale views.
     */
    public static void clientTick() {
        if (!captures.isEmpty()) tickCaptures();

        for (var view : views.values()) {
            if (view.puppet != null) view.puppet.tick(view.config().cameraPos());
            // Dedicated renderers never see Minecraft's own levelRenderer.tick(), so advance them here: rain and
            // cloud animation run off the renderer's tick counter and freeze mid-air without it.
            if (view.local != null) view.local.renderer().tick();
        }

        var frame = FrameInfo.getRenderFrame();
        if (watching != -1 && frame - lastRequestFrame > STOP_AFTER_FRAMES) {
            // Stop the stream, but keep the cached picture: glancing back should be instant.
            watching = -1;
            watchingScreen = null;
            ClientNetworking.sendToServer(WatchChannelMessage.of(-1, null));
        }

        if (watching == -1 && !views.isEmpty() && frame - lastRequestFrame > STOP_AFTER_FRAMES + DISCARD_AFTER_FRAMES) {
            for (var view : views.values()) view.close();
            views.clear();
        }
    }

    /** Reset all state, e.g. when leaving a world. */
    public static void clear() {
        watching = -1;
        lastRequestFrame = candidateFrame = -1;
        candidateChannel = -1;
        for (var view : views.values()) view.close();
        views.clear();
        captures.clear();
    }

    /**
     * The server has asked this client to render a snapshot of a channel; see
     * {@link dan200.computercraft.shared.camera.CameraSnapshots}.
     *
     * @param requestId The capture request id, echoed in the upload.
     * @param channel   The channel to render.
     * @param width     The frame width, in pixels.
     * @param height    The frame height, in pixels.
     */
    public static void startCapture(long requestId, int channel, int width, int height) {
        captures.add(new CaptureJob(requestId, channel, width, height));
    }

    private static void tickCaptures() {
        // Captures run one at a time: they need exclusive use of the watch slot to stream their channel.
        var job = captures.get(0);
        job.ticks++;

        if (watching != job.channel) setWatching(job.channel, null);
        lastRequestFrame = FrameInfo.getRenderFrame();

        var frame = tryCapture(job);
        if (frame != null) {
            for (var offset = 0; offset < frame.length; offset += CameraFrameMessage.MAX_CHUNK) {
                var length = Math.min(CameraFrameMessage.MAX_CHUNK, frame.length - offset);
                var chunk = new byte[length];
                System.arraycopy(frame, offset, chunk, 0, length);
                ClientNetworking.sendToServer(new CameraFrameMessage(job.requestId, frame.length, offset, chunk));
            }
            captures.remove(0);
        } else if (job.ticks >= CAPTURE_TIMEOUT_TICKS) {
            ClientNetworking.sendToServer(new CameraFrameMessage(job.requestId, -1, 0, new byte[0]));
            captures.remove(0);
        }
    }

    private static byte @Nullable [] tryCapture(CaptureJob job) {
        var view = views.get(job.channel);
        if (view == null) return null;

        // Drive rendering even when no screen shows this channel, so meshes keep baking.
        var renderer = view.renderer();
        renderer.getTexture(RemoteViewRenderer.VIEW_WIDTH, RemoteViewRenderer.VIEW_HEIGHT);

        // Wait until the picture is complete before capturing. If that takes suspiciously long (chunks the server
        // cannot load never arrive, say), settle for what we have.
        boolean ready, bestEffort;
        if (view.puppet != null) {
            var columns = view.config().streamRadius() * 2 + 1;
            ready = view.puppet.chunksReceived() >= columns * columns;
            bestEffort = job.ticks >= CAPTURE_BEST_EFFORT_TICKS && view.puppet.ready();
        } else {
            if (view.coverageComplete() && job.coverageRevision == -1) job.coverageRevision = view.revision();
            ready = job.coverageRevision != -1 && renderer.bakedRevision() >= job.coverageRevision;
            bestEffort = job.ticks >= CAPTURE_BEST_EFFORT_TICKS && renderer.bakedRevision() > 0;
        }
        if (!ready && !bestEffort) return null;

        return renderer.capture(job.width, job.height);
    }

    @Nullable
    public static ChannelView getView(int channel) {
        return views.get(channel);
    }

    public static void handleConfig(RemoteViewConfigMessage config) {
        LOG.info("[camera] Received view config for channel {}", config.channel());
        var view = views.get(config.channel());
        // A dimension change means every streamed section is stale, so start the view over.
        if (view == null || view.world.getMinSectionYRaw() != config.minSectionY() || !view.config().dimension().equals(config.dimension())) {
            if (view != null) view.close();
            view = new ChannelView(config);
            views.put(config.channel(), view);
        } else {
            view.updateConfig(config);
        }
    }

    public static void handleSections(RemoteViewSectionsMessage message) {
        var view = views.get(message.channel());
        if (view == null) return;

        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var biomes = level.registryAccess().registryOrThrow(Registries.BIOME);

        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(message.payload()));
        while (buf.isReadable()) {
            var pos = buf.readLong();
            var length = buf.readVarInt();
            LevelChunkSection section = null;
            if (length != 0) {
                section = new LevelChunkSection(biomes);
                var slice = new FriendlyByteBuf(buf.readSlice(length));
                section.read(slice);
            }

            var blockLight = new byte[2048];
            var skyLight = new byte[2048];
            buf.readBytes(blockLight);
            buf.readBytes(skyLight);

            view.world.putSection(pos, section, new RemoteViewWorld.SectionLight(blockLight, skyLight));
            view.sectionReceived(pos);
        }
        if (view.revision() == 0) LOG.info("[camera] First sections arrived for channel {}", message.channel());
        view.markDirty();
    }

    public static void handleStop(int channel) {
        var view = views.remove(channel);
        if (view != null) view.close();
        if (watching == channel) watching = -1;
    }

    public static void handleEntities(RemoteViewEntitiesMessage message) {
        var view = views.get(message.channel());
        if (view == null) return;

        var puppet = view.puppet();
        if (puppet != null) puppet.applyEntities(message.payload());
    }

    public static void handleChunk(RemoteViewChunkMessage message) {
        var view = views.get(message.channel());
        if (view == null) return;

        var puppet = view.puppet();
        if (puppet == null) return;
        puppet.recentre(view.config());
        puppet.applyChunk(message.data());
    }

    public static void handleEnvironment(RemoteViewEnvironmentMessage message) {
        var view = views.get(message.channel());
        if (view == null) return;

        var puppet = view.puppet();
        if (puppet != null) puppet.applyEnvironment(message);
    }

    public static void handleEffect(RemoteViewEffectMessage message) {
        var view = views.get(message.channel());
        if (view == null) return;

        var puppet = view.puppet();
        if (puppet != null) puppet.applyEffect(message);
    }

    /**
     * The streamed state of one channel.
     */
    public static final class ChannelView {
        final RemoteViewWorld world;
        final RemoteViewEntities entities = new RemoteViewEntities();
        int lastEntityCount = -1;
        private RemoteViewConfigMessage config;
        private boolean dirty = true;
        private int revision;
        private int expectedSections;
        private final Set<Long> receivedSections = new HashSet<>();
        private @Nullable RemoteViewRenderer renderer;
        @Nullable PuppetLevel puppet;
        private boolean puppetBroken = false;
        private @Nullable LocalRenderer local;

        /** How long a pose update is interpolated over: one server tick, so moving cameras glide. */
        private static final long POSE_LERP_NANOS = 50_000_000L;

        private Vec3 poseFromPos;
        private float poseFromYaw;
        private float poseFromPitch;
        private float poseFromRoll;
        private float poseFromFov;
        private long poseChanged = 0;

        ChannelView(RemoteViewConfigMessage config) {
            this.config = config;
            this.world = new RemoteViewWorld(config.minSectionY(), config.sectionCount());
            expectedSections = expectedSections(config);
            poseFromPos = config.cameraPos();
            poseFromYaw = config.yaw();
            poseFromPitch = config.pitch();
            poseFromRoll = config.roll();
            poseFromFov = config.fov();
        }

        void updateConfig(RemoteViewConfigMessage config) {
            // Freeze the current interpolated pose as the new starting point, then glide to the new one.
            var now = System.nanoTime();
            poseFromPos = posePosition(now);
            poseFromYaw = poseYaw(now);
            poseFromPitch = posePitch(now);
            poseFromRoll = poseRoll(now);
            poseFromFov = poseFov(now);
            poseChanged = now;

            this.config = config;
            expectedSections = expectedSections(config);
            if (puppet != null) puppet.recentre(config);
        }

        private float poseProgress(long now) {
            return poseChanged == 0 ? 1 : Mth.clamp((now - poseChanged) / (float) POSE_LERP_NANOS, 0, 1);
        }

        /**
         * The camera's structure-local eye position, when it rides a physics structure whose pose is currently
         * synced to this client. Transforming the camera's structure-local pose through the structure's own
         * client-side (interpolated) pose every frame glues the view to the structure exactly as Sable draws it -
         * the server-streamed world pose runs on a different timeline (server ticks vs snapshot interpolation)
         * and would lag or lead a fast mover.
         *
         * @return The structure-local eye position, or {@code null} when not applicable.
         */
        private @Nullable Vec3 clientStructureLocal() {
            var local = config.localPos().orElse(null);
            if (local == null) return null;
            var level = Minecraft.getInstance().level;
            return level != null && dan200.computercraft.shared.camera.SableSupport.poseAt(level, local) != null ? local : null;
        }

        /**
         * The view's eye position, interpolated between the last two pose updates.
         *
         * @return The current eye position.
         */
        public Vec3 posePosition() {
            var local = clientStructureLocal();
            if (local != null) {
                return dan200.computercraft.shared.camera.SableSupport.toWorldPosition(Minecraft.getInstance().level, local);
            }
            return posePosition(System.nanoTime());
        }

        private Vec3 posePosition(long now) {
            return poseFromPos.lerp(config.cameraPos(), poseProgress(now));
        }

        /**
         * The view's yaw, interpolated between the last two pose updates.
         *
         * @return The current yaw, in degrees.
         */
        public float poseYaw() {
            var local = clientStructureLocal();
            if (local != null) {
                return dan200.computercraft.shared.camera.SableSupport.toWorldYaw(Minecraft.getInstance().level, local, config.localYaw(), config.localPitch());
            }
            return poseYaw(System.nanoTime());
        }

        private float poseYaw(long now) {
            return poseFromYaw + Mth.wrapDegrees(config.yaw() - poseFromYaw) * poseProgress(now);
        }

        /**
         * The view's pitch, interpolated between the last two pose updates.
         *
         * @return The current pitch, in degrees.
         */
        public float posePitch() {
            var local = clientStructureLocal();
            if (local != null) {
                return dan200.computercraft.shared.camera.SableSupport.toWorldPitch(Minecraft.getInstance().level, local, config.localYaw(), config.localPitch());
            }
            return posePitch(System.nanoTime());
        }

        private float posePitch(long now) {
            return Mth.lerp(poseProgress(now), poseFromPitch, config.pitch());
        }

        /**
         * The view's roll, interpolated between the last two pose updates.
         *
         * @return The current roll, in degrees.
         */
        public float poseRoll() {
            var local = clientStructureLocal();
            if (local != null) {
                return dan200.computercraft.shared.camera.SableSupport.toWorldRoll(Minecraft.getInstance().level, local, config.localYaw(), config.localPitch());
            }
            return poseRoll(System.nanoTime());
        }

        private float poseRoll(long now) {
            return poseFromRoll + Mth.wrapDegrees(config.roll() - poseFromRoll) * poseProgress(now);
        }

        /**
         * The view's field of view, interpolated between the last two pose updates.
         *
         * @return The current field of view, in degrees.
         */
        public float poseFov() {
            return poseFov(System.nanoTime());
        }

        private float poseFov(long now) {
            return Mth.lerp(poseProgress(now), poseFromFov, config.fov());
        }

        /**
         * Whether this view looks into a dimension other than the viewer's, and so renders through a puppet level.
         *
         * @return Whether this is a cross-dimension view.
         */
        boolean isRemoteDimension() {
            var level = Minecraft.getInstance().level;
            return level != null && !level.dimension().location().equals(config.dimension());
        }

        /**
         * Get (creating on demand) the dedicated renderer of a same-dimension view. The player's own renderer
         * cannot be shared: its section grid can only be anchored to one viewpoint at a time.
         *
         * @param level The viewer's current level.
         * @return The view's dedicated same-dimension renderer.
         */
        LocalRenderer local(net.minecraft.client.multiplayer.ClientLevel level) {
            if (local != null && local.level() != level) {
                local.close();
                local = null;
            }
            if (local == null) local = LocalRenderer.create(level, config.cameraPos());
            return local;
        }

        /**
         * Get (creating on demand) the puppet level of a cross-dimension view.
         *
         * @return The view's puppet level, or {@code null} for same-dimension views (or if creation failed).
         */
        @Nullable
        PuppetLevel puppet() {
            if (puppet == null && !puppetBroken && isRemoteDimension()) {
                puppet = PuppetLevel.create(config);
                if (puppet == null) {
                    puppetBroken = true;
                } else {
                    puppet.recentre(config);
                }
            }
            return puppet;
        }

        private static int expectedSections(RemoteViewConfigMessage config) {
            var columns = config.streamRadius() * 2 + 1;
            return columns * columns * config.sectionCount();
        }

        void markDirty() {
            dirty = true;
            revision++;
        }

        boolean pollDirty() {
            var was = dirty;
            dirty = false;
            return was;
        }

        void sectionReceived(long pos) {
            receivedSections.add(pos);
        }

        /**
         * Get a counter which increases every time streamed content changes.
         *
         * @return The current revision.
         */
        public int revision() {
            return revision;
        }

        /**
         * Whether every section the server streams for this view has arrived at least once.
         *
         * @return Whether the picture is complete.
         */
        public boolean coverageComplete() {
            return receivedSections.size() >= expectedSections;
        }

        public RemoteViewConfigMessage config() {
            return config;
        }

        /**
         * Get (creating on demand) the renderer for this view.
         *
         * @return This view's renderer.
         */
        public RemoteViewRenderer renderer() {
            var renderer = this.renderer;
            if (renderer == null) renderer = this.renderer = new RemoteViewRenderer(this);
            return renderer;
        }

        void close() {
            if (renderer != null) {
                renderer.close();
                renderer = null;
            }
            if (puppet != null) {
                puppet.close();
                puppet = null;
            }
            if (local != null) {
                local.close();
                local = null;
            }
        }
    }

    /**
     * Mirror a section dirty-mark from the player's level renderer to every same-dimension view's dedicated
     * renderer, so block and light changes recompile in camera views too.
     *
     * @param x         The section's x coordinate.
     * @param y         The section's y coordinate.
     * @param z         The section's z coordinate.
     * @param important Whether the section should recompile synchronously.
     */
    public static void forwardSectionDirty(int x, int y, int z, boolean important) {
        for (var view : views.values()) {
            if (view.local != null) view.local.setSectionDirty(x, y, z, important);
        }
    }

    /**
     * A snapshot the server has asked this client to render and upload.
     */
    private static final class CaptureJob {
        final long requestId;
        final int channel;
        final int width;
        final int height;

        int ticks;
        int coverageRevision = -1;

        CaptureJob(long requestId, int channel, int width, int height) {
            this.requestId = requestId;
            this.channel = channel;
            this.width = width;
            this.height = height;
        }
    }
}
