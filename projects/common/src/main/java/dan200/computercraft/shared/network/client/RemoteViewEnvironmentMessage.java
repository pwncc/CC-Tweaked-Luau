// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.network.client;

import dan200.computercraft.shared.network.NetworkMessage;
import dan200.computercraft.shared.network.NetworkMessages;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;

/**
 * The environment (time and weather) of a camera's dimension, so a cross-dimension puppet level matches the real
 * one. Sent when it changes meaningfully, and periodically to correct clock drift.
 *
 * @param channel      The channel this configures.
 * @param dayTime      The level's time of day.
 * @param rainLevel    The level's rain strength.
 * @param thunderLevel The level's thunder strength.
 */
public record RemoteViewEnvironmentMessage(
    int channel, long dayTime, float rainLevel, float thunderLevel
) implements NetworkMessage<ClientNetworkContext> {
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteViewEnvironmentMessage> STREAM_CODEC = StreamCodec.of(
        (buf, msg) -> {
            buf.writeVarInt(msg.channel);
            buf.writeVarLong(msg.dayTime);
            buf.writeFloat(msg.rainLevel);
            buf.writeFloat(msg.thunderLevel);
        },
        buf -> new RemoteViewEnvironmentMessage(buf.readVarInt(), buf.readVarLong(), buf.readFloat(), buf.readFloat())
    );

    /**
     * Capture the environment of a camera's level.
     *
     * @param channel The channel to configure.
     * @param level   The camera's level.
     * @return The environment message.
     */
    public static RemoteViewEnvironmentMessage of(int channel, ServerLevel level) {
        return new RemoteViewEnvironmentMessage(
            channel, level.getDayTime(), level.getRainLevel(1f), level.getThunderLevel(1f)
        );
    }

    @Override
    public void handle(ClientNetworkContext context) {
        context.handleRemoteViewEnvironment(this);
    }

    @Override
    public CustomPacketPayload.Type<RemoteViewEnvironmentMessage> type() {
        return NetworkMessages.REMOTE_VIEW_ENVIRONMENT;
    }
}
