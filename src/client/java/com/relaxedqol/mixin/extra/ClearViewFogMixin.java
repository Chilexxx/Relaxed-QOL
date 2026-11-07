package com.relaxedqol.mixin.extra;

import com.llamalad7.mixinextras.sugar.Local;
import com.relaxedqol.client.module.modules.render.ClearView;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Clear View > No Fog: the distance fog pushed far away (the exact 26.2 method). Optional (require = 0). */
@Mixin(FogRenderer.class)
public abstract class ClearViewFogMixin {
    @Inject(require = 0,
        method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/fog/FogData;renderDistanceEnd:F", opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER))
    private void relaxed$noFog(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount,
                               ClientLevel level, CallbackInfoReturnable<FogData> cir, @Local FogData fog) {
        if (!ClearView.noFog()) return;
        fog.renderDistanceStart = 1000000;
        fog.renderDistanceEnd = 1000000;
    }
}
