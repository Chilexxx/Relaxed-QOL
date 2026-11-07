package com.relaxedqol.mixin.extra;

import com.mojang.blaze3d.vertex.PoseStack;
import com.relaxedqol.client.module.modules.render.ClearView;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clear View: no screen shake / tilt when you take damage (the exact 26.2 method). Optional (require = 0). */
@Mixin(GameRenderer.class)
public abstract class ClearViewCameraMixin {
    @Inject(require = 0,
        method = "bobHurt(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
        at = @At("HEAD"), cancellable = true)
    private void relaxed$noHurtShake(CameraRenderState camera, PoseStack pose, CallbackInfo ci) {
        if (ClearView.noHurtShake()) ci.cancel();
    }
}
