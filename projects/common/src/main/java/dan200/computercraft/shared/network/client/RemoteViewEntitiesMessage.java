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
 * A snapshot of the entities visible to a camera, for viewers in a <em>different</em> dimension (same-dimension
 * viewers see the real entities through their camera zone). The payload is a compact list read by
 * {@code RemoteViewCache}.
 *
 * @param channel The channel this updates.
 * @param payload The serialised entity list.
 */
public record RemoteViewEntitiesMessage(int channel, byte[] payload) implements NetworkMessage<ClientNetworkContext> {
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteViewEntitiesMessage> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, RemoteViewEntitiesMessage::channel,
        ByteBufCodecs.byteArray(1 << 20), RemoteViewEntitiesMessage::payload,
        RemoteViewEntitiesMessage::new
    );

    @Override
    public void handle(ClientNetworkContext context) {
        context.handleRemoteViewEntities(this);
    }

    @Override
    public CustomPacketPayload.Type<RemoteViewEntitiesMessage> type() {
        return NetworkMessages.REMOTE_VIEW_ENTITIES;
    }
}
