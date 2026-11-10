package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.gui.ClickGuiScreen;
import com.relaxedqol.client.util.Later;
import net.minecraft.client.Minecraft;

/** .menu (or .gui / .clickgui) - opens the client menu, same as the menu key. */
public class MenuCommand extends Command {
    public MenuCommand() {
        super("menu", "Opens the client menu", "gui", "clickgui");
    }

    @Override
    public void execute(String[] args) {
        // Once the chat box has closed
        Later.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.gui.setScreen(ClickGuiScreen.create(null));
        });
    }

    @Override
    public String getUsage() {
        return "menu";
    }
}
