// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.sequencer;

import com.mojang.serialization.MapCodec;
import dan200.computercraft.shared.ModRegistry;
import dan200.computercraft.shared.util.BlockEntityHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The block for redstone sequencers. This mostly just forwards method calls to the
 * {@linkplain RedstoneSequencerBlockEntity block entity}.
 */
public final class RedstoneSequencerBlock extends Block implements EntityBlock {
    private static final MapCodec<RedstoneSequencerBlock> CODEC = simpleCodec(RedstoneSequencerBlock::new);
    private static final BlockEntityTicker<RedstoneSequencerBlockEntity> serverTicker = (level, pos, state, sequencer) -> sequencer.serverTick();

    public RedstoneSequencerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<RedstoneSequencerBlock> codec() {
        return CODEC;
    }

    @Override
    @Deprecated
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    @Deprecated
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction incomingSide) {
        return level.getBlockEntity(pos) instanceof RedstoneSequencerBlockEntity sequencer ? sequencer.getOutput(incomingSide.getOpposite()) : 0;
    }

    @Override
    @Deprecated
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getDirectSignal(state, level, pos, direction);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return ModRegistry.BlockEntities.REDSTONE_SEQUENCER.get().create(pos, state);
    }

    @Override
    @Nullable
    public <U extends BlockEntity> BlockEntityTicker<U> getTicker(Level level, BlockState state, BlockEntityType<U> type) {
        return level.isClientSide ? null : BlockEntityHelpers.createTickerHelper(type, ModRegistry.BlockEntities.REDSTONE_SEQUENCER.get(), serverTicker);
    }
}
