// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.computer.apis;

import dan200.computercraft.api.lua.ILuaAPI;
import dan200.computercraft.api.lua.IComputerSystem;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.shared.computer.blocks.NanoComputerBlockEntity;
import dan200.computercraft.shared.util.DataComponentUtil;
import org.jspecify.annotations.Nullable;

/**
 * The {@code chip} API, exposed on nano computers.
 * <p>
 * This mounts the installed {@linkplain dan200.computercraft.shared.media.items.RomChipItem ROM chip} read-only at
 * {@code /chip} when the computer boots (the {@code rom/autorun/nano_chip.lua} script then runs
 * {@code chip/startup.lua}), and provides a few functions for inspecting the chip from Lua.
 *
 * @cc.module chip
 */
public class NanoChipAPI implements ILuaAPI {
    private final IComputerSystem system;
    private final NanoComputerBlockEntity nano;
    private @Nullable String mountLocation;

    public NanoChipAPI(IComputerSystem system, NanoComputerBlockEntity nano) {
        this.system = system;
        this.nano = nano;
    }

    @Override
    public String[] getNames() {
        return new String[]{ "chip" };
    }

    @Override
    public void startup() {
        var mount = nano.getChipMount();
        if (mount != null) mountLocation = system.mount("chip", mount);
    }

    @Override
    public void shutdown() {
        var location = mountLocation;
        mountLocation = null;
        if (location == null) return;
        try {
            system.unmount(location);
        } catch (RuntimeException ignored) {
            // The filesystem is already gone; nothing to clean up.
        }
    }

    /**
     * Check whether a ROM chip is installed.
     *
     * @return Whether a chip is installed.
     */
    @LuaFunction(mainThread = true)
    public final boolean present() {
        return !nano.getChip().isEmpty();
    }

    /**
     * Get the label of the installed chip.
     *
     * @return The chip's label, or {@code nil} if there is no chip or it has no label.
     */
    @LuaFunction(mainThread = true)
    public final @Nullable String getLabel() {
        var chip = nano.getChip();
        return chip.isEmpty() ? null : DataComponentUtil.getCustomName(chip);
    }

    /**
     * Eject the installed chip into the world, rebooting the computer.
     */
    @LuaFunction(mainThread = true)
    public final void eject() {
        nano.ejectChip(null);
    }
}
