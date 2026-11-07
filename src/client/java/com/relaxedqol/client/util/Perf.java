package com.relaxedqol.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * ".perf": how much of your computer's time the client's own parts take - the HUD, the cards, the 3D things
 * (ESP, waypoints, chunk borders), the culling checks and the module ticks - as a share of every second, measured
 * while it is shown. When the client's total is small, the FPS difference to another client comes from the game's
 * own rendering (what Sodium / Lunar's renderer change), not from these.
 */
public final class Perf {
    private Perf() {}

    public static final int HUD = 0;
    public static final int CARDS = 1;
    public static final int WORLD = 2;
    public static final int CULLING = 3;
    public static final int TICKS = 4;
    private static final String[] NAMES = {"HUD", "Cards", "3D (ESP, waypoints...)", "Culling checks", "Module ticks"};

    private static boolean on;
    private static final long[] acc = new long[NAMES.length];
    private static final double[] shown = new double[NAMES.length];
    private static long windowStart;
    private static int frames;
    private static int fps;

    public static boolean on() {
        return on;
    }

    public static void toggle() {
        on = !on;
        java.util.Arrays.fill(acc, 0);
        java.util.Arrays.fill(shown, 0);
        windowStart = System.nanoTime();
        frames = 0;
        ChatUtil.send(on ? "§aPerformance overlay on§7 - type §f.perf§7 again to hide it" : "§7Performance overlay off");
    }

    public static long start() {
        return on ? System.nanoTime() : 0L;
    }

    public static void end(int slot, long t0) {
        if (t0 != 0L) acc[slot] += System.nanoTime() - t0;
    }

    /** Once per frame (from the HUD layer): every second the shares are worked out. */
    public static void frame() {
        if (!on) return;
        frames++;
        long now = System.nanoTime();
        long span = now - windowStart;
        if (span >= 1_000_000_000L) {
            for (int i = 0; i < acc.length; i++) {
                shown[i] = acc[i] * 100.0 / span;
                acc[i] = 0;
            }
            fps = (int) Math.round(frames * 1e9 / span);
            frames = 0;
            windowStart = now;
        }
    }

    public static void draw(GuiGraphicsExtractor g) {
        if (!on) return;
        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;
        double total = 0;
        for (double v : shown) total += v;
        String[] lines = new String[NAMES.length + 2];
        lines[0] = "Relaxed QOL load   " + fps + " FPS";
        for (int i = 0; i < NAMES.length; i++) lines[i + 1] = String.format(java.util.Locale.ROOT, "%-24s %5.2f%%", NAMES[i], shown[i]);
        lines[lines.length - 1] = String.format(java.util.Locale.ROOT, "%-24s %5.2f%%", "Client total", total);
        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        w += 12;
        int h = lines.length * 10 + 8;
        int x = 4;
        int y = g.guiHeight() - h - 40;
        com.relaxedqol.client.gui.Render2D.roundRect(g, x, y, w, h, 6, 0xD0101018);
        com.relaxedqol.client.gui.Render2D.roundOutline(g, x, y, w, h, 6, 0x30FFFFFF);
        for (int i = 0; i < lines.length; i++) {
            int color = i == 0 ? 0xFFFFFFFF : i == lines.length - 1 ? (total > 15 ? 0xFFF87171 : 0xFF4ADE80) : 0xFFC9CDD9;
            g.text(font, lines[i], x + 6, y + 5 + i * 10, color, false);
        }
    }
}
