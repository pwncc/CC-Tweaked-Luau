// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin;

import dan200.computercraft.shared.camera.BroadcastChannels;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mirrors server-spawned particles (composters, potion effects, explosions and the like) to cross-dimension camera
 * viewers, whose puppet level cannot see the vanilla per-player particle sends.
 */
@Mixin(ServerLevel.class)
class CameraServerLevelMixin {
    @Inject(
        method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I",
        at = @At("HEAD")
    )
    @SuppressWarnings("UnusedMethod")
    private <T extends ParticleOptions> void computercraft$mirrorParticles(
        T type, double posX, double posY, double posZ, int particleCount,
        double xOffset, double yOffset, double zOffset, double speed,
        CallbackInfoReturnable<Integer> cir
    ) {
        BroadcastChannels.forwardEffect(
            (ServerLevel) (Object) this, posX, posY, posZ,
            new ClientboundLevelParticlesPacket(type, false, posX, posY, posZ, (float) xOffset, (float) yOffset, (float) zOffset, (float) speed, particleCount)
        );
    }
}
