package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.gui.HudCard;
import com.relaxedqol.client.gui.HudWindow;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Move and resize the vanilla scoreboard (sidebar) like the other HUD cards: open your inventory (or the menu),
 * drag it anywhere, and pull its bottom-right corner to make it bigger or smaller. It is vanilla's own sidebar, drawn
 * through a moved / scaled view, so it always looks exactly like the server made it. Its natural size is measured
 * every frame (ScoreboardBounds), so the frame always fits however many lines the server sends.
 */
public class Scoreboard extends Module implements HudCard {
    private final Setting<Integer> winX = addSetting(HudWindow.xSetting());
    private final Setting<Integer> winY = addSetting(HudWindow.ySetting());
    private final Setting<Integer> winScale = addSetting(new Setting<>("Size %", 85, 50, 300, 5),
        "How big the scoreboard is (you can also drag its bottom-right corner while your inventory is open)");
    private final Setting<Boolean> hide = addSetting(new Setting<>("Hide", false), "No scoreboard at all");
    /** Stays on the right edge of the screen (also while you resize it) until you drag it somewhere else. */
    private final Setting<Boolean> rightEdge = addSetting(new Setting<>("Right Edge", true).hide());
    /** 85% became the default size - set once for configs saved with the old 100%. */
    private final Setting<Boolean> size85 = addSetting(new Setting<>("Size 85 Set", false).hide());
    private final HudWindow window = new HudWindow(winX, winY, winScale).pinRight(rightEdge);

    /** The sidebar's natural box {x, y, w, h} in GUI pixels, measured last frame (null until it was drawn once). */
    private int[] natural;

    public Scoreboard() {
        super("Scoreboard", "Move and resize the scoreboard (drag it / its corner while your inventory is open)", ModuleCategory.RENDER);
        addSetting(new com.relaxedqol.client.setting.ActionSetting("Reset Position", this::resetPosition),
            "Back to the right edge of the screen at the default size (85%)");
        setEnabled(true);
    }

    @Override
    public HudWindow getWindow() {
        return window;
    }

    /** Drawn by the vanilla sidebar layer (HudMovers), not by the card renderer. */
    @Override
    public void render(GuiGraphicsExtractor g, Font font, int vw, int vh) {}

    @Override
    public boolean showsCard() {
        return false;
    }

    /** Size % as a factor (0.85 = 85%). */
    public float sizeScale() {
        return winScale.getValue() / 100f;
    }

    public boolean hidden() {
        return hide.getValue();
    }

    public int[] natural() {
        if (!size85.getValue()) {
            size85.setValue(true);
            if (winScale.getValue() == 100) winScale.setValue(85);
        }
        return natural;
    }

    /** Null when nothing was drawn (no sidebar right now), so no empty frame stays behind to drag. */
    public void setNatural(int[] box) {
        natural = box;
    }

    /** Puts it back where vanilla draws it, at normal size. */
    public void resetPosition() {
        winX.setValue(-1);
        winY.setValue(-1);
        winScale.setValue(85);
        rightEdge.setValue(true);
    }

    @Override
    public String getInfo() {
        return hidden() ? "Hidden" : winScale.getValue() + "%";
    }
}
