// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.seismograph;

import com.mojang.serialization.MapCodec;
import dan200.computercraft.shared.ModRegistry;
import dan200.computercraft.shared.util.BlockEntityHelpers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The block for seismographs. All the interesting behaviour lives in the {@linkplain SeismographBlockEntity block
 * entity}.
 */
public final class SeismographBlock extends Block implements EntityBlock {
    private static final MapCodec<SeismographBlock> CODEC = simpleCodec(SeismographBlock::new);
    private static final BlockEntityTicker<SeismographBlockEntity> serverTicker = (level, pos, state, seismograph) -> seismograph.serverTick();

    public SeismographBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<SeismographBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return ModRegistry.BlockEntities.SEISMOGRAPH.get().create(pos, state);
    }

    @Override
    @Nullable
    public <U extends BlockEntity> BlockEntityTicker<U> getTicker(Level level, BlockState state, BlockEntityType<U> type) {
        return level.isClientSide ? null : BlockEntityHelpers.createTickerHelper(type, ModRegistry.BlockEntities.SEISMOGRAPH.get(), serverTicker);
    }
}
