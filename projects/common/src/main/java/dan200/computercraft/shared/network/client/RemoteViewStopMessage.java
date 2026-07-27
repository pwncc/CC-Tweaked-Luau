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
 * Sent when a camera broadcast the player was watching ends (for instance, the camera was broken or changed
 * channel).
 *
 * @param channel The channel which stopped.
 */
public record RemoteViewStopMessage(int channel) implements NetworkMessage<ClientNetworkContext> {
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteViewStopMessage> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, RemoteViewStopMessage::channel,
        RemoteViewStopMessage::new
    );

    @Override
    public void handle(ClientNetworkContext context) {
        context.handleRemoteViewStop(channel);
    }

    @Override
    public CustomPacketPayload.Type<RemoteViewStopMessage> type() {
        return NetworkMessages.REMOTE_VIEW_STOP;
    }
}
