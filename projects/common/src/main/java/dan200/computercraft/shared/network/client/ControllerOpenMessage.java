// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.network.client;

import dan200.computercraft.shared.network.NetworkMessage;
import dan200.computercraft.shared.network.NetworkMessages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Sent when a player takes a controller dock's controls: their client opens the control screen, which streams
 * keyboard and gamepad input back to the dock.
 *
 * @param pos The dock's position.
 */
public record ControllerOpenMessage(BlockPos pos) implements NetworkMessage<ClientNetworkContext> {
    public static final StreamCodec<RegistryFriendlyByteBuf, ControllerOpenMessage> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, ControllerOpenMessage::pos,
        ControllerOpenMessage::new
    );

    @Override
    public void handle(ClientNetworkContext context) {
        context.handleControllerOpen(this);
    }

    @Override
    public CustomPacketPayload.Type<ControllerOpenMessage> type() {
        return NetworkMessages.CONTROLLER_OPEN;
    }
}
