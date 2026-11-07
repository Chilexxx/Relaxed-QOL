package com.relaxedqol.client.module;

/**
 * A module that does one thing when triggered (clicked in the GUI or its keybind pressed),
 * instead of staying on.
 */
public abstract class ActionModule extends Module {
    protected ActionModule(String name, String description, ModuleCategory category) {
        super(name, description, category);
    }

    public abstract void run();

    @Override
    protected final void onEnable() {
        try {
            run();
        } finally {
            setEnabled(false);
        }
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }
}
