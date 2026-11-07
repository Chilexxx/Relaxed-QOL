package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.module.modules.misc.PlayerAlert;
import com.relaxedqol.client.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;

/** .alert add|remove NAME, .alert list, .alert clear - PlayerAlert's watch list (replaces .seen). */
public class AlertCommand extends Command {
    public AlertCommand() {
        super("alert", "PlayerAlert watch list: .alert add NAME / remove NAME / list / clear", "playeralert", "pa");
    }

    @Override
    public void execute(String[] args) {
        PlayerAlert alert = RelaxedQolClient.moduleManager.getModule(PlayerAlert.class);
        if (alert == null) {
            ChatUtil.send("§cPlayerAlert module not found");
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            alert.printList();
            return;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "add" -> {
                if (args.length < 2) {
                    ChatUtil.send("§7Usage: §f.alert add NAME §7(several: .alert add a b c)");
                    return;
                }
                List<String> added = new ArrayList<>();
                for (int i = 1; i < args.length; i++) {
                    for (String n : args[i].split(",")) if (!n.isBlank() && alert.add(n.trim())) added.add(n.trim());
                }
                ChatUtil.send(added.isEmpty() ? "§7Nothing added (already on the list, or not a valid name)"
                    : "§aPlayerAlert: watching §f" + String.join("§7, §f", added));
                if (!alert.isEnabled()) ChatUtil.send("§7(PlayerAlert is off - turn it on to get alerts)");
            }
            case "remove", "del", "delete" -> {
                if (args.length < 2) {
                    ChatUtil.send("§7Usage: §f.alert remove NAME");
                    return;
                }
                for (int i = 1; i < args.length; i++) {
                    ChatUtil.send(alert.remove(args[i]) ? "§7Removed §f" + args[i] : "§c" + args[i] + " is not on the list");
                }
            }
            case "clear" -> {
                alert.clearList();
                ChatUtil.send("§7PlayerAlert watch list cleared");
            }
            default -> ChatUtil.send("§7Usage: §f" + getUsage());
        }
    }

    @Override
    public List<String> suggest(String[] args, int index) {
        if (index == 0) return List.of("add", "remove", "list", "clear");
        if (index >= 1 && args.length > 0 && args[0].equalsIgnoreCase("remove")) {
            PlayerAlert alert = RelaxedQolClient.moduleManager.getModule(PlayerAlert.class);
            return alert != null ? new ArrayList<>(alert.watched()) : List.of();
        }
        if (index >= 1 && args.length > 0 && args[0].equalsIgnoreCase("add")) return playerNames();
        return List.of();
    }

    @Override
    public String getUsage() {
        return "alert add NAME | remove NAME | list | clear";
    }
}
