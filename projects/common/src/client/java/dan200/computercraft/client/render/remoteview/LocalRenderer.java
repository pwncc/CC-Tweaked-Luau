// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.render.remoteview;

import dan200.computercraft.mixin.client.LevelRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A dedicated {@link LevelRenderer} for a <em>same-dimension</em> camera view.
 * <p>
 * The player's own level renderer cannot be shared with a camera: its section grid and occlusion graph are
 * anchored to a single viewpoint, so rendering it from the camera's position each frame and the player's position
 * the next makes the two passes endlessly re-anchor and recompile sections - terrain flickers hard in both views
 * once the camera is far from the player. This renderer draws the player's own {@link ClientLevel}, but keeps its
 * own section grid pinned to the camera, just as {@link PuppetLevel} does for other dimensions.
 * <p>
 * Block and light updates flow to the player's renderer only, so {@link RemoteViewCache#forwardSectionDirty}
 * mirrors those dirty marks here.
 */
final class LocalRenderer {
    private static final Logger LOG = LoggerFactory.getLogger(LocalRenderer.class);

    private final ClientLevel level;
    private final LevelRenderer renderer;
    private final RenderBuffers renderBuffers;

    private LocalRenderer(ClientLevel level, LevelRenderer renderer, RenderBuffers renderBuffers) {
        this.level = level;
        this.renderer = renderer;
        this.renderBuffers = renderBuffers;
    }

    /**
     * Build a dedicated renderer over the viewer's own level.
     *
     * @param level     The viewer's level.
     * @param cameraPos The camera's position, anchoring the section grid.
     * @return The new renderer.
     */
    static LocalRenderer create(ClientLevel level, Vec3 cameraPos) {
        var minecraft = Minecraft.getInstance();
        // Our own RenderBuffers: section meshing runs on background threads, and the main renderer's builders
        // are not safe to share.
        var renderBuffers = new RenderBuffers(1);
        var renderer = new LevelRenderer(minecraft, minecraft.getEntityRenderDispatcher(), minecraft.getBlockEntityRenderDispatcher(), renderBuffers);
        renderer.setLevel(level);
        // setLevel repoints the *shared* entity render dispatcher; it is already on this level, but keep the
        // invariant explicit in case the call order ever changes.
        minecraft.getEntityRenderDispatcher().setLevel(level);
        ((RemoteViewRenderOverride) renderer).computercraft$setCameraOverride(cameraPos);
        LOG.info("[camera] Created dedicated same-dimension renderer for {}", level.dimension().location());
        return new LocalRenderer(level, renderer, renderBuffers);
    }

    LevelRenderer renderer() {
        return renderer;
    }

    ClientLevel level() {
        return level;
    }

    /**
     * Re-anchor the section grid on the camera's (possibly moving) position.
     *
     * @param cameraPos The camera's current position.
     */
    void moveTo(Vec3 cameraPos) {
        ((RemoteViewRenderOverride) renderer).computercraft$setCameraOverride(cameraPos);
    }

    /**
     * Mirror a dirty mark from the player's renderer, so block and light changes recompile here too.
     *
     * @param x         The section's x coordinate.
     * @param y         The section's y coordinate.
     * @param z         The section's z coordinate.
     * @param important Whether the section should recompile synchronously.
     */
    void setSectionDirty(int x, int y, int z, boolean important) {
        ((LevelRendererAccessor) renderer).computercraft$setSectionDirty(x, y, z, important);
    }

    void close() {
        var minecraft = Minecraft.getInstance();
        renderer.setLevel(null);
        minecraft.getEntityRenderDispatcher().setLevel(minecraft.level);
        renderBuffers.fixedBufferPack().discardAll();
        LOG.info("[camera] Closed dedicated same-dimension renderer");
    }
}
