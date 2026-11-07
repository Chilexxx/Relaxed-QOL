package com.relaxedqol.client.gui;

import com.relaxedqol.client.setting.ColorSetting;
import com.relaxedqol.client.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Colour picker: a palette of swatches (plus "Accent" and "Rainbow" where they make sense). Click one and it
 * is set straight away - no scrolling through names, no hex codes. Opened from any colour setting's swatch.
 */
public class ColorPickerScreen extends WindowScreen {
    private static final int COLS = 8;
    private static final int SW = 24;
    private static final int SH = 18;
    private static final int GAP = 4;

    private final ColorSetting setting;
    private final Runnable onPick;
    private final List<String> names = new ArrayList<>();
    private final List<int[]> rects = new ArrayList<>();
    private String hover;

    private ColorPickerScreen(Screen parent, ColorSetting setting, Runnable onPick) {
        super(parent, Component.literal("Colour - " + setting.getName()), "colorpicker", COLS * (SW + GAP) + 16, 210);
        this.setting = setting;
        this.onPick = onPick;
        if (setting.allowsAccent()) names.add("Accent");
        if (setting.allowsRainbow()) names.add("Rainbow");
        java.util.Collections.addAll(names, Colors.PALETTE);
    }

    public static void open(Screen parent, ColorSetting setting, Runnable onPick) {
        Minecraft.getInstance().gui.setScreen(new ColorPickerScreen(parent, setting, onPick));
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
    protected String titleInfo() {
        return hover != null ? hover : setting.label();
    }

    private int specials() {
        return (setting.allowsAccent() ? 1 : 0) + (setting.allowsRainbow() ? 1 : 0);
    }

    @Override
    protected void initContent() {
        int rows = (Colors.PALETTE.length + COLS - 1) / COLS;
        ww = Math.min(width - 10, COLS * (SW + GAP) - GAP + 16);
        wh = TITLE_H + 8 + 22 + (specials() > 0 ? SH + 8 : 0) + rows * (SH + GAP) + 4;
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        rects.clear();
        int x0 = contentX() + 2;
        int y = contentY();
        int accent = Theme.accent();
        String current = setting.getValue();
        hover = null;

        // Current colour: a wide swatch with its name
        int now = setting.argb();
        Render2D.roundRect(g, x0, y, 40, 16, 5, now);
        Render2D.roundOutline(g, x0, y, 40, 16, 5, 0x60FFFFFF);
        g.text(font, "Now: " + setting.label(), x0 + 48, y + 4, Theme.TEXT, false);
        y += 22;

        int index = 0;
        int special = specials();
        if (special > 0) {
            int w = (contentW() - 4 - GAP) / 2;
            for (int i = 0; i < special; i++) {
                String name = names.get(index++);
                int sx = x0 + i * (w + GAP);
                boolean over = mouseX >= sx && mouseX < sx + w && mouseY >= y && mouseY < y + SH;
                boolean picked = name.equalsIgnoreCase(current);
                int c = Colors.of(name);
                Render2D.roundRect(g, sx, y, w, SH, 6, Theme.withAlpha(c, over ? 0.55f : 0.35f));
                Render2D.roundOutline(g, sx, y, w, SH, 6, picked ? 0xFFFFFFFF : over ? c : Theme.withAlpha(c, 0.7f));
                g.centeredText(font, name.equals("Accent") ? "Accent (menu colour)" : "Rainbow", sx + w / 2, y + 5, Theme.TEXT);
                if (over) hover = name;
                rects.add(new int[]{sx, y, w, SH});
            }
            y += SH + 8;
        }
        for (int i = 0; index < names.size(); i++, index++) {
            String name = names.get(index);
            int col = i % COLS;
            int row = i / COLS;
            int sx = x0 + col * (SW + GAP);
            int sy = y + row * (SH + GAP);
            boolean over = mouseX >= sx && mouseX < sx + SW && mouseY >= sy && mouseY < sy + SH;
            boolean picked = name.equalsIgnoreCase(Colors.nameOf(current));
            int c = Colors.paletteColor(name);
            if (over) Render2D.roundRect(g, sx - 2, sy - 2, SW + 4, SH + 4, 7, Theme.withAlpha(accent, 0.35f));
            Render2D.roundRect(g, sx, sy, SW, SH, 5, c);
            Render2D.roundOutline(g, sx, sy, SW, SH, 5, picked ? 0xFFFFFFFF : 0x30FFFFFF);
            if (picked) Render2D.checkmark(g, sx + SW / 2 - 4, sy + SH / 2 - 3, luminance(c) > 0.6f ? 0xFF101018 : 0xFFFFFFFF);
            if (over) hover = name;
            rects.add(new int[]{sx, sy, SW, SH});
        }
    }

    private static float luminance(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    @Override
    protected boolean contentClicked(double mx, double my, int button) {
        for (int i = 0; i < rects.size() && i < names.size(); i++) {
            int[] r = rects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                setting.setValue(names.get(i));
                if (onPick != null) onPick.run();
                onClose();
                return true;
            }
        }
        return false;
    }
}
