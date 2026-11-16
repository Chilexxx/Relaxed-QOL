package com.relaxedqol.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GlyphRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Smooth Font: Minecraft draws every piece of menu / HUD text with blocky (nearest) sampling, which leaves a smooth
 * font grainy and jagged. Text from the smooth font's own textures is drawn with smooth sampling instead; every other
 * font (Minecraft's own included) is left exactly as it is.
 */
@Mixin(GlyphRenderState.class)
public abstract class GlyphRenderStateMixin {
    @Inject(method = "textureSetup", at = @At("HEAD"), cancellable = true)
    private void relaxed$smoothFont(CallbackInfoReturnable<TextureSetup> cir) {
        GpuTextureView view = ((GlyphRenderState) (Object) this).renderable().textureView();
        GpuSampler sampler = com.relaxedqol.client.util.SmoothFont.samplerFor(view);
        if (sampler != null) cir.setReturnValue(TextureSetup.singleTextureWithLightmap(view, sampler));
    }
}
