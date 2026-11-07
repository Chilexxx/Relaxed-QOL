package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.module.modules.misc.Notes;
import com.relaxedqol.client.util.ChatUtil;
import com.relaxedqol.client.util.NotesStore;

/**
 * .notes - opens the notes window. .notes add <text> - adds a note straight from chat. .notes list - shows
 * them in chat.
 */
public class NotesCommand extends Command {
    public NotesCommand() {
        super("notes", "Your notes: .notes (open the window), .notes add <text>, .notes list");
    }

    @Override
    public void execute(String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("add")) {
            String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
            NotesStore.add(text);
            ChatUtil.send("§aNote added §7(" + NotesStore.all().size() + " notes)");
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            if (NotesStore.all().isEmpty()) {
                ChatUtil.send("§7No notes yet - §f.notes add <text>");
                return;
            }
            int i = 1;
            for (NotesStore.Note n : NotesStore.all()) ChatUtil.send("§7" + (i++) + ". " + (n.pinned ? "§b" : "§f") + n.text);
            return;
        }
        Notes.openSoon();
    }

    @Override
    public String getUsage() {
        return "notes [add <text>|list]";
    }

    @Override
    public java.util.List<String> suggest(String[] args, int index) {
        return index == 0 ? java.util.List.of("add", "list") : java.util.List.of();
    }
}
