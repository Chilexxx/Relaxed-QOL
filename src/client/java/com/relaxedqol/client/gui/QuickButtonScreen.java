package com.relaxedqol.client.gui;

import com.relaxedqol.client.module.modules.misc.QuickPanel;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Sets up one Quick Panel button: the name shown on it and the command it runs (e.g. "Farm" and "/home farm").
 * Save / Enter keeps it, Clear empties the button, Cancel / Esc goes back without changes.
 */
public class QuickButtonScreen extends WindowScreen {
    private final QuickPanel panel;
    private final int index;
    private EditBox nameField;
    private EditBox commandField;

    public QuickButtonScreen(Screen parent, QuickPanel panel, int index) {
        super(parent, Component.literal("Quick Panel - Button " + (index + 1)), "quickbutton", 320, 130);
        this.panel = panel;
        this.index = index;
    }

    @Override
    protected boolean compact() {
        return true;
    }

    @Override
    protected boolean rememberPosition() {
        return false;
    }

    @Override
    protected void initContent() {
        // A fixed, compact size in the middle of the screen
        ww = Math.min(340, width - 10);
        wh = 138;
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        int x = contentX();
        // Keep what was typed when the window rebuilds
        String name = nameField != null ? nameField.getValue() : panel.name(index);
        String command = commandField != null ? commandField.getValue() : panel.command(index);

        nameField = new EditBox(font, x, contentY() + 11, contentW(), 15, Component.literal("Name"));
        nameField.setHint(Component.literal("e.g. Farm"));
        nameField.setMaxLength(com.relaxedqol.client.module.modules.misc.QuickPanel.NAME_MAX); // Short names keep the panel narrow
        nameField.setValue(name);
        nameField.moveCursorToEnd(false);
        addRenderableWidget(nameField);

        commandField = new EditBox(font, x, contentY() + 43, contentW(), 15, Component.literal("Command"));
        commandField.setHint(Component.literal("e.g. /home farm"));
        commandField.setMaxLength(256);
        commandField.setValue(command);
        commandField.moveCursorToEnd(false);
        addRenderableWidget(commandField);
        if (command.isEmpty() && !name.isEmpty()) setInitialFocus(commandField);
        else setInitialFocus(nameField);

        int by = contentY() + contentH() - 12;
        pillRow(by, new String[]{"Save", "Clear", "Cancel"}, new Runnable[]{this::save, this::clear, this::onClose});
    }

    private void save() {
        String command = commandField.getValue().trim();
        panel.setButton(index, nameField.getValue().trim(), command);
        onClose();
    }

    private void clear() {
        panel.setButton(index, "", "");
        onClose();
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = contentX();
        g.text(font, "Name on the button", x, contentY(), Theme.TEXT_DIM, false);
        g.text(font, "Command (/server command, .client command, or chat)", x, contentY() + 32, Theme.TEXT_DIM, false);
        String command = commandField != null ? commandField.getValue().trim() : "";
        String note = command.isEmpty() ? "No command = the button stays empty"
            : command.startsWith("/") ? "Runs the server command " + command
            : command.startsWith(".") ? "Runs the client command " + command
            : "Sends \"" + command + "\" in chat";
        note = Theme.ellipsize(note, contentW(), font::width);
        g.text(font, note, x, contentY() + 62, Theme.TEXT_FAINT, false);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == 257 || key == 335) { // Enter / numpad Enter
            save();
            return true;
        }
        return super.keyPressed(event);
    }
}
