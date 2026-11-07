package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.util.Culling;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** FPS Boost: particles that would appear where you cannot see them are not made. Optional (require = 0). */
@Mixin(ParticleEngine.class)
public abstract class CullingParticleMixin {
    @Inject(require = 0,
        method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
        at = @At("HEAD"), cancellable = true)
    private void relaxed$cullHiddenParticle(ParticleOptions options, double x, double y, double z, double xa, double ya, double za,
                                            CallbackInfoReturnable<Particle> cir) {
        if (Culling.particleHidden(options, x, y, z)) cir.setReturnValue(null);
    }
}
