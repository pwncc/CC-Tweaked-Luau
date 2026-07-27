// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the camera renderer temporarily swap the "main" render target: several vanilla render types bind the main
 * target mid-pass, so rendering a camera view off-screen requires the main target to <em>be</em> the off-screen
 * target for the duration.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("mainRenderTarget")
    @Mutable
    void computercraft$setMainRenderTarget(RenderTarget target);
}
