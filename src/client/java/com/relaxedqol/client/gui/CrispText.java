package com.relaxedqol.client.gui;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.modules.client.ClickGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Sharp text inside a freely scaled UI.
 *
 * The menu and the Quick Panel are laid out in "virtual" pixels and scaled by any amount (Menu Scale %, panel
 * size). Minecraft's font is a pixel font: drawn at, say, 2.36 screen pixels per font pixel, some strokes end up
 * 2 pixels wide and others 3, which is what made the text look pixely and uneven. Here the boxes keep the free
 * scale, but every piece of text is drawn at the nearest WHOLE number of screen pixels per font pixel and
 * snapped to the pixel grid, so letters are always even and crisp.
 *
 * Usage: {@code float prev = CrispText.begin(scale);} after {@code pose().scale(scale, scale)}, then draw with
 * the methods below using virtual coordinates, then {@code CrispText.end(prev)}.
 */
public final class CrispText {
    /** Virtual -> GUI scale of the pose the caller pushed. */
    private static float layoutScale = 1f;
    /** Text drawn this much bigger than the layout scale (Quick Panel: a bit larger, easier to read). */
    private static float boost = 1f;

    /**
     * While a panel is being resized, text scales freely with it (no snapping to whole screen pixels), so the
     * text and the boxes grow together smoothly instead of jumping a step at a time. Snapped again when you let go.
     */
    public static boolean free;

    private CrispText() {}

    /** Starts drawing text inside a pose scaled by {@code scale}. Returns the previous scale for {@link #end}. */
    public static float begin(float scale) {
        return begin(scale, 1f);
    }

    /** Like {@link #begin(float)}, with text drawn {@code textBoost} times larger than the layout (then snapped). */
    public static float begin(float scale, float textBoost) {
        float prev = layoutScale;
        prevBoost = boost;
        layoutScale = scale <= 0 ? 1f : scale;
        boost = textBoost <= 0 ? 1f : textBoost;
        return prev;
    }

    private static float prevBoost = 1f;

    public static void end(float previous) {
        layoutScale = previous;
        boost = prevBoost;
        prevBoost = 1f;
    }

    private static int guiScale() {
        return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }

    private static boolean enabled() {
        if (RelaxedQolClient.moduleManager == null) return true;
        ClickGUI gui = RelaxedQolClient.moduleManager.getModule(ClickGUI.class);
        return gui == null || gui.crispText();
    }

    /**
     * GUI units per font pixel for text drawn at the current layout scale: a whole number of screen pixels.
     * Rounds slightly down so labels never outgrow the boxes they were laid out for.
     */
    public static float textScale() {
        return boost > 1f ? snap(layoutScale * boost, 0f) : snap(layoutScale, 0.2f);
    }

    /** GUI units per font pixel nearest to {@code wanted} that is a whole number of screen pixels. */
    public static float snap(float wanted, float bias) {
        if (!enabled() || free) return wanted;
        int gs = guiScale();
        int pixels = Math.max(1, Math.round(wanted * gs - bias));
        return (float) pixels / gs;
    }

    /** Width of the text in virtual pixels (as it will really be drawn). */
    public static int width(Font font, String text) {
        return Math.round(font.width(text) * textScale() / layoutScale);
    }

    public static int width(Font font, Component text) {
        return Math.round(font.width(text) * textScale() / layoutScale);
    }

    // ------------------------------------------------------------------ drawing (virtual coordinates)

    /** Like {@link #text} but {@code extra} times bigger than the current text size (still snapped). */
    public static void textBigger(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color, float extra) {
        float before = boost;
        boost = before * extra;
        try {
            text(g, font, text, x, y, color);
        } finally {
            boost = before;
        }
    }

    public static int widthBigger(Font font, String text, float extra) {
        float before = boost;
        boost = before * extra;
        try {
            return width(font, text);
        } finally {
            boost = before;
        }
    }

    /** Left-aligned at virtual (x, y), where y is the top of a normal 9 px text line. */
    public static void text(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        draw(g, font, Component.literal(text), font.width(text), x, y, color, 0);
    }

    public static void text(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
        draw(g, font, text, font.width(text), x, y, color, 0);
    }

    /** Centred on virtual x. */
    public static void centered(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int color) {
        draw(g, font, Component.literal(text), font.width(text), cx, y, color, 1);
    }

    /** Right edge at virtual x. */
    public static void right(GuiGraphicsExtractor g, Font font, String text, int rx, int y, int color) {
        draw(g, font, Component.literal(text), font.width(text), rx, y, color, 2);
    }

    /** align: 0 = left edge at x, 1 = centred on x, 2 = right edge at x. */
    private static void draw(GuiGraphicsExtractor g, Font font, Component text, int fontWidth, int x, int y, int color, int align) {
        float s = layoutScale;
        float ts = textScale();
        if (Math.abs(ts - s) < 0.0001f) {
            int dx = align == 1 ? -fontWidth / 2 : align == 2 ? -fontWidth : 0;
            g.text(font, text, x + dx, y, color, false);
            return;
        }
        int gs = guiScale();
        // GUI position: keep the vertical middle of the line where the layout expects it
        float gx = x * s - (align == 1 ? fontWidth * ts / 2f : align == 2 ? fontWidth * ts : 0f);
        float gy = (y + 4f) * s - 4f * ts;
        // Whole screen pixels only, so no glyph column is ever split
        gx = Math.round(gx * gs) / (float) gs;
        gy = Math.round(gy * gs) / (float) gs;
        g.pose().pushMatrix();
        g.pose().scale(1f / s, 1f / s);
        g.pose().translate(gx, gy);
        g.pose().scale(ts, ts);
        g.text(font, text, 0, 0, color, false);
        g.pose().popMatrix();
    }
}
