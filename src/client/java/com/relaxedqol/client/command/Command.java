package com.relaxedqol.client.command;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;

public abstract class Command {
    private final String name;
    private final String description;
    private final String[] aliases;

    protected Command(String name, String description, String... aliases) {
        this.name = name;
        this.description = description;
        this.aliases = aliases;
    }

    public abstract void execute(String[] args);

    public abstract String getUsage();

    /**
     * Tab completion: candidates for the argument at {@code index} (0 = first argument after the command name).
     * {@code args} holds the arguments typed so far (the last one may be partly typed). Filtering is done by the caller.
     */
    public List<String> suggest(String[] args, int index) {
        return List.of();
    }

    /** The module category this command belongs to - while it is locked the command does not exist. */
    public com.relaxedqol.client.module.ModuleCategory requires() {
        return null;
    }

    /** Whether the command can be used / is shown right now. */
    public boolean available() {
        return requires() == null || requires().active();
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String[] getAliases() { return aliases; }

    // ---------------------------------------------------------------- helpers for suggest()

    protected static List<String> moduleNames() {
        List<String> names = new ArrayList<>();
        for (Module module : RelaxedQolClient.moduleManager.getModules()) {
            if (module.getCategory().active()) names.add(module.getName());
        }
        return names;
    }

    protected static List<String> playerNames() {
        List<String> names = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) names.add(info.getProfile().name());
        }
        return names;
    }
}
