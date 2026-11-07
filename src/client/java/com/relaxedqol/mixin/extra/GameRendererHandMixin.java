package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.modules.render.Freelook;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freelook: no hand / held item in front of the camera while freelooking (Hide Hand). */
@Mixin(GameRenderer.class)
public abstract class GameRendererHandMixin {
    @Inject(require = 0, method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void relaxed$hideHand(CallbackInfo ci) {
        Freelook freelook = RelaxedQolClient.enabled(Freelook.class);
        if (freelook != null && freelook.isLooking() && freelook.hidesHand()) ci.cancel();
    }
}
