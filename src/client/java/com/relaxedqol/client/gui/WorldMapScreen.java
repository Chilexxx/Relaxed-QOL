package com.relaxedqol.client.gui;

import com.relaxedqol.client.module.modules.lovercraft.LiveMap;
import com.relaxedqol.client.util.MapTiles;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * World Map: the server's live web map inside the game. Drag to move, scroll to zoom (smoothly, on the
 * spot under the mouse), the zone squares with their numbers, every player with their face (click one for /profile),
 * a scale bar and the block / zone under the mouse. "Me" flies back to you; the search box finds a player.
 */
public class WorldMapScreen extends WindowScreen {
    /** Where the map looks (block X / Z) and how big it is (GUI pixels per block) - kept while the game runs. */
    private static double viewX = Double.NaN;
    private static double viewZ;
    private static double ppb = 0.35;
    private static double targetPpb = 0.35;
    private static boolean zoneOnly;
    private static final double MIN_PPB = 1 / 420.0; // Zoom out much further (the whole map fits)
    private static final double MAX_PPB = 6;

    private final LiveMap map;
    private EditBox search;
    private int mapX;
    private int mapY;
    private int mapW;
    private int mapH;
    private boolean pressed;
    private boolean moved;
    private double pressX;
    private double pressY;
    private double pressViewX;
    private double pressViewZ;
    /** The zoom glides towards targetPpb, keeping this block under this screen point. */
    private double anchorBX;
    private double anchorBZ;
    private double anchorSX = Double.NaN;
    private double anchorSY;
    /** "Me" / search: the view glides to this spot. */
    private double flyX = Double.NaN;
    private double flyZ;
    private long lastFrame;
    private float mapProgress = 1f;
    private final List<int[]> dots = new ArrayList<>();
    private final List<String> dotNames = new ArrayList<>();

    // ------------------------------------------------------------------ player list, search, zone filter
    private static final int SIDEBAR = 190;
    private static final int ROW = 17;
    /** null = every zone, else the zone number as text. */
    private static String zoneFilter;
    private static String selected;
    private boolean zoneMenu;
    private int[] zonePill = {0, 0, 0, 0};
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private final ScrollBar bar = new ScrollBar();
    private final List<int[]> rowRects = new ArrayList<>();
    private final List<LiveMap.MapPlayer> rowPlayers = new ArrayList<>();
    private String hoverRow;

    public WorldMapScreen(Screen parent, LiveMap map) {
        super(parent, Component.literal("World Map"), "worldmap2", 980, 600);
        this.map = map;
        if (Double.isNaN(viewX)) {
            var p = net.minecraft.client.Minecraft.getInstance().player;
            viewX = p != null ? p.getX() : 0;
            viewZ = p != null ? p.getZ() : 0;
        }
    }

    @Override
    protected float windowAlpha() {
        return 1f;
    }

    /** The map may be made much smaller than its big default (its buttons still never overlap). */
    @Override
    protected int minWidth() {
        return 420;
    }

    @Override
    protected int minHeight() {
        return 260;
    }

    @Override
    protected String titleInfo() {
        int n = map == null ? 0 : (zoneOnly ? map.usable() : map.all()).size();
        return n + (zoneOnly ? " in your zone" : " players");
    }

    /** Flies to the middle of the zone grid. */
    private void centerOnGrid() {
        if (map == null || map.zoneCenters().isEmpty()) return;
        double sx = 0;
        double sz = 0;
        for (double[] z : map.zoneCenters()) {
            sx += z[1];
            sz += z[2];
        }
        anchorSX = Double.NaN;
        flyTo(sx / map.zoneCenters().size(), sz / map.zoneCenters().size());
    }

    private void flyTo(double x, double z) {
        flyX = x;
        flyZ = z;
    }

    private void centerOnMe() {
        if (minecraft.player != null) flyTo(minecraft.player.getX(), minecraft.player.getZ());
    }

