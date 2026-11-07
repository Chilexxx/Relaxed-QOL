package com.relaxedqol.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.ConfigManager;
import com.relaxedqol.client.module.modules.client.ClickGUI;
import com.relaxedqol.client.setting.ColorSetting;
import com.relaxedqol.client.setting.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD editor (Lunar-style): the game stays visible, every HUD element shows with a frame around it.
 * - Drag an element to move it: it snaps to the screen edges, the centre lines and the other elements (guide lines
 *   show where); hold Shift to place it freely. Drag its corner to make it bigger / smaller. Right-click it to put it
 *   back at its automatic spot.
 * - Click an element: a side panel opens with its look - its own background colour / opacity, text and accent colour,
 *   border, roundness, shadow, text shadow and size. Every change shows on the element straight away.
 * - Theme (top bar): the client's look everywhere - accent, surface and text colour, menu background, borders,
 *   roundness, shadows and animation speed - with a live preview of a window, buttons, a module row and controls.
 * Opened from the menu's "Edit HUD" button (or Settings > Edit HUD & Theme).
 */
public class HudEditorScreen extends Screen {
    private static final int PANEL_W = 196;
    private static final int ROW = 16;
    private static final int SLIDER_ROW = 24;
    private static final int SNAP = 5;

    private final Screen parent;

    /** The element whose look is being edited (by its window: the element list is made fresh every frame). */
    private HudWindow selected;
    /** The side panel shows the theme instead of an element. */
    private boolean themeTab;

    private HudWindow moving;
    private HudWindow resizing;
    private int grabX;
    private int grabY;
    private double pressX;
    private double pressY;
    private boolean moved;
    /** Guide lines to draw (HUD pixels): {x1, y1, x2, y2}. */
    private final List<int[]> guides = new ArrayList<>();

    /** Panel controls laid out on the last frame (panel pixels). */
    private final List<Control> controls = new ArrayList<>();
    private final List<Control> barButtons = new ArrayList<>();
    private Setting<?> dragSlider;
    private int sliderX;
    private int sliderW;
    private int panelScroll;
    private int panelContentH;
    private int[] panelRect = {0, 0, 0, 0};
    private final Anim anim = new Anim();

    private enum Kind { HEADER, NOTE, TOGGLE, SLIDER, COLOR, BUTTON, PREVIEW }

