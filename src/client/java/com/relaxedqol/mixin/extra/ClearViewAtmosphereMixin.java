package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.module.modules.render.ClearView;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clear View > No Fog: the overworld's grey mist (also the thicker rain fog) is gone too. Only the open-air
 * fog - water, lava, powder snow, blindness and darkness keep their fog. Optional (require = 0).
 */
@Mixin(AtmosphericFogEnvironment.class)
public abstract class ClearViewAtmosphereMixin {
    @Inject(require = 0, method = "setupFog", at = @At("TAIL"))
    private void relaxed$noMist(FogData data, Camera camera, ClientLevel level, float viewDistance, DeltaTracker delta, CallbackInfo ci) {
        if (!ClearView.noFog()) return;
        data.environmentalStart = 1000000;
        data.environmentalEnd = 1000000;
    }
}
