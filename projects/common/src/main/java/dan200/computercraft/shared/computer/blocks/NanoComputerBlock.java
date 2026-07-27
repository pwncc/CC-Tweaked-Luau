// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.computer.blocks;

import dan200.computercraft.impl.PocketUpgrades;
import dan200.computercraft.shared.media.items.RomChipItem;
import dan200.computercraft.shared.platform.RegistryEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The nano computer's block: headless, so clicking never opens a GUI. Instead, items are fitted by right-clicking
 * with them, popped out by sneak-clicking, and the computer is powered up with an empty-hand click.
 */
public class NanoComputerBlock extends ComputerBlock<NanoComputerBlockEntity> {
    public NanoComputerBlock(Properties settings, RegistryEntry<BlockEntityType<NanoComputerBlockEntity>> type) {
        super(settings, type);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof NanoComputerBlockEntity nano
            && (stack.getItem() instanceof RomChipItem || PocketUpgrades.instance().get(level.registryAccess(), stack) != null)) {
            if (!level.isClientSide && nano.isUsable(player)) nano.installItem(player, stack);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof NanoComputerBlockEntity nano)) return InteractionResult.PASS;

        if (!level.isClientSide && nano.isUsable(player)) {
            if (player.isCrouching()) {
                nano.ejectNext(player);
            } else {
                nano.activate(player);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof NanoComputerBlockEntity nano) {
            nano.dropContents();
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
