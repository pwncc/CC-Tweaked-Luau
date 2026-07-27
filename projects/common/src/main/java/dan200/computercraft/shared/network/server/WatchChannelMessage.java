// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.network.server;

import dan200.computercraft.shared.camera.BroadcastChannels;
import dan200.computercraft.shared.network.NetworkMessage;
import dan200.computercraft.shared.network.NetworkMessages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Sent by the client when the camera channel it wants to watch changes. A channel of {@code -1} stops watching.
 * <p>
 * Clients watch at most one channel at a time: whichever tuned screen is closest to the player. The server treats
 * this purely as a hint - it controls what is actually streamed.
 *
 * @param channel The channel to watch, or {@code -1} for none.
 * @param screen  The screen the client watches through, used to find the receiving modems.
 */
public record WatchChannelMessage(int channel, Optional<BlockPos> screen) implements NetworkMessage<ServerNetworkContext> {
    public static final StreamCodec<RegistryFriendlyByteBuf, WatchChannelMessage> STREAM_CODEC = StreamCodec.of(
        (buf, msg) -> {
            buf.writeInt(msg.channel);
            buf.writeOptional(msg.screen, (b, p) -> b.writeBlockPos(p));
        },
        buf -> new WatchChannelMessage(buf.readInt(), buf.readOptional(b -> b.readBlockPos()))
    );

    /**
     * Watch a channel through a screen.
     *
     * @param channel The channel to watch.
     * @param screen  The screen's position, or null.
     * @return The message.
     */
    public static WatchChannelMessage of(int channel, @Nullable BlockPos screen) {
        return new WatchChannelMessage(channel, Optional.ofNullable(screen));
    }

    @Override
    public void handle(ServerNetworkContext context) {
        var player = context.getSender();
        BroadcastChannels.get(player.server).watch(player, channel, screen.orElse(null));
    }

    @Override
    public CustomPacketPayload.Type<WatchChannelMessage> type() {
        return NetworkMessages.WATCH_CHANNEL;
    }
}
