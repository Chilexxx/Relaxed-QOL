package com.relaxedqol.client.util;

import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.setting.ColorSetting;
import com.relaxedqol.client.setting.Setting;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Named colours for colour settings (block outline, keystrokes, chat, sky...). every colour setting is a
 * {@link ColorSetting} - a swatch that opens a palette to click a colour from, no hex codes to type anywhere.
 * "Accent" follows the menu colour, "Rainbow" slowly cycles; everything else is a fixed colour.
 */
public final class Colors {
    /** The palette shown in the colour picker, row by row (8 per row). */
    public static final String[] PALETTE = {
        // vivid
        "Red", "Orange", "Amber", "Yellow", "Lime", "Green", "Teal", "Cyan",
        "Sky Blue", "Blue", "Indigo", "Violet", "Purple", "Magenta", "Pink", "Rose",
        // soft / pastel
        "Salmon", "Peach", "Cream", "Mint", "Aqua", "Baby Blue", "Lavender", "Bubblegum",
        // deep
        "Crimson", "Rust", "Bronze", "Forest", "Emerald", "Ocean", "Navy", "Plum",
        // neutrals
        "White", "Silver", "Grey", "Slate", "Graphite", "Charcoal", "Ink", "Black"};

    private static final Map<String, Integer> NAMED = new LinkedHashMap<>();

    static {
        put("Red", 0xFF4D5E); put("Orange", 0xFF9F40); put("Amber", 0xFFC233); put("Yellow", 0xFFE15A);
        put("Lime", 0xA3F25C); put("Green", 0x34D399); put("Teal", 0x2DD4BF); put("Cyan", 0x38BDF8);
        put("Sky Blue", 0x60A5FA); put("Blue", 0x3B82F6); put("Indigo", 0x6366F1); put("Violet", 0x9A6BFF);
        put("Purple", 0xA78BFA); put("Magenta", 0xD946EF); put("Pink", 0xF472B6); put("Rose", 0xFB7185);
        put("Salmon", 0xFFB3B3); put("Peach", 0xFFD6A5); put("Cream", 0xFDFFB6); put("Mint", 0xB9FBC0);
        put("Aqua", 0x9BF6FF); put("Baby Blue", 0xA0C4FF); put("Lavender", 0xBDB2FF); put("Bubblegum", 0xFFC6FF);
        put("Crimson", 0x9F1239); put("Rust", 0x9A3412); put("Bronze", 0x854D0E); put("Forest", 0x3F6212);
        put("Emerald", 0x047857); put("Ocean", 0x155E75); put("Navy", 0x1E3A8A); put("Plum", 0x581C87);
        put("White", 0xFFFFFF); put("Silver", 0xE5E7EB); put("Grey", 0x9CA3AF); put("Slate", 0x6B7280);
        put("Graphite", 0x4B5563); put("Charcoal", 0x2A2D36); put("Ink", 0x161822); put("Black", 0x0B0B10);
        // Older names (configs from, menu accents) that still mean a colour
        put("Gray", 0x9CA3AF);
        put("Light Grey", 0xE5E7EB);
    }

    private static void put(String name, int rgb) {
        NAMED.put(name, 0xFF000000 | rgb);
    }

    private Colors() {}

    /** A colour setting (swatch + picker). defaultName: a palette name, "Accent", "Rainbow" or "#RRGGBB". */
    public static ColorSetting setting(String name, String defaultName) {
        return new ColorSetting(name, defaultName);
    }

    /** The palette spelling of a name ("sky blue" -> "Sky Blue"), "Accent" / "Rainbow", or null when unknown. */
    public static String canonicalName(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.equalsIgnoreCase("Accent")) return "Accent";
        if (t.equalsIgnoreCase("Rainbow")) return "Rainbow";
        for (String n : NAMED.keySet()) if (n.equalsIgnoreCase(t)) return n.equals("Gray") ? "Grey" : n;
        // Menu accents that are not in the palette under that name
        return switch (t.toLowerCase(Locale.ROOT)) {
            case "light blue" -> "Sky Blue";
            case "light gray", "light grey" -> "Silver";
            case "dark gray", "dark grey" -> "Graphite";
            default -> null;
        };
    }

    /** What to call a value in the menu: its palette name, or the hex code for anything else. */
    public static String nameOf(String value) {
        if (value == null) return "White";
        String named = canonicalName(value);
        if (named != null) return named;
        int c = parseHex(value, 0xFFFFFFFF);
        for (Map.Entry<String, Integer> e : NAMED.entrySet()) if ((e.getValue() & 0xFFFFFF) == (c & 0xFFFFFF)) return e.getKey();
        return String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF);
    }

    public static int of(String name) {
        return of(name, null);
    }

    /** The ARGB colour for a name or "#RRGGBB" (customHex is only read for the old "Custom" value). */
    public static int of(String name, String customHex) {
        if (name == null) return 0xFFFFFFFF;
        String t = name.trim();
        if (t.equalsIgnoreCase("Accent")) return Theme.accent();
        if (t.equalsIgnoreCase("Rainbow")) {
            float hue = (System.currentTimeMillis() % 6000L) / 6000f;
            return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, 0.6f, 1f) & 0xFFFFFF);
        }
        if (t.equalsIgnoreCase("Custom")) return parseHex(customHex, 0xFFFFFFFF);
        if (t.startsWith("#") || t.startsWith("0x")) return parseHex(t, 0xFFFFFFFF);
        String named = canonicalName(t);
        if (named != null) {
            Integer c = NAMED.get(named);
            if (c != null) return c;
        }
        return 0xFFFFFFFF;
    }

    /** The palette colour of a name (no Accent / Rainbow), or 0 when it is not in the palette. */
    public static int paletteColor(String name) {
        Integer c = NAMED.get(name);
        return c == null ? 0 : c;
    }

    public static int parseHex(String text, int fallback) {
        if (text == null) return fallback;
        String t = text.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        try {
            if (t.length() == 6) return 0xFF000000 | Integer.parseInt(t, 16);
            if (t.length() == 8) return (int) Long.parseLong(t, 16);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return fallback;
    }

    public static int withAlpha(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0xFFFFFF);
    }

    /** Green (full) -> yellow -> red (empty), for durability and health. */
    public static int health(float fraction) {
        float f = Math.max(0f, Math.min(1f, fraction));
        int r;
        int g;
        if (f > 0.5f) {
            r = Math.round(255 * (1f - f) * 2f);
            g = 220;
        } else {
            r = 255;
            g = Math.round(220 * f * 2f);
        }
        return 0xFF000000 | (Math.min(255, r + 40) << 16) | (g << 8) | 0x50;
    }

    /** Kept for old code paths: a colour setting (the hex is never typed any more). */
    public static Setting<String> colorSetting(String name, String defaultName) {
        return setting(name, defaultName);
    }
}
