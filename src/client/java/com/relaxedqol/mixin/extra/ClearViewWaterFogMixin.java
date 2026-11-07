package com.relaxedqol.mixin.extra;

import com.llamalad7.mixinextras.sugar.Local;
import com.relaxedqol.client.module.modules.render.ClearView;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clear View > Clear Water: underwater you see far, not just a few blocks into murky blue (the water fog is
 * pushed far away). Its own class, so the rest of Clear View keeps working if this game build names things
 * differently. Optional (require = 0).
 */
@Mixin(FogRenderer.class)
public abstract class ClearViewWaterFogMixin {
    @Inject(require = 0,
        method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F", opcode = Opcodes.PUTFIELD,
            ordinal = 0, shift = At.Shift.AFTER))
    private void relaxed$clearWater(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount,
                                    ClientLevel level, CallbackInfoReturnable<FogData> cir, @Local FogData fog,
                                    @Local(name = "fogType") FogType fogType) {
        if (fogType != FogType.WATER || !ClearView.clearWater()) return;
        fog.environmentalStart = 1000000;
        fog.environmentalEnd = 1000000;
        fog.renderDistanceStart = 1000000;
        fog.renderDistanceEnd = 1000000;
    }
}
