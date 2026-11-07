package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;

/**
 * (Lunar-style "Boss Bar"): move and scale the vanilla boss bars, or hide them. The bars are vanilla's own,
 * drawn through a moved / scaled view, so they look exactly the same (colours, names, overlays).
 */
public class BossBar extends Module {
    private final Setting<Integer> scale = addSetting(new Setting<>("Scale %", 100, 40, 200, 5));
    private final Setting<Integer> offsetX = addSetting(new Setting<>("X Offset", 0, -600, 600, 1),
        "Sideways from the middle of the screen (negative = left)");
    private final Setting<Integer> offsetY = addSetting(new Setting<>("Y Offset", 0, -10, 500, 1),
        "Down from the top of the screen");
    private final Setting<Boolean> hide = addSetting(new Setting<>("Hide", false), "No boss bars at all");

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

    public BossBar() {
        super("BossBar", "Move, scale or hide the boss bars", ModuleCategory.RENDER);
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

    /** Where a vanilla y (GUI pixels from the top) ends up after moving / scaling. */
    public int transformedY(int y) {
        return Math.round(offsetY() + y * scale());
    }

    @Override
    public String getInfo() {
        return hidden() ? "Hidden" : scale.getValue() + "%";
    }
}
