package com.relaxedqol.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Small drawing helpers on top of GuiGraphicsExtractor: rounded boxes, shadows, switches, sliders. */
public final class Render2D {
    private Render2D() {}

    // Rounded shapes are drawn with white nine-slice sprites (assets/relaxed-qol/textures/gui/sprites/ui),
    // tinted to the wanted colour. One sprite = a handful of quads, with smooth anti-aliased corners baked into a
    // 4x-resolution texture. (drew the corners pixel by pixel - hundreds of fills per box - which made the
    // menu crawl.)
    private static final int MAX_SPRITE_R = 16;
    private static final Identifier[] ROUND = new Identifier[MAX_SPRITE_R + 1];
    private static final Identifier[] OUTLINE = new Identifier[MAX_SPRITE_R + 1];
    private static final Identifier SHADOW = Identifier.fromNamespaceAndPath("relaxed-qol", "ui/shadow");

    static {
        for (int r = 2; r <= MAX_SPRITE_R; r++) {
            ROUND[r] = Identifier.fromNamespaceAndPath("relaxed-qol", "ui/round_" + r);
            OUTLINE[r] = Identifier.fromNamespaceAndPath("relaxed-qol", "ui/outline_" + r);
        }
    }

    /** Radius that fits the box and has a sprite. */
    private static int fit(int radius, int w, int h) {
        if (VanillaStyle.on()) return 0; // square, like Minecraft
        return Math.max(0, Math.min(Math.min(radius, MAX_SPRITE_R), Math.min(w, h) / 2));
    }

