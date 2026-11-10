package com.relaxedqol.client.gui;

/**
 * Settings > Clean Style: a calm, modern PvP-client look - near-black see-through panels, thin faint borders, small
 * rounded corners, soft shadows, the smooth font, grey-scale only (no colour accent, light grey switches) and plain
 * see-through HUD boxes. Like
 * {@link VanillaStyle} it only changes the shared pieces ({@link Theme}, {@link Render2D}, {@link HudStyle},
 * the smooth font), so every menu, window and card follows; the layout stays exactly the same. Vanilla Style wins
 * when both are on (switching one on switches the other off).
 */
public final class CleanStyle {
    private CleanStyle() {}

    // Very see-through dark greys: the game stays clearly visible behind every panel
    public static final int SURFACE = 0xFF1C1D20;
    public static final int PANEL = 0x8C1C1D20;
    public static final int HEADER = 0x99121315;
    public static final int SETTINGS = 0x59101113;
    public static final int BACKGROUND = 0x40000000;
    /** Thin grey outlines around panels, buttons and boxes. */
    public static final int BORDER = 0x4DFFFFFF;
    /** Switches: a green block when on, a red one when off; sliders: a thin dark line with a blue knob. */
    public static final int SWITCH_ON = 0xFF2FAE5B;
    public static final int SWITCH_OFF = 0xFFC8304A;
    public static final int KNOB = 0xFF4A8FE7;
    /** No colours: the accent is a soft white, switches go light grey when on. */
    public static final int ACCENT = 0xFFE6E6E6;
    public static final int ON = 0xFFE6E6E6;
    /** HUD boxes: plain see-through black. */
    public static final int HUD_BOX = 0xFF000000;

    public static boolean on() {
        return com.relaxedqol.client.module.modules.client.ClickGUI.cleanStyle()
            && !com.relaxedqol.client.module.modules.client.ClickGUI.vanillaStyle();
    }
}
