package com.relaxedqol.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class ChatUtil {
    private static final String PREFIX = "§8[§dRelaxed§8] §r";

    /** LoverFella Mode's tag. */
    private static final String LOVER_PREFIX = "§8[§c♥ §bLC§8] §r";

    private ChatUtil() {}

    /** Prints a client-side message in chat. Supports § colour codes. */
    public static void send(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            String prefix = com.relaxedqol.client.gui.LoverFx.chatTag() ? LOVER_PREFIX : PREFIX;
            mc.player.sendSystemMessage(Component.literal(prefix + message));
        }
    }
}
