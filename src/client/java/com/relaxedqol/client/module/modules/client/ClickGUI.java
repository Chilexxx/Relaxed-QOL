package com.relaxedqol.client.module.modules.client;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.KeySetting;
import com.relaxedqol.client.setting.Setting;

/** Holds the menu look-and-feel settings. The menu opens with the Menu Key (Right Shift by default). */
public class ClickGUI extends Module {
    /** Picked from the colour palette (click the swatch) instead of cycling through names. */
    private final com.relaxedqol.client.setting.ColorSetting accent = addSetting(
        new com.relaxedqol.client.setting.ColorSetting("Accent", "Violet", false, true), "Colour of highlights, switches and outlines");
    private final Setting<Integer> size = addSetting(new Setting<>("Menu Scale %", 100, 50, 150, 5),
        "Size of the menu and the Quick Panel");
    private final Setting<Boolean> crisp = addSetting(new Setting<>("Crisp Text", true),
        "Draws text at whole pixels so letters stay sharp at any menu size");
    private final Setting<Integer> opacity = addSetting(new Setting<>("Background %", 45, 0, 100, 5),
        "How solid the panels are (0 = see-through)");
    private final Setting<Boolean> blur = addSetting(new Setting<>("Blur Behind", false),
        "Blurs the game behind the menu (needs Menu Background Blur in the video settings)");
    /** "." opens the chat with "." typed, like "/" does for server commands. */
    private final Setting<Boolean> dotOpensChat = addSetting(new Setting<>("Dot Opens Chat", true),
        "Pressing . opens the chat with . already typed, ready for a client command (like / does for server commands)");
    private final Setting<Boolean> descriptions = addSetting(new Setting<>("Tooltips", true),
        "Shows these little explanation boxes when you hover a module or a setting in the menu");
    /** Opens this menu (Right Shift by default). */
    private final KeySetting menuKey = addSetting(new KeySetting("Menu Key", com.mojang.blaze3d.platform.InputConstants.KEY_RSHIFT, false),
        "Opens and closes this menu");
    /** Menu layout: the classic floating panels, or one app-style window with a sidebar. */
    private final Setting<String> layout = addSetting(Setting.mode("Menu Layout", "Window", "Window", "Panels"),
        "Window (default): one app-style window - categories on the left, modules in the middle, the picked module's"
            + " settings on the right. Panels: the classic floating category panels you can drag around");
    /** The game says "vanilla" everywhere (no "Modded" on the title screen, no * in the window title, F3, server brand). */
    private final Setting<Boolean> hideModded = addSetting(new Setting<>("Hide Modded", true).hide(),
        "Makes the game claim it is plain vanilla: the brand sent to servers says \"vanilla\" instead of \"fabric\","
            + " no \"Modded\" on the title screen, no * in the window title, vanilla in F3. Servers that block"
            + " modded clients by brand then let you in. It does NOT hide what the client does in game");
    /** Other Relaxed users see an "Relaxed" title above you. Off = nobody can tell you use the client. */
    private final Setting<Boolean> showClientTag = addSetting(new Setting<>("Show Client Tag", true)
        .onChange(com.relaxedqol.client.ClientTag::rebroadcast),
        "Other Relaxed QOL users see an \"Relaxed\" title above you. Off = nobody can tell you use it");
    /** Draw the "Relaxed" title above other players who use the client. */
    private final Setting<Boolean> seeClientTags = addSetting(new Setting<>("See Client Tags", true),
        "Shows the \"Relaxed\" title above other players who use the client");


    /** Lunar-style smart culling (see util/Culling) - not a module, just on. */
    private final Setting<Boolean> fpsBoost = addSetting(new Setting<>("FPS Boost", true),
        "More FPS without changing how the game looks: mobs, chests, signs, heads, item frames... that are completely"
            + " hidden behind solid walls are skipped instead of drawn (big boost at spawns and bases)");
    /** For slower computers - the client does less work per frame. */
    private final Setting<Boolean> lowEnd = addSetting(new Setting<>("Low-End PC", false),
        "For slower computers: the HUD, cards and LocatorBar are worked out 30 times a second instead of 60, the minimap"
            + " builds terrain in smaller slices, and soft glows are left out. Looks the same, costs less FPS");
    /** Apple-style smooth text (Inter) in the client's windows and cards. */
    private final Setting<Boolean> smoothFont = addSetting(new Setting<>("Smooth Font", false),
        "Smooth, Apple-style text (no pixel font) in the client's windows and the Notes card");
    /** The Screenshots module became this switch. */
    private final Setting<Boolean> screenshotTools = addSetting(new Setting<>("Screenshot Tools", true),
        "After F2: the screenshot is copied to your clipboard and one chat line shows Copy / Open / Folder / Delete"
            + " (instead of the game's own \"Saved screenshot as\" line)");
    private static ClickGUI self;

