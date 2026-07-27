// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.turtle.upgrades;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dan200.computercraft.shared.camera.CameraSource;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * The persisted state of a {@link TurtleCamera} upgrade, stored as a data component in the turtle's upgrade data.
 *
 * @param channel   The channel being broadcast on, or {@link CameraSource#NO_CHANNEL}.
 * @param yaw       The peripheral-set yaw offset, relative to the turtle's facing.
 * @param pitch     The view's pitch.
 * @param fov       The vertical field of view.
 * @param loaderPos Where this camera last registered itself with the chunk loader, so the registration can be
 *                  cleaned up (or moved) even across restarts and dimension changes.
 */
public record CameraUpgradeState(int channel, float yaw, float pitch, float fov, Optional<GlobalPos> loaderPos) {
    public static final CameraUpgradeState DEFAULT = new CameraUpgradeState(CameraSource.NO_CHANNEL, 0, 0, 70, Optional.empty());

    public static final Codec<CameraUpgradeState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.INT.optionalFieldOf("channel", CameraSource.NO_CHANNEL).forGetter(CameraUpgradeState::channel),
        Codec.FLOAT.optionalFieldOf("yaw", 0f).forGetter(CameraUpgradeState::yaw),
        Codec.FLOAT.optionalFieldOf("pitch", 0f).forGetter(CameraUpgradeState::pitch),
        Codec.FLOAT.optionalFieldOf("fov", 70f).forGetter(CameraUpgradeState::fov),
        GlobalPos.CODEC.optionalFieldOf("loader_pos").forGetter(CameraUpgradeState::loaderPos)
    ).apply(instance, CameraUpgradeState::new));

    public static final StreamCodec<FriendlyByteBuf, CameraUpgradeState> STREAM_CODEC = StreamCodec.of(
        (buf, state) -> {
            buf.writeVarInt(state.channel);
            buf.writeFloat(state.yaw);
            buf.writeFloat(state.pitch);
            buf.writeFloat(state.fov);
            buf.writeOptional(state.loaderPos, FriendlyByteBuf::writeGlobalPos);
        },
        buf -> new CameraUpgradeState(
            buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readOptional(FriendlyByteBuf::readGlobalPos)
        )
    );

    public CameraUpgradeState withChannel(int channel) {
        return new CameraUpgradeState(channel, yaw, pitch, fov, loaderPos);
    }

    public CameraUpgradeState withRotation(float yaw, float pitch) {
        return new CameraUpgradeState(channel, yaw, pitch, fov, loaderPos);
    }

    public CameraUpgradeState withFov(float fov) {
        return new CameraUpgradeState(channel, yaw, pitch, fov, loaderPos);
    }

    public CameraUpgradeState withLoaderPos(@Nullable GlobalPos loaderPos) {
        return new CameraUpgradeState(channel, yaw, pitch, fov, Optional.ofNullable(loaderPos));
    }
}
