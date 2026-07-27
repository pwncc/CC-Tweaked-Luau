// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.gametest

import dan200.computercraft.gametest.api.assertBlockHas
import dan200.computercraft.gametest.api.getBlockEntity
import dan200.computercraft.gametest.api.sequence
import dan200.computercraft.shared.ModRegistry
import dan200.computercraft.shared.peripheral.sequencer.RedstoneSequencerPeripheral
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.RedstoneLampBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.Optional

class Sequencer_Test {
    private fun peripheral(context: GameTestHelper, pos: BlockPos) =
        context.getBlockEntity(pos, ModRegistry.BlockEntities.REDSTONE_SEQUENCER.get()).peripheral()
            as RedstoneSequencerPeripheral

    /**
     * A looping pattern drives a neighbouring lamp on and off without any further computer involvement.
     */
    @GameTest
    fun Plays_pattern(context: GameTestHelper) = context.sequence {
        val sequencer = BlockPos(2, 2, 2)
        val lamp = BlockPos(2, 2, 3)

        thenExecute {
            // 10 ticks on, 10 ticks off, looping.
            peripheral(context, sequencer).setPattern("south", mapOf(1.0 to 10.0, 2.0 to 10.0), Optional.empty())
        }
        thenIdle(3)
        thenExecute { context.assertBlockHas(lamp, RedstoneLampBlock.LIT, true, "Lamp lit in the ON segment") }
        thenIdle(14) // t=17: signal off at t=10, plus the lamp's turn-off delay.
        thenExecute { context.assertBlockHas(lamp, RedstoneLampBlock.LIT, false, "Lamp unlit in the OFF segment") }
        thenIdle(7) // t=24: the loop has wrapped back into the ON segment.
        thenExecute { context.assertBlockHas(lamp, RedstoneLampBlock.LIT, true, "Lamp lit again after the loop") }
    }

    /**
     * Non-looping patterns finish, stop reporting as running, and can be cleared.
     */
    @GameTest
    fun Finishes_and_clears(context: GameTestHelper) = context.sequence {
        val sequencer = BlockPos(2, 2, 2)

        thenExecute {
            val peripheral = peripheral(context, sequencer)
            peripheral.setPattern("up", mapOf(1.0 to 2.0), Optional.of(false))
            assertTrue(peripheral.isRunning("up"), "Running immediately after setPattern")
            assertEquals(listOf(2), peripheral.getPattern("up"), "Pattern is stored")
        }
        thenIdle(6)
        thenExecute {
            val peripheral = peripheral(context, sequencer)
            assertFalse(peripheral.isRunning("up"), "Stopped once the pattern finished")

            peripheral.clear(Optional.empty())
            assertNull(peripheral.getPattern("up"), "Pattern removed after clear()")
        }
    }
}
