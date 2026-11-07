package com.relaxedqol.client.gui;

import com.relaxedqol.client.waypoint.WaypointManager;
import com.relaxedqol.client.waypoint.WaypointManager.Waypoint;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The waypoint menu, in the spirit of Lunar Client's. Left: every waypoint of this server (this dimension or
 * all) with its colour, name, coordinates, dimension and distance, and on each row an eye (show / hide in the world),
 * a pencil (edit) and an x (delete, click twice). Right: the editor - name, X / Y / Z (or "Use my spot"), a colour,
 * the dimension and whether it shows. "+ New here" makes one where you stand.
 */
public class WaypointsScreen extends WindowScreen {
    private static final int ROW = 24;
    private static boolean allDimensions;

    private final ScrollBar bar = new ScrollBar();
    private EditBox search;
    private String searchText = "";

    /** The editor: the waypoint being edited (null + creating = a new one). */
    private Waypoint editing;
    private boolean creating;
    private EditBox nameBox;
    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private int color = WaypointManager.PALETTE[5];
    private String dimension = "minecraft:overworld";
    private boolean visible = true;
    private Waypoint deleteArmed;
    private long deleteArmedAt;

    /** Last frame's clickable parts. */
    private final List<Waypoint> rows = new ArrayList<>();
    private final List<int[]> rowRects = new ArrayList<>();
    private final List<int[]> swatches = new ArrayList<>();
    private final List<int[]> dimChips = new ArrayList<>();

    private static final String[] DIMS = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
    private static final String[] DIM_NAMES = {"Overworld", "Nether", "End"};

    public WaypointsScreen(Screen parent) {
        super(parent, Component.literal("Waypoints"), "waypoints", 480, 290);
    }

    @Override
    protected boolean compact() {
        return true;
    }

    @Override
    protected String titleInfo() {
        return WaypointManager.forServer().size() + " on " + WaypointManager.currentServer();
    }

    private boolean editorOpen() {
        return editing != null || creating;
    }

    private int listW() {
        return editorOpen() ? (int) (contentW() * 0.58f) : contentW();
    }

    private int paneX() {
        return contentX() + listW() + 8;
    }

    private int paneW() {
        return contentX() + contentW() - paneX();
    }

    @Override
    protected void initContent() {
        int x = contentX();
        int y = contentY();
        String typed = search != null ? search.getValue() : searchText;
        search = new EditBox(font, x + 6, y + 3, Math.max(60, listW() - 190), 9, Component.literal("Search"));
        search.setBordered(false);
        search.setHint(Component.literal("Search waypoints\u2026"));
        search.setMaxLength(32);
        search.setValue(typed);
        search.setResponder(t -> {
            searchText = t;
            bar.scroll = 0;
        });
        addRenderableWidget(search);
        int right = x + listW();
        pill(() -> "+ New here", right - 70, y, 70, 14, this::newHere, Theme.SUCCESS, () -> creating);
        pill(() -> allDimensions ? "All worlds" : "This world", right - 140, y, 66, 14, () -> {
            allDimensions = !allDimensions;
            bar.scroll = 0;
        }, 0, () -> allDimensions);

        // Keep what was typed when the window is moved / resized
        String keepName = nameBox != null ? nameBox.getValue() : null;
        String keepX = xBox != null ? xBox.getValue() : null;
        String keepY = yBox != null ? yBox.getValue() : null;
        String keepZ = zBox != null ? zBox.getValue() : null;
        nameBox = xBox = yBox = zBox = null;
        if (!editorOpen()) return;
        int px = paneX();
        int pw = paneW();
        int py = y + 20;
        nameBox = field(px + 6, py + 12, pw - 12, keepName != null ? keepName : editing != null ? editing.name() : "", "Name", 32);
        int cw = (pw - 8) / 3;
        xBox = field(px + 6, py + 40, cw - 10, keepX != null ? keepX : editing != null ? String.valueOf(editing.x()) : coord(0), "X", 9);
        yBox = field(px + 6 + cw, py + 40, cw - 10, keepY != null ? keepY : editing != null ? String.valueOf(editing.y()) : coord(1), "Y", 9);
        zBox = field(px + 6 + cw * 2, py + 40, cw - 10, keepZ != null ? keepZ : editing != null ? String.valueOf(editing.z()) : coord(2), "Z", 9);
        pill(() -> "Use my spot", px, py + 56, 70, 13, () -> {
            if (minecraft.player == null) return;
            xBox.setValue(coord(0));
            yBox.setValue(coord(1));
            zBox.setValue(coord(2));
            dimension = WaypointManager.currentDimension();
        }, 0, () -> false);
        pill(() -> visible ? "Shown" : "Hidden", px + 74, py + 56, 50, 13, () -> visible = !visible, Theme.SUCCESS, () -> visible);
        int by = contentY() + contentH() - 14;
        int bw = (pw - 8) / 3;
        pill(() -> creating ? "Add" : "Save", px, by, bw, 14, this::saveEditor, Theme.SUCCESS, () -> false);
        pill(() -> "Cancel", px + bw + 4, by, bw, 14, this::closeEditor, 0, () -> false);
        if (!creating) {
            pill(() -> deleteArmed == editing && System.currentTimeMillis() - deleteArmedAt < 3000 ? "Sure?" : "Delete",
                px + (bw + 4) * 2, by, pw - (bw + 4) * 2, 14, () -> askDelete(editing), Theme.DANGER, () -> false);
        }
    }