    @Override
    protected void initContent() {
        int x = contentX();
        int y = contentY();
        int meW = pillWidth("Centre on Me");
        pill(() -> "Centre on Me", x, y, meW, 15, this::centerOnMe, Theme.SUCCESS, () -> false);
        int zw = pillWidth("Players: Everyone");
        pill(() -> zoneOnly ? "Players: My Zone" : "Players: Everyone", x + meW + 4, y, zw, 15, () -> zoneOnly = !zoneOnly, 0, () -> zoneOnly);
        int rw = pillWidth("Refresh");
        pill(() -> "Refresh", x + meW + zw + 8, y, rw, 15, MapTiles::refreshAll, 0, () -> false);
        String typed = search != null ? search.getValue() : "";
        // A player list on the left - search box (the list narrows as you type), zone filter, the players
        boolean side = contentW() >= 520;
        int sideW = side ? SIDEBAR : 0;
        search = new EditBox(font, x, y + 20, side ? sideW : 160, 15, Component.literal("Search players"));
        search.setHint(Component.literal("Search players\u2026"));
        search.setMaxLength(17);
        search.setValue(typed);
        search.setResponder(t -> bar.scroll = 0);
        addRenderableWidget(search);
        zonePill = new int[]{x, y + 39, sideW, 15};
        pill(() -> "Zone: " + (zoneFilter == null ? "All" : zoneFilter) + "  \u25BE", x, y + 39, Math.max(60, sideW), 15,
            () -> zoneMenu = !zoneMenu, 0, () -> zoneFilter != null || zoneMenu);
        listX = x;
        listY = y + 58;
        listW = sideW;
        listH = contentH() - 58;
        mapX = x + (side ? sideW + 6 : 0);
        mapY = y + 20;
        mapW = contentW() - (side ? sideW + 6 : 0);
        mapH = contentH() - 20;
    }

    private double toScreenX(double bx) {
        return mapX + mapW / 2.0 + (bx - viewX) * ppb;
    }

    private double toScreenY(double bz) {
        return mapY + mapH / 2.0 + (bz - viewZ) * ppb;
    }

