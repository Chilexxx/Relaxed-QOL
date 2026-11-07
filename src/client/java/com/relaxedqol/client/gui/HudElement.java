package com.relaxedqol.client.gui;

import com.relaxedqol.client.module.Module;

/**
 * One thing on the HUD the HUD editor can move and style - its name, where it is drawn ({@link HudWindow}),
 * its look ({@link CardStyle}, null = always the theme's) and the module it belongs to.
 */
public record HudElement(String name, HudWindow window, CardStyle style, Module owner) {}
