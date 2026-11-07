package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.Colors;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * "Crosshair" (Lunar-style): your own crosshair - shape, size, gap, thickness, colour and a dark outline so it
 * reads on any background, optionally red while you aim at a player. Replaces the game's crosshair (Fabric HUD
 * element) only while it is on; first person only, like the real one.
 */
public class Crosshair extends Module {
    private final Setting<String> shape = addSetting(Setting.mode("Shape", "Cross", "Cross", "Cross + Dot", "Dot", "Circle", "Circle + Dot", "T"),
        "Click to pick the crosshair's shape");
    private final Setting<Integer> size = addSetting(new Setting<>("Size", 4, 1, 10, 1),
        "Length of the lines (or the circle's radius). 4 = short and neat; 1 = tiny, 10 = long");
    /** Size became a 1..10 scale (4 = the old Size 1) - an old saved size is switched over once. */
    private final Setting<Boolean> newSizeScale = addSetting(new Setting<>("Size Scale v2", false).hide());
    private final Setting<Integer> gap = addSetting(new Setting<>("Gap", 2, 0, 8, 1), "Empty space in the middle");
    private final Setting<Integer> thickness = addSetting(new Setting<>("Thickness", 1, 1, 4, 1));
    private final Setting<String> colour = addSetting(Colors.setting("Colour", "White"), "Click the swatch to pick the colour");
    private final Setting<Integer> opacity = addSetting(new Setting<>("Opacity %", 100, 20, 100, 5));
    private final Setting<Boolean> outline = addSetting(new Setting<>("Outline", true), "A thin dark edge so it stays visible on bright blocks");
    private final Setting<Boolean> playerColour = addSetting(new Setting<>("Red On Players", true),
        "Turns red while you aim at a player");

    private static Crosshair instance;

    public Crosshair() {
        super("Crosshair", "Your own crosshair: shape, size, gap, colour and outline", ModuleCategory.RENDER);
        instance = this;
    }

    /** From the HUD: draws ours and returns true, or false to let the game draw its own. */
    public static boolean draw(GuiGraphicsExtractor g) {
        Crosshair c = instance;
        if (c == null || !c.isEnabled() || com.relaxedqol.client.util.Privacy.hidden()) return false;
        if (!c.newSizeScale.getValue()) {
            c.newSizeScale.setValue(true);
            c.size.setValue(4);
        }
        if (c.mc.options == null || !c.mc.options.getCameraType().isFirstPerson()) return true; // like vanilla: none in third person
        c.render(g);
        return true;
    }

    /**
     * Drawn in real screen pixels around one centre square, so every thickness (also even ones like 2) is
     * exactly as wide on every side - the arms used to be off by half a pixel against each other.
     */
    private void render(GuiGraphicsExtractor g) {
        int gs = Math.max(1, mc.getWindow().getGuiScale());
        int col = Colors.of(colour.getValue());
        if (playerColour.getValue() && mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit
            && hit.getEntity() instanceof net.minecraft.world.entity.player.Player) {
            col = 0xFFFF4D4D;
        }
        int a = Math.round(opacity.getValue() * 2.55f);
        col = (a << 24) | (col & 0xFFFFFF);
        int edge = (Math.round(a * 0.7f) << 24);
        // Everything in screen pixels: Size 4 = one GUI pixel long (the old Size 1), 1..10
        int s = Math.max(1, Math.round(size.getValue() * gs / 4f));
        int gp = gap.getValue() * gs;
        int t = thickness.getValue() * gs;
        int o = outline.getValue() ? Math.max(1, gs / 2) : 0;
        int cxs = g.guiWidth() * gs / 2;
        int cys = g.guiHeight() * gs / 2;
        // The centre square every arm lines up with: [c0, c0 + t) on both axes
        int x0 = cxs - t / 2;
        int y0 = cys - t / 2;
        String sh = shape.getValue();
        boolean dot = sh.contains("Dot");

        g.pose().pushMatrix();
        g.pose().scale(1f / gs, 1f / gs);
        if (sh.startsWith("Cross") || sh.equals("T")) {
            int[][] arms = {
                {x0 - gp - s, y0, x0 - gp, y0 + t},             // left
                {x0 + t + gp, y0, x0 + t + gp + s, y0 + t},     // right
                {x0, y0 - gp - s, x0 + t, y0 - gp},             // top
                {x0, y0 + t + gp, x0 + t, y0 + t + gp + s}};    // bottom
            if (o > 0) {
                for (int i = 0; i < arms.length; i++) {
                    if (sh.equals("T") && i == 2) continue;
                    int[] r = arms[i];
                    g.fill(r[0] - o, r[1] - o, r[2] + o, r[3] + o, edge);
                }
            }
            for (int i = 0; i < arms.length; i++) {
                if (sh.equals("T") && i == 2) continue;
                int[] r = arms[i];
                g.fill(r[0], r[1], r[2], r[3], col);
            }
        }
        if (sh.startsWith("Circle")) {
            int r = Math.max(2 * gs, s + gp + t);
            int points = Math.max(24, (int) (2 * Math.PI * r * 1.2));
            if (o > 0) ring(g, x0 + t / 2f, y0 + t / 2f, r, points, t + 2 * o, edge);
            ring(g, x0 + t / 2f, y0 + t / 2f, r, points, t, col);
        }
        if (dot || sh.equals("Dot")) {
            if (o > 0) g.fill(x0 - o, y0 - o, x0 + t + o, y0 + t + o, edge);
            g.fill(x0, y0, x0 + t, y0 + t, col);
        }
        g.pose().popMatrix();
    }

    private static void ring(GuiGraphicsExtractor g, float cx, float cy, int r, int points, int t, int color) {
        for (int i = 0; i < points; i++) {
            double ang = 2 * Math.PI * i / points;
            int px = (int) Math.round(cx + Math.cos(ang) * r - t / 2f);
            int py = (int) Math.round(cy + Math.sin(ang) * r - t / 2f);
            g.fill(px, py, px + t, py + t, color);
        }
    }

    @Override
    public String getInfo() {
        return shape.getValue();
    }
}
