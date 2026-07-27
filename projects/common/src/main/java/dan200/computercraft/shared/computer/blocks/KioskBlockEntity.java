// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.shared.computer.blocks;

import dan200.computercraft.shared.computer.core.ComputerFamily;
import dan200.computercraft.shared.computer.core.TerminalSize;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A public-facing touchscreen terminal with a portrait screen.
 * <p>
 * Kiosks are always on: the computer is (re)booted whenever the block is loaded or the computer shuts down.
 */
public class KioskBlockEntity extends ComputerBlockEntity {
    private static final TerminalSize TERMINAL_SIZE = new TerminalSize(28, 30);

    /**
     * How often (in ticks) we check whether the computer needs booting.
     */
    private static final int REBOOT_INTERVAL = 20;

    public KioskBlockEntity(BlockEntityType<? extends ComputerBlockEntity> type, BlockPos pos, BlockState state, ComputerFamily family) {
        super(type, pos, state, family);
    }

    @Override
    protected TerminalSize defaultTerminalSize() {
        return TERMINAL_SIZE;
    }

    @Override
    protected void serverTick() {
        super.serverTick();

        // Kiosks boot themselves whenever they're loaded or shut down. Only attempt this once a second, to avoid
        // repeatedly poking a computer which is already starting up.
        if (!(getLevel() instanceof ServerLevel level) || level.getGameTime() % REBOOT_INTERVAL != 0) return;

        var computer = getServerComputer();
        if (computer == null || !computer.isOn()) createServerComputer().turnOn();
    }
}
