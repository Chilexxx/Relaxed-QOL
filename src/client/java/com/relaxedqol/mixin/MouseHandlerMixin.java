package com.relaxedqol.mixin;

import com.relaxedqol.client.schematic.PlaceMode;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    /** Schematic placement / area selection: a click picks a corner or locks the preview instead of hitting the world. */
    @Inject(require = 0, method = "onButton", at = @At("HEAD"), cancellable = true)
    private void relaxed$placeModeButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (PlaceMode.button(info.button(), action)) ci.cancel();
    }

    /** ...and the wheel turns the preview instead of the hotbar. */
    @Inject(require = 0, method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void relaxed$placeModeScroll(long window, double x, double y, CallbackInfo ci) {
        if (PlaceMode.scroll(y)) ci.cancel();
    }
}
