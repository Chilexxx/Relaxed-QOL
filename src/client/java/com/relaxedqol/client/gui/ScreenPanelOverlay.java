package com.relaxedqol.client.gui;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.modules.client.ClickGUI;
import com.relaxedqol.client.module.modules.misc.QuickPanel;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The Quick Panel: a compact panel with your 4 command buttons, drawn on top of chest / inventory screens (and the
 * menu). It also shows which slot the mouse is over. Left click runs a button, right click (or clicking an empty one)
 * sets its name and command.
 * Title bar: chevron folds it, drag to move, the gear opens the client menu. Corner: drag to resize.
 * Drawn in ClickGUI-scaled ("virtual") coordinates.
 */
public final class ScreenPanelOverlay {
    private static final int PAD = 6;
    private static final int GAP = 4;


    // Sizes that follow the text size (set by measure() every frame / input event)
    private static float textBoost = 1.15f;
    private static float effective = 1.15f;
    private static int HEADER_H = 20;
    private static int CELL_H = 20;

    private static boolean collapsed;
    private static boolean dragging;
    private static boolean resizing;
    /** What the panel looked like when the resize started (the size follows the mouse from there, smoothly). */
    private static float resizeLeftGui;
    private static float resizeTopGui;
    private static float resizeWidthGui;
    private static double resizeMouseX;
    private static int resizePercent;
    /** The panel follows the mouse (moved / resized) - it only steps aside from HUD cards when it is let go. */
    private static boolean pointerMoved;
    private static int dragOffsetX;
    private static int dragOffsetY;
    /** "Join a world to use this" shows under the panel until this time. */
    private static long offlineHintUntil;

    private static final Anim ANIM = new Anim();
    /** The screen the panel is on right now (its cards are drawn there instead of in the HUD). */
    private static net.minecraft.client.gui.screens.Screen attached;

