// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.diviningrod;

import com.mojang.serialization.MapCodec;
import dan200.computercraft.shared.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The block for divining rods. All the interesting behaviour lives in the
 * {@linkplain DiviningRodPeripheral peripheral}.
 */
public final class DiviningRodBlock extends Block implements EntityBlock {
    private static final MapCodec<DiviningRodBlock> CODEC = simpleCodec(DiviningRodBlock::new);

    public DiviningRodBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<DiviningRodBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return ModRegistry.BlockEntities.DIVINING_ROD.get().create(pos, state);
    }
}
