package com.relaxedqol.client.util;

import net.minecraft.client.Minecraft;

/**
 * While the F3 debug screen is open the whole client HUD steps aside (module list, info, radar, minimap,
 * notes, alerts, scoreboard...) so every F3 line can be read; closing F3 brings it all back.
 */
public final class DebugHud {
    private DebugHud() {}

    public static boolean open() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.debugEntries != null && mc.debugEntries.isOverlayVisible();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
