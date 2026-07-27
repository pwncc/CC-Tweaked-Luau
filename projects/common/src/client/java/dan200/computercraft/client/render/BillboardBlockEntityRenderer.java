// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dan200.computercraft.client.render.remoteview.RemoteViewCache;
import dan200.computercraft.client.render.text.FixedWidthFontRenderer;
import dan200.computercraft.shared.computer.blocks.BillboardBlockEntity;
import dan200.computercraft.shared.computer.blocks.ComputerBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

import static dan200.computercraft.client.render.text.FixedWidthFontRenderer.FONT_HEIGHT;
import static dan200.computercraft.client.render.text.FixedWidthFontRenderer.FONT_WIDTH;

/**
 * Renders a billboard's terminal on the front face of the block.
 * <p>
 * This uses the same maths as {@code MonitorBlockEntityRenderer} to position the terminal on the block's front face
 * (though horizontal rotation only), but draws with the simple immediate-mode path, as billboard terminals are small.
 */
public class BillboardBlockEntityRenderer implements BlockEntityRenderer<BillboardBlockEntity> {
    /**
     * The screen inset on the billboard's front texture: 14x10 texture pixels, centred on the face. The terminal
     * (including its margin) is scaled to fit entirely inside this region, preserving aspect ratio.
     */
    private static final float SCREEN_WIDTH = 14.0f / 16.0f;
    private static final float SCREEN_HEIGHT = 10.0f / 16.0f;

    /**
     * The margin drawn around the terminal contents, in world units. Unlike the terminal itself this is background
     * colour only, but it must still fit within the screen inset.
     */
    private static final float MARGIN = 0.25f / 16.0f;

    /**
     * The offset from the centre of the block to the front face, pushed out slightly to avoid z-fighting with the
     * block model.
     */
    private static final float FACE_OFFSET = 0.5f + 0.002f;

    public BillboardBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    /**
     * The resolution of live camera views. This is the "720p" ceiling: the remote view is rendered off-screen at
     * this size and scaled onto the billboard's face.
     */
    private static final int VIEW_WIDTH = 1280, VIEW_HEIGHT = 720;

    @Override
    public void render(BillboardBlockEntity billboard, float partialTicks, PoseStack transform, MultiBufferSource bufferSource, int lightmapCoord, int overlayLight) {
        if (renderPixelBuffer(billboard, transform, bufferSource)) return;
        if (renderRemoteView(billboard, transform, bufferSource)) return;

        var display = billboard.getClientDisplay();
        var terminal = display == null ? null : display.getTerminal();
        if (terminal == null) return;

        int pixelWidth = terminal.getWidth() * FONT_WIDTH, pixelHeight = terminal.getHeight() * FONT_HEIGHT;
        var scale = Math.min(
            (SCREEN_WIDTH - 2 * MARGIN) / pixelWidth,
            (SCREEN_HEIGHT - 2 * MARGIN) / pixelHeight
        );

        transform.pushPose();

        // Move to the centre of the block, rotate towards the front face, then move to the top-left corner of the
        // (centred) terminal on that face. The negative y scale flips the terminal's y-down coordinates to Minecraft's
        // y-up ones, as in MonitorBlockEntityRenderer.
        transform.translate(0.5f, 0.5f, 0.5f);
        transform.mulPose(Axis.YN.rotationDegrees(billboard.getBlockState().getValue(ComputerBlock.FACING).toYRot()));
        transform.translate(-0.5f * pixelWidth * scale, 0.5f * pixelHeight * scale, FACE_OFFSET);
        transform.scale(scale, -scale, scale);

        var margin = MARGIN / scale;
        // Draw the background and text as two passes with a real world-space gap between them: drawTerminal's own
        // z-nudge is in local space, and at this tiny scale it collapses into z-fighting.
        FixedWidthFontRenderer.drawTerminalBackground(
            FixedWidthFontRenderer.toVertexConsumer(transform, bufferSource.getBuffer(RenderTypes.TERMINAL)),
            0, 0, terminal, margin, margin, margin, margin
        );

        transform.translate(0, 0, 0.001f / scale);
        var foreground = FixedWidthFontRenderer.toVertexConsumer(transform, bufferSource.getBuffer(RenderTypes.TERMINAL));
        FixedWidthFontRenderer.drawTerminalForeground(foreground, 0, 0, terminal);
        FixedWidthFontRenderer.drawCursor(foreground, 0, 0, terminal);

        transform.popPose();
    }

