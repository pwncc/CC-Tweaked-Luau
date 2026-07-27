// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.gametest

import dan200.computercraft.gametest.api.sequence
import dan200.computercraft.shared.ModRegistry
import dan200.computercraft.shared.peripheral.camera.CameraBlockEntity
import dev.ryanhcode.sable.companion.SableCompanion
import dev.ryanhcode.sable.companion.impl.DefaultSableCompanion
import dev.ryanhcode.sable.companion.math.BoundingBox3d
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Tests cameras riding Sable physics structures: their reported view pose must be the structure's world-space
 * pose, and must follow as the structure moves.
 *
 * These tests are skipped (and pass trivially) when Sable itself is not on the classpath, so they run identically
 * on both loaders.
 */
class Sable_Test {
    /**
     * Assemble a floating pedestal-with-camera into a physics structure. The camera must report a world-space
     * pose at the structure's location, and as gravity pulls the (unsupported) structure down, the pose must
     * follow it.
     */
    @GameTest(timeoutTicks = 400)
    fun Camera_rides_structure(context: GameTestHelper) = context.sequence {
        var plotPos: BlockPos? = null
        var initialPose: Vec3? = null

        thenExecute {
            if (!sableLoaded()) return@thenExecute
            // A floating one-block pedestal with a camera on top: nothing supports it, so it falls when assembled.
            context.setBlock(BlockPos(2, 2, 2), Blocks.NETHERRACK)
            context.setBlock(BlockPos(2, 3, 2), ModRegistry.Blocks.CAMERA.get())

            val from = context.absolutePos(BlockPos(2, 2, 2))
            val to = context.absolutePos(BlockPos(2, 3, 2))
            val server = context.level.server
            server.commands.performPrefixedCommand(
                server.createCommandSourceStack(),
                "sable assemble area ${from.x} ${from.y} ${from.z} ${to.x} ${to.y} ${to.z}",
            )
        }
        // Assembly is asynchronous, so retry until the structure (and the camera inside it) turns up.
        thenWaitUntil {
            if (!sableLoaded()) return@thenWaitUntil
            val cameraWorld = Vec3.atCenterOf(context.absolutePos(BlockPos(2, 3, 2)))

            // The blocks have moved into the structure's plot grid; find the structure by its world-space bounds,
            // then the camera inside it by inverting the structure's pose.
            val subLevel = SableCompanion.INSTANCE
                .getAllIntersecting(context.level, BoundingBox3d(AABB.ofSize(cameraWorld, 4.0, 4.0, 4.0)))
                .firstOrNull() ?: throw GameTestAssertException("No sub-level was assembled at the camera")

            // The physics pose wobbles a fraction of a block, so search around the inverse-mapped position.
            val plotCentre = BlockPos.containing(subLevel.logicalPose().transformPositionInverse(cameraWorld))
            val plot = BlockPos.betweenClosedStream(plotCentre.offset(-1, -1, -1), plotCentre.offset(1, 1, 1))
                .filter { context.level.getBlockEntity(it) is CameraBlockEntity }
                .findFirst().map { it.immutable() }
                .orElseThrow { GameTestAssertException("No camera block entity in the sub-level plot around $plotCentre") }
            val camera = context.level.getBlockEntity(plot) as CameraBlockEntity

            val pose = camera.viewPosition
            if (pose.distanceTo(cameraWorld) > 1.5) {
                throw GameTestAssertException("Camera pose $pose is nowhere near its world position $cameraWorld")
            }
            plotPos = plot
            initialPose = pose
        }
        // The structure is a live physics body (gravity constantly nudges it); wait until the camera's reported
        // pose has visibly moved with it.
        thenWaitUntil {
            if (!sableLoaded()) return@thenWaitUntil
            val camera = context.level.getBlockEntity(plotPos!!) as? CameraBlockEntity
                ?: throw GameTestAssertException("The camera vanished from the sub-level plot")
            val pose = camera.viewPosition
            if (pose.distanceTo(initialPose!!) < 1.0e-4) {
                throw GameTestAssertException("Camera pose has not moved with its structure yet (still $pose)")
            }
        }
    }

    private fun sableLoaded() = SableCompanion.INSTANCE !is DefaultSableCompanion
}
