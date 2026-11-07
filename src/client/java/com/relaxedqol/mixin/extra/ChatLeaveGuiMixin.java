package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.module.modules.render.Chat;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The HUD's "you left the server" reset (Hud.onDisconnected) clears the chat: marked, so Keep On Leave can skip that. */
@Mixin(Hud.class)
public abstract class ChatLeaveGuiMixin {
    @Inject(method = "onDisconnected", at = @At("HEAD"), require = 0)
    private void relaxed$leaveStart(CallbackInfo ci) {
        if (Chat.leaving == 0) Chat.saveForLeave();
        Chat.leaving++;
    }

    @Inject(method = "onDisconnected", at = @At("RETURN"), require = 0)
    private void relaxed$leaveEnd(CallbackInfo ci) {
        Chat.leaving = Math.max(0, Chat.leaving - 1);
    }
}
