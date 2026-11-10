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
        return com.relaxedqol.client.module.modules.client.ClickGUI.cleanStyle()
            && !com.relaxedqol.client.module.modules.client.ClickGUI.vanillaStyle();
    }

    /** Windows (Notepad, prompts, pickers...) are much more solid than the menu, so their text stays easy to read. */
    public static int window() {
        return Theme.withAlpha(Theme.PANEL, Math.max(0.88f, ((Theme.PANEL >>> 24) & 0xFF) / 255f));
    }
}
