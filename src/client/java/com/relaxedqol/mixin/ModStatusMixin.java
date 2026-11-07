package com.relaxedqol.mixin;

import com.relaxedqol.client.module.modules.client.ClickGUI;
import net.minecraft.util.ModCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hide Modded: no "(Modded)" on the title screen and no "*" in the window title. Both ask the mod check
 * "should this be reported as modified?" - with Hide Modded on the answer is no. require = 0 so a rename in a
 * future version can never stop the game from starting.
 */
@Mixin(ModCheck.class)
public abstract class ModStatusMixin {
    @Inject(method = "shouldReportAsModified", at = @At("HEAD"), cancellable = true, require = 0)
    private void relaxed$notModded(CallbackInfoReturnable<Boolean> cir) {
        if (ClickGUI.hideModded()) cir.setReturnValue(false);
    }
}
