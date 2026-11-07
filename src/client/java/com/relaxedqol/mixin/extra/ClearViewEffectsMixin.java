package com.relaxedqol.mixin.extra;

import com.mojang.blaze3d.vertex.PoseStack;
import com.relaxedqol.client.module.modules.render.ClearView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clear View, matched to how 26.2 draws these (the fire is built later in a lambda): the fire's height constant
 * is lowered inside that lambda, Off skips the fire, and the underwater overlay is skipped. Optional (require = 0).
 * A second way to lower the fire (the whole fire overlay moved down before it is submitted), used only when the
 * first one is not reached in this game build - normal fire and soul fire alike.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ClearViewEffectsMixin {
    @Unique
    private static boolean relaxed$pushed;

    @ModifyConstant(require = 0,
        method = "lambda$submitFire$0(Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V",
        constant = @Constant(floatValue = -0.3F))
    private static float relaxed$lowerFire(float original) {
        ClearView.fireConstantSeen();
        ClearView.fireHookSeen();
        return original - ClearView.fireOffset();
    }

    @Inject(require = 0, method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void relaxed$noFire(CallbackInfo ci) {
        ClearView.fireHookSeen();
        if (ClearView.fireMode() == 2) ci.cancel();
    }

    @Inject(require = 0,
        method = "submitFire(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V",
        at = @At("HEAD"))
    private static void relaxed$lowerFireFallback(PoseStack pose, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
        relaxed$pushed = false;
        if (ClearView.fireMode() != 1 || ClearView.fireConstantWorks()) return;
        pose.pushPose();
        pose.translate(0f, -ClearView.fireOffset(), 0f);
        relaxed$pushed = true;
    }

    @Inject(require = 0,
        method = "submitFire(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V",
        at = @At("RETURN"))
    private static void relaxed$lowerFireFallbackEnd(PoseStack pose, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (!relaxed$pushed) return;
        relaxed$pushed = false;
        pose.popPose();
    }

    @Inject(require = 0,
        method = "submitWater(Lnet/minecraft/client/Minecraft;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
        at = @At("HEAD"), cancellable = true)
    private static void relaxed$noWater(Minecraft client, PoseStack pose, SubmitNodeCollector collector, CallbackInfo ci) {
        if (ClearView.noWaterOverlay()) ci.cancel();
    }
}
