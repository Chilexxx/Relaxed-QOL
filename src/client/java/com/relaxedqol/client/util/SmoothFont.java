package com.relaxedqol.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * "Smooth Font": smooth, anti-aliased text (Inter, shipped with the client) instead of Minecraft's pixel
 * font - in every client window (Notes, PlayerAlert, Accounts...), the Notes card and the other
 * places that ask for it. How it works:
 * - the client's font files (assets/relaxed-qol/font) make the game build Inter at 1x..8x detail;
 * - while a window draws ({@link #begin} / {@link #end}), every piece of text in the plain game font is switched to
 *   the Inter version made for exactly the number of screen pixels it lands on, so it is sharp and smooth at any GUI
 *   scale, window scale or card size; widths are measured the same way, so everything stays aligned;
 * - characters Inter does not have (hearts, emoji, box lines) still come from the game's own font;
 * - if the font cannot be loaded for any reason, nothing changes (the normal pixel font is used).
 */
public final class SmoothFont {
    private SmoothFont() {}

    private static final int MAX = 8;
    private static int depth;
    private static final Object[] REGULAR = new Object[MAX + 1];
    private static final Object[] BOLD = new Object[MAX + 1];
    private static Method withFont;
    private static Method withBold;
    private static Object defaultFont;
    private static boolean broken;
    private static boolean looked;
    private static long checkedAt;
    private static boolean ready;

    /** Text drawn / measured from here until {@link #end} uses the smooth font. Calls nest. */
    public static void begin() {
        if (depth++ == 0) swapWidths(true);
    }

    public static void end() {
        if (depth <= 0) return;
        if (--depth == 0) swapWidths(false);
    }

    // ------------------------------------------------------------------ widths

    /** The game font's width provider (inside its string splitter) and our stand-in, found by type, not by name. */
    private static java.lang.reflect.Field providerField;
    private static Object splitter;
    private static Object originalProvider;
    private static Object smoothProvider;
    private static Object swappedFont;
    private static boolean widthsBroken;

    /**
     * While a window draws, the game font measures the smooth font (centred / right-aligned / cut-off text lines up).
     * Swapped in only between begin and end, so the rest of the game pays nothing for it.
     */
    private static void swapWidths(boolean on) {
        if (widthsBroken) return;
        try {
            if (!on) {
                if (splitter != null && originalProvider != null && providerField.get(splitter) == smoothProvider) {
                    providerField.set(splitter, originalProvider);
                }
                return;
            }
            if (!enabled() || !lookUp()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.font == null) return;
            if (swappedFont != mc.font) prepare(mc.font);
            if (smoothProvider != null && ready()) providerField.set(splitter, smoothProvider);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            widthsBroken = true;
        }
    }

    private static void prepare(Object font) throws ReflectiveOperationException {
        swappedFont = font;
        splitter = null;
        smoothProvider = null;
        Class<?> splitterType = net.minecraft.client.StringSplitter.class;
        for (java.lang.reflect.Field f : font.getClass().getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == splitterType) {
                f.setAccessible(true);
                splitter = f.get(font);
            }
        }
        if (splitter == null) throw new NoSuchFieldException("Font splitter");
        for (java.lang.reflect.Field f : splitterType.getDeclaredFields()) {
            Class<?> type = f.getType();
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !type.isInterface() || type.getEnclosingClass() != splitterType) continue;
            Method single = null;
            int abstracts = 0;
            for (Method m : type.getMethods()) {
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                    single = m;
                    abstracts++;
                }
            }
            if (abstracts != 1 || single.getParameterCount() != 2 || single.getParameterTypes()[1] != Style.class) continue;
            f.setAccessible(true);
            providerField = f;
            originalProvider = f.get(splitter);
            Method call = single;
            Object original = originalProvider;
            smoothProvider = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.equals(call)) {
                    args[1] = measureStyle((Style) args[1]);
                    return call.invoke(original, args);
                }
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "SmoothWidths";
                    default -> method.invoke(original, args);
                };
            });
            return;
        }
        throw new NoSuchFieldException("width provider");
    }

    /** Whether the smooth font is in use right now (inside begin / end, switched on, loaded). */
    public static boolean active() {
        return depth > 0 && enabled() && ready();
    }

    private static boolean enabled() {
        // Vanilla Style always uses Minecraft's pixel font
        return com.relaxedqol.client.module.modules.client.ClickGUI.smoothFont()
            && !com.relaxedqol.client.module.modules.client.ClickGUI.vanillaStyle();
    }

    /** Whether the client's own UI (a window, the menu, a HUD card) is being drawn right now. */
    public static boolean inClientUi() {
        return depth > 0;
    }

    private static boolean lookUp() {
        if (looked) return !broken;
        looked = true;
        try {
            defaultFont = Style.class.getMethod("getFont").invoke(Style.EMPTY);
            for (Method m : Style.class.getMethods()) {
                if (m.getName().equals("withFont") && m.getParameterCount() == 1) withFont = m;
                if (m.getName().equals("withBold") && m.getParameterCount() == 1) withBold = m;
            }
            if (withFont == null) throw new NoSuchMethodException("Style.withFont");
            for (int n = 1; n <= MAX; n++) {
                REGULAR[n] = description(Identifier.fromNamespaceAndPath("relaxed-qol", "ui_" + n));
                BOLD[n] = description(Identifier.fromNamespaceAndPath("relaxed-qol", "ui_bold_" + n));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("Smooth Font: this game version names its fonts differently - using the pixel font", e);
        }
        return !broken;
    }

    /** What Style.withFont takes in this game version: the id itself, or a font description made from it. */
    private static Object description(Identifier id) throws ReflectiveOperationException {
        Class<?> type = withFont.getParameterTypes()[0];
        if (type.isInstance(id)) return id;
        // A small sealed type with a "Resource(Identifier)" record inside
        for (Class<?> inner : type.getDeclaredClasses()) {
            if (!type.isAssignableFrom(inner)) continue;
            for (Constructor<?> c : inner.getDeclaredConstructors()) {
                if (c.getParameterCount() == 1 && c.getParameterTypes()[0] == Identifier.class) {
                    c.setAccessible(true);
                    return c.newInstance(id);
                }
            }
        }
        throw new NoSuchMethodException("font description for " + type.getName());
    }

    /**
     * Loaded for real? (Inter's "i" is far narrower than its "W"; a font that failed to load draws every character
     * as the same box.) Checked again every few seconds until it works (resource packs may still be loading).
     */
    private static boolean ready() {
        if (!lookUp()) return false;
        long now = System.currentTimeMillis();
        if (ready || now - checkedAt < 3000) return ready;
        checkedAt = now;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.font == null) return false;
            Style s = (Style) withFont.invoke(Style.EMPTY, REGULAR[2]);
            int narrow = mc.font.width(Component.literal("iiiiiiii").withStyle(s));
            int wide = mc.font.width(Component.literal("WWWWWWWW").withStyle(s));
            ready = narrow > 0 && narrow * 2 < wide;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ready = false;
        }
        return ready;
    }

    /** Which detail level fits: screen pixels per font pixel, rounded, 1..8. */
    public static int level(float screenPixelsPerUnit) {
        return Math.max(1, Math.min(MAX, Math.round(screenPixelsPerUnit)));
    }

    /** The style in the smooth font at this detail level (styles that already use another font stay as they are). */
    /** The last styles switched, per detail level (the same few styles come by for every letter). */
    @SuppressWarnings("unchecked")
    private static final java.util.Map<Style, Style>[] CACHE = new java.util.Map[MAX + 1];

    public static Style smooth(Style style, int level) {
        if (broken || style == null) return style;
        int lv = Math.max(1, Math.min(MAX, level));
        java.util.Map<Style, Style> cache = CACHE[lv];
        if (cache == null) {
            cache = new java.util.HashMap<>();
            CACHE[lv] = cache;
        }
        if (cache.size() > 512) cache.clear();
        Style hit = cache.get(style);
        if (hit != null) return hit;
        Style made = smoothNow(style, lv);
        cache.put(style, made);
        return made;
    }

    private static Style smoothNow(Style style, int level) {
        try {
            if (!Objects.equals(style.getFont(), defaultFont)) return style;
            boolean bold = style.isBold();
            Style s = bold && withBold != null ? (Style) withBold.invoke(style, Boolean.FALSE) : style;
            return (Style) withFont.invoke(s, (bold ? BOLD : REGULAR)[Math.max(1, Math.min(MAX, level))]);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return style;
        }
    }

    /** For measuring widths: inside begin / end, plain-font text is measured as smooth text. */
    public static Style measureStyle(Style style) {
        if (depth <= 0 || !active()) return style;
        return smooth(style, guiLevel());
    }

    /** The game's GUI scale (screen pixels per GUI unit). */
    public static float guiScale() {
        try {
            return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        } catch (RuntimeException e) {
            return 2;
        }
    }

    private static int guiLevel() {
        try {
            return level(Minecraft.getInstance().getWindow().getGuiScale());
        } catch (RuntimeException e) {
            return 2;
        }
    }

    /** Text about to be drawn: the same characters in the smooth font (the level is fixed now, drawing happens later). */
    public static FormattedCharSequence wrap(FormattedCharSequence text, float screenPixelsPerUnit) {
        if (text == null) return null;
        int level = level(screenPixelsPerUnit);
        return sink -> text.accept((position, style, codepoint) -> sink.accept(position, smooth(style, level), codepoint));
    }

    /** Screen pixels per GUI unit right now: GUI scale times the scale of the current pose. */
    public static float pixelsPer(org.joml.Matrix3x2f pose) {
        float gs;
        try {
            gs = Minecraft.getInstance().getWindow().getGuiScale();
        } catch (RuntimeException e) {
            gs = 2;
        }
        float sx = (float) Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01());
        float sy = (float) Math.sqrt(pose.m10() * pose.m10() + pose.m11() * pose.m11());
        return gs * Math.max(0.05f, (sx + sy) / 2f);
    }
}
