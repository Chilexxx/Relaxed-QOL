package com.relaxedqol.mixin;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.modules.render.Fullbright;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapRenderStateExtractorMixin {
    /** Fullbright: brighten the lightmap directly, no potion effect involved. */
    @Inject(method = "extract", at = @At("TAIL"))
    private void relaxed$fullbright(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
        if (!renderState.needsUpdate) return;
        Fullbright fullbright = RelaxedQolClient.enabled(Fullbright.class);
        if (fullbright == null) return;

        if (fullbright.removeDarkness()) renderState.darknessEffectScale = 0.0F;
        if (!fullbright.fullMode()) {
            // Gamma mode: brightness far above the options maximum (1.0 = "Bright")
            renderState.brightness = Math.max(renderState.brightness, fullbright.gammaValue());
            return;
        }
        // Full mode: the lightmap is scaled so the brightest channel is 1.0 everywhere (same maths the
        // shader uses for night vision, but without the effect, its icon or its flicker when it runs out)
        renderState.brightness = 1.0F;
        renderState.nightVisionEffectIntensity = 1.0F;
        renderState.nightVisionColor = LightmapRenderStateExtractor.WHITE;
        renderState.ambientColor = LightmapRenderStateExtractor.WHITE;
        renderState.bossOverlayWorldDarkening = 0.0F;
    }
}
