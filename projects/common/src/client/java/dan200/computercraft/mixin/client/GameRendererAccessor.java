// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the camera renderer temporarily swap the "main" camera: parts of the render pipeline query it directly
 * rather than using the camera they were passed, and during an off-screen camera render those must see the view's
 * camera, not the player's.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("mainCamera")
    @Mutable
    void computercraft$setMainCamera(Camera camera);
}
