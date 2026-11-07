package com.relaxedqol.client.util;

import com.mojang.blaze3d.platform.InputConstants;

import java.util.Locale;
import java.util.Map;

public final class KeyUtil {
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("rshift", "right.shift"),
        Map.entry("lshift", "left.shift"),
        Map.entry("rctrl", "right.control"),
        Map.entry("lctrl", "left.control"),
        Map.entry("ralt", "right.alt"),
        Map.entry("lalt", "left.alt"),
        Map.entry("esc", "escape"),
        Map.entry("del", "delete"),
        Map.entry("ins", "insert")
    );

    private KeyUtil() {}

    /**
     * Converts a key name typed by the user ("r", "f6", "rshift", "keypad.1", "right.control")
     * into a GLFW key code. Returns -1 if the name is unknown.
     */
    public static int parse(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        key = ALIASES.getOrDefault(key, key);
        try {
            int code = InputConstants.getKey("key.keyboard." + key).getValue();
            return code >= 0 ? code : -1;
        } catch (IllegalArgumentException e) {
            // Unknown name; NumberFormatException is a subclass of IllegalArgumentException
            return -1;
        }
    }

    /** Human readable name of a GLFW key code. */
    public static String name(int key) {
        if (key < 0) return "None";
        return InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
    }
}