    /** One button of the panel, with its rectangle. index -1 = none. */
    private record Cell(int index, int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private ScreenPanelOverlay() {}

    /** The title in the panel's header. */
    private static String title() {
        return "QUICK PANEL";
    }

    // ------------------------------------------------------------------ helpers

    private static QuickPanel settings() {
        return RelaxedQolClient.moduleManager == null ? null : RelaxedQolClient.enabled(QuickPanel.class);
    }

    /** Recomputes every size from the text size, so bigger text always gets room. */
    private static void measure() {
        if (RelaxedQolClient.moduleManager == null) return;
        QuickPanel panel = RelaxedQolClient.moduleManager.getModule(QuickPanel.class);
        float t = panel != null ? panel.getTextPercent() / 100f : 1.15f;
        textBoost = Math.max(1f, Math.min(2f, t));
        // How big the text really ends up (it is snapped to whole screen pixels), so the rows fit it exactly
        float scale = scale();
        // While resizing, the sizes follow the scale exactly (no snapping steps), so the panel grows smoothly
        float e = scale > 0 && !resizing ? CrispText.snap(scale * textBoost, 0f) / scale : textBoost;
        effective = Math.max(1f, Math.min(2.2f, e));
        int th = (int) Math.ceil(9 * effective);
        HEADER_H = th + 9;
        CELL_H = th + 9;
        SLOT_H = th + 5;
    }

    /** Menu scale (ClickGUI Scale %) times the panel's own size (drag its corner). */
    private static float scale() {
        Minecraft mc = Minecraft.getInstance();
        ClickGUI gui = RelaxedQolClient.moduleManager.getModule(ClickGUI.class);
        QuickPanel panel = RelaxedQolClient.moduleManager.getModule(QuickPanel.class);
        float base = Theme.layoutScale(mc.getWindow().getGuiScale(), gui != null ? gui.getSizePercent() : 100);
        return base * (panel != null ? panel.getScalePercent() / 100f : 1f);
    }

    /** Width of a text at the panel's text size, in panel pixels (no drawing state needed). */
    private static int textW(Font font, String text) {
        return (int) Math.ceil(font.width(text) * effective);
    }

    private static String shownLabel(QuickPanel panel, int i) {
        return panel.isSet(i) ? panel.label(i) : "+ Set";
    }

    /** Width of one button: room for the longest label (clamped so the panel stays compact). */
    private static int cellWidth(Font font, QuickPanel panel) {
        int widest = textW(font, "+ Set");
        for (int i = 0; i < QuickPanel.BUTTONS; i++) widest = Math.max(widest, textW(font, shownLabel(panel, i)));
        // Every label fits (the panel grows with it, up to a sane maximum)
        return Math.max(Math.round(44 * effective), Math.min(Math.round(170 * effective), widest + 18));
    }

    private static int columns(QuickPanel panel) {
        return switch (panel.layoutMode()) {
            case "Row" -> QuickPanel.BUTTONS;
            case "Column" -> 1;
            default -> 2;
        };
    }

    /** Width of a bold text (bold adds a pixel per letter). */
    private static int boldW(Font font, String text) {
        return (int) Math.ceil((font.width(text) + text.length()) * effective);
    }

    private static int panelW(Font font, QuickPanel panel) {
        int cols = columns(panel);
        int cells = cols * cellWidth(font, panel) + (cols - 1) * GAP + PAD * 2;
        // The title bar always has room for the title (bold), so the gear never touches the last letter
        int titleW = boldW(font, "QUICK PANEL");
        int title = 17 + titleW + 10 + (HEADER_H - 8) + 8;
        return Math.max(cells, title);
    }

    /** The panel's width in its own (unscaled) pixels, for the menu's panel arrangement. */
    public static int panelWidth() {
        if (beforeMenu != null) return 0; // not shown in the client menu, so it takes no room there
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return panelWidth$smooth();
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private static int panelWidth$smooth() {
        QuickPanel panel = RelaxedQolClient.moduleManager == null ? null
            : RelaxedQolClient.moduleManager.getModule(QuickPanel.class);
        if (panel == null) return 0;
        measure();
        return panelW(Minecraft.getInstance().font, panel);
    }

    /** Default spot: just under the HUD's watermark / info panel, so it never covers it. */
    private static int defaultY() {
        com.relaxedqol.client.module.modules.render.HUD hud =
            RelaxedQolClient.enabled(com.relaxedqol.client.module.modules.render.HUD.class);
        if (hud == null || hud.leftColumnBottom() <= 0) return 6;
        float hudBottomGui = hud.leftColumnBottom() * OverlayRenderer.hudScale();
        return Math.round((hudBottomGui + 8) / scale());
    }

    private static int[] origin(QuickPanel s, int w, int vw, int vh) {
        int x = s.isPlaced() ? s.getX() : 6;
        int y = s.isPlaced() ? s.getY() : defaultY();
        // In the menu (Right Shift) the panel waits folded up on the left, out of the way
        if (beforeMenu != null && !movedInMenu) {
            x = 6;
            y = defaultY();
        } else if (!s.isPlaced() && attached instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> cs && leftBusy()) {
            // HUD cards already on the left: the panel sits right next to the inventory instead
            var acc = (com.relaxedqol.mixin.AbstractContainerScreenAccessor) cs;
            float sc = scale();
            int left = Math.round(acc.relaxed$getLeftPos() / sc);
            int top = Math.round(acc.relaxed$getTopPos() / sc);
            if (left - w - 6 >= 2) {
                x = left - w - 6;
                y = top;
            }
        }
        x = Math.max(0, Math.min(x, vw - w));
        y = Math.max(0, Math.min(y, vh - HEADER_H));
        // In an inventory / chest the panel never sits on top of your HUD cards (Notes, Keystrokes...): it
        // moves next to them, towards the middle of the screen, so you can see and use all of them
        if (beforeMenu == null && !pointerMoved && attached != null) return avoidCards(s, x, y, w, vw, vh);
        return new int[]{x, y};
    }

    /** {x, y, w, h} of every HUD card on screen, in panel pixels. */
    private static List<int[]> cardRects() {
        List<int[]> out = new ArrayList<>();
        float k = OverlayRenderer.hudScale() / scale();
        for (HudWindow wnd : OverlayRenderer.visibleWindows()) {
            if (!wnd.isVisible() || wnd.getW() <= 0 || wnd.getH() <= 0) continue;
            int cx = (int) Math.floor(wnd.getX() * k);
            int cy = (int) Math.floor(wnd.getY() * k);
            out.add(new int[]{cx, cy, (int) Math.ceil((wnd.getX() + wnd.getW()) * k) - cx, (int) Math.ceil((wnd.getY() + wnd.getH()) * k) - cy});
        }
        return out;
    }

    private static boolean overlaps(int x, int y, int w, int h, int[] r) {
        int m = 3;
        return x < r[0] + r[2] + m && x + w > r[0] - m && y < r[1] + r[3] + m && y + h > r[1] - m;
    }

    /** The card the rectangle overlaps that reaches furthest right, or null when it is free. */
    private static int[] hit(int x, int y, int w, int h, List<int[]> cards) {
        int[] best = null;
        for (int[] r : cards) {
            if (overlaps(x, y, w, h, r) && (best == null || r[0] + r[2] > best[0] + best[2])) best = r;
        }
        return best;
    }

    private static int[] avoidCards(QuickPanel panel, int x, int y, int w, int vw, int vh) {
        List<int[]> cards = cardRects();
        if (cards.isEmpty()) return new int[]{x, y};
        int h = heightOf(layout(Minecraft.getInstance().font, panel, 0, 0), 0);
        if (hit(x, y, w, h, cards) == null) return new int[]{x, y};
        // The inventory itself (the panel must not cover it either)
        int[] inv = null;
        if (attached instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> cs) {
            var acc = (com.relaxedqol.mixin.AbstractContainerScreenAccessor) cs;
            float sc = scale();
            int left = Math.round(acc.relaxed$getLeftPos() / sc);
            int top = Math.round(acc.relaxed$getTopPos() / sc);
            int iw = Math.round(acc.relaxed$getImageWidth() / sc);
            int ih = Math.max(20, Math.round((cs.height - 2 * acc.relaxed$getTopPos()) / sc));
            inv = new int[]{left, top, iw, ih};
        }
        // 1: slide sideways (towards the middle) past the cards it would cover, at the same height
        boolean rightSide = x + w / 2 > vw / 2;
        int cx = x;
        for (int i = 0; i < 10; i++) {
            int[] r = hit(cx, y, w, h, cards);
            if (r == null) break;
            cx = rightSide ? r[0] - w - 6 : r[0] + r[2] + 6;
        }
        boolean fits = cx >= 0 && cx + w <= vw && hit(cx, y, w, h, cards) == null;
        if (fits && (inv == null || !overlaps(cx, y, w, h, inv))) return new int[]{cx, y};
        // 2: right next to the inventory (on the side the panel is on), at the same height or level with its top
        if (inv != null) {
            int ix = rightSide ? inv[0] + inv[2] + 6 : inv[0] - w - 6;
            for (int ty : new int[]{y, inv[1], Math.max(0, vh - h - 4)}) {
                int yy = Math.max(0, Math.min(ty, vh - h));
                if (ix >= 0 && ix + w <= vw && hit(ix, yy, w, h, cards) == null) return new int[]{ix, yy};
            }
        }
        // 3: no free spot - as far towards the middle as the screen allows
        return new int[]{Math.max(0, Math.min(cx, vw - w)), y};
    }

    // ------------------------------------------------------------------ where the panel was drawn

    private static int[] lastRectGui;
    private static long lastDrawnAt;
    private static boolean lastCollapsed;

    /**
     * Whether the open (unfolded) panel was just drawn over this rectangle (GUI pixels). The Notes card hides
     * itself then, instead of showing through the panel.
     */
    public static boolean coversGui(float gx, float gy, float gw, float gh) {
        int[] r = lastRectGui;
        if (r == null || lastCollapsed || System.currentTimeMillis() - lastDrawnAt > 150) return false;
        return gx < r[0] + r[2] && gx + gw > r[0] && gy < r[1] + r[3] && gy + gh > r[1];
    }

    // ------------------------------------------------------------------ layout

    private static List<Cell> layout(Font font, QuickPanel panel, int x, int y) {
        List<Cell> cells = new ArrayList<>();
        if (collapsed) return cells;
        int cols = columns(panel);
        int w = panelW(font, panel);
        int cw = (w - PAD * 2 - (cols - 1) * GAP) / cols;
        int top = y + HEADER_H + PAD;
        for (int i = 0; i < QuickPanel.BUTTONS; i++) {
            int col = i % cols;
            int row = i / cols;
            cells.add(new Cell(i, x + PAD + col * (cw + GAP), top + row * (CELL_H + GAP), cw, CELL_H));
        }
        return cells;
    }

    private static int heightOf(List<Cell> cells, int y) {
        if (cells.isEmpty()) return HEADER_H;
        Cell last = cells.get(cells.size() - 1);
        int h = last.y() + last.h() + PAD - y;
        if (slotLine() != null) h += SLOT_H;
        return h;
    }

    /** Height of the "Slot 12 · Diamond Sword" line at the bottom. */
    private static int SLOT_H = 14;

    /** What the hovered-slot line says, or null when no container is open. */
    private static String slotLine() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen)) return null;
        net.minecraft.world.inventory.Slot slot = ((com.relaxedqol.mixin.AbstractContainerScreenAccessor) screen).relaxed$getHoveredSlot();
        if (slot != null) lastSlot = slot;
        net.minecraft.world.inventory.Slot shown = lastSlot;
        if (shown == null || !screen.getMenu().slots.contains(shown)) return "-";
        return String.valueOf(screen.getMenu().slots.indexOf(shown));
    }

    private static net.minecraft.world.inventory.Slot lastSlot;

    /** {x, y} of the gear button in the title bar (its size is HEADER_H - 8). */
    private static int[] gear(int x, int y, int w) {
        int size = HEADER_H - 8;
        return new int[]{x + w - 6 - size, y + 4, size};
    }

    // ------------------------------------------------------------------ drawing

    /** When an item tooltip was last drawn (the game draws it before the panel). */
    public static volatile long tooltipAtNs;

    private static java.lang.reflect.Method tooltipLines;
    private static boolean tooltipLinesLooked;
    private static Object tipStack;
    private static int[] tipSize;

    /**
     * Where the hovered slot's item tooltip is drawn {x, y, w, h} (GUI pixels), worked out the way the game
     * places it: right of the mouse, flipped to the left when it would leave the screen, pushed up from the bottom.
     * Null when no slot item is hovered or its lines can not be read.
     */
    private static int[] tooltipRect(Minecraft mc, Font font, int mouseX, int mouseY, int sw, int sh) {
        if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> cs)) return null;
        net.minecraft.world.inventory.Slot slot;
        try {
            slot = ((com.relaxedqol.mixin.AbstractContainerScreenAccessor) cs).relaxed$getHoveredSlot();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        if (slot == null || !slot.hasItem()) return null;
        net.minecraft.world.item.ItemStack stack = slot.getItem();
        if (stack != tipStack || tipSize == null) {
            tipStack = stack;
            tipSize = null;
            if (!tooltipLinesLooked) {
                tooltipLinesLooked = true;
                for (java.lang.reflect.Method m : net.minecraft.client.gui.screens.Screen.class.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && p.length == 2 && p[0] == Minecraft.class
                        && p[1] == net.minecraft.world.item.ItemStack.class && List.class.isAssignableFrom(m.getReturnType())) {
                        tooltipLines = m;
                        break;
                    }
                }
            }
            if (tooltipLines == null) return null;
            try {
                List<?> lines = (List<?>) tooltipLines.invoke(null, mc, stack);
                int w = 0;
                for (Object l : lines) if (l instanceof Component c) w = Math.max(w, font.width(c));
                int n = Math.max(1, lines.size());
                int h = n == 1 ? 8 : 10 + (n - 1) * 10;
                tipSize = new int[]{w, h};
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        int w = tipSize[0] + 8;
        int h = tipSize[1] + 8;
        int x = mouseX + 12 - 4;
        if (x + w > sw - 2) x = Math.max(0, mouseX - 24 - tipSize[0] - 4);
        int y = mouseY - 12 - 4;
        if (y + h > sh - 2) y = sh - h - 2;
        y = Math.max(0, y);
        return new int[]{x, y, w, h};
    }

    public static void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (com.relaxedqol.client.util.Privacy.hidden() || beforeMenu != null) return; // never in the client menu
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            render$smooth(g, mouseX, mouseY);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private static void render$smooth(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        QuickPanel panel = settings();
        if (panel == null) return;
        measure();
        ANIM.tick();
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        float scale = scale();
        int vw = Math.round(g.guiWidth() / scale);
        int vh = Math.round(g.guiHeight() / scale);
        int mx = Math.round(mouseX / scale);
        int my = Math.round(mouseY / scale);
        int accent = Theme.accent();

        int w = panelW(font, panel);
        int[] o = origin(panel, w, vw, vh);
        int x = o[0];
        int y = o[1];
        List<Cell> cells = layout(font, panel, x, y);
        int h = heightOf(cells, y);

        lastRectGui = new int[]{Math.round(x * scale), Math.round(y * scale), Math.round(w * scale), Math.round(h * scale)};
        lastDrawnAt = System.currentTimeMillis();
        lastCollapsed = collapsed;
        // An item tooltip is showing (and the mouse is not on the panel): the panel steps aside for this frame,
        // so it never covers the tooltip
        int[] lr = lastRectGui;
        boolean mouseOnPanel = mouseX >= lr[0] && mouseX < lr[0] + lr[2] && mouseY >= lr[1] && mouseY < lr[1] + lr[3];
        // Only when that tooltip really lands on the panel (it was hidden for every tooltip anywhere)
        if (!dragging && !resizing && !mouseOnPanel && System.nanoTime() - tooltipAtNs < 60_000_000L) {
            int[] tip = tooltipRect(mc, font, mouseX, mouseY, g.guiWidth(), g.guiHeight());
            if (tip != null && tip[0] < lr[0] + lr[2] && tip[0] + tip[2] > lr[0] && tip[1] < lr[1] + lr[3] && tip[1] + tip[3] > lr[1]) return;
        }

        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        // While resizing, the top-left corner stays exactly where it was (the saved position is in whole panel
        // pixels, which made the panel wobble by a pixel or two at every step) and the text is not snapped
        boolean wasFree = CrispText.free;
        if (resizing) {
            g.pose().translate(resizeLeftGui / scale - x, resizeTopGui / scale - y);
            CrispText.free = true;
        }
        float prevText = CrispText.begin(scale, textBoost);

        Render2D.shadow(g, x, y, w, h, Theme.R_SURFACE);
        Render2D.roundRect(g, x, y, w, h, Theme.R_SURFACE, Theme.panel());
        if (collapsed) Render2D.roundRect(g, x, y, w, h, Theme.R_SURFACE, Theme.header());
        else Render2D.roundRectTop(g, x, y, w, HEADER_H, Theme.R_SURFACE, Theme.header());
        Render2D.headerSheen(g, x, y, w, HEADER_H, Theme.R_SURFACE, accent, collapsed);
        int textY = y + HEADER_H / 2 - 4;
        Render2D.chevron(g, x + 7, y + HEADER_H / 2 - 3, !collapsed, Theme.TEXT_FAINT);
        CrispText.text(g, font, Component.literal(title()).withStyle(ChatFormatting.BOLD), x + 17, textY, Theme.TEXT_DIM);
        int[] gr = gear(x, y, w);
        boolean overGear = mx >= gr[0] - 2 && mx < gr[0] + gr[2] + 2 && my >= gr[1] - 2 && my < gr[1] + gr[2] + 2;
        if (overGear) Render2D.roundRect(g, gr[0] - 2, gr[1] - 2, gr[2] + 4, gr[2] + 4, Theme.R_CONTROL, Theme.withAlpha(accent, 0.30f));
        Render2D.icon(g, "gear", gr[0], gr[1], gr[2], overGear ? Theme.TEXT : Theme.TEXT_DIM);
        if (!collapsed) Render2D.headerLine(g, x, y + HEADER_H - 1, w, accent);

        Cell hovered = null;
        for (Cell cell : cells) {
            boolean hover = cell.contains(mx, my) && !dragging && !resizing;
            if (hover) hovered = cell;
            drawCell(g, font, panel, cell, hover, accent);
        }

        // The slot the mouse is over, as "SlotID: 41" (the number macros and plugins use)
        String slot = collapsed ? null : slotLine();
        if (slot != null) {
            int sy = y + h - SLOT_H - 2;
            // A quiet label / value line ("Slot ID" left, the number right) instead of a filled box
            g.fill(x + PAD, sy - 1, x + w - PAD, sy, Theme.HAIRLINE);
            int ty = sy + (SLOT_H - 2) / 2 - 4;
            CrispText.text(g, font, "Slot ID", x + PAD + 2, ty, Theme.TEXT_FAINT);
            CrispText.right(g, font, slot.equals("-") ? "\u2013" : slot, x + w - PAD - 2, ty, slot.equals("-") ? Theme.TEXT_FAINT : Theme.TEXT);
        }
        Render2D.roundOutline(g, x, y, w, h, Theme.R_SURFACE, Theme.BORDER);
        boolean overGrip = mx >= x + w - 9 && mx < x + w + 2 && my >= y + h - 9 && my < y + h + 2;
        Render2D.resizeGrip(g, x + w - 6, y + h - 6, resizing || overGrip ? 0xB0FFFFFF : 0x38FFFFFF);

        // Under the panel: what the hovered button runs (or how to set it up), or the "join a world" hint
        String hint = null;
        int hintColor = Theme.TEXT_DIM;
        if (mc.player == null) {
            boolean warn = System.currentTimeMillis() < offlineHintUntil;
            hint = warn ? "Join a world to use this" : "Preview - join a world to use the buttons";
            hintColor = warn ? Theme.DANGER : Theme.TEXT_DIM;
        } else if (hovered != null) {
            int i = hovered.index();
            hint = panel.isSet(i) ? panel.command(i) + "\nRight-click to edit" : "Click to set this button";
        }
        if (hint != null) drawHint(g, font, hint, x, y + h + 4, w, hintColor);

        CrispText.end(prevText);
        CrispText.free = wasFree;
        g.pose().popMatrix();
    }

    private static void drawCell(GuiGraphicsExtractor g, Font font, QuickPanel panel, Cell c, boolean hover, int accent) {
        boolean set = panel.isSet(c.index());
        float hov = ANIM.smooth("qp:" + c.index(), hover ? 1 : 0, 28);
        // Your buttons keep the accent colour - a soft accent fill with a matching edge that both get
        // stronger under the mouse (tidier than the old full-strength glow). An empty slot is a faint outline "+ Set".
        int fill = set ? Theme.withAlpha(accent, 0.18f + 0.14f * hov) : Theme.mix(0x00FFFFFF, 0x14FFFFFF, hov);
        Render2D.roundRect(g, c.x(), c.y(), c.w(), c.h(), Theme.R_CONTROL, fill);
        int outline = set ? Theme.withAlpha(accent, 0.45f + 0.40f * hov) : Theme.mix(0x1CFFFFFF, 0x40FFFFFF, hov);
        Render2D.roundOutline(g, c.x(), c.y(), c.w(), c.h(), Theme.R_CONTROL, outline);
        String label = fit(font, shownLabel(panel, c.index()), c.w() - 8);
        int color = set ? Theme.mix(Theme.mix(Theme.TEXT, accent, 0.15f), 0xFFFFFFFF, hov) : Theme.mix(Theme.TEXT_FAINT, Theme.TEXT_DIM, hov);
        CrispText.centered(g, font, label, c.x() + c.w() / 2, c.y() + c.h() / 2 - 4, color);
    }

    /**
     * The hint under the panel is never cut off - long text wraps onto a second (or third) line, as wide as
     * the panel. "\n" starts a new line (the second line is drawn fainter).
     */
    private static void drawHint(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w, int color) {
        int maxW = Math.max(w, 130) - 12;
        List<String> lines = new ArrayList<>();
        List<Boolean> faint = new ArrayList<>();
        String[] parts = text.split("\n");
        for (int pi = 0; pi < parts.length; pi++) {
            String rest = parts[pi];
            while (!rest.isEmpty() && lines.size() < 4) {
                String piece = rest;
                while (piece.length() > 1 && CrispText.width(font, piece) > maxW) {
                    int space = piece.lastIndexOf(' ', piece.length() - 2);
                    piece = space > 0 && CrispText.width(font, piece.substring(0, space)) > 0 ? piece.substring(0, space) : piece.substring(0, piece.length() - 1);
                }
                lines.add(piece);
                faint.add(pi > 0);
                rest = rest.substring(piece.length()).trim();
            }
        }
        int lineH = Math.round(9 * effective) + 2;
        int tw = 0;
        for (String l : lines) tw = Math.max(tw, CrispText.width(font, l));
        int pw = tw + 12;
        int ph = lines.size() * lineH + 6;
        int px = x + (w - pw) / 2;
        Render2D.popup(g, px, y, pw, ph);
        for (int i = 0; i < lines.size(); i++) {
            CrispText.text(g, font, lines.get(i), px + 6, y + 4 + i * lineH, faint.get(i) ? Theme.TEXT_FAINT : color);
        }
    }

    /** The text shortened with "…" so it fits {@code maxW} virtual pixels at the size it is really drawn. */
    private static String fit(Font font, String text, int maxW) {
        return Theme.ellipsize(text, maxW, t -> CrispText.width(font, t));
    }

    // ------------------------------------------------------------------ input

    /** Opens the window to set a button's name and command, on top of the current screen. */
    private static void edit(QuickPanel panel, int index) {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new QuickButtonScreen(mc.gui.screen(), panel, index));
    }

    /** Returns true when the click hit the panel (so the screen behind does not get it). */
    public static boolean mouseClicked(MouseButtonEvent event) {
        if (beforeMenu != null) return false; // not shown in the client menu
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return mouseClicked$smooth(event);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private static boolean mouseClicked$smooth(MouseButtonEvent event) {
        QuickPanel panel = settings();
        if (panel == null) return false;
        measure();
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        float scale = scale();
        double mx = event.x() / scale;
        double my = event.y() / scale;
        int vw = Math.round(mc.getWindow().getGuiScaledWidth() / scale);
        int vh = Math.round(mc.getWindow().getGuiScaledHeight() / scale);
        int w = panelW(font, panel);
        int[] o = origin(panel, w, vw, vh);
        int x = o[0];
        int y = o[1];

        if (mx >= x && mx < x + w && my >= y && my < y + HEADER_H) {
            int[] gr = gear(x, y, w);
            if (event.button() == 0 && mx >= gr[0] - 2 && mx < gr[0] + gr[2] + 2) {
                // The client menu (remembers this screen, so closing the menu comes back here)
                if (!(mc.gui.screen() instanceof ClickGuiScreen)) mc.gui.setScreen(ClickGuiScreen.create(mc.gui.screen()));
            } else if (mx < x + 15) {
                collapsed = !collapsed;
            } else if (event.button() == 0) {
                dragging = true;
                dragOffsetX = (int) mx - x;
                dragOffsetY = (int) my - y;
            }
            return true;
        }
        List<Cell> cells = layout(font, panel, x, y);
        int h = heightOf(cells, y);
        int bottom = y + h;
        if (event.button() == 0 && mx >= x + w - 9 && mx < x + w + 2 && my >= bottom - 9 && my < bottom + 2) {
            resizing = true;
            resizeLeftGui = x * scale;
            resizeTopGui = y * scale;
            resizeWidthGui = w * scale;
            resizeMouseX = event.x();
            resizePercent = panel.getScalePercent();
            return true;
        }
        if (mx < x || mx >= x + w || my < y || my >= bottom) return false;
        for (Cell cell : cells) {
            if (!cell.contains(mx, my)) continue;
            int i = cell.index();
            if (event.button() == 1 || (event.button() == 0 && !panel.isSet(i))) {
                edit(panel, i);
            } else if (event.button() == 0) {
                if (mc.player == null) offlineHintUntil = System.currentTimeMillis() + 2000;
                else {
                    // Close On Click: the screen closes first (a chest also tells the server), then the button runs
                    if (panel.closeOnUse() && mc.gui.screen() != null && !(mc.gui.screen() instanceof ClickGuiScreen)) {
                        mc.gui.screen().onClose();
                    }
                    panel.run(i);
                }
            }
            break;
        }
        return true;
    }

    public static boolean mouseDragged(MouseButtonEvent event) {
        if (beforeMenu != null) return false; // not shown in the client menu
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return mouseDragged$smooth(event);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private static boolean mouseDragged$smooth(MouseButtonEvent event) {
        QuickPanel s = settings();
        if (s == null) return false;
        measure();
        if (resizing || dragging) pointerMoved = true;
        if (resizing) {
            // Smooth - the size follows how far the mouse moved since the resize started, and the top-left
            // corner stays exactly where it was (it used to re-measure every step, so the panel jumped and crept)
            float wantedW = (float) (resizeWidthGui + (event.x() - resizeMouseX));
            int percent = Math.max(60, Math.min(200, Math.round(resizePercent * wantedW / Math.max(1f, resizeWidthGui))));
            if (percent != s.getScalePercent()) {
                s.setScalePercent(percent);
                float newScale = scale();
                s.markPlaced();
                s.setPosition(Math.round(resizeLeftGui / newScale), Math.round(resizeTopGui / newScale));
            }
            return true;
        }
        if (!dragging) return false;
        float scale = scale();
        if (beforeMenu != null) movedInMenu = true;
        s.markPlaced();
        s.setPosition((int) (event.x() / scale) - dragOffsetX, (int) (event.y() / scale) - dragOffsetY);
        return true;
    }

    public static boolean mouseReleased() {
        if (beforeMenu != null) return false; // not shown in the client menu
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return mouseReleased$smooth();
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private static boolean mouseReleased$smooth() {
        boolean was = dragging || resizing;
        dragging = false;
        resizing = false;
        pointerMoved = false;
        return was;
    }

    /** The compact panel has no text box: keys always go to the screen. */
    public static boolean keyPressed(KeyEvent event) {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return keyPressed$smooth(event);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private static boolean keyPressed$smooth(KeyEvent event) {
        return false;
    }

    public static boolean charTyped(CharacterEvent event) {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return charTyped$smooth(event);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private static boolean charTyped$smooth(CharacterEvent event) {
        return false;
    }

    public static boolean isTyping() {
        return false;
    }

    /** Whether HUD things (info panel, cards) use the left side of the screen. */
    private static boolean leftBusy() {
        com.relaxedqol.client.module.modules.render.HUD hud =
            RelaxedQolClient.enabled(com.relaxedqol.client.module.modules.render.HUD.class);
        if (hud != null && hud.leftColumnBottom() > 40) return true;
        Minecraft mc = Minecraft.getInstance();
        float hs = OverlayRenderer.hudScale();
        float third = mc.getWindow().getGuiScaledWidth() / 3f;
        for (HudWindow wnd : OverlayRenderer.visibleWindows()) {
            if (wnd.isVisible() && wnd.getX() * hs < third) return true;
        }
        return false;
    }

    /** The panel's fold state from before the menu opened (null = the menu is not open). */
    private static Boolean beforeMenu;
    private static boolean movedInMenu;

    /** The menu (Right Shift) opened: fold the panel up and keep it on the left. */
    public static void menuOpened() {
        if (beforeMenu != null) return;
        beforeMenu = collapsed;
        collapsed = true;
        movedInMenu = false;
    }

    /** The menu closed: the panel goes back to how it was. */
    public static void menuClosed() {
        if (beforeMenu == null) return;
        collapsed = beforeMenu;
        beforeMenu = null;
        movedInMenu = false;
    }

    public static void attach(net.minecraft.client.gui.screens.Screen screen) {
        attached = screen;
    }

    public static boolean isAttachedTo(net.minecraft.client.gui.screens.Screen screen) {
        return attached == screen;
    }

    /** Called when the screen closes. */
    public static void onScreenClosed() {
        attached = null;
        dragging = false;
        resizing = false;
        pointerMoved = false;
    }
}
