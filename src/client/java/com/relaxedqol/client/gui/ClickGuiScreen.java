package com.relaxedqol.client.gui;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.ConfigManager;
import com.relaxedqol.client.module.ActionModule;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.module.modules.client.ClickGUI;
import com.relaxedqol.client.setting.ActionSetting;
import com.relaxedqol.client.setting.ListSetting;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The module menu. Everything is laid out in "virtual" pixels and scaled with the ClickGUI "Scale %" setting,
 * so it looks the same whatever the game's GUI scale is.
 *
 * Module: left click toggles, right click opens settings, middle click binds a key.
 * Header: drag to move, right click to collapse. Type anywhere to search.
 */
public class ClickGuiScreen extends Screen {
    private static final int PANEL_W = 152; // A little wider, so setting names and values fit side by side
    private static final int MIN_W = 110;
    private static final int MAX_W = 320;
    private static final int GRIP = 8;
    /** Per panel: {width, max body height (0 = fit the screen)}. Changed by dragging a panel's bottom-right corner. */
    private static final Map<ModuleCategory, int[]> SIZES = new EnumMap<>(ModuleCategory.class);
    private static final int HEADER_H = 20;
    private static final int MODULE_H = 18;
    /** Room kept free on the right of a scrolling panel for its scroll bar. */
    private static final int SCROLL_LANE = 8;
    private static final int ROW_H = 14;
    private static final int NUMBER_H = 22;
    private static final int ACTION_H = 18;
    /** Panel corners. Rows sit 4 px inside, so ROW_R + 4 keeps the curves parallel (concentric). */
    private static final int RADIUS = 10;
    /** Rounded module rows / pills inside the panels. */
    private static final int ROW_R = 6;
    private static final int GAP = 8;
    private static final int TOP = 36;

    // Kept between openings so the layout stays where the player left it
    private static final Map<ModuleCategory, int[]> POSITIONS = new EnumMap<>(ModuleCategory.class);
    private static final Map<ModuleCategory, Integer> SCROLL = new EnumMap<>(ModuleCategory.class);
    private static final Set<ModuleCategory> COLLAPSED = EnumSet.noneOf(ModuleCategory.class);
    private static final Set<String> EXPANDED = new HashSet<>();
    private static final Anim ANIM = new Anim();

    /** Stable animation keys per module / setting (no "on:" + name strings built every frame). */
    private static final class Keys {
        final Object on = new Object();
        final Object exp = new Object();
        final Object hov = new Object();
        /** Cached shortened row label: the width it was made for, and the text. */
        int labelFor = -1;
        float labelScale;
        String labelSrc = "";
        String label;
    }

    private static final Map<Object, Keys> KEYS = new java.util.IdentityHashMap<>();

    private static Keys keys(Object owner) {
        Keys k = KEYS.get(owner);
        if (k == null) {
            k = new Keys();
            KEYS.put(owner, k);
        }
        return k;
    }
    /** When each one-shot module was last clicked, for a short highlight flash. */
    private static final Map<String, Long> FLASH = new java.util.HashMap<>();
    /** Whether the one-row layout has been applied yet this session. */
    private static boolean arranged;
    /**
     * A module whose settings were just opened. Its panel scrolls so the whole settings block is in view and
     * a height you dragged the panel to does not cut it off (Reach used to open half-way, needing a scroll).
     */
    private static String revealName;
    private static long revealUntil;
    /**
     * The number / mode setting you clicked last. Only that one changes with the mouse wheel; scrolling over
     * any other setting just scrolls the list.
     */
    private Setting<?> scrollFocus;

    private enum Kind { MODULE, SETTING, BIND }

