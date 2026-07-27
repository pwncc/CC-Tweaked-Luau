// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.peripheral.cassette;

import dan200.computercraft.shared.ModRegistry;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The menu for cassette decks. This has a single slot, which only accepts {@linkplain ModRegistry.Items#CASSETTE
 * cassettes}.
 */
public class CassetteDeckMenu extends AbstractContainerMenu {
    private final Container inventory;

    public CassetteDeckMenu(int id, Inventory player, Container inventory) {
        super(ModRegistry.Menus.CASSETTE_DECK.get(), id);

        this.inventory = inventory;

        addSlot(new Slot(this.inventory, 0, 8 + 4 * 18, 35) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(ModRegistry.Items.CASSETTE.get());
            }
        });

        for (var y = 0; y < 3; y++) {
            for (var x = 0; x < 9; x++) {
                addSlot(new Slot(player, x + y * 9 + 9, 8 + x * 18, 84 + y * 18));
            }
        }

        for (var x = 0; x < 9; x++) {
            addSlot(new Slot(player, x, 8 + x * 18, 142));
        }
    }

    public CassetteDeckMenu(int id, Inventory player) {
        this(id, player, new SimpleContainer(1));
    }

    @Override
    public boolean stillValid(Player player) {
        return inventory.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        var slot = slots.get(slotIndex);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        var existing = slot.getItem().copy();
        var result = existing.copy();
        if (slotIndex == 0) {
            // Insert into player inventory
            if (!moveItemStackTo(existing, 1, 37, true)) return ItemStack.EMPTY;
        } else {
            // Insert into deck inventory
            if (!moveItemStackTo(existing, 0, 1, false)) return ItemStack.EMPTY;
        }

        if (existing.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        if (existing.getCount() == result.getCount()) return ItemStack.EMPTY;

        slot.onTake(player, existing);
        return result;
    }
}
