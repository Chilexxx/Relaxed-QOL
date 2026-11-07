package com.relaxedqol.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * PlayerAlert's background "/msg NAME" tab-completion check: its answers are caught here (ConnectionMixin) so they never pop up in your chat box. Nothing is delayed, cancelled or changed.
 */
public final class PacketManager {
    private PacketManager() {}

    /** Only the client's in-game connection is looked at (not login, config, or the integrated server's side). */
    private static boolean isGameConnection(Connection connection) {
        return connection.getReceiving() == PacketFlow.CLIENTBOUND && connection.getPacketListener() instanceof ClientPacketListener;
    }

    /** Network thread. Returns true for answers to our own background completion requests (they are dropped). */
    public static boolean onReceive(Connection connection, Packet<?> packet) {
        if (!isGameConnection(connection)) return false;
        com.relaxedqol.client.util.TabInfo.onPacket(packet);
        return com.relaxedqol.client.util.SuggestionProbe.onReceive(packet);
    }

    /** Runs the action (nothing is held back). */
    public static void withBypass(Runnable action) {
        action.run();
    }

    /** Sends a packet right away. */
    public static void sendDirect(Packet<?> packet) {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener == null) return;
        listener.getConnection().send(packet);
    }
}