    /** Smooth zoom and flying, a little each frame. */
    private void animate() {
        // Never further out than the grid (also after the window was made smaller)
        double min = minPpb();
        if (targetPpb < min) targetPpb = min;
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        float k = 1f - (float) Math.exp(-dt * (Double.isNaN(flyX) ? 14f : 7f));
        if (Math.abs(targetPpb - ppb) > ppb * 0.001) {
            ppb += (targetPpb - ppb) * k;
            if (!Double.isNaN(anchorSX)) {
                viewX = anchorBX - (anchorSX - mapX - mapW / 2.0) / ppb;
                viewZ = anchorBZ - (anchorSY - mapY - mapH / 2.0) / ppb;
            }
        } else {
            ppb = targetPpb;
            anchorSX = Double.NaN;
        }
        if (!Double.isNaN(flyX)) {
            float f = 1f - (float) Math.exp(-dt * 7f);
            viewX += (flyX - viewX) * f;
            viewZ += (flyZ - viewZ) * f;
            if (Math.abs(flyX - viewX) * ppb < 0.5 && Math.abs(flyZ - viewZ) * ppb < 0.5) {
                viewX = flyX;
                viewZ = flyZ;
                flyX = Double.NaN;
            }
        }
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        animate();
        dots.clear();
        dotNames.clear();
        int accent = Theme.accent();
        float cx = mapX + mapW / 2f;
        float cy = mapY + mapH / 2f;
        // Ocean-dark backdrop while tiles load
        g.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF0D1420);
        g.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
        if (MapTiles.available()) {
            // One pass, square clip (it used to be drawn once per rounded-corner row - 15 times a frame)
            MapTiles.draw(g, cx, cy, ppb, 0, viewX, viewZ, Math.hypot(mapW, mapH) / 2.0 / 1.4, 255, gridBounds());
            // Read right after OUR draw - the radar card draws tiles too and used to overwrite it (the % jumped)
            mapProgress = MapTiles.progress();
        } else {
            String why = minecraft.level != null && com.relaxedqol.client.util.Dims.ceiling()
                ? "The web map has no Nether - only the Survival world" : "The map is not available here";
            g.centeredText(font, why, (int) cx, (int) cy - 4, Theme.TEXT_DIM);
        }
        drawZones(g);
        String hover = drawPlayers(g, mouseX, mouseY, accent);
        // You
        if (minecraft.player != null) {
            float sx = (float) toScreenX(minecraft.player.getX());
            float sy = (float) toScreenY(minecraft.player.getZ());
            double yaw = Math.toRadians(minecraft.player.getViewYRot(partialTick));
            if (!com.relaxedqol.client.module.modules.client.ClickGUI.lowEnd()) {
                Render2D.smoothCircle(g, sx, sy, 9f, Theme.withAlpha(accent, 0.25f), 3f);
            }
            Render2D.smoothArrow(g, sx, sy, 6.5f, yaw + Math.PI, Theme.mix(accent, 0xFFFFFFFF, 0.2f), 0xF0000000);
        }
        // Soft vignette at the edges
        g.fillGradient(mapX, mapY, mapX + mapW, mapY + 14, 0x50000000, 0x00000000);
        g.fillGradient(mapX, mapY + mapH - 14, mapX + mapW, mapY + mapH, 0x00000000, 0x60000000);
        g.disableScissor();
        roundCorners(g, mapX, mapY, mapW, mapH, 8);
        Render2D.roundOutline(g, mapX, mapY, mapW, mapH, 8, 0x38FFFFFF);
        drawHud(g, mouseX, mouseY, hover, accent);
        if (listW > 0) drawList(g, mouseX, mouseY, accent);
    }

    /** The zone number of a map player as text ("22"), or null. */
    private static String zoneOf(LiveMap.MapPlayer p) {
        String z = LiveMap.zoneLabel(p);
        if (z == null) return null;
        String d = z.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    /** The players after the Players / zone / search filters, best search matches first. */
    private List<LiveMap.MapPlayer> filtered(boolean bySearch) {
        List<LiveMap.MapPlayer> out = new ArrayList<>();
        if (map == null) return out;
        String me = minecraft.player != null ? minecraft.player.getGameProfile().name() : "";
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        for (LiveMap.MapPlayer p : zoneOnly ? map.usable() : map.all()) {
            if (p.foreign() || p.name().equalsIgnoreCase(me)) continue;
            if (zoneFilter != null && !zoneFilter.equals(zoneOf(p))) continue;
            if (bySearch && !q.isEmpty() && !p.name().toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(p);
        }
        out.sort((a, b) -> {
            if (!q.isEmpty()) {
                boolean sa = a.name().toLowerCase(Locale.ROOT).replaceFirst("^[.*~!+]", "").startsWith(q);
                boolean sb = b.name().toLowerCase(Locale.ROOT).replaceFirst("^[.*~!+]", "").startsWith(q);
                if (sa != sb) return sa ? -1 : 1;
            }
            return a.name().replaceFirst("^[.*~!+]", "").compareToIgnoreCase(b.name().replaceFirst("^[.*~!+]", ""));
        });
        return out;
    }

    private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY, int accent) {
        rowRects.clear();
        rowPlayers.clear();
        hoverRow = null;
        List<LiveMap.MapPlayer> list = filtered(true);
        Render2D.roundRect(g, listX, listY, listW, listH, 7, 0x22000000);
        Render2D.roundOutline(g, listX, listY, listW, listH, 7, 0x18FFFFFF);
        String head = list.size() + (list.size() == 1 ? " player" : " players");
        g.text(font, head, listX + 7, listY + 5, Theme.TEXT_FAINT, false);
        int top = listY + 17;
        int h = listH - 19;
        if (list.isEmpty()) {
            String q = search == null ? "" : search.getValue().trim();
            g.text(font, q.isEmpty() ? "Nobody here" : "No name with \"" + q + "\"", listX + 7, top + 4, Theme.TEXT_DIM, false);
            return;
        }
        int total = list.size() * ROW;
        bar.update(listX + listW - 5, top, h, total, h);
        int rowW = listW - (total > h ? 12 : 6);
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        g.enableScissor(listX + 1, top, listX + listW - 1, top + h);
        int ry = top - bar.scroll;
        for (LiveMap.MapPlayer p : list) {
            if (ry + ROW >= top && ry <= top + h) {
                int rx = listX + 3;
                boolean over = mouseX >= rx && mouseX < rx + rowW && mouseY >= Math.max(ry, top) && mouseY < Math.min(ry + ROW - 1, top + h);
                boolean sel = p.name().equals(selected);
                if (over) hoverRow = p.name();
                if (sel || over) Render2D.roundRect(g, rx, ry, rowW, ROW - 1, 4, sel ? Theme.withAlpha(accent, 0.35f) : 0x16FFFFFF);
                if (!com.relaxedqol.client.util.Heads.draw(g, com.relaxedqol.client.util.Names.tabId(p.name(), p.uuid()), rx + 3, ry + 3, 10, 0xFFFFFFFF)
                    && !com.relaxedqol.client.util.SkinFaces.draw(g, p.name(), p.uuid(), rx + 3, ry + 3, 10, 0xFFFFFFFF)) {
                    Render2D.roundRect(g, rx + 3, ry + 3, 10, 10, 2, 0x30FFFFFF);
                }
                String zone = zoneOf(p);
                String right = zone != null ? "Z" + zone : "";
                int rw = font.width(right);
                String name = p.name();
                int room = rowW - 18 - rw - 8;
                while (name.length() > 3 && font.width(name) > room) name = name.substring(0, name.length() - 1);
                int nx = rx + 17;
                // The typed part of the name lights up (like an auto-complete list)
                int at = q.isEmpty() ? -1 : name.toLowerCase(Locale.ROOT).indexOf(q);
                if (at >= 0) {
                    String a = name.substring(0, at);
                    String b = name.substring(at, Math.min(name.length(), at + q.length()));
                    String c = name.substring(Math.min(name.length(), at + q.length()));
                    g.text(font, a, nx, ry + 4, Theme.TEXT_DIM, false);
                    g.text(font, b, nx + font.width(a), ry + 4, accent, false);
                    g.text(font, c, nx + font.width(a + b), ry + 4, Theme.TEXT_DIM, false);
                } else {
                    g.text(font, name, nx, ry + 4, sel || over ? Theme.TEXT : 0xFFD6D7DD, false);
                }
                if (!right.isEmpty()) g.text(font, right, rx + rowW - 5 - rw, ry + 4, 0xFFFBBF24, false);
                rowRects.add(new int[]{rx, Math.max(ry, top), rowW, Math.min(ry + ROW - 1, top + h) - Math.max(ry, top)});
                rowPlayers.add(p);
            }
            ry += ROW;
        }
        g.disableScissor();
        bar.draw(g, mouseX, mouseY);
    }

    /** The zone filter drop-down: All, then every zone on the map. */
    private List<String> zoneChoices() {
        List<String> out = new ArrayList<>();
        out.add(null);
        if (map != null) {
            List<Integer> nums = new ArrayList<>();
            for (double[] z : map.zoneCenters()) nums.add((int) z[0]);
            if (nums.isEmpty()) {
                for (LiveMap.MapPlayer p : map.all()) {
                    String z = zoneOf(p);
                    if (z != null && !nums.contains(Integer.parseInt(z))) nums.add(Integer.parseInt(z));
                }
            }
            nums.sort(Integer::compare);
            for (int n : nums) out.add(String.valueOf(n));
        }
        return out;
    }

    private static final int MENU_COLS = 4;
    private static final int MENU_W = 44;
    private static final int MENU_H = 15;

    private int[] menuBox() {
        int n = zoneChoices().size();
        int rows = (n + MENU_COLS - 1) / MENU_COLS;
        return new int[]{zonePill[0], zonePill[1] + zonePill[3] + 2, MENU_COLS * MENU_W + 8, rows * MENU_H + 8};
    }

    @Override
    protected void drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!zoneMenu) return;
        int[] b = menuBox();
        int accent = Theme.accent();
        Render2D.popup(g, b[0], b[1], b[2], b[3]);
        List<String> choices = zoneChoices();
        for (int i = 0; i < choices.size(); i++) {
            int cx = b[0] + 4 + (i % MENU_COLS) * MENU_W;
            int cy = b[1] + 4 + (i / MENU_COLS) * MENU_H;
            String c = choices.get(i);
            boolean sel = c == null ? zoneFilter == null : c.equals(zoneFilter);
            boolean over = mouseX >= cx && mouseX < cx + MENU_W - 2 && mouseY >= cy && mouseY < cy + MENU_H - 1;
            if (sel || over) Render2D.roundRect(g, cx, cy, MENU_W - 2, MENU_H - 1, Theme.R_CONTROL, sel ? Theme.withAlpha(accent, 0.2f) : 0x1FFFFFFF);
            String label = c == null ? "All" : "Zone " + c;
            g.text(font, label, cx + (MENU_W - 2 - font.width(label)) / 2, cy + 3, sel ? Theme.TEXT : Theme.TEXT_DIM, false);
        }
    }

    @Override
    protected boolean overlayClicked(double mx, double my, int button) {
        if (!zoneMenu) return false;
        if (mx >= zonePill[0] && mx < zonePill[0] + zonePill[2] && my >= zonePill[1] && my < zonePill[1] + zonePill[3]) {
            zoneMenu = false;
            return true;
        }
        int[] b = menuBox();
        List<String> choices = zoneChoices();
        if (mx >= b[0] + 4 && mx < b[0] + b[2] - 4 && my >= b[1] + 4 && my < b[1] + b[3] - 4) {
            int i = (int) ((my - b[1] - 4) / MENU_H) * MENU_COLS + (int) ((mx - b[0] - 4) / MENU_W);
            if (i >= 0 && i < choices.size()) {
                zoneFilter = choices.get(i);
                bar.scroll = 0;
                // Glide to the zone and zoom so it fits the map exactly (All = back out to the whole grid)
                if (map != null) {
                    if (zoneFilter == null) {
                        targetPpb = minPpb();
                        centerOnGrid();
                    } else {
                        for (double[] z : map.zoneCenters()) {
                            if (!String.valueOf((int) z[0]).equals(zoneFilter)) continue;
                            double[] box = map.zoneSquare(z[1], z[2]);
                            if (box != null) {
                                anchorSX = Double.NaN;
                                targetPpb = Math.max(minPpb(), Math.min(MAX_PPB, Math.min(mapW, mapH) / (box[2] * 2 * 1.05)));
                            }
                            flyTo(z[1], z[2]);
                        }
                    }
                }
            }
        }
        zoneMenu = false;
        return true;
    }

    /** Rounds the map's corners off with the window's own colour (cheap: a few lines per corner). */
    private void roundCorners(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
        int body = bodyColor();
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.ceil(r - Math.sqrt(Math.max(0, r * (double) r - dy * dy)) - 0.35);
            if (inset <= 0) continue;
            g.fill(x, y + i, x + inset, y + i + 1, body);
            g.fill(x + w - inset, y + i, x + w, y + i + 1, body);
            g.fill(x, y + h - 1 - i, x + inset, y + h - i, body);
            g.fill(x + w - inset, y + h - 1 - i, x + w, y + h - i, body);
        }
    }

    /** The zone squares (from the map's zone markers): a thin amber frame and the number, like on the website. */
    private void drawZones(GuiGraphicsExtractor g) {
        if (map == null) return;
        List<double[]> centers = map.zoneCenters();
        if (centers.isEmpty()) return;
        double spacing = Double.MAX_VALUE;
        for (int i = 0; i < centers.size(); i++) {
            for (int j = i + 1; j < centers.size(); j++) {
                double d = Math.max(Math.abs(centers.get(i)[1] - centers.get(j)[1]), Math.abs(centers.get(i)[2] - centers.get(j)[2]));
                if (d > 16) spacing = Math.min(spacing, d);
            }
        }
        boolean frames = spacing < Double.MAX_VALUE && centers.size() >= 4;
        double half = frames ? spacing / 2.0 : 0;
        for (double[] z : centers) {
            double sx = toScreenX(z[1]);
            double sy = toScreenY(z[2]);
            if (frames) {
                int x0 = (int) Math.round(toScreenX(z[1] - half));
                int x1 = (int) Math.round(toScreenX(z[1] + half));
                int y0 = (int) Math.round(toScreenY(z[2] - half));
                int y1 = (int) Math.round(toScreenY(z[2] + half));
                if (x1 >= mapX && x0 <= mapX + mapW && y1 >= mapY && y0 <= mapY + mapH) {
                    int c = 0x70FBBF24;
                    g.fill(x0, y0, x1, y0 + 1, c);
                    g.fill(x0, y1 - 1, x1, y1, c);
                    g.fill(x0, y0, x0 + 1, y1, c);
                    g.fill(x1 - 1, y0, x1, y1, c);
                }
            }
            if (sx < mapX - 40 || sx > mapX + mapW + 40 || sy < mapY - 20 || sy > mapY + mapH + 20) continue;
            String t = "Zone " + (int) z[0];
            int tw = font.width(t) + 10;
            Render2D.roundRect(g, (int) sx - tw / 2, (int) sy - 7, tw, 13, 4, 0xC8101114);
            Render2D.roundOutline(g, (int) sx - tw / 2, (int) sy - 7, tw, 13, 4, 0xB0FBBF24);
            g.text(font, t, (int) sx - tw / 2 + 5, (int) sy - 4, 0xFFFBBF24, false);
        }
    }

    /** Every player as their face; names when zoomed in. Returns the hover text, if any. */
    private String drawPlayers(GuiGraphicsExtractor g, int mouseX, int mouseY, int accent) {
        if (map == null) return null;
        String me = minecraft.player != null ? minecraft.player.getGameProfile().name() : "";
        List<LiveMap.MapPlayer> list = filtered(false);
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        String hover = null;
        int hs = ppb >= 0.6 ? 12 : ppb >= 0.2 ? 10 : 8;
        boolean names = ppb >= 0.3;
        List<int[]> taken = new ArrayList<>();
        for (LiveMap.MapPlayer p : list) {
            if (p.name().equalsIgnoreCase(me) || p.foreign()) continue;
            int sx = (int) Math.round(toScreenX(p.x()));
            int sy = (int) Math.round(toScreenY(p.z()));
            if (sx < mapX - 10 || sx > mapX + mapW + 10 || sy < mapY - 10 || sy > mapY + mapH + 10) continue;
            boolean match = (!query.isEmpty() && p.name().toLowerCase(Locale.ROOT).contains(query)) || p.name().equals(selected)
                || p.name().equals(hoverRow);
            boolean over = Math.abs(mouseX - sx) <= hs / 2 + 2 && Math.abs(mouseY - sy) <= hs / 2 + 2;
            // Shadow, rim (accent for a search match / hover), face
            Render2D.roundRect(g, sx - hs / 2 - 2, sy - hs / 2 - 1, hs + 4, hs + 4, 3, 0x70000000);
            Render2D.roundRect(g, sx - hs / 2 - 1, sy - hs / 2 - 1, hs + 2, hs + 2, 2, match || over ? accent : 0xF0FFFFFF);
            if (!com.relaxedqol.client.util.Heads.draw(g, com.relaxedqol.client.util.Names.tabId(p.name(), p.uuid()), sx - hs / 2, sy - hs / 2, hs, 0xFFFFFFFF)
                && !com.relaxedqol.client.util.SkinFaces.draw(g, p.name(), p.uuid(), sx - hs / 2, sy - hs / 2, hs, 0xFFFFFFFF)) {
                Render2D.roundRect(g, sx - hs / 2, sy - hs / 2, hs, hs, 1, 0xFF3A3D48);
            }
            dots.add(new int[]{sx, sy});
            dotNames.add(p.name());
            if (over) {
                String zone = LiveMap.zoneLabel(p);
                hover = p.name() + (zone != null ? "  ·  " + zone : "") + "  ·  " + Math.round(p.x()) + ", " + Math.round(p.z());
            } else if (names || match) {
                int tw = font.width(p.name()) + 8;
                int[] rect = {sx - tw / 2, sy + hs / 2 + 3, tw, 11};
                boolean free = true;
                for (int[] t : taken) {
                    if (rect[0] < t[0] + t[2] && rect[0] + rect[2] > t[0] && rect[1] < t[1] + t[3] && rect[1] + rect[3] > t[1]) {
                        free = false;
                        break;
                    }
                }
                if (free || match) {
                    taken.add(rect);
                    Render2D.roundRect(g, rect[0], rect[1], tw, 11, 4, match ? Theme.withAlpha(accent, 0.85f) : 0xC8101114);
                    g.text(font, p.name(), rect[0] + 4, rect[1] + 2, 0xFFF4F4F7, false);
                }
            }
        }
        return hover;
    }

    /** Hover card, scale bar, position under the mouse, loading hint. */
    private void drawHud(GuiGraphicsExtractor g, int mouseX, int mouseY, String hover, int accent) {
        boolean overMap = mouseX >= mapX && mouseX < mapX + mapW && mouseY >= mapY && mouseY < mapY + mapH;
        if (hover != null) {
            int tw = font.width(hover) + 12;
            int tx = Math.max(mapX + 4, Math.min(mouseX + 10, mapX + mapW - tw - 4));
            int ty = Math.max(mapY + 4, mouseY - 20);
            Render2D.roundRect(g, tx, ty, tw, 14, 4, 0xF0101114);
            Render2D.roundOutline(g, tx, ty, tw, 14, 4, Theme.withAlpha(accent, 0.8f));
            g.text(font, hover, tx + 6, ty + 3, Theme.TEXT, false);
        }
        // Scale bar: a round number of blocks, about 80 px long
        double target = 80 / ppb;
        double[] steps = {10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000};
        double blocks = steps[0];
        for (double s : steps) if (s <= target) blocks = s;
        int bw = (int) Math.round(blocks * ppb);
        int bx = mapX + 10;
        int by = mapY + mapH - 12;
        g.fill(bx, by, bx + bw, by + 2, 0xE0FFFFFF);
        g.fill(bx, by - 3, bx + 1, by + 2, 0xE0FFFFFF);
        g.fill(bx + bw - 1, by - 3, bx + bw, by + 2, 0xE0FFFFFF);
        String label = blocks >= 1000 ? (blocks / 1000 == Math.floor(blocks / 1000) ? (int) (blocks / 1000) + "k" : (blocks / 1000) + "k") + " blocks"
            : (int) blocks + " blocks";
        g.text(font, label, bx + bw + 6, by - 3, 0xE0FFFFFF, true);
        int line = bx + bw + 12 + font.width(label);
        // The bottom line never overlaps - over the map it shows the spot under the mouse (right-aligned),
        // otherwise the how-to hint; each only when it fits next to the scale bar
        String bottom;
        if (overMap) {
            long mbx = Math.round(viewX + (mouseX - mapX - mapW / 2.0) / ppb);
            long mbz = Math.round(viewZ + (mouseY - mapY - mapH / 2.0) / ppb);
            String zone = map != null ? map.zoneAt(mbx, mbz) : null;
            bottom = "X " + mbx + "   Z " + mbz + (zone != null ? "   ·   Zone " + zone : "");
        } else {
            bottom = "Drag  ·  Scroll to zoom  ·  Click a player";
        }
        int btw = font.width(bottom);
        int btx = mapX + mapW - btw - 10;
        if (btx > line + 10) {
            Render2D.roundRect(g, btx - 5, by - 6, btw + 10, 13, 4, 0x90000000);
            g.text(font, bottom, btx, by - 3, overMap ? 0xE6FFFFFF : 0x90FFFFFF, false);
        }
        // How much of what you see is loaded, in % (it showed the number of downloads waiting)
        float progress = MapTiles.available() ? mapProgress : 1f;
        String status = progress < 0.995f ? "Loading map  " + Math.min(99, Math.round(progress * 100)) + "%"
            : map != null && map.all().isEmpty() ? "Reading players\u2026" : null;
        if (status != null) {
            int tw = font.width(status) + 18;
            int tx = mapX + mapW - tw - 8;
            Render2D.roundRect(g, tx, mapY + 8, tw, 14, 7, 0xC8101114);
            float spin = (System.currentTimeMillis() % 900) / 900f;
            Render2D.roundRect(g, tx + 6, mapY + 13, 4, 4, 2, Theme.withAlpha(accent, 0.4f + 0.6f * Math.abs(1 - 2 * spin)));
            g.text(font, status, tx + 13, mapY + 11, Theme.TEXT_DIM, false);
        }
    }

    /**
     * The furthest you can zoom out - the whole zone grid fills the map (a bit of room around it). Without zones:
     * a fixed limit.
     */
    /** The zone grid in blocks {minX, minZ, maxX, maxZ} with a little room around it, or null. */
    private double[] gridBounds() {
        if (map == null) return null;
        List<double[]> c = map.zoneCenters();
        if (c.size() < 4) return null;
        double[] box = map.zoneSquare(c.get(0)[1], c.get(0)[2]);
        double half = box != null ? box[2] : 0;
        double x0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double z0 = Double.MAX_VALUE;
        double z1 = -Double.MAX_VALUE;
        for (double[] z : c) {
            x0 = Math.min(x0, z[1] - half);
            x1 = Math.max(x1, z[1] + half);
            z0 = Math.min(z0, z[2] - half);
            z1 = Math.max(z1, z[2] + half);
        }
        double pad = half * 0.25;
        return new double[]{x0 - pad, z0 - pad, x1 + pad, z1 + pad};
    }

    private double minPpb() {
        if (map == null) return MIN_PPB;
        List<double[]> c = map.zoneCenters();
        if (c.size() < 4) return MIN_PPB;
        double[] box = map.zoneSquare(c.get(0)[1], c.get(0)[2]);
        double half = box != null ? box[2] : 0;
        double x0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double z0 = Double.MAX_VALUE;
        double z1 = -Double.MAX_VALUE;
        for (double[] z : c) {
            x0 = Math.min(x0, z[1] - half);
            x1 = Math.max(x1, z[1] + half);
            z0 = Math.min(z0, z[2] - half);
            z1 = Math.max(z1, z[2] + half);
        }
        double fit = Math.min(mapW / Math.max(1, (x1 - x0) * 1.04), mapH / Math.max(1, (z1 - z0) * 1.04));
        return Math.max(MIN_PPB, fit);
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean contentClicked(double mx, double my, int button) {
        if (bar.mouseClicked(mx, my)) return true;
        for (int i = 0; i < rowRects.size(); i++) {
            int[] r = rowRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                LiveMap.MapPlayer p = rowPlayers.get(i);
                if (button == 1 || p.name().equals(selected)) {
                    // Right click (or a second click): their profile
                    com.relaxedqol.client.module.modules.render.Radar radar = com.relaxedqol.client.RelaxedQolClient.moduleManager
                        .getModule(com.relaxedqol.client.module.modules.render.Radar.class);
                    if (radar != null) radar.openProfile(p.name());
                    return true;
                }
                selected = p.name();
                flyTo(p.x(), p.z());
                if (targetPpb < 0.5) {
                    anchorSX = Double.NaN;
                    targetPpb = 0.5;
                }
                return true;
            }
        }
        if (button != 0 || mx < mapX || mx >= mapX + mapW || my < mapY || my >= mapY + mapH) return false;
        pressed = true;
        moved = false;
        pressX = mx;
        pressY = my;
        pressViewX = viewX;
        pressViewZ = viewZ;
        return true;
    }

    @Override
    protected boolean contentDragged(double mx, double my) {
        if (bar.mouseDragged(my)) return true;
        if (!pressed) return false;
        if (!moved && Math.abs(mx - pressX) + Math.abs(my - pressY) < 3) return true;
        moved = true;
        flyX = Double.NaN;
        anchorSX = Double.NaN;
        targetPpb = ppb;
        viewX = pressViewX - (mx - pressX) / ppb;
        viewZ = pressViewZ - (my - pressY) / ppb;
        return true;
    }

    @Override
    protected void contentReleased() {
        bar.release();
        if (pressed && !moved) {
            int best = -1;
            double bestD = 9 * 9;
            for (int i = 0; i < dots.size(); i++) {
                double dx = dots.get(i)[0] - pressX;
                double dy = dots.get(i)[1] - pressY;
                double d = dx * dx + dy * dy;
                if (d <= bestD) {
                    bestD = d;
                    best = i;
                }
            }
            if (best >= 0) {
                com.relaxedqol.client.module.modules.render.Radar radar = com.relaxedqol.client.RelaxedQolClient.moduleManager
                    .getModule(com.relaxedqol.client.module.modules.render.Radar.class);
                if (radar != null) radar.openProfile(dotNames.get(best));
            }
        }
        pressed = false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        double mx = vx(x);
        double my = vy(y);
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            bar.scrollBy(-(int) Math.signum(scrollY) * ROW * 3);
            return true;
        }
        if (mx < mapX || mx >= mapX + mapW || my < mapY || my >= mapY + mapH) return true;
        // Smooth zoom on the spot under the mouse
        flyX = Double.NaN;
        anchorBX = viewX + (mx - mapX - mapW / 2.0) / ppb;
        anchorBZ = viewZ + (my - mapY - mapH / 2.0) / ppb;
        anchorSX = mx;
        anchorSY = my;
        double min = minPpb();
        targetPpb = Math.max(min, Math.min(MAX_PPB, targetPpb * (scrollY > 0 ? 1.35 : 1 / 1.35)));
        // At the furthest: the whole grid, centred
        if (targetPpb <= min * 1.001) centerOnGrid();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if ((key == 257 || key == 335) && search != null && search.isFocused()) {
            findPlayer(search.getValue());
            return true;
        }
        boolean typing = search != null && search.isFocused();
        if (!typing && map != null && map.mapKey() >= 0 && key == map.mapKey()) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private void findPlayer(String text) {
        if (map == null || text == null || text.isBlank()) return;
        // The top of the list (best match first)
        for (LiveMap.MapPlayer p : filtered(true)) {
            {
                selected = p.name();
                flyTo(p.x(), p.z());
                if (targetPpb < 0.6) {
                    anchorSX = Double.NaN;
                    targetPpb = 0.6;
                }
                return;
            }
        }
        Toasts.show("World Map", "\"" + text.trim() + "\" is not on the map", Theme.DANGER, 2500);
    }
}
