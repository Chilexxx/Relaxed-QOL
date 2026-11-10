package com.relaxedqol.client.setting;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A module setting. Supported value types: Boolean, Integer, Double, String.
 * String settings created with {@link #mode} only accept one of their listed modes.
 * Subclasses: {@link ListSetting} (block/packet lists) and {@link ActionSetting} (buttons).
 */
public class Setting<T> {
    private final String name;
    private final T defaultValue;
    protected T value;
    private final Number min;
    private final Number max;
    private final Number step;
    private final List<String> modes;

    protected Setting(String name, T defaultValue, Number min, Number max, Number step, List<String> modes) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        this.min = min;
        this.max = max;
        this.step = step;
        this.modes = modes;
    }

    /** Boolean or free-text String setting. */
    public Setting(String name, T defaultValue) {
        this(name, defaultValue, null, null, null, null);
    }

    /** Numeric (Integer or Double) setting with bounds. */
    public Setting(String name, T defaultValue, T min, T max, T step) {
        this(name, defaultValue, (Number) min, (Number) max, (Number) step, null);
    }

    /** String setting that cycles through a fixed list of modes. */
    public static Setting<String> mode(String name, String defaultMode, String... modes) {
        return new Setting<>(name, defaultMode, null, null, null, Arrays.asList(modes));
    }

    public String getName() { return name; }
    public T getValue() { return value; }
    public T getDefaultValue() { return defaultValue; }
    public List<String> getModes() { return modes; }
    public Number getMin() { return min; }
    public Number getMax() { return max; }

    private boolean hidden;
    /** What the setting does, shown when you hover it in the menu (null = no explanation). */
    private String help;
    private Runnable onChange;
    private boolean notifying;

    /** Runs after the value changes (menu click, slider, command or config load). */
    public Setting<T> onChange(Runnable action) {
        onChange = action;
        return this;
    }

    protected void changed() {
        if (onChange == null || notifying) return;
        notifying = true;
        try {
            onChange.run();
        } finally {
            notifying = false;
        }
    }

    /** Hidden settings are saved but not shown in the menu (e.g. window positions). */
    public Setting<T> hide() {
        hidden = true;
        return this;
    }

    /** Shown in the menu only while this is true (e.g. a colour that only matters in one mode). */
    private java.util.function.BooleanSupplier showIf;

    /** The setting only shows in the menu while {@code condition} is true (it is still saved either way). */
    public Setting<T> showIf(java.util.function.BooleanSupplier condition) {
        showIf = condition;
        return this;
    }

    /** Why this setting does nothing right now (null = it works), e.g. "Clean Style always uses the smooth font". */
    private java.util.function.Supplier<String> lockedWhy;

    /**
     * The setting is locked while {@code why} gives a reason: the menu fades its row, ignores clicks on it and shows
     * the reason when you point at it (it is still saved and comes back as it was).
     */
    public Setting<T> lockedWhen(java.util.function.Supplier<String> why) {
        lockedWhy = why;
        return this;
    }

    /** The reason this setting does nothing right now, or null when it works. */
    public String lockedReason() {
        if (lockedWhy == null) return null;
        try {
            return lockedWhy.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public boolean isHidden() {
        if (hidden) return true;
        if (showIf == null) return false;
        try {
            return !showIf.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Hidden for good (window positions...), not just because of {@link #showIf}. Reset skips these. */
    public boolean isPermanentlyHidden() { return hidden; }

    /** Explanation shown as a tooltip when the setting is hovered in the menu. */
    public Setting<T> describe(String text) {
        help = text;
        return this;
    }

    public String getHelp() { return help; }

    private boolean prompt;

    /**
     * This text setting is edited in a proper text window (like PlayerAlert's "Add Name") instead of typing
     * straight into the menu row - the row shows the value as a button.
     */
    public Setting<T> prompt() {
        prompt = true;
        return this;
    }

    public boolean usesPrompt() { return prompt; }

    public boolean isBoolean() { return defaultValue instanceof Boolean; }
    public boolean isNumber() { return defaultValue instanceof Number; }
    public boolean isMode() { return modes != null; }
    public boolean isText() { return defaultValue instanceof String && modes == null && !isAction() && !isColor(); }
    /** Colour settings ({@link ColorSetting}): a swatch in the menu that opens the colour picker. */
    public boolean isColor() { return false; }
    public boolean isList() { return false; }
    public boolean isAction() { return false; }
    /** Key bind settings ({@link KeySetting}). */
    public boolean isKey() { return false; }

    /** Whether this setting is written to the config file. */
    public boolean isSaved() { return true; }

    public void reset() {
        value = defaultValue;
        changed();
    }

    public void setValue(T newValue) {
        T before = value;
        applyValue(newValue);
        if (!java.util.Objects.equals(before, value)) changed();
    }

    @SuppressWarnings("unchecked")
    private void applyValue(T newValue) {
        if (newValue == null) return;
        if (isMode()) {
            String wanted = newValue.toString();
            for (String mode : modes) {
                if (mode.equalsIgnoreCase(wanted)) {
                    value = (T) mode;
                    return;
                }
            }
            return;
        }
        if (isNumber() && newValue instanceof Number number) {
            double clamped = Math.max(min.doubleValue(), Math.min(max.doubleValue(), number.doubleValue()));
            if (defaultValue instanceof Integer) {
                value = (T) Integer.valueOf((int) Math.round(clamped));
            } else {
                double stepped = Math.round(clamped / step.doubleValue()) * step.doubleValue();
                value = (T) Double.valueOf(Math.round(stepped * 1000.0) / 1000.0);
            }
            return;
        }
        value = newValue;
    }

    /** Position of a number setting between min (0) and max (1). */
    public double getFraction() {
        if (!isNumber()) return 0;
        double range = max.doubleValue() - min.doubleValue();
        return range <= 0 ? 0 : (((Number) value).doubleValue() - min.doubleValue()) / range;
    }

    /** Sets a number setting from a 0..1 slider position. */
    @SuppressWarnings("unchecked")
    public void setFraction(double fraction) {
        if (!isNumber()) return;
        double f = Math.max(0, Math.min(1, fraction));
        double v = min.doubleValue() + f * (max.doubleValue() - min.doubleValue());
        setValue((T) (defaultValue instanceof Integer ? (Object) (int) Math.round(v) : (Object) v));
    }

    /** Booleans flip, modes cycle, numbers move one step. direction is +1 or -1. */
    @SuppressWarnings("unchecked")
    public void cycle(int direction) {
        T before = value;
        cycleValue(direction);
        if (!java.util.Objects.equals(before, value)) changed();
    }

    @SuppressWarnings("unchecked")
    private void cycleValue(int direction) {
        if (isBoolean()) {
            value = (T) Boolean.valueOf(!(Boolean) value);
        } else if (isMode()) {
            int index = modes.indexOf((String) value);
            int next = Math.floorMod(index + direction, modes.size());
            value = (T) modes.get(next);
        } else if (isNumber()) {
            double next = ((Number) value).doubleValue() + direction * step.doubleValue();
            setValue((T) (defaultValue instanceof Integer ? (Object) (int) Math.round(next) : (Object) next));
        }
    }

    /** Parses a value typed by the user (command or config file). Returns false if it is invalid. */
    public boolean parse(String text) {
        T before = value;
        boolean ok = parseValue(text);
        if (ok && !java.util.Objects.equals(before, value)) changed();
        return ok;
    }

    @SuppressWarnings("unchecked")
    private boolean parseValue(String text) {
        try {
            if (isBoolean()) {
                String t = text.toLowerCase(Locale.ROOT);
                if (t.equals("true") || t.equals("on") || t.equals("yes")) value = (T) Boolean.TRUE;
                else if (t.equals("false") || t.equals("off") || t.equals("no")) value = (T) Boolean.FALSE;
                else return false;
                return true;
            }
            if (defaultValue instanceof Integer) {
                setValue((T) Integer.valueOf((int) Math.round(Double.parseDouble(text))));
                return true;
            }
            if (defaultValue instanceof Double) {
                setValue((T) Double.valueOf(Double.parseDouble(text)));
                return true;
            }
            if (isMode()) {
                for (String mode : modes) {
                    if (mode.equalsIgnoreCase(text)) {
                        value = (T) mode;
                        return true;
                    }
                }
                return false;
            }
            value = (T) text;
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Text stored in the config file. */
    public String serialize() {
        return String.valueOf(value);
    }

    /** Value formatted for display. */
    public String display() {
        if (value instanceof Double d) {
            if (step != null && step.doubleValue() >= 1) return String.valueOf(Math.round(d));
            if (step != null && step.doubleValue() >= 0.1) return String.format(Locale.ROOT, "%.1f", d);
            return String.format(Locale.ROOT, "%.2f", d);
        }
        return String.valueOf(value);
    }

    @Override
    public String toString() {
        return name + ": " + display();
    }
}
