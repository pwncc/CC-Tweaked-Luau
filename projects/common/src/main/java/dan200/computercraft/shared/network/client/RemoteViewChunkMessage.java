// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.network.client;

import dan200.computercraft.shared.network.NetworkMessage;
import dan200.computercraft.shared.network.NetworkMessages;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * A full chunk column (blocks, block entities and light) of a camera's dimension, for viewers watching from
 * another dimension. The payload is a vanilla {@link ClientboundLevelChunkWithLightPacket}, serialised with its own
 * codec, which the client applies to the channel's puppet level.
 *
 * @param channel The channel this chunk belongs to.
 * @param data    The serialised chunk packet.
 */
public record RemoteViewChunkMessage(int channel, byte[] data) implements NetworkMessage<ClientNetworkContext> {
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteViewChunkMessage> STREAM_CODEC = StreamCodec.of(
        (buf, msg) -> {
            buf.writeVarInt(msg.channel);
            buf.writeByteArray(msg.data);
        },
        buf -> new RemoteViewChunkMessage(buf.readVarInt(), buf.readByteArray())
    );

    /**
     * Serialise a chunk of a camera's level.
     *
     * @param channel The channel to stream on.
     * @param level   The camera's level.
     * @param chunk   The chunk to serialise.
     * @return The serialised chunk message.
     */
    public static RemoteViewChunkMessage of(int channel, ServerLevel level, LevelChunk chunk) {
        var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        var buf = RegistryFriendlyByteBuf.decorator(level.registryAccess()).apply(Unpooled.buffer());
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, packet);
            var data = new byte[buf.writerIndex()];
            buf.getBytes(0, data);
            return new RemoteViewChunkMessage(channel, data);
        } finally {
            buf.release();
        }
    }

    @Override
    public void handle(ClientNetworkContext context) {
        context.handleRemoteViewChunk(this);
    }

    @Override
    public CustomPacketPayload.Type<RemoteViewChunkMessage> type() {
        return NetworkMessages.REMOTE_VIEW_CHUNK;
    }
}
