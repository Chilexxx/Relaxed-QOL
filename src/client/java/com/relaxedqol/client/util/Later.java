package com.relaxedqol.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

import java.util.ArrayDeque;

/**
 * Runs something on a later tick, once no other screen is open (chat commands that open a window: the chat
 * screen closes right after the command, which would close the window too).
 */
public final class Later {
    private static final ArrayDeque<Runnable> QUEUE = new ArrayDeque<>();

    private Later() {}

    public static void run(Runnable r) {
        QUEUE.add(r);
    }

    public static void tick() {
        if (QUEUE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null && !(mc.gui.screen() instanceof ChatScreen)) return;
        if (mc.gui.screen() instanceof ChatScreen) return; // wait for the chat to close
        Runnable r = QUEUE.poll();
        try {
            r.run();
        } catch (RuntimeException e) {
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("Later task failed", e);
        }
    }
}
