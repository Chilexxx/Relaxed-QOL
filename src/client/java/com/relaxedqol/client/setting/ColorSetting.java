package com.relaxedqol.client.setting;

import com.relaxedqol.client.util.Colors;

/**
 * A colour, picked from a palette in the colour picker (no hex codes to type). Shown in the menu as a swatch;
 * clicking it opens the picker. The value is a palette name ("Accent", "Rainbow", "Red"...) or "#RRGGBB".
 * Old configs that saved "Custom" (with a separate hex setting) keep the default colour.
 */
public class ColorSetting extends Setting<String> {
    private final boolean allowAccent;
    private final boolean allowRainbow;

    public ColorSetting(String name, String defaultColor) {
        this(name, defaultColor, true, true);
    }

    /** allowAccent: offer "Accent" (the menu colour); allowRainbow: offer the slowly cycling "Rainbow". */
    public ColorSetting(String name, String defaultColor, boolean allowAccent, boolean allowRainbow) {
        super(name, defaultColor);
        this.allowAccent = allowAccent;
        this.allowRainbow = allowRainbow;
    }

    @Override
    public boolean isColor() {
        return true;
    }

    public boolean allowsAccent() {
        return allowAccent;
    }

    public boolean allowsRainbow() {
        return allowRainbow;
    }

    /** The colour as ARGB (fully opaque). */
    public int argb() {
        return Colors.of(getValue());
    }

    /** Name shown next to the swatch ("Accent", "Sky Blue", "#5B7CFF" for colours outside the palette). */
    public String label() {
        return Colors.nameOf(getValue());
    }

    @Override
    public void setValue(String newValue) {
        String v = clean(newValue);
        if (v != null) super.setValue(v);
    }

    @Override
    public boolean parse(String text) {
        String v = clean(text);
        if (v == null) return text != null && text.trim().equalsIgnoreCase("Custom"); // old "Custom": keep the default
        String before = getValue();
        value = v;
        if (!java.util.Objects.equals(before, value)) changed();
        return true;
    }

    /** A valid value, or null for something that is not a colour. */
    private String clean(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("Custom")) return null;
        if (t.startsWith("#") || t.startsWith("0x")) {
            int c = Colors.parseHex(t, 0);
            if (c == 0 && !t.replace("#", "").replace("0x", "").matches("0+")) return null;
            return String.format(java.util.Locale.ROOT, "#%06X", c & 0xFFFFFF);
        }
        String named = Colors.canonicalName(t);
        if (named == null) return null;
        if (named.equals("Accent") && !allowAccent) return null;
        if (named.equals("Rainbow") && !allowRainbow) return null;
        return named;
    }

    @Override
    public void cycle(int direction) {
        // Picked in the colour picker
    }

    @Override
    public String display() {
        return label();
    }
}