    /** One row of the side panel. */
    private record Control(Kind kind, String label, Setting<?> setting, Runnable action, int color, int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    public HudEditorScreen(Screen parent) {
        super(Component.literal("HUD Editor"));
        this.parent = parent;
    }

    /** Opens the editor on top of whatever is open (the menu comes back when you are done). */
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) {
                com.relaxedqol.client.util.ChatUtil.send("§eJoin a world to edit the HUD - it is shown over the game");
                return;
            }
            Screen current = mc.gui.screen();
            mc.gui.setScreen(new HudEditorScreen(current instanceof HudEditorScreen e ? e.parent : current));
        });
    }

    // ------------------------------------------------------------------ scales

    /** HUD pixels per GUI unit (elements live in HUD pixels). */
    private static float hudScale() {
        return OverlayRenderer.hudScale();
    }

    /** The editor's own panels follow the menu's size (Menu Scale %). */
    private float panelScale() {
        ClickGUI gui = gui();
        return Theme.layoutScale(minecraft.getWindow().getGuiScale(), gui != null ? gui.getSizePercent() : 100);
    }

    private static ClickGUI gui() {
        return RelaxedQolClient.moduleManager == null ? null : RelaxedQolClient.moduleManager.getModule(ClickGUI.class);
    }

    private HudElement selectedElement() {
        if (selected == null) return null;
        for (HudElement e : OverlayRenderer.elements()) if (e.window() == selected) return e;
        return null;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // No blur: the game and the HUD stay sharp behind the editor - only a light veil so the frames read
        g.fill(0, 0, width, height, 0x38000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            render$smooth(g, mouseX, mouseY, partialTick);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private void render$smooth(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Theme.refresh();
        anim.tick();
        int accent = Theme.accent();
        if (selected != null && selectedElement() == null) selected = null;

        // ---- element frames, in HUD pixels
        float hs = hudScale();
        double hx = mouseX / hs;
        double hy = mouseY / hs;
        int hvw = Math.round(width / hs);
        int hvh = Math.round(height / hs);
        g.pose().pushMatrix();
        g.pose().scale(hs, hs);
        boolean busy = moving != null || resizing != null;
        if (busy) {
            // The screen's centre lines, faint, while something is being placed
            g.fill(hvw / 2, 0, hvw / 2 + 1, hvh, 0x14FFFFFF);
            g.fill(0, hvh / 2, hvw, hvh / 2 + 1, 0x14FFFFFF);
        }
        HudWindow hover = busy || overPanel(mouseX, mouseY) ? null : windowAt(hx, hy);
        for (HudElement e : OverlayRenderer.elements()) {
            HudWindow w = e.window();
            if (!w.isVisible()) continue;
            boolean sel = w == selected;
            boolean hot = w == hover || w == moving || w == resizing;
            int x = w.getX();
            int y = w.getY();
            int ww = w.getW();
            int wh = w.getH();
            int edge = sel ? accent : hot ? Theme.withAlpha(accent, 0.7f) : 0x40FFFFFF;
            if (sel || hot) g.fill(x - 2, y - 2, x + ww + 2, y + wh + 2, Theme.withAlpha(accent, sel ? 0.10f : 0.06f));
            Render2D.roundOutline(g, x - 2, y - 2, ww + 4, wh + 4, 3, edge);
            if (sel || hot) {
                Render2D.resizeGrip(g, x + ww - 4, y + wh - 4, edge);
                // Its name in a small tag above it (below it when there is no room above)
                String name = e.name();
                int tw = font.width(name) + 8;
                int ty = y - 14 >= 0 ? y - 14 : y + wh + 4;
                Render2D.roundRect(g, x - 2, ty, tw, 11, Theme.R_CONTROL, sel ? accent : 0xE01C1D21);
                g.text(font, name, x + 2, ty + 2, sel ? 0xFFFFFFFF : Theme.TEXT, false);
            }
        }
        int guideColor = Theme.withAlpha(accent, 0.85f);
        for (int[] l : guides) g.fill(l[0], l[1], Math.max(l[0] + 1, l[2]), Math.max(l[1] + 1, l[3]), guideColor);
        g.pose().popMatrix();

        // ---- the bar on top and the side panel, at the menu's size
        float ps = panelScale();
        int vw = Math.round(width / ps);
        int vh = Math.round(height / ps);
        int mx = Math.round(mouseX / ps);
        int my = Math.round(mouseY / ps);
        g.pose().pushMatrix();
        g.pose().scale(ps, ps);
        float prevText = CrispText.begin(ps);
        drawBar(g, vw, mx, my, accent);
        HudElement el = selectedElement();
        if (themeTab || el != null) drawPanel(g, vw, vh, mx, my, accent, el);
        else panelRect = new int[]{0, 0, 0, 0};
        drawHint(g, vw, vh);
        CrispText.end(prevText);
        g.pose().popMatrix();
    }

    private void drawBar(GuiGraphicsExtractor g, int vw, int mx, int my, int accent) {
        barButtons.clear();
        String[] labels = {"Theme", "Reset Positions", "Done"};
        int gap = 4;
        int h = 18;
        int titleW = CrispText.width(font, "HUD Editor") + 20;
        int total = titleW;
        int[] widths = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            widths[i] = CrispText.width(font, labels[i]) + 18;
            total += gap + widths[i];
        }
        int x = (vw - total - 8) / 2;
        int y = 8;
        Render2D.shadow(g, x, y, total + 8, h + 8, Theme.R_SURFACE);
        Render2D.roundRect(g, x, y, total + 8, h + 8, Theme.R_SURFACE, Render2D.POPUP_BODY);
        Render2D.roundOutline(g, x, y, total + 8, h + 8, Theme.R_SURFACE, Theme.BORDER);
        int bx = x + 4;
        Render2D.icon(g, "pin", bx + 4, y + 4 + (h - 9) / 2, 9, accent);
        CrispText.text(g, font, "HUD Editor", bx + 16, y + 4 + (h - 8) / 2, Theme.TEXT);
        bx += titleW;
        for (int i = 0; i < labels.length; i++) {
            bx += gap;
            String label = labels[i];
            boolean active = i == 0 ? themeTab : i == 2;
            boolean over = mx >= bx && mx < bx + widths[i] && my >= y + 4 && my < y + 4 + h;
            int fill = active ? Theme.withAlpha(accent, over ? 0.45f : 0.30f) : over ? 0x24FFFFFF : Theme.PILL;
            Render2D.roundRect(g, bx, y + 4, widths[i], h, Theme.R_CONTROL, fill);
            Render2D.roundOutline(g, bx, y + 4, widths[i], h, Theme.R_CONTROL, active ? Theme.withAlpha(accent, 0.8f) : Theme.HAIRLINE);
            CrispText.centered(g, font, label, bx + widths[i] / 2, y + 4 + (h - 8) / 2, active || over ? Theme.TEXT : Theme.TEXT_SECONDARY);
            final int idx = i;
            barButtons.add(new Control(Kind.BUTTON, label, null, () -> barAction(idx), 0, bx, y + 4, widths[i], h));
            bx += widths[i];
        }
    }

    private void barAction(int index) {
        switch (index) {
            case 0 -> {
                themeTab = !themeTab;
                if (themeTab) selected = null;
                panelScroll = 0;
            }
            case 1 -> {
                for (HudElement e : OverlayRenderer.elements()) e.window().resetPlacement();
                save();
            }
            default -> onClose();
        }
    }

    private void drawHint(GuiGraphicsExtractor g, int vw, int vh) {
        String hint = "Drag to move  ·  drag the corner to resize  ·  click to style  ·  right-click to reset  ·  Shift: no snapping";
        hint = Theme.ellipsize(hint, vw - 20, t -> CrispText.width(font, t));
        int w = CrispText.width(font, hint) + 16;
        int x = (vw - w) / 2;
        int y = vh - 22;
        Render2D.roundRect(g, x, y, w, 15, Theme.R_CONTROL, 0xC0141518);
        CrispText.text(g, font, hint, x + 8, y + 4, Theme.TEXT_DIM);
    }

    /** The side panel: the selected element's look, or the theme. On the side of the screen away from the element. */
    private void drawPanel(GuiGraphicsExtractor g, int vw, int vh, int mx, int my, int accent, HudElement el) {
        controls.clear();
        boolean left = false;
        if (!themeTab && el != null) {
            float k = hudScale() / panelScale();
            int centre = Math.round((el.window().getX() + el.window().getW() / 2f) * k);
            left = centre > vw / 2;
        }
        int w = PANEL_W;
        int x = left ? 8 : vw - w - 8;
        int top = 42;
        int maxH = vh - top - 30;

        // Lay the rows out first (content coordinates), then draw them clipped and scrolled
        List<Control> rows = new ArrayList<>();
        int cy = 0;
        int inner = w - 16;
        if (themeTab) {
            ClickGUI gui = gui();
            rows.add(new Control(Kind.HEADER, "Theme", null, null, 0, 0, cy, inner, 18));
            cy += 20;
            rows.add(new Control(Kind.PREVIEW, "", null, null, 0, 0, cy, inner, 104));
            cy += 110;
            if (gui != null) {
                cy = add(rows, Kind.COLOR, "Accent", gui.accentSetting(), cy, inner);
                cy = add(rows, Kind.COLOR, "Surface", gui.themeSurface(), cy, inner);
                cy = add(rows, Kind.COLOR, "Text", gui.themeText(), cy, inner);
                cy = add(rows, Kind.SLIDER, "Menu background %", gui.opacitySetting(), cy, inner);
                cy = add(rows, Kind.SLIDER, "Borders %", gui.themeBorder(), cy, inner);
                cy = add(rows, Kind.SLIDER, "Roundness %", gui.themeRound(), cy, inner);
                cy = add(rows, Kind.SLIDER, "Shadows %", gui.themeShadow(), cy, inner);
                cy = add(rows, Kind.SLIDER, "Animation speed %  (0 = off)", gui.themeAnim(), cy, inner);
                cy += 4;
                rows.add(new Control(Kind.BUTTON, "Reset theme", null, () -> {
                    gui.resetTheme();
                    save();
                }, Theme.DANGER, 0, cy, inner, ROW));
                cy += ROW + 2;
            }
        } else if (el != null) {
            rows.add(new Control(Kind.HEADER, el.name(), null, null, 0, 0, cy, inner, 18));
            cy += 20;
            cy = add(rows, Kind.SLIDER, "Size %", el.window().scaleSettingRef(), cy, inner);
            rows.add(new Control(Kind.BUTTON, "Reset position & size", null, () -> {
                el.window().resetPlacement();
                save();
            }, 0, 0, cy, inner, ROW));
            cy += ROW + 6;
            CardStyle st = el.style();
            if (st == null) {
                rows.add(new Control(Kind.NOTE, "This element always uses the theme's look", null, null, 0, 0, cy, inner, ROW));
                cy += ROW;
            } else {
                cy = add(rows, Kind.TOGGLE, "Custom look", st.custom, cy, inner);
                if (!st.isCustom()) {
                    rows.add(new Control(Kind.NOTE, "Follows the theme - switch on to style it", null, null, 0, 0, cy, inner, ROW));
                    cy += ROW;
                } else {
                    cy = add(rows, Kind.COLOR, "Background", st.background, cy, inner);
                    cy = add(rows, Kind.SLIDER, "Background opacity %", st.opacity, cy, inner);
                    cy = add(rows, Kind.COLOR, "Text", st.text, cy, inner);
                    cy = add(rows, Kind.COLOR, "Accent", st.accent, cy, inner);
                    cy = add(rows, Kind.TOGGLE, "Border", st.border, cy, inner);
                    if (st.border.getValue()) {
                        cy = add(rows, Kind.COLOR, "Border colour", st.borderColor, cy, inner);
                        cy = add(rows, Kind.SLIDER, "Border strength %", st.borderOpacity, cy, inner);
                    }
                    cy = add(rows, Kind.SLIDER, "Roundness", st.radius, cy, inner);
                    cy = add(rows, Kind.TOGGLE, "Shadow", st.shadow, cy, inner);
                    cy = add(rows, Kind.TOGGLE, "Text shadow", st.textShadow, cy, inner);
                    cy += 4;
                    rows.add(new Control(Kind.BUTTON, "Reset look", null, () -> {
                        st.reset();
                        save();
                    }, Theme.DANGER, 0, cy, inner, ROW));
                    cy += ROW + 2;
                }
            }
        }
        panelContentH = cy;
        int h = Math.min(maxH, cy + 16);
        panelScroll = Math.max(0, Math.min(panelScroll, Math.max(0, cy + 16 - h)));
        panelRect = new int[]{x, top, w, h};

        Render2D.shadow(g, x, top, w, h, Theme.R_SURFACE);
        Render2D.roundRect(g, x, top, w, h, Theme.R_SURFACE, Render2D.POPUP_BODY);
        Render2D.roundOutline(g, x, top, w, h, Theme.R_SURFACE, Theme.BORDER);
        // Close (x) in the corner
        boolean overClose = mx >= x + w - 18 && mx < x + w - 4 && my >= top + 4 && my < top + 18;
        if (overClose) Render2D.roundRect(g, x + w - 18, top + 4, 14, 14, Theme.R_CONTROL, 0x24FFFFFF);
        Render2D.icon(g, "close", x + w - 14, top + 8, 6, overClose ? Theme.TEXT : Theme.TEXT_DIM);
        controls.add(new Control(Kind.BUTTON, "close", null, () -> {
            selected = null;
            themeTab = false;
        }, 0, x + w - 18, top + 4, 14, 14));

        g.enableScissor(x + 1, top + 1, x + w - 1, top + h - 1);
        int ox = x + 8;
        int oy = top + 8 - panelScroll;
        for (Control r : rows) {
            Control c = new Control(r.kind(), r.label(), r.setting(), r.action(), r.color(), ox + r.x(), oy + r.y(), r.w(), r.h());
            if (c.y() + c.h() < top || c.y() > top + h) continue;
            drawControl(g, c, mx, my, accent);
            if (my >= top && my < top + h) controls.add(c);
        }
        g.disableScissor();
        // Scroll bar when it does not all fit
        if (cy + 16 > h) {
            int trackH = h - 12;
            int thumbH = Math.max(14, trackH * h / (cy + 16));
            int thumbY = top + 6 + (trackH - thumbH) * panelScroll / Math.max(1, cy + 16 - h);
            Render2D.roundRect(g, x + w - 5, thumbY, 3, thumbH, 2, 0x4DFFFFFF);
        }
    }

    private int add(List<Control> rows, Kind kind, String label, Setting<?> setting, int cy, int w) {
        int h = kind == Kind.SLIDER ? SLIDER_ROW : ROW;
        rows.add(new Control(kind, label, setting, null, 0, 0, cy, w, h));
        return cy + h + 2;
    }

    private void drawControl(GuiGraphicsExtractor g, Control c, int mx, int my, int accent) {
        boolean over = c.contains(mx, my);
        int x = c.x();
        int y = c.y();
        int w = c.w();
        switch (c.kind()) {
            case HEADER -> {
                CrispText.text(g, font, Component.literal(c.label()).withStyle(net.minecraft.ChatFormatting.BOLD), x, y + 4, Theme.TEXT);
                g.fill(x, y + c.h() - 1, x + w - 16, y + c.h(), Theme.HAIRLINE);
            }
            case NOTE -> CrispText.text(g, font, Theme.ellipsize(c.label(), w, t -> CrispText.width(font, t)), x, y + 4, Theme.TEXT_FAINT);
            case TOGGLE -> {
                if (over) Render2D.roundRect(g, x - 4, y, w + 8, c.h(), Theme.R_CONTROL, 0x0FFFFFFF);
                CrispText.text(g, font, c.label(), x, y + 4, Theme.TEXT_SECONDARY);
                boolean on = (Boolean) c.setting().getValue();
                float p = anim.smooth(c.setting(), on ? 1 : 0, 18);
                Render2D.toggleSwitch(g, x + w - 16, y + 4, p, accent);
            }
            case SLIDER -> {
                if (over) Render2D.roundRect(g, x - 4, y, w + 8, c.h(), Theme.R_CONTROL, 0x0FFFFFFF);
                CrispText.text(g, font, c.label(), x, y + 3, Theme.TEXT_SECONDARY);
                String value = String.valueOf(c.setting().getValue());
                CrispText.right(g, font, value, x + w, y + 3, Theme.TEXT);
                Render2D.slider(g, x, y + 16, w, (float) c.setting().getFraction(), accent, c.setting() == dragSlider);
            }
            case COLOR -> {
                if (over) Render2D.roundRect(g, x - 4, y, w + 8, c.h(), Theme.R_CONTROL, 0x0FFFFFFF);
                CrispText.text(g, font, c.label(), x, y + 4, Theme.TEXT_SECONDARY);
                ColorSetting cs = (ColorSetting) c.setting();
                int sw = 18;
                Render2D.roundRect(g, x + w - sw, y + 3, sw, 10, 3, cs.argb());
                Render2D.roundOutline(g, x + w - sw, y + 3, sw, 10, 3, over ? 0xB0FFFFFF : 0x40FFFFFF);
                String name = cs.label();
                CrispText.right(g, font, name, x + w - sw - 5, y + 4, Theme.TEXT_DIM);
            }
            case BUTTON -> {
                int col = c.color() == 0 ? accent : c.color();
                Render2D.roundRect(g, x, y, w, c.h(), Theme.R_CONTROL, over ? Theme.withAlpha(col, 0.30f) : Theme.PILL);
                Render2D.roundOutline(g, x, y, w, c.h(), Theme.R_CONTROL, over ? Theme.withAlpha(col, 0.8f) : Theme.HAIRLINE);
                CrispText.centered(g, font, c.label(), x + w / 2, y + (c.h() - 8) / 2, over ? Theme.TEXT : Theme.TEXT_SECONDARY);
            }
            case PREVIEW -> drawPreview(g, x, y, w, c.h(), accent);
        }
    }

    /**
     * The theme preview: a small window in the current theme - its title bar, a module row that is on and one that is
     * off, a switch, a slider, a text field and two buttons (one lit) - so every theme change shows at once.
     */
    private void drawPreview(GuiGraphicsExtractor g, int x, int y, int w, int h, int accent) {
        // A "game" behind it (sky, grass, a bright spot), so how see-through the menu is shows in the preview
        Render2D.roundRect(g, x, y, w, h, Theme.R_ROW, 0xFF2A4A6E);
        Render2D.roundRectBottom(g, x, y + h / 2, w, h - h / 2, Theme.R_ROW, 0xFF3C6B2E);
        Render2D.smoothCircle(g, x + w - 22, y + 18, 9f, 0xFFF2D46B, 1f);
        int px = x + 8;
        int py = y + 8;
        int pw = w - 16;
        int ph = h - 16;
        Render2D.shadow(g, px, py, pw, ph, Theme.R_SURFACE);
        Render2D.roundRect(g, px, py, pw, ph, Theme.R_SURFACE, Theme.withAlpha(Theme.WINDOW_BODY, Theme.windowAlpha()));
        Render2D.roundRectTop(g, px, py, pw, 16, Theme.R_SURFACE, 0x08FFFFFF);
        g.fill(px + 1, py + 15, px + pw - 1, py + 16, Theme.HAIRLINE);
        Render2D.roundOutline(g, px, py, pw, ph, Theme.R_SURFACE, Theme.BORDER);
        CrispText.text(g, font, Component.literal("Preview").withStyle(net.minecraft.ChatFormatting.BOLD), px + 8, py + 4, Theme.TEXT);
        Render2D.icon(g, "close", px + pw - 12, py + 5, 6, Theme.TEXT_DIM);
        // A module that is on, and one that is off
        int ry = py + 21;
        int rw = pw - 12;
        Render2D.roundRect(g, px + 6, ry, rw, 14, Theme.R_ROW, Theme.withAlpha(accent, 0.18f));
        Render2D.roundRect(g, px + 8, ry + 4, 2, 6, 1, accent);
        CrispText.text(g, font, "Fullbright", px + 14, ry + 3, Theme.TEXT);
        Render2D.toggleSwitch(g, px + 6 + rw - 20, ry + 3, 1f, accent);
        ry += 16;
        CrispText.text(g, font, "Zoom", px + 14, ry + 3, Theme.TEXT_DIM);
        Render2D.toggleSwitch(g, px + 6 + rw - 20, ry + 3, 0f, accent);
        ry += 17;
        // A slider and a text field
        Render2D.slider(g, px + 10, ry + 2, rw / 2 - 12, 0.6f, accent, false);
        int fx = px + 6 + rw / 2 + 2;
        int fw = rw / 2 - 2;
        Render2D.roundRect(g, fx, ry - 4, fw, 12, Theme.R_CONTROL, 0x33000000);
        Render2D.roundOutline(g, fx, ry - 4, fw, 12, Theme.R_CONTROL, Theme.HAIRLINE);
        CrispText.text(g, font, "Search…", fx + 4, ry - 2, Theme.TEXT_FAINT);
        ry += 14;
        // Two buttons: a lit one and a quiet one
        int bw = (rw - 4) / 2;
        Render2D.roundRect(g, px + 6, ry, bw, 13, Theme.R_CONTROL, Theme.withAlpha(accent, 0.20f));
        Render2D.roundOutline(g, px + 6, ry, bw, 13, Theme.R_CONTROL, Theme.withAlpha(accent, 0.8f));
        CrispText.centered(g, font, "On", px + 6 + bw / 2, ry + 3, Theme.mix(accent, 0xFFFFFFFF, 0.45f));
        Render2D.roundRect(g, px + 10 + bw, ry, bw, 13, Theme.R_CONTROL, 0x0FFFFFFF);
        Render2D.roundOutline(g, px + 10 + bw, ry, bw, 13, Theme.R_CONTROL, Theme.BORDER);
        CrispText.centered(g, font, "Cancel", px + 10 + bw + bw / 2, ry + 3, Theme.TEXT_SECONDARY);
    }

    // ------------------------------------------------------------------ hit testing

    private boolean overPanel(double guiX, double guiY) {
        float ps = panelScale();
        double mx = guiX / ps;
        double my = guiY / ps;
        int[] r = panelRect;
        if (r[2] > 0 && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) return true;
        for (Control c : barButtons) if (c.contains(mx, my)) return true;
        return false;
    }

    /** The topmost element under the mouse (HUD pixels). */
    private static HudWindow windowAt(double hx, double hy) {
        List<HudElement> all = OverlayRenderer.elements();
        for (int i = all.size() - 1; i >= 0; i--) {
            HudWindow w = all.get(i).window();
            if (w.contains(hx, hy) || w.onGrip(hx, hy)) return w;
        }
        return null;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double gx = event.x();
        double gy = event.y();
        float ps = panelScale();
        double mx = gx / ps;
        double my = gy / ps;
        if (event.button() == 0) {
            for (Control c : barButtons) {
                if (c.contains(mx, my)) {
                    c.action().run();
                    return true;
                }
            }
        }
        int[] r = panelRect;
        if (r[2] > 0 && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
            if (event.button() == 0) {
                for (Control c : new ArrayList<>(controls)) {
                    if (!c.contains(mx, my)) continue;
                    panelClick(c, mx);
                    return true;
                }
            }
            return true;
        }
        float hs = hudScale();
        double hx = gx / hs;
        double hy = gy / hs;
        HudWindow w = windowAt(hx, hy);
        if (w == null) {
            // A click on empty space closes the element's panel
            if (event.button() == 0 && !themeTab) selected = null;
            return true;
        }
        if (event.button() == 1) {
            w.resetPlacement();
            save();
            return true;
        }
        if (event.button() != 0) return true;
        if (w.onGrip(hx, hy)) {
            resizing = w;
        } else {
            moving = w;
            grabX = (int) Math.round(hx) - w.getX();
            grabY = (int) Math.round(hy) - w.getY();
        }
        pressX = gx;
        pressY = gy;
        moved = false;
        return true;
    }

    private void panelClick(Control c, double mx) {
        switch (c.kind()) {
            case TOGGLE -> {
                @SuppressWarnings("unchecked")
                Setting<Boolean> s = (Setting<Boolean>) c.setting();
                s.setValue(!s.getValue());
                save();
            }
            case SLIDER -> {
                dragSlider = c.setting();
                sliderX = c.x();
                sliderW = c.w();
                dragSlider.setFraction((mx - sliderX) / sliderW);
            }
            case COLOR -> ColorPickerScreen.open(this, (ColorSetting) c.setting(), this::save);
            case BUTTON -> {
                if (c.action() != null) c.action().run();
            }
            default -> {
            }
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double gx = event.x();
        double gy = event.y();
        if (dragSlider != null) {
            dragSlider.setFraction((gx / panelScale() - sliderX) / sliderW);
            return true;
        }
        if (moving == null && resizing == null) return true;
        if (!moved && Math.abs(gx - pressX) + Math.abs(gy - pressY) < 3) return true;
        moved = true;
        float hs = hudScale();
        double hx = gx / hs;
        double hy = gy / hs;
        if (resizing != null) {
            resizing.resizeTo(hx, hy);
            return true;
        }
        int nx = (int) Math.round(hx) - grabX;
        int ny = (int) Math.round(hy) - grabY;
        int vw = Math.round(width / hs);
        int vh = Math.round(height / hs);
        nx = Math.max(0, Math.min(nx, vw - moving.getW()));
        ny = Math.max(0, Math.min(ny, vh - moving.getH()));
        int[] p = shiftDown() ? new int[]{nx, ny} : snap(moving, nx, ny, vw, vh);
        if (shiftDown()) guides.clear();
        moving.moveTo(p[0], p[1]);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragSlider != null) {
            dragSlider = null;
            save();
            return true;
        }
        if (moving != null || resizing != null) {
            HudWindow w = moving != null ? moving : resizing;
            // A click without dragging selects the element (its look opens in the side panel)
            if (!moved) {
                selected = w;
                themeTab = false;
                panelScroll = 0;
            } else {
                save();
            }
        }
        moving = null;
        resizing = null;
        guides.clear();
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (overPanel(x, y)) {
            panelScroll = Math.max(0, panelScroll - (int) Math.signum(scrollY) * ROW * 2);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Arrow keys nudge the selected element a pixel at a time
        HudWindow w = selected;
        if (w != null && w.isVisible()) {
            int dx = event.key() == InputConstants.KEY_LEFT ? -1 : event.key() == InputConstants.KEY_RIGHT ? 1 : 0;
            int dy = event.key() == InputConstants.KEY_UP ? -1 : event.key() == InputConstants.KEY_DOWN ? 1 : 0;
            if (dx != 0 || dy != 0) {
                w.moveTo(w.getX() + dx, w.getY() + dy);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private boolean shiftDown() {
        var win = minecraft.getWindow();
        return InputConstants.isKeyDown(win, InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(win, InputConstants.KEY_RSHIFT);
    }

    // ------------------------------------------------------------------ snapping

    /**
     * Snaps a dragged element to the screen edges (a small margin in), the screen's centre lines and the edges /
     * centres of the other elements, when it comes within a few pixels. Fills {@link #guides} with the lines it
     * snapped to.
     */
    private int[] snap(HudWindow w, int nx, int ny, int vw, int vh) {
        guides.clear();
        int ww = w.getW();
        int wh = w.getH();
        List<int[]> others = new ArrayList<>();
        for (HudElement e : OverlayRenderer.elements()) {
            HudWindow o = e.window();
            if (o == w || !o.isVisible()) continue;
            others.add(new int[]{o.getX(), o.getY(), o.getW(), o.getH()});
        }
        // Candidates: {position for the element's left / top, where the guide line is}
        List<int[]> xs = new ArrayList<>();
        List<int[]> ys = new ArrayList<>();
        xs.add(new int[]{2, 2});
        xs.add(new int[]{vw - 2 - ww, vw - 2});
        xs.add(new int[]{vw / 2 - ww / 2, vw / 2});
        ys.add(new int[]{2, 2});
        ys.add(new int[]{vh - 2 - wh, vh - 2});
        ys.add(new int[]{vh / 2 - wh / 2, vh / 2});
        for (int[] o : others) {
            xs.add(new int[]{o[0], o[0]});
            xs.add(new int[]{o[0] + o[2] - ww, o[0] + o[2]});
            xs.add(new int[]{o[0] + o[2] + 4, o[0] + o[2] + 2});
            xs.add(new int[]{o[0] - ww - 4, o[0] - 2});
            xs.add(new int[]{o[0] + o[2] / 2 - ww / 2, o[0] + o[2] / 2});
            ys.add(new int[]{o[1], o[1]});
            ys.add(new int[]{o[1] + o[3] - wh, o[1] + o[3]});
            ys.add(new int[]{o[1] + o[3] + 4, o[1] + o[3] + 2});
            ys.add(new int[]{o[1] - wh - 4, o[1] - 2});
            ys.add(new int[]{o[1] + o[3] / 2 - wh / 2, o[1] + o[3] / 2});
        }
        int[] bx = closest(xs, nx);
        int[] by = closest(ys, ny);
        int x = bx != null ? bx[0] : nx;
        int y = by != null ? by[0] : ny;
        if (bx != null) guides.add(new int[]{bx[1], 0, bx[1] + 1, vh});
        if (by != null) guides.add(new int[]{0, by[1], vw, by[1] + 1});
        return new int[]{Math.max(0, x), Math.max(0, y)};
    }

    private static int[] closest(List<int[]> candidates, int value) {
        int[] best = null;
        int bestD = SNAP + 1;
        for (int[] c : candidates) {
            int d = Math.abs(c[0] - value);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ closing

    private void save() {
        ConfigManager.save(ConfigManager.DEFAULT);
    }

    @Override
    public void onClose() {
        save();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
