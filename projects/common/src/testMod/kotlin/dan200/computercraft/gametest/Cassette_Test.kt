// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.gametest

import dan200.computercraft.api.lua.Coerced
import dan200.computercraft.api.lua.LuaException
import dan200.computercraft.gametest.api.getBlockEntity
import dan200.computercraft.gametest.api.sequence
import dan200.computercraft.gametest.api.setContainerItem
import dan200.computercraft.shared.ModRegistry
import dan200.computercraft.shared.peripheral.cassette.CassetteDeckPeripheral
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.ByteBuffer
import java.util.Optional

class Cassette_Test {
    private fun peripheral(context: GameTestHelper, pos: BlockPos) =
        context.getBlockEntity(pos, ModRegistry.BlockEntities.CASSETTE_DECK.get()).peripheral()
            as CassetteDeckPeripheral

    /**
     * Data written to a cassette can be read back after rewinding.
     */
    @GameTest
    fun Writes_and_reads(context: GameTestHelper) = context.sequence {
        val deck = BlockPos(2, 2, 2)
        val message = "hello tape"

        thenExecute {
            context.setContainerItem(deck, 0, ItemStack(ModRegistry.Items.CASSETTE.get()))

            val peripheral = peripheral(context, deck)
            val written = peripheral.write(Coerced(ByteBuffer.wrap(message.toByteArray())))
            assertEquals(message.length, written, "Wrote the whole message")
            assertEquals(message.length.toLong(), peripheral.getPosition(), "Position advanced by the write")
            peripheral.rewind()
        }
        thenIdle(2) // Rewinding a few bytes takes under a tick.
        thenExecute {
            val peripheral = peripheral(context, deck)
            assertTrue(peripheral.isReady(), "Deck is ready after rewinding")
            assertEquals(message, String(peripheral.read(Optional.of(message.length))), "Read the message back")
        }
    }

    /**
     * Long seeks wind the tape over multiple ticks, with [CassetteDeckPeripheral.isReady] reporting `false` until the
     * target position is reached.
     */
    @GameTest
    fun Winds_asynchronously(context: GameTestHelper) = context.sequence {
        val deck = BlockPos(2, 2, 2)

        thenExecute {
            context.setContainerItem(deck, 0, ItemStack(ModRegistry.Items.CASSETTE.get()))

            val time = peripheral(context, deck).seek(1_000_000)
            assertEquals(1_000_000 / 512_000.0, time, 1e-9, "seek() reports the wind time")
        }
        thenIdle(5)
        thenExecute { assertFalse(peripheral(context, deck).isReady(), "Deck is still winding") }
        thenIdle(45) // The full wind takes 40 ticks.
        thenExecute {
            val peripheral = peripheral(context, deck)
            assertTrue(peripheral.isReady(), "Deck has finished winding")
            assertEquals(1_000_000L, peripheral.getPosition(), "Wound to the target position")
        }
    }

    /**
     * Reading from a deck with no cassette inserted throws.
     */
    @GameTest
    fun Requires_cassette(context: GameTestHelper) = context.sequence {
        val deck = BlockPos(2, 2, 2)

        thenExecute {
            val e = assertThrows(LuaException::class.java) { peripheral(context, deck).read(Optional.empty()) }
            assertEquals("No cassette inserted", e.message)
        }
    }
}
