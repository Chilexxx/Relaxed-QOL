package com.relaxedqol.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * One look for every HUD card in the normal (non-LoverFella) theme - the watermark, the info panel, the
 * Players list, the Notes card and the other cards all use the same dark glass body, the same corner radius, the same
 * hairline edge, the same header (accent mark + bold title + a count chip) and the same text colours, all in the
 * smooth font. Change it here and every card follows.
 */
public final class HudStyle {
    private HudStyle() {}

    /** Corner radius of every card (the theme's, or the element's own while one is being drawn). */
    public static int RADIUS = 8;
    /** Inner padding of every card. */
    public static final int PAD = 7;
    /** Height of a card header row (title line). */
    public static final int HEADER_H = 18;

    /** Card body colour (before the opacity). */
    public static final int BODY = 0xFF151619;
    /** The hairline edge around every card. */
    public static int EDGE = Theme.BORDER;
    /** Thin dividers inside a card. */
    public static int DIVIDER = Theme.HAIRLINE;

    /** Text colours: labels, values, faint extras. */
    public static int LABEL = Theme.TEXT_DIM;
    public static int VALUE = Theme.TEXT;
    public static int FAINT = Theme.TEXT_FAINT;

    // ------------------------------------------------------------------ per-element styles

    /** The style of the element being drawn now (null = the theme). */
    private static CardStyle current;
    private static int bodyColor = BODY;
    private static float bodyAlpha = -1f;
    private static boolean shadow = true;
    private static boolean textShadow;
    private static int accentColor;

    /**
     * Draw the next element with this style (null = follow the theme). Everything HudStyle draws - and the text
     * colours LABEL / VALUE / FAINT, {@link #accent()} and {@link #textShadow()} the element reads - follows it
     * until {@link #end()}.
     */
    public static void begin(CardStyle style) {
        current = style;
        Theme.refresh();
        if (style == null || !style.isCustom()) {
            RADIUS = Theme.R_SURFACE;
            EDGE = Theme.BORDER;
            DIVIDER = Theme.HAIRLINE;
            VALUE = Theme.TEXT;
            LABEL = Theme.TEXT_DIM;
            FAINT = Theme.TEXT_FAINT;
            bodyColor = Theme.shade(Theme.SURFACE, -0.04f);
            bodyAlpha = -1f;
            shadow = true;
            textShadow = false;
            accentColor = Theme.accent();
            return;
        }
        int text = style.textColor();
        int body = style.bodyColor();
        RADIUS = style.radius();
        EDGE = style.edgeColor();
        DIVIDER = Theme.withAlpha(text, 0.10f);
        VALUE = text;
        LABEL = Theme.mix(body, text, 0.65f);
        FAINT = Theme.mix(body, text, 0.42f);
        bodyColor = body;
        bodyAlpha = style.bodyAlpha();
        shadow = style.hasShadow();
        textShadow = style.hasTextShadow();
        accentColor = style.accentColor();
    }

    /** Back to the theme. */
    public static void end() {
        begin(null);
    }

    /** The accent of the element being drawn. */
    public static int accent() {
        return current != null && current.isCustom() ? accentColor : Theme.accent();
    }

    /** Whether the element's text gets a drop shadow. */
    public static boolean textShadow() {
        return textShadow;
    }

    /** The element's body colour (opaque) - for cards that paint their own pieces (keys...). */
    public static int bodyColor() {
        return bodyColor;
    }

    /** The element's own opacity 0..1, or -1 when it follows its own setting. */
    public static float bodyAlpha() {
        return bodyAlpha;
    }

    /** Whether the element gets a drop shadow. */
    public static boolean shadow() {
        return shadow;
    }
    /** "Online" green, only for live status dots. */
    public static final int ONLINE = 0xFF4ADE80;

    /** The card itself: soft shadow, dark body and the hairline edge (flat - no sheen line along the top). */
    public static void card(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        card(g, x, y, w, h, 0.84f);
    }