    /** clipY0/clipY1: the area this row may draw in (panel body, narrowed to the settings block while it animates). */
    private record Row(Kind kind, int x, int y, int w, int h, Module module, Setting<?> setting, boolean visible, int clipY0, int clipY1) {
        boolean contains(double mx, double my) {
            return visible && mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final class Panel {
        ModuleCategory category;
        int x;
        int y;
        int w = PANEL_W;
        int bodyY;
        int bodyH;
        int contentH;
        int moduleCount;
        int enabledCount;
        boolean collapsed;
        final List<Row> rows = new ArrayList<>();
        /** Settings blocks to paint behind setting rows: {y, height} in screen space. */
        final List<int[]> settingBlocks = new ArrayList<>();
    }

    private float scale = 1;
    /** The mouse (menu pixels) on the last frame, for hover looks drawn outside the row code. */
    private int lastMx = -1000;
    private int lastMy = -1000;
    private int vw;
    private int vh;
    /** The open option list of a mode setting (click it: every option shows, click the one you want). */
    private Setting<?> modeMenu;
    private int menuX;
    private int menuY;
    private int menuW;
    private static final int MENU_ROW = 13;
    private final long openedAt = System.currentTimeMillis();

    private String search = "";
    /** The Settings panel (ClickGUI look options), toggled with the gear button or opened from the Quick Panel. */
    private static boolean showSettings;
    /** The Settings panel needs a free spot (it was just opened) - found on the next frame. */
    private static boolean placeSettings;
    /** You dragged the Settings panel yourself - it then stays where you put it. */
    private static boolean settingsMoved;
    /** No free spot was found, so the Settings panel sits on top of the others (they are dimmed behind it). */
    private boolean settingsOverlaps;
    private static final int GEAR = 18;
    /** The "Edit HUD" button right of the gear (its width follows its label: see {@link #hudBtnW}). */
    private static final int HUD_BTN_GAP = 5;
    /**
     * The search text lives in a vanilla text box that always has keyboard focus (it is not drawn; the
     * search bar is drawn by us). Typing reaches it exactly like typing in chat does.
     */
    private EditBox searchBox;
    private Module binding;
    /** A key setting (Menu Key, Save GUI Key...) waiting for its key. */
    private com.relaxedqol.client.setting.KeySetting bindingSetting;
    private Setting<?> slider;
    private int sliderX;
    private int sliderW;
    private Setting<?> editing;
    private String editBuffer = "";
    /**
     * The character event that follows a key press arrives AFTER keyPressed: when that key press finished a key
     * bind, its letter used to land in the search bar (and searched for it). Swallow chars for a moment instead.
     */
    private long swallowCharsUntil;
    /** The search text before a bind / value edit started, put back if anything sneaks into the search box. */
    private String searchBeforeTyping = "";
    private ModuleCategory dragging;
    private int dragOffsetX;
    private int dragOffsetY;
    private Module hovered;
    /** The setting under the mouse (its explanation shows as a tooltip, like module descriptions do). */
    private Setting<?> hoveredSetting;
    private long hoverStart;

    private final Screen parent;
    private ModuleCategory resizing;
    /** The panel whose scroll bar is being dragged, and where on the thumb it was grabbed. */
    private ModuleCategory scrollDragging;
    private double scrollGrab;
    private HudWindow movingWindow;
    private HudWindow resizingWindow;
    private int windowOffsetX;
    private int windowOffsetY;
    // Typing fallback: if a keyboard layout never delivers character events, letters come from key presses
    private boolean charEventsSeen;
    private char lastKeyChar;
    private long lastKeyTime;

    public ClickGuiScreen() {
        this(null);
    }

    /** parent: the screen to go back to when the menu closes (e.g. an open chest), or null. */
    public ClickGuiScreen(Screen parent) {
        super(Component.literal("Relaxed QOL"));
        this.parent = parent;
    }

    /**
     * The client menu to open (Vanilla Style only changes how it looks). Every place that opens the menu goes
     * through here.
     */
    public static Screen create(Screen parent) {
        return new ClickGuiScreen(parent);
    }

    /** Vanilla Style was switched: the open menu just redraws in the new look (nothing to swap). */
    public static void styleChanged() {
        Theme.refresh();
    }

    /** Opens the menu with the Settings panel shown and expanded. */
    public static Screen settingsScreen(Screen parent) {
        openSettings();
        return new ClickGuiScreen(parent);
    }

    /** Shows the Settings panel in the menu that is already open. */
    public static void openSettings() {
        if (!showSettings) placeSettings = true;
        showSettings = true;
        EXPANDED.add("ClickGUI");
        COLLAPSED.remove(ModuleCategory.CLIENT);
        appCategory = ModuleCategory.CLIENT;
        appModule = "ClickGUI";
        appSetScroll = 0;
    }

    /**
     * Whether this module's settings are open in the menu right now (its row unfolded, or picked in the
     * Window layout) - the ActionBar / BossBar modules then show where they will be.
     */
    public static boolean isShowingSettingsOf(String moduleName) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof ClickGuiScreen screen)) return false;
        return screen.appMode() ? moduleName.equals(appModule) : EXPANDED.contains(moduleName);
    }

    @Override
    public void onClose() {
        // Go back to where the menu was opened from; closing must never act like closing a chest
        minecraft.gui.setScreen(parent);
    }

    private ClickGUI settings() {
        return RelaxedQolClient.moduleManager.getModule(ClickGUI.class);
    }

    private void updateScale() {
        // While a slider is dragged the layout must not change under the mouse (dragging Scale % used to run away)
        if (slider != null && vw > 0) return;
        ClickGUI gui = settings();
        // Free scale: every Menu Scale % step really changes the size (it used to snap to 1x/2x/3x, so most of
        // the slider did nothing and then jumped). Text stays sharp through CrispText.
        scale = Theme.layoutScale(minecraft.getWindow().getGuiScale(), gui != null ? gui.getSizePercent() : 100);
        vw = Math.round(width / scale);
        vh = Math.round(height / scale);
    }

    @Override
    protected void init() {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            init$smooth();
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private void init$smooth() {
        ScreenPanelOverlay.menuOpened();
        updateScale();
        searchBox = new EditBox(font, -2000, -2000, 160, 12, Component.literal("Search")); // off-screen: we draw the bar
        searchBox.setMaxLength(24);
        searchBox.setCanLoseFocus(false);
        searchBox.setValue(search);
        searchBox.setResponder(text -> search = text);
        addWidget(searchBox);
        setFocused(searchBox);
        searchBox.setFocused(true);
        minecraft.onTextInputFocusChange(this, true);
        // On the first menu open this session, line everything up in one row: Quick Panel far left,
        // then the module panels next to it, all sharing the same top edge.
        if (!arranged) {
            arrangeRow();
            arranged = true;
        }
        int x = 10;
        int y = TOP;
        if (!POSITIONS.containsKey(ModuleCategory.CLIENT)) {
            POSITIONS.put(ModuleCategory.CLIENT, new int[]{Math.max(0, (vw - PANEL_W) / 2), TOP});
        }
        for (ModuleCategory category : ModuleCategory.values()) {
            if (category == ModuleCategory.CLIENT || !category.active()) continue;
            if (stackedUnder(category) != null) {
                if (!POSITIONS.containsKey(category)) placeStacked(category);
                if (POSITIONS.containsKey(category)) continue;
            }
            if (!POSITIONS.containsKey(category)) {
                if (x + PANEL_W > vw - 6) {
                    x = 10;
                    y += 190;
                }
                POSITIONS.put(category, new int[]{x, y});
            }
            x += PANEL_W + GAP;
        }
    }

    /** Quick Panel hard left, the module panels in a single row to its right, tops aligned. */
    private void arrangeRow() {
        int quickX = 6;
        int quickW = ScreenPanelOverlay.panelWidth();
        com.relaxedqol.client.module.modules.misc.QuickPanel qp =
            RelaxedQolClient.moduleManager.getModule(com.relaxedqol.client.module.modules.misc.QuickPanel.class);
        if (qp != null) {
            // The Quick Panel keeps its own spot (under the HUD by default, or wherever you dragged it)
            quickW = Math.round(ScreenPanelOverlay.panelWidth() * qp.getScalePercent() / 100f);
            if (qp.isPlaced() && qp.getX() > quickX + quickW) quickW = 0; // moved away to the right: start the row at the left
        }
        // A bit more room so the panels never sit on the HUD / Quick Panel
        int left = quickX + quickW + GAP + 12;
        // The row is centred on the screen (it used to start hard left), but never under the Quick Panel
        int total = -GAP;
        for (ModuleCategory category : ModuleCategory.values()) {
            if (category == ModuleCategory.CLIENT || !category.active() || stackedUnder(category) != null) continue;
            total += SIZES.getOrDefault(category, new int[]{PANEL_W, 0})[0] + GAP;
        }
        int x = Math.max(left, (vw - total) / 2);
        if (x + total > vw - 4) x = Math.max(4, Math.min(left, vw - total - 4));
        for (ModuleCategory category : ModuleCategory.values()) {
            if (category == ModuleCategory.CLIENT || !category.active() || stackedUnder(category) != null) continue;
            int w = SIZES.getOrDefault(category, new int[]{PANEL_W, 0})[0];
            POSITIONS.put(category, new int[]{x, TOP});
            x += w + GAP;
        }
        for (ModuleCategory category : ModuleCategory.values()) if (stackedUnder(category) != null) placeStacked(category);
    }

    /**
     * Panels that sit under another panel instead of making the row even longer: Hidden under Mod.
     */
    private static ModuleCategory stackedUnder(ModuleCategory category) {
        return null; // no Mod / Hidden panels in this version
    }

    private void placeStacked(ModuleCategory category) {
        ModuleCategory parent = stackedUnder(category);
        if (parent == null || !category.active()) return;
        int[] top = POSITIONS.get(parent);
        if (top == null) return;
        int body = SIZES.getOrDefault(parent, new int[]{PANEL_W, 0})[1];
        if (body <= 0) body = visibleModules(parent).size() * MODULE_H + 6;
        POSITIONS.put(category, new int[]{top[0], top[1] + HEADER_H + body + GAP * 2});
    }
    // ------------------------------------------------------------------ layout

    private List<Module> visibleModules(ModuleCategory category) {
        List<Module> result = new ArrayList<>();
        if (!category.active()) return result;
        String query = search.toLowerCase(Locale.ROOT);
        for (Module module : RelaxedQolClient.moduleManager.getModulesByCategory(category)) {
            if (query.isEmpty() || module.getName().toLowerCase(Locale.ROOT).contains(query)
                || module.displayName().toLowerCase(Locale.ROOT).contains(query)) result.add(module);
        }
        return result;
    }

    private static int heightOf(Setting<?> setting) {
        if (setting.isNumber()) return NUMBER_H;
        if (setting.isAction()) return ACTION_H;
        return ROW_H;
    }

    private static int settingsHeight(Module module) {
        int h = 4 + ROW_H; // padding + keybind row
        for (Setting<?> setting : module.getSettings()) if (!setting.isHidden()) h += heightOf(setting);
        return h;
    }

    private List<Panel> layout() {
        List<Panel> panels = new ArrayList<>();
        for (ModuleCategory category : ModuleCategory.values()) {
            if (category == ModuleCategory.CLIENT && !showSettings) continue;
            List<Module> modules = visibleModules(category);
            if (!search.isEmpty() && modules.isEmpty()) continue;
            if (!category.active()) continue;

            // A panel that never got a position in init(): give it one here instead of crashing on a null position
            int[] pos = POSITIONS.computeIfAbsent(category, c -> defaultPosition(c));
            int[] size = SIZES.computeIfAbsent(category, c -> new int[]{PANEL_W, 0});
            // Keep it on screen for DRAWING only: the saved spot is never overwritten here. (It used to be: making
            // the menu bigger pushed panels inwards for good, so scaling back down left them in the wrong place.
            // Now going back to the old scale puts every panel exactly where it was.) The panel being dragged or
            // resized follows the mouse exactly.
            int px = pos[0];
            int py = pos[1];
            if (category != dragging && category != resizing) {
                px = Math.max(0, Math.min(Math.max(0, vw - size[0]), px));
                py = Math.max(0, Math.min(Math.max(0, vh - HEADER_H), py));
            }

            Panel panel = new Panel();
            panel.category = category;
            panel.w = size[0];
            panel.x = px;
            panel.y = py;
            panel.bodyY = panel.y + HEADER_H;
            panel.moduleCount = modules.size();
            for (Module module : modules) {
                if (module.isEnabled()) panel.enabledCount++;
            }
            if (COLLAPSED.contains(category) && search.isEmpty()) {
                panel.collapsed = true;
                panels.add(panel);
                continue;
            }

            // First pass in content coordinates
            record Pending(Kind kind, int y, int h, Module module, Setting<?> setting, int clipTop, int clipBottom) {}
            List<Pending> pending = new ArrayList<>();
            List<int[]> blocks = new ArrayList<>();
            int cy = 0;
            int revealTop = -1;
            int revealBottom = -1;
            boolean anyExpanded = false;
            boolean anyShown = false;
            // The Settings panel shows the look options straight away - no "ClickGUI" row to fold open
            boolean direct = category == ModuleCategory.CLIENT;
            for (Module module : modules) {
                if (direct) {
                    for (Setting<?> setting : module.getSettings()) {
                        if (setting.isHidden()) continue;
                        pending.add(new Pending(Kind.SETTING, cy, heightOf(setting), module, setting, Integer.MIN_VALUE, Integer.MAX_VALUE));
                        cy += heightOf(setting);
                    }
                    continue;
                }
                pending.add(new Pending(Kind.MODULE, cy, MODULE_H, module, null, Integer.MIN_VALUE, Integer.MAX_VALUE));
                if (module.getName().equals(revealName)) {
                    revealTop = cy;
                    revealBottom = cy + MODULE_H + settingsHeight(module) + 2;
                }
                if (EXPANDED.contains(module.getName()) && hasSettings(module)) anyExpanded = true;
                cy += MODULE_H;
                float expand = hasSettings(module) ? ANIM.get(keys(module).exp, EXPANDED.contains(module.getName()) ? 1 : 0) : 0;
                if (expand > 0.001f) {
                    anyShown = true;
                    int full = settingsHeight(module);
                    int shown = Math.round(full * expand);
                    int sy = cy + 2;
                    int clipTop = cy;
                    int clip = cy + shown;
                    for (Setting<?> setting : module.getSettings()) {
                        if (setting.isHidden()) continue;
                        pending.add(new Pending(Kind.SETTING, sy, heightOf(setting), module, setting, clipTop, clip));
                        sy += heightOf(setting);
                    }
                    pending.add(new Pending(Kind.BIND, sy, ROW_H, module, null, clipTop, clip));
                    blocks.add(new int[]{cy, shown});
                    cy += shown;
                }
            }
            panel.contentH = cy + 3;
            int maxBody = Math.max(48, vh - panel.bodyY - 8);
            // A height you dragged the panel to only applies while no settings are open in it: open settings get
            // all the room the screen has, so they are never cut off.
            // Closing settings in a resized panel used to snap the panel back to its dragged height the
            // moment you clicked (while the fold animation was still running), so the panel jumped and the scroll
            // bar flashed. Now the full room stays until the fold has finished, and only then does the panel ease
            // back to the height you gave it.
            int wantBody = maxBody;
            if (size[1] > 0 && !anyExpanded && !anyShown) wantBody = Math.min(maxBody, Math.max(40, size[1]));
            // (While you drag the panel's corner it follows the mouse exactly)
            maxBody = easedBodyCap(category, wantBody, category == resizing);
            panel.bodyH = Math.min(panel.contentH, maxBody);
            int maxScroll = Math.max(0, panel.contentH - panel.bodyH);
            int scroll = Math.max(0, Math.min(maxScroll, SCROLL.getOrDefault(category, 0)));
            if (revealTop >= 0 && System.currentTimeMillis() < revealUntil) {
                // Scroll just enough to show the whole settings block, but never past the module's own row
                int want = Math.min(revealTop, Math.max(scroll, revealBottom - panel.bodyH));
                scroll = Math.max(0, Math.min(maxScroll, want));
            }
            SCROLL.put(category, scroll);

            int bodyTop = panel.bodyY;
            int bodyBottom = panel.bodyY + panel.bodyH;
            for (Pending p : pending) {
                int y = bodyTop + p.y() - scroll;
                boolean insideBody = y + p.h() > bodyTop && y < bodyBottom;
                // Module rows have no settings block to fit in; setting rows must lie inside their (animated) block
                boolean insideExpand = p.clipTop() == Integer.MIN_VALUE || (long) p.y() + p.h() <= (long) p.clipBottom() + 1;
                int clipY0 = bodyTop;
                int clipY1 = bodyBottom - 1;
                if (p.clipTop() != Integer.MIN_VALUE) {
                    clipY0 = Math.max(clipY0, bodyTop + p.clipTop() - scroll);
                    clipY1 = Math.min(clipY1, bodyTop + p.clipBottom() - scroll);
                }
                // A scrolling panel keeps a lane free for its scroll bar (it used to run through the arrows)
                panel.rows.add(new Row(p.kind(), panel.x, y, panel.w - (maxScroll > 0 ? SCROLL_LANE : 0), p.h(), p.module(), p.setting(),
                    insideBody && insideExpand, clipY0, clipY1));
            }
            for (int[] block : blocks) {
                panel.settingBlocks.add(new int[]{bodyTop + block[0] - scroll, block[1]});
            }
            panels.add(panel);
        }
        // The Settings panel is on top of the others - drawn last and the first to get clicks
        for (int i = 0; i < panels.size(); i++) {
            if (panels.get(i).category == ModuleCategory.CLIENT) {
                panels.add(panels.remove(i));
                break;
            }
        }
        return panels;
    }

    /** Per panel: the eased body height cap {value, last update in ns}. */
    private static final Map<ModuleCategory, double[]> BODY_CAP = new java.util.EnumMap<>(ModuleCategory.class);

    /** Grows to {@code target} at once; shrinks towards it smoothly (frame-rate independent). */
    private static int easedBodyCap(ModuleCategory category, int target, boolean instant) {
        long now = System.nanoTime();
        double[] cap = BODY_CAP.get(category);
        if (cap == null || instant) {
            BODY_CAP.put(category, new double[]{target, now});
            return target;
        }
        double dt = Math.min(0.1, (now - (long) cap[1]) / 1_000_000_000.0);
        cap[1] = now;
        if (target >= cap[0]) {
            cap[0] = target;
        } else {
            cap[0] += (target - cap[0]) * (1 - Math.exp(-16 * dt));
            if (cap[0] - target < 0.5) cap[0] = target;
        }
        return (int) Math.ceil(cap[0]);
    }

    /** A free spot for a panel that has no saved position yet: right of / below the other panels. */
    /** The rectangle a panel takes on screen {x, y, w, h}. */
    private static int[] rectOf(Panel p) {
        return new int[]{p.x, p.y, p.w, HEADER_H + (p.collapsed ? 0 : p.bodyH)};
    }

    private static boolean overlapsOthers(Panel settingsPanel, List<Panel> panels) {
        int[] a = rectOf(settingsPanel);
        for (Panel p : panels) {
            if (p == settingsPanel) continue;
            int[] b = rectOf(p);
            if (a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3]) return true;
        }
        return false;
    }

    /**
     * Puts the Settings panel where it covers nothing - in the top row next to your panels (the right-most
     * free spot, near the gear), or anywhere free lower down. Only when the screen is full does it go on the right,
     * on top (the others are dimmed behind it then, so it reads as a deliberate overlay, not a stray panel).
     */
    private void placeSettingsPanel(List<Panel> panels) {
        Panel settingsPanel = null;
        List<int[]> others = new ArrayList<>();
        for (Panel p : panels) {
            if (p.category == ModuleCategory.CLIENT) settingsPanel = p;
            else others.add(rectOf(p));
        }
        int w = settingsPanel != null ? settingsPanel.w : PANEL_W;
        int h = settingsPanel != null ? HEADER_H + Math.min(settingsPanel.contentH, Math.max(48, vh - TOP - HEADER_H - 8)) : 200;
        int[] spot = null;
        for (int y = TOP; y + Math.min(h, 120) <= vh - 6 && spot == null; y += 8) {
            for (int x = vw - w - 10; x >= 6; x -= 4) {
                boolean free = true;
                for (int[] r : others) {
                    if (x < r[0] + r[2] + GAP && r[0] < x + w + GAP && y < r[1] + r[3] + GAP && r[1] < y + h + GAP) {
                        free = false;
                        break;
                    }
                }
                if (free) {
                    spot = new int[]{x, y};
                    break;
                }
            }
        }
        if (spot == null) spot = new int[]{Math.max(6, vw - w - 10), TOP};
        POSITIONS.put(ModuleCategory.CLIENT, spot);
        SCROLL.put(ModuleCategory.CLIENT, 0);
    }

    private int[] defaultPosition(ModuleCategory category) {
        int x = 10;
        int y = TOP;
        for (Map.Entry<ModuleCategory, int[]> e : POSITIONS.entrySet()) {
            if (e.getKey() == category || e.getValue() == null) continue;
            int[] size = SIZES.get(e.getKey());
            int w = size != null ? size[0] : PANEL_W;
            if (e.getValue()[1] == TOP) x = Math.max(x, e.getValue()[0] + w + GAP);
        }
        if (x + PANEL_W > vw - 6) {
            x = 10;
            y = TOP + 190;
        }
        return new int[]{x, y};
    }

    private Row rowAt(List<Panel> panels, double mx, double my) {
        for (int i = panels.size() - 1; i >= 0; i--) {
            for (Row row : panels.get(i).rows) {
                if (row.contains(mx, my)) return row;
            }
        }
        return null;
    }

    private Panel headerAt(List<Panel> panels, double mx, double my) {
        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel p = panels.get(i);
            if (mx >= p.x && mx < p.x + p.w && my >= p.y && my < p.y + HEADER_H) return p;
        }
        return null;
    }

    private Panel panelAt(List<Panel> panels, double mx, double my) {
        for (int i = panels.size() - 1; i >= 0; i--) {
            Panel p = panels.get(i);
            if (mx >= p.x && mx < p.x + p.w && my >= p.y && my < p.bodyY + p.bodyH) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Opened from the title screen / server list: show the usual menu panorama behind the panels
        if (minecraft.level == null) extractPanorama(graphics, partialTick);
        ClickGUI gui = settings();
        if ((gui == null || gui.useBlur()) && minecraft.options.getMenuBackgroundBlurriness() >= 1) {
            graphics.blurBeforeThisStratum();
        }
        graphics.fillGradient(0, 0, width, height, 0x14000008, 0x30000010);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            extractRenderState$smooth(graphics, mouseX, mouseY, partialTick);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private void extractRenderState$smooth(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        ANIM.tick();
        updateScale();
        // While binding a key or typing a value, the search must not change
        if (binding == null && editing == null && bindingSetting == null) {
            searchBeforeTyping = search;
        } else if (!search.equals(searchBeforeTyping) && searchBox != null) {
            searchBox.setValue(searchBeforeTyping);
            search = searchBeforeTyping;
        }
        int mx = Math.round(mouseX / scale);
        int my = Math.round(mouseY / scale);
        lastMx = mx;
        lastMy = my;

        // HUD cards (the Radar) step BEHIND the menu while you work in it, so a module's
        // settings are never hidden under one. A card only comes to the front while you point at it outside
        // the menu (to drag / resize it), or while you are dragging it.
        boolean cardsInFront = cardsInFront(mouseX, mouseY, mx, my);
        if (!cardsInFront) OverlayRenderer.renderAll(graphics, partialTick);

        // Update animation targets before laying out, so heights follow smoothly
        for (Module module : RelaxedQolClient.moduleManager.getModules()) {
            Keys k = keys(module);
            ANIM.smooth(k.on, module.isEnabled() ? 1 : 0, 18);
            ANIM.smooth(k.exp, EXPANDED.contains(module.getName()) ? 1 : 0, 18);
        }

        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        float prevText = CrispText.begin(scale);

        // LoverFella Mode: hearts, the LC logo and the bobble heads behind the panels
        if (LoverFx.on()) LoverFx.drawMenuBackdrop(graphics, font, vw, vh, mx, my, hudCardRects());

        if (appMode()) {
            // Settings > Menu Layout: Window - one app-style window instead of the floating panels
            drawApp(graphics, mx, my);
        } else {
            List<Panel> panels = layout();
            if (placeSettings && showSettings) {
                placeSettings = false;
                if (!settingsMoved) {
                    placeSettingsPanel(panels);
                    panels = layout();
                }
            }
            // The Settings panel comes last from layout() (on top); the panels it covers are dimmed behind it
            Panel settingsPanel = null;
            for (Panel p : panels) if (p.category == ModuleCategory.CLIENT) settingsPanel = p;
            settingsOverlaps = settingsPanel != null && overlapsOthers(settingsPanel, panels);
            Row hoverRow = rowAt(panels, mx, my);
            Module hoverModule = hoverRow != null && hoverRow.kind() == Kind.MODULE ? hoverRow.module() : null;
            Setting<?> hoverSetting = hoverRow != null && hoverRow.kind() == Kind.SETTING ? hoverRow.setting() : null;
            setHover(hoverModule, hoverSetting);

            int index = 0;
            for (Panel panel : panels) {
                if (panel == settingsPanel && settingsOverlaps) graphics.fill(0, 0, vw, vh, 0x80000000);
                drawPanel(graphics, panel, index++, hoverRow, mx, my);
            }
            // LoverFella Mode: little figures sitting on the windows
            if (LoverFx.on()) {
                List<int[]> perches = new ArrayList<>();
                for (Panel panel : panels) perches.add(new int[]{panel.x, panel.y, panel.w});
                perches.sort((a, b) -> Integer.compare(a[0], b[0]));
                LoverFx.drawSitters(graphics, perches, searchBarRects(), mx, my);
            }
        }
        if (appMode() && LoverFx.on()) {
            // One edge (the old three overlapping stretches put figures on top of each other), and the figures
            // keep clear of the search bar (Isaac sat right under it)
            int[] b = appBounds();
            LoverFx.drawSitters(graphics, List.of(new int[]{b[0], b[1], b[2]}), searchBarRects(), mx, my);
        }

        drawSearchBar(graphics);
        if (modeMenu != null) drawModeMenu(graphics, mx, my);
        else drawTooltip(graphics, mx, my);

        CrispText.end(prevText);
        graphics.pose().popMatrix();

        // The Quick Panel (your command buttons) is shown in the menu too, so it can be moved / set up here
        ScreenPanelOverlay.render(graphics, mouseX, mouseY);

        // Cards (radar) on top of the menu while you point at / drag them
        if (cardsInFront) OverlayRenderer.renderAll(graphics, partialTick);
        float hudScale = OverlayRenderer.hudScale();
        double hx = mouseX / hudScale;
        double hy = mouseY / hudScale;
        graphics.pose().pushMatrix();
        graphics.pose().scale(hudScale, hudScale);
        for (HudWindow window : OverlayRenderer.visibleWindows()) {
            boolean hot = window == movingWindow || window == resizingWindow || (cardsInFront && window.contains(hx, hy));
            // Behind the menu: only a faint frame, so you can still see where the cards are
            if (cardsInFront || hot) window.drawEditFrame(graphics, hot, Theme.accent());
        }
        graphics.pose().popMatrix();
    }

    /** The visible HUD cards (radar, armor...) in menu coordinates, so LoverFella Mode's logo stays off them. */
    private List<int[]> hudCardRects() {
        List<int[]> out = new ArrayList<>();
        float k = OverlayRenderer.hudScale() / scale;
        for (HudWindow w : OverlayRenderer.visibleWindows()) {
            out.add(new int[]{Math.round(w.getX() * k), Math.round(w.getY() * k), Math.round(w.getW() * k), Math.round(w.getH() * k)});
        }
        return out;
    }

    /** Whether the virtual point is on the menu itself: a panel, the app window, the search bar or the gear. */
    private boolean overMenu(double mx, double my) {
        int sbw = 180;
        int sbx = (vw - sbw) / 2;
        if (my >= 9 && my < 27 && mx >= sbx && mx < sbx + sbw + 5 + GEAR + HUD_BTN_GAP + hudBtnW()) return true;
        if (appMode()) {
            int[] b = appBounds();
            return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
        }
        List<Panel> panels = layout();
        return panelAt(panels, mx, my) != null || headerAt(panels, mx, my) != null;
    }

    private boolean cardsInFront(double guiX, double guiY, double mx, double my) {
        if (movingWindow != null || resizingWindow != null) return true;
        float hudScale = OverlayRenderer.hudScale();
        double hx = guiX / hudScale;
        double hy = guiY / hudScale;
        boolean overCard = false;
        for (HudWindow window : OverlayRenderer.visibleWindows()) {
            if (window.contains(hx, hy) || window.onGrip(hx, hy)) overCard = true;
        }
        return overCard && !overMenu(mx, my);
    }

    private void drawPanel(GuiGraphicsExtractor g, Panel panel, int index, Row hoverRow, int mx, int my) {
        int accent = Theme.accent(index * 3);
        boolean collapsed = panel.collapsed;
        int totalH = HEADER_H + (collapsed ? 0 : panel.bodyH);

        Render2D.shadow(g, panel.x, panel.y, panel.w, totalH, RADIUS);

        // Header
        if (collapsed) Render2D.roundRect(g, panel.x, panel.y, panel.w, HEADER_H, RADIUS, Theme.header());
        else Render2D.roundRectTop(g, panel.x, panel.y, panel.w, HEADER_H, RADIUS, Theme.header());
        Render2D.headerSheen(g, panel.x, panel.y, panel.w, HEADER_H, RADIUS, accent, collapsed);
        Render2D.icon(g, panel.category.iconSprite(), panel.x + 7, panel.y + 5, 10, accent);
        CrispText.text(g, font, Component.literal(panel.category.getName().toUpperCase(Locale.ROOT)).withStyle(ChatFormatting.BOLD), panel.x + 20, panel.y + 6, Theme.TEXT_DIM);
        // The Settings panel has no "on / total" counter (it showed a meaningless "0/1")
        if (panel.category != ModuleCategory.CLIENT) {
            String count = panel.enabledCount + "/" + panel.moduleCount;
            CrispText.text(g, font, count, panel.x + panel.w - CrispText.width(font, count) - 8, panel.y + 6, Theme.TEXT_FAINT);
        }
        int inset = collapsed ? RADIUS : 0;
        Render2D.headerLine(g, panel.x + inset, panel.y + HEADER_H - 1, panel.w - inset * 2, accent);
        if (collapsed) {
            Render2D.roundOutline(g, panel.x, panel.y, panel.w, HEADER_H, RADIUS, Theme.BORDER);
            return;
        }

        // Body
        int bodyTop = panel.bodyY;
        int bodyBottom = panel.bodyY + panel.bodyH;
        Render2D.roundRectBottom(g, panel.x, bodyTop, panel.w, panel.bodyH, RADIUS, Theme.panel());
        g.enableScissor(panel.x, bodyTop, panel.x + panel.w, bodyBottom - 1);

        int lane = panel.contentH > panel.bodyH ? SCROLL_LANE : 0;
        for (int[] block : panel.settingBlocks) {
            // Rounded like everything else (it used to be a square box inside the rounded panel)
            Render2D.roundRect(g, panel.x + 4, block[0] + 1, panel.w - 8 - lane, block[1] - 2, ROW_R, Theme.settingsBg());
            if (block[1] > 8) Render2D.roundRect(g, panel.x + 6, block[0] + 4, 2, block[1] - 8, 1, Theme.withAlpha(accent, 0.45f));
        }

        for (Row row : panel.rows) {
            if (row.y() + row.h() < bodyTop || row.y() > bodyBottom) continue;
            boolean hover = row == hoverRow;
            if (row.kind() == Kind.MODULE) {
                drawModule(g, row, hover, accent, mx, my);
                continue;
            }
            if (row.clipY1() <= row.clipY0()) continue;
            g.enableScissor(panel.x, row.clipY0(), panel.x + panel.w, row.clipY1());
            if (row.kind() == Kind.BIND) drawBindRow(g, row, hover, accent);
            else drawSetting(g, row, hover, accent, mx);
            g.disableScissor();
        }
        g.disableScissor();
        if (VanillaStyle.on()) VanillaStyle.bevel(g, panel.x, panel.y, panel.w, totalH);
        else Render2D.roundOutline(g, panel.x, panel.y, panel.w, totalH, RADIUS, Theme.BORDER);
        // Resize grip in the bottom-right corner
        int gx = panel.x + panel.w - 5;
        int gy = panel.bodyY + panel.bodyH - 5;
        int gripColor = panel.category == resizing ? 0xB0FFFFFF : 0x38FFFFFF;
        Render2D.resizeGrip(g, gx, gy, gripColor);

        // Scrollbar: grab the thumb and drag it, or click the track to jump there
        if (panel.contentH > panel.bodyH) {
            int[] t = thumb(panel);
            boolean overBar = onScrollBar(panel, mx, my);
            boolean dragged = scrollDragging == panel.category;
            boolean hot = dragged || (overBar && my >= t[0] && my < t[0] + t[1]);
            if (overBar || dragged) Render2D.roundRect(g, panel.x + panel.w - 7, bodyTop + 3, 4, panel.bodyH - 6, 2, 0x12FFFFFF);
            int bw = overBar || dragged ? 4 : 3;
            // Centred in its own lane, clear of the rows and of the panel's outline
            Render2D.roundRect(g, panel.x + panel.w - 3 - bw, t[0], bw, t[1], 2, hot ? 0x8CFFFFFF : overBar ? 0x66FFFFFF : 0x40FFFFFF);
        }
    }

    /** Scroll bar thumb of a panel: {y, height} in virtual pixels. */
    private static int[] thumb(Panel panel) {
        int trackH = panel.bodyH - 6;
        int thumbH = Math.max(14, trackH * panel.bodyH / Math.max(1, panel.contentH));
        int scroll = SCROLL.getOrDefault(panel.category, 0);
        int thumbY = panel.bodyY + 3 + (trackH - thumbH) * scroll / Math.max(1, panel.contentH - panel.bodyH);
        return new int[]{thumbY, thumbH};
    }

    /** Over a panel's scroll bar strip (the right edge of the body, above the resize grip). */
    private static boolean onScrollBar(Panel panel, double mx, double my) {
        return !panel.collapsed && panel.contentH > panel.bodyH
            && mx >= panel.x + panel.w - SCROLL_LANE && mx < panel.x + panel.w + 1
            && my >= panel.bodyY + 2 && my < panel.bodyY + panel.bodyH - GRIP;
    }

    /** Moves a panel's scroll so the grabbed point of the thumb follows the mouse. */
    private static void dragScrollTo(Panel panel, double my, double grab) {
        int trackH = panel.bodyH - 6;
        int thumbH = thumb(panel)[1];
        int range = trackH - thumbH;
        if (range <= 0) return;
        double f = (my - grab - (panel.bodyY + 3)) / range;
        int max = Math.max(0, panel.contentH - panel.bodyH);
        SCROLL.put(panel.category, (int) Math.round(Math.max(0, Math.min(1, f)) * max));
    }

    /** The two buttons on the right of a module row: {bindX, bindW, arrowX, arrowW}. */
    private int[] pills(Row row) {
        Module module = row.module();
        String bind = module == binding ? "\u2026" : (module.getKey() >= 0 ? KeyUtil.name(module.getKey()) : "Bind");
        // No settings button on modules without settings (e.g. SilentClose): clicking the row runs them
        int arrowW = hasSettings(module) ? 13 : 0;
        int arrowX = row.x() + row.w() - 7 - arrowW;
        int bindW = Math.max(28, CrispText.width(font, bind) + 10);
        int bindX = arrowX - (arrowW > 0 ? 3 : 0) - bindW;
        return new int[]{bindX, bindW, arrowX, arrowW};
    }

    private static boolean hasSettings(Module module) {
        // One-shot modules just run when clicked - no dropdown
        if (module instanceof ActionModule) return false;
        for (Setting<?> setting : module.getSettings()) if (!setting.isHidden()) return true;
        // Normal toggle modules still get a dropdown so their keybind is reachable from the arrow
        return !module.isSettingsOnly();
    }

    private void drawModule(GuiGraphicsExtractor g, Row row, boolean hover, int accent, int mx, int my) {
        Module module = row.module();
        String key = module.getName();
        Keys keys = keys(module);
        float on = ANIM.get(keys.on, module.isEnabled() ? 1 : 0);
        // Fast ease (about 80 ms) in AND out. It used to switch the colours on/off at 1% of the fade, so the
        // highlight popped on instantly and then stayed lit for a quarter second after the mouse left.
        float hov = ANIM.smooth(keys.hov, hover ? 1 : 0, 28);
        int x = row.x() + 4;
        int y = row.y() + 1;
        int w = row.w() - 8;
        int h = row.h() - 2;

        boolean action = module instanceof ActionModule;
        if (action) {
            // One-shot modules never stay on; they flash briefly when clicked
            long since = System.currentTimeMillis() - FLASH.getOrDefault(key, 0L);
            on = since < 300 ? 1f - since / 300f : 0f;
        }
        // Flat rows. No box around every module - a soft fill on hover, and an enabled module gets a calm
        // accent tint with a short accent marker on its left edge, so the on / off state reads at a glance.
        int fill = Theme.mix(Theme.withAlpha(0xFFFFFFFF, 0.07f * hov), Theme.withAlpha(accent, 0.16f + 0.06f * hov), on);
        Render2D.roundRect(g, x, y, w, h, ROW_R, fill);
        if (on > 0.01f) Render2D.roundRect(g, x + 2, y + 4, 2, h - 8, 1, Theme.withAlpha(accent, on));

        int[] p = pills(row);
        boolean overBind = hover && mx >= p[0] && mx < p[0] + p[1];
        boolean overArrow = hover && mx >= p[2] && mx < p[2] + p[3];

        // Name (shortened with "…" if it would run into the buttons)
        String label = module == binding ? "Press a key\u2026" : module.displayName();
        int labelX = x + 8;
        if (action && module != binding) {
            CrispText.text(g, font, "\u25B6", labelX, y + 4, Theme.mix(Theme.TEXT_FAINT, accent, Math.max(on, hov)));
            labelX += 10;
        }
        int maxW = p[0] - labelX - 4;
        if (module != binding) {
            // Shortening needs many width measurements: only redo it when the room or the scale changed
            if (keys.label == null || keys.labelFor != maxW || keys.labelScale != scale || !keys.labelSrc.equals(label)) {
                keys.labelSrc = label;
                keys.label = fitText(label, maxW);
                keys.labelFor = maxW;
                keys.labelScale = scale;
            }
            label = keys.label;
        }
        int textColor = module == binding ? accent : Theme.mix(Theme.TEXT_DIM, Theme.TEXT, Math.max(on, hov * 0.8f));
        CrispText.text(g, font, label, labelX, y + 4, textColor);

        // Key cap: shown when a key is bound (or while binding); an unbound "Bind" only appears under the mouse
        boolean bound = module.getKey() >= 0;
        String bind = module == binding ? "\u2026" : (bound ? KeyUtil.name(module.getKey()) : "Bind");
        if (bound || module == binding || hover) {
            int capFill = module == binding ? Theme.withAlpha(accent, 0.35f) : overBind ? 0x40FFFFFF : bound ? KEYCAP_FILL : Theme.PILL;
            if (!vanillaBox(g, p[0], y + 2, p[1], h - 4, module == binding || overBind)) {
                Render2D.roundRect(g, p[0], y + 2, p[1], h - 4, Theme.R_CONTROL, capFill);
                Render2D.roundOutline(g, p[0], y + 2, p[1], h - 4, Theme.R_CONTROL,
                    module == binding ? Theme.withAlpha(accent, 0.8f) : overBind ? 0x73FFFFFF : bound ? KEYCAP_EDGE : Theme.HAIRLINE);
            }
            int capText = module == binding || overBind || bound ? Theme.TEXT : Theme.TEXT_FAINT;
            CrispText.centered(g, font, bind, p[0] + p[1] / 2, y + 4, capText);
        }

        // Settings chevron (only when there is something to configure): a plain icon, a soft square under the mouse
        if (p[3] > 0) {
            if (overArrow) Render2D.roundRect(g, p[2], y + 2, p[3], h - 4, Theme.R_CONTROL, 0x1FFFFFFF);
            boolean open = EXPANDED.contains(key);
            int iconColor = overArrow || open ? Theme.TEXT : Theme.mix(Theme.TEXT_FAINT, Theme.TEXT_DIM, hov);
            Render2D.chevron(g, p[2] + 4, y + 5, open, iconColor);
        }
    }

    /** The text shortened with "…" so it is at most maxW virtual pixels wide. */
    private String fitText(String text, int maxW) {
        if (maxW <= 6) return "";
        return Theme.ellipsize(text, maxW, t -> CrispText.width(font, t));
    }

    /**
     * One setting row. the name is shortened so it never runs into the value on the right (long names in a
     * narrow panel used to overlap their value), and colour settings are a swatch that opens the colour picker.
     */
    /** A bound key shown next to a module: a dark box with a clear edge, so the key is easy to see on any background. */
    private static final int KEYCAP_FILL = 0x80000000;
    private static final int KEYCAP_EDGE = 0x66FFFFFF;

    /** Vanilla Style: a small value box drawn as a Minecraft button. False = not in Vanilla Style (draw the normal box). */
    private static boolean vanillaBox(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hot) {
        if (!VanillaStyle.on()) return false;
        VanillaStyle.buttonBg(g, x, y, w, h, hot);
        return true;
    }

    private void drawSetting(GuiGraphicsExtractor g, Row row, boolean hover, int accent, int mx) {
        Setting<?> setting = row.setting();
        int x = row.x();
        int y = row.y();
        int w = row.w();
        int labelX = x + 10;
        Keys keys = keys(setting);
        float hov = ANIM.smooth(keys.hov, hover ? 1 : 0, 28);
        if (hov > 0.004f && !setting.isAction()) Render2D.roundRect(g, x + 6, y, w - 8, row.h(), Theme.R_CONTROL, Theme.withAlpha(0xFFFFFFFF, 0.05f * hov));
        // The setting the mouse wheel changes right now (clicked last): a small accent bar on its left
        if (setting == scrollFocus) Render2D.roundRect(g, x + 6, y + 2, 2, row.h() - 4, 1, accent);

        if (setting.isAction()) {
            // An action is a real button: quiet until the mouse is on it
            if (!vanillaBox(g, x + 10, y + 2, w - 18, row.h() - 4, hover)) {
                Render2D.roundRect(g, x + 10, y + 2, w - 18, row.h() - 4, Theme.R_CONTROL, Theme.mix(Theme.TRACK, Theme.withAlpha(accent, 0.55f), hov));
                Render2D.roundOutline(g, x + 10, y + 2, w - 18, row.h() - 4, Theme.R_CONTROL, Theme.mix(Theme.HAIRLINE, Theme.withAlpha(accent, 0.8f), hov));
            }
            CrispText.centered(g, font, fitText(setting.getName(), w - 26), x + 10 + (w - 18) / 2, y + 5, Theme.TEXT);
            return;
        }

        int right = x + w - 8;          // right edge of the value
        int room = w - 18;              // name + value must fit in here
        int valueW;                     // what the value on the right takes (decided per type below)

        if (setting.isKey()) {
            boolean waiting = setting == bindingSetting;
            String value = waiting ? "Press a key\u2026"
                : ((com.relaxedqol.client.setting.KeySetting) setting).getKey() < 0 ? "Bind" : setting.display();
            value = fitText(value, room / 2);
            int pw = CrispText.width(font, value) + 10;
            valueW = pw + 4;
            if (!vanillaBox(g, right - pw, y + 1, pw, 11, waiting || hover))
                Render2D.roundRect(g, right - pw, y + 1, pw, 11, Theme.R_CONTROL, waiting ? accent : hover ? Theme.withAlpha(accent, 0.45f) : Theme.TRACK);
            CrispText.text(g, font, value, right - pw + 5, y + 3, Theme.TEXT);
        } else if (setting.isBoolean()) {
            valueW = 22;
            float p = ANIM.smooth(keys.on, (Boolean) setting.getValue() ? 1 : 0, 18);
            Render2D.toggleSwitch(g, x + w - 26, y + 3, p, accent);
        } else if (setting.isColor()) {
            var color = (com.relaxedqol.client.setting.ColorSetting) setting;
            String value = fitText(color.label(), Math.max(20, room / 2 - 22));
            int tw = CrispText.width(font, value);
            int sw = 16;
            valueW = tw + sw + 10;
            int c = color.argb();
            Render2D.roundRect(g, right - sw, y + 2, sw, 9, 3, c);
            Render2D.roundOutline(g, right - sw, y + 2, sw, 9, 3, hover ? 0xB0FFFFFF : 0x40FFFFFF);
            CrispText.text(g, font, value, right - sw - 4 - tw, y + 3, hover ? Theme.TEXT : Theme.TEXT_DIM);
        } else if (setting.isNumber()) {
            String value = setting == editing ? editBuffer + "_" : setting.display();
            valueW = CrispText.width(font, value) + 10;
            if (setting == editing) {
                // Typing an exact value (click the number)
                boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
                String typed = editBuffer + (blink ? "_" : " ");
                int tw = CrispText.width(font, typed);
                Render2D.roundRect(g, x + w - 12 - tw, y + 1, tw + 8, 11, Theme.R_CONTROL, Theme.withAlpha(accent, 0.35f));
                CrispText.text(g, font, typed, x + w - 8 - tw, y + 3, Theme.TEXT);
            } else {
                CrispText.text(g, font, value, right - CrispText.width(font, value), y + 3, Theme.TEXT);
            }
            int sx = labelX;
            int sw = w - 20;
            Render2D.slider(g, sx, y + 15, sw, (float) setting.getFraction(), accent, setting == slider);
        } else if (setting.isMode()) {
            String value = fitText(setting.display(), Math.max(24, room * 55 / 100 - 20));
            int vw0 = CrispText.width(font, value);
            valueW = vw0 + 22;
            // A little drop-down button - click it and every option is listed
            int pw = vw0 + 17;
            if (!vanillaBox(g, right - pw, y + 1, pw, 11, setting == modeMenu || hover))
                Render2D.roundRect(g, right - pw, y + 1, pw, 11, Theme.R_CONTROL, setting == modeMenu || hover ? Theme.withAlpha(accent, 0.30f) : Theme.TRACK);
            CrispText.text(g, font, value, right - pw + 4, y + 3, Theme.TEXT);
            CrispText.text(g, font, "\u25BE", right - 10, y + 3, hover ? Theme.TEXT : Theme.TEXT_FAINT);
        } else if (setting.isList()) {
            int count = ((ListSetting) setting).getValue().size();
            String value = count == 0 ? "Edit" : count + " \u00B7 Edit";
            int pw = CrispText.width(font, value) + 8;
            valueW = pw + 4;
            if (!vanillaBox(g, right - pw, y + 1, pw, 11, hover))
                Render2D.roundRect(g, right - pw, y + 1, pw, 11, Theme.R_CONTROL, hover ? Theme.withAlpha(accent, 0.45f) : Theme.TRACK);
            CrispText.text(g, font, value, right - pw + 4, y + 3, Theme.TEXT);
        } else if (setting.isText() && setting.usesPrompt()) {
            // A button showing the value; clicking it opens the text window
            String value = setting.display().isEmpty() ? "Set\u2026" : setting.display();
            String shown = fitText(value, Math.max(24, room * 55 / 100 - 18));
            int pw = CrispText.width(font, shown) + 18;
            valueW = pw + 4;
            if (!vanillaBox(g, right - pw, y + 1, pw, 11, hover))
                Render2D.roundRect(g, right - pw, y + 1, pw, 11, Theme.R_CONTROL, hover ? Theme.withAlpha(accent, 0.45f) : Theme.TRACK);
            CrispText.text(g, font, shown, right - pw + 4, y + 3, setting.display().isEmpty() ? Theme.TEXT_DIM : Theme.TEXT);
            CrispText.text(g, font, "\u270E", x + w - 15, y + 3, hover ? Theme.TEXT : Theme.TEXT_FAINT);
        } else if (setting.isText()) {
            boolean isEditing = setting == editing;
            String value = isEditing ? editBuffer : setting.display();
            int maxW = Math.max(24, room * 55 / 100);
            valueW = maxW + 4;
            int fieldLeft = right - maxW;
            String shown = value;
            while (shown.length() > 1 && CrispText.width(font, shown) > maxW) {
                shown = isEditing ? shown.substring(1) : shown.substring(0, shown.length() - 1);
            }
            if (isEditing) {
                // Left-anchor while editing and draw the caret as its own thin bar, so a blinking caret
                // never changes the text's width and makes the whole line jump left and right.
                CrispText.text(g, font, shown, fieldLeft, y + 3, accent);
                if ((System.currentTimeMillis() / 500) % 2 == 0) {
                    int caretX = Math.min(fieldLeft + CrispText.width(font, shown) + 1, right);
                    g.fill(caretX, y + 2, caretX + 1, y + row.h() - 3, accent);
                }
                g.fill(fieldLeft, y + row.h() - 2, right, y + row.h() - 1, accent);
            } else {
                CrispText.text(g, font, shown, right - CrispText.width(font, shown), y + 3, Theme.TEXT);
            }
        } else {
            valueW = 0;
        }
        CrispText.text(g, font, fitText(setting.getName(), room - valueW), labelX, y + 3, Theme.TEXT_DIM);
    }

    private void drawBindRow(GuiGraphicsExtractor g, Row row, boolean hover, int accent) {
        Module module = row.module();
        int x = row.x();
        int y = row.y();
        int w = row.w();
        if (hover) Render2D.roundRect(g, x + 6, y, w - 8, row.h(), Theme.R_CONTROL, 0x0DFFFFFF);
        CrispText.text(g, font, "Keybind", x + 10, y + 3, Theme.TEXT_DIM);
        boolean waiting = module == binding;
        boolean bound = module.getKey() >= 0;
        String value = waiting ? "Press a key\u2026" : bound ? KeyUtil.name(module.getKey()) : "None";
        int pw = CrispText.width(font, value) + 10;
        int px = x + w - 8 - pw;
        if (!vanillaBox(g, px, y + 1, pw, 11, waiting || hover)) {
            Render2D.roundRect(g, px, y + 1, pw, 11, Theme.R_CONTROL, waiting ? Theme.withAlpha(accent, 0.35f) : hover ? 0x24FFFFFF : Theme.PILL);
            Render2D.roundOutline(g, px, y + 1, pw, 11, Theme.R_CONTROL, waiting ? Theme.withAlpha(accent, 0.8f) : Theme.HAIRLINE);
        }
        CrispText.text(g, font, value, px + 5, y + 3, waiting || bound ? Theme.TEXT : Theme.TEXT_FAINT);
    }

    /** Width of the "Edit HUD" button - its icon, its label and even padding on both sides. */
    private int hudBtnW() {
        return 9 + 5 + CrispText.width(font, "Edit HUD") + 22;
    }

    /** The search bar and the gear next to it, {x, y, w, h} - LoverFella Mode's figures stay off them. */
    private List<int[]> searchBarRects() {
        int w = 180;
        return List.of(new int[]{(vw - w) / 2, 9, w + 5 + GEAR + HUD_BTN_GAP + hudBtnW(), 18});
    }

    private void drawSearchBar(GuiGraphicsExtractor g) {
        int w = 180;
        int h = 18;
        int x = (vw - w) / 2;
        int y = 9;
        int accent = Theme.accent();
        boolean vanilla = VanillaStyle.on();
        if (vanilla) {
            VanillaStyle.field(g, x, y, w, h, !search.isEmpty());
        } else {
            Render2D.shadow(g, x, y, w, h, 9);
            Render2D.roundRect(g, x, y, w, h, 9, Theme.header());
            Render2D.roundOutline(g, x, y, w, h, 9, search.isEmpty() ? Theme.BORDER : Theme.withAlpha(accent, 0.9f));
        }
        Render2D.icon(g, "search", x + 8, y + 4, 10, search.isEmpty() ? Theme.TEXT_FAINT : Theme.TEXT_DIM);
        // Blinking text cursor: the box is always ready to type in
        boolean blink = (System.currentTimeMillis() / 530) % 2 == 0;
        int textX = x + 20;
        if (search.isEmpty()) {
            CrispText.text(g, font, "Type to search modules", textX + 3, y + 5, Theme.TEXT_FAINT);
            if (blink && editing == null && binding == null) g.fill(textX, y + 4, textX + 1, y + 13, accent);
        } else {
            CrispText.text(g, font, search, textX, y + 5, Theme.TEXT);
            int cx = textX + CrispText.width(font, search) + 1;
            if (blink && editing == null && binding == null) g.fill(cx, y + 4, cx + 1, y + 13, accent);
            CrispText.text(g, font, "Esc", x + w - 6 - CrispText.width(font, "Esc"), y + 5, Theme.TEXT_FAINT);
        }

        // Settings (gear) button right of the search bar
        int gx = x + w + 5;
        // Only lit while the Settings are really what you are looking at (window layout: the Settings tab;
        // panel layout: the Settings panel is shown) - it used to stay lit after leaving them, which looked like
        // you were still in the settings
        boolean inSettings = settingsShownNow();
        boolean overGear = lastMx >= gx && lastMx < gx + GEAR && lastMy >= y && lastMy < y + h;
        if (vanilla) {
            VanillaStyle.buttonBg(g, gx, y, GEAR, h, inSettings || overGear);
        } else {
            Render2D.shadow(g, gx, y, GEAR, h, 9);
            Render2D.roundRect(g, gx, y, GEAR, h, 9, inSettings ? Theme.withAlpha(accent, 0.55f) : Theme.header());
            Render2D.roundOutline(g, gx, y, GEAR, h, 9, inSettings ? accent : Theme.BORDER);
        }
        // Gear icon, centred in the round button
        int iconSize = 12;
        Render2D.icon(g, "gear", gx + (GEAR - iconSize) / 2, y + (h - iconSize) / 2, iconSize, inSettings ? Theme.TEXT : Theme.TEXT_DIM);

        // "Edit HUD" - opens the HUD editor (move / style every HUD element, the theme). as wide as
        // its label needs, the icon and the text centred together, the text on the same line as the search text
        int hx = gx + GEAR + HUD_BTN_GAP;
        int bw = hudBtnW();
        boolean overHud = lastMx >= hx && lastMx < hx + bw && lastMy >= y && lastMy < y + h;
        if (vanilla) {
            VanillaStyle.buttonBg(g, hx, y, bw, h, overHud);
        } else {
            Render2D.shadow(g, hx, y, bw, h, 9);
            Render2D.roundRect(g, hx, y, bw, h, 9, overHud ? Theme.withAlpha(accent, 0.40f) : Theme.header());
            Render2D.roundOutline(g, hx, y, bw, h, 9, overHud ? accent : Theme.BORDER);
        }
        int tw = CrispText.width(font, "Edit HUD");
        int cx = hx + (bw - (9 + 5 + tw)) / 2;
        Render2D.icon(g, "pin", cx, y + (h - 9) / 2, 9, overHud ? Theme.TEXT : accent);
        CrispText.text(g, font, "Edit HUD", cx + 9 + 5, y + 5, overHud ? Theme.TEXT : Theme.TEXT_SECONDARY);
    }

    private void drawTooltip(GuiGraphicsExtractor g, int mx, int my) {
        ClickGUI gui = settings();
        if (dragging != null || slider != null || (gui != null && !gui.showTooltips())) return;
        String text = hovered != null ? hovered.getDescription() : hoveredSetting != null ? hoveredSetting.getHelp() : null;
        if (text == null || text.isEmpty()) return;
        long shownFor = System.currentTimeMillis() - hoverStart - 350;
        if (shownFor < 0) return;
        float a = Math.min(1f, shownFor / 120f); // quick fade in instead of popping up
        drawTooltipBox(g, font, text, mx, my, vw, vh, a);
    }

    /** A rounded tooltip that wraps long text over several lines (max ~230 virtual px wide). */
    static void drawTooltipBox(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String text, int mx, int my, int vw, int vh, float a) {
        List<String> lines = wrap(font, text, 230);
        int textW = 0;
        for (String line : lines) textW = Math.max(textW, CrispText.width(font, line));
        int w = textW + 14;
        int h = 6 + lines.size() * 10;
        int x = Math.max(2, Math.min(mx + 12, vw - w - 4));
        int y = Math.max(2, Math.min(my + 10, vh - h - 4));
        Render2D.popup(g, x, y, w, h, a);
        for (int i = 0; i < lines.size(); i++) {
            CrispText.text(g, font, lines.get(i), x + 7, y + 4 + i * 10, Theme.fade(Theme.TEXT_SECONDARY, Math.max(0.05f, a)));
        }
    }

    /** Splits text into lines no wider than maxW GUI pixels (plain font widths, for normal-scale windows). */
    static List<String> wrapPlain(net.minecraft.client.gui.Font font, String text, int maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tryLine = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && font.width(tryLine) > maxW) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tryLine);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    /** Splits text into lines no wider than maxW (virtual px), at spaces. */
    static List<String> wrap(net.minecraft.client.gui.Font font, String text, int maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tryLine = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && CrispText.width(font, tryLine) > maxW) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tryLine);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private void setHover(Module module, Setting<?> setting) {
        if (module != hovered || setting != hoveredSetting) {
            hovered = module;
            hoveredSetting = setting;
            hoverStart = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------------ app layout (Settings > Menu Layout: Window)

    /** Tabs in the sidebar, in order. */
    private static final ModuleCategory[] ALL_APP_TABS = {ModuleCategory.RENDER, ModuleCategory.PLAYER, ModuleCategory.MISC,
        ModuleCategory.LOVERCRAFT, ModuleCategory.CLIENT};

    /** The sidebar tabs. */
    private static List<ModuleCategory> appTabs() {
        List<ModuleCategory> tabs = new ArrayList<>();
        for (ModuleCategory c : ALL_APP_TABS) if (c.active()) tabs.add(c);
        return tabs;
    }
    private static final int SIDEBAR_W = 108;
    private static final int LIST_W = 172;
    private static final int APP_HEADER = 22;
    private static final int TAB_H = 20;
    private static ModuleCategory appCategory = ModuleCategory.RENDER;
    private static String appModule;
    private static int appModScroll;
    private static int appSetScroll;
    private static int appX = Integer.MIN_VALUE;
    private static int appY;
    private boolean appDragging;
    private int appDragDX;
    private int appDragDY;
    /** Rows of the settings pane drawn last frame (for clicks and hover). */
    private final List<Row> appRows = new ArrayList<>();
    /** Module list rows drawn last frame: module, and its rectangle {x, y, w, h}. */
    private final List<Module> appListModules = new ArrayList<>();
    private final List<int[]> appListRects = new ArrayList<>();
    private int appSetContentH;
    private int appListContentH;

    /** Whether the Settings are on screen right now (the gear only glows then). */
    private boolean settingsShownNow() {
        return appMode() ? appCategory == ModuleCategory.CLIENT : showSettings;
    }

    private boolean appMode() {
        ClickGUI gui = settings();
        return gui != null && gui.windowLayout();
    }

    /** {x, y, w, h} of the app window, in virtual pixels. */
    private int[] appBounds() {
        int w = Math.min(vw - 12, 640);
        int h = Math.min(vh - TOP - 8, 360);
        w = Math.max(Math.min(vw - 4, 420), w);
        h = Math.max(Math.min(vh - TOP, 200), h);
        if (appX == Integer.MIN_VALUE) {
            // First time: right of the Quick Panel when there is room, otherwise centred
            int quickRight = 0;
            com.relaxedqol.client.module.modules.misc.QuickPanel qp =
                RelaxedQolClient.enabled(com.relaxedqol.client.module.modules.misc.QuickPanel.class);
            if (qp != null) quickRight = (qp.isPlaced() ? qp.getX() : 6) + Math.round(ScreenPanelOverlay.panelWidth() * qp.getScalePercent() / 100f) + GAP;
            appX = quickRight + w <= vw - 6 ? Math.max(quickRight, (vw - w) / 2) : (vw - w) / 2;
            appY = TOP;
        }
        int x = Math.max(0, Math.min(appX, vw - w));
        int y = Math.max(0, Math.min(appY, vh - APP_HEADER));
        return new int[]{x, y, w, h};
    }

    /** Modules shown in the middle column: the tab's modules, or every match while searching. */
    private List<Module> appModules() {
        if (!appCategory.active()) appCategory = appTabs().get(0);
        if (!search.isEmpty()) {
            List<Module> all = new ArrayList<>();
            for (ModuleCategory category : appTabs()) all.addAll(visibleModules(category));
            return all;
        }
        return visibleModules(appCategory);
    }

    private Module appSelected(List<Module> modules) {
        for (Module m : modules) if (m.getName().equals(appModule)) return m;
        Module first = modules.isEmpty() ? null : modules.get(0);
        appModule = first != null ? first.getName() : null;
        return first;
    }

    private void drawApp(GuiGraphicsExtractor g, int mx, int my) {
        int[] b = appBounds();
        int x = b[0];
        int y = b[1];
        int w = b[2];
        int h = b[3];
        int accent = Theme.accent();
        appRows.clear();
        appListModules.clear();
        appListRects.clear();
        Module hoverModule = null;
        Setting<?> hoverSetting = null;

        Render2D.shadow(g, x, y, w, h, RADIUS);
        Render2D.roundRect(g, x, y, w, h, RADIUS, Theme.panel());
        // Title bar (drag to move)
        Render2D.roundRectTop(g, x, y, w, APP_HEADER, RADIUS, Theme.header());
        Render2D.headerSheen(g, x, y, w, APP_HEADER, RADIUS, accent);
        Render2D.icon(g, "gear", x + 8, y + 6, 10, accent);
        CrispText.text(g, font, Component.literal("RELAXED QOL").withStyle(ChatFormatting.BOLD), x + 22, y + 7, Theme.TEXT_DIM);
        int on = 0;
        for (Module m : RelaxedQolClient.moduleManager.getModules()) {
            if (m.isEnabled()) on++;
        }
        CrispText.right(g, font, on + " on", x + w - 10, y + 7, Theme.TEXT_FAINT);
        Render2D.headerLine(g, x, y + APP_HEADER - 1, w, accent);
        Render2D.underGlow(g, x + 1, y + APP_HEADER, w - 2, accent);

        int bodyY = y + APP_HEADER;
        int bodyH = h - APP_HEADER;

        // ---- Sidebar: categories
        Render2D.roundRectBottom(g, x, bodyY, SIDEBAR_W, bodyH, RADIUS, 0x26000000);
        int ty = bodyY + 6;
        for (ModuleCategory category : appTabs()) {
            boolean sel = search.isEmpty() && category == appCategory;
            boolean hover = mx >= x + 4 && mx < x + SIDEBAR_W - 4 && my >= ty && my < ty + TAB_H - 2;
            if (sel || hover) {
                Render2D.roundRect(g, x + 4, ty, SIDEBAR_W - 8, TAB_H - 2, ROW_R, sel ? Theme.withAlpha(accent, 0.18f) : 0x12FFFFFF);
            }
            if (sel) Render2D.roundRect(g, x + 4, ty + 4, 2, TAB_H - 10, 1, accent);
            Render2D.icon(g, category.iconSprite(), x + 11, ty + 4, 10, sel ? accent : Theme.TEXT_DIM);
            CrispText.text(g, font, category.getName(), x + 26, ty + 5, sel ? Theme.TEXT : Theme.TEXT_DIM);
            if (category != ModuleCategory.CLIENT) {
                int total = 0;
                int enabled = 0;
                for (Module m : visibleModulesAll(category)) {
                    total++;
                    if (m.isEnabled()) enabled++;
                }
                String count = enabled > 0 ? String.valueOf(enabled) : "";
                if (!count.isEmpty()) {
                    int cw = Math.max(12, CrispText.width(font, count) + 6);
                    Render2D.roundRect(g, x + SIDEBAR_W - 10 - cw, ty + 3, cw, 12, 6, sel ? Theme.withAlpha(accent, 0.35f) : 0x1AFFFFFF);
                    CrispText.centered(g, font, count, x + SIDEBAR_W - 10 - cw / 2, ty + 5, sel ? Theme.TEXT : Theme.TEXT_DIM);
                }
            }
            ty += TAB_H;
        }

        // ---- Middle: module list
        int lx = x + SIDEBAR_W + 4;
        int lw = Math.min(LIST_W, (w - SIDEBAR_W) / 2);
        List<Module> modules = appModules();
        Module selected = appSelected(modules);
        String heading = search.isEmpty() ? appCategory.getName().toUpperCase(Locale.ROOT) : "RESULTS FOR \"" + search + "\"";
        CrispText.text(g, font, Component.literal(heading).withStyle(ChatFormatting.BOLD), lx + 4, bodyY + 6, Theme.TEXT_FAINT);
        int listTop = bodyY + 18;
        int listH = bodyH - 22;
        appListContentH = modules.size() * MODULE_H + 2;
        appModScroll = Math.max(0, Math.min(Math.max(0, appListContentH - listH), appModScroll));
        g.enableScissor(lx, listTop, lx + lw, listTop + listH);
        int ry = listTop - appModScroll;
        for (Module module : modules) {
            if (ry + MODULE_H >= listTop && ry <= listTop + listH) {
                boolean hover = mx >= lx && mx < lx + lw && my >= Math.max(ry, listTop) && my < Math.min(ry + MODULE_H, listTop + listH);
                if (hover) hoverModule = module;
                drawAppModule(g, module, lx, ry, lw, module == selected, hover, accent, mx);
                appListModules.add(module);
                appListRects.add(new int[]{lx, Math.max(ry, listTop), lw, Math.min(ry + MODULE_H, listTop + listH) - Math.max(ry, listTop)});
            }
            ry += MODULE_H;
        }
        g.disableScissor();
        if (modules.isEmpty()) CrispText.text(g, font, "Nothing found", lx + 6, listTop + 4, Theme.TEXT_FAINT);
        appScrollBar(g, lx + lw - 3, listTop, listH, appListContentH, appModScroll, accent);

        // ---- Right: the selected module's settings
        int sx = lx + lw + 6;
        int sw = x + w - sx - 6;
        g.fill(sx - 3, bodyY + 6, sx - 2, bodyY + bodyH - 6, 0x18FFFFFF);
        if (selected != null) {
            int hy = bodyY + 6;
            boolean settingsOnly = selected.isSettingsOnly();
            String title = selected instanceof ClickGUI ? "Menu & Client" : selected.displayName();
            CrispText.text(g, font, Component.literal(title).withStyle(ChatFormatting.BOLD), sx + 6, hy, Theme.TEXT);
            if (!settingsOnly) {
                // On/off (or Run) right in the header
                boolean action = selected instanceof ActionModule;
                if (action) {
                    boolean hot = mx >= sx + sw - 38 && mx < sx + sw - 4 && my >= hy - 2 && my < hy + 10;
                    Render2D.roundRect(g, sx + sw - 38, hy - 2, 34, 12, Theme.R_CONTROL, hot ? Theme.withAlpha(accent, 0.75f) : Theme.withAlpha(accent, 0.4f));
                    CrispText.centered(g, font, "Run", sx + sw - 21, hy, Theme.TEXT);
                } else {
                    float p = ANIM.smooth(keys(selected).on, selected.isEnabled() ? 1 : 0, 18);
                    Render2D.toggleSwitch(g, sx + sw - 22, hy, p, accent);
                }
            }
            hy += 12;
            String description = selected.getDescription();
            if (selected instanceof ClickGUI) description = "How the menu looks and works. Hover a setting to see what it does.";
            for (String line : wrap(font, description, sw - 14)) {
                CrispText.text(g, font, line, sx + 6, hy, Theme.TEXT_FAINT);
                hy += 10;
            }
            hy += 4;
            int setTop = hy;
            int setH = bodyY + bodyH - 4 - setTop;
            int content = 0;
            for (Setting<?> setting : selected.getSettings()) if (!setting.isHidden()) content += heightOf(setting);
            boolean bindRow = !settingsOnly;
            if (bindRow) content += ROW_H;
            appSetContentH = content + 4;
            appSetScroll = Math.max(0, Math.min(Math.max(0, appSetContentH - setH), appSetScroll));
            int clip0 = setTop;
            int clip1 = setTop + setH;
            Render2D.roundRect(g, sx, setTop - 2, sw, setH + 2, ROW_R, Theme.settingsBg());
            g.enableScissor(sx, clip0, sx + sw, clip1);
            int cy = setTop - appSetScroll;
            for (Setting<?> setting : selected.getSettings()) {
                if (setting.isHidden()) continue;
                int rh = heightOf(setting);
                boolean visible = cy + rh > clip0 && cy < clip1;
                Row row = new Row(Kind.SETTING, sx - 4, cy, sw - 2, rh, selected, setting, visible, clip0, clip1);
                if (visible) {
                    boolean hover = row.contains(mx, my) && my >= clip0 && my < clip1;
                    if (hover) hoverSetting = setting;
                    drawSetting(g, row, hover, accent, mx);
                    appRows.add(row);
                }
                cy += rh;
            }
            if (bindRow) {
                Row row = new Row(Kind.BIND, sx - 4, cy, sw - 2, ROW_H, selected, null, cy + ROW_H > clip0 && cy < clip1, clip0, clip1);
                if (row.visible()) {
                    drawBindRow(g, row, row.contains(mx, my), accent);
                    appRows.add(row);
                }
            }
            g.disableScissor();
            if (content == 0 && !bindRow) CrispText.text(g, font, "No settings", sx + 6, setTop + 4, Theme.TEXT_FAINT);
            appScrollBar(g, sx + sw - 3, setTop, setH, appSetContentH, appSetScroll, accent);
        }
        if (VanillaStyle.on()) VanillaStyle.bevel(g, x, y, w, h);
        else Render2D.roundOutline(g, x, y, w, h, RADIUS, Theme.BORDER);
        setHover(hoverModule, hoverSetting);
    }

    /** Every module of a category (not filtered by the search), for the sidebar counts. */
    private List<Module> visibleModulesAll(ModuleCategory category) {
        List<Module> result = new ArrayList<>();
        if (!category.active()) return result;
        for (Module module : RelaxedQolClient.moduleManager.getModulesByCategory(category)) {
            result.add(module);
        }
        return result;
    }

    private void appScrollBar(GuiGraphicsExtractor g, int x, int y, int h, int content, int scroll, int accent) {
        if (content <= h || h <= 6) return;
        int thumbH = Math.max(14, h * h / content);
        int thumbY = y + (h - thumbH) * scroll / Math.max(1, content - h);
        Render2D.roundRect(g, x, thumbY, 3, thumbH, 2, 0x4DFFFFFF);
    }

    /** One module in the middle column: name, a switch (or Run) on the right; the picked one is outlined. */
    private void drawAppModule(GuiGraphicsExtractor g, Module module, int x, int y, int w, boolean selected, boolean hover, int accent, int mx) {
        Keys keys = keys(module);
        float on = ANIM.get(keys.on, module.isEnabled() ? 1 : 0);
        float hov = ANIM.smooth(keys.hov, hover ? 1 : 0, 28);
        boolean action = module instanceof ActionModule;
        if (action) {
            long since = System.currentTimeMillis() - FLASH.getOrDefault(module.getName(), 0L);
            on = since < 300 ? 1f - since / 300f : 0f;
        }
        int rx = x + 2;
        int rw = w - 8;
        int rh = MODULE_H - 2;
        // Flat rows like the panel layout - the picked module gets a soft fill and an accent marker; on / off is
        // the switch on the right (no outlines around every row)
        int fill = selected ? Theme.withAlpha(accent, 0.18f + 0.05f * hov) : Theme.withAlpha(0xFFFFFFFF, 0.07f * hov);
        Render2D.roundRect(g, rx, y + 1, rw, rh, ROW_R, fill);
        if (selected) Render2D.roundRect(g, rx + 2, y + 5, 2, rh - 8, 1, accent);
        String label = module == binding ? "Press a key\u2026" : module.displayName();
        String cap = module.getKey() >= 0 && module != binding ? KeyUtil.name(module.getKey()) : null;
        int capW = cap != null ? CrispText.width(font, cap) + 8 : 0;
        int room = rw - 34 - (capW > 0 ? capW + 4 : 0);
        String shown = fitText(label, room);
        int color = module == binding ? accent : Theme.mix(Theme.TEXT_DIM, Theme.TEXT, Math.max(on, Math.max(hov * 0.8f, selected ? 1f : 0f)));
        CrispText.text(g, font, shown, rx + 8, y + 5, color);
        if (cap != null) {
            // The bound key as a small key cap after the name
            int cx = rx + 8 + CrispText.width(font, shown) + 5;
            if (!vanillaBox(g, cx, y + 3, capW, rh - 4, false)) {
                Render2D.roundRect(g, cx, y + 3, capW, rh - 4, Theme.R_CONTROL, KEYCAP_FILL);
                Render2D.roundOutline(g, cx, y + 3, capW, rh - 4, Theme.R_CONTROL, KEYCAP_EDGE);
            }
            CrispText.text(g, font, cap, cx + 4, y + 5, Theme.TEXT);
        }
        if (module.isSettingsOnly()) return;
        if (action) {
            CrispText.text(g, font, "\u25B6", rx + rw - 14, y + 5, Theme.mix(accent, Theme.TEXT, on));
        } else {
            Render2D.toggleSwitch(g, rx + rw - 22, y + 5, on, accent);
        }
    }

    private boolean appClicked(double mx, double my, int button) {
        // Search bar and the gear button above the window work the same as in the panel layout
        int sbw = 180;
        int sbx = (vw - sbw) / 2;
        if (button == 0 && my >= 9 && my < 27) {
            if (mx >= sbx && mx < sbx + sbw) return true;
            int hudX = sbx + sbw + 5 + GEAR + HUD_BTN_GAP;
            if (mx >= hudX && mx < hudX + hudBtnW()) {
                HudEditorScreen.open();
                return true;
            }
            if (mx >= sbx + sbw + 5 && mx < sbx + sbw + 5 + GEAR) {
                if (appCategory == ModuleCategory.CLIENT) {
                    appCategory = ModuleCategory.RENDER;
                    appModule = null;
                } else {
                    openSettings();
                }
                return true;
            }
        }
        int[] b = appBounds();
        int x = b[0];
        int y = b[1];
        int w = b[2];
        int h = b[3];
        if (mx < x || mx >= x + w || my < y || my >= y + h) return false;
        if (my < y + APP_HEADER) {
            if (button == 0) {
                appDragging = true;
                appX = x;
                appY = y;
                appDragDX = (int) Math.round(mx) - x;
                appDragDY = (int) Math.round(my) - y;
            }
            return true;
        }
        // Sidebar tabs
        int ty = y + APP_HEADER + 6;
        for (ModuleCategory category : appTabs()) {
            if (mx >= x + 4 && mx < x + SIDEBAR_W - 4 && my >= ty && my < ty + TAB_H - 2) {
                if (!search.isEmpty()) searchBox.setValue("");
                appCategory = category;
                appModule = null;
                appModScroll = 0;
                appSetScroll = 0;
                return true;
            }
            ty += TAB_H;
        }
        // Module list
        for (int i = 0; i < appListRects.size(); i++) {
            int[] r = appListRects.get(i);
            if (mx < r[0] || mx >= r[0] + r[2] || my < r[1] || my >= r[1] + r[3]) continue;
            Module module = appListModules.get(i);
            boolean onSwitch = mx >= r[0] + r[2] - 34;
            if (button == 2) {
                binding = module == binding ? null : module;
            } else if (button == 1 || (button == 0 && onSwitch && !module.isSettingsOnly())) {
                // Right click or the switch: turn it on / off (Run for one-shot modules)
                if (module instanceof ActionModule) FLASH.put(module.getName(), System.currentTimeMillis());
                if (!module.isSettingsOnly()) module.toggle();
            } else if (button == 0) {
                if (!module.getName().equals(appModule)) appSetScroll = 0;
                appModule = module.getName();
            }
            return true;
        }
        // Header switch / Run of the selected module
        Module selected = appSelected(appModules());
        int lw = Math.min(LIST_W, (w - SIDEBAR_W) / 2);
        int sx = x + SIDEBAR_W + 4 + lw + 6;
        int sw = x + w - sx - 6;
        int hy = y + APP_HEADER + 6;
        if (selected != null && !selected.isSettingsOnly() && button == 0 && mx >= sx + sw - 40 && mx < sx + sw && my >= hy - 3 && my < hy + 11) {
            if (selected instanceof ActionModule) FLASH.put(selected.getName(), System.currentTimeMillis());
            selected.toggle();
            return true;
        }
        // Settings rows
        for (Row row : appRows) {
            if (!row.contains(mx, my) || my < row.clipY0() || my >= row.clipY1()) continue;
            if (row.kind() == Kind.BIND) {
                if (button == 0) binding = row.module() == binding ? null : row.module();
                else if (button == 1) row.module().setKey(-1);
            } else {
                clickSetting(row, mx, my, button);
            }
            return true;
        }
        return true; // inside the window: never fall through to anything behind it
    }

    private boolean appScrolled(double mx, double my, double amount) {
        for (Row row : appRows) {
            if (row.kind() == Kind.SETTING && row.contains(mx, my) && my >= row.clipY0() && my < row.clipY1()
                && row.setting() == scrollFocus && (row.setting().isNumber() || row.setting().isMode())) {
                row.setting().cycle(amount > 0 ? 1 : -1);
                return true;
            }
        }
        int[] b = appBounds();
        int x = b[0];
        int w = b[2];
        int lw = Math.min(LIST_W, (w - SIDEBAR_W) / 2);
        int lx = x + SIDEBAR_W + 4;
        if (mx >= lx && mx < lx + lw) {
            appModScroll = Math.max(0, appModScroll - (int) Math.signum(amount) * 20);
            return true;
        }
        if (mx >= lx + lw && mx < x + w) {
            appSetScroll = Math.max(0, appSetScroll - (int) Math.signum(amount) * 20);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return mouseClicked$smooth(event, doubleClick);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private boolean mouseClicked$smooth(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x() / scale;
        double my = event.y() / scale;
        int button = event.button();
        if (editing != null) commitEdit();
        scrollFocus = null; // set again by clickSetting when a number / mode setting is clicked
        if (modeMenu != null) {
            // A click on an option picks it; anywhere else just closes the list
            int index = modeMenuIndex(mx, my);
            Setting<?> menu = modeMenu;
            modeMenu = null;
            if (index >= 0 && button == 0) {
                @SuppressWarnings("unchecked")
                Setting<String> ms = (Setting<String>) menu;
                ms.setValue(ms.getModes().get(index));
                ConfigManager.save(ConfigManager.DEFAULT);
            }
            return true;
        }

        if (ScreenPanelOverlay.mouseClicked(event)) return true;

        // The LoverCraft badge behind the menu opens LoverFella's YouTube channel
        if (button == 0 && LoverFx.badgeHit(mx, my) && !overMenu(mx, my)) {
            LoverFx.openYoutube();
            return true;
        }

        // Cards: drag to move, bottom-right corner to resize - but the menu wins where they overlap (cards sit
        // behind the menu while you work in it)
        if (button == 0 && !overMenu(mx, my)) {
            float hudScale = OverlayRenderer.hudScale();
            double hx = event.x() / hudScale;
            double hy = event.y() / hudScale;
            List<HudWindow> windows = OverlayRenderer.visibleWindows();
            for (int i = windows.size() - 1; i >= 0; i--) {
                HudWindow window = windows.get(i);
                if (window.onGrip(hx, hy)) {
                    resizingWindow = window;
                    return true;
                }
                if (window.contains(hx, hy)) {
                    movingWindow = window;
                    windowOffsetX = (int) hx - window.getX();
                    windowOffsetY = (int) hy - window.getY();
                    return true;
                }
            }
        }

        if (appMode()) return appClicked(mx, my, button) || super.mouseClicked(event, doubleClick);

        List<Panel> panels = layout();
        if (button == 0) {
            for (int i = panels.size() - 1; i >= 0; i--) {
                Panel p = panels.get(i);
                if (p.collapsed) continue;
                int gx = p.x + p.w;
                int gy = p.bodyY + p.bodyH;
                if (mx >= gx - GRIP && mx < gx + 2 && my >= gy - GRIP && my < gy + 2) {
                    resizing = p.category;
                    POSITIONS.put(p.category, new int[]{p.x, p.y}); // touching a panel keeps it where you see it
                    return true;
                }
            }
            for (int i = panels.size() - 1; i >= 0; i--) {
                Panel p = panels.get(i);
                if (!onScrollBar(p, mx, my)) continue;
                int[] t = thumb(p);
                if (my >= t[0] && my < t[0] + t[1]) {
                    scrollGrab = my - t[0];
                } else {
                    scrollGrab = t[1] / 2.0;
                    dragScrollTo(p, my, scrollGrab);
                }
                scrollDragging = p.category;
                return true;
            }
            int sw = 180;
            int sx = (vw - sw) / 2;
            if (mx >= sx && mx < sx + sw && my >= 9 && my < 27) {
                return true; // search box: just typing works, nothing else to do
            }
            int hudX = sx + sw + 5 + GEAR + HUD_BTN_GAP;
            if (mx >= hudX && mx < hudX + hudBtnW() && my >= 9 && my < 27) {
                HudEditorScreen.open();
                return true;
            }
            if (mx >= sx + sw + 5 && mx < sx + sw + 5 + GEAR && my >= 9 && my < 27) {
                showSettings = !showSettings;
                if (showSettings) {
                    EXPANDED.add("ClickGUI");
                    placeSettings = true;
                }
                return true;
            }
        }
        Panel header = headerAt(panels, mx, my);
        if (header != null) {
            if (button == 0) {
                dragging = header.category;
                POSITIONS.put(header.category, new int[]{header.x, header.y});
                dragOffsetX = (int) Math.round(mx) - header.x;
                dragOffsetY = (int) Math.round(my) - header.y;
            } else if (button == 1) {
                if (!COLLAPSED.remove(header.category)) COLLAPSED.add(header.category);
            }
            return true;
        }

        Row row = rowAt(panels, mx, my);
        if (row == null) return super.mouseClicked(event, doubleClick);

        switch (row.kind()) {
            case MODULE -> {
                Module module = row.module();
                int[] p = pills(row);
                boolean onBind = mx >= p[0] && mx < p[0] + p[1];
                boolean onArrow = p[3] > 0 && mx >= p[2] && mx < p[2] + p[3];
                boolean expandable = hasSettings(module);
                if (button == 0 && onBind) {
                    binding = module == binding ? null : module;
                } else if (button == 1 && onBind) {
                    module.setKey(-1);
                } else if (button == 0 && (onArrow || (module.isSettingsOnly() && expandable))) {
                    if (!EXPANDED.remove(module.getName())) expand(module);
                } else if (button == 0) {
                    if (module instanceof ActionModule) FLASH.put(module.getName(), System.currentTimeMillis());
                    module.toggle();
                } else if (button == 1 && expandable) {
                    if (!EXPANDED.remove(module.getName())) expand(module);
                } else if (button == 2) {
                    binding = module == binding ? null : module;
                }
            }
            case BIND -> {
                if (button == 0) binding = row.module() == binding ? null : row.module();
                else if (button == 1) row.module().setKey(-1);
            }
            case SETTING -> clickSetting(row, mx, my, button);
        }
        return true;
    }

    /** Opens a module's settings and makes sure all of them come into view. */
    private static void expand(Module module) {
        EXPANDED.add(module.getName());
        revealName = module.getName();
        revealUntil = System.currentTimeMillis() + 700;
    }

    private void openModeMenu(Setting<?> setting, int rx, int ry, int rw, int rh) {
        if (modeMenu == setting) {
            modeMenu = null;
            return;
        }
        int widest = 60;
        for (String m : setting.getModes()) widest = Math.max(widest, CrispText.width(font, m) + 26);
        menuW = Math.min(widest, Math.max(80, vw - 8));
        menuX = Math.max(4, Math.min(rx + rw - menuW - 4, vw - menuW - 4));
        int h = setting.getModes().size() * MENU_ROW + 6;
        menuY = ry + Math.min(rh, 14);
        if (menuY + h > vh - 4) menuY = Math.max(4, ry - h);
        modeMenu = setting;
    }

    private int modeMenuIndex(double mx, double my) {
        if (modeMenu == null) return -1;
        int n = modeMenu.getModes().size();
        if (mx < menuX || mx >= menuX + menuW || my < menuY + 3 || my >= menuY + 3 + n * MENU_ROW) return -1;
        return (int) ((my - menuY - 3) / MENU_ROW);
    }

    private void drawModeMenu(GuiGraphicsExtractor g, int mx, int my) {
        java.util.List<String> modes = modeMenu.getModes();
        int h = modes.size() * MENU_ROW + 6;
        int accent = Theme.accent();
        Render2D.popup(g, menuX, menuY, menuW, h);
        int hover = modeMenuIndex(mx, my);
        Object current = modeMenu.getValue();
        for (int i = 0; i < modes.size(); i++) {
            int ry = menuY + 3 + i * MENU_ROW;
            boolean sel = modes.get(i).equals(current);
            if (i == hover) Render2D.roundRect(g, menuX + 3, ry, menuW - 6, MENU_ROW, Theme.R_CONTROL, 0x1FFFFFFF);
            if (sel) Render2D.checkmark(g, menuX + 6, ry + 3, accent);
            CrispText.text(g, font, modes.get(i), menuX + 17, ry + 3, sel || i == hover ? Theme.TEXT : Theme.TEXT_DIM);
        }
    }

    private void clickSetting(Row row, double mx, double my, int button) {
        Setting<?> setting = row.setting();
        // Clicked number / mode settings take the mouse wheel (see scrollFocus)
        scrollFocus = setting.isNumber() || setting.isMode() ? setting : null;
        if (setting.isKey()) {
            var key = (com.relaxedqol.client.setting.KeySetting) setting;
            if (button == 1) key.reset();
            else bindingSetting = bindingSetting == key ? null : key;
        } else if (setting.isAction()) {
            ((ActionSetting) setting).run();
        } else if (setting.isBoolean()) {
            setting.cycle(1);
        } else if (setting.isMode()) {
            if (button == 1) {
                setting.cycle(-1);
            } else {
                openModeMenu(setting, row.x(), row.y(), row.w(), row.h());
            }
        } else if (setting.isNumber()) {
            String value = setting.display();
            int valueX = row.x() + row.w() - 8 - CrispText.width(font, value);
            if (button == 1) {
                setting.reset();
            } else if (mx >= valueX - 4 && my < row.y() + 13) {
                // Click the number to type an exact value (Enter = OK, Esc = cancel)
                editing = setting;
                editBuffer = "";
            } else {
                slider = setting;
                sliderX = row.x() + 10;
                sliderW = row.w() - 20;
                setting.setFraction((mx - sliderX) / sliderW);
            }
        } else if (setting.isColor()) {
            ColorPickerScreen.open(this, (com.relaxedqol.client.setting.ColorSetting) setting,
                () -> ConfigManager.save(ConfigManager.DEFAULT));
        } else if (setting.isList()) {
            minecraft.gui.setScreen(new ListPickerScreen(this, row.module(), (ListSetting) setting));
        } else if (setting.isText() && setting.usesPrompt()) {
            Setting<?> text = setting;
            String help = text.getHelp() != null ? text.getHelp() : "";
            TextPrompt.open(this, text.getName(), help, text.display(), value -> {
                text.parse(value.trim());
                com.relaxedqol.client.ConfigManager.save(com.relaxedqol.client.ConfigManager.DEFAULT);
            });
        } else if (setting.isText()) {
            editing = setting;
            editBuffer = setting.display();
        }
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return mouseReleased$smooth(event);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private boolean mouseReleased$smooth(MouseButtonEvent event) {
        ScreenPanelOverlay.mouseReleased();
        appDragging = false;
        dragging = null;
        resizing = null;
        scrollDragging = null;
        movingWindow = null;
        resizingWindow = null;
        slider = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return mouseDragged$smooth(event, dx, dy);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private boolean mouseDragged$smooth(MouseButtonEvent event, double dx, double dy) {
        double mx = event.x() / scale;
        double my = event.y() / scale;
        if (ScreenPanelOverlay.mouseDragged(event)) return true;
        if (appDragging) {
            appX = (int) Math.round(mx) - appDragDX;
            appY = (int) Math.round(my) - appDragDY;
            return true;
        }
        if (scrollDragging != null) {
            for (Panel p : layout()) {
                if (p.category == scrollDragging) dragScrollTo(p, my, scrollGrab);
            }
            return true;
        }
        if (movingWindow != null || resizingWindow != null) {
            float hudScale = OverlayRenderer.hudScale();
            double hx = event.x() / hudScale;
            double hy = event.y() / hudScale;
            if (movingWindow != null) movingWindow.moveTo((int) hx - windowOffsetX, (int) hy - windowOffsetY);
            else resizingWindow.resizeTo(hx, hy);
            return true;
        }
        if (resizing != null) {
            int[] pos = POSITIONS.get(resizing);
            int[] size = SIZES.computeIfAbsent(resizing, c -> new int[]{PANEL_W, 0});
            size[0] = Math.max(MIN_W, Math.min(MAX_W, (int) Math.round(mx) - pos[0]));
            size[1] = Math.max(40, (int) Math.round(my) - (pos[1] + HEADER_H));
            return true;
        }
        if (dragging != null) {
            if (dragging == ModuleCategory.CLIENT) settingsMoved = true;
            int[] pos = POSITIONS.get(dragging);
            pos[0] = (int) Math.round(mx) - dragOffsetX;
            pos[1] = (int) Math.round(my) - dragOffsetY;
            return true;
        }
        if (slider != null) {
            slider.setFraction((mx - sliderX) / sliderW);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return mouseScrolled$smooth(x, y, scrollX, scrollY);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private boolean mouseScrolled$smooth(double x, double y, double scrollX, double scrollY) {
        if (modeMenu != null) return true;
        double mx = x / scale;
        double my = y / scale;
        if (appMode()) return appScrolled(mx, my, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
        List<Panel> panels = layout();
        Row row = rowAt(panels, mx, my);
        if (row != null && row.kind() == Kind.SETTING && row.setting() == scrollFocus
            && (row.setting().isNumber() || row.setting().isMode())) {
            row.setting().cycle(scrollY > 0 ? 1 : -1);
            return true;
        }
        Panel panel = panelAt(panels, mx, my);
        if (panel != null && panel.contentH > panel.bodyH) {
            SCROLL.put(panel.category, SCROLL.getOrDefault(panel.category, 0) - (int) Math.signum(scrollY) * 20);
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return keyPressed$smooth(event);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private boolean keyPressed$smooth(KeyEvent event) {
        int key = event.key();
        if (modeMenu != null && key == InputConstants.KEY_ESCAPE) {
            modeMenu = null;
            return true;
        }
        if (ScreenPanelOverlay.isTyping() && ScreenPanelOverlay.keyPressed(event)) return true;
        if (bindingSetting != null) {
            // Esc (or Delete / Backspace) wipes the bind back to its default: no key ("Bind"), or the
            // default key for binds that must have one (the menu key). Any other key becomes the bind.
            if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE) {
                if (bindingSetting.allowsNone()) bindingSetting.setValue(-1);
                else bindingSetting.reset();
            } else {
                bindingSetting.setValue(key);
                Module owner = null;
                for (Module m : RelaxedQolClient.moduleManager.getModules()) if (m.getSettings().contains(bindingSetting)) owner = m;
                com.relaxedqol.client.util.KeyConflicts.check(owner, bindingSetting, key);
            }
            bindingSetting = null;
            swallowCharsUntil = System.currentTimeMillis() + 300;
            if (!search.equals(searchBeforeTyping)) searchBox.setValue(searchBeforeTyping);
            ConfigManager.save(ConfigManager.DEFAULT);
            return true;
        }
        if (binding != null) {
            if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE) {
                binding.setKey(-1);
            } else {
                binding.setKey(key);
                com.relaxedqol.client.util.KeyConflicts.check(binding, null, key);
            }
            binding = null;
            // The letter of this key arrives as a character right after: it must not go into the search bar
            swallowCharsUntil = System.currentTimeMillis() + 300;
            if (!search.equals(searchBeforeTyping)) searchBox.setValue(searchBeforeTyping);
            ConfigManager.save(ConfigManager.DEFAULT);
            return true;
        }
        if (editing != null) {
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                commitEdit();
            } else if (key == InputConstants.KEY_ESCAPE) {
                editing = null;
            } else if (key == InputConstants.KEY_BACKSPACE && !editBuffer.isEmpty()) {
                editBuffer = editBuffer.substring(0, editBuffer.length() - 1);
            } else if (!charEventsSeen) {
                char c = keyChar(event);
                if (c != 0) {
                    typeText(String.valueOf(c));
                    lastKeyChar = c;
                    lastKeyTime = System.currentTimeMillis();
                }
            }
            return true;
        }
        // The menu key closes the menu again
        ClickGUI look = settings();
        if (look != null && key == look.menuKey() && System.currentTimeMillis() - openedAt > 400) {
            onClose();
            return true;
        }
        if (key == InputConstants.KEY_ESCAPE && !search.isEmpty()) {
            searchBox.setValue("");
            return true;
        }
        if (key != InputConstants.KEY_ESCAPE) {
            ensureSearchFocus();
            // Backspace, Ctrl+A/C/V, arrows... handled by the text box
            if (searchBox.keyPressed(event)) return true;
            if (!charEventsSeen) {
                char c = keyChar(event);
                if (c != 0 && typeText(String.valueOf(c))) {
                    lastKeyChar = c;
                    lastKeyTime = System.currentTimeMillis();
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            return charTyped$smooth(event);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    private boolean charTyped$smooth(CharacterEvent event) {
        if (ScreenPanelOverlay.isTyping() && ScreenPanelOverlay.charTyped(event)) return true;
        if (Character.isISOControl(event.codepoint())) return false;
        if (System.currentTimeMillis() < swallowCharsUntil) {
            swallowCharsUntil = 0;
            return true;
        }
        charEventsSeen = true;
        String typed = event.codepointAsString();
        // Skip the character if the key-press fallback already typed it a moment ago
        if (System.currentTimeMillis() - lastKeyTime < 150 && typed.length() == 1
            && Character.toLowerCase(typed.charAt(0)) == Character.toLowerCase(lastKeyChar)) {
            lastKeyTime = 0;
            return true;
        }
        return typeText(typed);
    }

    private boolean typeText(String typed) {
        if (editing != null) {
            if (editBuffer.length() < 120) editBuffer += typed;
            return true;
        }
        if (binding != null || bindingSetting != null) return true;
        if (search.isEmpty() && typed.isBlank()) return false;
        ensureSearchFocus();
        searchBox.insertText(typed);
        search = searchBox.getValue();
        return true;
    }

    private void ensureSearchFocus() {
        if (getFocused() != searchBox) setFocused(searchBox);
        if (!searchBox.isFocused()) searchBox.setFocused(true);
    }

    /** Letter/number for a key press, used only when the game sends no character events. 0 = none. */
    private static char keyChar(KeyEvent event) {
        int key = event.key();
        if (event.hasControlDown()) return 0;
        if (key >= InputConstants.KEY_A && key <= InputConstants.KEY_Z) {
            char c = (char) ('a' + (key - InputConstants.KEY_A));
            return event.hasShiftDown() ? Character.toUpperCase(c) : c;
        }
        if (key >= InputConstants.KEY_0 && key <= InputConstants.KEY_9) return (char) ('0' + (key - InputConstants.KEY_0));
        if (key == InputConstants.KEY_SPACE) return ' ';
        if (key == InputConstants.KEY_MINUS) return event.hasShiftDown() ? '_' : '-';
        if (key == InputConstants.KEY_PERIOD) return '.';
        if (key == InputConstants.KEY_SLASH) return '/';
        return 0;
    }

    private void commitEdit() {
        if (editing != null) editing.parse(editBuffer);
        editing = null;
    }

    @Override
    public void removed() {
        ScreenPanelOverlay.menuClosed();
        minecraft.onTextInputFocusChange(this, false);
        commitEdit();
        ConfigManager.save(ConfigManager.DEFAULT);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
