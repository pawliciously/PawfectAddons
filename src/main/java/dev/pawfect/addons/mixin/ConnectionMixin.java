package dev.pawfect.addons.mixin;

import dev.pawfect.addons.features.dev.PacketLog;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Shadow
    public abstract PacketFlow getReceiving();

    @Shadow
    public abstract PacketFlow getSending();

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD")
    )
    private void pawfectaddons$inbound(ChannelHandlerContext context, Packet<?> packet, CallbackInfo callback) {
        if (!PacketLog.capturing) {
            return;
        }
        try {
            if (getReceiving() == PacketFlow.CLIENTBOUND) {
                PacketLog.record(true, packet);
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(
        method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
        at = @At("HEAD")
    )
    private void pawfectaddons$outbound(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo callback) {
        if (!PacketLog.capturing) {
            return;
        }
        try {
            if (getSending() == PacketFlow.SERVERBOUND) {
                PacketLog.record(false, packet);
            }
        } catch (Throwable ignored) {
        }
    }
}
