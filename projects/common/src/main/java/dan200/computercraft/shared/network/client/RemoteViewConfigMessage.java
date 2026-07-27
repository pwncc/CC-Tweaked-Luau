// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.network.client;

import dan200.computercraft.shared.camera.BroadcastChannels;
import dan200.computercraft.shared.camera.CameraSource;
import dan200.computercraft.shared.network.NetworkMessage;
import dan200.computercraft.shared.network.NetworkMessages;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Describes (or updates) the viewpoint of a camera broadcast the player is watching.
 *
 * @param channel       The channel this configures.
 * @param dimension     The camera's dimension id.
 * @param dimensionType The camera dimension's dimension type id, for building a matching client-side level.
 * @param biomeSeed     The obfuscated biome-zoom seed of the camera's level.
 * @param cameraPos     The view's eye position, in world space.
 * @param yaw           The view's absolute yaw, in degrees.
 * @param pitch         The view's pitch, in degrees.
 * @param roll          The view's roll, in degrees (cameras riding a banked physics structure).
 * @param fov           The vertical field of view, in degrees.
 * @param streamRadius  The radius (in chunks) of the streamed area.
 * @param minSectionY   The lowest section y of the camera's level.
 * @param sectionCount  The number of sections per chunk column in the camera's level.
 */
public record RemoteViewConfigMessage(
    int channel, ResourceLocation dimension, ResourceLocation dimensionType, long biomeSeed, Vec3 cameraPos,
    float yaw, float pitch, float roll, float fov,
    int streamRadius, int minSectionY, int sectionCount
) implements NetworkMessage<ClientNetworkContext> {
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoteViewConfigMessage> STREAM_CODEC = StreamCodec.of(
        (buf, msg) -> {
            buf.writeVarInt(msg.channel);
            buf.writeResourceLocation(msg.dimension);
            buf.writeResourceLocation(msg.dimensionType);
            buf.writeLong(msg.biomeSeed);
            buf.writeDouble(msg.cameraPos.x);
            buf.writeDouble(msg.cameraPos.y);
            buf.writeDouble(msg.cameraPos.z);
            buf.writeFloat(msg.yaw);
            buf.writeFloat(msg.pitch);
            buf.writeFloat(msg.roll);
            buf.writeFloat(msg.fov);
            buf.writeVarInt(msg.streamRadius);
            buf.writeVarInt(msg.minSectionY);
            buf.writeVarInt(msg.sectionCount);
        },
        buf -> new RemoteViewConfigMessage(
            buf.readVarInt(), buf.readResourceLocation(), buf.readResourceLocation(), buf.readLong(),
            new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()),
            buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readVarInt(), buf.readVarInt(), buf.readVarInt()
        )
    );

    /**
     * Capture the current view configuration of a camera.
     *
     * @param channel The channel being broadcast on.
     * @param camera  The broadcasting camera.
     * @return The view configuration.
     */
    public static RemoteViewConfigMessage of(int channel, CameraSource camera) {
        var level = Objects.requireNonNull(camera.cameraLevel());
        return new RemoteViewConfigMessage(
            channel, level.dimension().location(),
            level.dimensionTypeRegistration().unwrapKey().orElseThrow().location(),
            BiomeManager.obfuscateSeed(level.getSeed()),
            camera.getViewPosition(),
            camera.getAbsoluteYaw(), camera.getPitch(), camera.getRoll(), camera.getFov(),
            BroadcastChannels.streamRadius(level), level.getMinSection(), level.getSectionsCount()
        );
    }

    @Override
    public void handle(ClientNetworkContext context) {
        context.handleRemoteViewConfig(this);
    }

    @Override
    public CustomPacketPayload.Type<RemoteViewConfigMessage> type() {
        return NetworkMessages.REMOTE_VIEW_CONFIG;
    }
}
