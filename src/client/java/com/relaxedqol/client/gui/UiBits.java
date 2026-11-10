package com.relaxedqol.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Small drawing pieces the newer windows share (profiles, style editor, what's new), each in the look of the style
 * that is on: Minecraft's own textures in Vanilla Style, see-through glass in Clean Style, the theme otherwise.
 */
public final class UiBits {
    private UiBits() {}

    /** A button drawn by the window itself (inside an overlay, where the window's own buttons can't go). */
    public static void button(GuiGraphicsExtractor g, Font font, String label, int x, int y, int w, int h, boolean hover,
                              boolean primary) {
        if (VanillaStyle.on()) {
            VanillaStyle.button(g, font, label, x, y, w, h, hover, false, true);
            return;
        }
        int accent = Theme.accent();
        int r = Math.min(h / 2, Theme.R_CONTROL);
        int bg = primary ? Theme.withAlpha(accent, hover ? 0.42f : 0.30f) : hover ? 0x22FFFFFF : 0x12FFFFFF;
        int edge = primary ? Theme.withAlpha(accent, 0.85f) : hover ? 0x4DFFFFFF : Theme.BORDER;
        Render2D.roundRect(g, x, y, w, h, r, bg);
        Render2D.roundOutline(g, x, y, w, h, r, edge);
        int text = primary ? Theme.mix(accent, 0xFFFFFFFF, 0.6f) : hover ? Theme.TEXT : Theme.TEXT_SECONDARY;
        g.centeredText(font, label, x + w / 2, y + (h - 8) / 2, text);
    }

    /** A list row's background: picked, under the mouse, or nothing. */
    public static void row(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean picked, boolean hover) {
        if (VanillaStyle.on()) {
            if (picked) VanillaStyle.selected(g, x, y, w, h);
            else if (hover) g.fill(x, y, x + w, y + h, 0x30FFFFFF);
            return;
        }
        if (picked) {
            Render2D.roundRect(g, x, y, w, h, Theme.R_CONTROL, Theme.withAlpha(Theme.accent(), 0.20f));
            Render2D.roundOutline(g, x, y, w, h, Theme.R_CONTROL, Theme.withAlpha(Theme.accent(), 0.55f));
        } else if (hover) {
            Render2D.roundRect(g, x, y, w, h, Theme.R_CONTROL, Theme.ROW_HOVER);
        }
    }

    /** A sunken area (a list, a card) inside a window. */
    public static void well(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (VanillaStyle.on()) {
            g.fill(x, y, x + w, y + h, 0x80000000);
            VanillaStyle.bevel(g, x, y, w, h);
            return;
        }
        Render2D.roundRect(g, x, y, w, h, Theme.R_ROW, CleanStyle.on() ? 0x24000000 : 0x1A000000);
        Render2D.roundOutline(g, x, y, w, h, Theme.R_ROW, Theme.HAIRLINE);
    }

    /** A small coloured tag ("IN USE", "NEW"...): light text on a soft tint of the colour. */
    public static int tag(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        int w = font.width(text) + 8;
        if (VanillaStyle.on()) {
            g.fill(x, y, x + w, y + 11, 0xFF000000);
            g.fill(x, y, x + 1, y + 11, color);
            g.text(font, text, x + 4, y + 2, 0xFFFFFFFF, true);
            return w;
        }
        Render2D.roundRect(g, x, y, w, 11, Math.min(5, Theme.R_CONTROL + 1), Theme.withAlpha(color, 0.22f));
        Render2D.roundOutline(g, x, y, w, 11, Math.min(5, Theme.R_CONTROL + 1), Theme.withAlpha(color, 0.55f));
        g.text(font, text, x + 4, y + 2, Theme.mix(color, 0xFFFFFFFF, 0.55f), false);
        return w;
    }

    /** The box a small question / confirmation floats in, over the window. */
    public static void dialog(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        Render2D.popup(g, x, y, w, h);
    }

    /** Word-wraps text to lines no wider than width (a word longer than a line is split). */
    public static List<String> wrap(Font font, String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (font.width(candidate) <= width) {
                line = new StringBuilder(candidate);
                continue;
            }
            if (line.length() > 0) out.add(line.toString());
            line = new StringBuilder(word);
            while (line.length() > 1 && font.width(line.toString()) > width) {
                int cut = line.length() - 1;
                while (cut > 1 && font.width(line.substring(0, cut)) > width) cut--;
                out.add(line.substring(0, cut));
                line = new StringBuilder(line.substring(cut));
            }
        }
        if (line.length() > 0 || out.isEmpty()) out.add(line.toString());
        return out;
    }

    public static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
