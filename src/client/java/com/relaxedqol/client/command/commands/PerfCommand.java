package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.command.Command;

/** .perf - shows / hides how much time the client's own parts take. */
public class PerfCommand extends Command {
    public PerfCommand() {
        super("perf", "Shows how much time the client's own parts take (HUD, cards, ESP, culling)", "fps");
    }

    @Override
    public void execute(String[] args) {
        com.relaxedqol.client.util.Perf.toggle();
    }

    @Override
    public String getUsage() {
        return "perf";
    }
}