    /** Same, with the body as solid as {@code alpha} (0..1). */
    public static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
        float a = bodyAlpha >= 0 ? bodyAlpha : alpha;
        if (VanillaStyle.on()) {
            // Like Minecraft's own HUD boxes (scoreboard, chat): a plain see-through black box, no edge
            g.fill(x, y, x + w, y + h, Theme.withAlpha(0xFF000000, Math.min(0.55f, a * 0.6f)));
            return;
        }
        if (CleanStyle.on()) {
            // A plain see-through box with small corners - no edge, no shadow
            Render2D.roundRect(g, x, y, w, h, 3, Theme.withAlpha(CleanStyle.HUD_BOX, Math.min(0.5f, a * 0.6f)));
            return;
        }
        if (shadow && a > 0.05f) Render2D.shadow(g, x, y, w, h, RADIUS);
        Render2D.roundRect(g, x, y, w, h, RADIUS, Theme.withAlpha(bodyColor, a));
        if ((EDGE >>> 24) != 0) Render2D.roundOutline(g, x, y, w, h, RADIUS, EDGE);
    }

    /** The accent mark in front of a card title (a small smooth dot). */
    public static void mark(GuiGraphicsExtractor g, int x, int centreY, int color) {
        Render2D.smoothCircle(g, x + 2.5f, centreY, 2.4f, color, 1f);
    }

    /** Width of a count chip for this text. */
    public static int chipWidth(Font font, String text) {
        // Roomier, with slack for the smooth font (its digits came out wider than measured and "1/6" ran out
        // past the chip's right edge)
        return font.width(text) + 14 + Math.round(text.length() * 0.6f);
    }

    /** A small rounded count chip ("1/1", "3") with its right edge at {@code right}, vertically centred on centreY. */
    public static void chip(GuiGraphicsExtractor g, Font font, String text, int right, int centreY, boolean lit) {
        int w = chipWidth(font, text);
        // As tall as the letters really are plus room above and below (the digits ran over its bottom edge)
        int h = Math.max(14, font.lineHeight + 5);
        int x = right - w;
        int y = centreY - h / 2;
        int accent = accent();
        Render2D.roundRect(g, x, y, w, h, Theme.R_CONTROL, lit ? Theme.withAlpha(accent, 0.20f) : 0x17FFFFFF);
        if (lit) Render2D.roundOutline(g, x, y, w, h, Theme.R_CONTROL, Theme.withAlpha(accent, 0.45f));
        g.text(font, text, x + (w - font.width(text) + 1) / 2, y + (h - font.lineHeight + 1) / 2, lit ? VALUE : LABEL, textShadow);
    }

    /**
     * A header row: bold title and an optional count chip on the right. Returns the row's height. no accent
     * dot in front of the title any more - the title alone, aligned with the card's text below it.
     */
    public static int header(GuiGraphicsExtractor g, Font font, int x, int y, int w, String title, String chip, boolean lit) {
        int cy = y + HEADER_H / 2;
        g.text(font, net.minecraft.network.chat.Component.literal(title).withStyle(net.minecraft.ChatFormatting.BOLD),
            x + PAD, cy - 4, VALUE, textShadow);
        if (chip != null) chip(g, font, chip, x + w - PAD, cy, lit);
        return HEADER_H;
    }

    /** Width the header needs (mark + bold title + chip). */
    public static int headerWidth(Font font, String title, String chip) {
        int bold = font.width(net.minecraft.network.chat.Component.literal(title).withStyle(net.minecraft.ChatFormatting.BOLD));
        return PAD + bold + (chip != null ? 10 + chipWidth(font, chip) : 0) + PAD;
    }

    /** A divider under the header. */
    public static void divider(GuiGraphicsExtractor g, int x, int y, int w) {
        g.fill(x + PAD, y, x + w - PAD, y + 1, DIVIDER);
    }
}
