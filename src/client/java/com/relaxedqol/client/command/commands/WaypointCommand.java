package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.util.ChatUtil;
import com.relaxedqol.client.waypoint.WaypointManager;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** .waypoint add <name> [x y z] | remove <name> | list | clear deaths */
public class WaypointCommand extends Command {
    public WaypointCommand() {
        super("waypoint", "Waypoints: add <name> [x y z], remove <name>, list, cleardeaths", "wp", "waypoints");
    }

    @Override
    public void execute(String[] args) {
        Minecraft mc = Minecraft.getInstance();
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            List<WaypointManager.Waypoint> all = WaypointManager.forServer();
            if (all.isEmpty()) {
                ChatUtil.send("§7No waypoints on this server. .waypoint add <name>");
                return;
            }
            ChatUtil.send("§6Waypoints:");
            for (WaypointManager.Waypoint w : all) {
                String dim = w.dimension().substring(w.dimension().indexOf(':') + 1);
                String dist = mc.player != null && w.dimension().equals(WaypointManager.currentDimension())
                    ? " §8" + Math.round(Math.sqrt(mc.player.distanceToSqr(w.x(), w.y(), w.z()))) + "m" : "";
                ChatUtil.send((w.death() ? "§c" : "§f") + w.name() + " §7" + w.x() + " " + w.y() + " " + w.z() + " §8(" + dim + ")" + dist);
            }
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add", "set" -> {
                if (args.length < 2 || mc.player == null) {
                    ChatUtil.send("§cUsage: .waypoint add <name> [x y z]");
                    return;
                }
                int x = mc.player.getBlockX();
                int y = mc.player.getBlockY();
                int z = mc.player.getBlockZ();
                String name = args[1];
                if (args.length >= 5) {
                    try {
                        x = Integer.parseInt(args[2]);
                        y = Integer.parseInt(args[3]);
                        z = Integer.parseInt(args[4]);
                    } catch (NumberFormatException e) {
                        ChatUtil.send("§cCoordinates must be numbers");
                        return;
                    }
                }
                WaypointManager.add(name, x, y, z, false);
                ChatUtil.send("§aWaypoint §f" + name + " §aat §f" + x + " " + y + " " + z + " §7(turn on Waypoints to see it)");
            }
            case "remove", "delete", "del" -> {
                // Join the rest, so multi-word names like "Point 1" or "Death 14:30" work
                String target = args.length >= 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "";
                if (target.equalsIgnoreCase("nearest") || target.isEmpty()) {
                    String removed = WaypointManager.removeNearest();
                    if (removed != null) ChatUtil.send("§cRemoved nearest waypoint §f" + removed);
                    else ChatUtil.send("§cNo waypoints on this server");
                } else if (WaypointManager.remove(target)) {
                    ChatUtil.send("§cRemoved waypoint §f" + target);
                } else {
                    ChatUtil.send("§cNo waypoint called \"" + target + "\". Use .waypoint list, or .waypoint remove nearest");
                }
            }
            case "cleardeaths" -> ChatUtil.send("§7Removed §f" + WaypointManager.clearDeaths() + " §7death points");
            default -> ChatUtil.send("§cUsage: " + getUsage());
        }
    }

    @Override
    public List<String> suggest(String[] args, int index) {
        if (index == 0) return List.of("add", "remove", "list", "cleardeaths");
        if (index == 1 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("del"))) {
            List<String> names = new ArrayList<>();
            names.add("nearest");
            for (WaypointManager.Waypoint w : WaypointManager.forServer()) names.add(w.name());
            return names;
        }
        Minecraft mc = Minecraft.getInstance();
        if (args[0].equalsIgnoreCase("add") && index >= 2 && index <= 4 && mc.player != null) {
            int v = index == 2 ? mc.player.getBlockX() : index == 3 ? mc.player.getBlockY() : mc.player.getBlockZ();
            return List.of(String.valueOf(v));
        }
        if (args[0].equalsIgnoreCase("add") && index == 1) return List.of("home", "base", "stash", "portal");
        return List.of();
    }

    @Override
    public String getUsage() {
        return "waypoint <add <name> [x y z]|remove <name>|list|cleardeaths>";
    }
}
