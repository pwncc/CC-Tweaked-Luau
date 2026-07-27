// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.peripheral.camera;

import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.shared.camera.BroadcastChannels;
import dan200.computercraft.shared.camera.CameraChunkLoader;
import dan200.computercraft.shared.camera.CameraHolder;
import dan200.computercraft.shared.camera.SableSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The camera: a fixed viewpoint which can broadcast the world around it on a numbered channel.
 * <p>
 * The camera itself holds only the view state (rotation, field of view, channel); the heavy lifting of streaming
 * world data to watching players lives in {@link BroadcastChannels}.
 */
public final class CameraBlockEntity extends BlockEntity implements CameraHolder {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CameraBlockEntity.class);

    public static final int NO_CHANNEL = CameraHolder.NO_CHANNEL;
    public static final int MAX_CHANNEL = CameraHolder.MAX_CHANNEL;

    private static final String NBT_CHANNEL = "Channel";
    private static final String NBT_YAW = "Yaw";
    private static final String NBT_PITCH = "Pitch";
    private static final String NBT_FOV = "Fov";

    private final CameraPeripheral peripheral = new CameraPeripheral(this);

    private int channel = NO_CHANNEL;
    private float yaw = 0; // Relative to the block's facing.
    private float pitch = 0;
    private float fov = 70;

    /**
     * Where this camera's chunk loader is armed. Cameras riding physics structures move - most drastically when
     * the structure warps between dimensions (e.g. falling back from space) and this block entity is recreated
     * somewhere new. The loader follows along in {@link #serverTick()}, or the camera would stop ticking (and
     * broadcasting) as soon as its new home unloads.
     */
    private @Nullable GlobalPos loaderPos;

    /**
     * Whether this camera has ever been part of a physics structure. When the structure leaves without us -
     * a dimension warp serialises the sub-level away, leaving this block entity behind in the abandoned plot,
     * which our own chunk loader would otherwise keep alive forever - the camera must step aside: stop pumping,
     * release the chunk loader, and free the channel for the copy of itself that now lives wherever the
     * structure went.
     */
    private boolean wasOnStructure = false;
    private boolean orphaned = false;

    public CameraBlockEntity(BlockEntityType<CameraBlockEntity> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public IPeripheral peripheral() {
        return peripheral;
    }

    void serverTick() {
        if (channel != NO_CHANNEL && getLevel() instanceof ServerLevel level) {
            if (orphaned) return;
            if (SableSupport.poseAt(level, Vec3.atCenterOf(getBlockPos())) != null) {
                wasOnStructure = true;
            } else if (wasOnStructure) {
                // The structure warped away without us: the block entity that replaced this camera at the
                // destination takes over the broadcast (adopting the channel once this one lets go).
                orphaned = true;
                LOG.info(
                    "[camera] Camera at {} {} lost its structure; releasing channel {}",
                    level.dimension().location(), getBlockPos(), channel
                );
                stopBroadcast();
                return;
            }

            var here = GlobalPos.of(level.dimension(), getBlockPos().immutable());
            if (!here.equals(loaderPos)) {
                if (loaderPos != null) CameraChunkLoader.remove(level.getServer(), loaderPos);
                CameraChunkLoader.add(level, getBlockPos());
                loaderPos = here;
            }
            BroadcastChannels.get(level.getServer()).updateCamera(channel, this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        stopBroadcast();
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        // (Re-)registration happens lazily from serverTick.
    }

    private void stopBroadcast() {
        if (getLevel() instanceof ServerLevel level) {
            if (channel != NO_CHANNEL) BroadcastChannels.get(level.getServer()).removeCamera(channel, this);
            if (loaderPos != null) {
                CameraChunkLoader.remove(level.getServer(), loaderPos);
                loaderPos = null;
            }
        }
    }

    @Override
    public int getChannel() {
        return channel;
    }

    @Override
    public void setChannel(int channel) {
        if (this.channel == channel) return;
        stopBroadcast();
        this.channel = channel;
        setChanged();
        // The chunk loader is (re-)armed by the next serverTick, which tracks the camera as it moves.
    }

    @Override
    public float getYaw() {
        return yaw;
    }

    @Override
    public float getLocalPitch() {
        return pitch;
    }

    @Override
    public void setRotation(float yaw, float pitch) {
        this.yaw = Mth.wrapDegrees(yaw);
        this.pitch = Mth.clamp(pitch, -89, 89);
        setChanged();
    }

    @Override
    public float getFov() {
        return fov;
    }

    @Override
    public void setFov(float fov) {
        this.fov = Mth.clamp(fov, 30, 110);
        setChanged();
    }

    /**
     * The view's yaw within the camera's own level: the block's facing plus the peripheral-set offset. A camera
     * mounted facing up or down has no horizontal facing, so the offset alone steers it.
     *
     * @return The camera's structure-local yaw.
     */
    private float localYaw() {
        var facing = getBlockState().getValue(CameraBlock.FACING);
        return facing.getAxis().isVertical() ? Mth.wrapDegrees(yaw) : Mth.wrapDegrees(facing.toYRot() + yaw);
    }

    /**
     * The view's pitch within the camera's own level: straight up/down for vertically mounted cameras, plus the
     * peripheral-set offset.
     *
     * @return The camera's structure-local pitch.
     */
    private float localPitch() {
        var facing = getBlockState().getValue(CameraBlock.FACING);
        var base = facing == net.minecraft.core.Direction.UP ? -90 : facing == net.minecraft.core.Direction.DOWN ? 90 : 0;
        return Mth.clamp(base + pitch, -90, 90);
    }

    /**
     * The absolute yaw of the view in degrees, combining the block's facing with the peripheral-set offset (and,
     * for cameras riding a physics structure, the structure's rotation).
     *
     * @return The camera's world-space yaw.
     */
    @Override
    public float getAbsoluteYaw() {
        return SableSupport.toWorldYaw(getLevel(), Vec3.atCenterOf(getBlockPos()), localYaw(), localPitch());
    }

    @Override
    public float getPitch() {
        return SableSupport.toWorldPitch(getLevel(), Vec3.atCenterOf(getBlockPos()), localYaw(), localPitch());
    }

    @Override
    public float getRoll() {
        return SableSupport.toWorldRoll(getLevel(), Vec3.atCenterOf(getBlockPos()), localYaw(), localPitch());
    }

    @Override
    public Vec3 getViewPosition() {
        return SableSupport.toWorldPosition(getLevel(), Vec3.atCenterOf(getBlockPos()));
    }

    @Override
    public BlockPos sourcePosition() {
        return getBlockPos();
    }

    @Override
    public @Nullable Vec3 localViewPosition() {
        var local = Vec3.atCenterOf(getBlockPos());
        return SableSupport.poseAt(getLevel(), local) != null ? local : null;
    }

    @Override
    public float localViewYaw() {
        return localYaw();
    }

    @Override
    public float localViewPitch() {
        return localPitch();
    }

    @Override
    public boolean isSourceRemoved() {
        return isRemoved() || orphaned;
    }

    @Override
    public void loadAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.loadAdditional(nbt, registries);
        channel = nbt.contains(NBT_CHANNEL) ? nbt.getInt(NBT_CHANNEL) : NO_CHANNEL;
        yaw = nbt.getFloat(NBT_YAW);
        pitch = nbt.getFloat(NBT_PITCH);
        fov = nbt.contains(NBT_FOV) ? nbt.getFloat(NBT_FOV) : 70;
    }

    @Override
    public void saveAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.saveAdditional(nbt, registries);
        if (channel != NO_CHANNEL) nbt.putInt(NBT_CHANNEL, channel);
        nbt.putFloat(NBT_YAW, yaw);
        nbt.putFloat(NBT_PITCH, pitch);
        nbt.putFloat(NBT_FOV, fov);
    }

    @Override
    public @Nullable ServerLevel cameraLevel() {
        return getLevel() instanceof ServerLevel level ? level : null;
    }
}
