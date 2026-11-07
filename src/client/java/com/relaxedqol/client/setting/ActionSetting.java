package com.relaxedqol.client.setting;

/** A button in the module's settings, e.g. "Flush Now". Clicking it runs the action. */
public class ActionSetting extends Setting<String> {
    private final Runnable action;

    public ActionSetting(String name, Runnable action) {
        super(name, name, null, null, null, null);
        this.action = action;
    }

    public void run() {
        action.run();
    }

    @Override
    public boolean isAction() {
        return true;
    }

    @Override
    public boolean isSaved() {
        return false;
    }

    @Override
    public void cycle(int direction) {
        run();
    }

    @Override
    public boolean parse(String text) {
        run();
        return true;
    }

    @Override
    public String display() {
        return "Run";
    }
}
