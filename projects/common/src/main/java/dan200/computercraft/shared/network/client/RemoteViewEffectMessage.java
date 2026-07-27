// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.network.client;

import dan200.computercraft.shared.network.NetworkMessage;
import dan200.computercraft.shared.network.NetworkMessages;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;

/**
 * A transient effect (particles, a level event such as block-break debris, a block event such as a chest lid, or
 * block cracking progress) inside a camera's streamed area, mirrored to cross-dimension viewers for their puppet
 * level. The payload is the corresponding vanilla packet, serialised with its own codec.
 *
 * @param channel The channel this effect belongs to.
 * @param kind    Which packet type the payload holds; one of the {@code KIND_*} constants.
 * @param data    The serialised vanilla packet.
 */
public record RemoteViewEffectMessage(int channel, byte kind, byte[] data) implements NetworkMessage<ClientNetworkContext> {
    public static final byte KIND_PARTICLES = 0;
    public static final byte KIND_LEVEL_EVENT = 1;
    public static final byte KIND_BLOCK_EVENT = 2;
    public static final byte KIND_BLOCK_DESTRUCTION = 3;

    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteViewEffectMessage> STREAM_CODEC = StreamCodec.of(
        (buf, msg) -> {
            buf.writeVarInt(msg.channel);
            buf.writeByte(msg.kind);
            buf.writeByteArray(msg.data);
        },
        buf -> new RemoteViewEffectMessage(buf.readVarInt(), buf.readByte(), buf.readByteArray())
    );

    /**
     * Identify a mirrorable packet.
     *
     * @param packet The packet a level is broadcasting.
     * @return Its {@code KIND_*} constant, or {@code -1} if this packet type is not mirrored.
     */
    public static int kindOf(Packet<?> packet) {
        if (packet instanceof ClientboundLevelParticlesPacket) return KIND_PARTICLES;
        if (packet instanceof ClientboundLevelEventPacket event) return event.isGlobalEvent() ? -1 : KIND_LEVEL_EVENT;
        if (packet instanceof ClientboundBlockEventPacket) return KIND_BLOCK_EVENT;
        if (packet instanceof ClientboundBlockDestructionPacket) return KIND_BLOCK_DESTRUCTION;
        return -1;
    }

    /**
     * Serialise a mirrorable packet.
     *
     * @param level  The level broadcasting the packet.
     * @param kind   The packet's kind, from {@link #kindOf(Packet)}.
     * @param packet The packet itself.
     * @return The serialised packet.
     */
    public static byte[] encode(ServerLevel level, int kind, Packet<?> packet) {
        var buf = RegistryFriendlyByteBuf.decorator(level.registryAccess()).apply(Unpooled.buffer());
        try {
            switch (kind) {
                case KIND_PARTICLES -> ClientboundLevelParticlesPacket.STREAM_CODEC.encode(buf, (ClientboundLevelParticlesPacket) packet);
                case KIND_LEVEL_EVENT -> ClientboundLevelEventPacket.STREAM_CODEC.encode(buf, (ClientboundLevelEventPacket) packet);
                case KIND_BLOCK_EVENT -> ClientboundBlockEventPacket.STREAM_CODEC.encode(buf, (ClientboundBlockEventPacket) packet);
                case KIND_BLOCK_DESTRUCTION -> ClientboundBlockDestructionPacket.STREAM_CODEC.encode(buf, (ClientboundBlockDestructionPacket) packet);
                default -> throw new IllegalArgumentException("Unknown effect kind " + kind);
            }
            var data = new byte[buf.writerIndex()];
            buf.getBytes(0, data);
            return data;
        } finally {
            buf.release();
        }
    }

    @Override
    public void handle(ClientNetworkContext context) {
        context.handleRemoteViewEffect(this);
    }

    @Override
    public CustomPacketPayload.Type<RemoteViewEffectMessage> type() {
        return NetworkMessages.REMOTE_VIEW_EFFECT;
    }
}
