package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.Colors;

/**
 * (Lunar-style custom sky): paint the sky your own colour per dimension (or a slow rainbow), or use the
 * starry End sky everywhere. Client side only - the world, light and time are untouched. Through
 * SkyRendererMixin (optional: if it cannot hook in on this game version the sky simply stays vanilla).
 */
public class CustomSky extends Module {
    private final Setting<String> style = addSetting(Setting.mode("Style", "Colour", "Colour", "End Sky", "Colour + End Sky"),
        "Colour: a coloured sky - pick it under Look (Look = My Colours to choose them yourself). End Sky: the starry"
            + " End sky everywhere. Or both");
    /** Ready-made looks - pick one and you are done (Custom = pick the three colours yourself). */
    private final Setting<String> theme = addSetting(Setting.mode("Look", "Twilight", "My Colours", "Twilight", "Ocean",
        "Sunset", "Midnight", "Candy", "Forest", "Blood Moon").showIf(this::colour), "Click to pick a ready-made sky, or"
        + " My Colours to choose the Overworld / Nether / End colours yourself (the swatches appear right below)");
    private final Setting<String> overworld = addSetting(Colors.setting("Overworld Colour", "#5B7CFF")
        .showIf(() -> this.colour() && this.mine()), "Click the swatch to pick the Overworld sky colour");
    private final Setting<String> nether = addSetting(Colors.setting("Nether Colour", "#5A0F1E")
        .showIf(() -> this.colour() && this.mine()), "Click the swatch to pick the Nether sky colour");
    private final Setting<String> end = addSetting(Colors.setting("End Colour", "#3C1E5A")
        .showIf(() -> this.colour() && this.mine()), "Click the swatch to pick the End sky colour");
    private final Setting<Integer> brightness = addSetting(new Setting<>("Brightness %", 100, 10, 150, 10),
        "Darker or brighter version of the colour");
    private final Setting<Boolean> followDay = addSetting(new Setting<>("Follow Day / Night", true),
        "The colour dims at night like the real sky");

    /** Look -> {overworld, nether, end} colours. */
    private static int[] themeColors(String theme) {
        return switch (theme) {
            case "Ocean" -> new int[]{0x2FA8E0, 0x0E4D64, 0x1B3B6F};
            case "Sunset" -> new int[]{0xFF8A5B, 0x8C1C13, 0x5E2750};
            case "Midnight" -> new int[]{0x1B2A6B, 0x2B0A14, 0x0D0A24};
            case "Candy" -> new int[]{0xF7A1D8, 0xC0567B, 0xA77BF3};
            case "Forest" -> new int[]{0x6FBF73, 0x3A2E12, 0x1F3D2B};
            case "Blood Moon" -> new int[]{0xB3122E, 0x5A0710, 0x3A0A1A};
            default -> new int[]{0x5B7CFF, 0x5A0F1E, 0x3C1E5A}; // Twilight
        };
    }

    public CustomSky() {
        super("CustomSky", "Your own sky colour, or the End sky everywhere", ModuleCategory.RENDER);
    }

    /** "My Colours" (was "Custom" - older configs still count). */
    private boolean mine() {
        String t = theme.getValue();
        return t.equals("My Colours") || t.equals("Custom");
    }

    public boolean colour() {
        return !style.getValue().equals("End Sky");
    }

    public boolean endSky() {
        return style.getValue().contains("End Sky");
    }

    /**
     * The sky colour as 0xRRGGBB for the current dimension. vanillaBrightness = the vanilla colour's brightness
     * (0..1) so night can dim it.
     */
    public int skyRgb(float vanillaBrightness) {
        int c;
        boolean custom = mine();
        int[] look = themeColors(theme.getValue());
        if (mc.level != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER) {
            c = custom ? Colors.of(nether.getValue()) : look[1];
        } else if (mc.level != null && mc.level.dimension() == net.minecraft.world.level.Level.END) {
            c = custom ? Colors.of(end.getValue()) : look[2];
        } else {
            c = custom ? Colors.of(overworld.getValue()) : look[0];
        }
        float f = brightness.getValue() / 100f;
        if (followDay.getValue() && vanillaBrightness >= 0) f *= Math.max(0.12f, Math.min(1f, vanillaBrightness * 1.4f));
        int r = Math.min(255, Math.round(((c >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((c >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((c & 0xFF) * f));
        return (r << 16) | (g << 8) | b;
    }

    @Override
    public String getInfo() {
        return style.getValue().equals("End Sky") ? "End Sky" : mine() ? "My Colours" : theme.getValue();
    }
}
