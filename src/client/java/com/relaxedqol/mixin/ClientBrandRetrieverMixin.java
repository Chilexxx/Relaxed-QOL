package com.relaxedqol.mixin;

import com.relaxedqol.client.module.modules.client.ClickGUI;
import net.minecraft.client.ClientBrandRetriever;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hide Modded (Settings panel, on by default): the game reports the "vanilla" brand everywhere - title screen,
 * F3, window title and the brand the server sees - instead of "fabric". require = 0: never stops the game starting.
 */
@Mixin(ClientBrandRetriever.class)
public abstract class ClientBrandRetrieverMixin {
    @Inject(method = "getClientModName", at = @At("HEAD"), cancellable = true, require = 0)
    private static void relaxed$vanillaBrand(CallbackInfoReturnable<String> cir) {
        if (ClickGUI.hideModded()) cir.setReturnValue("vanilla");
    }
}
