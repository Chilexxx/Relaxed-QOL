package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.FriendManager;
import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.util.ChatUtil;

import java.util.List;

public class FriendCommand extends Command {
    public FriendCommand() {
        super("friend", "Manages your friends list", "f");
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 1) {
            ChatUtil.send("§cUsage: " + getUsage());
            return;
        }
        switch (args[0].toLowerCase()) {
            case "add" -> {
                if (args.length != 2) {
                    ChatUtil.send("§cUsage: " + getUsage());
                } else if (FriendManager.add(args[1])) {
                    ChatUtil.send("§aAdded §f" + args[1] + " §ato friends");
                } else {
                    ChatUtil.send("§c" + args[1] + " is already your friend");
                }
            }
            case "remove", "del" -> {
                if (args.length != 2) {
                    ChatUtil.send("§cUsage: " + getUsage());
                } else if (FriendManager.remove(args[1])) {
                    ChatUtil.send("§aRemoved §f" + args[1] + " §afrom friends");
                } else {
                    ChatUtil.send("§c" + args[1] + " is not your friend");
                }
            }
            case "list" -> {
                List<String> friends = FriendManager.getFriends();
                ChatUtil.send(friends.isEmpty() ? "§7You have no friends added" : "§6Friends: §f" + String.join(", ", friends));
            }
            case "clear" -> {
                FriendManager.clear();
                ChatUtil.send("§aCleared friends list");
            }
            default -> ChatUtil.send("§cUnknown action: " + args[0] + " §7(" + getUsage() + ")");
        }
    }

    @Override
    public java.util.List<String> suggest(String[] args, int index) {
        if (index == 0) return java.util.List.of("add", "remove", "list", "clear");
        if (index == 1 && args[0].equalsIgnoreCase("add")) return playerNames();
        if (index == 1) return com.relaxedqol.client.FriendManager.getFriends();
        return java.util.List.of();
    }

    @Override
    public String getUsage() {
        return "friend <add|remove|list|clear> [name]";
    }
}
