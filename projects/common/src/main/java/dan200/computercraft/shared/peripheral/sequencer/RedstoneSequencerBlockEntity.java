// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.sequencer;

import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.shared.util.DirectionUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * The block entity for redstone sequencers. This stores a redstone pattern for each side of the block, and plays the
 * active ones back on the server tick, independently of any attached computer.
 */
public final class RedstoneSequencerBlockEntity extends BlockEntity {
    private static final String NBT_SIDES = "Sides";
    private static final String NBT_DURATIONS = "Durations";
    private static final String NBT_LOOP = "Loop";
    private static final String NBT_RUNNING = "Running";
    private static final String NBT_CURSOR = "Cursor";
    private static final String NBT_TICKS_LEFT = "TicksLeft";

    private final RedstoneSequencerPeripheral peripheral = new RedstoneSequencerPeripheral(this);
    private final @Nullable SideState[] sides = new SideState[DirectionUtil.FACINGS.length];

    public RedstoneSequencerBlockEntity(BlockEntityType<RedstoneSequencerBlockEntity> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    void serverTick() {
        var changed = false;
        for (var direction : DirectionUtil.FACINGS) {
            var side = sides[direction.ordinal()];
            if (side == null || !side.running) continue;

            if (--side.ticksLeft > 0) continue;

            side.cursor++;
            if (side.cursor >= side.durations.length) {
                if (side.loop) {
                    side.cursor = 0;
                    side.ticksLeft = side.durations[0];
                } else {
                    side.running = false;
                    peripheral.queueDone(direction);
                }
            } else {
                side.ticksLeft = side.durations[side.cursor];
            }

            var output = side.currentOutput();
            if (output != side.output) {
                side.output = output;
                changed = true;
            }
            setChanged();
        }

        if (changed) updateNeighbours();
    }

    void setPattern(Direction direction, int[] durations, boolean loop) {
        sides[direction.ordinal()] = new SideState(durations, loop);
        setChanged();
        updateNeighbours();
    }

    void clear(@Nullable Direction direction) {
        if (direction == null) {
            Arrays.fill(sides, null);
        } else {
            sides[direction.ordinal()] = null;
        }
        setChanged();
        updateNeighbours();
    }

    boolean isRunning(Direction direction) {
        var side = sides[direction.ordinal()];
        return side != null && side.running;
    }

    int @Nullable [] getPattern(Direction direction) {
        var side = sides[direction.ordinal()];
        return side == null ? null : side.durations.clone();
    }

    int getOutput(Direction direction) {
        var side = sides[direction.ordinal()];
        return side == null ? 0 : side.output;
    }

    public IPeripheral peripheral() {
        return peripheral;
    }

    private void updateNeighbours() {
        var level = getLevel();
        if (level != null) level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);

        Arrays.fill(sides, null);
        var sidesTag = tag.getCompound(NBT_SIDES);
        for (var direction : DirectionUtil.FACINGS) {
            if (!sidesTag.contains(direction.getName(), Tag.TAG_COMPOUND)) continue;

            var sideTag = sidesTag.getCompound(direction.getName());
            var durations = sideTag.getIntArray(NBT_DURATIONS);
            if (durations.length == 0) continue;

            var side = new SideState(durations, sideTag.getBoolean(NBT_LOOP));
            side.running = sideTag.getBoolean(NBT_RUNNING);
            side.cursor = Mth.clamp(sideTag.getInt(NBT_CURSOR), 0, durations.length - 1);
            side.ticksLeft = Math.max(1, sideTag.getInt(NBT_TICKS_LEFT));
            side.output = side.currentOutput();
            sides[direction.ordinal()] = side;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);

        var sidesTag = new CompoundTag();
        for (var direction : DirectionUtil.FACINGS) {
            var side = sides[direction.ordinal()];
            if (side == null) continue;

            var sideTag = new CompoundTag();
            sideTag.putIntArray(NBT_DURATIONS, side.durations.clone());
            sideTag.putBoolean(NBT_LOOP, side.loop);
            sideTag.putBoolean(NBT_RUNNING, side.running);
            sideTag.putInt(NBT_CURSOR, side.cursor);
            sideTag.putInt(NBT_TICKS_LEFT, side.ticksLeft);
            sidesTag.put(direction.getName(), sideTag);
        }
        if (!sidesTag.isEmpty()) tag.put(NBT_SIDES, sidesTag);
    }

    /**
     * The playback state of a single side of the sequencer.
     */
    private static final class SideState {
        final int[] durations;
        final boolean loop;
        boolean running = true;
        int cursor = 0;
        int ticksLeft;
        int output;

        SideState(int[] durations, boolean loop) {
            this.durations = durations;
            this.loop = loop;
            ticksLeft = durations[0];
            output = currentOutput();
        }

        /**
         * Get the redstone level this side should currently emit. Segments alternate on/off, starting on.
         *
         * @return The current redstone level.
         */
        int currentOutput() {
            return running && cursor % 2 == 0 ? 15 : 0;
        }
    }
}
