package com.relaxedqol.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Settings > Vanilla Style: the client drawn like Minecraft's own menus. Square dark panels with a thin bevel,
 * Minecraft's own button / slider textures and the pixel font with shadows. The theme ({@link Theme}), the shapes
 * ({@link Render2D}) and the windows ({@link WindowScreen}) ask {@link #on()} and draw through here, so every module's
 * screens follow without changes of their own.
 */
public final class VanillaStyle {
    private VanillaStyle() {}

    private static final Identifier BUTTON = Identifier.fromNamespaceAndPath("minecraft", "widget/button");
    private static final Identifier BUTTON_HOVER = Identifier.fromNamespaceAndPath("minecraft", "widget/button_highlighted");
    private static final Identifier BUTTON_OFF = Identifier.fromNamespaceAndPath("minecraft", "widget/button_disabled");
    private static final Identifier SLIDER = Identifier.fromNamespaceAndPath("minecraft", "widget/slider");
    private static final Identifier SLIDER_HANDLE = Identifier.fromNamespaceAndPath("minecraft", "widget/slider_handle");
    private static final Identifier SLIDER_HANDLE_HOVER = Identifier.fromNamespaceAndPath("minecraft", "widget/slider_handle_highlighted");

    /** Minecraft's colours. */
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_GRAY = 0xFFA0A0A0;
    public static final int TEXT_DARK = 0xFF707070;
    public static final int YELLOW = 0xFFFFFF55;
    public static final int PANEL = 0xD0101010;
    public static final int HEADER = 0xE0000000;
    public static final int EDGE_DARK = 0xFF000000;
    public static final int EDGE_LIGHT = 0xFF5A5A5A;

    public static boolean on() {
        return com.relaxedqol.client.module.modules.client.ClickGUI.vanillaStyle();
    }

    /** A Minecraft button (its own texture) with the label centred in white / yellow-on-hover, with a shadow. */
    public static void button(GuiGraphicsExtractor g, Font font, String label, int x, int y, int w, int h, boolean hover,
                              boolean active, boolean enabled) {
        Identifier sprite = !enabled ? BUTTON_OFF : hover || active ? BUTTON_HOVER : BUTTON;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h, 0xFFFFFFFF);
        if (label == null || label.isEmpty()) return;
        int color = !enabled ? TEXT_GRAY : active ? YELLOW : hover ? 0xFFFFFFA0 : TEXT;
        g.centeredText(font, label, x + w / 2, y + (h - 8) / 2 + (h % 2 == 0 ? 0 : 1), color);
    }

    /**
     * A window / panel: a dark see-through body, a darker title strip and the classic bevel (black outside, a
     * lighter grey line inside), square corners.
     */
    public static void window(GuiGraphicsExtractor g, int x, int y, int w, int h, int titleH) {
        g.fill(x, y, x + w, y + h, PANEL);
        if (titleH > 0) {
            g.fill(x + 1, y + 1, x + w - 1, y + titleH, HEADER);
            g.fill(x + 1, y + titleH, x + w - 1, y + titleH + 1, EDGE_LIGHT);
        }
        bevel(g, x, y, w, h);
    }

    /** Black outline with a grey inner line, like Minecraft's list and tooltip frames. */
    public static void bevel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w < 3 || h < 3) return;
        g.fill(x, y, x + w, y + 1, EDGE_DARK);
        g.fill(x, y + h - 1, x + w, y + h, EDGE_DARK);
        g.fill(x, y, x + 1, y + h, EDGE_DARK);
        g.fill(x + w - 1, y, x + w, y + h, EDGE_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, EDGE_LIGHT);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, EDGE_LIGHT);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, EDGE_LIGHT);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, EDGE_LIGHT);
    }

    /** A Minecraft slider: the dark track texture, a green filled part and the button-like handle. */
    public static void slider(GuiGraphicsExtractor g, int x, int y, int w, float fraction, boolean active) {
        int h = 7;
        int ty = y - 2;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SLIDER, x, ty, w, h, 0xFFFFFFFF);
        float f = Math.max(0, Math.min(1, fraction));
        int filled = Math.round((w - 2) * f);
        if (filled > 0) g.fill(x + 1, ty + 1, x + 1 + filled, ty + h - 1, 0x8055AA55);
        int hx = x + Math.round((w - 4) * f);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, active ? SLIDER_HANDLE_HOVER : SLIDER_HANDLE, hx, ty - 1, 4, h + 2, 0xFFFFFFFF);
    }

    /** An on / off switch in Minecraft's look: a slider track, green when on, with the handle at either end. */
    public static void toggle(GuiGraphicsExtractor g, int x, int y, float progress) {
        int w = 16;
        int h = 8;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SLIDER, x, y, w, h, 0xFFFFFFFF);
        if (progress > 0.01f) g.fill(x + 1, y + 1, x + w - 1, y + h - 1, Theme.withAlpha(0xFF55AA55, 0.75f * progress));
        int hx = x + Math.round(progress * (w - 5));
        g.blitSprite(RenderPipelines.GUI_TEXTURED, progress > 0.5f ? SLIDER_HANDLE_HOVER : SLIDER_HANDLE, hx, y, 5, h, 0xFFFFFFFF);
    }
}
