package com.relaxedqol.mixin.extra;

import com.llamalad7.mixinextras.sugar.Local;
import com.relaxedqol.client.util.Culling;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** FPS Boost: an entity completely hidden behind solid blocks is not drawn. Optional (require = 0). */
@Mixin(EntityRenderer.class)
public abstract class CullingEntityMixin {
    @Inject(require = 0, method = "shouldRender", at = @At("RETURN"), cancellable = true)
    private void relaxed$cullHidden(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true) Entity entity) {
        // Only things the game would draw anyway (inside your view) are checked
        if (Boolean.TRUE.equals(cir.getReturnValue()) && Culling.entityHidden(entity)) cir.setReturnValue(false);
    }
}
