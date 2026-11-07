package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.module.modules.lovercraft.KeepServerPacks;
import com.relaxedqol.client.module.modules.render.Chat;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaving a server: marks the moment (Chat's Keep On Leave skips the chat clear that comes with it) and lets
 * KeepServerPacks keep LoverCraft's packs (the game's own "clear downloaded packs" is skipped then).
 * Optional (extra mixins, require 0).
 */
@Mixin(Minecraft.class)
public abstract class ChatLeaveMixin {
    @Inject(method = {"disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", "clearClientLevel"}, at = @At("HEAD"), require = 0)
    private void relaxed$leaveStart(CallbackInfo ci) {
        if (Chat.leaving == 0) Chat.saveForLeave();
        Chat.leaving++;
    }

    @Inject(method = {"disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", "clearClientLevel"}, at = @At("RETURN"), require = 0)
    private void relaxed$leaveEnd(CallbackInfo ci) {
        Chat.leaving = Math.max(0, Chat.leaving - 1);
    }

    @Inject(method = "clearDownloadedResourcePacks", at = @At("HEAD"), cancellable = true, require = 0)
    private void relaxed$keepPacks(CallbackInfo ci) {
        if (KeepServerPacks.keepOnLeave()) ci.cancel();
    }
}
