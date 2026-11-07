package com.relaxedqol.mixin;

import com.relaxedqol.client.PacketManager;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    /**
     * Every incoming packet arrives here on the network thread before it is handled. Only the answers to
     * PlayerAlert's own background tab-completion checks are taken out (so they never show in your chat box).
     */
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD"), cancellable = true)
    private void relaxed$onReceive(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (PacketManager.onReceive((Connection) (Object) this, packet)) {
            ci.cancel();
        }
    }
}
