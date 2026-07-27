// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.gametest

import dan200.computercraft.client.render.remoteview.RemoteViewCache
import dan200.computercraft.gametest.api.ClientGameTest
import dan200.computercraft.gametest.api.getBlockEntity
import dan200.computercraft.gametest.api.positionAtArmorStand
import dan200.computercraft.gametest.api.sequence
import dan200.computercraft.gametest.api.thenOnClient
import dan200.computercraft.shared.ModRegistry
import dan200.computercraft.shared.peripheral.camera.CameraBlock
import dan200.computercraft.shared.peripheral.monitor.MonitorBlock
import dan200.computercraft.shared.peripheral.monitor.MonitorPeripheral
import dev.ryanhcode.sable.companion.SableCompanion
import dev.ryanhcode.sable.companion.impl.DefaultSableCompanion
import dev.ryanhcode.sable.companion.math.BoundingBox3d
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The remote-piloting scenario, end to end: a camera-and-computer rig is assembled into a Sable physics structure
 * and launched skyward at high speed while the player watches its feed on a monitor. Everything that has ever
 * broken in this flight is asserted here: the structure must keep simulating (the camera's pose keeps climbing),
 * the camera must keep broadcasting, the watcher's client must keep tracking the structure, and screenshots
 * document what the player actually saw.
 *
 * Skipped (passing trivially) when Sable is not on the classpath.
 */
class Sable_Flight_Test {
    companion object {
        /** The broadcast channel the flight uses; distinct from other tests' channels. */
        private const val CHANNEL = 88

        @Volatile private var baselineY = Double.NaN
        @Volatile private var duringY = Double.NaN
        @Volatile private var duringOnStructure = false
        @Volatile private var duringTracked = false
        @Volatile private var afterY = Double.NaN
        @Volatile private var afterTracked = false
    }

    @ClientGameTest(timeoutTicks = 2400)
    fun Flying_structure_stays_watchable(context: GameTestHelper) = context.sequence {
        thenExecute {
            if (!sableLoaded()) return@thenExecute
            baselineY = Double.NaN
            duringY = Double.NaN

            // The rig to launch: pedestal, computer, ender modem on the computer, camera on top looking down
            // at the world it is about to leave.
            context.setBlock(BlockPos(2, 1, 4), Blocks.NETHERRACK)
            context.setBlock(BlockPos(2, 2, 4), ModRegistry.Blocks.COMPUTER_NORMAL.get())
            context.setBlock(BlockPos(1, 2, 4), ModRegistry.Blocks.WIRELESS_MODEM_ADVANCED.get())
            context.setBlock(
                BlockPos(2, 3, 4),
                ModRegistry.Blocks.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.NORTH),
            )
            val camera = context.getBlockEntity(BlockPos(2, 3, 4), ModRegistry.BlockEntities.CAMERA.get())
            camera.setChannel(CHANNEL)
            camera.setFov(90f)
            camera.setRotation(0f, 50f)

            // The ground station: a two-tall monitor in front of the player, with its own ender modem.
            val monitorState = ModRegistry.Blocks.MONITOR_ADVANCED.get().defaultBlockState().setValue(MonitorBlock.FACING, Direction.NORTH)
            context.setBlock(BlockPos(2, 1, 2), monitorState)
            context.setBlock(BlockPos(2, 2, 2), monitorState)
            context.setBlock(BlockPos(3, 2, 2), ModRegistry.Blocks.WIRELESS_MODEM_ADVANCED.get())
            val monitor = context.getBlockEntity(BlockPos(2, 2, 2), ModRegistry.BlockEntities.MONITOR_ADVANCED.get())
            (monitor.peripheral() as MonitorPeripheral).setChannel(CHANNEL)

            context.positionAtArmorStand()

            // Assemble the rig into a physics structure.
            val from = context.absolutePos(BlockPos(1, 1, 4))
            val to = context.absolutePos(BlockPos(2, 3, 4))
            val server = context.level.server
            server.commands.performPrefixedCommand(
                server.createCommandSourceStack(),
                "sable assemble area ${from.x} ${from.y} ${from.z} ${to.x} ${to.y} ${to.z}",
            )
        }

        // Assembly is asynchronous: wait for the structure to appear.
        thenWaitUntil {
            if (!sableLoaded()) return@thenWaitUntil
            val cameraWorld = Vec3.atCenterOf(context.absolutePos(BlockPos(2, 3, 4)))
            SableCompanion.INSTANCE
                .getAllIntersecting(context.level, BoundingBox3d(AABB.ofSize(cameraWorld, 4.0, 4.0, 4.0)))
                .firstOrNull() ?: throw GameTestAssertException("No sub-level was assembled at the rig")
        }

        // Let the watch handshake and the live feed spin up, then document lift-off conditions.
        thenIdle(150)
        thenOnClient { minecraft.options.hideGui = true }
        thenIdle(5)
        thenExecute { if (sableLoaded()) screenshot("sable_flight.before.png") }
        thenIdle(10)
        thenOnClient {
            if (!sableLoaded()) return@thenOnClient
            baselineY = RemoteViewCache.getView(CHANNEL)?.config()?.cameraPos()?.y ?: Double.NaN
        }
        thenExecute {
            if (!sableLoaded()) return@thenExecute
            if (baselineY.isNaN()) throw GameTestAssertException("The camera feed never started before launch")
        }

        // Launch: a sustained vertical burn, exactly how a computer pilots a rocket (single impulses are heavily
        // damped by the physics engine). Sable 2.0.3's selector parser has no UUID form and every single-target
        // selector needs a player, so @e (all sub-levels in the level) is the only console-safe choice; our
        // assertions only follow channel 88's camera, so stray structures getting the same shove are harmless.
        repeat(20) {
            thenExecute {
                if (!sableLoaded()) return@thenExecute
                val server = context.level.server
                server.commands.performPrefixedCommand(
                    server.createCommandSourceStack(),
                    "sable physics impulse @e linear 0.0 3000.0 0.0 global",
                )
            }
            thenIdle(10)
        }
        thenExecute { if (sableLoaded()) screenshot("sable_flight.during.png") }
        thenIdle(10)
        thenOnClient {
            if (!sableLoaded()) return@thenOnClient
            val config = RemoteViewCache.getView(CHANNEL)?.config()
            duringY = config?.cameraPos()?.y ?: Double.NaN
            duringOnStructure = config?.localPos()?.isPresent ?: false
            duringTracked = config?.localPos()?.map { SableCompanion.INSTANCE.getContainingClient(it) != null }?.orElse(false) ?: false
        }
        thenExecute {
            if (!sableLoaded()) return@thenExecute
            if (duringY.isNaN()) throw GameTestAssertException("The camera feed died mid-flight")
            if (duringY < baselineY + 200) {
                throw GameTestAssertException("The camera only climbed from $baselineY to $duringY in 10s: the structure froze or was unloaded")
            }
            if (!duringOnStructure) throw GameTestAssertException("The camera no longer reports riding a structure mid-flight")
            if (!duringTracked) throw GameTestAssertException("The watcher's client no longer tracks the flying structure (it vanished)")
        }

        // Ten more: the crashes historically happened in this window.
        thenIdle(200)
        thenExecute { if (sableLoaded()) screenshot("sable_flight.after.png") }
        thenIdle(10)
        thenOnClient {
            if (!sableLoaded()) return@thenOnClient
            val view = RemoteViewCache.getView(CHANNEL)
            val config = view?.config()
            afterY = config?.cameraPos()?.y ?: Double.NaN
            afterTracked = config?.localPos()?.map { SableCompanion.INSTANCE.getContainingClient(it) != null }?.orElse(false) ?: false
            // The feed itself, for post-mortems.
            view?.renderer()?.capture(160, 90)?.let {
                java.io.File(minecraft.gameDirectory, "sable_flight_feed.rgb332").writeBytes(it)
            }
        }
        thenOnClient { minecraft.options.hideGui = false }
        thenExecute {
            if (!sableLoaded()) return@thenExecute
            if (afterY.isNaN()) throw GameTestAssertException("The camera feed died in the 10-20s window")
            if (afterY < duringY + 200) {
                throw GameTestAssertException("The camera stopped climbing between 10s ($duringY) and 20s ($afterY): the structure froze or was unloaded")
            }
            if (!afterTracked) throw GameTestAssertException("The watcher's client lost the structure in the 10-20s window")
        }
    }

    private fun screenshot(name: String) {
        Minecraft.getInstance().submit {
            net.minecraft.client.Screenshot.grab(
                Minecraft.getInstance().gameDirectory, name, Minecraft.getInstance().mainRenderTarget,
            ) { }
        }
    }

    private fun sableLoaded() = SableCompanion.INSTANCE !is DefaultSableCompanion
}
