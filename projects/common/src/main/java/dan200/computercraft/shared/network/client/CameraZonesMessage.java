// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.network.client;

import dan200.computercraft.shared.camera.CameraViewZones;
import dan200.computercraft.shared.network.NetworkMessage;
import dan200.computercraft.shared.network.NetworkMessages;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Tells a client which extra chunk zones (around watched cameras) the server is syncing to it, so the client knows
 * to retain those chunks even though they fall outside its normal storage ring.
 *
 * @param zones The active zones, possibly empty.
 */
public record CameraZonesMessage(List<CameraViewZones.Zone> zones) implements NetworkMessage<ClientNetworkContext> {
    private static final StreamCodec<RegistryFriendlyByteBuf, CameraViewZones.Zone> ZONE_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, CameraViewZones.Zone::centerX,
        ByteBufCodecs.VAR_INT, CameraViewZones.Zone::centerZ,
        ByteBufCodecs.VAR_INT, CameraViewZones.Zone::radius,
        CameraViewZones.Zone::new
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, CameraZonesMessage> STREAM_CODEC = StreamCodec.composite(
        ZONE_CODEC.apply(ByteBufCodecs.list(64)), CameraZonesMessage::zones,
        CameraZonesMessage::new
    );

    @Override
    public void handle(ClientNetworkContext context) {
        context.handleCameraZones(this);
    }

    @Override
    public CustomPacketPayload.Type<CameraZonesMessage> type() {
        return NetworkMessages.CAMERA_ZONES;
    }
}
