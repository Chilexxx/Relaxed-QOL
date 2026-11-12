package com.relaxedqol.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Smooth on-screen alerts: a rounded card slides down at the top of the screen, stays a few seconds and fades out.
 * Drawn with the client's rounded sprites at the HUD's whole-pixel scale, so it looks clean instead of a block of
 * coloured chat lines. Up to 4 stack under each other.
 */
public final class Toasts {
    private record Toast(String title, String message, int color, long start, long duration) {}

    private static final List<Toast> ACTIVE = new ArrayList<>();
    private static final long SLIDE = 260;

    private Toasts() {}

    /** Shows an alert. color: the accent stripe / title colour (ARGB). */
    public static void show(String title, String message, int color, long durationMs) {
        synchronized (ACTIVE) {
            ACTIVE.add(new Toast(title, message, color, System.currentTimeMillis(), durationMs));
            while (ACTIVE.size() > 4) ACTIVE.remove(0);
        }
    }

    public static void render(GuiGraphicsExtractor g) {
        List<Toast> list;
        synchronized (ACTIVE) {
            long now = System.currentTimeMillis();
            ACTIVE.removeIf(t -> now - t.start() > t.duration() + SLIDE);
            if (ACTIVE.isEmpty()) return;
            list = new ArrayList<>(ACTIVE);
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        float scale = OverlayRenderer.hudScale();
        int vw = Math.round(g.guiWidth() / scale);
        long now = System.currentTimeMillis();

        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        int y = 8;
        for (Toast t : list) {
            long age = now - t.start();
            float in = Math.min(1f, age / (float) SLIDE);
            float out = age > t.duration() ? 1f - Math.min(1f, (age - t.duration()) / (float) SLIDE) : 1f;
            float ease = 1f - (1f - in) * (1f - in) * (1f - in); // ease-out cubic
            float alpha = Math.min(in * 1.4f, 1f) * out;
            if (alpha <= 0.02f) continue;

            Component title = Component.literal(t.title()).withStyle(ChatFormatting.BOLD);
            int w = Math.max(font.width(title), font.width(t.message())) + 26;
            int h = t.message().isEmpty() ? 20 : 30;
            int x = vw / 2 - w / 2;
            int yy = Math.round(y - (1f - ease) * 14f);

            float left = 1f - Math.min(1f, age / (float) t.duration());
            int textAlpha = Math.round(alpha * 255) << 24;
            // Vanilla Style: a square dark box like Minecraft's own pop-ups, white title, grey text, pixel shadow
            if (VanillaStyle.on()) {
                g.fill(x, yy, x + w, yy + h, Theme.fade(0xF0101010, alpha));
                g.fill(x, yy, x + w, yy + 1, Theme.fade(VanillaStyle.EDGE_LIGHT, alpha));
                g.fill(x, yy + h - 1, x + w, yy + h, Theme.fade(VanillaStyle.EDGE_DARK, alpha));
                g.fill(x, yy, x + 1, yy + h, Theme.fade(VanillaStyle.EDGE_LIGHT, alpha));
                g.fill(x + w - 1, yy, x + w, yy + h, Theme.fade(VanillaStyle.EDGE_DARK, alpha));
                g.fill(x + 5, yy + 5, x + 7, yy + h - 5, Theme.fade(t.color(), alpha));
                int barW = Math.round((w - 4) * left);
                if (barW > 0) g.fill(x + 2, yy + h - 2, x + 2 + barW, yy + h - 1, Theme.fade(0xFF808080, alpha));
                g.text(font, Component.literal(t.title()), x + 12, yy + 6, (VanillaStyle.TEXT & 0xFFFFFF) | textAlpha, true);
                if (!t.message().isEmpty()) g.text(font, t.message(), x + 12, yy + 17, (VanillaStyle.TEXT_GRAY & 0xFFFFFF) | textAlpha, true);
                y += h + 5;
                continue;
            }
            // Clean Style: see-through glass in the theme's colours, a thin light edge, white text; the pop-up's
            // colour only as a small dot and the time line
            if (CleanStyle.on()) {
                // Liquid glass: see-through, lightly tinted with the accent, glossy finish
                int r = Math.min(h / 2, 10);
                int glass = Theme.mix(Theme.PANEL, Theme.accent(), 0.08f + com.relaxedqol.client.module.modules.client.ClickGUI.glassTint() * 0.4f);
                Render2D.shadow(g, x, yy, w, h, r);
                Render2D.roundRect(g, x, yy, w, h, r, Theme.fade(Theme.withAlpha(glass, 0.55f), alpha));
                if (alpha > 0.6f) CleanStyle.gloss(g, x, yy, w, h, r);
                Render2D.smoothCircle(g, x + 10f, yy + h / 2f, 2.2f, Theme.fade(Theme.withAlpha(t.color(), 0.9f), alpha), 1f);
                // Time left: a faint white hairline inside the bottom edge
                int barW = Math.round((w - 2 * r) * left);
                if (barW > 0) g.fill(x + r, yy + h - 2, x + r + barW, yy + h - 1, Theme.fade(0x40FFFFFF, alpha));
                com.relaxedqol.client.util.SmoothFont.begin();
                try {
                    int ty = t.message().isEmpty() ? yy + (h - 8) / 2 : yy + 6;
                    g.text(font, t.title(), x + 17, ty, (Theme.TEXT & 0xFFFFFF) | textAlpha, false);
                    if (!t.message().isEmpty()) g.text(font, t.message(), x + 17, yy + 17, (Theme.TEXT_DIM & 0xFFFFFF) | textAlpha, false);
                } finally {
                    com.relaxedqol.client.util.SmoothFont.end();
                }
                y += h + 5;
                continue;
            }
            // Default look: the theme's panel colour (solid enough to read on anything), a calm neutral edge with a
            // faint hint of the pop-up's colour, a slim rounded colour bar, a soft white-ish title and dim text
            int r = Theme.R_SURFACE;
            Render2D.shadow(g, x, yy, w, h, r);
            Render2D.roundRect(g, x, yy, w, h, r, Theme.fade(Theme.withAlpha(Theme.panel(), 0.92f), alpha));
            Render2D.roundRect(g, x, yy, w, h, r, Theme.fade(Theme.withAlpha(t.color(), 0.07f), alpha));
            Render2D.roundOutline(g, x, yy, w, h, r, Theme.fade(Theme.mix(Theme.BORDER, Theme.withAlpha(t.color(), 0.35f), 0.5f), alpha));
            Render2D.roundRect(g, x + 7, yy + 7, 2, h - 14, 1, Theme.fade(t.color(), alpha));
            // Time left: a thin line in a faint track inside the bottom edge
            int trackW = w - 2 * r;
            int barW = Math.round(trackW * left);
            g.fill(x + r, yy + h - 3, x + r + trackW, yy + h - 2, Theme.fade(0x14FFFFFF, alpha));
            if (barW > 0) g.fill(x + r, yy + h - 3, x + r + barW, yy + h - 2, Theme.fade(Theme.withAlpha(t.color(), 0.6f), alpha));
            int titleColor = Theme.mix(t.color(), 0xFFFFFFFF, 0.55f);
            int ty = t.message().isEmpty() ? yy + (h - 8) / 2 : yy + 6;
            g.text(font, title, x + 15, ty, (titleColor & 0xFFFFFF) | textAlpha, false);
            if (!t.message().isEmpty()) g.text(font, t.message(), x + 15, yy + 17, (Theme.TEXT_DIM & 0xFFFFFF) | textAlpha, false);
            y += h + 5;
        }
        g.pose().popMatrix();
    }
}
