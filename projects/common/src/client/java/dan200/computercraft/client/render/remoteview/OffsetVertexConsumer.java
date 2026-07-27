// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.render.remoteview;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Offsets vertex positions by a fixed amount. The vanilla fluid renderer emits vertices in section-local
 * coordinates, so meshing shifts them into the view's camera-relative space with this.
 */
final class OffsetVertexConsumer implements VertexConsumer {
    private final VertexConsumer parent;
    private final float dx, dy, dz;

    OffsetVertexConsumer(VertexConsumer parent, float dx, float dy, float dz) {
        this.parent = parent;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        parent.addVertex(x + dx, y + dy, z + dz);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        parent.setColor(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        parent.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        parent.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        parent.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        parent.setNormal(x, y, z);
        return this;
    }
}
