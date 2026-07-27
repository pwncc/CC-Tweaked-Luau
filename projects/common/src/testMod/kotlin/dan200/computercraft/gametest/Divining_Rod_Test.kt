// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.gametest

import dan200.computercraft.api.lua.LuaException
import dan200.computercraft.gametest.api.getBlockEntity
import dan200.computercraft.gametest.api.sequence
import dan200.computercraft.shared.ModRegistry
import dan200.computercraft.shared.peripheral.diviningrod.DiviningRodPeripheral
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

class Divining_Rod_Test {
    private fun peripheral(context: GameTestHelper, pos: BlockPos) =
        context.getBlockEntity(pos, ModRegistry.BlockEntities.DIVINING_ROD.get()).peripheral()
            as DiviningRodPeripheral

    /**
     * The rod senses nearby ore groups (without leaking positions), pulls point the right way,
     * and back-to-back readings are rejected while it re-attunes.
     *
     * These tests run in their own batch: the rod's 17x17x17 scan reaches into neighbouring
     * arena structures, so they cannot share ore types with each other (or run alongside the
     * rest of the suite).
     */
    @GameTest(batch = "divining_rod")
    fun Surveys_ores(context: GameTestHelper) = context.sequence {
        val rod = BlockPos(2, 2, 2)

        thenExecute {
            val peripheral = peripheral(context, rod)
            val survey = peripheral.survey()

            assertTrue(survey.getOrDefault("coal", 0.0) > 0.0, "Coal is sensed: $survey")
            assertTrue(survey.getOrDefault("diamond", 0.0) > 0.0, "Diamond is sensed: $survey")
            assertTrue(
                survey.getOrDefault("coal", 0.0) > survey.getOrDefault("diamond", 0.0),
                "The nearer coal reads stronger than the farther diamond: $survey",
            )

            // A second reading straight away is rejected while the rod attunes.
            val error = assertThrows(LuaException::class.java) { peripheral.survey() }
            assertTrue(error.message!!.contains("attuning"), "Cooldown error mentions attuning: ${error.message}")
        }
    }

    /**
     * pull() gives a coarse compass bearing towards an ore group.
     */
    @GameTest(batch = "divining_rod")
    fun Pull_gives_bearing(context: GameTestHelper) = context.sequence {
        val rod = BlockPos(2, 2, 2)

        thenExecute {
            val result = peripheral(context, rod).pull("iron")
            assertNotNull(result, "Iron is nearby")
            assertEquals("west", result!![1], "The single iron ore sits due west")
        }
    }
}
