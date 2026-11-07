package com.relaxedqol.client.setting;

import com.relaxedqol.client.util.KeyUtil;

/**
 * A key bind setting (GLFW key code, -1 = none). In the menu: click it, then press a key
 * (Esc / Delete / Backspace = back to the default: no key). In commands/config: a key name ("rshift", "f6") or a number.
 */
public class KeySetting extends Setting<Integer> {
    private final boolean allowNone;

    /** allowNone false: Delete / Backspace puts the default back instead of clearing (e.g. the menu key). */
    public KeySetting(String name, int defaultKey, boolean allowNone) {
        super(name, defaultKey, null, null, null, null);
        this.allowNone = allowNone;
    }

    public boolean allowsNone() {
        return allowNone;
    }

    public int getKey() {
        return getValue() == null ? -1 : getValue();
    }

    @Override
    public boolean isNumber() {
        return false;
    }

    @Override
    public boolean isKey() {
        return true;
    }

    @Override
    public void cycle(int direction) {
        // Keys are picked by pressing them in the menu
    }

    @Override
    public boolean parse(String text) {
        String t = text.trim();
        if (t.equalsIgnoreCase("none") || t.equals("-1")) {
            if (!allowNone) return false;
            setValue(-1);
            return true;
        }
        try {
            setValue(Integer.parseInt(t));
            return true;
        } catch (NumberFormatException e) {
            int key = KeyUtil.parse(t);
            if (key < 0) return false;
            setValue(key);
            return true;
        }
    }

    @Override
    public String display() {
        return getKey() < 0 ? "None" : KeyUtil.name(getKey());
    }
}
