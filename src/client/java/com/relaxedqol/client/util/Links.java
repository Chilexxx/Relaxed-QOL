package com.relaxedqol.client.util;

import net.minecraft.util.Util;

/** Opens a web link in the browser (copied to the clipboard when no browser opens). */
public final class Links {
    private Links() {}

    public static void open(String url) {
        try {
            Util.getPlatform().openUri(url);
        } catch (RuntimeException e) {
            ChatUtil.send("§7Could not open the browser - link copied: §f" + url);
            net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(url);
        }
    }
}
