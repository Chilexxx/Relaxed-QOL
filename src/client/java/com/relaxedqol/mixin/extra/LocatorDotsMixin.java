package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.module.modules.render.LocatorBar;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LocatorBar > Hide Vanilla Dots, the sure way: the game's locator dots (also the coloured ones a server
 * gives its own waypoints) and the up / down arrows are sprites called "hud/locator_bar_dot..." and
 * "hud/locator_bar_arrow...". While our LocatorBar is on, those sprites are simply not drawn; the bar itself and
 * everything else is untouched.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class LocatorDotsMixin {
    @Inject(require = 0, at = @At("HEAD"), cancellable = true, method = {
        "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
        "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
        "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIF)V"})
    private void relaxed$skipLocatorDot(CallbackInfo ci, @Local(argsOnly = true) Identifier sprite) {
        if (!LocatorBar.hideDotsNow || sprite == null) return;
        String path = sprite.getPath();
        if (path.contains("locator_bar_dot") || path.contains("locator_bar_arrow")) ci.cancel();
    }
}
