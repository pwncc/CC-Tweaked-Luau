// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.display;

import dan200.computercraft.api.lua.LuaException;

import java.nio.ByteBuffer;

/**
 * A Lua-owned RGB framebuffer for a screen (a monitor or billboard) in graphics mode.
 * <p>
 * Pixels are stored as RGB332 (one byte per pixel), the same encoding {@code camera.capture} frames use. Computers
 * draw into the buffer with {@code drawFrame}, and the server periodically pushes dirty buffers to watching clients.
 */
public final class PixelBuffer {
    public static final int MAX_WIDTH = 640;
    public static final int MAX_HEIGHT = 360;

    private final int width;
    private final int height;
    private final byte[] data;
    private boolean dirty = true;

    public PixelBuffer(int width, int height) {
        this.width = width;
        this.height = height;
        this.data = new byte[width * height];
    }

    /**
     * Validate a requested graphics mode size.
     *
     * @param width  The requested width.
     * @param height The requested height.
     * @throws LuaException If the size is out of range.
     */
    public static void checkSize(int width, int height) throws LuaException {
        if (width < 1 || height < 1 || width > MAX_WIDTH || height > MAX_HEIGHT) {
            throw new LuaException("Size out of range (expected 1x1 to " + MAX_WIDTH + "x" + MAX_HEIGHT + ")");
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public byte[] data() {
        return data;
    }

    public boolean pollDirty() {
        var was = dirty;
        dirty = false;
        return was;
    }

    public void markDirty() {
        dirty = true;
    }

    /**
     * Blit a frame into the buffer, clipping to its bounds.
     *
     * @param x           The x position to draw at (0-based).
     * @param y           The y position to draw at (0-based).
     * @param frameWidth  The width of the frame.
     * @param frameHeight The height of the frame.
     * @param format      The frame's pixel format, {@code rgb332} or {@code rgb888}.
     * @param pixels      The frame's pixel data.
     * @throws LuaException If the format is unknown or the data is the wrong length.
     */
    public void blit(int x, int y, int frameWidth, int frameHeight, String format, ByteBuffer pixels) throws LuaException {
        var rgb888 = switch (format) {
            case "rgb332" -> false;
            case "rgb888" -> true;
            default -> throw new LuaException("Unknown frame format '" + format + "' (expected rgb332 or rgb888)");
        };

        var expected = frameWidth * frameHeight * (rgb888 ? 3 : 1);
        if (pixels.remaining() != expected) {
            throw new LuaException("Frame data is the wrong length (expected " + expected + " bytes, got " + pixels.remaining() + ")");
        }

        var base = pixels.position();
        for (var sy = 0; sy < frameHeight; sy++) {
            var destY = y + sy;
            if (destY < 0 || destY >= height) continue;
            for (var sx = 0; sx < frameWidth; sx++) {
                var destX = x + sx;
                if (destX < 0 || destX >= width) continue;

                var index = sy * frameWidth + sx;
                byte pixel;
                if (rgb888) {
                    var r = pixels.get(base + index * 3) & 0xFF;
                    var g = pixels.get(base + index * 3 + 1) & 0xFF;
                    var b = pixels.get(base + index * 3 + 2) & 0xFF;
                    pixel = (byte) ((r >> 5 << 5) | (g >> 5 << 2) | (b >> 6));
                } else {
                    pixel = pixels.get(base + index);
                }
                data[destY * width + destX] = pixel;
            }
        }
        dirty = true;
    }
}
