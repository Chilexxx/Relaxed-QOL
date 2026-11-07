package com.relaxedqol.client.module.modules.lovercraft;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * "LoverFella Mode" (LoverCraft): the whole client goes LoverCraft - navy panels, heart-red / mustache-cyan
 * accents that slowly shimmer between the two, the heart + mustache logo in the HUD, the LC logo and floating hearts
 * behind the menu, and bobble heads of LoverFella and the team (their real skins) wobbling in the corner of the menu.
 * Everything is drawn by {@link com.relaxedqol.client.gui.LoverFx}; this module only holds the switches.
 */
public class LoverFellaMode extends Module {
    private final Setting<Boolean> bobbleHeads = addSetting(new Setting<>("Bobble Heads", true),
        "Bobble heads of the players below in the bottom-left corner of the menu (point at them to make them wobble)");
    private final Setting<String> heads = addSetting(new Setting<>("Heads", "LoverFella, Ayihana, Isaacbc76, RainingSoap").prompt(),
        "The players shown as bobble heads, separated with commas (their real skins are used) - up to 10, the row"
            + " shrinks to fit");
    /** Little full-body figures sitting on the menu windows, legs dangling over the edge. */
    private final Setting<Boolean> sitters = addSetting(new Setting<>("Window Sitters", true),
        "Small LoverFella-style figures sitting on top of the menu windows, legs dangling (they wave when you point at them)");
    private final Setting<String> sitterNames = addSetting(new Setting<>("Sitters", "LoverFella, Isaacbc76, RainingSoap").prompt(),
        "Who sits on the windows, separated with commas (their real skins are used), up to 6 - spread out in the order you type"
            + " them. LoverFella and his brothers Isaac and Ben by default");
    private final Setting<Boolean> hearts = addSetting(new Setting<>("Floating Hearts", true),
        "Red and cyan hearts drifting up behind the menu");
    private final Setting<Boolean> logo = addSetting(new Setting<>("Logos", true),
        "The LC logo in the menu and the heart + mustache logo in the HUD watermark");
    private final Setting<Boolean> chatTag = addSetting(new Setting<>("Chat Tag", true),
        "Client messages in chat start with a red / cyan LC tag");
    /**
     * LoverFella and his brothers in the letters of the scoreboard's title (no longer on its corner, where
     * he covered the server's name): one sitting on the foot of the "L", one peeking out of the "C".
     */
    private final Setting<Boolean> scoreboardSitter = addSetting(new Setting<>("Scoreboard Sitters", true),
        "Little figures in the letters of the scoreboard's title: one sits on the foot of the L, one peeks out of the C");
    private final Setting<String> onL = addSetting(Setting.mode("On The L", "LoverFella", "LoverFella", "Isaac", "Ben", "Nobody")
        .showIf(() -> this.scoreboardSitter.getValue()), "Who sits on the foot of the L");
    private final Setting<String> inC = addSetting(Setting.mode("In The C", "Isaac", "LoverFella", "Isaac", "Ben", "Nobody")
        .showIf(() -> this.scoreboardSitter.getValue()), "Who peeks out of the C");
    private final Setting<String> atEnd = addSetting(Setting.mode("At The End", "Ben", "LoverFella", "Isaac", "Ben", "Nobody")
        .showIf(() -> this.scoreboardSitter.getValue()), "Who sits on the last letter of the title");
    /** Was a typed name - now picked above (kept hidden so old configs load). */
    private final Setting<String> scoreboardName = addSetting(new Setting<>("Scoreboard Who", "LoverFella").hide());
    /** PlayerAlert's HUD list in LoverCraft style. */
    private final Setting<Boolean> alertStyle = addSetting(new Setting<>("LoverCraft Player Alert", true),
        "PlayerAlert's HUD list in LoverCraft style: navy card, a heart that beats while someone is online, red / cyan status");
    private final Setting<Boolean> alertFaces = addSetting(new Setting<>("Alert Faces", true)
        .showIf(() -> this.alertStyle.getValue()), "Each player's face in PlayerAlert's list");
    /** The HUD info panel (XYZ, speed, FPS...) in LoverCraft style. */
    private final Setting<Boolean> infoPanel = addSetting(new Setting<>("LoverCraft Info Panel", true),
        "The HUD info panel (coordinates, speed, FPS...) in LoverCraft style: navy card, red / cyan hearts, LoverFella peeking over its edge");

    public LoverFellaMode() {
        super("LoverFellaMode", "The whole client in LoverFella / LoverCraft style - colours, logos, hearts and bobble heads",
            ModuleCategory.LOVERCRAFT);
    }

    public boolean bobbleHeads() {
        return bobbleHeads.getValue();
    }

    public boolean sitters() {
        return sitters.getValue();
    }

    /** The sitting figures' names (at most 6; fewer are drawn when the windows are narrow). */
    public List<String> sitterNames() {
        return parse(sitterNames.getValue(), MAX_SITTERS);
    }

    public static final int MAX_SITTERS = 6;
    /** Up to 10 bobble heads; the row shrinks to fit the screen. */
    public static final int MAX_HEADS = 10;

    /** "LoverFella, ayihana  Isaacbc76;;" -> names, no doubles, at most max. */
    private static List<String> parse(String value, int max) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String part : value.split("[,;\\s]+")) {
            String n = part.trim();
            if (out.size() >= max) break;
            if (n.matches("[A-Za-z0-9_]{2,16}") && seen.add(n.toLowerCase(java.util.Locale.ROOT))) out.add(n);
        }
        return out;
    }

    public boolean hearts() {
        return hearts.getValue();
    }

    public boolean logos() {
        return logo.getValue();
    }

    public boolean chatTag() {
        return chatTag.getValue();
    }

    public boolean scoreboardSitter() {
        return scoreboardSitter.getValue();
    }

    /** LoverFella / Isaac / Ben -> their Minecraft names; "Nobody" -> null. */
    public static String account(String who) {
        return switch (who) {
            case "Isaac" -> "Isaacbc76";
            case "Ben" -> "RainingSoap";
            case "LoverFella" -> "LoverFella";
            default -> null;
        };
    }

    /** Who peeks over the info panel: the one on the L (LoverFella when nobody is picked). */
    public String scoreboardName() {
        String n = account(onL.getValue());
        return n != null ? n : "LoverFella";
    }

    /** The three title spots: {L, C, end} account names (null = nobody). */
    public String[] titleSitters() {
        return new String[]{account(onL.getValue()), account(inC.getValue()), account(atEnd.getValue())};
    }

    public boolean alertStyle() {
        return alertStyle.getValue();
    }

    public boolean alertFaces() {
        return alertFaces.getValue();
    }

    public boolean infoPanel() {
        return infoPanel.getValue();
    }

    /** The bobble-head names (at most 10; the row is made smaller to fit). */
    public List<String> headNames() {
        return parse(heads.getValue(), MAX_HEADS);
    }

    @Override
    public String displayName() {
        return "LoverFella Mode";
    }
}
