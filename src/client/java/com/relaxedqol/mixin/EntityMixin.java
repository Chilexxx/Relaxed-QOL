package com.relaxedqol.mixin;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.modules.render.Freelook;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
    /** Freelook: mouse movement turns the camera instead of your player (you keep walking where you were facing). */
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void relaxed$freelookTurn(double xo, double yo, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().player) return;
        Freelook freelook = RelaxedQolClient.enabled(Freelook.class);
        if (freelook != null && freelook.isLooking()) {
            freelook.turn(xo, yo);
            ci.cancel();
        }
    }
}
