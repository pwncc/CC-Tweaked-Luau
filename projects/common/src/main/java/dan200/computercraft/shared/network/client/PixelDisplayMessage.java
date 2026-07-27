// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.network.client;

import dan200.computercraft.shared.display.PixelBuffer;
import dan200.computercraft.shared.network.NetworkMessage;
import dan200.computercraft.shared.network.NetworkMessages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The contents of a screen's {@linkplain PixelBuffer pixel buffer}, pushed to players who can see it.
 * <p>
 * A zero-sized buffer means the screen has left graphics mode.
 *
 * @param pos    The position of the screen (a monitor origin, or a billboard).
 * @param width  The buffer width in pixels, or {@code 0} when leaving graphics mode.
 * @param height The buffer height in pixels.
 * @param data   The RGB332 pixel data.
 */
public record PixelDisplayMessage(
    BlockPos pos, int width, int height, byte[] data
) implements NetworkMessage<ClientNetworkContext> {
    public static final StreamCodec<RegistryFriendlyByteBuf, PixelDisplayMessage> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, PixelDisplayMessage::pos,
        ByteBufCodecs.VAR_INT, PixelDisplayMessage::width,
        ByteBufCodecs.VAR_INT, PixelDisplayMessage::height,
        ByteBufCodecs.byteArray(PixelBuffer.MAX_WIDTH * PixelBuffer.MAX_HEIGHT), PixelDisplayMessage::data,
        PixelDisplayMessage::new
    );

    /**
     * Create a message for the current contents of a buffer.
     *
     * @param pos    The screen's position.
     * @param buffer The buffer to send.
     * @return The message to send.
     */
    public static PixelDisplayMessage of(BlockPos pos, PixelBuffer buffer) {
        return new PixelDisplayMessage(pos, buffer.width(), buffer.height(), buffer.data());
    }

    /**
     * Create a message reporting that a screen has left graphics mode.
     *
     * @param pos The screen's position.
     * @return The message to send.
     */
    public static PixelDisplayMessage cleared(BlockPos pos) {
        return new PixelDisplayMessage(pos, 0, 0, new byte[0]);
    }

    @Override
    public void handle(ClientNetworkContext context) {
        context.handlePixelDisplay(this);
    }

    @Override
    public CustomPacketPayload.Type<PixelDisplayMessage> type() {
        return NetworkMessages.PIXEL_DISPLAY;
    }
}