    private EditBox field(int x, int y, int w, String value, String hint, int max) {
        EditBox box = new EditBox(font, x, y, w, 9, Component.literal(hint));
        box.setBordered(false);
        box.setMaxLength(max);
        box.setHint(Component.literal(hint));
        box.setValue(value);
        addRenderableWidget(box);
        return box;
    }

    private String coord(int axis) {
        if (minecraft.player == null) return "0";
        int v = axis == 0 ? minecraft.player.getBlockX() : axis == 1 ? minecraft.player.getBlockY() : minecraft.player.getBlockZ();
        return Integer.toString(v);
    }

    // ------------------------------------------------------------------ editor actions

    private void newHere() {
        editing = null;
        creating = true;
        color = WaypointManager.PALETTE[Math.floorMod(WaypointManager.forServer().size(), WaypointManager.PALETTE.length)];
        dimension = WaypointManager.currentDimension();
        visible = true;
        nameBox = xBox = yBox = zBox = null;
        rebuildWidgets();
        if (nameBox != null) {
            nameBox.setValue("Point " + (WaypointManager.forServer().size() + 1));
            setFocused(nameBox);
            nameBox.setFocused(true);
        }
    }

    private void edit(Waypoint w) {
        editing = w;
        creating = false;
        color = w.color();
        dimension = w.dimension();
        visible = w.visible();
        nameBox = xBox = yBox = zBox = null;
        rebuildWidgets();
    }

    private void closeEditor() {
        editing = null;
        creating = false;
        nameBox = xBox = yBox = zBox = null;
        rebuildWidgets();
    }

