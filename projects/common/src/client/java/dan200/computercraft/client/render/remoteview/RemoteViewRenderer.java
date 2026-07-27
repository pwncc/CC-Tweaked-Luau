// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.render.remoteview;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.client.FrameInfo;
import dan200.computercraft.mixin.client.GameRendererAccessor;
import dan200.computercraft.mixin.client.LevelRendererAccessor;
import dan200.computercraft.mixin.client.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.LightLayer;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renders a {@link RemoteViewCache.ChannelView} into an off-screen target: streamed sections are meshed with the
 * vanilla block renderer on a background thread (the same contract as vanilla's own section compiler), uploaded on
 * the render thread, then drawn from the camera's viewpoint. The result is exposed as an ordinary registered
 * texture, so screen renderers can draw it with a plain textured quad.
 */
public final class RemoteViewRenderer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteViewRenderer.class);

    /** The resolution views are rendered at: the mod-wide 720p ceiling. */
    public static final int VIEW_WIDTH = 1280;
    public static final int VIEW_HEIGHT = 720;

    private static final ExecutorService BAKE_POOL = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "ComputerCraft-RemoteViewBaker");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicInteger TEXTURE_IDS = new AtomicInteger();

    /** How often (in frames) to redraw the view, and to consider rebaking the mesh. */
    private static final int REDRAW_INTERVAL = 2;
    private static final int REBAKE_INTERVAL = 4;

    private static boolean broken = false;

    private final RemoteViewCache.ChannelView view;
    private final ResourceLocation textureId;

    private @Nullable RenderTarget target;
    private @Nullable TargetTexture targetTexture;
    private final Map<RenderType, VertexBuffer> buffers = new HashMap<>();

    private @Nullable CompletableFuture<Bake> pendingBake;
    private long lastDraw = -REDRAW_INTERVAL;
    private long lastBake = -REBAKE_INTERVAL;
    private int pendingBakeRevision;
    private int bakedRevision;

    /**
     * A finished off-thread bake: the built meshes, plus the memory pools backing them. The pools must stay alive
     * until the meshes have been uploaded (or discarded), and be freed after.
     *
     * @param meshes The built mesh for each render type.
     * @param memory The memory pools backing those meshes.
     */
    private record Bake(Map<RenderType, MeshData> meshes, List<ByteBufferBuilder> memory) {
        void discard() {
            for (var mesh : meshes.values()) mesh.close();
            free();
        }

        void free() {
            for (var buffer : memory) buffer.close();
        }
    }

    RemoteViewRenderer(RemoteViewCache.ChannelView view) {
        this.view = view;
        this.textureId = ResourceLocation.fromNamespaceAndPath(ComputerCraftAPI.MOD_ID, "remote_view/" + TEXTURE_IDS.getAndIncrement());
    }

    /**
     * Draw a "NO SIGNAL" label over a screen's dark panel, in the y-down screen space both monitor and billboard
     * renderers use. Shown when a screen is tuned but nothing reaches it - usually a missing modem link.
     *
     * @param transform    The current pose, in the screen's flipped (y-down) space.
     * @param bufferSource The active buffer source.
     * @param x            The panel's left edge.
     * @param y            The panel's top edge.
     * @param width        The panel's width.
     * @param height       The panel's height.
     */
    public static void drawNoSignal(PoseStack transform, net.minecraft.client.renderer.MultiBufferSource bufferSource, float x, float y, float width, float height) {
        var label = "NO SIGNAL";
        var pixels = label.length() * dan200.computercraft.client.render.text.FixedWidthFontRenderer.FONT_WIDTH;
        var scale = width * 0.6f / pixels;
        transform.pushPose();
        transform.translate(x + width / 2f, y + height / 2f, -0.002f);
        transform.scale(scale, scale, 1);
        dan200.computercraft.client.render.text.FixedWidthFontRenderer.drawString(
            dan200.computercraft.client.render.text.FixedWidthFontRenderer.toVertexConsumer(transform, bufferSource.getBuffer(dan200.computercraft.client.render.RenderTypes.TERMINAL)),
            -pixels / 2f, -dan200.computercraft.client.render.text.FixedWidthFontRenderer.FONT_HEIGHT / 2f,
            new dan200.computercraft.core.terminal.TextBuffer(label), new dan200.computercraft.core.terminal.TextBuffer("000000000"),
            dan200.computercraft.core.terminal.Palette.DEFAULT, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT
        );
        transform.popPose();
    }

    /** Renderers whose screens were visible this frame, redrawn together at the start of the next frame. */
    private static final java.util.Set<RemoteViewRenderer> PENDING = new java.util.LinkedHashSet<>();

    private int requestWidth = VIEW_WIDTH;
    private int requestHeight = VIEW_HEIGHT;

    /**
     * Get the texture this view renders to, requesting a redraw at the start of the next frame. Must be called on
     * the render thread.
     * <p>
     * The actual off-screen rendering deliberately does <em>not</em> happen here: this is called mid-way through
     * the world render pass, and switching render targets there stalls the pipeline (and can corrupt the pass's
     * state). Instead visible views are {@linkplain #drainPending() redrawn together} before the frame starts.
     *
     * @param width  The preferred width of the render target.
     * @param height The preferred height of the render target.
     * @return The registered texture to draw, or {@code null} when rendering is unavailable (or not yet started).
     */
    @Nullable
    public ResourceLocation getTexture(int width, int height) {
        if (broken) return null;

        requestWidth = width;
        requestHeight = height;
        PENDING.add(this);
        return target == null ? null : textureId;
    }

    /**
     * Redraw every view that was visible last frame. Called at the start of each frame, before the world renders.
     */
    public static void drainPending() {
        if (PENDING.isEmpty()) return;
        for (var renderer : PENDING) renderer.redraw();
        PENDING.clear();
    }

    private void redraw() {
        if (broken) return;

        try {
            var frame = FrameInfo.getRenderFrame();

            var target = ensureTarget(requestWidth, requestHeight);
            if (frame - lastDraw >= REDRAW_INTERVAL) {
                lastDraw = frame;
                // The full-fidelity path renders the viewer's own world through the vanilla renderer; the
                // meshed diorama is the fallback (cross-dimension views, fabulous graphics).
                if (!drawLive(target)) {
                    maybeRebake(frame);
                    draw(target);
                }
            } else {
                maybeRebake(frame);
            }
        } catch (Exception e) {
            // Off-screen rendering can fall foul of aggressive rendering mods; fail once, loudly, then disable.
            LOG.error("Remote view rendering failed; disabling camera views for this session", e);
            broken = true;
        }
    }

    private static @Nullable ViewCamera liveCamera;
    private static Display.@Nullable BlockDisplay liveCameraEntity;
    private boolean liveLogged;
    private boolean fallbackLogged;

    private boolean computercraft$fallback(String reason) {
        if (!fallbackLogged) {
            fallbackLogged = true;
            LOG.info("[camera] Using the streamed diorama for channel {}: {}", view.config().channel(), reason);
        }
        return false;
    }

    /**
     * Render the view through the vanilla level renderer: the camera's surroundings are synced into the viewer's
     * own world (see {@code CameraViewZones}), so the real renderer can draw them - entities, block entities,
     * particles, sky and all.
     *
     * @param target The render target to draw into.
     * @return Whether the live path was usable; when false, the caller falls back to the meshed diorama.
     */
    private boolean drawLive(RenderTarget target) {
        var minecraft = Minecraft.getInstance();
        var config = view.config();
        if (minecraft.level == null || minecraft.player == null) return false;
        // Fabulous graphics routes render types through deferred targets we don't manage yet.
        if (Minecraft.useShaderTransparency()) return computercraft$fallback("fabulous graphics is enabled");

        // Same-dimension views render the viewer's own world (the camera's surroundings are chunk-synced into
        // it); cross-dimension views render their streamed puppet level with its own dedicated renderer.
        ClientLevel level;
        LevelRenderer levelRenderer;
        PuppetLevel puppet = null;
        if (minecraft.level.dimension().location().equals(config.dimension())) {
            level = minecraft.level;
            levelRenderer = minecraft.levelRenderer;
        } else {
            puppet = view.puppet();
            if (puppet == null || !puppet.ready()) {
                return computercraft$fallback("the puppet level for " + config.dimension() + " has no data yet");
            }
            level = puppet.level();
            levelRenderer = puppet.renderer();
        }

        var gameRenderer = minecraft.gameRenderer;
        var partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);

        var camera = liveCamera;
        if (camera == null) camera = liveCamera = new ViewCamera();

        // The camera's entity is a dummy pinned at the camera block: using the player would make the level
        // renderer treat the view as their first-person perspective (skipping their body, applying their fog
        // effects, and so on).
        var cameraEntity = liveCameraEntity;
        if (cameraEntity == null || cameraEntity.level() != level) {
            cameraEntity = liveCameraEntity = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        }
        // The pose interpolates between the last two server updates, so moving cameras (turtles, physics
        // structures) glide instead of snapping.
        var position = view.posePosition();
        var yaw = view.poseYaw();
        var pitch = view.posePitch();
        cameraEntity.setPos(position);
        cameraEntity.setYRot(yaw);
        cameraEntity.setXRot(pitch);

        camera.setup(level, cameraEntity, false, false, partialTick);
        camera.moveTo(position, yaw, pitch);

        if (!liveLogged) {
            liveLogged = true;
            LOG.info("[camera] Live (vanilla) rendering active for channel {}", config.channel());
        }

        var projection = new Matrix4f().perspective(
            view.poseFov() * Mth.DEG_TO_RAD, (float) target.width / target.height,
            0.05f, gameRenderer.getDepthFar()
        );
        // Roll is applied on top of the yaw/pitch camera: cameras on banked physics structures tilt the horizon.
        var modelView = new Matrix4f()
            .rotateZ(view.poseRoll() * Mth.DEG_TO_RAD)
            .rotate(camera.rotation().conjugate(new org.joml.Quaternionf()));

        var oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var oldSort = RenderSystem.getVertexSorting();
        var mainTarget = minecraft.getMainRenderTarget();
        var mainCamera = gameRenderer.getMainCamera();
        var access = (MinecraftAccessor) minecraft;
        var modelViewStack = RenderSystem.getModelViewStack();

        var levelRendererAccess = (LevelRendererAccessor) levelRenderer;
        var entityOutlineTarget = levelRendererAccess.computercraft$getEntityTarget();
        var mainLevel = minecraft.level;

        try {
            // Several render types bind the "main" target mid-pass, so ours must be it for the duration - and
            // parts of the pipeline query the "main" camera directly, so that must be ours too.
            access.computercraft$setMainRenderTarget(target);
            ((GameRendererAccessor) gameRenderer).computercraft$setMainCamera(camera);
            // Disable the glow-outline pipeline: clearing its window-sized target mid-pass resets the viewport
            // to the window's dimensions, skewing and clipping everything drawn after the terrain.
            levelRendererAccess.computercraft$setEntityTarget(null);

            if (puppet != null) {
                // The pipeline reads "the" level off the Minecraft instance in several places, and particles
                // render from the global engine; both must be the puppet's for the duration.
                minecraft.level = level;
                puppet.installParticles();
                minecraft.getEntityRenderDispatcher().prepare(level, camera, null);
                minecraft.getBlockEntityRenderDispatcher().prepare(level, camera, minecraft.hitResult);
            }

            // renderLevel composes its matrices onto the global model-view stack, and entities/particles draw
            // through it. Vanilla calls with an identity base; force the same here, or everything except terrain
            // (which gets its matrices passed explicitly) renders skewed by whatever is on the stack.
            modelViewStack.pushMatrix();
            modelViewStack.identity();
            RenderSystem.applyModelViewMatrix();

            FogRenderer.setupColor(camera, partialTick, level, minecraft.options.getEffectiveRenderDistance(), gameRenderer.getDarkenWorldAmount(partialTick));
            target.bindWrite(true);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);

            levelRenderer.prepareCullFrustum(camera.getPosition(), modelView, projection);
            levelRenderer.renderLevel(
                minecraft.getTimer(), false, camera, gameRenderer, gameRenderer.lightTexture(), modelView, projection
            );
        } finally {
            if (puppet != null) {
                minecraft.level = mainLevel;
                puppet.restoreParticles();
                minecraft.getEntityRenderDispatcher().prepare(mainLevel, mainCamera, null);
                minecraft.getEntityRenderDispatcher().setLevel(mainLevel);
                minecraft.getBlockEntityRenderDispatcher().prepare(mainLevel, mainCamera, minecraft.hitResult);
            }
            access.computercraft$setMainRenderTarget(mainTarget);
            ((GameRendererAccessor) gameRenderer).computercraft$setMainCamera(mainCamera);
            levelRendererAccess.computercraft$setEntityTarget(entityOutlineTarget);
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProjection, oldSort);
            mainTarget.bindWrite(true);
        }
        return true;
    }

    private RenderTarget ensureTarget(int width, int height) {
        var target = this.target;
        if (target != null && (target.width != width || target.height != height)) {
            close();
            target = null;
        }

        if (target == null) {
            target = this.target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            target.setClearColor(0f, 0f, 0f, 1f);
            targetTexture = new TargetTexture(target);
            Minecraft.getInstance().getTextureManager().register(textureId, targetTexture);
        }
        return target;
    }

    private void maybeRebake(long frame) {
        // Upload a finished bake.
        var pending = pendingBake;
        if (pending != null && pending.isDone()) {
            pendingBake = null;
            var bake = pending.join();
            for (var buffer : buffers.values()) buffer.close();
            buffers.clear();
            for (var entry : bake.meshes().entrySet()) {
                var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                buffer.upload(entry.getValue()); // Consumes (and closes) the mesh.
                buffers.put(entry.getKey(), buffer);
            }
            VertexBuffer.unbind();
            bake.free();
            bakedRevision = pendingBakeRevision;
        }

        if (pendingBake == null && frame - lastBake >= REBAKE_INTERVAL && view.pollDirty()) {
            lastBake = frame;
            pendingBakeRevision = view.revision();
            var snapshot = view.world.snapshotSections();
            var origin = BlockPos.containing(view.config().cameraPos());
            var world = view.world;
            pendingBake = CompletableFuture.supplyAsync(() -> bake(world, snapshot, origin), BAKE_POOL);
        }
    }

    /**
     * Get the {@linkplain RemoteViewCache.ChannelView#revision() view revision} the current mesh was built from,
     * or {@code 0} when nothing has been meshed yet.
     *
     * @return The meshed revision.
     */
    public int bakedRevision() {
        return bakedRevision;
    }

    /**
     * Render the view and read it back as an RGB332 frame. Must be called on the render thread.
     *
     * @param width  The width to downscale the frame to.
     * @param height The height to downscale the frame to.
     * @return One byte per pixel in RGB332 order, or {@code null} when rendering is unavailable.
     */
    public byte @Nullable [] capture(int width, int height) {
        if (broken) return null;

        // Captures run from the client tick, outside the world render pass, so drawing right here is safe.
        requestWidth = VIEW_WIDTH;
        requestHeight = VIEW_HEIGHT;
        redraw();

        var target = this.target;
        if (target == null) return null;

        try (var image = Screenshot.takeScreenshot(target)) {
            var sourceWidth = image.getWidth();
            var sourceHeight = image.getHeight();
            var out = new byte[width * height];
            var i = 0;
            for (var y = 0; y < height; y++) {
                var y0 = y * sourceHeight / height;
                var y1 = Math.max(y0 + 1, (y + 1) * sourceHeight / height);
                for (var x = 0; x < width; x++) {
                    var x0 = x * sourceWidth / width;
                    var x1 = Math.max(x0 + 1, (x + 1) * sourceWidth / width);

                    int r = 0, g = 0, b = 0, count = 0;
                    for (var sy = y0; sy < y1; sy++) {
                        for (var sx = x0; sx < x1; sx++) {
                            var pixel = image.getPixelRGBA(sx, sy);
                            r += pixel & 0xFF;
                            g += (pixel >> 8) & 0xFF;
                            b += (pixel >> 16) & 0xFF;
                            count++;
                        }
                    }
                    out[i++] = (byte) ((r / count >> 5 << 5) | (g / count >> 5 << 2) | (b / count >> 6));
                }
            }
            return out;
        } catch (Exception e) {
            LOG.error("Failed to read back camera frame", e);
            return null;
        }
    }

    /**
     * Mesh every streamed section, relative to the camera position. Runs off-thread; baked models and block colours
     * are safe to read concurrently (this is the same contract as vanilla's section compiler workers).
     *
     * @param world    The view's world, for state lookups during tesselation.
     * @param sections A snapshot of the sections to mesh.
     * @param origin   The camera position meshes are made relative to.
     * @return The built mesh for each render type.
     */
    private static Bake bake(RemoteViewWorld world, Map<Long, LevelChunkSection> sections, BlockPos origin) {
        var dispatcher = Minecraft.getInstance().getBlockRenderer();
        var random = RandomSource.create();
        var pose = new PoseStack();

        Map<RenderType, BufferBuilder> builders = new HashMap<>();
        Map<RenderType, ByteBufferBuilder> memory = new HashMap<>();

        var cursor = new BlockPos.MutableBlockPos();
        for (var entry : sections.entrySet()) {
            var sectionPos = SectionPos.of(entry.getKey());
            var section = entry.getValue();
            if (section.hasOnlyAir()) continue;

            var baseX = sectionPos.minBlockX();
            var baseY = sectionPos.minBlockY();
            var baseZ = sectionPos.minBlockZ();
            for (var y = 0; y < 16; y++) {
                for (var z = 0; z < 16; z++) {
                    for (var x = 0; x < 16; x++) {
                        var state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        cursor.set(baseX + x, baseY + y, baseZ + z);

                        FluidState fluid = state.getFluidState();
                        if (!fluid.isEmpty()) {
                            var type = ItemBlockRenderTypes.getRenderLayer(fluid);
                            var consumer = builders.computeIfAbsent(type, t -> newBuilder(memory, t));
                            dispatcher.renderLiquid(
                                cursor, world,
                                new OffsetVertexConsumer(consumer, (baseX & ~15) - origin.getX(), (baseY & ~15) - origin.getY(), (baseZ & ~15) - origin.getZ()),
                                state, fluid
                            );
                        }

                        if (state.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL) {
                            var type = ItemBlockRenderTypes.getChunkRenderType(state);
                            var consumer = builders.computeIfAbsent(type, t -> newBuilder(memory, t));
                            pose.pushPose();
                            pose.translate(cursor.getX() - origin.getX(), cursor.getY() - origin.getY(), cursor.getZ() - origin.getZ());
                            dispatcher.renderBatched(state, cursor, world, pose, consumer, true, random);
                            pose.popPose();
                        }
                    }
                }
            }
        }

        Map<RenderType, MeshData> meshes = new HashMap<>();
        for (var entry : builders.entrySet()) {
            var mesh = entry.getValue().build();
            if (mesh != null) meshes.put(entry.getKey(), mesh);
        }
        // The memory pools back the built meshes, so stay alive until the meshes are uploaded.
        return new Bake(meshes, List.copyOf(memory.values()));
    }

    private static BufferBuilder newBuilder(Map<RenderType, ByteBufferBuilder> memory, RenderType type) {
        var buffer = new ByteBufferBuilder(type.bufferSize());
        memory.put(type, buffer);
        return new BufferBuilder(buffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
    }

    private void draw(RenderTarget target) {
        var config = view.config();
        var minecraft = Minecraft.getInstance();
        var main = minecraft.getMainRenderTarget();

        // Sky colour by dimension family; the overworld follows the viewer's own sky (time of day, weather).
        var dimension = config.dimension().getPath();
        if (dimension.contains("nether")) {
            target.setClearColor(0.14f, 0.03f, 0.03f, 1f);
        } else if (dimension.contains("end")) {
            target.setClearColor(0.03f, 0.03f, 0.06f, 1f);
        } else if (minecraft.level != null) {
            var sky = minecraft.level.getSkyColor(minecraft.gameRenderer.getMainCamera().getPosition(), 1f);
            // Vanilla's night sky colour is pure black (stars are separate geometry we don't draw), so floor it
            // at a deep night blue to keep the horizon readable.
            target.setClearColor(Math.max((float) sky.x, 0.02f), Math.max((float) sky.y, 0.03f), Math.max((float) sky.z, 0.09f), 1f);
        } else {
            target.setClearColor(0.47f, 0.65f, 1.0f, 1f);
        }
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);

        // Projection + view matrices for the camera. The pose interpolates between the last two server updates,
        // so moving cameras glide here just as they do in the same-dimension path.
        var projection = new Matrix4f().perspective(
            view.poseFov() * Mth.DEG_TO_RAD,
            (float) target.width / target.height,
            0.05f, 16 * (config.streamRadius() + 1) * 2f
        );
        // The mesh is baked relative to the block the (configured) eye is in; shift by the interpolated eye's
        // offset within it. Roll comes first, tilting the horizon for cameras on banked physics structures.
        var eye = view.posePosition();
        var origin = BlockPos.containing(config.cameraPos());
        var modelView = new Matrix4f()
            .rotateZ(view.poseRoll() * Mth.DEG_TO_RAD)
            .rotateX(view.posePitch() * Mth.DEG_TO_RAD)
            .rotateY((view.poseYaw() + 180) * Mth.DEG_TO_RAD)
            .translate((float) -(eye.x - origin.getX()), (float) -(eye.y - origin.getY()), (float) -(eye.z - origin.getZ()));

        var oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var oldSort = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(projection, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
        // The block shaders modulate by the lightmap; without it bound the whole scene comes out black.
        var lightTexture = minecraft.gameRenderer.lightTexture();
        lightTexture.turnOnLightLayer();
        var fogStart = RenderSystem.getShaderFogStart();
        var fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        drawLayer(RenderType.solid(), modelView, projection);
        drawLayer(RenderType.cutoutMipped(), modelView, projection);
        drawLayer(RenderType.cutout(), modelView, projection);
        drawEntities(modelView);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        drawLayer(RenderType.translucent(), modelView, projection);
        RenderSystem.disableBlend();

        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        lightTexture.turnOffLightLayer();
        RenderSystem.setProjectionMatrix(oldProjection, oldSort);
        main.bindWrite(true);
    }

    /**
     * Draw the streamed entities of a cross-dimension view, using the real entity renderers.
     *
     * @param modelView The view matrix terrain is drawn with.
     */
    private void drawEntities(Matrix4f modelView) {
        if (view.entities.isEmpty()) return;

        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) return;

        var config = view.config();
        var origin = BlockPos.containing(config.cameraPos());

        // Pose a camera at the view's eye, so nametags and billboards face it.
        var camera = liveCamera;
        if (camera == null) camera = liveCamera = new ViewCamera();
        if (minecraft.player != null) camera.setup(level, minecraft.player, false, false, 1f);
        camera.moveTo(view.posePosition(), view.poseYaw(), view.posePitch());

        var dispatcher = minecraft.getEntityRenderDispatcher();
        // Shadows sample light from the viewer's own level, which is meaningless here.
        dispatcher.setRenderShadow(false);
        dispatcher.prepare(level, camera, null);

        // Entity geometry is drawn through the global model-view at batch time, so pin it to ours - and entity
        // render types bind "the main target" mid-batch, so that must be our target for the duration.
        var modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        modelViewStack.set(modelView);
        RenderSystem.applyModelViewMatrix();
        var mainTarget = minecraft.getMainRenderTarget();
        var access = (MinecraftAccessor) minecraft;
        minecraft.gameRenderer.overlayTexture().setupOverlayColor();

        try {
            if (target != null) access.computercraft$setMainRenderTarget(target);

            var buffers = minecraft.renderBuffers().bufferSource();
            var pose = new PoseStack();
            var blockPos = new BlockPos.MutableBlockPos();
            for (var remote : view.entities.list()) {
                var entity = remote.rendered(level, origin.getX(), origin.getY(), origin.getZ());
                if (entity == null) continue;

                blockPos.set(origin.getX() + remote.x, origin.getY() + remote.y, origin.getZ() + remote.z);
                var light = LightTexture.pack(
                    view.world.getBrightness(LightLayer.BLOCK, blockPos),
                    view.world.getBrightness(LightLayer.SKY, blockPos)
                );
                dispatcher.render(entity, remote.x, remote.y, remote.z, remote.yRot, 1f, pose, buffers, light);
            }
            buffers.endBatch();
        } finally {
            access.computercraft$setMainRenderTarget(mainTarget);
            minecraft.gameRenderer.overlayTexture().teardownOverlayColor();
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            dispatcher.setRenderShadow(true);
            // The entity batch rebinds textures and may rebind the target; restore both for the layers after us.
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            if (target != null) target.bindWrite(false);
        }
    }

    private void drawLayer(RenderType type, Matrix4f modelView, Matrix4f projection) {
        var buffer = buffers.get(type);
        if (buffer == null) return;

        var shader = GameRenderer.getRendertypeSolidShader();
        if (type == RenderType.cutout()) {
            shader = GameRenderer.getRendertypeCutoutShader();
        } else if (type == RenderType.cutoutMipped()) {
            shader = GameRenderer.getRendertypeCutoutMippedShader();
        } else if (type == RenderType.translucent()) {
            shader = GameRenderer.getRendertypeTranslucentShader();
        }
        if (shader == null) return;

        buffer.bind();
        buffer.drawWithShader(modelView, projection, shader);
        VertexBuffer.unbind();
    }

    @Override
    public void close() {
        PENDING.remove(this);
        for (var buffer : buffers.values()) buffer.close();
        buffers.clear();
        if (targetTexture != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            targetTexture = null;
        }
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
        if (pendingBake != null) {
            // The bake cannot be interrupted, so let it finish and free its buffers when it lands.
            pendingBake.whenComplete((bake, error) -> {
                if (bake != null) bake.discard();
            });
            pendingBake = null;
        }
    }

    /**
     * Wraps a render target's colour buffer as a registered texture, so world renderers can use it through the
     * ordinary texture manager.
     */
    private static final class TargetTexture extends AbstractTexture {
        private final RenderTarget target;

        TargetTexture(RenderTarget target) {
            this.target = target;
        }

        @Override
        public int getId() {
            return target.getColorTextureId();
        }

        @Override
        public void releaseId() {
            // The render target owns the texture; nothing to release here.
        }

        @Override
        public void load(ResourceManager manager) {
        }
    }
}