    /** Filled rectangle with smooth rounded corners (radius in virtual pixels). */
    public static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        int r = fit(radius, w, h);
        if (r < 2) {
            if (r == 1 && w > 2 && h > 2) {
                g.fill(x + 1, y, x + w - 1, y + h, color);
                g.fill(x, y + 1, x + 1, y + h - 1, color);
                g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
            } else {
                g.fill(x, y, x + w, y + h, color);
            }
            return;
        }
        g.blitSprite(RenderPipelines.GUI_TEXTURED, ROUND[r], x, y, w, h, color);
    }

    /** Like roundRect but only the top corners are rounded (the bottom edge is square). */
    public static void roundRectTop(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;
        int r = VanillaStyle.on() ? 0 : Math.min(radius, Math.min(MAX_SPRITE_R, w / 2));
        if (r < 2) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        // Draw a taller rounded box and clip off its bottom corners
        g.enableScissor(x, y, x + w, y + h);
        roundRect(g, x, y, w, Math.max(h, 2 * r) + r, r, color);
        g.disableScissor();
    }

    /** Like roundRect but only the bottom corners are rounded (the top edge is square). */
    public static void roundRectBottom(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;
        int r = VanillaStyle.on() ? 0 : Math.min(radius, Math.min(MAX_SPRITE_R, w / 2));
        if (r < 2) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        int tall = Math.max(h, 2 * r) + r;
        g.enableScissor(x, y, x + w, y + h);
        roundRect(g, x, y + h - tall, w, tall, r, color);
        g.disableScissor();
    }

    /** A small icon sprite (assets/relaxed-qol/textures/gui/sprites/ui/NAME.png), tinted. */
    public static void icon(GuiGraphicsExtractor g, String name, int x, int y, int size, int color) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath("relaxed-qol", "ui/" + name), x, y, size, size, color);
    }

    /** Soft drop shadow around a box (one blurred sprite). */
    public static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius) {
        if (w <= 0 || h <= 0 || Theme.SHADOW <= 0.01f) return;
        // As strong as the theme's Shadow %
        int a = Math.min(255, Math.round(0x66 * Theme.SHADOW));
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SHADOW, x - 8, y - 6, w + 16, h + 16, a << 24);
    }

    /**
     * The one surface for everything that floats above the UI - drop-down menus, tooltips, pickers: a soft
     * shadow, a near-opaque neutral body and a hairline edge. Every popup looks the same.
     */
    public static void popup(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        popup(g, x, y, w, h, 1f);
    }

    /** The same, faded to {@code alpha} (0..1) while it appears. */
    public static void popup(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
        if (VanillaStyle.on()) {
            // Like Minecraft's tooltips: near-black body with the bevel
            g.fill(x, y, x + w, y + h, Theme.fade(0xF0100010, alpha));
            VanillaStyle.bevel(g, x, y, w, h);
            return;
        }
        if (alpha >= 1f) shadow(g, x, y, w, h, Theme.R_ROW);
        roundRect(g, x, y, w, h, Theme.R_ROW, Theme.fade(POPUP_BODY, alpha));
        roundOutline(g, x, y, w, h, Theme.R_ROW, Theme.fade(Theme.BORDER, alpha));
    }

    /** Body colour of {@link #popup}. */
    public static final int POPUP_BODY = 0xF71C1D21;

    /** One-pixel outline with the default 5 px rounded corners. */
    public static void roundOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        roundOutline(g, x, y, w, h, 5, color);
    }

    /** One-pixel outline that follows a rounded box of the same radius, smooth at the corners. */
    public static void roundOutline(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 1 || h <= 1 || (color >>> 24) == 0) return;
        int r = fit(radius, w, h);
        if (r < 2) {
            g.fill(x + 1, y, x + w - 1, y + 1, color);
            g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
            g.fill(x, y + 1, x + 1, y + h - 1, color);
            g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
            return;
        }
        g.blitSprite(RenderPipelines.GUI_TEXTURED, OUTLINE[r], x, y, w, h, color);
    }

    /** Horizontal gradient (Minecraft only has vertical ones), drawn as thin strips. */
    public static void horizontalGradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int left, int right) {
        // Capped: the steps of a 1 px tall line are invisible past ~32 strips, and every strip is a quad
        // One strip per ~4 px (max 24) - just as smooth to the eye, far fewer quads every frame
        int strips = Math.max(1, Math.min(24, w / 4));
        for (int i = 0; i < strips; i++) {
            int x0 = x + i * w / strips;
            int x1 = x + (i + 1) * w / strips;
            g.fill(x0, y, x1, y + h, Theme.mix(left, right, (i + 0.5f) / strips));
        }
    }

    /**
     * "premium" header: a soft accent glow from the left that fades out, a faint glass highlight along the top
     * edge and a little bloom under the accent line. Kept clear of the rounded corners (radius r) so they stay smooth.
     */
    public static void headerSheen(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int accent) {
        headerSheen(g, x, y, w, h, r, accent, false);
    }

    /** whole = the bar is the whole (folded) panel, so all four corners are round. */
    public static void headerSheen(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int accent, boolean whole) {
        if (w <= r * 2 + 4 || h <= 2) return;
        // The clean look (LoverFella Mode off) has no coloured tint on title bars - just a faint top light
        // The clean look (LoverFella Mode off) has no coloured tint on title bars - just a faint top light
        if (!LoverFx.on()) {
            g.fill(x + r, y + 1, x + w - r, y + 2, 0x0FFFFFFF);
            return;
        }
        if (whole) {
            roundRect(g, x, y, w, h, r, Theme.withAlpha(accent, 0.10f));
            g.fill(x + r, y + 1, x + w - r, y + 2, 0x14FFFFFF);
            return;
        }
        // One even tint over the whole bar (rounded corners included) - the sideways fade used to start at the
        // corner and end in a visible vertical line
        roundRectTop(g, x, y, w, h, r, Theme.withAlpha(accent, 0.10f));
        g.fill(x + r, y + 1, x + w - r, y + 2, 0x14FFFFFF);
    }

    /** A soft glow under an accent line at y (fades out downwards over a few pixels). */
    public static void underGlow(GuiGraphicsExtractor g, int x, int y, int w, int accent) {
        if (w <= 0 || !LoverFx.on()) return;
        g.fillGradient(x, y, x + w, y + 6, Theme.withAlpha(accent, 0.10f), Theme.withAlpha(accent, 0f));
    }

    /**
     * The line under a title bar. LoverFella Mode: the accent fading out to the right. Clean look: one even
     * hairline (a gradient made of strips showed as a broken dark line at some menu scales).
     */
    public static void headerLine(GuiGraphicsExtractor g, int x, int y, int w, int accent) {
        if (w <= 0) return;
        if (LoverFx.on()) {
            // LoverFella Mode keeps its accent gradient, on top of one faint solid line: where the gradient's strips
            // left hairline gaps at uneven menu scales, the line shows instead of the dark panel ("black broken line")
            // No coloured line any more - one calm hairline, like the rest of the client
            g.fill(x + 1, y, x + w - 1, y + 1, 0x1CFFFFFF);
        } else {
            g.fill(x + 1, y, x + w - 1, y + 1, 0x1CFFFFFF);
        }
    }

    /** Smooth 0..1 ease (fast start, soft landing). */
    public static float easeOut(float t) {
        float k = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - k) * (1f - k) * (1f - k);
    }

    /**
     * Resize grip in a bottom-right corner: two smooth diagonal strokes from a sprite (it was three dots,
     * and before that a pixel "L"). (cx, cy) = the inner corner point, 5 px in from the edges.
     */
    public static void resizeGrip(GuiGraphicsExtractor g, int cx, int cy, int color) {
        icon(g, "grip", cx - 2, cy - 2, 7, color);
    }

    /** iOS-style switch. progress 0 = off, 1 = on (animated by the caller). */
    public static void toggleSwitch(GuiGraphicsExtractor g, int x, int y, float progress, int accent) {
        if (VanillaStyle.on()) {
            VanillaStyle.toggle(g, x, y, progress);
            return;
        }
        // Clean Style: a dark box with a thin grey edge and a block inside - green on the right when on, red on
        // the left when off (sliding across while it changes)
        if (CleanStyle.on()) {
            int w = 16;
            int h = 8;
            // A dark rounded slot with a soft edge...
            roundRect(g, x, y, w, h, 2, 0xE0141517);
            roundOutline(g, x, y, w, h, 2, 0x47FFFFFF);
            // ...and a rounded block that slides across: soft green on the right when on, soft red on the left when off,
            // with a faint light line on its top edge so it reads as a raised piece rather than a flat sticker
            int bw = 8;
            int bx = x + 1 + Math.round(progress * (w - 2 - bw));
            int color = Theme.mix(CleanStyle.SWITCH_OFF, CleanStyle.SWITCH_ON, progress);
            roundRect(g, bx, y + 1, bw, h - 2, 2, color);
            g.fill(bx + 1, y + 1, bx + bw - 1, y + 2, 0x40FFFFFF);
            return;
        }
        int w = 16;
        int h = 8;
        roundRect(g, x, y, w, h, 4, Theme.mix(Theme.TRACK, accent, progress));
        int knobX = x + 1 + Math.round(progress * (w - 8));
        roundRect(g, knobX, y + 1, 6, 6, 3, Theme.mix(0xFFB8B8C8, 0xFFFFFFFF, progress));
    }

    /** Slider track with filled part and knob. fraction 0..1. */
    public static void slider(GuiGraphicsExtractor g, int x, int y, int w, float fraction, int accent, boolean active) {
        if (VanillaStyle.on()) {
            VanillaStyle.slider(g, x, y, w, fraction, active);
            return;
        }
        if (CleanStyle.on()) {
            // A thin dark line and a round blue knob, no coloured fill
            g.fill(x, y + 1, x + w, y + 2, 0xFF0E0F11);
            g.fill(x, y + 2, x + w, y + 3, 0x40FFFFFF);
            float f = Math.max(0, Math.min(1, fraction));
            smoothCircle(g, x + f * w, y + 1.5f, active ? 4f : 3.5f, CleanStyle.KNOB, 1f);
            return;
        }
        roundRect(g, x, y, w, 3, 1, Theme.TRACK);
        int filled = Math.round(w * Math.max(0, Math.min(1, fraction)));
        if (filled > 0) roundRect(g, x, y, filled, 3, 1, accent);
        int knob = x + filled - 2;
        roundRect(g, knob, y - 2, 5, 7, 2, active ? 0xFFFFFFFF : 0xFFE4E4EE);
    }

    /** Small chevron: pointing right (closed) or down (open). */
    public static void chevron(GuiGraphicsExtractor g, int x, int y, boolean open, int color) {
        // A smooth sprite - the pixel version showed a seam line at some menu scales
        icon(g, open ? "chevron_down" : "chevron_right", x - 1, y - 1, 7, color);
    }

    /** Small "play" triangle for one-shot action modules. */
    public static void playIcon(GuiGraphicsExtractor g, int x, int y, int color) {
        for (int i = 0; i < 4; i++) {
            g.fill(x + i, y + i, x + i + 1, y + 7 - i, color);
        }
    }

    /**
     * Text at GUI coordinates (no scaled pose around it) that stays sharp at any size: a whole number of
     * screen pixels per font pixel, on the pixel grid. centred = x is the middle of the text.
     */
    public static void crispText(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String text, float x, float y,
                                 int color, float size, boolean centred, boolean shadow) {
        int gs = Math.max(1, net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale());
        int perFont = Math.max(1, Math.round(gs * size));
        float k = perFont / (float) gs;
        float w = font.width(text) * k;
        float left = centred ? x - Math.max(0f, w - k) / 2f : x;
        float top = y + 4f * size - 4f * k;
        float sx = Math.round(left * gs) / (float) gs;
        float sy = Math.round(top * gs) / (float) gs;
        g.pose().pushMatrix();
        g.pose().translate(sx, sy);
        g.pose().scale(k, k);
        g.text(font, text, 0, 0, color, shadow);
        g.pose().popMatrix();
    }

    /** Width of {@link #crispText} text in GUI units. */
    public static float crispWidth(net.minecraft.client.gui.Font font, String text, float size) {
        int gs = Math.max(1, net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale());
        return font.width(text) * Math.max(1, Math.round(gs * size)) / (float) gs;
    }

    /** Check mark inside a 9x9 box. */
    public static void checkmark(GuiGraphicsExtractor g, int x, int y, int color) {
        icon(g, "check", x, y, 7, color);
    }

    /**
     * A clean, anti-aliased arrow head (the "you are here" marker on the radar / minimap) with a dark rim,
     * drawn in real screen pixels like {@link #smoothCircle}. (cx, cy) = its middle, size = half its height, angle in
     * radians (0 = pointing up, clockwise). It replaces the stepped pixel arrow that looked like a bent stick.
     */
    public static void smoothArrow(GuiGraphicsExtractor g, float cx, float cy, float size, double angle, int fill, int rim) {
        var m = g.pose();
        float gs = com.relaxedqol.client.util.SmoothFont.guiScale();
        float pps = com.relaxedqol.client.util.SmoothFont.pixelsPer(m);
        float k = size * pps;
        if (k <= 0) return;
        float sx = (m.m00() * cx + m.m10() * cy + m.m20()) * gs;
        float sy = (m.m01() * cx + m.m11() * cy + m.m21()) * gs;
        // Arrow head pointing up: tip, right wing, notch, left wing (unit size)
        float[][] unit = {{0f, -1f}, {0.78f, 0.82f}, {0f, 0.36f}, {-0.78f, 0.82f}};
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        float[] px = new float[4];
        float[] py = new float[4];
        for (int i = 0; i < 4; i++) {
            float ux = unit[i][0] * k;
            float uy = unit[i][1] * k;
            px[i] = (float) (sx + ux * cos - uy * sin);
            py[i] = (float) (sy + ux * sin + uy * cos);
        }
        float rimW = Math.max(1f, k * 0.16f);
        int reach = (int) Math.ceil(k + rimW + 2);
        int x0 = (int) Math.floor(sx) - reach;
        int x1 = (int) Math.floor(sx) + reach;
        int y0 = (int) Math.floor(sy) - reach;
        int y1 = (int) Math.floor(sy) + reach;
        float fillA = ((fill >>> 24) & 0xFF) / 255f;
        float rimA = ((rim >>> 24) & 0xFF) / 255f;
        Run run = new Run();
        m.pushMatrix();
        m.identity();
        m.scale(1f / gs, 1f / gs);
        for (int yy = y0; yy <= y1; yy++) {
            float qy = yy + 0.5f;
            for (int xx = x0; xx <= x1; xx++) {
                float qx = xx + 0.5f;
                // Signed distance to the outline: + inside, - outside
                boolean inside = false;
                float best = Float.MAX_VALUE;
                for (int i = 0, j = 3; i < 4; j = i++) {
                    if ((py[i] > qy) != (py[j] > qy)
                        && qx < (px[j] - px[i]) * (qy - py[i]) / (py[j] - py[i]) + px[i]) inside = !inside;
                    float ex = px[j] - px[i];
                    float ey = py[j] - py[i];
                    float len2 = ex * ex + ey * ey;
                    float t = len2 <= 0 ? 0 : Math.max(0f, Math.min(1f, ((qx - px[i]) * ex + (qy - py[i]) * ey) / len2));
                    float dx = qx - (px[i] + t * ex);
                    float dy = qy - (py[i] + t * ey);
                    best = Math.min(best, (float) Math.sqrt(dx * dx + dy * dy));
                }
                float d = inside ? best : -best;
                float outer = Math.max(0f, Math.min(1f, d + rimW + 0.5f));
                if (outer <= 0f) continue;
                float inner = Math.max(0f, Math.min(1f, d + 0.5f));
                int rgb = Theme.mix(rim | 0xFF000000, fill | 0xFF000000, inner) & 0xFFFFFF;
                float a = (rimA + (fillA - rimA) * inner) * outer;
                int ai = Math.round(a * 255f);
                if (ai <= 0) continue;
                run.add(g, xx, yy, (ai << 24) | rgb);
            }
            run.flush(g);
        }
        m.popMatrix();
    }

    /**
     * Runs a drawing (a map texture...) clipped to a rounded box: the middle in one go, and the top / bottom
     * rows that hold the rounded corners one pixel row at a time, each a little narrower. A square scissor let the map's
     * corners stick out past the window's rounded frame.
     */
    public static void clipRounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, Runnable draw) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r > 0) {
            g.enableScissor(x, y + r, x + w, y + h - r);
            draw.run();
            g.disableScissor();
        }
        for (int i = 0; i < Math.max(1, r); i++) {
            double dy = r - i - 0.5;
            int inset = r == 0 ? 0 : (int) Math.ceil(r - Math.sqrt(Math.max(0, r * (double) r - dy * dy)) - 0.35);
            if (r == 0) {
                g.enableScissor(x, y, x + w, y + h);
                draw.run();
                g.disableScissor();
                return;
            }
            g.enableScissor(x + inset, y + i, x + w - inset, y + i + 1);
            draw.run();
            g.disableScissor();
            g.enableScissor(x + inset, y + h - 1 - i, x + w - inset, y + h - i);
            draw.run();
            g.disableScissor();
        }
    }

    /**
     * A perfectly smooth (anti-aliased) filled circle at any size: drawn pixel by pixel in real screen pixels,
     * each pixel as solid as much of it the circle covers. {@code feather} = how soft the edge is in screen pixels
     * (1 = just smooth, bigger = a soft glow). Centre and radius are in the current (local) coordinates.
     */
    public static void smoothCircle(GuiGraphicsExtractor g, float cx, float cy, float radius, int color, float feather) {
        var m = g.pose();
        float gs = com.relaxedqol.client.util.SmoothFont.guiScale();
        float pps = com.relaxedqol.client.util.SmoothFont.pixelsPer(m);
        float r = radius * pps;
        if (r <= 0) return;
        float f = Math.max(0.75f, feather);
        float sx = (m.m00() * cx + m.m10() * cy + m.m20()) * gs;
        float sy = (m.m01() * cx + m.m11() * cy + m.m21()) * gs;
        float baseA = ((color >>> 24) & 0xFF) / 255f;
        int rgb = color & 0xFFFFFF;
        int reach = (int) Math.ceil(r + f);
        int x0 = (int) Math.floor(sx) - reach;
        int x1 = (int) Math.floor(sx) + reach;
        int y0 = (int) Math.floor(sy) - reach;
        int y1 = (int) Math.floor(sy) + reach;
        Run run = new Run();
        m.pushMatrix();
        m.identity();
        m.scale(1f / gs, 1f / gs);
        for (int py = y0; py <= y1; py++) {
            float dy = py + 0.5f - sy;
            for (int px = x0; px <= x1; px++) {
                float dx = px + 0.5f - sx;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float cov = (r + f / 2f - d) / f;
                if (cov <= 0f) continue;
                if (cov > 1f) cov = 1f;
                int a = Math.round(baseA * cov * 255f);
                if (a <= 0) continue;
                run.add(g, px, py, (a << 24) | rgb);
            }
            run.flush(g);
        }
        m.popMatrix();
    }

    /**
     * Neighbouring pixels of the same colour in a row are drawn as one rectangle (the inside of a circle or an
     * arrow is one colour) - hundreds of 1-pixel quads every frame became a few dozen.
     */
    private static final class Run {
        int x0 = Integer.MIN_VALUE;
        int x1;
        int y;
        int color;

        void add(GuiGraphicsExtractor g, int x, int yy, int c) {
            if (x0 != Integer.MIN_VALUE && yy == y && x == x1 && c == color) {
                x1++;
                return;
            }
            flush(g);
            x0 = x;
            x1 = x + 1;
            y = yy;
            color = c;
        }

        void flush(GuiGraphicsExtractor g) {
            if (x0 == Integer.MIN_VALUE) return;
            g.fill(x0, y, x1, y + 1, color);
            x0 = Integer.MIN_VALUE;
        }
    }
}