    // ------------------------------------------------------------------ the theme (set in the HUD editor's Theme tab)
    private final com.relaxedqol.client.setting.ColorSetting themeSurface = themeColor("Theme Surface", "#18191C");
    private final com.relaxedqol.client.setting.ColorSetting themeText = themeColor("Theme Text", "#ECEDEF");
    private final Setting<Integer> themeBorder = addSetting(new Setting<>("Theme Border %", 17, 0, 60, 1).hide());
    private final Setting<Integer> themeRound = addSetting(new Setting<>("Theme Roundness %", 100, 0, 200, 10).hide());
    private final Setting<Integer> themeShadow = addSetting(new Setting<>("Theme Shadow %", 100, 0, 200, 10).hide());
    private final Setting<Integer> themeAnim = addSetting(new Setting<>("Theme Animation %", 100, 0, 300, 10).hide());
    private final com.relaxedqol.client.setting.ActionSetting editHud = addSetting(
        new com.relaxedqol.client.setting.ActionSetting("Edit HUD & Theme", com.relaxedqol.client.gui.HudEditorScreen::open),
        "Move every HUD element, style each one (colours, background, border, shadow...) and change the client's theme,"
            + " with a live preview");
    private final com.relaxedqol.client.setting.ActionSetting accounts = addSetting(
        new com.relaxedqol.client.setting.ActionSetting("Accounts", () -> com.relaxedqol.client.gui.AccountsScreen.open(mc.gui.screen())),
        "Switch Minecraft accounts without restarting, and keep the session renewed so you never get \"Invalid session\"");

    private com.relaxedqol.client.setting.ColorSetting themeColor(String name, String def) {
        com.relaxedqol.client.setting.ColorSetting c = new com.relaxedqol.client.setting.ColorSetting(name, def, false, false);
        c.hide();
        return addSetting(c);
    }

    /** The accent colour setting (for the HUD editor's Theme tab). */
    public com.relaxedqol.client.setting.ColorSetting accentSetting() {
        return accent;
    }

    public com.relaxedqol.client.setting.ColorSetting themeSurface() {
        return themeSurface;
    }

    public com.relaxedqol.client.setting.ColorSetting themeText() {
        return themeText;
    }

    public Setting<Integer> themeBorder() {
        return themeBorder;
    }

    public Setting<Integer> themeRound() {
        return themeRound;
    }

    public Setting<Integer> themeShadow() {
        return themeShadow;
    }

    public Setting<Integer> themeAnim() {
        return themeAnim;
    }

    public Setting<Integer> opacitySetting() {
        return opacity;
    }

    /** Puts every theme setting back to its default. */
    public void resetTheme() {
        themeSurface.reset();
        themeText.reset();
        themeBorder.reset();
        themeRound.reset();
        themeShadow.reset();
        themeAnim.reset();
        opacity.reset();
        accent.reset();
    }

    public ClickGUI() {
        super("ClickGUI", "How the menu looks: accent colour, scale, see-through background", ModuleCategory.CLIENT);
        self = this;
    }

    /** Only holds settings; the menu itself opens with Right Shift (or the Quick Panel). */
    @Override
    public boolean isSettingsOnly() {
        return true;
    }

    /** Safe to call very early (mixins at startup, before modules exist): defaults to on. */
    public static boolean hideModded() {
        // No longer a setting - the game always looks vanilla
        return true;
    }

    /** Safe at any time (on until the settings exist). */
    public static boolean smoothFont() {
        ClickGUI g = self;
        return g == null || g.smoothFont.getValue();
    }

    /** Safe at any time (on until the settings exist). */
    public static boolean screenshotTools() {
        ClickGUI g = self;
        return g == null || g.screenshotTools.getValue();
    }

    /** Safe at any time (on until the settings exist). */
    public static boolean lowEnd() {
        ClickGUI g = self;
        return g != null && g.lowEnd.getValue();
    }

    public static boolean fpsBoost() {
        ClickGUI g = self;
        return g == null || g.fpsBoost.getValue();
    }

    public boolean dotOpensChat() {
        return dotOpensChat.getValue();
    }

    public boolean showClientTag() {
        return showClientTag.getValue();
    }

    public boolean seeClientTags() {
        return seeClientTags.getValue();
    }

    public int menuKey() {
        return menuKey.getKey() >= 0 ? menuKey.getKey() : com.mojang.blaze3d.platform.InputConstants.KEY_RSHIFT;
    }

    /** True for the single-window (app style) menu layout. */
    public boolean windowLayout() {
        return layout.getValue().equals("Window");
    }

    public boolean crispText() {
        return crisp.getValue();
    }

    /** .scale reset: back to the defaults. */
    public void resetLook() {
        size.reset();
        crisp.reset();
    }

    public void setScalePercent(int percent) {
        size.setValue(percent);
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    public String getAccentMode() {
        return accent.getValue();
    }

    public int getSizePercent() {
        return size.getValue();
    }

    public int getOpacityPercent() {
        return opacity.getValue();
    }

    public boolean useBlur() {
        return blur.getValue();
    }

    public boolean showTooltips() {
        return descriptions.getValue();
    }
}
