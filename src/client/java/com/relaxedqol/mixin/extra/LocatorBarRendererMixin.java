package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.module.modules.render.LocatorBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LocatorBar > Hide Vanilla Dots: the game's locator bar draws its coloured waypoint dots in this method (the
 * bar itself is drawn separately and stays). Optional: if the game names it differently, nothing happens.
 */
@Pseudo
@Mixin(targets = "net.minecraft.client.gui.contextualbar.LocatorBarRenderer")
public abstract class LocatorBarRendererMixin {
    @Inject(require = 0, method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void relaxed$hideDots(CallbackInfo ci) {
        if (LocatorBar.hidesVanillaDots()) ci.cancel();
    }
}
