package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.util.SmoothFont;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smooth Font: inside a client window every piece of text is switched to the smooth font made for the size it
 * is drawn at. Plain strings and components are turned into character sequences the same way the game does, so all
 * three ways of drawing text end up in one place. Optional (require = 0).
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class SmoothTextMixin {
    @org.spongepowered.asm.mixin.Unique
    private boolean relaxed$snapped;

    /** The text's starting point on a whole screen pixel, so smooth letters are as crisp as they can be. */
    @Inject(require = 0, method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", at = @At("HEAD"))
    private void relaxed$snapStart(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow, CallbackInfo ci) {
        relaxed$snapped = false;
        if (!SmoothFont.active()) return;
        var pose = ((GuiGraphicsExtractor) (Object) this).pose();
        float gs = SmoothFont.guiScale();
        float sx = (pose.m00() * x + pose.m10() * y + pose.m20()) * gs;
        float sy = (pose.m01() * x + pose.m11() * y + pose.m21()) * gs;
        float dx = (Math.round(sx) - sx) / gs;
        float dy = (Math.round(sy) - sy) / gs;
        if (Math.abs(dx) < 0.001f && Math.abs(dy) < 0.001f) return;
        pose.pushMatrix();
        pose.translateLocal(dx, dy);
        relaxed$snapped = true;
    }

    @Inject(require = 0, method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", at = @At("RETURN"))
    private void relaxed$snapEnd(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow, CallbackInfo ci) {
        // Smart HUD: remember this text (as it was really drawn) so it can be repeated
        com.relaxedqol.client.util.HudCache.onText((GuiGraphicsExtractor) (Object) this, font, text, x, y, color, shadow);
        if (!relaxed$snapped) return;
        relaxed$snapped = false;
        ((GuiGraphicsExtractor) (Object) this).pose().popMatrix();
    }
    @ModifyVariable(require = 0,
        method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
        at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence relaxed$smoothText(FormattedCharSequence text) {
        if (text == null || !SmoothFont.active()) return text;
        return SmoothFont.wrap(text, SmoothFont.pixelsPer(((GuiGraphicsExtractor) (Object) this).pose()));
    }

    @Inject(require = 0, method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void relaxed$smoothString(Font font, String text, int x, int y, int color, boolean shadow, CallbackInfo ci) {
        if (text == null || !SmoothFont.active()) return;
        ci.cancel();
        ((GuiGraphicsExtractor) (Object) this).text(font, Component.literal(text).getVisualOrderText(), x, y, color, shadow);
    }

    @Inject(require = 0, method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", at = @At("HEAD"), cancellable = true)
    private void relaxed$smoothComponent(Font font, Component text, int x, int y, int color, boolean shadow, CallbackInfo ci) {
        if (text == null || !SmoothFont.active()) return;
        ci.cancel();
        ((GuiGraphicsExtractor) (Object) this).text(font, text.getVisualOrderText(), x, y, color, shadow);
    }
}
