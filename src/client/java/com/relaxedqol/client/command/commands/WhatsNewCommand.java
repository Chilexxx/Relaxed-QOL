package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.command.Command;

/** .whatsnew - opens the What's New window (the changelog). */
public class WhatsNewCommand extends Command {
    public WhatsNewCommand() {
        super("whatsnew", "Shows what changed in the latest updates", "changelog");
    }

    @Override
    public void execute(String[] args) {
        com.relaxedqol.client.util.Later.run(() -> com.relaxedqol.client.gui.ChangelogScreen.open(null));
    }

    @Override
    public String getUsage() {
        return "whatsnew";
    }
}
