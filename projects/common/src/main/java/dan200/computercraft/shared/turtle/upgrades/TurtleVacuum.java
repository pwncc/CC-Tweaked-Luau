// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.turtle.upgrades;

import dan200.computercraft.api.turtle.AbstractTurtleUpgrade;
import dan200.computercraft.api.turtle.ITurtleAccess;
import dan200.computercraft.api.turtle.TurtleSide;
import dan200.computercraft.api.turtle.TurtleUpgradeType;
import dan200.computercraft.api.upgrades.UpgradeType;
import dan200.computercraft.shared.ModRegistry;
import dan200.computercraft.shared.util.InventoryUtil;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * A passive upgrade which sucks up nearby item drops, storing them in the turtle's inventory.
 * <p>
 * The vacuum scans for nearby items every {@code SUCK_INTERVAL} ticks, picking up all item entities within
 * {@code SUCK_RADIUS} blocks of the turtle, ignoring any pickup delay. Items which do not fit into the turtle's
 * inventory are left in the world.
 */
public class TurtleVacuum extends AbstractTurtleUpgrade {
    /**
     * The radius (in blocks) to search for item drops in.
     */
    private static final double SUCK_RADIUS = 2.5;

    /**
     * The number of ticks between each scan for nearby items.
     */
    private static final int SUCK_INTERVAL = 8;

    public TurtleVacuum(ItemStack stack) {
        super(TurtleUpgradeType.PERIPHERAL, "upgrade.computercraft.vacuum.adjective", stack);
    }

    @Override
    public UpgradeType<TurtleVacuum> getType() {
        return ModRegistry.TurtleUpgradeTypes.VACUUM.get();
    }

    @Override
    public void update(ITurtleAccess turtle, TurtleSide side) {
        var level = turtle.getLevel();
        if (level.isClientSide || level.getGameTime() % SUCK_INTERVAL != 0) return;

        // If vacuums are equipped on both sides, only run the left-hand one, to avoid scanning for items twice.
        if (side == TurtleSide.RIGHT && turtle.getUpgrade(TurtleSide.LEFT) instanceof TurtleVacuum) return;

        var bounds = new AABB(turtle.getPosition()).inflate(SUCK_RADIUS);
        for (var entity : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            if (entity.isRemoved()) continue;

            var stack = entity.getItem();
            if (stack.isEmpty()) continue;

            var remainder = InventoryUtil.storeItemsFromOffset(turtle.getInventory(), stack.copy(), turtle.getSelectedSlot());
            if (remainder.isEmpty()) {
                entity.discard();
            } else if (remainder.getCount() != stack.getCount()) {
                entity.setItem(remainder);
            }
        }
    }
}
