// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets the camera renderer temporarily disable the entity glow-outline pipeline. Clearing the (window-sized)
 * outline target mid-pass silently resets the GL viewport to the window's dimensions, which would skew everything
 * drawn after the terrain in an off-screen camera render.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("entityTarget")
    @Nullable
    RenderTarget computercraft$getEntityTarget();

    @Accessor("entityTarget")
    void computercraft$setEntityTarget(@Nullable RenderTarget target);

    @Invoker("setSectionDirty")
    void computercraft$setSectionDirty(int x, int y, int z, boolean important);
}
