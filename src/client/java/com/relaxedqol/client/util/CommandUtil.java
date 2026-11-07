package com.relaxedqol.client.util;

import com.relaxedqol.client.RelaxedQolClient;
import net.minecraft.client.Minecraft;

/**
 * Sends a line the way the chat box would: "/command" goes to the server as a command, ".command" runs one of the
 * client's own commands, anything else is sent as a chat message. Used by the Quick Panel buttons and the Radar window
 * (/profile).
 */
public final class CommandUtil {
    private CommandUtil() {}

    /** Returns false when there is no server connection to send it to. */
    public static boolean run(String line) {
        String text = line == null ? "" : line.trim();
        if (text.isEmpty()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (text.startsWith(".")) {
            if (RelaxedQolClient.commandManager != null) RelaxedQolClient.commandManager.handle(text);
            return true;
        }
        if (mc.getConnection() == null || mc.player == null) return false;
        if (text.startsWith("/")) {
            String command = text.substring(1).trim();
            if (command.isEmpty()) return false;
            try {
                mc.getConnection().sendCommand(command);
            } catch (RuntimeException e) {
                // e.g. a signed argument without a chat session: send it unsigned
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(command));
            }
        } else {
            mc.getConnection().sendChat(text);
        }
        return true;
    }

    /** Like {@link #run} but always as a server command ("vanish" and "/vanish" both send /vanish). */
    public static boolean command(String command) {
        String text = command == null ? "" : command.trim();
        if (text.isEmpty()) return false;
        return run(text.startsWith("/") || text.startsWith(".") ? text : "/" + text);
    }
}
