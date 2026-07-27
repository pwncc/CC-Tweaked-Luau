// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.turtle.upgrades;

import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.turtle.AbstractTurtleUpgrade;
import dan200.computercraft.api.turtle.ITurtleAccess;
import dan200.computercraft.api.turtle.TurtleSide;
import dan200.computercraft.api.turtle.TurtleUpgradeType;
import dan200.computercraft.api.upgrades.UpgradeType;
import dan200.computercraft.shared.ModRegistry;
import dan200.computercraft.shared.camera.BroadcastChannels;
import dan200.computercraft.shared.camera.CameraChunkLoader;
import dan200.computercraft.shared.camera.CameraHolder;
import dan200.computercraft.shared.camera.SableSupport;
import dan200.computercraft.shared.peripheral.camera.CameraPeripheral;
import dan200.computercraft.shared.turtle.blocks.TurtleBlockEntity;
import dan200.computercraft.shared.turtle.core.TurtleBrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A camera mounted on the side of a turtle: the same peripheral (and broadcast pipeline) as the camera block, but
 * the viewpoint moves with the turtle - viewers follow along live.
 * <p>
 * All state lives in a {@linkplain CameraUpgradeState data component} on the turtle's upgrade data, so a broadcast
 * survives restarts: the {@link CameraChunkLoader} keeps the turtle's chunk loaded, the turtle resumes ticking, and
 * the pump below re-adopts the channel.
 */
public class TurtleCamera extends AbstractTurtleUpgrade {
    public TurtleCamera(ItemStack stack) {
        super(TurtleUpgradeType.PERIPHERAL, "upgrade.computercraft.camera.adjective", stack);
    }

    @Override
    public UpgradeType<TurtleCamera> getType() {
        return ModRegistry.TurtleUpgradeTypes.CAMERA.get();
    }

    @Override
    public IPeripheral createPeripheral(ITurtleAccess turtle, TurtleSide side) {
        return new CameraPeripheral(new Holder(turtle, side));
    }

    @Override
    public void update(ITurtleAccess turtle, TurtleSide side) {
        if (turtle.getLevel().isClientSide) return;
        if (turtle.getPeripheral(side) instanceof CameraPeripheral peripheral && peripheral.holder() instanceof Holder holder) {
            holder.tick();
        }
    }

    /**
     * Whether a turtle carries a camera upgrade that is currently broadcasting. Used by the chunk loader to decide
     * whether a loaded position still deserves its tickets.
     *
     * @param turtle The turtle to inspect.
     * @return Whether either side is a broadcasting camera.
     */
    public static boolean isBroadcasting(ITurtleAccess turtle) {
        for (var side : TurtleSide.values()) {
            if (turtle.getUpgrade(side) instanceof TurtleCamera && state(turtle, side).channel() != CameraHolder.NO_CHANNEL) {
                return true;
            }
        }
        return false;
    }

    private static CameraUpgradeState state(ITurtleAccess turtle, TurtleSide side) {
        var data = turtle.getUpgradeData(side).get(ModRegistry.DataComponents.CAMERA.get());
        return data == null || data.isEmpty() ? CameraUpgradeState.DEFAULT : data.get();
    }

    private static final class Holder implements CameraHolder {
        private final ITurtleAccess turtle;
        private final TurtleSide side;

        Holder(ITurtleAccess turtle, TurtleSide side) {
            this.turtle = turtle;
            this.side = side;
        }

        private CameraUpgradeState state() {
            return TurtleCamera.state(turtle, side);
        }

        private void setState(CameraUpgradeState state) {
            turtle.setUpgradeData(side, DataComponentPatch.builder().set(ModRegistry.DataComponents.CAMERA.get(), state).build());
        }

        /**
         * The per-tick pump: keep the chunk-loader registration at the turtle's current position, then stream.
         */
        void tick() {
            var state = state();
            if (state.channel() == NO_CHANNEL) return;

            var level = cameraLevel();
            if (level == null) return;

            var here = GlobalPos.of(level.dimension(), turtle.getPosition().immutable());
            if (state.loaderPos().isEmpty() || !state.loaderPos().get().equals(here)) {
                var server = level.getServer();
                state.loaderPos().ifPresent(old -> CameraChunkLoader.remove(server, old));
                CameraChunkLoader.add(level, here.pos());
                setState(state.withLoaderPos(here));
            }

            BroadcastChannels.get(level.getServer()).updateCamera(state.channel(), this);
        }

