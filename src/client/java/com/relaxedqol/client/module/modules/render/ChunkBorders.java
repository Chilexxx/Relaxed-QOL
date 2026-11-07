package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/**
 * "ChunkBorders": the chunk borders of F3 + G, always on while the module is on (no key combination). The
 * chunk you stand in gets yellow walls with a line every 2 blocks, blue lines where a 16-block section starts, and the
 * corners of the chunks around it red - like the game's own view. Only around your height, so it stays light.
 */
public class ChunkBorders extends Module {
    private final Setting<Integer> height = addSetting(new Setting<>("Height", 32, 8, 128, 8),
        "How far above and below you the lines go (blocks)");
    private final Setting<Boolean> grid = addSetting(new Setting<>("Grid Lines", true),
        "The yellow line every 2 blocks on the walls of your chunk (off = only its corners and section lines)");
    private final Setting<Boolean> neighbours = addSetting(new Setting<>("Nearby Chunks", true),
        "Red corner lines of the chunks around yours");

    private static final int YELLOW = 0xFFFFE14D;
    private static final int YELLOW_FAINT = 0x99FFE14D;
    private static final int BLUE = 0xFF4DA6FF;
    private static final int RED = 0xFFFF4D4D;

    public ChunkBorders() {
        super("ChunkBorders", "Shows chunk borders (like F3 + G) while it is on", ModuleCategory.RENDER);
    }

    @Override
    public void onRender3D(float partialTicks) {
        if (mc.player == null || mc.level == null) return;
        Vec3 p = mc.player.getPosition(partialTicks);
        int x0 = Math.floorDiv((int) Math.floor(p.x), 16) * 16;
        int z0 = Math.floorDiv((int) Math.floor(p.z), 16) * 16;
        int x1 = x0 + 16;
        int z1 = z0 + 16;
        int min = mc.level.getMinY();
        int max = min + mc.level.getHeight();
        int py = (int) Math.floor(p.y);
        int y0 = Math.max(min, py - height.getValue());
        int y1 = Math.min(max, py + height.getValue());
        if (y1 <= y0) return;

        // The chunks around: red corner lines
        if (neighbours.getValue()) {
            for (int cx = x0 - 16; cx <= x1 + 16; cx += 16) {
                for (int cz = z0 - 16; cz <= z1 + 16; cz += 16) {
                    boolean own = (cx == x0 || cx == x1) && (cz == z0 || cz == z1);
                    if (own) continue;
                    Gizmos.line(new Vec3(cx, y0, cz), new Vec3(cx, y1, cz), RED, 1.5f);
                }
            }
        }
        // Your chunk: the four corners
        int[][] corners = {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}};
        for (int[] c : corners) Gizmos.line(new Vec3(c[0], y0, c[1]), new Vec3(c[0], y1, c[1]), YELLOW, 2.5f);
        // Its walls: upright lines every 2 blocks
        if (grid.getValue()) {
            for (int i = 2; i < 16; i += 2) {
                Gizmos.line(new Vec3(x0 + i, y0, z0), new Vec3(x0 + i, y1, z0), YELLOW_FAINT, 1f);
                Gizmos.line(new Vec3(x0 + i, y0, z1), new Vec3(x0 + i, y1, z1), YELLOW_FAINT, 1f);
                Gizmos.line(new Vec3(x0, y0, z0 + i), new Vec3(x0, y1, z0 + i), YELLOW_FAINT, 1f);
                Gizmos.line(new Vec3(x1, y0, z0 + i), new Vec3(x1, y1, z0 + i), YELLOW_FAINT, 1f);
            }
        }
        // Rings around the walls: every 2 blocks (yellow), every 16 = a section start (blue)
        for (int y = (int) Math.ceil(y0 / 2.0) * 2; y <= y1; y += 2) {
            boolean section = Math.floorMod(y, 16) == 0;
            if (!section && !grid.getValue()) continue;
            int color = section ? BLUE : YELLOW_FAINT;
            float width = section ? 2f : 1f;
            Gizmos.line(new Vec3(x0, y, z0), new Vec3(x1, y, z0), color, width);
            Gizmos.line(new Vec3(x1, y, z0), new Vec3(x1, y, z1), color, width);
            Gizmos.line(new Vec3(x1, y, z1), new Vec3(x0, y, z1), color, width);
            Gizmos.line(new Vec3(x0, y, z1), new Vec3(x0, y, z0), color, width);
        }
    }
}
