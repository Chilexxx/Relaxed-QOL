package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.util.ChatUtil;

public class ToggleCommand extends Command {
    public ToggleCommand() {
        super("toggle", "Toggles a module", "t");
    }

    @Override
    public void execute(String[] args) {
        if (args.length != 1) {
            ChatUtil.send("§cUsage: " + getUsage());
            return;
        }
        Module module = RelaxedQolClient.moduleManager.getModule(args[0]);
        if (module == null) {
            ChatUtil.send("§cModule not found: " + args[0]);
            return;
        }
        module.toggle();
        ChatUtil.send("§6" + module.getName() + " " + (module.isEnabled() ? "§aenabled" : "§cdisabled"));
    }

    @Override
    public java.util.List<String> suggest(String[] args, int index) {
        return index == 0 ? moduleNames() : java.util.List.of();
    }

    @Override
    public String getUsage() {
        return "toggle <module>";
    }
}
