package com.relaxedqol.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * On Minecraft's loading screen (the red / black one with the progress bar), when loading takes a while: a small card
 * that says what is going on - resource packs are loading, how many, how long it has taken so far, and whether the
 * client is putting your saved server packs back (Keep Server Packs) - so a long start doesn't look stuck.
 */
public final class LoadingInfo {
    private LoadingInfo() {}

    private static long startedAt;
    private static long lastDrawn;
    private static int packs = -1;

    /** From the loading screen, every frame. */
    public static void draw(GuiGraphicsExtractor g) {
        try {
            long now = System.currentTimeMillis();
            if (now - lastDrawn > 1500) {
                // A new loading screen
                startedAt = now;
                packs = countPacks();
            }
            lastDrawn = now;
            long took = now - startedAt;
            if (took < 2500) return; // a quick reload: nothing to explain
            Minecraft mc = Minecraft.getInstance();
            var font = mc.font;
            boolean server = restoring();
            String title = server ? "Loading your server resource packs" : "Loading resource packs";
            String detail = (packs > 0 ? packs + (packs == 1 ? " pack" : " packs") + " - " : "")
                + "this can take a while with big packs";
            String time = (took / 1000) + " s";
            String hint = server ? "Keep Server Packs: they stay loaded, so joining the server is instant"
                : "The game is not stuck - it is still working";
            int w = Math.max(Math.max(font.width(title) + font.width(time) + 30, font.width(detail) + 24), font.width(hint) + 24);
            int h = 44;
            int x = (g.guiWidth() - w) / 2;
            int y = 10;
            // A calm dark card with a thin light edge and a slowly moving accent line
            g.fill(x, y, x + w, y + h, 0xC8101116);
            g.fill(x, y, x + w, y + 1, 0x40FFFFFF);
            g.fill(x, y + h - 1, x + w, y + h, 0x30000000);
            g.fill(x, y, x + 1, y + h, 0x26FFFFFF);
            g.fill(x + w - 1, y, x + w, y + h, 0x26FFFFFF);
            int span = w - 16;
            int seg = Math.max(20, span / 4);
            int off = (int) ((now / 8) % (span + seg)) - seg;
            int a = Math.max(0, off);
            int b = Math.min(span, off + seg);
            g.fill(x + 8, y + h - 5, x + 8 + span, y + h - 4, 0x20FFFFFF);
            if (b > a) g.fill(x + 8 + a, y + h - 5, x + 8 + b, y + h - 4, 0xFFB794F6);
            g.text(font, title, x + 10, y + 7, 0xFFFFFFFF, false);
            g.text(font, time, x + w - 10 - font.width(time), y + 7, 0xFFA0A3AA, false);
            g.text(font, detail, x + 10, y + 19, 0xFFC9CBD0, false);
            g.text(font, hint, x + 10, y + 29, 0xFF8A8D96, false);
        } catch (RuntimeException | LinkageError ignored) {
            // the font or the game isn't ready yet: just Minecraft's own loading screen
        }
    }

    private static boolean restoring() {
        try {
            return com.relaxedqol.client.module.modules.lovercraft.KeepServerPacks.restoring();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** How many resource packs are on (Minecraft's own built-in ones not counted), or -1. */
    private static int countPacks() {
        try {
            int n = 0;
            for (String id : Minecraft.getInstance().getResourcePackRepository().getSelectedIds()) {
                if (!id.equals("vanilla") && !id.startsWith("fabric") && !id.equals("programmer_art")) n++;
            }
            return n;
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }
}
