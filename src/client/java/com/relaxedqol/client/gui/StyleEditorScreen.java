package com.relaxedqol.client.gui;

import com.relaxedqol.client.module.modules.client.ClickGUI;
import com.relaxedqol.client.setting.Setting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Style Editor: pick the style (Normal / Clean / Vanilla) and the accent, and tune the look with sliders - roundness,
 * how solid panels are, borders, shadows, glow, the glass shine and tint, animation speed. A live preview on the right
 * (a small window with switches, a slider, buttons and a pop-up) changes as you drag.
 */
public class StyleEditorScreen extends WindowScreen {
    private record Slider(String label, Setting<Integer> setting, java.util.function.BooleanSupplier shown) {}

    private final List<Slider> sliders = new ArrayList<>();
    private final List<int[]> sliderRects = new ArrayList<>();
    private final List<Slider> sliderRows = new ArrayList<>();
    private Slider dragging;
    private int dragX;
    private int dragW;
    private float previewToggle = 1f;
    private boolean previewOn = true;
    private float previewSlider = 0.62f;

    public StyleEditorScreen(Screen parent) {
        super(parent, Component.literal("Style Editor"), "styleeditor", 440, 280);
    }

    public static void open(Screen parent) {
        net.minecraft.client.Minecraft.getInstance().gui.setScreen(new StyleEditorScreen(parent));
    }

    private static ClickGUI gui() {
        var mm = com.relaxedqol.client.RelaxedQolClient.moduleManager;
        return mm == null ? null : mm.getModule(ClickGUI.class);
    }

    private String styleName() {
        return VanillaStyle.on() ? "Vanilla" : CleanStyle.liquid() ? "Liquid" : CleanStyle.on() ? "Clean" : "Normal";
    }

    @Override
    protected String titleInfo() {
        return (styleName().equals("Liquid") ? "Liquid Glass" : styleName()) + " Style";
    }

    private int leftW() {
        return Math.max(190, contentW() * 52 / 100);
    }

    private void setStyle(String style) {
        ClickGUI gui = gui();
        if (gui == null) return;
        gui.vanillaStyleSetting().setValue(style.equals("Vanilla"));
        gui.cleanStyleSetting().setValue(style.equals("Clean"));
        gui.liquidStyleSetting().setValue(style.equals("Liquid"));
        Theme.refreshNow();
        save();
        rebuildWidgets();
    }

    private static void save() {
        com.relaxedqol.client.ConfigManager.save(com.relaxedqol.client.ConfigManager.DEFAULT);
    }

