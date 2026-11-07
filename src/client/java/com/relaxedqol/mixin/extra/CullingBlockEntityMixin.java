package com.relaxedqol.mixin.extra;

import com.mojang.blaze3d.vertex.PoseStack;
import com.relaxedqol.client.util.Culling;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** FPS Boost: a chest / sign / head... completely hidden behind solid blocks is not drawn. Optional (require = 0). */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class CullingBlockEntityMixin {
    @Inject(require = 0,
        method = "submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD"), cancellable = true)
    private <S extends BlockEntityRenderState> void relaxed$cullHidden(S state, PoseStack pose, SubmitNodeCollector collector,
                                                                       CameraRenderState camera, CallbackInfo ci) {
        if (state != null && Culling.blockHidden(state.blockPos, state)) ci.cancel();
    }
}
