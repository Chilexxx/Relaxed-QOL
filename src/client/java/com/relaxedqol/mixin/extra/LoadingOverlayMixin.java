package com.relaxedqol.mixin.extra;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's loading screen: when it takes a while, a card on top says what is loading and for how long
 * (util/LoadingInfo). Optional (require 0): if the game changes this screen, it is just Minecraft's own again.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.LoadingOverlay")
public abstract class LoadingOverlayMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
    private void relaxed$loadingInfo(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        com.relaxedqol.client.util.LoadingInfo.draw(g);
    }
}
