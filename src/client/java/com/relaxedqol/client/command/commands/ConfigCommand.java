package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.ConfigManager;
import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.util.ChatUtil;

import java.util.List;

public class ConfigCommand extends Command {
    public ConfigCommand() {
        super("config", "Saves or loads module configurations", "cfg");
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 1) {
            ChatUtil.send("§cUsage: " + getUsage());
            return;
        }
        String name = args.length >= 2 ? args[1] : ConfigManager.DEFAULT;
        switch (args[0].toLowerCase()) {
            case "save" -> {
                if (!ConfigManager.isValidName(name)) {
                    ChatUtil.send("§cConfig names may only use letters, numbers, - and _");
                } else if (ConfigManager.save(name)) {
                    ChatUtil.send("§aSaved config §f" + name);
                } else {
                    ChatUtil.send("§cCould not save config " + name + " (see log)");
                }
            }
            case "load" -> {
                if (!ConfigManager.isValidName(name)) {
                    ChatUtil.send("§cConfig names may only use letters, numbers, - and _");
                } else if (ConfigManager.load(name)) {
                    ChatUtil.send("§aLoaded config §f" + name);
                } else {
                    ChatUtil.send("§cConfig not found: " + name);
                }
            }
            case "list" -> {
                List<String> configs = ConfigManager.list();
                ChatUtil.send(configs.isEmpty() ? "§7No saved configs" : "§6Configs: §f" + String.join(", ", configs));
            }
            default -> ChatUtil.send("§cUnknown action: " + args[0] + " §7(" + getUsage() + ")");
        }
    }

    @Override
    public java.util.List<String> suggest(String[] args, int index) {
        if (index == 0) return java.util.List.of("save", "load", "list");
        if (index == 1) return com.relaxedqol.client.ConfigManager.list();
        return java.util.List.of();
    }

    @Override
    public String getUsage() {
        return "config <save|load|list> [name]";
    }
}
