package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.schematic.PlaceMode;
import com.relaxedqol.client.schematic.Schematic;
import com.relaxedqol.client.schematic.SchematicLibrary;
import com.relaxedqol.client.util.ChatUtil;
import net.minecraft.core.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * .schem - the Schematics Library from chat: copy an area or whole chunks out of the world into a schematic file
 * (on a server too: what your game has loaded), and paste a file into a single-player world.
 */
public class SchemCommand extends Command {
    public SchemCommand() {
        super("schem", "Schematics Library: copy an area or chunks into a file, paste files in single player", "schematic", "schematics");
    }

    /** The schematic file with this name (with or without its ending), else the first that starts with it. */
    private static Path find(String want) {
        Path found = null;
        for (Path p : Schematic.listFiles()) {
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            String base = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
            if (n.equals(want) || base.equals(want)) return p;
            if (found == null && n.startsWith(want)) found = p;
        }
        return found;
    }

    private static String rest(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            com.relaxedqol.client.module.modules.misc.SchematicsLibrary.openSoon();
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                List<Path> files = Schematic.listFiles();
                if (files.isEmpty()) ChatUtil.send("§7No schematics yet. Copy an area (§f.schem select§7) or put files in §f.minecraft/schematics§7.");
                for (Path p : files) ChatUtil.send("§7- §f" + p.getFileName());
            }
            case "select", "sel" -> {
                // pick the two corners by clicking (left = A, right = B, also in mid-air), Enter saves the area
                String no = PlaceMode.select();
                if (no != null) ChatUtil.send(no);
            }
            case "pos1", "corner1" -> ChatUtil.send(SchematicLibrary.setCorner(1));
            case "pos2", "corner2" -> ChatUtil.send(SchematicLibrary.setCorner(2));
            case "save", "copy" -> {
                if (args.length < 2) ChatUtil.send("§7Usage: §f.schem save <name>§7 - copies the area between the two corners into a schematic");
                else SchematicLibrary.saveArea(rest(args, 1), ChatUtil::send);
            }
            case "chunks", "chunk" -> {
                // .schem chunks [radius] <name>
                int radius = 0;
                int from = 1;
                if (args.length >= 3 && args[1].matches("[0-9]+")) {
                    radius = Integer.parseInt(args[1]);
                    from = 2;
                }
                if (args.length <= from) {
                    ChatUtil.send("§7Usage: §f.schem chunks [radius] <name>§7 - copies the chunks around you, bedrock to sky (radius 0 = the one you stand in, 1 = 3 x 3, 6 = 13 x 13... up to "
                        + SchematicLibrary.MAX_RADIUS + ")");
                } else {
                    SchematicLibrary.saveChunks(radius, rest(args, from), ChatUtil::send);
                }
            }
            case "paste" -> paste(args);
            case "cancel", "abort" -> {
                // ends a copy or a paste that is under way
                String paste = SchematicLibrary.pasteState() != null ? SchematicLibrary.stopPaste() : null;
                String copy = SchematicLibrary.copyState() != null ? SchematicLibrary.stopCopy() : null;
                ChatUtil.send(paste != null ? paste : copy != null ? copy : "§7No copy or paste is under way.");
            }
            case "preview" -> {
                // single player: the box a file will fill follows your aim, E pastes it there
                if (args.length < 2) {
                    ChatUtil.send("§7Usage: §f.schem preview <name>§7 - shows where the file would go; E pastes it there (single player)");
                    return;
                }
                Path file = find(rest(args, 1).toLowerCase(Locale.ROOT));
                String no = file == null ? "§cNo schematic with that name (.schem list)" : PlaceMode.pasteFile(file);
                if (no != null) ChatUtil.send(no);
            }
            default -> ChatUtil.send("§7Usage: §f." + getUsage());
        }
    }

    /** .schem paste <name> [air] [at <x> <y> <z>] | stop - single player: the file straight into the world. */
    private static void paste(String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("stop")) {
            ChatUtil.send(SchematicLibrary.stopPaste());
            return;
        }
        boolean air = false;
        BlockPos at = null;
        List<String> name = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("air")) {
                air = true;
            } else if (args[i].equalsIgnoreCase("at") && i + 3 < args.length) {
                try {
                    at = new BlockPos(Integer.parseInt(args[i + 1]), Integer.parseInt(args[i + 2]), Integer.parseInt(args[i + 3]));
                } catch (NumberFormatException e) {
                    ChatUtil.send("§cNot a number. §7Usage: §f.schem paste <name> [air] [at <x> <y> <z>]");
                    return;
                }
                i += 3;
            } else {
                name.add(args[i]);
            }
        }
        if (name.isEmpty()) {
            ChatUtil.send("§7Usage: §f.schem paste <name> [air] [at <x> <y> <z>]§7 - or §f.schem preview <name>§7 to place it by eye");
            return;
        }
        Path file = find(String.join(" ", name).toLowerCase(Locale.ROOT));
        if (file == null) ChatUtil.send("§cNo schematic called §f" + String.join(" ", name) + "§c (.schem list)");
        else SchematicLibrary.paste(file, at, 0, air, ChatUtil::send);
    }

    @Override
    public List<String> suggest(String[] args, int index) {
        if (index == 0) return List.of("select", "pos1", "pos2", "save", "chunks", "paste", "preview", "cancel", "list");
        if (index == 1 && args.length > 0 && (args[0].equalsIgnoreCase("paste") || args[0].equalsIgnoreCase("preview"))) {
            List<String> names = new ArrayList<>();
            for (Path p : Schematic.listFiles()) {
                String n = p.getFileName().toString();
                names.add(n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n);
            }
            return names;
        }
        return List.of();
    }

    @Override
    public String getUsage() {
        return "schem [select | pos1 | pos2 | save <name> | chunks [radius] <name> | paste <name> [air] [at <x y z>] | paste stop | cancel | preview <name> | list]";
    }
}
