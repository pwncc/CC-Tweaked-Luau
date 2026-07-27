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
 * A batch of world sections for a camera broadcast.
 * <p>
 * The payload is a sequence of entries, each a section position ({@code long}), a VarInt payload length, and that
 * many bytes of {@link net.minecraft.world.level.chunk.LevelChunkSection#write serialised section data}. A zero
 * length marks an empty (all-air) section.
 *
 * @param channel The channel this data belongs to.
 * @param payload The serialised sections.
 */
public record RemoteViewSectionsMessage(int channel, byte[] payload) implements NetworkMessage<ClientNetworkContext> {
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteViewSectionsMessage> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, RemoteViewSectionsMessage::channel,
        ByteBufCodecs.BYTE_ARRAY, RemoteViewSectionsMessage::payload,
        RemoteViewSectionsMessage::new
    );

    @Override
    public void handle(ClientNetworkContext context) {
        context.handleRemoteViewSections(this);
    }

    @Override
    public CustomPacketPayload.Type<RemoteViewSectionsMessage> type() {
        return NetworkMessages.REMOTE_VIEW_SECTIONS;
    }
}
