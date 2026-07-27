// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.media.items;

import dan200.computercraft.shared.peripheral.diskdrive.DiskDriveBlock;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/**
 * A small storage chip that a nano computer boots from.
 * <p>
 * Chips are written like any other media: put one in a disk drive next to a full-size computer and edit
 * {@code disk/startup.lua}. Slotted into a nano computer, the chip mounts read-only at {@code /chip} and its
 * startup program runs on boot.
 */
public class RomChipItem extends Item {
    public RomChipItem(Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return DiskDriveBlock.defaultUseItemOn(context);
    }
}
