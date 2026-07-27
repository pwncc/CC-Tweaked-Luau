// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.pocket.peripherals;

import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.pocket.AbstractPocketUpgrade;
import dan200.computercraft.api.pocket.IPocketAccess;
import dan200.computercraft.api.upgrades.UpgradeType;
import dan200.computercraft.shared.ModRegistry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

public class PocketFireworks extends AbstractPocketUpgrade {
    public PocketFireworks() {
        super("upgrade.computercraft.firework_launcher.adjective", new ItemStack(Items.FIREWORK_ROCKET));
    }

    @Nullable
    @Override
    public IPeripheral createPeripheral(IPocketAccess access) {
        return new FireworksPeripheral(access);
    }

    @Override
    public UpgradeType<PocketFireworks> getType() {
        return ModRegistry.PocketUpgradeTypes.FIREWORK_LAUNCHER.get();
    }
}
