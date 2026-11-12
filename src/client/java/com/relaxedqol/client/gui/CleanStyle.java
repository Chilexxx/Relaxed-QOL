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
    /**
     * Liquid Glass: Minecraft's own menu blur behind the client's menus and windows (when Menu Background Blur is on
     * in the video settings). Once per frame at most; a game that refuses just shows no blur.
     */
    public static void blurBehind(net.minecraft.client.gui.GuiGraphicsExtractor g) {
        if (!liquid()) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.options.getMenuBackgroundBlurriness() < 1) return;
        try {
            g.blurBeforeThisStratum();
        } catch (IllegalStateException ignored) {
            // already blurred this frame
        }
    }

    public static boolean liquid() {
        return com.relaxedqol.client.module.modules.client.ClickGUI.liquidStyle()
            && !com.relaxedqol.client.module.modules.client.ClickGUI.vanillaStyle();
    }

    /**
     * The finish over a surface that is already filled (windows, menu panels, pop-ups, the hotbar). Clean Style: just
     * a thin light rim. Liquid Glass: a soft sheen fading down from the top, a touch of depth at the bottom, and the
     * lit glass edge ({@link GlassRim}). A handful of quads - cheap enough for every frame.
     */
    public static void gloss(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
        if (w < 4 || h < 4) return;
        if (!liquid()) {
            Render2D.roundOutline(g, x, y, w, h, r, 0x30FFFFFF);
            return;
        }
        float k = Math.max(0f, Math.min(2f, Theme.SHEEN));
        if (k > 0.01f) {
            // A soft sheen over the top that fades out smoothly (no bands), and a little depth at the bottom
            int sheenH = Math.max(6, Math.min(h * 45 / 100, 110));
            int rr = Math.max(0, Math.min(r, sheenH / 2));
            int top = Theme.withAlpha(0xFFFFFFFF, Math.min(0.13f, 0.06f * k));
            if (rr >= 2) Render2D.roundRectTop(g, x, y, w, rr, rr, top);
            else g.fill(x, y, x + w, y + 1, top);
            int from = y + Math.max(1, rr);
            if (y + sheenH > from) g.fillGradient(x, from, x + w, y + sheenH, top, top & 0x00FFFFFF);
            int depthTop = y + h - Math.max(4, h / 4);
            int depthEnd = y + h - Math.max(1, r);
            if (depthEnd - depthTop > 2 && depthTop > y + sheenH) {
                g.fillGradient(x, depthTop, x + w, depthEnd, 0x00000000, Theme.withAlpha(0xFF000000, Math.min(0.10f, 0.05f * k)));
            }
        }
        // The lit glass edge: bright where the light hits the top-left corner, a softer catch bottom-right, and a
        // soft glow just inside where the glass bends the light
        if (!GlassRim.surface(g, x, y, w, h, r, Math.min(1f, 0.55f + 0.30f * k))) {
            Render2D.roundOutline(g, x, y, w, h, r, 0x48FFFFFF);
        }
    }

    /**
     * Liquid Glass on a small piece (a button, chip, switch, a lit module row): its lit glass edge, as strong as
     * {@code amount} (0..1). Nothing in the other styles.
     */
    public static void glassControl(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, int w, int h, int r, float amount) {
        if (!liquid() || amount <= 0.02f) return;
        GlassRim.control(g, x, y, w, h, r, Math.min(1f, 0.65f * amount));
    }
}
