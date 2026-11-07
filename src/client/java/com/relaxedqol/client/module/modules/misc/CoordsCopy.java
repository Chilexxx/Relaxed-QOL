package com.relaxedqol.client.module.modules.misc;

import com.relaxedqol.client.module.ActionModule;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.ChatUtil;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * Coords Copy (custom QOL): one click (or its keybind) copies where you are to the clipboard, ready to paste in
 * Discord, a waypoint or a command - e.g. "120 64 -340".
 */
public class CoordsCopy extends ActionModule {
    private final Setting<String> format = addSetting(Setting.mode("Format", "X Y Z", "X Y Z", "X, Y, Z", "X: Y: Z:"),
        "\"X Y Z\" = 120 64 -340, \"X, Y, Z\" = 120, 64, -340, \"X: Y: Z:\" = X: 120 Y: 64 Z: -340");
    private final Setting<Boolean> dimension = addSetting(new Setting<>("Add Dimension", false),
        "Add the dimension after the coordinates, e.g. 120 64 -340 (Nether)");
    private final Setting<Boolean> netherToo = addSetting(new Setting<>("Add Other Side", false),
        "Also add the matching Nether / Overworld coordinates (x/8 or x*8)");

    public CoordsCopy() {
        super("CoordsCopy", "Copies your coordinates to the clipboard (bind a key for one-press copying)", ModuleCategory.MISC);
    }

    private String format(int x, int y, int z) {
        return switch (format.getValue()) {
            case "X, Y, Z" -> x + ", " + y + ", " + z;
            case "X: Y: Z:" -> "X: " + x + " Y: " + y + " Z: " + z;
            default -> x + " " + y + " " + z;
        };
    }

    @Override
    public void run() {
        if (mc.player == null || mc.level == null) return;
        int x = mc.player.getBlockX();
        int y = mc.player.getBlockY();
        int z = mc.player.getBlockZ();
        boolean nether = mc.level.dimension() == Level.NETHER;
        boolean overworld = mc.level.dimension() == Level.OVERWORLD;
        StringBuilder text = new StringBuilder(format(x, y, z));
        if (dimension.getValue()) {
            String dim = nether ? "Nether" : overworld ? "Overworld" : mc.level.dimension().identifier().getPath();
            text.append(" (").append(dim.substring(0, 1).toUpperCase(Locale.ROOT)).append(dim.substring(1)).append(")");
        }
        if (netherToo.getValue() && (nether || overworld)) {
            int ox = nether ? x * 8 : Math.floorDiv(x, 8);
            int oz = nether ? z * 8 : Math.floorDiv(z, 8);
            text.append(" | ").append(nether ? "Overworld " : "Nether ").append(format(ox, y, oz));
        }
        mc.keyboardHandler.setClipboard(text.toString());
        ChatUtil.send("§aCopied §f" + text);
    }
}
