package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.module.modules.misc.Screenshots;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Screenshots: the game's own "Saved screenshot as ..." line is left out while the client's screenshot line
 * (Copy / Open / Folder / Delete) is on - one line per screenshot instead of two. Optional (require = 0).
 */
@Mixin(ChatComponent.class)
public abstract class ScreenshotMessageMixin {
    @Inject(require = 0, method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"), cancellable = true)
    private void relaxed$hideVanillaScreenshotLine(Component message, MessageSignature signature, GuiMessageSource source,
                                                   GuiMessageTag tag, CallbackInfo ci) {
        if (Screenshots.isVanillaScreenshotLine(message)) ci.cancel();
    }
}
