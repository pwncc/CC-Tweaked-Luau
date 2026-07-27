// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.network.server;

import dan200.computercraft.shared.network.NetworkMessage;
import dan200.computercraft.shared.network.NetworkMessages;
import dan200.computercraft.shared.peripheral.controller.ControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * One input from the client of the player holding a controller dock's controls: a key, gamepad button or axis
 * change, a heartbeat, or an explicit release.
 *
 * @param pos   The dock's position.
 * @param kind  The input kind; one of the {@code ControllerBlockEntity.INPUT_*} constants.
 * @param code  The key code, button index or axis index.
 * @param value The axis value, or 1/0 for pressed/released.
 */
public record ControllerInputMessage(BlockPos pos, byte kind, int code, float value) implements NetworkMessage<ServerNetworkContext> {
    public static final StreamCodec<RegistryFriendlyByteBuf, ControllerInputMessage> STREAM_CODEC = StreamCodec.of(
        (buf, msg) -> {
            buf.writeBlockPos(msg.pos);
            buf.writeByte(msg.kind);
            buf.writeVarInt(msg.code);
            buf.writeFloat(msg.value);
        },
        buf -> new ControllerInputMessage(buf.readBlockPos(), buf.readByte(), buf.readVarInt(), buf.readFloat())
    );

    @Override
    public void handle(ServerNetworkContext context) {
        var player = context.getSender();
        if (player.blockPosition().distSqr(pos) > ControllerBlockEntity.GRAB_RANGE * ControllerBlockEntity.GRAB_RANGE * 4) return;
        if (player.level().getBlockEntity(pos) instanceof ControllerBlockEntity controller) {
            controller.handleInput(player, kind, code, value);
        }
    }

    @Override
    public CustomPacketPayload.Type<ControllerInputMessage> type() {
        return NetworkMessages.CONTROLLER_INPUT;
    }
}
