// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.network.client;

import dan200.computercraft.shared.computer.terminal.TerminalState;
import dan200.computercraft.shared.network.NetworkMessage;
import dan200.computercraft.shared.network.NetworkMessages;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Update the terminal contents shown on a billboard's front face.
 *
 * @param pos      The position of the billboard.
 * @param terminal The billboard's current terminal.
 */
public record BillboardClientMessage(
    BlockPos pos, TerminalState terminal
) implements NetworkMessage<ClientNetworkContext> {
    public static final StreamCodec<RegistryFriendlyByteBuf, BillboardClientMessage> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, BillboardClientMessage::pos,
        TerminalState.STREAM_CODEC, BillboardClientMessage::terminal,
        BillboardClientMessage::new
    );

    @Override
    public void handle(ClientNetworkContext context) {
        context.handleBillboardData(pos, terminal);
    }

    @Override
    public CustomPacketPayload.Type<BillboardClientMessage> type() {
        return NetworkMessages.BILLBOARD_CLIENT;
    }
}
