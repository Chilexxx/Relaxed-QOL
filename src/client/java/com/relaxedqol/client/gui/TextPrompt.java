package com.relaxedqol.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** A small one-field text prompt (used to edit macro step arguments). OK/Enter accepts, Esc/Cancel returns. */
public class TextPrompt extends WindowScreen {
    private final String prompt;
    private final String hint;
    private final String initial;
    private final Consumer<String> onAccept;
    private EditBox field;

    private TextPrompt(Screen parent, String prompt, String hint, String initial, Consumer<String> onAccept) {
        super(parent, Component.literal(prompt), "textprompt", 320, 96);
        this.prompt = prompt;
        this.hint = hint;
        this.initial = initial;
        this.onAccept = onAccept;
    }

    @Override
    protected boolean compact() {
        return true;
    }

    public static void open(Screen parent, String prompt, String hint, String initial, Consumer<String> onAccept) {
        Minecraft.getInstance().gui.setScreen(new TextPrompt(parent, prompt, hint, initial, onAccept));
    }

    @Override
    protected void initContent() {
        // Keep this prompt a fixed, compact size
        ww = Math.min(340, width - 10);
        wh = 104;
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        int x = contentX();
        String typed = field != null ? field.getValue() : initial; // keep what was typed when the window rebuilds
        field = new EditBox(font, x, contentY() + 11, contentW(), 15, Component.literal("Value"));
        if (!hint.isEmpty()) field.setHint(Component.literal(hint));
        field.setMaxLength(256);
        field.setValue(typed);
        field.moveCursorToEnd(false);
        addRenderableWidget(field);
        setInitialFocus(field);
        int by = contentY() + contentH() - 12;
        pillRow(by, new String[]{"OK", "Cancel"}, new Runnable[]{this::accept, this::onClose});
    }

    private void accept() {
        onAccept.accept(field.getValue());
        onClose();
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        String title = Theme.ellipsize(prompt, contentW(), font::width);
        g.text(font, title, contentX(), contentY(), Theme.TEXT_DIM, false);
        // The hint again under the field (it is hidden inside the field once there is text), wrapped to two lines
        if (!hint.isEmpty()) {
            int y = contentY() + 29;
            String rest = hint;
            for (int line = 0; line < 2 && !rest.isEmpty(); line++) {
                String piece = font.plainSubstrByWidth(rest, contentW());
                if (piece.length() < rest.length()) {
                    int space = piece.lastIndexOf(' ');
                    if (space > 10) piece = piece.substring(0, space);
                }
                g.text(font, piece.trim(), contentX(), y, Theme.TEXT_FAINT, false);
                rest = rest.substring(piece.length()).trim();
                y += 9;
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == 257 || key == 335) { // Enter / numpad Enter
            accept();
            return true;
        }
        return super.keyPressed(event);
    }
}
