// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.cassette;

import com.mojang.serialization.MapCodec;
import dan200.computercraft.shared.ModRegistry;
import dan200.computercraft.shared.common.HorizontalContainerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jspecify.annotations.Nullable;

/**
 * The block for cassette decks. This mostly just forwards method calls to the
 * {@linkplain CassetteDeckBlockEntity block entity}.
 */
public class CassetteDeckBlock extends HorizontalContainerBlock {
    private static final MapCodec<CassetteDeckBlock> CODEC = simpleCodec(CassetteDeckBlock::new);

    private static final BlockEntityTicker<CassetteDeckBlockEntity> serverTicker = (level, pos, state, deck) -> deck.serverTick();

    public CassetteDeckBlock(Properties settings) {
        super(settings);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> properties) {
        properties.add(FACING);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    /**
     * A default implementation of {@link Item#useOn(UseOnContext)} for items that can be placed into a cassette deck.
     *
     * @param context The context of this item usage action.
     * @return Whether the item was placed or not.
     */
    public static InteractionResult defaultUseItemOn(UseOnContext context) {
        var level = context.getLevel();
        var blockPos = context.getClickedPos();
        var blockState = level.getBlockState(blockPos);
        if (!blockState.is(ModRegistry.Blocks.CASSETTE_DECK.get())) return InteractionResult.PASS;

        if (!level.isClientSide && level.getBlockEntity(blockPos) instanceof CassetteDeckBlockEntity deck && deck.getCassette().isEmpty()) {
            deck.setCassette(context.getItemInHand().split(1));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return ModRegistry.BlockEntities.CASSETTE_DECK.get().create(pos, state);
    }

    @Override
    @Nullable
    public <U extends BlockEntity> BlockEntityTicker<U> getTicker(Level level, BlockState state, BlockEntityType<U> type) {
        return level.isClientSide ? null : BaseEntityBlock.createTickerHelper(type, ModRegistry.BlockEntities.CASSETTE_DECK.get(), serverTicker);
    }
}
