package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.command.CommandManager;
import com.relaxedqol.client.util.ChatUtil;

public class HelpCommand extends Command {
    public HelpCommand() {
        super("help", "Shows a list of commands", "h");
    }

    @Override
    public void execute(String[] args) {
        ChatUtil.send("§6Commands:");
        for (Command command : RelaxedQolClient.commandManager.getCommands()) {
            if (!command.available()) continue;
            ChatUtil.send("§e" + CommandManager.PREFIX + command.getUsage() + " §7- §f" + command.getDescription());
        }
        ChatUtil.send("§7Press Right Shift to open the menu.");
    }

    @Override
    public java.util.List<String> suggest(String[] args, int index) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (index == 0) for (Command c : com.relaxedqol.client.RelaxedQolClient.commandManager.getCommands()) if (c.available()) names.add(c.getName());
        return names;
    }

    @Override
    public String getUsage() {
        return "help";
    }
}
