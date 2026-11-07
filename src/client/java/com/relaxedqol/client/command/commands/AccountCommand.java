package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.gui.AccountsScreen;
import com.relaxedqol.client.util.Accounts;
import com.relaxedqol.client.util.ChatUtil;
import com.relaxedqol.client.util.Later;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * .account - opens the Accounts window. .account refresh - a new session now. .account list - saved
 * accounts and how long the session has left. .account NAME - switch (outside a world; in chat on the server list
 * this is not reachable, so use the window there).
 */
public class AccountCommand extends Command {
    public AccountCommand() {
        super("account", "Switch Minecraft accounts / renew the session: .account, .account refresh, .account list",
            "accounts", "alt");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            Later.run(() -> AccountsScreen.open(Minecraft.getInstance().gui.screen()));
            return;
        }
        String sub = args[0];
        if (sub.equalsIgnoreCase("refresh")) {
            if (!Accounts.currentManaged()) {
                ChatUtil.send("§7Add §f" + Accounts.currentName() + " §7once in §f.account §7(+ Add account) - then its session renews by itself");
                return;
            }
            Accounts.refreshCurrent(true);
            ChatUtil.send("§7Renewing your session…");
            return;
        }
        if (sub.equalsIgnoreCase("list")) {
            ChatUtil.send("§7Playing as §f" + Accounts.currentName() + " §7- session: §f" + Accounts.timeLeft(Accounts.currentExpiry())
                + (Accounts.currentManaged() ? " §a(renews by itself)" : " §e(launcher session)"));
            List<Accounts.Account> all = Accounts.all();
            if (all.isEmpty()) ChatUtil.send("§7No saved accounts - §f.account §7to add one");
            for (Accounts.Account a : all) ChatUtil.send("§7- §f" + a.name + (a.needsLogin ? " §c(sign in again)" : ""));
            return;
        }
        Accounts.Account a = Accounts.findByName(sub);
        if (a == null) {
            ChatUtil.send("§cNo saved account called §f" + sub + "§c - §f.account list");
            return;
        }
        if (Accounts.inWorld()) {
            ChatUtil.send("§7Leave the world first - switching works on the title screen / server list (Account button)");
            return;
        }
        Accounts.switchTo(a);
    }

    @Override
    public String getUsage() {
        return "account [refresh|list|<name>]";
    }

    @Override
    public List<String> suggest(String[] args, int index) {
        if (index != 0) return List.of();
        List<String> out = new ArrayList<>(List.of("refresh", "list"));
        for (Accounts.Account a : Accounts.all()) out.add(a.name);
        return out;
    }
}
