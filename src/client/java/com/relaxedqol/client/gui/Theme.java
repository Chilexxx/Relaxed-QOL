package com.relaxedqol.client.gui;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.modules.client.ClickGUI;

/**
 * The client's design tokens: colours, corner radii and text colours shared by the menu, windows, Quick Panel and
 * HUD. Change a value here and everything follows - drawing code should not invent its own greys or radii.
 *
 * (polish pass): one neutral grey ramp (the text greys were tinted lavender and clashed with the neutral
 * graphite surfaces), calmer accents and status colours, and a small fixed set of corner radii.
 */
public final class Theme {
    // The tokens are no longer fixed - refresh works them out from the theme settings (HUD editor > Theme)
    // a few times a second, so a change shows everywhere at once. The values below are the defaults.

    // ---------------------------------------------------------------- surfaces
    public static int BACKGROUND = 0xF2111214;
    public static int PANEL = 0xF2151619;
    public static int PANEL_HEADER = 0xFA1C1D21;
    public static int SETTINGS_BG = 0xF20D0E10;
    public static int ROW_HOVER = 0x12FFFFFF;
    /** Unlit controls: switch / slider tracks, value buttons. */
    public static int TRACK = 0xFF2E3036;
    /** The clean (LoverFella Mode off) window body. */
    public static int WINDOW_BODY = 0xFF18191C;

    // ---------------------------------------------------------------- lines
    /** Outline around panels, windows and cards. */
    public static int BORDER = 0x2BFFFFFF;
    /** Dividers and the outline of small controls. */
    public static int HAIRLINE = 0x1CFFFFFF;
    /** Fill of a quiet button / key cap. */
    public static int PILL = 0x14FFFFFF;

    // ---------------------------------------------------------------- text (one ramp, from the theme's text colour)
    public static int TEXT = 0xFFECEDEF;
    /** Button labels and other text that must read clearly but is not the main content. */
    public static int TEXT_SECONDARY = 0xFFC4C6CC;
    public static int TEXT_DIM = 0xFFA0A3AA;
    public static int TEXT_FAINT = 0xFF6C7078;

    // ---------------------------------------------------------------- status (fixed: they mean something)
    public static final int SUCCESS = 0xFF3DBE7E;
    public static final int DANGER = 0xFFE5534B;
    public static final int WARNING = 0xFFE3A13B;

    // ---------------------------------------------------------------- corner radii (Roundness % scales them)
    /** Small controls: buttons, value chips, text fields, key caps. */
    public static int R_CONTROL = 4;
    /** Rows and inner blocks. */
    public static int R_ROW = 6;
    /** Windows, cards, menus. */
    public static int R_SURFACE = 8;

    // ---------------------------------------------------------------- effects
    /** Drop shadow strength (1 = normal). */
    public static float SHADOW = 1f;
    /** Animation speed (1 = normal, 0 = no animations: everything changes at once). */
    public static float ANIM = 1f;
    /** The surface colour the theme is built from (opaque). */
    public static int SURFACE = 0xFF18191C;

    private static long refreshedAt;

