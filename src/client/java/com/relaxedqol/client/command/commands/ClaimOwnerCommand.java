package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.gui.Toasts;
import com.relaxedqol.client.util.ChatUtil;
import com.relaxedqol.client.util.TabInfo;
import net.minecraft.client.Minecraft;

/**
 * .claimowner (or .owner / .claim) - copies the name of the owner of the claim you stand in, read from the
 * scoreboard's "NAME's Claim" line.
 */
public class ClaimOwnerCommand extends Command {
    public ClaimOwnerCommand() {
        super("claimowner", "Copies the name of the owner of the claim you are standing in", "owner", "claim", "copyowner");
    }

    @Override
    public void execute(String[] args) {
        String owner = TabInfo.claimOwner();
        if (owner == null) {
            ChatUtil.send("§7No claim owner on the scoreboard here §8(stand in someone's claim)");
            return;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(owner);
        ChatUtil.send("§aCopied claim owner §f" + owner);
        Toasts.show("Copied", owner, Theme.accent(), 2500);
    }

    @Override
    public String getUsage() {
        return "claimowner";
    }
}