    @Override
    protected void initContent() {
        ClickGUI gui = gui();
        sliders.clear();
        if (gui == null) return;
        int x = contentX();
        int y = contentY() + 10;
        int lw = leftW();
        int seg = (lw - 12) / 4;
        String[] styles = {"Normal", "Clean", "Liquid", "Vanilla"};
        for (int i = 0; i < 4; i++) {
            String st = styles[i];
            pill(() -> st, x + i * (seg + 4), y, seg, 15, () -> setStyle(st), 0, () -> styleName().equals(st));
        }
        // Accent: its swatch is drawn next to this button
        pill(() -> "Change Accent", x + 22, y + 22, 92, 13, () -> ColorPickerScreen.open(this, gui.accentSetting(), () -> {
            Theme.refreshNow();
            save();
        }), 0, () -> false);
        sliders.add(new Slider("Roundness", gui.themeRound(), () -> !VanillaStyle.on()));
        sliders.add(new Slider("Solid panels", gui.opacitySetting(), () -> true));
        sliders.add(new Slider("Borders", gui.themeBorder(), () -> !VanillaStyle.on()));
        sliders.add(new Slider("Shadows", gui.themeShadow(), () -> !VanillaStyle.on()));
        sliders.add(new Slider("Glow", gui.themeGlow(), () -> !VanillaStyle.on()));
        sliders.add(new Slider("Glass shine", gui.themeSheen(), CleanStyle::liquid));
        sliders.add(new Slider("Glass tint", gui.glassTintSetting(), CleanStyle::on));
        sliders.add(new Slider("Animation speed", gui.themeAnim(), () -> true));
        int by = contentY() + contentH() - 15;
        pill(() -> "Reset Look", x, by, 80, 15, () -> {
            gui.resetTheme();
            gui.glassTintSetting().reset();
            Theme.refreshNow();
            save();
        }, Theme.DANGER, () -> false);
        pill(() -> "Colours & HUD…", x + 84, by, 96, 15, HudEditorScreen::open, 0, () -> false);
        pill(() -> "Done", x + contentW() - 70, by, 70, 15, this::onClose, 0, () -> true);
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        Theme.refresh();
        ClickGUI gui = gui();
        if (gui == null) return;
        int x = contentX();
        int lw = leftW();
        int y = contentY();
        g.text(font, "Style", x, y, Theme.TEXT_DIM, false);
        // Accent swatch
        int sy = y + 32;
        int accent = Theme.accent();
        if (VanillaStyle.on()) {
            g.fill(x, sy, x + 16, sy + 13, 0xFF000000);
            g.fill(x + 1, sy + 1, x + 15, sy + 12, accent);
        } else {
            Render2D.glow(g, x, sy, 16, 13, accent, 1f);
            Render2D.roundRect(g, x, sy, 16, 13, Math.min(6, Theme.R_CONTROL + 1), accent);
            Render2D.roundOutline(g, x, sy, 16, 13, Math.min(6, Theme.R_CONTROL + 1), 0x40FFFFFF);
        }
        g.text(font, "Accent colour", x + 120, sy + 3, Theme.TEXT_DIM, false);

        // Sliders
        sliderRects.clear();
        sliderRows.clear();
        int ly = sy + 22;
        int bottom = contentY() + contentH() - 20;
        for (Slider s : sliders) {
            if (!s.shown().getAsBoolean()) continue;
            if (ly + 18 > bottom) break;
            boolean hot = dragging == s || UiBits.in(mouseX, mouseY, x, ly, lw, 18);
            g.text(font, s.label(), x, ly + 1, hot ? Theme.TEXT : Theme.TEXT_SECONDARY, false);
            String v = s.setting().getValue() + "%";
            g.text(font, v, x + lw - font.width(v), ly + 1, Theme.TEXT_DIM, false);
            int tx = x + 1;
            int tw = lw - 3;
            Render2D.slider(g, tx, ly + 12, tw, (float) s.setting().getFraction(), accent, hot);
            sliderRects.add(new int[]{tx - 3, ly, tw + 6, 18});
            sliderRows.add(s);
            ly += 21;
        }

        // Live preview
        int px = x + lw + 12;
        int pw = contentW() - lw - 12;
        drawPreview(g, px, y, pw, bottom - y, mouseX, mouseY);
    }

