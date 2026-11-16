package com.relaxedqol.mixin;

import com.relaxedqol.client.schematic.PlaceMode;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    /** Schematic placement / area selection: its keys (E, Esc, R, G, the arrows...) are its own while it is on. */
    @Inject(require = 0, method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void relaxed$placeModeKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (PlaceMode.key(action, event.key())) ci.cancel();
    }
}
