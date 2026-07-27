// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.gametest

import dan200.computercraft.gametest.api.getBlockEntity
import dan200.computercraft.gametest.api.sequence
import dan200.computercraft.shared.ModRegistry
import dan200.computercraft.shared.peripheral.controller.ControllerBlockEntity
import dan200.computercraft.shared.peripheral.controller.ControllerPeripheral
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.GameType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class Controller_Test {
    /**
     * Grabbing the dock and sending inputs updates the peripheral's pollable state, and releasing clears it.
     */
    @GameTest
    fun Input_reaches_peripheral(context: GameTestHelper) = context.sequence {
        thenExecute {
            val dock = context.getBlockEntity(BlockPos(2, 2, 2), ModRegistry.BlockEntities.CONTROLLER.get())
            val peripheral = dock.peripheral() as ControllerPeripheral
            val player = context.makeMockPlayer(GameType.DEFAULT_MODE)

            assertFalse(peripheral.isControlled, "Nobody has the controls yet")
            assertTrue(dock.grab(player), "The player can take the controls")
            assertTrue(peripheral.isControlled, "The dock is now controlled")

            // A second (different) player cannot steal the controls.
            val other = context.makeMockPlayer(GameType.DEFAULT_MODE)
            assertFalse(dock.grab(other), "A second player cannot steal the controls")

            dock.handleInput(player, ControllerBlockEntity.INPUT_AXIS, 0, 0.75f)
            dock.handleInput(player, ControllerBlockEntity.INPUT_BUTTON, 0, 1f)
            dock.handleInput(player, ControllerBlockEntity.INPUT_KEY, 87, 1f)
            // Inputs from somebody who does not hold the controls are ignored.
            dock.handleInput(other, ControllerBlockEntity.INPUT_AXIS, 1, -1f)

            assertEquals(0.75, peripheral.getAxis("left_x"), 1e-6, "left_x axis reflects the input")
            assertEquals(0.0, peripheral.getAxis("left_y"), 1e-6, "The impostor's input was ignored")
            assertTrue(peripheral.isDown("a"), "The A button is held")
            assertTrue(peripheral.isKeyDown(87), "The W key is held")

            val state = peripheral.state
            assertTrue((state["axes"] as Map<*, *>).containsKey("left_x"), "getState carries axes")
            assertTrue((state["buttons"] as Map<*, *>).containsKey("a"), "getState carries buttons")

            dock.handleInput(player, ControllerBlockEntity.INPUT_RELEASE, 0, 0f)
            assertFalse(peripheral.isControlled, "Releasing clears the controller")
            assertEquals(0.0, peripheral.getAxis("left_x"), 1e-6, "Releasing clears the axes")
            assertFalse(peripheral.isDown("a"), "Releasing clears the buttons")
        }
    }
}