    /**
     * Works the tokens out from the theme settings. Cheap, and it only really runs a few times a second, so it is
     * called from every place that starts drawing (and from {@link #accent}).
     */
    public static void refresh() {
        long now = System.currentTimeMillis();
        if (now - refreshedAt < 100) return;
        refreshedAt = now;
        ClickGUI gui = gui();
        if (gui == null) return;
        if (VanillaStyle.on()) {
            // Minecraft's own menu colours, square corners, no shadows
            SURFACE = 0xFF101010;
            WINDOW_BODY = 0xFF101010;
            BACKGROUND = 0xC0000000;
            PANEL = VanillaStyle.PANEL;
            PANEL_HEADER = VanillaStyle.HEADER;
            SETTINGS_BG = 0xC0000000;
            TRACK = 0xFF000000;
            BORDER = 0xFF5A5A5A;
            HAIRLINE = 0xFF3A3A3A;
            ROW_HOVER = 0x30FFFFFF;
            PILL = 0x20FFFFFF;
            TEXT = VanillaStyle.TEXT;
            TEXT_SECONDARY = 0xFFE0E0E0;
            TEXT_DIM = VanillaStyle.TEXT_GRAY;
            TEXT_FAINT = VanillaStyle.TEXT_DARK;
            R_CONTROL = 0;
            R_ROW = 0;
            R_SURFACE = 0;
            SHADOW = 0f;
            ANIM = gui.themeAnim().getValue() / 100f;
            return;
        }
        if (CleanStyle.on()) {
            // Clean Style: near-black see-through panels, thin faint lines, small corners, soft shadows
            SURFACE = CleanStyle.SURFACE;
            WINDOW_BODY = CleanStyle.SURFACE;
            BACKGROUND = CleanStyle.BACKGROUND;
            PANEL = CleanStyle.PANEL;
            PANEL_HEADER = CleanStyle.HEADER;
            SETTINGS_BG = CleanStyle.SETTINGS;
            TRACK = 0xFF26272B;
            BORDER = CleanStyle.BORDER;
            HAIRLINE = 0x26FFFFFF;
            ROW_HOVER = 0x14FFFFFF;
            PILL = 0x1FFFFFFF;
            TEXT = 0xFFF5F5F7;
            TEXT_SECONDARY = 0xFFC8CAD0;
            // Brighter greys than the normal theme: the panels are see-through, the text must stay readable
            TEXT_DIM = 0xFFB4B7BE;
            TEXT_FAINT = 0xFF8C8F97;
            R_CONTROL = 2;
            R_ROW = 2;
            R_SURFACE = 3;
            SHADOW = 0f;
            ANIM = gui.themeAnim().getValue() / 100f;
            return;
        }
        try {
            int surface = 0xFF000000 | com.relaxedqol.client.util.Colors.of(gui.themeSurface().getValue());
            int text = 0xFF000000 | com.relaxedqol.client.util.Colors.of(gui.themeText().getValue());
            float border = gui.themeBorder().getValue() / 100f;
            float round = gui.themeRound().getValue() / 100f;
            SURFACE = surface;
            WINDOW_BODY = surface;
            BACKGROUND = withAlpha(shade(surface, -0.10f), 0.95f);
            PANEL = withAlpha(shade(surface, -0.04f), 0.95f);
            PANEL_HEADER = withAlpha(shade(surface, 0.02f), 0.98f);
            SETTINGS_BG = withAlpha(shade(surface, -0.30f), 0.95f);
            TRACK = shade(surface, 0.10f);
            BORDER = withAlpha(lineColor(surface), border);
            HAIRLINE = withAlpha(lineColor(surface), border * 0.65f);
            ROW_HOVER = withAlpha(lineColor(surface), 0.07f);
            PILL = withAlpha(lineColor(surface), 0.08f);
            TEXT = text;
            TEXT_SECONDARY = mix(surface, text, 0.80f);
            TEXT_DIM = mix(surface, text, 0.62f);
            TEXT_FAINT = mix(surface, text, 0.40f);
            R_CONTROL = Math.round(4 * round);
            R_ROW = Math.round(6 * round);
            R_SURFACE = Math.round(8 * round);
            SHADOW = gui.themeShadow().getValue() / 100f;
            ANIM = gui.themeAnim().getValue() / 100f;
        } catch (RuntimeException e) {
            // keep the last good values
        }
    }

