package com.relaxedqol.client.setting;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Not a value but a live picture in the module's settings (e.g. Crosshair's preview): the menu gives it a row of
 * {@link #height()} and lets it paint itself there. Not saved, not clickable, nothing to type.
 */
public class PreviewSetting extends Setting<String> {
    /** Paints the preview into the box x, y, w, h (menu coordinates). */
    public interface Painter {
        void paint(GuiGraphicsExtractor g, int x, int y, int w, int h);
    }

    private final int height;
    private final Painter painter;

    public PreviewSetting(String name, int height, Painter painter) {
        super(name, "");
        this.height = height;
        this.painter = painter;
    }

    public int height() {
        return height;
    }

    public void paint(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        painter.paint(g, x, y, w, h);
    }

    @Override
    public boolean isText() {
        return false;
    }

    @Override
    public boolean isSaved() {
        return false;
    }

    @Override
    public void cycle(int direction) {
        // nothing to change
    }

    @Override
    public boolean parse(String text) {
        return false;
    }

    @Override
    public String display() {
        return "";
    }
}
