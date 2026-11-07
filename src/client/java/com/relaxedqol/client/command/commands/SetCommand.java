package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.ChatUtil;

import java.util.Arrays;

public class SetCommand extends Command {
    public SetCommand() {
        super("set", "Lists or changes module settings, e.g. .set Radar Size 120", "s");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 1) {
            Module module = RelaxedQolClient.moduleManager.getModule(args[0]);
            if (module == null) {
                ChatUtil.send("§cModule not found: " + args[0]);
                return;
            }
            ChatUtil.send("§6" + module.getName() + " settings:");
            for (Setting<?> setting : module.getSettings()) {
                String options = setting.isMode() ? " §8" + setting.getModes() : "";
                ChatUtil.send("§e" + setting.getName().replace(" ", "") + " §7= §f" + setting.display() + options);
            }
            return;
        }
        if (args.length < 3) {
            ChatUtil.send("§cUsage: " + getUsage());
            return;
        }
        Module module = RelaxedQolClient.moduleManager.getModule(args[0]);
        if (module == null) {
            ChatUtil.send("§cModule not found: " + args[0]);
            return;
        }
        Setting<?> setting = module.getSetting(args[1]);
        if (setting == null) {
            ChatUtil.send("§cSetting not found: " + args[1] + " §7(.set " + module.getName() + " lists them)");
            return;
        }
        String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (setting.parse(value)) {
            ChatUtil.send("§6" + module.getName() + " §7" + setting.getName() + " = §f" + setting.display());
        } else {
            ChatUtil.send("§cInvalid value for " + setting.getName() + ": " + value);
        }
    }

    @Override
    public java.util.List<String> suggest(String[] args, int index) {
        if (index == 0) return moduleNames();
        com.relaxedqol.client.module.Module module = com.relaxedqol.client.RelaxedQolClient.moduleManager.getModule(args[0]);
        if (module == null) return java.util.List.of();
        if (index == 1) {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (Setting<?> s : module.getSettings()) if (!s.isHidden() && !s.isAction()) names.add(s.getName().replace(" ", ""));
            return names;
        }
        if (index == 2) {
            Setting<?> setting = module.getSetting(args[1]);
            if (setting == null) return java.util.List.of();
            if (setting.isBoolean()) return java.util.List.of("true", "false");
            if (setting.isMode()) return setting.getModes();
            if (setting.isList()) return java.util.List.of("add", "remove", "clear");
            if (setting.isNumber()) return java.util.List.of(String.valueOf(setting.getDefaultValue()), String.valueOf(setting.getMin()), String.valueOf(setting.getMax()));
        }
        return java.util.List.of();
    }

    @Override
    public String getUsage() {
        return "set <module> [setting] [value]";
    }
}
