package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.module.modules.lovercraft.KeepServerPacks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * KeepServerPacks: a pack the server sends that is still loaded from last time (same id, same hash) is not
 * loaded again - the server is told it is loaded. Optional (extra mixins, require 0).
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl")
public abstract class ResourcePackPushMixin {
    @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true, require = 0)
    private void relaxed$skipKnownPack(@Coerce Object packet, CallbackInfo ci) {
        if (KeepServerPacks.skipPush(this, packet)) ci.cancel();
    }

    @Inject(method = "handleResourcePackPop", at = @At("HEAD"), cancellable = true, require = 0)
    private void relaxed$keepOnPop(@Coerce Object packet, CallbackInfo ci) {
        if (KeepServerPacks.skipPop(packet)) ci.cancel();
    }
}
