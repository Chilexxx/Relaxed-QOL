package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.module.modules.lovercraft.KeepServerPacks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * KeepServerPacks: leaving a lover server keeps its packs loaded; joining anything else removes them first.
 * Optional (extra mixins, require 0): when a method is not found, the game simply behaves as normal.
 */
@Mixin(targets = "net.minecraft.client.resources.server.DownloadedPackSource")
public abstract class DownloadedPackSourceMixin {
    @Inject(method = "cleanupAfterDisconnect", at = @At("HEAD"), cancellable = true, require = 0)
    private void relaxed$keepPacks(CallbackInfo ci) {
        if (KeepServerPacks.keepOnLeave()) ci.cancel();
    }

    @Inject(method = "configureForServerControl", at = @At("HEAD"), require = 0)
    private void relaxed$joinServer(CallbackInfo ci) {
        KeepServerPacks.beforeJoin(this, false);
    }

    @Inject(method = "configureForLocalWorld", at = @At("HEAD"), require = 0)
    private void relaxed$joinWorld(CallbackInfo ci) {
        KeepServerPacks.beforeJoin(this, true);
    }
}
