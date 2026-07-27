// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package dan200.computercraft.shared.media.items;

import dan200.computercraft.shared.peripheral.cassette.CassetteDeckBlock;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/**
 * An item that can be shift-right-clicked into a {@link CassetteDeckBlock}.
 */
public class CassetteItem extends Item {
    public CassetteItem(Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return CassetteDeckBlock.defaultUseItemOn(context);
    }
}
