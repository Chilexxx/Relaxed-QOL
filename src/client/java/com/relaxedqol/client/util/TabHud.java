package com.relaxedqol.client.util;

import net.minecraft.client.Minecraft;

/**
 * While you hold Tab (the player list) the HUD cards that would cover it step aside - the watermark / info
 * panel / module list, Notes, Keystrokes, the Players list... The scoreboard, the radar and the armor
 * durability card stay. Letting go of Tab brings everything back.
 */
public final class TabHud {
    private TabHud() {}

    public static boolean open() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.level != null && mc.gui.screen() == null && mc.options.keyPlayerList.isDown();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
