package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.module.modules.misc.Notepad;

/** .notepad - opens the Notepad (a big text window for long notes). */
public class NotepadCommand extends Command {
    public NotepadCommand() {
        super("notepad", "Opens the Notepad - a big window to write a lot in");
    }

    @Override
    public void execute(String[] args) {
        Notepad.openSoon();
    }

    @Override
    public String getUsage() {
        return "notepad";
    }
}
