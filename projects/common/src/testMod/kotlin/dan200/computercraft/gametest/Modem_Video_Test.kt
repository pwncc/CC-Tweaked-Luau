// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.gametest

import dan200.computercraft.gametest.api.getBlockEntity
import dan200.computercraft.gametest.api.sequence
import dan200.computercraft.shared.ModRegistry
import dan200.computercraft.shared.camera.BroadcastChannels
import dan200.computercraft.shared.camera.VideoLinks
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class Modem_Video_Test {
    /**
     * A camera cannot transmit without a modem; a wireless modem beside it carries the signal, and wireless
     * links respect radio range and dimensions.
     */
    @GameTest
    fun Modem_gates_transmission(context: GameTestHelper) = context.sequence {
        thenExecute {
            val camera = context.getBlockEntity(BlockPos(2, 2, 2), ModRegistry.BlockEntities.CAMERA.get())
            camera.setChannel(98)
            assertFalse(BroadcastChannels.canTransmit(camera), "No modem: the camera cannot transmit")

            context.setBlock(BlockPos(3, 2, 2), ModRegistry.Blocks.WIRELESS_MODEM_NORMAL.get())
            assertTrue(BroadcastChannels.canTransmit(camera), "An attached wireless modem carries the signal")

            context.setBlock(BlockPos(3, 2, 2), Blocks.AIR)
            assertFalse(BroadcastChannels.canTransmit(camera), "Breaking the modem cuts the signal")

            // The computer drives the peripheral, so its modem carries the video too: camera <- computer <- modem.
            context.setBlock(BlockPos(1, 2, 2), ModRegistry.Blocks.COMPUTER_NORMAL.get())
            context.setBlock(BlockPos(0, 2, 2), ModRegistry.Blocks.WIRELESS_MODEM_NORMAL.get())
            assertTrue(BroadcastChannels.canTransmit(camera), "A modem on the camera's computer carries the signal")

            context.setBlock(BlockPos(0, 2, 2), Blocks.AIR)
            assertFalse(BroadcastChannels.canTransmit(camera), "The bare computer alone does not")
            context.setBlock(BlockPos(1, 2, 2), Blocks.AIR)

            // Link-matrix checks on the pure logic: wireless in range, ender across everything.
            val level = context.level
            val a = VideoLinks.Modem(VideoLinks.WIRELESS, context.absolutePos(BlockPos(0, 2, 0)), null)
            val b = VideoLinks.Modem(VideoLinks.WIRELESS, context.absolutePos(BlockPos(4, 2, 4)), null)
            assertTrue(VideoLinks.linked(level, listOf(a), level, listOf(b)), "Nearby wireless modems link")

            val far = VideoLinks.Modem(VideoLinks.WIRELESS, context.absolutePos(BlockPos(0, 2, 0)).offset(100000, 0, 0), null)
            assertFalse(VideoLinks.linked(level, listOf(a), level, listOf(far)), "Out-of-range wireless modems do not link")

            val ender = VideoLinks.Modem(VideoLinks.ENDER, context.absolutePos(BlockPos(0, 2, 0)), null)
            assertTrue(VideoLinks.linked(level, listOf(ender), level, listOf(far)), "An ender modem reaches anything")

            val wiredA = VideoLinks.Modem(VideoLinks.WIRED, context.absolutePos(BlockPos(0, 2, 0)), "netA")
            val wiredB = VideoLinks.Modem(VideoLinks.WIRED, context.absolutePos(BlockPos(4, 2, 4)), "netA")
            val wiredC = VideoLinks.Modem(VideoLinks.WIRED, context.absolutePos(BlockPos(4, 2, 4)), "netB")
            assertTrue(VideoLinks.linked(level, listOf(wiredA), level, listOf(wiredB)), "Same wired network links")
            assertFalse(VideoLinks.linked(level, listOf(wiredA), level, listOf(wiredC)), "Different wired networks do not link")
        }
    }

    /**
     * Two wired modems joined by cable resolve to the same network; an isolated one does not.
     */
    @GameTest
    fun Wired_networks_resolve(context: GameTestHelper) = context.sequence {
        thenExecute {
            context.setBlock(BlockPos(1, 2, 2), ModRegistry.Blocks.WIRED_MODEM_FULL.get())
            context.setBlock(BlockPos(2, 2, 2), ModRegistry.Blocks.WIRED_MODEM_FULL.get())
            context.setBlock(BlockPos(4, 2, 4), ModRegistry.Blocks.WIRED_MODEM_FULL.get())
        }
        // Wired networks form over a couple of ticks as the block entities connect.
        thenWaitUntil {
            val level = context.level
            val a = VideoLinks.wiredNetworkAt(level, context.absolutePos(BlockPos(1, 2, 2)))
                ?: throw GameTestAssertException("First modem has no network yet")
            val b = VideoLinks.wiredNetworkAt(level, context.absolutePos(BlockPos(2, 2, 2)))
                ?: throw GameTestAssertException("Second modem has no network yet")
            val c = VideoLinks.wiredNetworkAt(level, context.absolutePos(BlockPos(4, 2, 4)))
                ?: throw GameTestAssertException("Isolated modem has no network yet")
            if (a !== b) throw GameTestAssertException("Adjacent wired modems should share a network")
            if (a === c) throw GameTestAssertException("The isolated modem should have its own network")
        }
    }
}
