// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.network.client;

import dan200.computercraft.shared.network.NetworkMessage;
import dan200.computercraft.shared.network.NetworkMessages;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Sent to a client to ask it to render a snapshot of a camera channel and upload the pixels back.
 * <p>
 * The receiving client watches the channel like any screen would, waits for the streamed picture to settle, renders
 * it off-screen and replies with {@link dan200.computercraft.shared.network.server.CameraFrameMessage}s.
 *
 * @param requestId The unique id of this capture request, echoed in the reply.
 * @param channel   The channel to render.
 * @param width     The requested frame width, in pixels.
 * @param height    The requested frame height, in pixels.
 */
public record CameraCaptureMessage(
    long requestId, int channel, int width, int height
) implements NetworkMessage<ClientNetworkContext> {
    public static final StreamCodec<RegistryFriendlyByteBuf, CameraCaptureMessage> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_LONG, CameraCaptureMessage::requestId,
        ByteBufCodecs.INT, CameraCaptureMessage::channel,
        ByteBufCodecs.INT, CameraCaptureMessage::width,
        ByteBufCodecs.INT, CameraCaptureMessage::height,
        CameraCaptureMessage::new
    );

    @Override
    public void handle(ClientNetworkContext context) {
        context.handleCameraCapture(this);
    }

    @Override
    public CustomPacketPayload.Type<CameraCaptureMessage> type() {
        return NetworkMessages.CAMERA_CAPTURE;
    }
}
