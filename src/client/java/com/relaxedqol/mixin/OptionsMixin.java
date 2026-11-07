package com.relaxedqol.mixin;

import com.relaxedqol.client.ClientTag;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client tag: the client information sent to the server (language, view distance, skin layers...) gets the spare
 * skin-layer bit set while "Show Client Tag" is on, so other Relaxed users can show the "Relaxed" title.
 * require = 0: if the method is ever renamed, the game simply sends the normal information.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Inject(method = "buildPlayerInformation", at = @At("RETURN"), cancellable = true, require = 0)
    private void relaxed$clientTag(CallbackInfoReturnable<Object> cir) {
        Object marked = ClientTag.withMark(cir.getReturnValue());
        if (marked != cir.getReturnValue()) cir.setReturnValue(marked);
    }
}
