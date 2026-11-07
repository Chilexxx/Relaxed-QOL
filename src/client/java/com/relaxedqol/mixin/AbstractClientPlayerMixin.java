package com.relaxedqol.mixin;

import com.relaxedqol.client.module.modules.render.Zoom;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom: scales the field-of-view multiplier (the one the spyglass and sprinting use) instead of the whole-number
 * FOV setting, so zooming is perfectly smooth - the game itself eases this value every frame.
 * require = 0: if the method is renamed, Zoom falls back to changing the FOV setting.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
    @Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true, require = 0)
    private void relaxed$zoom(CallbackInfoReturnable<Float> cir) {
        Zoom.hookSeen();
        float divisor = Zoom.divisor();
        if (divisor > 1.0001f) cir.setReturnValue(cir.getReturnValueF() / divisor);
    }
}