    /** A small window in the current look, a pop-up under it - redrawn from the theme every frame. */
    private void drawPreview(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY) {
        UiBits.well(g, x, y, w, h);
        g.text(font, "Preview", x + 6, y + 5, Theme.TEXT_FAINT, false);
        int accent = Theme.accent();
        int mx = x + 10;
        int my = y + 19;
        int mw = w - 20;
        int mh = Math.min(108, h - 62);
        int th = 14;
        // The mini window
        if (VanillaStyle.on()) {
            VanillaStyle.window(g, mx, my, mw, mh, th);
        } else {
            int r = Theme.R_SURFACE;
            int body = bodyColor();
            Render2D.shadow(g, mx, my, mw, mh, r);
            Render2D.roundRect(g, mx, my, mw, mh, r, body);
            Render2D.roundRectTop(g, mx, my, mw, th, r, 0x08FFFFFF);
            if (CleanStyle.liquid() && Theme.SHEEN > 0.01f) {
                Render2D.roundRectTop(g, mx + 1, my + 1, mw - 2, mh / 3, Math.max(0, r - 1),
                    Theme.withAlpha(0xFFFFFFFF, Math.min(0.14f, 0.05f * Theme.SHEEN)));
            }
            g.fill(mx + 1, my + th - 1, mx + mw - 1, my + th, Theme.HAIRLINE);
            Render2D.roundOutline(g, mx, my, mw, mh, r, Theme.BORDER);
        }
        g.centeredText(font, Component.literal("Window").withStyle(ChatFormatting.BOLD), mx + mw / 2, my + 3, Theme.TEXT);
        int ry = my + th + 6;
        // A switch that is on (click it), and one that is off
        previewToggle += ((previewOn ? 1f : 0f) - previewToggle) * 0.25f;
        g.text(font, "Switch", mx + 8, ry + 1, Theme.TEXT, false);
        Render2D.toggleSwitch(g, mx + mw - 26, ry, previewToggle, accent);
        ry += 14;
        g.text(font, "Another", mx + 8, ry + 1, Theme.TEXT_SECONDARY, false);
        Render2D.toggleSwitch(g, mx + mw - 26, ry, 0f, accent);
        ry += 15;
        Render2D.slider(g, mx + 8, ry + 2, mw - 16, previewSlider, accent, false);
        ry += 12;
        int bw = (mw - 20) / 2;
        boolean o1 = UiBits.in(mouseX, mouseY, mx + 8, ry, bw, 14);
        boolean o2 = UiBits.in(mouseX, mouseY, mx + 12 + bw, ry, bw, 14);
        UiBits.button(g, font, "Button", mx + 8, ry, bw, 14, o1, false);
        UiBits.button(g, font, "Active", mx + 12 + bw, ry, bw, 14, o2, true);

        // A pop-up under it, like the real ones
        int ty = my + mh + 8;
        int tw = mw;
        int tH = 26;
        if (ty + tH > y + h - 2) return;
        if (VanillaStyle.on()) {
            g.fill(mx, ty, mx + tw, ty + tH, 0xF0101010);
            VanillaStyle.bevel(g, mx, ty, tw, tH);
            g.fill(mx + 4, ty + 4, mx + 6, ty + tH - 4, Theme.SUCCESS);
            g.text(font, "Pop-up", mx + 10, ty + 4, 0xFFFFFFFF, true);
            g.text(font, "Looks like this", mx + 10, ty + 14, VanillaStyle.TEXT_GRAY, true);
            return;
        }
        int r = CleanStyle.on() ? Math.min(tH / 2, 10) : Theme.R_SURFACE;
        Render2D.shadow(g, mx, ty, tw, tH, r);
        if (CleanStyle.on()) {
            Render2D.roundRect(g, mx, ty, tw, tH, r, Theme.withAlpha(Theme.mix(Theme.PANEL, accent, 0.08f), 0.55f));
            if (CleanStyle.liquid() && Theme.SHEEN > 0.01f) {
                Render2D.roundRect(g, mx + 1, ty + 1, tw - 2, tH / 2, r - 1, Theme.withAlpha(0xFFFFFFFF, Math.min(0.16f, 0.07f * Theme.SHEEN)));
            }
            Render2D.roundOutline(g, mx, ty, tw, tH, r, 0x38FFFFFF);
            Render2D.smoothCircle(g, mx + 9f, ty + tH / 2f, 2.2f, Theme.SUCCESS, 1f);
        } else {
            Render2D.roundRect(g, mx, ty, tw, tH, r, Theme.withAlpha(Theme.panel(), 0.92f));
            Render2D.roundOutline(g, mx, ty, tw, tH, r, Theme.mix(Theme.BORDER, Theme.withAlpha(Theme.SUCCESS, 0.35f), 0.5f));
            Render2D.roundRect(g, mx + 6, ty + 6, 2, tH - 12, 1, Theme.SUCCESS);
        }
        g.text(font, "Pop-up", mx + 15, ty + 4, Theme.TEXT, false);
        g.text(font, "Looks like this", mx + 15, ty + 14, Theme.TEXT_DIM, false);
    }

    private void dragTo(double mx) {
        if (dragging == null) return;
        Setting<Integer> s = dragging.setting();
        double f = Math.max(0, Math.min(1, (mx - dragX) / Math.max(1, dragW)));
        int min = s.getMin().intValue();
        int max = s.getMax().intValue();
        int step = 5;
        int v = (int) Math.round((min + f * (max - min)) / step) * step;
        s.setValue(Math.max(min, Math.min(max, v)));
        Theme.refreshNow();
    }

    @Override
    protected boolean contentClicked(double mx, double my, int button) {
        if (button != 0) return false;
        for (int i = 0; i < sliderRects.size(); i++) {
            int[] r = sliderRects.get(i);
            if (UiBits.in(mx, my, r[0], r[1], r[2], r[3])) {
                dragging = sliderRows.get(i);
                dragX = r[0] + 3;
                dragW = r[2] - 6;
                dragTo(mx);
                return true;
            }
        }
        // Clicking the preview switch flips it (just to see the animation)
        int px = contentX() + leftW() + 12;
        int pw = contentW() - leftW() - 12;
        int sx = px + 10 + (pw - 20) - 26;
        int sy = contentY() + 19 + 14 + 6;
        if (UiBits.in(mx, my, sx - 2, sy - 2, 20, 12)) {
            previewOn = !previewOn;
            return true;
        }
        return false;
    }

    @Override
    protected boolean contentDragged(double mx, double my) {
        if (dragging == null) return false;
        dragTo(mx);
        return true;
    }

    @Override
    protected void contentReleased() {
        if (dragging != null) save();
        dragging = null;
    }
}
