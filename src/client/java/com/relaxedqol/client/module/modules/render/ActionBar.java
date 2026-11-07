package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;

/**
 * (Lunar-style "Action Bar"): move and scale the action bar text (the line above the hotbar that servers use
 * for balances, cooldowns, "now playing"...), or hide it.
 */
public class ActionBar extends Module {
    private final Setting<Integer> scale = addSetting(new Setting<>("Scale %", 100, 40, 250, 5));
    private final Setting<Integer> offsetX = addSetting(new Setting<>("X Offset", 0, -600, 600, 1),
        "Sideways from the middle (negative = left)");
    private final Setting<Integer> offsetY = addSetting(new Setting<>("Y Offset", 0, -500, 60, 1),
        "Up (negative) or down from where vanilla puts it");
    private final Setting<Boolean> hide = addSetting(new Setting<>("Hide", false));

    /** While you change a setting (and for a moment after), an outline shows where and how big it will be. */
    private long previewUntil;

    {
        for (com.relaxedqol.client.setting.Setting<?> setting : java.util.List.of(scale, offsetX, offsetY, hide)) {
            setting.onChange(() -> previewUntil = System.currentTimeMillis() + 2500);
        }
    }

    /** Show the outline: a setting just changed, or its settings are open in the menu. */
    public boolean previewing() {
        return System.currentTimeMillis() < previewUntil
            || com.relaxedqol.client.gui.ClickGuiScreen.isShowingSettingsOf(getName());
    }

    public ActionBar() {
        super("ActionBar", "Move, scale or hide the action bar text", ModuleCategory.LOVERCRAFT);
    }

    public float scale() {
        return scale.getValue() / 100f;
    }

    public int offsetX() {
        return offsetX.getValue();
    }

    public int offsetY() {
        return offsetY.getValue();
    }

    public boolean hidden() {
        return hide.getValue();
    }

    @Override
    public String getInfo() {
        return hidden() ? "Hidden" : scale.getValue() + "%";
    }
}