        @Override
        public void setChannel(int channel) {
            var state = state();
            if (state.channel() == channel) return;

            var level = cameraLevel();
            if (level != null) {
                var server = level.getServer();
                if (state.channel() != NO_CHANNEL) BroadcastChannels.get(server).removeCamera(state.channel(), this);
                state.loaderPos().ifPresent(old -> CameraChunkLoader.remove(server, old));

                if (channel != NO_CHANNEL) {
                    var here = GlobalPos.of(level.dimension(), turtle.getPosition().immutable());
                    CameraChunkLoader.add(level, here.pos());
                    setState(state.withChannel(channel).withLoaderPos(here));
                    return;
                }
            }
            setState(state.withChannel(channel).withLoaderPos(null));
        }

        @Override
        public float getYaw() {
            return state().yaw();
        }

        @Override
        public void setRotation(float yaw, float pitch) {
            setState(state().withRotation(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -89, 89)));
        }

        @Override
        public void setFov(float fov) {
            setState(state().withFov(Mth.clamp(fov, 30, 110)));
        }

        @Override
        public @Nullable ServerLevel cameraLevel() {
            return turtle.getLevel() instanceof ServerLevel level ? level : null;
        }

        /**
         * The turtle's animated position, before any physics-structure transform.
         *
         * @return The structure-local view position.
         */
        private Vec3 localPosition() {
            var position = Vec3.atCenterOf(turtle.getPosition());
            // Follow the movement animation, so the view glides between blocks instead of snapping.
            if (turtle instanceof TurtleBrain brain) position = position.add(brain.getRenderOffset(1));
            return position;
        }

        private float localYaw() {
            var facing = turtle instanceof TurtleBrain brain ? brain.getVisualYaw(1) : turtle.getDirection().toYRot();
            return Mth.wrapDegrees(facing + state().yaw());
        }

        @Override
        public Vec3 getViewPosition() {
            return SableSupport.toWorldPosition(turtle.getLevel(), localPosition());
        }

        @Override
        public float getAbsoluteYaw() {
            return SableSupport.toWorldYaw(turtle.getLevel(), localPosition(), localYaw(), state().pitch());
        }

        @Override
        public float getPitch() {
            return SableSupport.toWorldPitch(turtle.getLevel(), localPosition(), localYaw(), state().pitch());
        }

        @Override
        public float getRoll() {
            return SableSupport.toWorldRoll(turtle.getLevel(), localPosition(), localYaw(), state().pitch());
        }

        @Override
        public BlockPos sourcePosition() {
            return turtle.getPosition();
        }

        @Override
        public @Nullable Vec3 localViewPosition() {
            var local = localPosition();
            return SableSupport.poseAt(turtle.getLevel(), local) != null ? local : null;
        }

        @Override
        public float localViewYaw() {
            return localYaw();
        }

        @Override
        public float localViewPitch() {
            return state().pitch();
        }

        @Override
        public float getLocalPitch() {
            return state().pitch();
        }

        @Override
        public float getFov() {
            return state().fov();
        }

        @Override
        public int getChannel() {
            return state().channel();
        }

        @Override
        public int equippedModem() {
            var kind = -1;
            for (var side : TurtleSide.values()) {
                if (turtle.getUpgrade(side) instanceof TurtleModem modem) {
                    kind = Math.max(kind, modem.advanced() ? dan200.computercraft.shared.camera.VideoLinks.ENDER : dan200.computercraft.shared.camera.VideoLinks.WIRELESS);
                }
            }
            return kind;
        }

        @Override
        public boolean isSourceRemoved() {
            if (turtle.isRemoved()) return true;
            // A stale holder outlives its turtle when the chunk unloads: the block entity at our position is then
            // no longer backed by this ITurtleAccess (or is not loaded at all).
            var level = turtle.getLevel();
            return !(level.getBlockEntity(turtle.getPosition()) instanceof TurtleBlockEntity entity)
                || entity.getAccess() != turtle;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return this == other || (other instanceof Holder holder && turtle == holder.turtle && side == holder.side);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(turtle) * 31 + side.hashCode();
        }
    }
}
