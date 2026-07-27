// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.gametest

import dan200.computercraft.gametest.api.assertBlockHas
import dan200.computercraft.gametest.api.getBlockEntity
import dan200.computercraft.gametest.api.sequence
import dan200.computercraft.shared.ModRegistry
import dan200.computercraft.shared.peripheral.chromalamp.ChromaLampPeripheral
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.Optional

class Chroma_Lamp_Test {
    private fun peripheral(context: GameTestHelper, pos: BlockPos) =
        context.getBlockEntity(pos, ModRegistry.BlockEntities.CHROMA_LAMP.get()).peripheral()
            as ChromaLampPeripheral

    /**
     * Brightness changes are reflected in the block's light level, and the colour round-trips.
     */
    @GameTest
    fun Brightness_and_colour(context: GameTestHelper) = context.sequence {
        val lamp = BlockPos(2, 2, 2)

        thenExecute {
            val peripheral = peripheral(context, lamp)
            peripheral.setColour(0x9B30FF)
            assertEquals(0x9B30FF, peripheral.getColour(), "Colour round-trips")
            peripheral.setBrightness(15)
        }
        thenIdle(2)
        thenExecute { context.assertBlockHas(lamp, BlockStateProperties.LEVEL, 15, "Lamp is at full brightness") }
        thenExecute { peripheral(context, lamp).setBrightness(0) }
        thenIdle(2)
        thenExecute { context.assertBlockHas(lamp, BlockStateProperties.LEVEL, 0, "Lamp is off again") }
    }

    /**
     * pulse() flashes to full brightness, then restores the previous level on its own.
     */
    @GameTest
    fun Pulse_restores(context: GameTestHelper) = context.sequence {
        val lamp = BlockPos(2, 2, 2)

        thenExecute {
            val peripheral = peripheral(context, lamp)
            peripheral.setBrightness(3)
            peripheral.pulse(Optional.of(0.5))
        }
        thenIdle(3)
        thenExecute { context.assertBlockHas(lamp, BlockStateProperties.LEVEL, 15, "Pulse drives the lamp to 15") }
        thenIdle(12)
        thenExecute { context.assertBlockHas(lamp, BlockStateProperties.LEVEL, 3, "Pulse restores the old brightness") }
    }
}
