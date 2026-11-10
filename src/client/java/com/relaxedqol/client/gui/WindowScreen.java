package com.relaxedqol.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A see-through window on top of whatever screen was open (a chest stays open behind it, and closing the window
 * goes back to it). Drag the title bar to move, drag any edge or corner to resize (it stops at the smallest
 * size where no button, text box or kept text area overlaps another or gets cut off); size/position are
 * remembered per window type for the session. Subclasses draw inside {@link #contentX()}.. etc.
 *
 * Compact windows ({@link #compact}): the whole window - text included - is drawn a step smaller than
 * the game's GUI scale (e.g. 3 screen pixels per font pixel instead of 4), always a whole number of screen pixels
 * so letters stay crisp. Everything inside works in these smaller "window pixels": the screen's width / height and
 * all mouse positions are converted, so subclasses and their text boxes need no changes - except that a subclass
 * overriding mouseScrolled must pass its x / y through {@link #vx} / {@link #vy}.
 */
public abstract class WindowScreen extends Screen {
    private static final Map<String, int[]> BOUNDS = new HashMap<>();
    protected static final int TITLE_H = 20;
    private static final int GRIP = 10;
    private static final int MIN_W = 180;
    private static final int MIN_H = 110;

    protected final Screen parent;
    private final String key;
    private final int defaultW;
    private final int defaultH;
    protected int wx;
    protected int wy;
    protected int ww;
    protected int wh;
    private boolean moving;
    private boolean resizingWindow;
    private int grabX;
    private int grabY;
    /**
     * Which edges a resize drags (any edge or corner, not only the bottom-right grip). The opposite edge stays
     * where it is, and a size that would make buttons / boxes / text overlap or get cut off is never taken.
     */
    private boolean dragL;
    private boolean dragR;
    private boolean dragT;
    private boolean dragB;
    /** How many things overlap / are cut off at the window's current size (0 = all fits). */
    private int layoutProblems;
    /** Where the mouse grabbed the edge, measured from the edge (so the edge doesn't jump to the mouse). */
    private int grabEdgeX;
    private int grabEdgeY;
    /** Areas a subclass keeps for its own text (set in initContent), checked like buttons. */
    private final List<int[]> reserved = new ArrayList<>();
    private static final int EDGE = 4;

    /** Small rounded buttons ("pills") added in initContent - the client's own look instead of big vanilla buttons. */
    protected record Pill(int x, int y, int w, int h, Supplier<String> label, Runnable action, int color, BooleanSupplier active) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Pill> pills = new ArrayList<>();
    /** The button just clicked (drawn pressed for a moment). */
    private Pill pressedPill;
    private long pressedAt;
    /** Hover glow per pill (0..1, eased each frame) and when the window opened (pop-in). */
    private final java.util.IdentityHashMap<Pill, Float> pillGlow = new java.util.IdentityHashMap<>();
    private long openedAt;
    private long lastFrameNs;

    /** Window pixels -> GUI units (1 = normal size; below 1 for compact windows). */
    private float vs = 1f;
    /** The real GUI size of the screen, and the window-pixel size we turned it into. */
    private int realW;
    private int realH;
    private int virtW = -1;
    private int virtH = -1;

    /** True: drawn a step smaller (smaller text), like a desktop app. */
    protected boolean compact() {
        return false;
    }

    private float computeScale() {
        if (!compact() || minecraft == null) return 1f;
        int gs = Math.max(1, (int) minecraft.getWindow().getGuiScale());
        if (gs <= 1) return 1f;
        com.relaxedqol.client.module.modules.client.ClickGUI gui = com.relaxedqol.client.RelaxedQolClient.moduleManager == null ? null
            : com.relaxedqol.client.RelaxedQolClient.moduleManager.getModule(com.relaxedqol.client.module.modules.client.ClickGUI.class);
        double percent = gui != null ? gui.getSizePercent() : 100;
        double wanted = gs * 0.75 * percent / 100.0;
        if (gui != null && !gui.crispText()) return (float) Math.max(0.5, Math.min(1.0, wanted / gs));
        int pixels = (int) Math.max(2, Math.min(gs, Math.round(wanted)));
        return (float) pixels / gs;
    }

    /** A mouse x / y from the game (GUI units) in window pixels. */
    protected double vx(double guiX) {
        return guiX / vs;
    }

    protected double vy(double guiY) {
        return guiY / vs;
    }

    private MouseButtonEvent virt(MouseButtonEvent e) {
        return vs == 1f ? e : new MouseButtonEvent(e.x() / vs, e.y() / vs, e.buttonInfo());
    }

    /** A small button (12 px high). */
    protected void pill(String label, int x, int y, int w, Runnable action) {
        pills.add(new Pill(x, y, w, 12, () -> label, action, 0, () -> false));
    }

    /** A small button whose label / highlight can change; color 0 = accent. */
    protected void pill(Supplier<String> label, int x, int y, int w, int h, Runnable action, int color, BooleanSupplier active) {
        pills.add(new Pill(x, y, w, h, label, action, color, active));
    }

    /** The width a pill needs for its text (so labels like "Commands" are never cut off). */
    protected int pillWidth(String label) {
        return font.width(label) + 14;
    }

    /** Lays out n equal pills across the content width at y. */
    protected void pillRow(int y, String[] labels, Runnable[] actions) {
        int n = labels.length;
        int gap = 4;
        int w = (contentW() - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) pill(labels[i], contentX() + i * (w + gap), y, w, actions[i]);
    }

    private void drawPills(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int accent = Theme.accent();
        long nowNs = System.nanoTime();
        float dt = lastFrameNs == 0 ? 1f : Math.min(1f, (nowNs - lastFrameNs) / 1_000_000_000f);
        lastFrameNs = nowNs;
        pillGlow.keySet().retainAll(new java.util.HashSet<>(pills));
        for (Pill p : pills) {
            int color = p.color() == 0 ? accent : p.color();
            boolean hover = p.contains(mouseX, mouseY);
            boolean active = p.active().getAsBoolean();
            // Hover fades in / out over ~120 ms instead of snapping
            float glow = pillGlow.getOrDefault(p, 0f);
            float sp = Theme.ANIM <= 0.01f ? 1000f : Theme.ANIM;
            glow = hover ? Math.min(1f, glow + dt * 9f * sp) : Math.max(0f, glow - dt * 6f * sp);
            pillGlow.put(p, glow);
            String label = p.label().get();
            String shown = label.length() > 2 ? Theme.ellipsize(label, p.w() - 8, font::width) : label;
            boolean pressed = p == pressedPill && System.currentTimeMillis() - pressedAt < 140;
            if (VanillaStyle.on()) {
                // Minecraft's own button, yellow text while it is "on"
                VanillaStyle.button(g, font, shown, p.x(), p.y(), p.w(), p.h(), hover, active, true);
                continue;
            }
            if (!LoverFx.on()) {
                // Clean look (LoverFella Mode keeps its own capsule buttons): a calm button with a hairline edge; "on" =
                // a soft accent fill, accent edge and light accent text. one control radius, the neutral text
                // greys, and a short darker flash when clicked so a click is felt.
                int r = Math.min(p.h() / 2, Theme.R_CONTROL);
                int bg = active ? Theme.withAlpha(color, 0.16f + 0.08f * glow) : Theme.mix(0x0FFFFFFF, 0x1CFFFFFF, glow);
                if (pressed) bg = Theme.mix(bg, 0x30000000, 0.6f);
                int edge = active ? Theme.withAlpha(color, 0.70f + 0.25f * glow) : Theme.mix(Theme.BORDER, 0x4DFFFFFF, glow);
                Render2D.roundRect(g, p.x(), p.y(), p.w(), p.h(), r, bg);
                Render2D.roundOutline(g, p.x(), p.y(), p.w(), p.h(), r, edge);
                int text = active ? Theme.mix(color, 0xFFFFFFFF, 0.45f) : Theme.mix(Theme.TEXT_SECONDARY, Theme.TEXT, glow);
                g.centeredText(font, shown, p.x() + p.w() / 2, p.y() + (p.h() - 8) / 2 + (pressed ? 1 : 0), text);
                continue;
            }
            // Soft buttons: a soft filled capsule; "on" = a calm tint with an edge in the accent (not a solid glowing bar)
            int r = Math.min(p.h() / 2, 7);
            int bg = active ? Theme.withAlpha(color, 0.22f + 0.08f * glow) : Theme.mix(0x1CFFFFFF, 0x30FFFFFF, glow);
            Render2D.roundRect(g, p.x(), p.y(), p.w(), p.h(), r, bg);
            if (active) Render2D.roundOutline(g, p.x(), p.y(), p.w(), p.h(), r, Theme.withAlpha(color, 0.75f));
            else if (p.w() > 2 * r + 2) g.fill(p.x() + r, p.y(), p.x() + p.w() - r, p.y() + 1, 0x12FFFFFF);
            g.centeredText(font, shown, p.x() + p.w() / 2, p.y() + (p.h() - 8) / 2,
                active ? Theme.mix(color, 0xFFFFFFFF, 0.55f) : Theme.mix(0xFFC9C9D1, 0xFFFFFFFF, glow));
        }
    }

    protected WindowScreen(Screen parent, Component title, String key, int defaultW, int defaultH) {
        super(title);
        this.parent = parent;
        this.key = key;
        this.defaultW = defaultW;
        this.defaultH = defaultH;
    }

    @Override
    protected void init() {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            initWindow();
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private void initWindow() {
        pills.clear();
        reserved.clear();
        // A fresh size from the game (opening, resizing) - not our own rebuild with the window-pixel size
        if (width != virtW || height != virtH) {
            realW = width;
            realH = height;
        }
        vs = computeScale();
        virtW = Math.max(1, Math.round(realW / vs));
        virtH = Math.max(1, Math.round(realH / vs));
        width = virtW;
        height = virtH;
        if (parent != null && (parent.width != realW || parent.height != realH)) parent.init(realW, realH);
        int[] b = BOUNDS.get(key);
        if (b != null && !rememberPosition() && !opened) {
            // Same size as last time, but always opened at its default spot (e.g. the middle of the screen)
            int[] spot = defaultSpot(Math.min(b[2], width - 10), Math.min(b[3], height - 10));
            b = new int[]{spot[0], spot[1], b[2], b[3]};
        }
        if (!opened) openedAt = System.currentTimeMillis();
        opened = true;
        if (b == null) {
            ww = Math.min(defaultW, width - 10);
            wh = Math.min(defaultH, height - 10);
            int[] spot = defaultSpot(ww, wh);
            wx = spot[0];
            wy = spot[1];
        } else {
            wx = b[0];
            wy = b[1];
            ww = b[2];
            wh = b[3];
        }
        clampWindow();
        initContent();
        appleFields();
        // The free room between top and bottom controls the window opened with is always allowed (up to the normal need)
        if (gapNeed < 0) gapNeed = Math.max(0, Math.min(minMiddleGap(), middleGap(layoutRects())));
        layoutProblems = countProblems();
    }

    /** Free height needed between the top and the bottom controls (-1 = not measured yet). */
    private int gapNeed = -1;

    // ------------------------------------------------------------------ sizing without overlaps

    /**
     * Keeps an area for text the window draws itself (a label next to buttons, a status line, column headers...), so
     * the window can never be made so small that buttons or other kept areas run into it. Call it in initContent.
     */
    protected void reserve(int x, int y, int w, int h) {
        if (w > 0 && h > 0) reserved.add(new int[]{x, y, w, h});
    }

    /** The smallest the window may get (window pixels). By default 70% of its normal size. */
    protected int minWidth() {
        return Math.max(MIN_W, defaultW * 7 / 10);
    }

    protected int minHeight() {
        return Math.max(MIN_H, defaultH * 7 / 10);
    }

    /** Free height a window needs between its top controls and its bottom controls (for its list / text). */
    protected int minMiddleGap() {
        return 28;
    }

    /**
     * How many things don't fit in the window at its current size: buttons / text boxes / kept text areas on top of
     * each other, sticking out of the window, buttons too narrow for their label, and no room left between the
     * controls at the top and those at the bottom. 0 = everything fits.
     */
    private int countProblems() {
        int problems = 0;
        for (Pill p : pills) {
            String label = p.label().get();
            if (label != null && label.length() > 2) {
                // Long changing labels (a picked item's name...) may be shortened, but never below ~80 px of text
                int need = Math.min(font.width(label), 80);
                if (need > p.w() - 8) problems++;
            }
        }
        List<int[]> rects = layoutRects();
        int top = wy + TITLE_H - 2;
        for (int[] r : rects) {
            if (r[2] <= 0 || r[3] <= 0) problems++;
            else if (r[0] < wx || r[0] + r[2] > wx + ww || r[1] < top || r[1] + r[3] > wy + wh) problems++;
        }
        for (int i = 0; i < rects.size(); i++) {
            int[] a = rects.get(i);
            for (int j = i + 1; j < rects.size(); j++) {
                int[] b = rects.get(j);
                boolean overlap = a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
                if (overlap) problems++;
            }
        }
        if (middleGap(rects) < Math.max(0, gapNeed)) problems++;
        return problems;
    }

    /** Every button, text box and kept text area of the window. */
    private List<int[]> layoutRects() {
        List<int[]> rects = new ArrayList<>();
        for (Pill p : pills) rects.add(new int[]{p.x(), p.y(), p.w(), p.h()});
        for (var child : children()) {
            if (!(child instanceof net.minecraft.client.gui.components.AbstractWidget aw) || !aw.visible) continue;
            int[] f = child instanceof net.minecraft.client.gui.components.EditBox box ? fields.get(box) : null;
            rects.add(f != null ? f.clone() : new int[]{aw.getX(), aw.getY(), aw.getWidth(), aw.getHeight()});
        }
        rects.addAll(reserved);
        return rects;
    }

    /** Free height between the controls in the top half and those in the bottom half (MAX_VALUE = only one group). */
    private int middleGap(List<int[]> rects) {
        int mid = contentY() + contentH() / 2;
        int topBottom = Integer.MIN_VALUE;
        int bottomTop = Integer.MAX_VALUE;
        for (int[] r : rects) {
            if (r[1] + r[3] / 2 < mid) topBottom = Math.max(topBottom, r[1] + r[3]);
            else bottomTop = Math.min(bottomTop, r[1]);
        }
        if (topBottom == Integer.MIN_VALUE || bottomTop == Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return bottomTop - topBottom;
    }

    /** Lays the window out at a size and says how many things don't fit there. */
    private int tryLayout(int x, int y, int w, int h) {
        wx = x;
        wy = y;
        ww = w;
        wh = h;
        clampWindow();
        rebuildWidgets();
        return layoutProblems;
    }

    /**
     * Resize towards a wanted size. Growing always works; shrinking stops at the smallest size where nothing overlaps
     * (found per direction, so you can still make it narrower while it is as short as it can get, and the other way
     * round). A window that already has something not fitting (a screen too small for it) can't get any worse.
     */
    private void resizeTo(int wantW, int wantH) {
        int right = wx + ww;
        int bottom = wy + wh;
        int allowed = layoutProblems;
        int w = ww;
        int h = wh;
        // Width first (with the height it has now), then the height with that width
        if (wantW != w) w = fit(true, w, wantW, h, right, bottom, allowed);
        if (wantH != h) h = fit(false, h, wantH, w, right, bottom, allowed);
        tryLayout(dragL ? right - w : wx, dragT ? bottom - h : wy, w, h);
    }

    private int fit(boolean horizontal, int good, int want, int other, int right, int bottom, int allowed) {
        int lo = horizontal ? Math.min(width - 4, minWidth()) : Math.min(height - 4, minHeight());
        want = Math.max(Math.max(lo, horizontal ? MIN_W : MIN_H), want);
        if (want >= good) return want;
        if (layoutAt(horizontal, want, other, right, bottom) <= allowed) return want;
        // Smallest size that still fits, between the wanted (too small) and the current (fits) size
        int bad = want;
        int ok = good;
        while (ok - bad > 1) {
            int m = (ok + bad) >>> 1;
            if (layoutAt(horizontal, m, other, right, bottom) <= allowed) ok = m;
            else bad = m;
        }
        return ok;
    }

    private int layoutAt(boolean horizontal, int size, int other, int right, int bottom) {
        int w = horizontal ? size : other;
        int h = horizontal ? other : size;
        return tryLayout(dragL ? right - w : wx, dragT ? bottom - h : wy, w, h);
    }

    /** Which edges of the window the mouse is on (for resizing): {left, right, top, bottom}. */
    private boolean[] edgesAt(double mx, double my) {
        boolean inX = mx >= wx - EDGE && mx < wx + ww + EDGE;
        boolean inY = my >= wy - EDGE && my < wy + wh + EDGE;
        if (!inX || !inY) return new boolean[4];
        // Windows that pass clicks outside them to the chest behind only resize from just inside their edge
        if (passThroughToParent() && containerBehind() != null && !overWindow(mx, my)) return new boolean[4];
        boolean l = mx < wx + 2;
        boolean r = mx >= wx + ww - 2;
        boolean t = my < wy + 2;
        boolean b = my >= wy + wh - 2;
        // The grip in the corner counts as the bottom-right corner
        if (onGrip(mx, my)) {
            r = true;
            b = true;
        }
        return new boolean[]{l, r, t, b};
    }

    private static boolean any(boolean[] e) {
        return e[0] || e[1] || e[2] || e[3];
    }

    /** Text boxes as soft rounded fields (box -> {x, y, w, h} of the field around it). */
    private final java.util.IdentityHashMap<net.minecraft.client.gui.components.EditBox, int[]> fields = new java.util.IdentityHashMap<>();

    /** The game's black boxes with a grey border become rounded fields with the text inside, like a Mac app. */
    private void appleFields() {
        fields.clear();
        // Vanilla Style: text boxes keep Minecraft's own look
        if (VanillaStyle.on()) return;
        for (var child : children()) {
            if (!(child instanceof net.minecraft.client.gui.components.EditBox box) || box.getHeight() < 12) continue;
            int x = box.getX();
            int y = box.getY();
            int w = box.getWidth();
            int h = box.getHeight();
            try {
                box.setBordered(false);
                box.setX(x + 5);
                box.setY(y + (h - 8) / 2);
                box.setWidth(Math.max(10, w - 10));
                box.setHeight(9);
                fields.put(box, new int[]{x, y, w, h});
            } catch (RuntimeException | LinkageError e) {
                // keep the game's own box
            }
        }
    }

    private void drawFields(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int accent = Theme.accent();
        for (var e : fields.entrySet()) {
            var box = e.getKey();
            int[] r = e.getValue();
            if (!box.visible) continue;
            boolean focused = box.isFocused();
            boolean hover = mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3];
            boolean lover = LoverFx.on();
            int rad = lover ? 5 : 4;
            if (focused && lover) Render2D.roundRect(g, r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2, 6, Theme.withAlpha(accent, 0.35f));
            Render2D.roundRect(g, r[0], r[1], r[2], r[3], rad, hover && !focused ? 0x40000000 : 0x33000000);
            Render2D.roundOutline(g, r[0], r[1], r[2], r[3], rad, focused ? Theme.withAlpha(accent, 0.9f)
                : lover ? 0x22FFFFFF : hover ? 0x45FFFFFF : 0x2EFFFFFF);
        }
    }

    /** A click on a field's padding still puts the cursor in it. */
    private boolean fieldClicked(double mx, double my) {
        for (var e : fields.entrySet()) {
            int[] r = e.getValue();
            var box = e.getKey();
            if (!box.visible || mx < r[0] || mx >= r[0] + r[2] || my < r[1] || my >= r[1] + r[3]) continue;
            if (mx >= box.getX() && mx < box.getX() + box.getWidth() && my >= box.getY() && my < box.getY() + box.getHeight()) return false;
            setFocused(box);
            return true;
        }
        return false;
    }

    /** Set after the first init: later inits (resizing the game window, rebuilding widgets) keep the spot. */
    private boolean opened;

    /** False: the window opens at its default spot every time instead of where it was last left. */
    protected boolean rememberPosition() {
        return true;
    }

    /** Where a window opens the first time (centred by default). */
    protected int[] defaultSpot(int w, int h) {
        return new int[]{(width - w) / 2, (height - h) / 2};
    }

    /** How solid the window background is (0..1). */
    protected float windowAlpha() {
        return Math.max(0.6f, Theme.opacity() + 0.25f);
    }

    /** Whether the game behind the window is darkened a little. */
    protected boolean dimBehind() {
        return true;
    }

    /** Add widgets etc. Window bounds are known here. Called again after a resize. */
    protected void initContent() {}

    private void clampWindow() {
        ww = Math.max(Math.min(width - 4, minWidth()), Math.min(ww, width - 4));
        wh = Math.max(Math.min(height - 4, minHeight()), Math.min(wh, height - 4));
        wx = Math.max(0, Math.min(wx, width - ww));
        wy = Math.max(0, Math.min(wy, height - wh));
        BOUNDS.put(key, new int[]{wx, wy, ww, wh});
    }

    protected int contentX() { return wx + 6; }
    protected int contentY() { return wy + TITLE_H + 4; }
    protected int contentW() { return ww - 12; }
    protected int contentH() { return wh - TITLE_H - 8; }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // An open chest/inventory stays visible behind the window (those never blur, so this is safe). also
        // when this window was opened from another window that sits on the chest (Macros -> Macro Editor), and for
        // windows that let you keep using the chest (passThroughToParent) it gets the real mouse, so slots light up
        // and item tooltips show while you work in the window.
        Screen behind = containerBehind();
        if (behind != null) {
            boolean live = passThroughToParent() && !overWindow(mouseX / vs, mouseY / vs);
            behind.extractRenderStateWithTooltipAndSubtitles(g, live ? mouseX : -1000, live ? mouseY : -1000, partialTick);
            g.nextStratum();
        }
        if (dimBehind()) {
            float in = Render2D.easeOut((System.currentTimeMillis() - openedAt) / 220f);
            g.fill(0, 0, Math.max(realW, width), Math.max(realH, height), Theme.withAlpha(0xFF000000, 0.11f * in));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int guiMouseX, int guiMouseY, float partialTick) {
        // Everything in the window - frame, content, text boxes - in the smooth font
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            renderWindow(g, guiMouseX, guiMouseY, partialTick);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private void renderWindow(GuiGraphicsExtractor g, int guiMouseX, int guiMouseY, float partialTick) {
        int mouseX = (int) Math.floor(vx(guiMouseX));
        int mouseY = (int) Math.floor(vy(guiMouseY));
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        boolean scaled = vs != 1f;
        if (scaled) {
            g.pose().pushMatrix();
            g.pose().scale(vs, vs);
        }
        int accent = Theme.accent();
        float alpha = windowAlpha();
        // A short settle when the window opens: it rises 4 px into place, eased. no more growing from 96% -
        // scaling made the crisp text and hairlines shimmer for the first frames.
        float popMs = Theme.animMs(160f);
        float pop = popMs <= 0 ? 1f : Render2D.easeOut((System.currentTimeMillis() - openedAt) / popMs);
        boolean popping = pop < 1f;
        if (popping) {
            g.pose().pushMatrix();
            g.pose().translate(0f, Math.round((1f - pop) * 4f));
        }
        // Window frame: a deep soft shadow, one calm translucent body, a hairline edge, the three
        // window lights on the left and the title centred in a smooth font
        if (alpha >= 0.6f) {
            Render2D.shadow(g, wx - 3, wy + 2, ww + 6, wh + 4, 14);
            Render2D.shadow(g, wx, wy, ww, wh, 10);
        }
        boolean lover = LoverFx.on();
        boolean vanilla = VanillaStyle.on();
        if (vanilla) {
            VanillaStyle.window(g, wx, wy, ww, wh, TITLE_H);
        } else if (lover) {
            int body = bodyColor();
            Render2D.roundRect(g, wx, wy, ww, wh, 10, body);
            // A whisper of light at the top, like frosted glass
            Render2D.roundRectTop(g, wx, wy, ww, TITLE_H, 10, 0x08FFFFFF);
            // A calm hairline under the title (no red / cyan line)
            g.fill(wx + 10, wy + TITLE_H - 1, wx + ww - 10, wy + TITLE_H, 0x18FFFFFF);
            Render2D.roundOutline(g, wx, wy, ww, wh, 10, 0x2EFFFFFF);
            Render2D.roundOutline(g, wx + 1, wy + 1, ww - 2, wh - 2, 9, 0x0AFFFFFF);
        } else {
            // Clean look: one neutral graphite body, a slightly lighter title strip, a single hairline under it
            // and a crisp hairline border - no gradients, no glow
            int body = bodyColor();
            Render2D.roundRect(g, wx, wy, ww, wh, Theme.R_SURFACE, body);
            Render2D.roundRectTop(g, wx, wy, ww, TITLE_H, Theme.R_SURFACE, 0x08FFFFFF);
            // Clean Style: the glass shine on the top part (Style Editor > Glass Shine)
            if (CleanStyle.on() && Theme.SHEEN > 0.01f) {
                Render2D.roundRectTop(g, wx + 1, wy + 1, ww - 2, Math.max(TITLE_H, wh / 3), Math.max(0, Theme.R_SURFACE - 1),
                    Theme.withAlpha(0xFFFFFFFF, Math.min(0.14f, 0.05f * Theme.SHEEN)));
            }
            g.fill(wx + 1, wy + TITLE_H - 1, wx + ww - 1, wy + TITLE_H, Theme.HAIRLINE);
            Render2D.roundOutline(g, wx, wy, ww, wh, Theme.R_SURFACE, Theme.BORDER);
        }
        // A normal close button (an X) in the top-right corner
        int cs = 12;
        int cxb = wx + ww - cs - 6;
        int cyb = wy + (TITLE_H - cs) / 2;
        boolean overClose = mouseX >= cxb - 2 && mouseX < cxb + cs + 2 && mouseY >= cyb - 2 && mouseY < cyb + cs + 2;
        if (vanilla) {
            VanillaStyle.button(g, font, "x", cxb, cyb, cs, cs, overClose, false, true);
        } else {
            if (overClose) Render2D.roundRect(g, cxb, cyb, cs, cs, Theme.R_CONTROL,
                CleanStyle.on() ? 0x33FFFFFF : Theme.withAlpha(Theme.DANGER, 0.9f));
            Render2D.icon(g, "close", cxb + 3, cyb + 3, 6, overClose ? 0xFFFFFFFF : Theme.TEXT_DIM);
        }
        Component bold = getTitle().copy().withStyle(net.minecraft.ChatFormatting.BOLD);
        String extra = titleInfo();
        int titleW = vanilla ? font.width(getTitle()) : font.width(bold);
        int textY = wy + (TITLE_H - 8) / 2;
        int infoW = 0;
        String shown = null;
        if (extra != null && !extra.isEmpty()) {
            int room = ww - 50 - titleW - 20;
            shown = Theme.ellipsize(extra, Math.max(0, room), font::width);
            infoW = shown.isEmpty() ? 0 : font.width(shown) + 12;
            if (shown.isEmpty()) shown = null;
        }
        int titleX;
        if (vanilla) {
            // Centred, like the titles of Minecraft's screens
            titleX = Math.max(wx + 8, wx + (ww - titleW) / 2);
            if (infoW > 0) titleX = Math.min(titleX, wx + ww - 26 - infoW - 8 - titleW);
            titleX = Math.max(titleX, wx + 8);
        } else if (lover) {
            // Centred like a Mac window; slides left when the info on the right needs the room
            titleX = wx + (ww - titleW) / 2;
            if (infoW > 0) titleX = Math.min(titleX, wx + ww - 26 - infoW - 8 - titleW);
            titleX = Math.max(titleX, wx + 10);
        } else {
            // The title on the left, like a tool window (no chevron in front - it looked clickable but did nothing)
            titleX = wx + 10;
        }
        if (vanilla) g.text(font, getTitle(), titleX, textY, VanillaStyle.TEXT, true);
        else g.text(font, bold, titleX, textY, Theme.TEXT, false);
        if (shown != null) {
            int px = wx + ww - 24 - infoW;
            if (vanilla) {
                // plain grey text, no badge
            } else if (lover) {
                Render2D.roundRect(g, px, wy + (TITLE_H - 12) / 2, infoW, 12, 6, 0x14FFFFFF);
            } else {
                Render2D.roundRect(g, px, wy + (TITLE_H - 12) / 2, infoW, 12, Theme.R_CONTROL, Theme.PILL);
                Render2D.roundOutline(g, px, wy + (TITLE_H - 12) / 2, infoW, 12, Theme.R_CONTROL, Theme.HAIRLINE);
            }
            g.text(font, shown, px + 6, textY, Theme.TEXT_DIM, false);
        }

        drawContent(g, mouseX, mouseY, partialTick);
        drawFields(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        drawPills(g, mouseX, mouseY);
        drawOverlay(g, mouseX, mouseY);

        // Resize grip, and a thin line on the edge you can drag (lit while the mouse is on it / dragging it)
        boolean[] e = resizingWindow ? new boolean[]{dragL, dragR, dragT, dragB}
            : moving || overCloseButton(mouseX, mouseY) ? new boolean[4] : edgesAt(mouseX, mouseY);
        boolean overGrip = (e[1] && e[3]) || onGrip(mouseX, mouseY) && !moving;
        Render2D.resizeGrip(g, wx + ww - 7, wy + wh - 7, overGrip || resizingWindow ? 0xB0FFFFFF : 0x38FFFFFF);
        if (any(e)) {
            int edgeColor = Theme.withAlpha(accent, resizingWindow ? 0.9f : 0.6f);
            if (e[0]) g.fill(wx, wy + 6, wx + 1, wy + wh - 6, edgeColor);
            if (e[1]) g.fill(wx + ww - 1, wy + 6, wx + ww, wy + wh - 6, edgeColor);
            if (e[2]) g.fill(wx + 6, wy, wx + ww - 6, wy + 1, edgeColor);
            if (e[3]) g.fill(wx + 6, wy + wh - 1, wx + ww - 6, wy + wh, edgeColor);
        }
        if (popping) g.pose().popMatrix();
        if (scaled) g.pose().popMatrix();
    }

    protected String titleInfo() {
        return null;
    }

    /** The window's body colour (subclasses use it to round off the corners of pictures drawn inside). */
    protected int bodyColor() {
        float alpha = windowAlpha();
        // LoverFella Mode's navy, as see-through as the style's own windows
        if (LoverFx.on() && (VanillaStyle.on() || CleanStyle.on())) {
            int style = VanillaStyle.on() ? Theme.PANEL : CleanStyle.window();
            return Theme.withAlpha(0xFF141A2E, ((style >>> 24) & 0xFF) / 255f);
        }
        if (VanillaStyle.on()) return Theme.PANEL;
        if (CleanStyle.on()) return CleanStyle.window();
        return LoverFx.on() ? Theme.withAlpha(0xFF141A2E, Math.max(0.86f, alpha)) : Theme.withAlpha(Theme.WINDOW_BODY, Theme.windowAlpha());
    }

    /** Drawn on top of the content, widgets and buttons (drop-down lists). */
    protected void drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {}

    /** A click while something is drawn by drawOverlay; true = used (nothing else gets it). */
    protected boolean overlayClicked(double mx, double my, int button) {
        return false;
    }

    protected abstract void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick);

    /** Clicks inside the content area (widgets get theirs first). Return true when used. */
    protected boolean contentClicked(double mx, double my, int button) {
        return false;
    }

    protected boolean contentDragged(double mx, double my) {
        return false;
    }

    protected void contentReleased() {}

    /** The X in the top-right corner. */
    private boolean overCloseButton(double mx, double my) {
        int cs = 12;
        int cxb = wx + ww - cs - 6;
        int cyb = wy + (TITLE_H - cs) / 2;
        return mx >= cxb - 2 && mx < cxb + cs + 2 && my >= cyb - 2 && my < cyb + cs + 2;
    }

    private boolean onGrip(double mx, double my) {
        return mx >= wx + ww - GRIP && mx < wx + ww + 2 && my >= wy + wh - GRIP && my < wy + wh + 2;
    }

    // ------------------------------------------------------------------ using the chest behind the window

    /**
     * True: clicks outside this window go to the chest / inventory behind it (it stays open and usable), e.g. edit a
     * macro while the auction house is open next to it.
     */
    protected boolean passThroughToParent() {
        return false;
    }

    /** The chest / inventory screen this window (or the window it was opened from) sits on, or null. */
    protected Screen containerBehind() {
        Screen s = parent;
        for (int i = 0; i < 6 && s != null; i++) {
            if (s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) return s;
            if (s instanceof WindowScreen w) s = w.parent;
            else return null;
        }
        return null;
    }

    /** Whether a point in window pixels is on this window. */
    protected boolean overWindow(double mx, double my) {
        return mx >= wx && mx < wx + ww && my >= wy && my < wy + wh;
    }

    /** Where the mouse was last frame (window pixels). */
    private int lastMouseX = -1000;
    private int lastMouseY = -1000;

    /** A mouse drag that started on the chest behind: the rest of it (and the release) goes there too. */
    private boolean forwarding;

    private boolean canForward(double mx, double my) {
        return passThroughToParent() && containerBehind() != null && !overWindow(mx, my)
            && minecraft != null && minecraft.player != null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent guiEvent, boolean doubleClick) {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return clickWindow(guiEvent, doubleClick);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private boolean clickWindow(MouseButtonEvent guiEvent, boolean doubleClick) {
        MouseButtonEvent event = virt(guiEvent);
        double mx = event.x();
        double my = event.y();
        // An open drop-down list (drawn on top of everything) gets the click first
        if (overlayClicked(mx, my, event.button())) return true;
        if (canForward(mx, my)) {
            forwarding = true;
            return containerBehind().mouseClicked(guiEvent, doubleClick);
        }
        if (event.button() == 0) {
            for (Pill p : new ArrayList<>(pills)) {
                if (p.contains(mx, my)) {
                    pressedPill = p;
                    pressedAt = System.currentTimeMillis();
                    p.action().run();
                    return true;
                }
            }
            if (overCloseButton(mx, my)) {
                onClose();
                return true;
            }
            boolean[] edges = edgesAt(mx, my);
            if (any(edges)) {
                // Any edge or corner resizes (the X button and title bar buttons come first)
                resizingWindow = true;
                dragL = edges[0];
                dragR = edges[1];
                dragT = edges[2];
                dragB = edges[3];
                grabEdgeX = dragL ? (int) mx - wx : dragR ? wx + ww - (int) mx : 0;
                grabEdgeY = dragT ? (int) my - wy : dragB ? wy + wh - (int) my : 0;
                return true;
            }
            if (mx >= wx && mx < wx + ww && my >= wy && my < wy + TITLE_H) {
                moving = true;
                grabX = (int) mx - wx;
                grabY = (int) my - wy;
                return true;
            }
        }
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() == 0 && fieldClicked(mx, my)) return true;
        if (mx >= wx && mx < wx + ww && my >= wy && my < wy + wh) {
            contentClicked(mx, my, event.button());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent guiEvent, double guiDx, double guiDy) {
        if (forwarding && containerBehind() != null) return containerBehind().mouseDragged(guiEvent, guiDx, guiDy);
        MouseButtonEvent event = virt(guiEvent);
        double dx = guiDx / vs;
        double dy = guiDy / vs;
        if (moving) {
            wx = (int) event.x() - grabX;
            wy = (int) event.y() - grabY;
            clampWindow();
            rebuildWidgets();
            return true;
        }
        if (resizingWindow) {
            int mx = (int) event.x();
            int my = (int) event.y();
            int wantW = dragR ? mx + grabEdgeX - wx : dragL ? wx + ww - (mx - grabEdgeX) : ww;
            int wantH = dragB ? my + grabEdgeY - wy : dragT ? wy + wh - (my - grabEdgeY) : wh;
            if (wantW != ww || wantH != wh) resizeTo(wantW, wantH);
            return true;
        }
        if (contentDragged(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (forwarding) {
            forwarding = false;
            Screen behind = containerBehind();
            if (behind != null) return behind.mouseReleased(event);
        }
        moving = false;
        resizingWindow = false;
        dragL = dragR = dragT = dragB = false;
        contentReleased();
        return super.mouseReleased(virt(event));
    }

    @Override
    public void mouseMoved(double x, double y) {
        super.mouseMoved(vx(x), vy(y));
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return super.charTyped(event);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return keyWindow(event);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private boolean keyWindow(KeyEvent event) {
        // Hotbar keys (1-9), drop (Q) and off hand (F) over a slot of the chest behind still work there
        if (passThroughToParent() && containerBehind() != null && !(getFocused() instanceof net.minecraft.client.gui.components.EditBox)
            && minecraft != null) {
            int key = event.key();
            boolean slotKey = (key >= 49 && key <= 57) || key == 81 || key == 70;
            if (slotKey && !overWindow(lastMouseX, lastMouseY)) return containerBehind().keyPressed(event);
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * A vertical scroll bar whose thumb can be grabbed with the mouse (and clicking the track jumps there).
     * Positions are in rows or pixels, whatever the caller uses consistently.
     */
    public static final class ScrollBar {
        private int x;
        private int y;
        private int h;
        private int total;
        private int visible;
        public int scroll;
        private boolean dragging;
        private int grabOffset;

        /** Call every frame before drawing. */
        public void update(int x, int y, int h, int total, int visible) {
            this.x = x;
            this.y = y;
            this.h = h;
            this.total = total;
            this.visible = visible;
            scroll = Math.max(0, Math.min(maxScroll(), scroll));
        }

        public int maxScroll() {
            return Math.max(0, total - visible);
        }

        private int thumbHeight() {
            if (total <= visible) return h;
            return Math.max(14, (int) ((long) h * visible / total));
        }

        private int thumbY() {
            int max = maxScroll();
            return max == 0 ? y : y + (int) ((long) (h - thumbHeight()) * scroll / max);
        }

        public void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            if (total <= visible) return;
            // A slim overlay scroller like macOS - it gets wider and brighter under the mouse
            boolean hot = dragging || (mouseX >= x - 3 && mouseX < x + 7 && mouseY >= y && mouseY < y + h);
            if (hot) Render2D.roundRect(g, x, y, 4, h, 2, 0x0EFFFFFF);
            int tw = hot ? 4 : 3;
            Render2D.roundRect(g, x + (4 - tw), thumbY(), tw, thumbHeight(), 2, hot ? 0x8CFFFFFF : 0x4DFFFFFF);
        }

        public boolean mouseClicked(double mx, double my) {
            if (total <= visible || mx < x - 3 || mx >= x + 7 || my < y || my >= y + h) return false;
            int ty = thumbY();
            if (my >= ty && my < ty + thumbHeight()) {
                grabOffset = (int) my - ty;
            } else {
                grabOffset = thumbHeight() / 2;
                dragTo(my);
            }
            dragging = true;
            return true;
        }

        public boolean mouseDragged(double my) {
            if (!dragging) return false;
            dragTo(my);
            return true;
        }

        private void dragTo(double my) {
            int range = h - thumbHeight();
            if (range <= 0) return;
            double f = (my - grabOffset - y) / range;
            scroll = (int) Math.round(Math.max(0, Math.min(1, f)) * maxScroll());
        }

        public void release() {
            dragging = false;
        }

        public void scrollBy(int amount) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + amount));
        }
    }
}
