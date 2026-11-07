package com.relaxedqol.client.module;

public enum ModuleCategory {
    RENDER("Render", "◉"),
    PLAYER("Player", "☺"),
    MISC("Misc", "✦"),
    /** The LoverCraft server's own modules (Action Bar, Radar, Trade Shot, Player Alert, LoverFella Mode). */
    LOVERCRAFT("LoverCraft", "♥"),
    /** Menu look settings: not a panel, opened with the settings (gear) button. */
    CLIENT("Settings", "⚙");

    private final String name;
    private final String icon;

    ModuleCategory(String name, String icon) {
        this.name = name;
        this.icon = icon;
    }

    public String getName() {
        return name;
    }

    /** Single symbol shown in the menu panel header. */
    public String getIcon() {
        return icon;
    }

    /**
     * Smooth icon sprite (assets/relaxed-qol/textures/gui/sprites/ui/NAME.png) drawn instead of the font symbol,
     * which looked blurry and blocky.
     */
    public String iconSprite() {
        return switch (this) {
            case PLAYER -> "cat_player";
            case MISC -> "cat_misc";
            case RENDER -> "cat_render";
            case LOVERCRAFT -> "cat_lovercraft";
            case CLIENT -> "gear";
        };
    }

    /** Whether this category is in use. */
    public boolean active() {
        return true;
    }

    @Override
    public String toString() {
        return name;
    }
}
