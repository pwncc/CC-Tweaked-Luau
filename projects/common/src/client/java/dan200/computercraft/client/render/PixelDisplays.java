// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import dan200.computercraft.api.ComputerCraftAPI;
import dan200.computercraft.shared.network.client.PixelDisplayMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side storage for screens' {@linkplain dan200.computercraft.shared.display.PixelBuffer pixel buffers}: the
 * received pixels are kept in GPU textures keyed by the screen's position, which block entity renderers draw as
 * plain textured quads.
 */
public final class PixelDisplays {
    private static final AtomicInteger TEXTURE_IDS = new AtomicInteger();

    private static final Map<BlockPos, Entry> displays = new HashMap<>();

    private PixelDisplays() {
    }

    /**
     * Handle a pixel buffer update from the server.
     *
     * @param message The received update.
     */
    public static void handle(PixelDisplayMessage message) {
        var pos = message.pos();
        if (message.width() <= 0 || message.height() <= 0) {
            remove(pos);
            return;
        }

        var entry = displays.get(pos);
        if (entry != null && (entry.image.getWidth() != message.width() || entry.image.getHeight() != message.height())) {
            remove(pos);
            entry = null;
        }

        if (entry == null) {
            var image = new NativeImage(message.width(), message.height(), false);
            var texture = new DynamicTexture(image);
            var id = ResourceLocation.fromNamespaceAndPath(ComputerCraftAPI.MOD_ID, "pixel_display/" + TEXTURE_IDS.getAndIncrement());
            Minecraft.getInstance().getTextureManager().register(id, texture);
            entry = new Entry(image, texture, id);
            displays.put(pos.immutable(), entry);
        }

        var data = message.data();
        var image = entry.image;
        var width = message.width();
        var expected = width * message.height();
        if (data.length < expected) return;

        // Written bottom-up, so the renderer can use the same (render-target style) texture coordinates for
        // pixel buffers and camera views alike.
        var height = message.height();
        for (var y = 0; y < height; y++) {
            var destY = height - 1 - y;
            for (var x = 0; x < width; x++) {
                var pixel = data[y * width + x] & 0xFF;
                var r = (pixel >> 5) * 255 / 7;
                var g = (pixel >> 2 & 7) * 255 / 7;
                var b = (pixel & 3) * 255 / 3;
                image.setPixelRGBA(x, destY, 0xFF000000 | b << 16 | g << 8 | r);
            }
        }
        entry.texture.upload();
    }

    /**
     * Get the texture for a screen's pixel buffer, if it has one.
     *
     * @param pos The screen's position.
     * @return The registered texture, or {@code null} when the screen is not in graphics mode.
     */
    public static @Nullable ResourceLocation get(BlockPos pos) {
        var entry = displays.get(pos);
        return entry == null ? null : entry.id;
    }

    private static void remove(BlockPos pos) {
        var entry = displays.remove(pos);
        if (entry != null) Minecraft.getInstance().getTextureManager().release(entry.id);
    }

    /** Drop all displays, e.g. when leaving a world. */
    public static void clear() {
        for (var entry : displays.values()) Minecraft.getInstance().getTextureManager().release(entry.id);
        displays.clear();
    }

    private record Entry(NativeImage image, DynamicTexture texture, ResourceLocation id) {
    }
}
