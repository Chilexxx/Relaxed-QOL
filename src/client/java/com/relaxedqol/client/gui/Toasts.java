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

            Render2D.shadow(g, x, yy, w, h, 8);
            Render2D.roundRect(g, x, yy, w, h, 8, Theme.fade(0xEE101018, alpha));
            Render2D.roundOutline(g, x, yy, w, h, 8, Theme.fade(Theme.withAlpha(t.color(), 0.55f), alpha));
            Render2D.roundRect(g, x + 6, yy + 6, 3, h - 12, 1, Theme.fade(t.color(), alpha));
            // Progress line at the bottom: how long it stays
            float left = 1f - Math.min(1f, age / (float) t.duration());
            int barW = Math.round((w - 20) * left);
            if (barW > 0) Render2D.roundRect(g, x + 10, yy + h - 3, barW, 1, 0, Theme.fade(Theme.withAlpha(t.color(), 0.5f), alpha));
            int textAlpha = Math.round(alpha * 255) << 24;
            g.text(font, title, x + 15, yy + 6, (t.color() & 0xFFFFFF) | textAlpha, false);
            if (!t.message().isEmpty()) g.text(font, t.message(), x + 15, yy + 17, (Theme.TEXT & 0xFFFFFF) | textAlpha, false);
            y += h + 5;
        }
        g.pose().popMatrix();
    }
}
