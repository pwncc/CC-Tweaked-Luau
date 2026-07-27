// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.chromalamp;

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
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jspecify.annotations.Nullable;

/**
 * The block for chroma lamps. The brightness is stored in the {@link #LEVEL} block state property (so light levels
 * update properly), while the colour lives on the {@linkplain ChromaLampBlockEntity block entity}.
 */
public final class ChromaLampBlock extends Block implements EntityBlock {
    public static final IntegerProperty LEVEL = BlockStateProperties.LEVEL;

    private static final MapCodec<ChromaLampBlock> CODEC = simpleCodec(ChromaLampBlock::new);
    private static final BlockEntityTicker<ChromaLampBlockEntity> serverTicker = (level, pos, state, lamp) -> lamp.serverTick();

    public ChromaLampBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(LEVEL, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> properties) {
        properties.add(LEVEL);
    }

    @Override
    protected MapCodec<ChromaLampBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return ModRegistry.BlockEntities.CHROMA_LAMP.get().create(pos, state);
    }

    @Override
    @Nullable
    public <U extends BlockEntity> BlockEntityTicker<U> getTicker(Level level, BlockState state, BlockEntityType<U> type) {
        return level.isClientSide ? null : BlockEntityHelpers.createTickerHelper(type, ModRegistry.BlockEntities.CHROMA_LAMP.get(), serverTicker);
    }
}
