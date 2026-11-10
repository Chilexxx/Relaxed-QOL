package com.relaxedqol.client.gui;

/**
 * Settings > Clean Style: a calm, modern PvP-client look - near-black see-through panels, thin faint borders, small
 * rounded corners, soft shadows, the smooth font, green / grey switches and plain see-through HUD boxes. Like
 * {@link VanillaStyle} it only changes the shared pieces ({@link Theme}, {@link Render2D}, {@link HudStyle},
 * the smooth font), so every menu, window and card follows; the layout stays exactly the same. Vanilla Style wins
 * when both are on (switching one on switches the other off).
 */
public final class CleanStyle {
    private CleanStyle() {}

    public static final int SURFACE = 0xFF16171A;
    public static final int PANEL = 0xD9141518;
    public static final int HEADER = 0xE61A1B1F;
    public static final int SETTINGS = 0xD90F1012;
    public static final int BACKGROUND = 0xD90E0F11;
    /** The "on" colour of switches. */
    public static final int ON = 0xFF3FB950;
    /** HUD boxes: plain see-through black. */
    public static final int HUD_BOX = 0xFF000000;

    public static boolean on() {
        return com.relaxedqol.client.module.modules.client.ClickGUI.cleanStyle()
            && !com.relaxedqol.client.module.modules.client.ClickGUI.vanillaStyle();
    }
}
