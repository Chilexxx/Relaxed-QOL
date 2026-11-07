package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.module.modules.misc.CoordsCopy;
import com.relaxedqol.client.util.ChatUtil;

/**
 * .coords (or .cords / .xyz) - copies where you are to the clipboard, in the CoordsCopy module's format
 * (X Y Z, with the dimension / the other side when those are switched on there).
 */
public class CoordsCommand extends Command {
    public CoordsCommand() {
        super("coords", "Copies your coordinates to the clipboard", "cords", "coord", "xyz");
    }

    @Override
    public void execute(String[] args) {
        CoordsCopy copy = RelaxedQolClient.moduleManager.getModule(CoordsCopy.class);
        if (copy == null) {
            ChatUtil.send("§cCoordsCopy is not available");
            return;
        }
        copy.run();
    }

    @Override
    public String getUsage() {
        return "coords";
    }
}
