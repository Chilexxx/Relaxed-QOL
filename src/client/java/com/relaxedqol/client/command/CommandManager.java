package com.relaxedqol.client.command;

import com.relaxedqol.RelaxedQolMod;
import com.relaxedqol.client.command.commands.BindCommand;
import com.relaxedqol.client.command.commands.ConfigCommand;
import com.relaxedqol.client.command.commands.WaypointCommand;
import com.relaxedqol.client.command.commands.FriendCommand;
import com.relaxedqol.client.command.commands.HelpCommand;
import com.relaxedqol.client.command.commands.ScaleCommand;
import com.relaxedqol.client.command.commands.SetCommand;
import com.relaxedqol.client.command.commands.ToggleCommand;
import com.relaxedqol.client.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CommandManager {
    public static final String PREFIX = ".";

    private final List<Command> commands = new ArrayList<>();

    public CommandManager() {
        commands.add(new HelpCommand());
        commands.add(new com.relaxedqol.client.command.commands.MenuCommand());
        commands.add(new ToggleCommand());
        commands.add(new BindCommand());
        commands.add(new SetCommand());
        commands.add(new ConfigCommand());
        commands.add(new com.relaxedqol.client.command.commands.ProfileCommand());
        commands.add(new com.relaxedqol.client.command.commands.WhatsNewCommand());
        commands.add(new com.relaxedqol.client.command.commands.AlertCommand());
        commands.add(new ScaleCommand());
        commands.add(new com.relaxedqol.client.command.commands.NotesCommand());
        commands.add(new com.relaxedqol.client.command.commands.NotepadCommand());
        commands.add(new WaypointCommand());
        commands.add(new com.relaxedqol.client.command.commands.CoordsCommand());
        commands.add(new com.relaxedqol.client.command.commands.PerfCommand());
        commands.add(new com.relaxedqol.client.command.commands.ClaimOwnerCommand());
        commands.add(new com.relaxedqol.client.command.commands.AccountCommand());
    }

    /**
     * Runs a chat message as a client command if it starts with the prefix.
     * Returns true when the message was a command and must not be sent to the server.
     */
    public boolean handle(String message) {
        if (!message.startsWith(PREFIX) || message.length() == PREFIX.length()) return false;

        String[] parts = message.substring(PREFIX.length()).trim().split("\\s+");
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        Command command = getCommand(parts[0]);
        if (command == null || !command.available()) {
            ChatUtil.send("§cUnknown command. Use " + PREFIX + "help for a list of commands.");
            return true;
        }
        try {
            command.execute(args);
        } catch (Exception e) {
            RelaxedQolMod.LOGGER.error("Error running command {}", message, e);
            ChatUtil.send("§cError executing command: " + e.getMessage());
        }
        return true;
    }

    /** Result of tab completion: where the replaced word starts, and the candidates for it. */
    public record Completion(int start, List<String> candidates) {}

    /** Tab completion for a partly typed "." command (text up to the cursor). */
    public Completion complete(String partial) {
        if (!partial.startsWith(PREFIX)) return new Completion(0, List.of());
        String body = partial.substring(PREFIX.length());
        int lastSpace = body.lastIndexOf(' ');
        int start = PREFIX.length() + lastSpace + 1;
        String current = body.substring(lastSpace + 1).toLowerCase(java.util.Locale.ROOT);
        List<String> pool = new ArrayList<>();
        if (lastSpace < 0) {
            for (Command command : commands) if (command.available()) pool.add(command.getName());
        } else {
            String[] words = body.split(" ", -1);
            Command command = getCommand(words[0]);
            if (command != null && command.available()) {
                String[] args = Arrays.copyOfRange(words, 1, words.length);
                try {
                    pool.addAll(command.suggest(args, args.length - 1));
                } catch (RuntimeException e) {
                    RelaxedQolMod.LOGGER.debug("Completion failed", e);
                }
            }
        }
        List<String> result = new ArrayList<>();
        for (String candidate : pool) {
            if (candidate == null || candidate.isEmpty() || result.contains(candidate)) continue;
            if (candidate.toLowerCase(java.util.Locale.ROOT).startsWith(current)) result.add(candidate);
        }
        // Also match anywhere in the word ("dar" finds Radar) after the prefix matches
        for (String candidate : pool) {
            if (candidate == null || result.contains(candidate)) continue;
            if (!current.isEmpty() && candidate.toLowerCase(java.util.Locale.ROOT).contains(current)) result.add(candidate);
        }
        return new Completion(start, result);
    }

    public Command getCommand(String name) {
        for (Command command : commands) {
            if (command.getName().equalsIgnoreCase(name)) return command;
            for (String alias : command.getAliases()) {
                if (alias.equalsIgnoreCase(name)) return command;
            }
        }
        return null;
    }

    public List<Command> getCommands() {
        return Collections.unmodifiableList(commands);
    }
}