    private void saveEditor() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) name = "Point " + (WaypointManager.forServer().size() + 1);
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(xBox.getValue().trim());
            y = Integer.parseInt(yBox.getValue().trim());
            z = Integer.parseInt(zBox.getValue().trim());
        } catch (NumberFormatException e) {
            com.relaxedqol.client.util.ChatUtil.send("§cWaypoints: X, Y and Z must be whole numbers");
            return;
        }
        if (creating) {
            WaypointManager.addFull(name, dimension, x, y, z, color, visible);
        } else {
            Waypoint w = editing;
            WaypointManager.update(w, new Waypoint(w.server(), dimension, name, x, y, z, color, w.death(), w.time(), visible));
        }
        closeEditor();
    }

    private void askDelete(Waypoint w) {
        long now = System.currentTimeMillis();
        if (deleteArmed == w && now - deleteArmedAt < 3000) {
            WaypointManager.delete(w);
            deleteArmed = null;
            if (editing == w) closeEditor();
        } else {
            deleteArmed = w;
            deleteArmedAt = now;
        }
    }

    // ------------------------------------------------------------------ drawing

    private List<Waypoint> visibleList() {
        String dim = WaypointManager.currentDimension();
        String q = searchText.trim().toLowerCase(Locale.ROOT);
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint w : WaypointManager.forServer()) {
            if (!allDimensions && !w.dimension().equals(dim) && !crossesTo(w.dimension(), dim)) continue;
            if (!q.isEmpty() && !w.name().toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(w);
        }
        Vec3 me = minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
        out.sort((a, b) -> Double.compare(distance(a, me, dim), distance(b, me, dim)));
        return out;
    }

    /** Overworld points are shown in the Nether (at /8) and the other way round. */
    private static boolean crossesTo(String from, String to) {
        return (from.endsWith("overworld") && to.endsWith("the_nether")) || (from.endsWith("the_nether") && to.endsWith("overworld"));
    }

    private static double distance(Waypoint w, Vec3 me, String dim) {
        double x = w.x() + 0.5;
        double z = w.z() + 0.5;
        if (!w.dimension().equals(dim)) {
            if (w.dimension().endsWith("overworld") && dim.endsWith("the_nether")) {
                x /= 8;
                z /= 8;
            } else if (w.dimension().endsWith("the_nether") && dim.endsWith("overworld")) {
                x *= 8;
                z *= 8;
            } else {
                return 1e12;
            }
        }
        return Math.hypot(x - me.x, z - me.z);
    }

    private static String dimTag(String dim) {
        return dim.endsWith("the_nether") ? "NETHER" : dim.endsWith("the_end") ? "END" : dim.endsWith("overworld") ? "OW" : "?";
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int accent = Theme.accent();
        int x = contentX();
        int y = contentY();
        int lw = listW();
        rows.clear();
        rowRects.clear();
        swatches.clear();
        dimChips.clear();

        // Search field
        int sw = Math.max(60, lw - 190) + 8;
        Render2D.roundRect(g, x, y, sw, 14, 7, 0x40000000);
        Render2D.roundOutline(g, x, y, sw, 14, 7, search != null && search.isFocused() ? Theme.withAlpha(accent, 0.9f) : 0x26FFFFFF);

        int top = y + 20;
        int h = contentY() + contentH() - top;
        List<Waypoint> list = visibleList();
        int total = list.size() * ROW;
        bar.update(x + lw - 4, top, h, total, h);
        Vec3 me = minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
        String dim = WaypointManager.currentDimension();
        float yaw = minecraft.player != null ? minecraft.player.getYRot() : 0f;
        if (list.isEmpty()) {
            g.centeredText(font, WaypointManager.forServer().isEmpty() ? "No waypoints on this server yet" : "Nothing matches",
                x + lw / 2, top + 20, Theme.TEXT_DIM);
            g.centeredText(font, "Click \"+ New here\" to add one where you stand", x + lw / 2, top + 32, Theme.TEXT_FAINT);
        }
        g.enableScissor(x, top, x + lw, top + h);
        int ry = top - bar.scroll;
        int rw = lw - (total > h ? 8 : 0);
        long now = System.currentTimeMillis();
        for (Waypoint w : list) {
            if (ry + ROW >= top && ry <= top + h) {
                boolean hover = mouseX >= x && mouseX < x + rw && mouseY >= Math.max(ry, top) && mouseY < Math.min(ry + ROW - 2, top + h);
                boolean sel = w.equals(editing);
                Render2D.roundRect(g, x, ry, rw, ROW - 2, 7, sel ? Theme.withAlpha(accent, 0.22f) : hover ? 0x22FFFFFF : 0x12FFFFFF);
                if (sel) Render2D.roundOutline(g, x, ry, rw, ROW - 2, 7, Theme.withAlpha(accent, 0.8f));
                // Colour dot (dimmed when hidden)
                int c = w.visible() ? w.color() : Theme.withAlpha(w.color(), 0.35f);
                Render2D.roundRect(g, x + 7, ry + 7, 8, 8, 4, c);
                g.text(font, w.name() + (w.death() ? " §8☠" : ""), x + 21, ry + 3, w.visible() ? Theme.TEXT : Theme.TEXT_FAINT, false);
                g.text(font, w.x() + " " + w.y() + " " + w.z(), x + 21, ry + 12, Theme.TEXT_FAINT, false);
                String tag = dimTag(w.dimension());
                int tw = font.width(tag) + 6;
                int tx = x + 21 + Math.max(font.width(w.name()) + 6, font.width(w.x() + " " + w.y() + " " + w.z()) + 6);
                if (tx + tw < x + rw - 110) {
                    Render2D.roundRect(g, tx, ry + 11, tw, 10, 4, 0x22FFFFFF);
                    g.text(font, tag, tx + 3, ry + 12, Theme.TEXT_DIM, false);
                }
                // Right side: distance + arrow, eye, pencil, x
                double d = distance(w, me, dim);
                String ds = d > 1e11 ? "-" : Math.round(d) + "m " + arrow(me, w, yaw, dim);
                int icons = x + rw - 54;
                g.text(font, ds, icons - 6 - font.width(ds), ry + 7, Theme.TEXT_DIM, false);
                drawIcon(g, icons, ry + 4, w.visible() ? "◉" : "○", hover && mouseX >= icons && mouseX < icons + 14, w.visible() ? Theme.SUCCESS : Theme.TEXT_FAINT);
                drawIcon(g, icons + 17, ry + 4, "✎", hover && mouseX >= icons + 17 && mouseX < icons + 31, accent);
                boolean armed = deleteArmed == w && now - deleteArmedAt < 3000;
                drawIcon(g, icons + 34, ry + 4, armed ? "?" : "✕", hover && mouseX >= icons + 34 && mouseX < icons + 48, Theme.DANGER);
                rows.add(w);
                rowRects.add(new int[]{x, Math.max(ry, top), rw, Math.min(ry + ROW - 2, top + h) - Math.max(ry, top), icons});
            }
            ry += ROW;
        }
        g.disableScissor();
        bar.draw(g, mouseX, mouseY);

        if (editorOpen()) drawEditor(g, mouseX, mouseY, accent);
    }

    private void drawIcon(GuiGraphicsExtractor g, int x, int y, String icon, boolean hover, int color) {
        Render2D.roundRect(g, x, y, 14, 14, 5, hover ? Theme.withAlpha(color, 0.35f) : 0x16FFFFFF);
        g.centeredText(font, icon, x + 7, y + 3, hover ? Theme.TEXT : color);
    }

    private static String arrow(Vec3 me, Waypoint w, float yaw, String dim) {
        double x = w.x() + 0.5;
        double z = w.z() + 0.5;
        if (!w.dimension().equals(dim)) {
            double f = w.dimension().endsWith("overworld") ? 1 / 8.0 : 8.0;
            x *= f;
            z *= f;
        }
        float angle = (float) Math.toDegrees(Math.atan2(z - me.z, x - me.x)) - 90f;
        float rel = Mth.wrapDegrees(angle - yaw);
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        return arrows[Math.floorMod(Math.round(rel / 45f), 8)];
    }

    private void drawEditor(GuiGraphicsExtractor g, int mouseX, int mouseY, int accent) {
        int px = paneX();
        int pw = paneW();
        int py = contentY() + 20;
        Render2D.roundRect(g, px - 4, contentY() - 2, pw + 4, contentH() + 4, 8, 0x22000000);
        g.text(font, Component.literal(creating ? "New waypoint" : "Edit waypoint").withStyle(net.minecraft.ChatFormatting.BOLD), px + 2, contentY() + 3, Theme.TEXT, false);
        // Name
        g.text(font, "Name", px + 2, py + 1, Theme.TEXT_DIM, false);
        fieldBox(g, nameBox, px, py + 9, pw, accent);
        // X Y Z
        g.text(font, "X        Y        Z", px + 2, py + 29, Theme.TEXT_DIM, false);
        fieldBox(g, xBox, px, py + 37, (pw - 8) / 3 - 2, accent);
        fieldBox(g, yBox, px + (pw - 8) / 3, py + 37, (pw - 8) / 3 - 2, accent);
        fieldBox(g, zBox, px + (pw - 8) / 3 * 2, py + 37, (pw - 8) / 3 - 2, accent);
        // Colours
        int cy = py + 76;
        g.text(font, "Colour", px + 2, cy, Theme.TEXT_DIM, false);
        cy += 10;
        int cols = 6;
        int size = Math.min(16, (pw - 4) / cols - 4);
        for (int i = 0; i < WaypointManager.PALETTE.length; i++) {
            int sx = px + 2 + (i % cols) * (size + 4);
            int sy = cy + (i / cols) * (size + 4);
            int c = WaypointManager.PALETTE[i];
            boolean on = c == color;
            boolean hover = mouseX >= sx && mouseX < sx + size && mouseY >= sy && mouseY < sy + size;
            Render2D.roundRect(g, sx, sy, size, size, 5, c);
            if (on || hover) Render2D.roundOutline(g, sx - 1, sy - 1, size + 2, size + 2, 6, on ? 0xFFFFFFFF : 0x80FFFFFF);
            swatches.add(new int[]{sx, sy, size, i});
        }
        // Dimension
        int dy = cy + 2 * (size + 4) + 6;
        g.text(font, "Dimension", px + 2, dy, Theme.TEXT_DIM, false);
        dy += 10;
        int dx = px + 2;
        for (int i = 0; i < DIMS.length; i++) {
            int w = font.width(DIM_NAMES[i]) + 10;
            boolean on = DIMS[i].equals(dimension);
            boolean hover = mouseX >= dx && mouseX < dx + w && mouseY >= dy && mouseY < dy + 13;
            Render2D.roundRect(g, dx, dy, w, 13, 6, on ? Theme.withAlpha(accent, 0.5f) : hover ? 0x28FFFFFF : 0x14FFFFFF);
            Render2D.roundOutline(g, dx, dy, w, 13, 6, on ? accent : Theme.BORDER);
            g.text(font, DIM_NAMES[i], dx + 5, dy + 3, on || hover ? Theme.TEXT : Theme.TEXT_DIM, false);
            dimChips.add(new int[]{dx, dy, w, i});
            dx += w + 4;
        }
        // Preview of the colour
        Render2D.roundRect(g, px + pw - 30, contentY() + 1, 26, 12, 6, color);
    }

    private void fieldBox(GuiGraphicsExtractor g, EditBox box, int x, int y, int w, int accent) {
        Render2D.roundRect(g, x, y, w, 14, 6, 0x48000000);
        Render2D.roundOutline(g, x, y, w, 14, 6, box != null && box.isFocused() ? Theme.withAlpha(accent, 0.9f) : 0x26FFFFFF);
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean contentClicked(double mx, double my, int button) {
        if (bar.mouseClicked(mx, my)) return true;
        for (int[] s : swatches) {
            if (mx >= s[0] && mx < s[0] + s[2] && my >= s[1] && my < s[1] + s[2]) {
                color = WaypointManager.PALETTE[s[3]];
                return true;
            }
        }
        for (int[] c : dimChips) {
            if (mx >= c[0] && mx < c[0] + c[2] && my >= c[1] && my < c[1] + 13) {
                dimension = DIMS[c[3]];
                return true;
            }
        }
        for (int i = 0; i < rowRects.size(); i++) {
            int[] r = rowRects.get(i);
            if (mx < r[0] || mx >= r[0] + r[2] || my < r[1] || my >= r[1] + r[3]) continue;
            Waypoint w = rows.get(i);
            int icons = r[4];
            if (mx >= icons && mx < icons + 14) {
                WaypointManager.update(w, new Waypoint(w.server(), w.dimension(), w.name(), w.x(), w.y(), w.z(), w.color(), w.death(), w.time(), !w.visible()));
            } else if (mx >= icons + 34 && mx < icons + 48) {
                askDelete(w);
            } else if (button == 1) {
                minecraft.keyboardHandler.setClipboard(w.x() + " " + w.y() + " " + w.z());
                com.relaxedqol.client.util.ChatUtil.send("§7Copied §f" + w.x() + " " + w.y() + " " + w.z());
            } else {
                edit(w);
            }
            return true;
        }
        return false;
    }

    @Override
    protected boolean contentDragged(double mx, double my) {
        return bar.mouseDragged(my);
    }

    @Override
    protected void contentReleased() {
        bar.release();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        bar.scrollBy(-(int) Math.signum(scrollY) * ROW);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean typing = (nameBox != null && nameBox.isFocused()) || (xBox != null && xBox.isFocused())
            || (yBox != null && yBox.isFocused()) || (zBox != null && zBox.isFocused());
        if (editorOpen() && (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER)) {
            saveEditor();
            return true;
        }
        if (editorOpen() && key == InputConstants.KEY_ESCAPE && !typing) {
            closeEditor();
            return true;
        }
        return super.keyPressed(event);
    }
}
