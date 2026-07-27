// SPDX-FileCopyrightText: 2026 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package dan200.computercraft.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TrackingEmitter;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Queue;

/**
 * Lets a puppet level swap its own particle collections (and level) into the global particle engine while it
 * spawns, ticks or renders. There can only ever be one {@link ParticleEngine}: constructing another destroys the
 * stitched particle atlas, so puppet particles share the real engine and just bring their own storage.
 */
@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessor {
    @Accessor("particles")
    Map<ParticleRenderType, Queue<Particle>> computercraft$getParticles();

    @Accessor("particles")
    @Mutable
    void computercraft$setParticles(Map<ParticleRenderType, Queue<Particle>> particles);

    @Accessor("particlesToAdd")
    Queue<Particle> computercraft$getParticlesToAdd();

    @Accessor("particlesToAdd")
    @Mutable
    void computercraft$setParticlesToAdd(Queue<Particle> particles);

    @Accessor("trackingEmitters")
    Queue<TrackingEmitter> computercraft$getTrackingEmitters();

    @Accessor("trackingEmitters")
    @Mutable
    void computercraft$setTrackingEmitters(Queue<TrackingEmitter> emitters);

    @Accessor("level")
    @Nullable
    ClientLevel computercraft$getLevel();

    @Accessor("level")
    void computercraft$setLevel(@Nullable ClientLevel level);
}
