package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.ConfigManager;
import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.module.modules.client.ClickGUI;
import com.relaxedqol.client.module.modules.misc.QuickPanel;
import com.relaxedqol.client.module.modules.render.HUD;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.ChatUtil;

import java.util.List;

/**
 * .scale reset            menu, Quick Panel and HUD back to their default size
 * .scale <percent>        menu size (50-150)
 * .scale hud <percent|reset>, .scale panel <percent|reset>
 */
public class ScaleCommand extends Command {
    public ScaleCommand() {
        super("scale", "Menu size: .scale reset (back to default), .scale 100, .scale hud 85, .scale panel 100");
    }

    @Override
    public void execute(String[] args) {
        ClickGUI gui = RelaxedQolClient.moduleManager.getModule(ClickGUI.class);
        if (args.length == 0) {
            ChatUtil.send("§7Menu scale: §f" + gui.getSizePercent() + "%§7. Use §f.scale reset§7 or §f.scale <50-150>");
            return;
        }
        String first = args[0].toLowerCase();
        if (first.equals("set") && args.length >= 2) {
            // ".scale set default" / ".scale set 100"
            execute(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (first.equals("reset") || first.equals("default")) {
            gui.resetLook();
            resetSetting(RelaxedQolClient.moduleManager.getModule(HUD.class).getSetting("Scale %"));
            RelaxedQolClient.moduleManager.getModule(QuickPanel.class).setScalePercent(100);
            ConfigManager.save(ConfigManager.DEFAULT);
            ChatUtil.send("§aMenu, Quick Panel and HUD are back to their default size.");
            return;
        }
        if (first.equals("hud") || first.equals("panel")) {
            if (args.length < 2) {
                ChatUtil.send("§cUsage: .scale " + first + " <percent|reset>");
                return;
            }
            boolean reset = args[1].equalsIgnoreCase("reset") || args[1].equalsIgnoreCase("default");
            if (first.equals("hud")) {
                Setting<?> setting = RelaxedQolClient.moduleManager.getModule(HUD.class).getSetting("Scale %");
                if (reset) resetSetting(setting);
                else if (!setting.parse(args[1])) ChatUtil.send("§cNot a number: " + args[1]);
            } else {
                QuickPanel panel = RelaxedQolClient.moduleManager.getModule(QuickPanel.class);
                if (reset) panel.setScalePercent(100);
                else panel.setScalePercent(parse(args[1], 100));
            }
            ConfigManager.save(ConfigManager.DEFAULT);
            ChatUtil.send("§a" + (first.equals("hud") ? "HUD" : "Quick Panel") + " size updated.");
            return;
        }
        int percent = parse(first, -1);
        if (percent < 0) {
            ChatUtil.send("§cUsage: " + getUsage());
            return;
        }
        gui.setScalePercent(percent);
        ConfigManager.save(ConfigManager.DEFAULT);
        ChatUtil.send("§aMenu scale set to §f" + gui.getSizePercent() + "%");
    }

    private static void resetSetting(Setting<?> setting) {
        if (setting != null) setting.reset();
    }

    private static int parse(String text, int fallback) {
        try {
            return Integer.parseInt(text.replace("%", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public List<String> suggest(String[] args, int index) {
        if (index == 0) return List.of("reset", "default", "set", "hud", "panel", "75", "100", "125");
        if (index == 1 && args[0].equalsIgnoreCase("set")) return List.of("default", "100");
        if (index == 1) return List.of("reset", "85", "100");
        return List.of();
    }

    @Override
    public String getUsage() {
        return "scale <reset|50-150|hud <n|reset>|panel <n|reset>>";
    }
}
