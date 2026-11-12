package com.relaxedqol.client.module.modules.misc;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.KeySetting;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.ChatUtil;
import com.relaxedqol.client.util.CommandUtil;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Quick Panel: a small panel with 4 buttons on every open chest / inventory (or every screen). Give each button a
 * name and a command - e.g. "Farm" runs "/home farm" - and one click runs it. Right-click a button (or click an
 * empty one) to set it up; it can also be set here in the settings. Each button can have its own key too.
 * Drag the panel by its title, resize it from its corner. The gear in its title opens the client menu.
 */
public class QuickPanel extends Module {
    /** Room for this many buttons; "Buttons" picks how many are shown. */
    public static final int BUTTONS = 12;

    /** Where the radar cards can be moved (not shown: the panel itself only shows on your inventory). */
    private final Setting<Boolean> allScreens = addSetting(new Setting<>("All Screens", true).hide());
    private final Setting<Integer> count = addSetting(new Setting<>("Buttons", 4, 1, BUTTONS, 1),
        "How many command buttons the panel has (1 - " + BUTTONS + ")");
    private final Setting<String> layout = addSetting(Setting.mode("Layout", "Grid", "Grid", "Row", "Column"),
        "How the buttons are arranged: a grid (pick how many columns), one row, or one column");
    private final Setting<Integer> columns = addSetting(new Setting<>("Columns", 2, 1, 6, 1)
            .showIf(() -> !"Row".equals(this.layout.getValue()) && !"Column".equals(this.layout.getValue())),
        "How many buttons side by side in the grid (2 = the classic 2 x 2 with 4 buttons)");
    private final Setting<Boolean> feedback = addSetting(new Setting<>("Chat Feedback", false),
        "Say in chat which command a button sent");
    private final Setting<Boolean> closeOnUse = addSetting(new Setting<>("Close On Click", false),
        "Clicking one of your buttons closes the inventory / chest (or whatever screen you are in) first, then runs it");
    private final Setting<Integer> textSize = addSetting(new Setting<>("Text Size %", 115, 100, 200, 5),
        "How big the panel's text is (the buttons grow with it). Drag the panel's corner to resize the whole panel");

    private final Setting<String>[] names = newArray();
    private final Setting<String>[] commands = newArray();
    private final KeySetting[] keys = new KeySetting[BUTTONS];
    private final boolean[] keyWasDown = new boolean[BUTTONS];

    private final Setting<Integer> x = addSetting(new Setting<>("X", 6, 0, 8000, 1).hide());
    private final Setting<Integer> y = addSetting(new Setting<>("Y", 40, 0, 8000, 1).hide());
    private final Setting<Integer> scale = addSetting(new Setting<>("Panel Scale", 100, 60, 200, 1).hide());
    /** False until you drag the panel yourself: until then it sits right under the HUD's info panel. */
    private final Setting<Boolean> placed = addSetting(new Setting<>("Placed", false).hide());

    @SuppressWarnings("unchecked")
    private static Setting<String>[] newArray() {
        return (Setting<String>[]) new Setting[BUTTONS];
    }

    public QuickPanel() {
        super("QuickPanel", "The panel on every chest / inventory: up to " + BUTTONS + " buttons with your own commands (e.g. /home farm) and"
            + " the slot under your mouse", ModuleCategory.MISC);
        for (int i = 0; i < BUTTONS; i++) {
            int n = i + 1;
            int index = i;
            java.util.function.BooleanSupplier shown = () -> index < count();
            names[i] = addSetting(new Setting<>("Button " + n + " Name", "").prompt().showIf(shown),
                "The text on button " + n + " (e.g. Farm), at most " + NAME_MAX + " letters. Empty = the command itself is shown");
            commands[i] = addSetting(new Setting<>("Button " + n + " Command", "").prompt().showIf(shown),
                "What button " + n + " runs, e.g. /home farm. Starting with / = a server command, . = a client"
                    + " command, anything else is sent as a chat message");
            keys[i] = addSetting((KeySetting) new KeySetting("Button " + n + " Key", -1, true).showIf(shown),
                "Runs button " + n + " with a key while no screen is open (Bind = no key)");
        }
        setEnabled(true);
    }

    // ------------------------------------------------------------------ buttons

    /** The text shown on a button: its name, or its command when it has no name, or "" when it is not set. */
    /** Button names are at most this long (longer ones made the panel far too wide). */
    public static final int NAME_MAX = 10;

    /** The text on a button: its name, else its command - never longer than NAME_MAX letters. */
    public String label(int i) {
        String name = names[i].getValue().trim();
        if (name.isEmpty()) name = commands[i].getValue().trim();
        return name.length() > NAME_MAX ? name.substring(0, NAME_MAX) : name;
    }

    public String command(int i) {
        return commands[i].getValue().trim();
    }

    public String name(int i) {
        return names[i].getValue().trim();
    }

    public boolean isSet(int i) {
        return !command(i).isEmpty();
    }

    public void setButton(int i, String name, String command) {
        String n = name == null ? "" : name.trim();
        names[i].setValue(n.length() > NAME_MAX ? n.substring(0, NAME_MAX) : n);
        commands[i].setValue(command == null ? "" : command.trim());
        com.relaxedqol.client.ConfigManager.save(com.relaxedqol.client.ConfigManager.DEFAULT);
    }

    /** Runs button i. Returns false when it has no command or there is no server to send it to. */
    public boolean run(int i) {
        if (i < 0 || i >= count() || !isSet(i)) return false;
        String line = command(i);
        boolean sent = CommandUtil.run(line);
        if (sent && feedback.getValue() && !line.startsWith(".")) ChatUtil.send("§7» " + label(i) + ": §f" + line);
        return sent;
    }

    /** Every tick (from the client tick): the button keys, only while no screen is open. */
    public void keyTick() {
        for (int i = 0; i < BUTTONS; i++) {
            int key = keys[i].getKey();
            boolean down = isEnabled() && i < count() && key >= 0 && mc.getWindow() != null && InputConstants.isKeyDown(mc.getWindow(), key);
            if (down && !keyWasDown[i] && mc.gui.screen() == null && mc.player != null) run(i);
            keyWasDown[i] = down;
        }
    }

    // ------------------------------------------------------------------ panel placement

    public boolean closeOnUse() {
        return closeOnUse.getValue();
    }

    public boolean onAllScreens() {
        return allScreens.getValue();
    }

    /** "Grid", "Row" or "Column". */
    public String layoutMode() {
        return layout.getValue();
    }

    /** How many buttons are shown. */
    public int count() {
        return Math.max(1, Math.min(BUTTONS, count.getValue()));
    }

    /** Buttons side by side: the grid's columns, all of them in a row, or 1 in a column. */
    public int columnCount() {
        return switch (layout.getValue()) {
            case "Row" -> count();
            case "Column" -> 1;
            default -> Math.max(1, Math.min(count(), columns.getValue()));
        };
    }

    public int getX() {
        return x.getValue();
    }

    public int getY() {
        return y.getValue();
    }

    public int getScalePercent() {
        return scale.getValue();
    }

    public int getTextPercent() {
        return textSize.getValue();
    }

    public void setScalePercent(int percent) {
        scale.setValue(percent);
    }

    public boolean isPlaced() {
        return placed.getValue();
    }

    /** Called when you drag the panel: from now on it stays where you put it. */
    public void markPlaced() {
        placed.setValue(true);
    }

    public void setPosition(int newX, int newY) {
        x.setValue(Math.max(0, newX));
        y.setValue(Math.max(0, newY));
    }

    @Override
    public boolean savesEnabledState() {
        return true;
    }
}
