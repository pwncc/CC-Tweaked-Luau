// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.network.server;

import dan200.computercraft.shared.camera.CameraSnapshots;
import dan200.computercraft.shared.network.NetworkMessage;
import dan200.computercraft.shared.network.NetworkMessages;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One chunk of a rendered camera frame, sent by a client which was asked to
 * {@linkplain dan200.computercraft.shared.network.client.CameraCaptureMessage capture a snapshot}.
 * <p>
 * Frames are far larger than the serverbound packet limit, so they arrive as a series of chunks which the server
 * reassembles. A {@code totalLength} of {@code -1} reports that the client could not render the view at all.
 *
 * @param requestId   The capture request this chunk belongs to.
 * @param totalLength The length of the complete frame in bytes, or {@code -1} on failure.
 * @param offset      The offset of this chunk within the frame.
 * @param data        The chunk of RGB332 pixel data.
 */
public record CameraFrameMessage(
    long requestId, int totalLength, int offset, byte[] data
) implements NetworkMessage<ServerNetworkContext> {
    /** The largest chunk a client will send. Comfortably below the 32KiB serverbound payload limit. */
    public static final int MAX_CHUNK = 28_000;

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraFrameMessage> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_LONG, CameraFrameMessage::requestId,
        ByteBufCodecs.INT, CameraFrameMessage::totalLength,
        ByteBufCodecs.INT, CameraFrameMessage::offset,
        ByteBufCodecs.byteArray(MAX_CHUNK), CameraFrameMessage::data,
        CameraFrameMessage::new
    );

    @Override
    public void handle(ServerNetworkContext context) {
        CameraSnapshots.handleUpload(context.getSender(), this);
    }

    @Override
    public CustomPacketPayload.Type<CameraFrameMessage> type() {
        return NetworkMessages.CAMERA_FRAME;
    }
}