    /**
     * Draw this billboard's Lua-owned pixel buffer, if it is in graphics mode.
     *
     * @param billboard    The billboard being rendered.
     * @param transform    The active pose stack.
     * @param bufferSource The active buffer source.
     * @return Whether a pixel buffer was drawn: when true, nothing else is drawn.
     */
    private boolean renderPixelBuffer(BillboardBlockEntity billboard, PoseStack transform, MultiBufferSource bufferSource) {
        var texture = PixelDisplays.get(billboard.getBlockPos());
        if (texture == null) return false;

        transform.pushPose();
        transform.translate(0.5f, 0.5f, 0.5f);
        transform.mulPose(Axis.YN.rotationDegrees(billboard.getBlockState().getValue(ComputerBlock.FACING).toYRot()));
        transform.translate(0, 0, FACE_OFFSET);
        transform.scale(1, -1, 1);

        var pose = transform.last().pose();
        var buffer = bufferSource.getBuffer(RenderType.text(texture));
        var left = -SCREEN_WIDTH / 2;
        var top = -SCREEN_HEIGHT / 2;
        var right = SCREEN_WIDTH / 2;
        var bottom = SCREEN_HEIGHT / 2;
        buffer.addVertex(pose, left, top, 0).setColor(-1).setUv(0, 1).setLight(LightTexture.FULL_BRIGHT);
        buffer.addVertex(pose, left, bottom, 0).setColor(-1).setUv(0, 0).setLight(LightTexture.FULL_BRIGHT);
        buffer.addVertex(pose, right, bottom, 0).setColor(-1).setUv(1, 0).setLight(LightTexture.FULL_BRIGHT);
        buffer.addVertex(pose, right, top, 0).setColor(-1).setUv(1, 1).setLight(LightTexture.FULL_BRIGHT);

        transform.popPose();
        return true;
    }

    /**
     * Draw the live camera view this billboard is tuned to, if any.
     *
     * @param billboard    The billboard being rendered.
     * @param transform    The active pose stack.
     * @param bufferSource The active buffer source.
     * @return Whether a remote view was drawn (or is pending): when true, the terminal is not drawn.
     */
    private boolean renderRemoteView(BillboardBlockEntity billboard, PoseStack transform, MultiBufferSource bufferSource) {
        var channel = billboard.getViewChannel();
        if (channel == BillboardBlockEntity.NO_CHANNEL) return false;

        var cameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        RemoteViewCache.requestView(channel, cameraPos.distanceToSqr(billboard.getBlockPos().getCenter()), billboard.getBlockPos());

        transform.pushPose();
        transform.translate(0.5f, 0.5f, 0.5f);
        transform.mulPose(Axis.YN.rotationDegrees(billboard.getBlockState().getValue(ComputerBlock.FACING).toYRot()));
        // Move onto the front face, and flip into the same y-down frame the terminal path uses: the font
        // renderer's quads (and ours, which copy their winding) only face outwards under that flip.
        transform.translate(0, 0, FACE_OFFSET);
        transform.scale(1, -1, 1);

        var view = RemoteViewCache.getView(channel);
        var texture = view == null ? null : view.renderer().getTexture(VIEW_WIDTH, VIEW_HEIGHT);
        var left = -SCREEN_WIDTH / 2;
        var top = -SCREEN_HEIGHT / 2;

        if (texture == null) {
            // Tuned, but no picture yet: a dark "no signal" panel.
            var buffer = bufferSource.getBuffer(RenderTypes.TERMINAL);
            FixedWidthFontRenderer.drawEmptyTerminal(
                FixedWidthFontRenderer.toVertexConsumer(transform, buffer),
                left, top, SCREEN_WIDTH, SCREEN_HEIGHT
            );
            dan200.computercraft.client.render.remoteview.RemoteViewRenderer.drawNoSignal(transform, bufferSource, left, top, SCREEN_WIDTH, SCREEN_HEIGHT);
        } else {
            var pose = transform.last().pose();
            var buffer = bufferSource.getBuffer(RenderType.text(texture));
            var right = SCREEN_WIDTH / 2;
            var bottom = SCREEN_HEIGHT / 2;
            // Top-left, bottom-left, bottom-right, top-right, as the font renderer emits. Render targets store
            // their image bottom-up, so the top of the screen samples v=1.
            buffer.addVertex(pose, left, top, 0).setColor(-1).setUv(0, 1).setLight(LightTexture.FULL_BRIGHT);
            buffer.addVertex(pose, left, bottom, 0).setColor(-1).setUv(0, 0).setLight(LightTexture.FULL_BRIGHT);
            buffer.addVertex(pose, right, bottom, 0).setColor(-1).setUv(1, 0).setLight(LightTexture.FULL_BRIGHT);
            buffer.addVertex(pose, right, top, 0).setColor(-1).setUv(1, 1).setLight(LightTexture.FULL_BRIGHT);
        }

        transform.popPose();
        return true;
    }
}
