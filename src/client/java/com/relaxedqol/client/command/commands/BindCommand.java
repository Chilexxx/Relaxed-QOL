package com.relaxedqol.client.command.commands;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.command.Command;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.util.ChatUtil;
import com.relaxedqol.client.util.KeyUtil;

public class BindCommand extends Command {
    public BindCommand() {
        super("bind", "Binds a key to a module (use 'none' to unbind)", "b");
    }

    @Override
    public void execute(String[] args) {
        if (args.length != 2) {
            ChatUtil.send("§cUsage: " + getUsage());
            return;
        }
        Module module = RelaxedQolClient.moduleManager.getModule(args[0]);
        if (module == null) {
            ChatUtil.send("§cModule not found: " + args[0]);
            return;
        }
        if (args[1].equalsIgnoreCase("none")) {
            module.setKey(-1);
            ChatUtil.send("§6" + module.getName() + " §7unbound");
            return;
        }
        int key = KeyUtil.parse(args[1]);
        if (key == -1) {
            ChatUtil.send("§cUnknown key: " + args[1] + " §7(examples: r, f6, rshift, keypad.1)");
            return;
        }
        module.setKey(key);
        ChatUtil.send("§6" + module.getName() + " §7bound to §e" + KeyUtil.name(key));
        com.relaxedqol.client.util.KeyConflicts.check(module, null, key);
    }

    @Override
    public java.util.List<String> suggest(String[] args, int index) {
        if (index == 0) return moduleNames();
        if (index == 1) return java.util.List.of("none", "r", "g", "h", "j", "k", "v", "x", "z", "c", "b", "n", "m", "f6", "f7", "f8", "f9", "rshift", "rcontrol", "ralt", "keypad.0", "keypad.1", "mouse.4", "mouse.5");
        return java.util.List.of();
    }

    @Override
    public String getUsage() {
        return "bind <module> <key|none>";
    }
}
