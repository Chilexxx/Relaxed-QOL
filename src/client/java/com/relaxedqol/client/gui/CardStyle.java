package com.relaxedqol.client.gui;

import com.relaxedqol.client.setting.ColorSetting;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.Colors;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The look of one HUD element (watermark, info panel, a card...), set in the HUD editor. While
 * {@link #custom} is off the element simply follows the theme; switched on, every value here is used instead:
 * background colour and opacity, text and accent colour, border (colour and strength), corner roundness, shadow and
 * text shadow. The values are hidden module settings, so they are saved with the config like everything else.
 */
public final class CardStyle {
    public final Setting<Boolean> custom;
    public final ColorSetting background;
    public final Setting<Integer> opacity;
    public final ColorSetting text;
    public final ColorSetting accent;
    public final Setting<Boolean> border;
    public final ColorSetting borderColor;
    public final Setting<Integer> borderOpacity;
    public final Setting<Integer> radius;
    public final Setting<Boolean> shadow;
    public final Setting<Boolean> textShadow;

    private final List<Setting<?>> all = new ArrayList<>();

    /**
     * prefix: put in front of every setting name (one module can hold several elements, e.g. "Watermark ");
     * add: the module's addSetting (so the values are saved with that module).
     */
    public CardStyle(String prefix, Consumer<Setting<?>> add) {
        String p = prefix == null ? "" : prefix;
        custom = hidden(new Setting<>(p + "Style Custom", false), add);
        background = color(p + "Style Background", "#151619", add);
        opacity = hidden(new Setting<>(p + "Style Opacity", 84, 0, 100, 1), add);
        text = color(p + "Style Text", "#ECEDEF", add);
        accent = color(p + "Style Accent", "Accent", add);
        border = hidden(new Setting<>(p + "Style Border", true), add);
        borderColor = color(p + "Style Border Colour", "White", add);
        borderOpacity = hidden(new Setting<>(p + "Style Border %", 17, 0, 100, 1), add);
        radius = hidden(new Setting<>(p + "Style Roundness", 8, 0, 16, 1), add);
        shadow = hidden(new Setting<>(p + "Style Shadow", true), add);
        textShadow = hidden(new Setting<>(p + "Style Text Shadow", false), add);
    }

    private <T> Setting<T> hidden(Setting<T> s, Consumer<Setting<?>> add) {
        s.hide();
        add.accept(s);
        all.add(s);
        return s;
    }

    private ColorSetting color(String name, String def, Consumer<Setting<?>> add) {
        ColorSetting c = new ColorSetting(name, def, true, true);
        c.hide();
        add.accept(c);
        all.add(c);
        return c;
    }

    public boolean isCustom() {
        return custom.getValue();
    }

    /** Back to following the theme, with every value at its default. */
    public void reset() {
        for (Setting<?> s : all) s.reset();
    }

    // ------------------------------------------------------------------ resolved values (theme when not custom)

    public int bodyColor() {
        return isCustom() ? 0xFF000000 | Colors.of(background.getValue()) : HudStyle.BODY;
    }

    /** 0..1, or -1 = whatever the card itself asks for (its own opacity setting). */
    public float bodyAlpha() {
        return isCustom() ? opacity.getValue() / 100f : -1f;
    }

    public int textColor() {
        return isCustom() ? 0xFF000000 | Colors.of(text.getValue()) : Theme.TEXT;
    }

    public int accentColor() {
        return isCustom() ? 0xFF000000 | Colors.of(accent.getValue()) : Theme.accent();
    }

    /** The edge colour, or 0 for no edge. */
    public int edgeColor() {
        if (!isCustom()) return Theme.BORDER;
        if (!border.getValue()) return 0;
        return Theme.withAlpha(Colors.of(borderColor.getValue()), borderOpacity.getValue() / 100f);
    }

    public int radius() {
        return isCustom() ? radius.getValue() : Theme.R_SURFACE;
    }

    public boolean hasShadow() {
        return !isCustom() || shadow.getValue();
    }

    public boolean hasTextShadow() {
        return isCustom() && textShadow.getValue();
    }
}
