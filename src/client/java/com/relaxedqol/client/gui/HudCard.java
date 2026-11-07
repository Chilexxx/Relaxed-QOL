package com.relaxedqol.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A module that draws a movable card on the HUD (armor status, keystrokes...). OverlayRenderer draws every
 * enabled one and lets you drag / resize it while a menu is open, like the radar.
 */
public interface HudCard {
    HudWindow getWindow();

    /** Draw at virtual HUD size vw x vh (call window.begin / end). */
    void render(GuiGraphicsExtractor g, Font font, int vw, int vh);

    /** The element's own look, set in the HUD editor (null = always the theme's). */
    default CardStyle cardStyle() {
        return null;
    }

    /** False to skip drawing right now (e.g. nothing to show). */
    default boolean showsCard() {
        return true;
    }
}