    /** Lines are light on a dark surface and dark on a light one. */
    private static int lineColor(int surface) {
        return luminance(surface) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** 0 (black) .. 1 (white). */
    public static float luminance(int c) {
        return (0.2126f * ((c >> 16) & 0xFF) + 0.7152f * ((c >> 8) & 0xFF) + 0.0722f * (c & 0xFF)) / 255f;
    }

    /** Lighter (amount > 0, towards white) or darker (amount < 0, towards black), alpha kept. */
    public static int shade(int c, float amount) {
        int a = c & 0xFF000000;
        return a | (mix(c, amount > 0 ? 0xFFFFFFFF : 0xFF000000, Math.abs(amount)) & 0xFFFFFF);
    }

    /** How long an animation of {@code ms} lasts with the theme's Animation % (0 = instantly). */
    public static float animMs(float ms) {
        return ANIM <= 0.01f ? 0f : ms / ANIM;
    }

    private Theme() {}

    private static ClickGUI gui() {
        return RelaxedQolClient.moduleManager != null ? RelaxedQolClient.moduleManager.getModule(ClickGUI.class) : null;
    }

    /** 0..1 from the ClickGUI "Background %" setting. */
    public static float opacity() {
        ClickGUI gui = gui();
        return gui != null ? gui.getOpacityPercent() / 100f : 0.45f;
    }

    /**
     * How solid windows are (0..1), from Menu background %: 45 % (the default) keeps the usual 93 %, 0 % makes
     * them clearly see-through (55 %), 100 % fully solid. (Windows used to stay at 93 % or more whatever it was set to.)
     */
    public static float windowAlpha() {
        return Math.max(0.55f, Math.min(1f, 0.93f + (opacity() - 0.45f) * 0.7f));
    }

    /** See-through panel background. */
    public static int panel() {
        if (LoverFx.on()) return withAlpha(0xFF0D1A3A, Math.max(0.55f, opacity()));
        if (VanillaStyle.on()) return VanillaStyle.PANEL;
        if (CleanStyle.on()) return CleanStyle.PANEL;
        return withAlpha(shade(SURFACE, -0.04f), opacity());
    }

    /** Panel title bar, a little more solid than the body. */
    public static int header() {
        if (LoverFx.on()) return withAlpha(0xFF15295C, Math.min(1f, Math.max(0.55f, opacity()) + 0.2f));
        if (VanillaStyle.on()) return VanillaStyle.HEADER;
        if (CleanStyle.on()) return CleanStyle.HEADER;
        return withAlpha(shade(SURFACE, 0.02f), Math.min(1f, opacity() + 0.18f));
    }

    /** Background behind expanded settings. */
    public static int settingsBg() {
        if (LoverFx.on()) return withAlpha(0xFF071230, Math.min(1f, Math.max(0.55f, opacity()) * 0.85f));
        if (VanillaStyle.on()) return 0xC0000000;
        if (CleanStyle.on()) return CleanStyle.SETTINGS;
        return withAlpha(shade(SURFACE, -0.30f), Math.min(1f, opacity() * 0.8f));
    }

    /** Accent colour; offset shifts the hue in Rainbow mode (used for gradients down lists). */
    public static int accent(int offset) {
        refresh();
        // LoverFella Mode - heart red and mustache cyan (in every style)
        if (LoverFx.on()) return LoverFx.accent(offset);
        // Vanilla Style: no colour accent - white, like Minecraft's selected / focused outlines
        if (VanillaStyle.on()) return 0xFFFFFFFF;
        // Clean Style: no colours anywhere - a soft white instead of an accent
        if (CleanStyle.on()) return CleanStyle.ACCENT;
        String mode = "Violet";
        if (RelaxedQolClient.moduleManager != null) {
            ClickGUI gui = RelaxedQolClient.moduleManager.getModule(ClickGUI.class);
            if (gui != null) mode = gui.getAccentMode();
        }
        return switch (mode) {
            case "Blue" -> 0xFF4F8DF7;
            case "Cyan" -> 0xFF2BB8D1;
            case "Mint" -> 0xFF2FBF8F;
            case "Rose" -> 0xFFEF5A7A;
            case "Amber" -> 0xFFF0A63A;
            case "Rainbow" -> {
                float hue = ((System.currentTimeMillis() % 6000L) / 6000f + offset * 0.035f) % 1f;
                yield 0xFF000000 | net.minecraft.util.Mth.hsvToRgb(hue, 0.55f, 1.0f);
            }
            case "Violet" -> 0xFF8B7CF6;
            // Any other colour from the palette (or a #RRGGBB from an old config)
            default -> mode.equalsIgnoreCase("Accent") ? 0xFF8B7CF6 : com.relaxedqol.client.util.Colors.of(mode);
        };
    }

    public static int accent() {
        return accent(0);
    }

    /** Replaces the alpha of an ARGB colour (alpha 0..1). */
    public static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** Multiplies the existing alpha of a colour. */
    public static int fade(int color, float factor) {
        int a = (color >>> 24) & 0xFF;
        return (Math.round(a * Math.max(0, Math.min(1, factor))) << 24) | (color & 0x00FFFFFF);
    }

    /**
     * Shortens a text with a real ellipsis ("…") so it is at most maxW wide, measured by {@code width}. The text is
     * returned unchanged when it fits; nothing at all when not even one letter fits.
     */
    public static String ellipsize(String text, int maxW, java.util.function.ToIntFunction<String> width) {
        if (text == null || text.isEmpty() || width.applyAsInt(text) <= maxW) return text;
        String cut = text;
        while (!cut.isEmpty() && width.applyAsInt(cut + "\u2026") > maxW) cut = cut.substring(0, cut.length() - 1);
        cut = cut.stripTrailing();
        return cut.isEmpty() ? "" : cut + "\u2026";
    }

    /** Blends two ARGB colours; t = 0 gives a, t = 1 gives b. */
    public static int mix(int a, int b, float t) {
        float k = Math.max(0, Math.min(1, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * k) << 24) | (Math.round(ar + (br - ar) * k) << 16)
            | (Math.round(ag + (bg - ag) * k) << 8) | Math.round(ab + (bb - ab) * k);
    }

    /**
     * Free (un-rounded) scale for the menu and the Quick Panel: GUI scale 2 at 100%, and every percent step really
     * changes the size. Text inside stays sharp through {@link CrispText}.
     */
    public static float layoutScale(int guiScale, double sizePercent) {
        int gs = Math.max(1, guiScale);
        double realPixels = 2.0 * Math.max(10, Math.min(300, sizePercent)) / 100.0;
        return (float) (realPixels / gs);
    }

    /** Scale that makes our UI look like GUI scale 2 at 100%, whatever the game's GUI scale is. */
    public static float uiScale(int guiScale, double sizePercent) {
        int gs = Math.max(1, guiScale);
        double realPixels = 2.0 * Math.max(10, Math.min(300, sizePercent)) / 100.0;
        ClickGUI gui = gui();
        // Crisp Text: whole screen pixels per menu pixel, so letters are never blurry or uneven
        if (gui == null || gui.crispText()) realPixels = Math.max(1, Math.round(realPixels));
        return (float) (realPixels / gs);
    }
}
