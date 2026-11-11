package com.relaxedqol.client.gui;

/**
 * Settings > Clean Style: a calm, modern PvP-client look - near-black see-through panels, thin faint borders, small
 * a frosted-glass look in the theme's own colours: see-through panels tinted with the accent (Glass Tint %), soft
 * edges, the smooth font, switches and sliders in the accent colour, plain see-through HUD boxes. Like
 * {@link VanillaStyle} it only changes the shared pieces ({@link Theme}, {@link Render2D}, {@link HudStyle},
 * the smooth font), so every menu, window and card follows; the layout stays exactly the same. Vanilla Style wins
 * when both are on (switching one on switches the other off).
 */
public final class CleanStyle {
    private CleanStyle() {}

    /** HUD boxes: plain see-through black. */
    public static final int HUD_BOX = 0xFF000000;
    /** Switch track when off. */
    public static final int SWITCH_OFF_TRACK = 0xFF3A3B40;

    public static boolean on() {
        return (com.relaxedqol.client.module.modules.client.ClickGUI.cleanStyle() || com.relaxedqol.client.module.modules.client.ClickGUI.liquidStyle())
            && !com.relaxedqol.client.module.modules.client.ClickGUI.vanillaStyle();
    }

    /** Windows (Notepad, prompts, pickers...) are much more solid than the menu, so their text stays easy to read. */
    public static int window() {
        return Theme.withAlpha(Theme.PANEL, Math.max(0.88f, ((Theme.PANEL >>> 24) & 0xFF) / 255f));
    }

    /** Liquid Glass Style (Clean Style's look plus the glass finish on everything). */
    public static boolean liquid() {
        return com.relaxedqol.client.module.modules.client.ClickGUI.liquidStyle()
            && !com.relaxedqol.client.module.modules.client.ClickGUI.vanillaStyle();
    }

    /**
     * The finish over a surface that is already filled (windows, menu panels, pop-ups, the hotbar). Clean Style: just
     * a thin light rim. Liquid Glass: a glossy shine on the top part (two soft bands), a bright specular line just
     * inside the top edge, light along the bottom and the rim. A handful of shapes - cheap enough for every frame.
     */
    public static void gloss(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
        if (w < 4 || h < 4) return;
        if (!liquid()) {
            Render2D.roundOutline(g, x, y, w, h, r, 0x30FFFFFF);
            return;
        }
        float k = Math.max(0f, Math.min(2f, Theme.SHEEN));
        if (k > 0.01f) {
            int sheenH = Math.max(6, Math.min(h * 2 / 5, 90));
            int rr = Math.min(r, sheenH / 2);
            Render2D.roundRectTop(g, x, y, w, sheenH, rr, Theme.withAlpha(0xFFFFFFFF, Math.min(0.09f, 0.04f * k)));
            Render2D.roundRectTop(g, x, y, w, sheenH / 2, rr, Theme.withAlpha(0xFFFFFFFF, Math.min(0.08f, 0.035f * k)));
            int inset = Math.max(1, r);
            int lineW = w - inset * 2;
            if (lineW > 4) {
                int a = Math.round(Math.min(0.75f, 0.42f * k) * 255);
                Render2D.horizontalGradient(g, x + inset, y + 1, lineW / 2, 1, 0x00FFFFFF, (a << 24) | 0xFFFFFF);
                Render2D.horizontalGradient(g, x + inset + lineW / 2, y + 1, lineW - lineW / 2, 1, (a << 24) | 0xFFFFFF, 0x00FFFFFF);
                g.fill(x + inset, y + h - 2, x + w - inset, y + h - 1, Theme.withAlpha(0xFFFFFFFF, Math.min(0.12f, 0.05f * k)));
            }
        }
        Render2D.roundOutline(g, x, y, w, h, r, 0x40FFFFFF);
    }
}
