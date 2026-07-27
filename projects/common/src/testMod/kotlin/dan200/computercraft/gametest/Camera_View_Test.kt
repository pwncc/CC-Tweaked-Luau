// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.gametest

import dan200.computercraft.gametest.api.ClientGameTest
import dan200.computercraft.gametest.api.getBlockEntity
import dan200.computercraft.gametest.api.positionAtArmorStand
import dan200.computercraft.gametest.api.sequence
import dan200.computercraft.gametest.api.thenOnClient
import net.minecraft.client.Minecraft
import dan200.computercraft.shared.ModRegistry
import dan200.computercraft.shared.peripheral.camera.CameraBlock
import dan200.computercraft.shared.peripheral.monitor.MonitorBlock
import dan200.computercraft.shared.peripheral.monitor.MonitorPeripheral
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks

/**
 * Renders real camera views on a monitor and screenshots them: the closest thing to a human looking at the screen.
 */
class Camera_View_Test {
    /**
     * A camera looks at a colourful wall; a monitor in front of the player shows its channel. The screenshot
     * should show the wall's colours on the monitor.
     */
    @ClientGameTest(timeoutTicks = 1200)
    fun Live_view(context: GameTestHelper) = context.sequence {
        thenExecute {
            // A colourful wall for the camera to look at.
            context.setBlock(BlockPos(1, 1, 4), Blocks.GOLD_BLOCK)
            context.setBlock(BlockPos(2, 1, 4), Blocks.EMERALD_BLOCK)
            context.setBlock(BlockPos(3, 1, 4), Blocks.REDSTONE_BLOCK)
            context.setBlock(BlockPos(1, 2, 4), Blocks.LAPIS_BLOCK)
            context.setBlock(BlockPos(2, 2, 4), Blocks.GOLD_BLOCK)
            context.setBlock(BlockPos(3, 2, 4), Blocks.EMERALD_BLOCK)

            // A sheep poses in front of the wall, proving entities render in the view.
            context.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.SHEEP, BlockPos(2, 1, 3))

            // A two-tall monitor at a comfortable viewing distance, with the camera perched on top of it,
            // looking over the sheep at the wall.
            val monitorState = ModRegistry.Blocks.MONITOR_ADVANCED.get().defaultBlockState().setValue(MonitorBlock.FACING, Direction.NORTH)
            context.setBlock(BlockPos(2, 1, 2), monitorState)
            context.setBlock(BlockPos(2, 2, 2), monitorState)
            context.setBlock(
                BlockPos(2, 3, 2),
                ModRegistry.Blocks.CAMERA.get().defaultBlockState().setValue(CameraBlock.FACING, Direction.SOUTH),
            )

            val camera = context.getBlockEntity(BlockPos(2, 3, 2), ModRegistry.BlockEntities.CAMERA.get())
            camera.setChannel(77)
            // Wide angle with a downward tilt: sheep front and centre, wall colours behind it.
            camera.setFov(100f)
            camera.setRotation(0f, 30f)
            val monitor = context.getBlockEntity(BlockPos(2, 2, 2), ModRegistry.BlockEntities.MONITOR_ADVANCED.get())
            (monitor.peripheral() as MonitorPeripheral).setChannel(77)

            context.positionAtArmorStand()
        }
        // Let the watch handshake, chunk zones and the live renderer spin up. The live view re-renders (and its
        // zone chunks re-mesh) continuously, so the usual wait-for-stable-rendering screenshot helper never
        // settles; a fixed warm-up and a direct capture does the job.
        thenIdle(150)
        thenOnClient { minecraft.options.hideGui = true }
        thenIdle(5)
        thenExecute {
            val name = "camera_view_test.live_view.png"
            Minecraft.getInstance().submit {
                net.minecraft.client.Screenshot.grab(
                    Minecraft.getInstance().gameDirectory, name, Minecraft.getInstance().mainRenderTarget,
                ) { }
            }
        }
        thenIdle(10)
        thenOnClient { minecraft.options.hideGui = false }
        // Also dump the live renderer's own view of the scene, for diagnosing what the feed contains.
        thenOnClient {
            val view = dan200.computercraft.client.render.remoteview.RemoteViewCache.getView(77)
            if (view != null) {
                val frame = view.renderer().capture(160, 90)
                if (frame != null) {
                    java.io.File(minecraft.gameDirectory, "camera_feed.rgb332").writeBytes(frame)
                }
            }
        }
    }
}
