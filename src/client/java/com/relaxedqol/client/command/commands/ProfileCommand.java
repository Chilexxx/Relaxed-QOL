package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.util.ChatUtil;
import com.relaxedqol.client.util.Profiles;

/** .profile - opens Profiles & Sharing; .profile <name> switches to that profile. */
public class ProfileCommand extends Command {
    public ProfileCommand() {
        super("profile", "Switches setting profiles (.profile PvP), or opens Profiles & Sharing", "profiles");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            com.relaxedqol.client.util.Later.run(() -> com.relaxedqol.client.gui.ProfilesScreen.open(null));
            return;
        }
        String name = Profiles.clean(String.join(" ", args));
        for (String p : Profiles.list()) {
            if (!p.equalsIgnoreCase(name)) continue;
            if (p.equals(Profiles.active())) ChatUtil.send("§7Already using §f" + p);
            else if (!Profiles.switchTo(p)) ChatUtil.send("§cCould not load " + p);
            return;
        }
        ChatUtil.send("§cNo profile called " + name + " §7(" + String.join(", ", Profiles.list()) + ")");
    }

    @Override
    public java.util.List<String> suggest(String[] args, int index) {
        return index == 0 ? Profiles.list() : java.util.List.of();
    }

    @Override
    public String getUsage() {
        return "profile [name]";
    }
}
