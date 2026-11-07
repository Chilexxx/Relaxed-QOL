package com.relaxedqol.client.gui;

import com.relaxedqol.client.module.modules.render.Radar;
import com.relaxedqol.client.util.EntityUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The big radar window: a large map of the players around you on the left (names drawn at full GUI resolution,
 * mouse wheel zooms, "North Up" or turning with you), and the list of their names in a column on the right.
 * Click a name - in the list or on the map - to run the Radar's Profile Command ("/profile NAME").
 * "Everyone" also lists players from the tab list who are too far away to be on the map.
 */
public class RadarScreen extends WindowScreen {
    private static final int ROW = 16;
    private static final int TOOLBAR = 16;
    private static final int[] RANGES = {16, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192};

    private final Radar radar;
    private final ScrollBar bar = new ScrollBar();
    private EditBox search;
    private String searchText = "";

    /** One entry of the list (and, when on the map, a dot). */
    /** pos = where a far player is according to the live web map (player == null then), else null. */
    private record Entry(String name, Player player, double dist, double dy, int color, Vec3 pos) {
        Entry(String name, Player player, double dist, double dy, int color) {
            this(name, player, dist, dy, color, null);
        }
    }

    /** What was drawn last frame, for clicks: list rows {x, y, w, h} and map dots {x, y}, each with its name. */
    private final List<int[]> rowRects = new ArrayList<>();
    private final List<String> rowNames = new ArrayList<>();
    private final List<int[]> dotSpots = new ArrayList<>();
    private final List<String> dotNames = new ArrayList<>();
    /** The name under the mouse (list row or map dot), highlighted in both places. */
    private String hoverName;
    private int mapX;
    private int mapY;
    private int mapSize;
    private int listX;
    private int listY;
    private int listW;
    private int listH;

    public RadarScreen(Screen parent, Radar radar) {
        super(parent, Component.literal("Radar"), "radar2", 640, 370);
        this.radar = radar;
    }

    @Override
    protected boolean dimBehind() {
        return true;
    }

    @Override
    protected float windowAlpha() {
        return Math.max(0.85f, Theme.opacity() + 0.4f);
    }

    @Override
    protected String titleInfo() {
        return radar.players().size() + " nearby  ·  " + radar.windowRangeShown() + "m";
    }

    // ------------------------------------------------------------------ layout

    private void computeLayout() {
        int cx = contentX();
        int cy = contentY() + TOOLBAR + 4;
        int cw = contentW();
        int ch = contentH() - TOOLBAR - 4;
        listW = Math.max(120, Math.min(210, cw * 34 / 100));
        mapSize = Math.max(60, Math.min(cw - listW - 8, ch));
        mapX = cx;
        mapY = cy;
        listX = cx + cw - listW;
        listY = cy;
        listH = ch;
    }

    @Override
    protected void initContent() {
        computeLayout();
        int y = contentY();
        int x = contentX();
        pill(() -> "−", x, y, 16, 14, () -> zoom(1), 0, () -> false);
        pill(() -> "+", x + 19, y, 16, 14, () -> zoom(-1), 0, () -> false);
        int bx = x + 40;
        int lw = pillWidth("List: Everyone");
        pill(() -> radar.windowEveryone() ? "List: Everyone" : "List: Nearby", bx, y, lw, 14,
            () -> radar.setWindowEveryone(!radar.windowEveryone()), 0, radar::windowEveryone);
        bx += lw + 4;
        // The world under the map (sharp, a block per pixel where it fits)
        int tw = pillWidth("Terrain: Off");
        pill(() -> radar.windowTerrain() ? "Terrain: On" : "Terrain: Off", bx, y, tw, 14,
            () -> radar.setWindowTerrain(!radar.windowTerrain()), 0, radar::windowTerrain);
        bx += tw + 4;
        // Faces instead of dots
        int hw = pillWidth("Heads: Off");
        if (bx + hw <= listX - 6) {
            pill(() -> radar.heads() ? "Heads: On" : "Heads: Off", bx, y, hw, 14, () -> radar.setHeads(!radar.heads()), 0, radar::heads);
        }
        // Search box over the name column (keeps what was typed when the window is moved / resized)
        String typed = search != null ? search.getValue() : searchText;
        search = new EditBox(font, listX, y, listW, 14, Component.literal("Search"));
        search.setHint(Component.literal("Search names\u2026"));
        search.setMaxLength(16);
        search.setValue(typed);
        search.setResponder(text -> {
            searchText = text;
            bar.scroll = 0;
        });
        addRenderableWidget(search);
    }

    // ------------------------------------------------------------------ moving the map around

    /** How far the middle of the map is from you (blocks, world X / Z). 0, 0 = centred on you. */
    private static double panX;
    private static double panZ;
    /** Far enough to reach anyone in your zone (it stopped at 2048 blocks from you). */
    private static final double MAX_PAN = 40000;
    /** A press on the map: a click (no movement) clicks a player, a drag moves the map. */
    private boolean mapPress;
    private boolean mapMoved;
    private double pressX;
    private double pressY;
    private double pressPanX;
    private double pressPanZ;
    /** The "back to you" chip on the map (only while it is moved). */
    private int[] recentreRect;
    /** Last frame's view: pixels per block and the map's turn (for turning a mouse move into blocks). */
    private double viewPpb = 1;
    private double viewCos = 1;
    private double viewSin = 0;
    private boolean viewNorthUp = true;

    private boolean panned() {
        return Math.abs(panX) > 0.01 || Math.abs(panZ) > 0.01;
    }

    /** A map offset in pixels (from the map's middle) as blocks in the world. */
    private double[] screenToWorld(double sx, double sy) {
        double right = sx / viewPpb;
        if (viewNorthUp) return new double[]{right, sy / viewPpb};
        double forward = -sy / viewPpb;
        return new double[]{-right * viewCos - forward * viewSin, -right * viewSin + forward * viewCos};
    }

    private static void setPan(double x, double z) {
        double len = Math.sqrt(x * x + z * z);
        if (len > MAX_PAN) {
            x *= MAX_PAN / len;
            z *= MAX_PAN / len;
        }
        panX = x;
        panZ = z;
    }

    /** Zooms, keeping the spot under the mouse where it is (null = zoom around the middle of the map). */
    private void zoomAt(int dir, Double mx, Double my) {
        if (mx == null) {
            zoom(dir);
            return;
        }
        double cx = mapX + mapSize / 2.0;
        double cy = mapY + mapSize / 2.0;
        double[] before = screenToWorld(mx - cx, my - cy);
        double oldPpb = viewPpb;
        zoom(dir);
        double newPpb = (mapSize / 2.0 - 8) / radar.windowRangeShown();
        if (Math.abs(newPpb - oldPpb) < 1e-9) return;
        viewPpb = newPpb;
        double[] after = screenToWorld(mx - cx, my - cy);
        setPan(panX + before[0] - after[0], panZ + before[1] - after[1]);
    }

    /** One step in / out through the zoom levels (dir +1 = further out). */
    private void zoom(int dir) {
        int current = radar.windowRangeShown();
        int index = 0;
        for (int i = 0; i < RANGES.length; i++) if (RANGES[i] <= current) index = i;
        // With terrain on, no zooming out past the loaded world
        int max = RANGES.length - 1;
        if (radar.windowTerrain() && !com.relaxedqol.client.module.modules.lovercraft.LiveMap.minimapTiles()) {
            while (max > 0 && RANGES[max] > radar.loadedLimit()) max--;
        }
        // At Spawn with the map's pictures: out to 2048 blocks (the whole of spawn)
        if (com.relaxedqol.client.module.modules.lovercraft.LiveMap.offMap()) {
            while (max > 0 && RANGES[max] > 2048) max--;
        }
        index = Math.max(0, Math.min(max, index + dir));
        // Never further out than your whole zone - at the furthest the map shows exactly your zone square
        double[] box = zoneBox();
        if (box != null) {
            int maxR = (int) Math.ceil(box[2] * 1.04);
            if (RANGES[index] >= maxR || (dir > 0 && current >= maxR)) {
                radar.setWindowRange(maxR);
                if (minecraft.player != null) setPan(box[0] - minecraft.player.getX(), box[1] - minecraft.player.getZ());
                return;
            }
        }
        radar.setWindowRange(RANGES[index]);
    }

    // ------------------------------------------------------------------ locate a player

    private final List<int[]> locateRects = new ArrayList<>();
    private final List<String> locateNames = new ArrayList<>();
    private List<Entry> lastEntries = new ArrayList<>();
    private String focusName;
    private long focusUntil;
    /** The map glides to this offset from you (NaN = not moving). */
    private double glideX = Double.NaN;
    private double glideZ;

    /** Zooms in on a player and glides the map to them (and keeps them highlighted for a moment). */
    private void locate(String name) {
        if (minecraft.player == null) return;
        for (Entry e : lastEntries) {
            if (!e.name().equals(name)) continue;
            Vec3 pos = e.player() != null ? e.player().position() : e.pos();
            if (pos == null) return;
            radar.setWindowRange(64);
            glideX = pos.x - minecraft.player.getX();
            glideZ = pos.z - minecraft.player.getZ();
            focusName = name;
            focusUntil = System.currentTimeMillis() + 4000;
            return;
        }
    }

    /** One step of the glide (called every frame before the map is drawn). */
    private void glide() {
        if (Double.isNaN(glideX)) return;
        double k = 0.22;
        setPan(panX + (glideX - panX) * k, panZ + (glideZ - panZ) * k);
        if (Math.abs(glideX - panX) < 0.5 && Math.abs(glideZ - panZ) < 0.5) {
            setPan(glideX, glideZ);
            glideX = Double.NaN;
        }
    }

    /** Your zone square {centre x, centre z, half width} from the live map's zones, or null. */
    private double[] zoneBox() {
        if (minecraft.player == null) return null;
        com.relaxedqol.client.module.modules.lovercraft.LiveMap live = com.relaxedqol.client.module.modules.lovercraft.LiveMap.get();
        return live == null ? null : live.zoneBoxAt(minecraft.player.getX(), minecraft.player.getZ());
    }

    /** The middle of the map stays inside your zone (move / zoom anywhere in it, not out of it). */
    private void clampToZone() {
        if (minecraft.player != null && com.relaxedqol.client.module.modules.lovercraft.LiveMap.offMap()) {
            // Spawn / Wildlands: with the map's own pictures of it you can look around all of it (up to 2048 blocks
            // away); without them only the part of the world the game has loaded
            double lim = com.relaxedqol.client.util.MapTiles.ownWorldShown()
                ? Math.max(16, 2048 - radar.windowRangeShown() / 2.0)
                : Math.max(16, radar.loadedLimit() - radar.windowRangeShown());
            setPan(Math.max(-lim, Math.min(lim, panX)), Math.max(-lim, Math.min(lim, panZ)));
            return;
        }
        double[] box = zoneBox();
        if (box == null || minecraft.player == null) return;
        double meX = minecraft.player.getX();
        double meZ = minecraft.player.getZ();
        // The view's EDGES stay inside your zone - you can look anywhere in it but never past it. (Zoomed all the
        // way out the view is exactly your zone.) Zooming at the mouse still works: only the part that would show
        // outside the zone is held back.
        double r = Math.min(radar.windowRangeShown(), box[2]);
        double lo = box[2] - r;
        double cx = Math.max(box[0] - lo, Math.min(box[0] + lo, meX + panX));
        double cz = Math.max(box[1] - lo, Math.min(box[1] + lo, meZ + panZ));
        panX = cx - meX;
        panZ = cz - meZ;
    }

    // ------------------------------------------------------------------ data

    private List<Entry> entries(float partialTick) {
        List<Entry> out = new ArrayList<>();
        if (minecraft.player == null) return out;
        Vec3 me = minecraft.player.getPosition(partialTick);
        Set<String> seen = new HashSet<>();
        for (Player player : radar.players()) {
            String name = Radar.nameOf(player);
            if (com.relaxedqol.client.util.Names.isFakeEntry(name, player.getUUID())) continue;
            Vec3 pos = player.getPosition(partialTick);
            double dx = pos.x - me.x;
            double dz = pos.z - me.z;
            out.add(new Entry(name, player, Math.sqrt(dx * dx + dz * dz), pos.y - me.y, EntityUtil.color(player)));
            seen.add(name.toLowerCase(Locale.ROOT));
        }
        // Players in your zone from the live web map - on the map (as rings) and in the list, in BOTH list modes
        // (they only came along with "List: Everyone" before, so far players never showed with "Nearby")
        com.relaxedqol.client.module.modules.lovercraft.LiveMap live = com.relaxedqol.client.module.modules.lovercraft.LiveMap.get();
        if (live != null && radar.liveMapPlayers()) {
            String self = minecraft.player.getGameProfile().name();
            for (var mp : live.inMyZoneSquare()) {
                if (mp.name().equalsIgnoreCase(self) || !seen.add(mp.name().toLowerCase(Locale.ROOT))) continue;
                double dx = mp.x() - me.x;
                double dz = mp.z() - me.z;
                out.add(new Entry(mp.name(), null, Math.sqrt(dx * dx + dz * dz), mp.y() - me.y, 0xFF9CA3AF, new Vec3(mp.x(), mp.y(), mp.z())));
            }
        }
        out.sort((a, b) -> Double.compare(a.dist(), b.dist()));
        if (radar.windowEveryone() && minecraft.getConnection() != null) {
            // Everyone on the tab list who is not on the map (too far away / other world)
            List<Entry> far = new ArrayList<>();
            String self = minecraft.player.getGameProfile().name();
            for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
                String name = info.getProfile().name();
                // Tab-list filler slots ("00009"), decorations and NPC entries are not players
                if (com.relaxedqol.client.util.Names.isFakeEntry(name, info.getProfile().id())) continue;
                if (name.equalsIgnoreCase(self) || !seen.add(name.toLowerCase(Locale.ROOT))) continue;
                // The live web map knows how far they are
                double mapDist = -1;
                Vec3 mapPos = null;
                com.relaxedqol.client.module.modules.lovercraft.LiveMap map = com.relaxedqol.client.module.modules.lovercraft.LiveMap.get();
                if (map != null) {
                    for (var mp : map.usable()) {
                        if (!mp.name().equalsIgnoreCase(name)) continue;
                        double dx = mp.x() - me.x;
                        double dz = mp.z() - me.z;
                        mapDist = Math.sqrt(dx * dx + dz * dz);
                        // On the map too (as a ring), when Live Map Players is on
                        if (radar.liveMapPlayers()) mapPos = new Vec3(mp.x(), mp.y(), mp.z());
                    }
                }
                far.add(new Entry(name, null, mapDist, mapPos != null ? mapPos.y - me.y : 0, mapPos != null ? 0xFF9CA3AF : Theme.TEXT_FAINT, mapPos));
            }
            far.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            out.addAll(far);
        }
        return out;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        computeLayout();
        rowRects.clear();
        rowNames.clear();
        dotSpots.clear();
        dotNames.clear();
        if (minecraft.player == null) return;
        List<Entry> entries = entries(partialTick);
        String hoverBefore = hoverName;
        hoverName = null;
        locateRects.clear();
        locateNames.clear();
        lastEntries = entries;
        // A located player stays highlighted for a few seconds
        if (hoverBefore == null && focusName != null && System.currentTimeMillis() < focusUntil) hoverBefore = focusName;
        drawMap(g, entries, mouseX, mouseY, partialTick, hoverBefore);
        drawList(g, entries, mouseX, mouseY, hoverBefore);
    }

    private void drawMap(GuiGraphicsExtractor g, List<Entry> entries, int mouseX, int mouseY, float partialTick, String highlight) {
        glide();
        clampToZone();
        int x = mapX;
        int y = mapY;
        int s = mapSize;
        int cx = x + s / 2;
        int cy = y + s / 2;
        int accent = Theme.accent();
        double range = radar.windowRangeShown();
        double ppb = (s / 2.0 - 8) / range;

        Render2D.roundRect(g, x, y, s, s, 10, 0xE00B0B12);
        boolean northUpMap = radar.windowNorthUp();
        if (radar.windowTerrain()) {
            // The map's middle is where you moved it to (you by default)
            Vec3 at = minecraft.player.getPosition(partialTick).add(panX, 0, panZ);
            double yawRad = Math.toRadians(minecraft.player.getViewYRot(partialTick));
            double rot = northUpMap ? 0 : Math.PI - yawRad;
            final int mcx = cx;
            final int mcy = cy;
            final int ms = s;
            final double mrange = range;
            // Clipped to the map's rounded corners (a square clip let the terrain stick out at the corners)
            boolean[] textured = {true};
            Render2D.clipRounded(g, x + 1, y + 1, s - 2, s - 2, 9, () -> {
                // The web map's picture underneath (beyond the loaded world)
                if (com.relaxedqol.client.module.modules.lovercraft.LiveMap.minimapTiles()) {
                    com.relaxedqol.client.util.MapTiles.draw(g, mcx, mcy, (ms / 2.0 - 8) / mrange, rot, at.x, at.z, ms / 2.0, 255);
                }
                if (textured[0] && !Minimap.drawTexture(g, true, mcx, mcy, ms / 2.0 - 8, mrange, rot, at.x, at.z, 215)) textured[0] = false;
            });
            if (!textured[0]) {
                g.enableScissor(x + 4, y + 4, x + s - 4, y + s - 4);
                Minimap.draw(g, cx, cy, s / 2.0 - 8, range, rot, 2.0, at.x, at.z, 200);
                g.disableScissor();
            }
            // Darken it a little so the names stay the brightest thing on the map
            Render2D.roundRect(g, x, y, s, s, 10, 0x30000000);
        }
        // A soft vignette so the map sinks into its frame (inside the rounded corners too)
        final int vx0 = x;
        final int vy0 = y;
        final int vs0 = s;
        Render2D.clipRounded(g, x + 1, y + 1, s - 2, s - 2, 9, () -> {
            g.fillGradient(vx0, vy0, vx0 + vs0, vy0 + 18, 0x70000000, 0x00000000);
            g.fillGradient(vx0, vy0 + vs0 - 18, vx0 + vs0, vy0 + vs0, 0x00000000, 0x80000000);
            Render2D.horizontalGradient(g, vx0, vy0, 16, vs0, 0x60000000, 0x00000000);
            Render2D.horizontalGradient(g, vx0 + vs0 - 16, vy0, 16, vs0, 0x00000000, 0x60000000);
        });
        Render2D.roundOutline(g, x, y, s, s, 10, Theme.BORDER);
        // Grid: cross + rings at a quarter, half and three quarters of the range, with their distance
        if (!radar.windowTerrain()) {
            g.fill(cx, y + 6, cx + 1, y + s - 6, 0x1CFFFFFF);
            g.fill(x + 6, cy, x + s - 6, cy + 1, 0x1CFFFFFF);
        }
        // Distance rings and their labels only in the radar look (terrain off) - the map speaks for itself
        for (int i = 1; i <= (radar.windowTerrain() || panned() ? 0 : 3); i++) {
            int r = (int) Math.round((s / 2.0 - 8) * i / 4.0);
            drawCircle(g, cx, cy, r, i == 2 ? 0x30FFFFFF : 0x1CFFFFFF);
            String label = Math.round(range * i / 4.0) + "m";
            // On the top of each ring (they sat on the cross line, right where player names go)
            int lw = font.width(label);
            int lx = cx + 3;
            int ly = cy - r - 5;
            Render2D.roundRect(g, lx - 3, ly - 1, lw + 6, 10, 4, 0x80000000);
            g.text(font, label, lx, ly, 0x90FFFFFF, false);
            taken.add(new int[]{lx - 3, ly - 1, lw + 6, 10});
        }

        boolean northUp = radar.windowNorthUp();
        if (northUp) {
            Render2D.roundRect(g, cx - 6, y + 3, 12, 11, 5, 0xC0101018);
            g.centeredText(font, "N", cx, y + 5, 0xFFFF6B6B);
        }
        double yaw = Math.toRadians(minecraft.player.getViewYRot(partialTick));
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        Vec3 me = minecraft.player.getPosition(partialTick);
        // Positions are measured from the map's middle (you, or the spot you moved it to)
        Vec3 view = me.add(panX, 0, panZ);
        viewPpb = ppb;
        viewCos = cos;
        viewSin = sin;
        viewNorthUp = northUp;
        int edge = s / 2 - 6;

        g.enableScissor(x + 2, y + 2, x + s - 2, y + s - 2);
        // Far ones first, so close players (and their names) are drawn on top
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.player() == null && e.pos() == null) continue;
            boolean fromMap = e.player() == null;
            Vec3 pos = fromMap ? e.pos() : e.player().getPosition(partialTick);
            double dx = pos.x - view.x;
            double dz = pos.z - view.z;
            double sx;
            double sy;
            if (northUp) {
                sx = dx * ppb;
                sy = dz * ppb;
            } else {
                double right = -dx * cos - dz * sin;
                double forward = -dx * sin + dz * cos;
                sx = right * ppb;
                sy = -forward * ppb;
            }
            boolean outside = Math.abs(sx) > edge || Math.abs(sy) > edge;
            if (outside) {
                // Beyond the range: a small marker on the edge, in their direction
                double k = edge / Math.max(Math.abs(sx), Math.abs(sy));
                sx *= k;
                sy *= k;
            }
            int ex = cx + (int) Math.round(sx);
            int ey = cy + (int) Math.round(sy);
            boolean hot = e.name().equals(highlight);
            int color = e.color();
            if (outside) {
                Render2D.roundRect(g, ex - 2, ey - 2, 4, 4, 1, Theme.withAlpha(color, hot ? 1f : 0.45f));
            } else if (radar.heads()) {
                // Their face instead of a dot (small, with a rim in their radar colour)
                int hs = 8;
                if (hot) Render2D.roundRect(g, ex - hs / 2 - 3, ey - hs / 2 - 3, hs + 6, hs + 6, 4, Theme.withAlpha(accent, 0.55f));
                Render2D.roundRect(g, ex - hs / 2 - 1, ey - hs / 2 - 1, hs + 2, hs + 2, 2, color);
                if (!com.relaxedqol.client.util.Heads.draw(g, radar.uuidOf(e.name(), fromMap ? null : e.player().getUUID()), ex - hs / 2, ey - hs / 2, hs, 0xFFFFFFFF)
                    && !com.relaxedqol.client.util.SkinFaces.draw(g, e.name(), ex - hs / 2, ey - hs / 2, hs, 0xFFFFFFFF)) {
                    Render2D.roundRect(g, ex - hs / 2, ey - hs / 2, hs, hs, 1, 0xFF2A2B31);
                }
                String label = e.name(); // No up / down arrows
                pendingLabels.add(new Object[]{label, ex, ey + 2, hot, color, e.dist()});
            } else {
                if (hot) Render2D.roundRect(g, ex - 5, ey - 5, 10, 10, 4, Theme.withAlpha(accent, 0.45f));
                if (fromMap) {
                    // A far player from the live web map - a ring instead of a filled dot
                    Render2D.roundRect(g, ex - 3, ey - 3, 6, 6, 3, 0xC0000000);
                    Render2D.roundOutline(g, ex - 3, ey - 3, 6, 6, 3, color);
                } else {
                    Render2D.roundRect(g, ex - 3, ey - 3, 6, 6, 2, color);
                }
                // Names are placed after all dots, so they never pile up on each other (see below)
                String label = e.name(); // No up / down arrows
                pendingLabels.add(new Object[]{label, ex, ey, hot, color, e.dist()});
            }
            dotSpots.add(new int[]{ex, ey});
            dotNames.add(e.name());
        }
        // Names without overlaps - hovered / nearest first, each one under its dot, else above, right or left;
        // when there is no free spot the name is left out (the dot stays, hover it to see who it is)
        pendingLabels.sort((a, b) -> {
            boolean ha = (Boolean) a[3];
            boolean hb = (Boolean) b[3];
            if (ha != hb) return ha ? -1 : 1;
            return Double.compare((Double) a[5], (Double) b[5]);
        });
        for (Object[] l : pendingLabels) {
            String label = (String) l[0];
            int ex = (Integer) l[1];
            int ey = (Integer) l[2];
            boolean hot = (Boolean) l[3];
            int color = (Integer) l[4];
            int tw = font.width(label);
            int[][] spots = {{ex - tw / 2, ey + 5}, {ex - tw / 2, ey - 14}, {ex + 6, ey - 4}, {ex - 6 - tw, ey - 4}};
            for (int[] sp : spots) {
                int[] rect = {sp[0] - 3, sp[1] - 1, tw + 6, 10};
                boolean free = true;
                for (int[] t : taken) {
                    if (rect[0] < t[0] + t[2] && rect[0] + rect[2] > t[0] && rect[1] < t[1] + t[3] && rect[1] + rect[3] > t[1]) {
                        free = false;
                        break;
                    }
                }
                if (!free && !hot) continue;
                // Cleaner names: a calm dark label with white text (the coloured text was hard to read on the map)
                Render2D.roundRect(g, rect[0], rect[1], rect[2], rect[3], 3, hot ? Theme.withAlpha(accent, 0.8f) : 0xC0101114);
                g.text(font, label, sp[0], sp[1], hot ? Theme.TEXT : color == 0xFF9CA3AF ? 0xFFC9CBD3 : 0xFFF2F2F6, false);
                taken.add(rect);
                break;
            }
        }
        pendingLabels.clear();
        taken.clear();
        g.disableScissor();

        // You: an arrow pointing the way you look (up when the map turns with you). where you are on the moved
        // map (pinned to the edge when you are off it)
        int mapCx = cx;
        int mapCy = cy;
        if (panned()) {
            double dx = -panX;
            double dz = -panZ;
            double sx;
            double sy;
            if (northUp) {
                sx = dx * ppb;
                sy = dz * ppb;
            } else {
                sx = (-dx * cos - dz * sin) * ppb;
                sy = -(-dx * sin + dz * cos) * ppb;
            }
            if (Math.abs(sx) > edge || Math.abs(sy) > edge) {
                double k = edge / Math.max(Math.abs(sx), Math.abs(sy));
                sx *= k;
                sy *= k;
            }
            cx = mapCx + (int) Math.round(sx);
            cy = mapCy + (int) Math.round(sy);
        }
        // You are one clean, smooth arrow (pointing where you look) with a dark rim - it was a stepped pixel shape
        if (!com.relaxedqol.client.module.modules.client.ClickGUI.lowEnd()) {
            Render2D.smoothCircle(g, cx + 0.5f, cy + 0.5f, 7f, Theme.withAlpha(accent, 0.18f), 3f);
        }
        Render2D.smoothArrow(g, cx + 0.5f, cy + 0.5f, 5.5f, northUp ? yaw + Math.PI : 0, Theme.mix(accent, 0xFFFFFFFF, 0.15f), 0xE0000000);

        // Map dot under the mouse
        if (mouseX >= x && mouseX < x + s && mouseY >= y && mouseY < y + s) {
            int best = -1;
            int bestD = 7 * 7;
            for (int i = 0; i < dotSpots.size(); i++) {
                int ddx = dotSpots.get(i)[0] - mouseX;
                int ddy = dotSpots.get(i)[1] - mouseY;
                int d = ddx * ddx + ddy * ddy;
                if (d <= bestD) {
                    bestD = d;
                    best = i;
                }
            }
            if (best >= 0) hoverName = dotNames.get(best);
        }
        cx = mapCx;
        cy = mapCy;
        // "back to you" while the map is moved
        recentreRect = null;
        if (panned()) {
            String back = "⌖ Back to you";
            int bw = font.width(back) + 10;
            int bx = x + 6;
            int by = y + 6;
            boolean over = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + 13;
            Render2D.roundRect(g, bx, by, bw, 13, 6, over ? Theme.withAlpha(accent, 0.85f) : 0xC0101018);
            Render2D.roundOutline(g, bx, by, bw, 13, 6, Theme.withAlpha(accent, 0.7f));
            g.text(font, back, bx + 5, by + 3, Theme.TEXT, false);
            recentreRect = new int[]{bx, by, bw, 13};
        }
        String hint = "Scroll: zoom  ·  Drag: move  ·  Click a player: profile";
        int hw = font.width(hint) + 10;
        Render2D.roundRect(g, x + (s - hw) / 2, y + s - 14, hw, 11, 5, 0x90000000);
        g.text(font, hint, x + (s - hw) / 2 + 5, y + s - 12, 0x90FFFFFF, false);
    }

    /** A dotted circle (range rings): a point every ~3 pixels along it. */
    /** Map labels waiting to be placed, and the spots already used this frame. */
    private final List<Object[]> pendingLabels = new ArrayList<>();
    private final List<int[]> taken = new ArrayList<>();

    private static void drawCircle(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        if (r < 2) return;
        int points = Math.max(12, (int) (2 * Math.PI * r / 3));
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            int px = cx + (int) Math.round(Math.cos(a) * r);
            int py = cy + (int) Math.round(Math.sin(a) * r);
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    /** A short straight line made of small squares (the facing arrow in North Up mode). */
    private static void drawLine(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= steps; i++) {
            int px = x0 + (steps == 0 ? 0 : (x1 - x0) * i / steps);
            int py = y0 + (steps == 0 ? 0 : (y1 - y0) * i / steps);
            g.fill(px - 1, py - 1, px + 1, py + 1, color);
        }
    }

    private void drawList(GuiGraphicsExtractor g, List<Entry> all, int mouseX, int mouseY, String highlight) {
        int x = listX;
        int y = listY;
        int w = listW;
        int h = listH;
        int accent = Theme.accent();
        String query = searchText.trim().toLowerCase(Locale.ROOT);
        List<Entry> entries = new ArrayList<>();
        for (Entry e : all) if (query.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(query)) entries.add(e);

        Render2D.roundRect(g, x, y, w, h, 8, 0x30000000);
        Render2D.roundOutline(g, x, y, w, h, 8, 0x14FFFFFF);
        int headerH = 14;
        g.text(font, Component.literal("PLAYERS").withStyle(net.minecraft.ChatFormatting.BOLD), x + 7, y + 4, Theme.TEXT_FAINT, false);
        String count = String.valueOf(entries.size());
        int cw = font.width(count) + 8;
        int hx = x + 7 + font.width(Component.literal("PLAYERS").withStyle(net.minecraft.ChatFormatting.BOLD)) + 5;
        Render2D.roundRect(g, hx, y + 3, cw, 10, 5, Theme.withAlpha(accent, 0.3f));
        g.text(font, count, hx + 4, y + 4, Theme.TEXT, false);
        int top = y + headerH + 2;
        int areaH = h - headerH - 4;
        if (entries.isEmpty()) {
            String msg = query.isEmpty() ? (radar.windowEveryone() ? "Nobody online" : "Nobody nearby") : "No match";
            g.centeredText(font, msg, x + w / 2, top + 10, Theme.TEXT_DIM);
            return;
        }
        int total = entries.size() * ROW;
        bar.update(x + w - 6, top, areaH, total, areaH);
        // Rows keep clear of the column's edge (they touched it, and the distance sat on the border)
        int rowW = w - (total > areaH ? 14 : 8);
        boolean overList = mouseX >= x && mouseX < x + 4 + rowW && mouseY >= top && mouseY < top + areaH;
        g.enableScissor(x + 1, top, x + w - 1, top + areaH);
        int ry = top - bar.scroll;
        for (Entry e : entries) {
            if (ry + ROW >= top && ry <= top + areaH) {
                boolean hover = overList && mouseY >= ry && mouseY < ry + ROW - 1;
                if (hover) hoverName = e.name();
                boolean hot = hover || e.name().equals(highlight);
                int rx = x + 4;
                Render2D.roundRect(g, rx, ry, rowW, ROW - 2, 5, hot ? Theme.withAlpha(accent, hover ? 0.42f : 0.25f) : 0x12FFFFFF);
                if (hover) Render2D.roundOutline(g, rx, ry, rowW, ROW - 2, 5, Theme.withAlpha(accent, 0.9f));
                // Their face (from the tab list), with a dot in their radar colour
                if (!com.relaxedqol.client.util.SkinFaces.draw(g, e.name(), rx + 4, ry + 2, 9, e.player() != null ? 0xFFFFFFFF : 0x90FFFFFF)) {
                    Render2D.roundRect(g, rx + 5, ry + 3, 7, 7, 2, 0x30FFFFFF);
                }
                Render2D.roundRect(g, rx + 11, ry + 8, 3, 3, 1, e.player() != null ? e.color() : 0x50FFFFFF);
                String dist = e.player() == null ? (e.dist() >= 0 ? shortDist(e.dist()) : "far") : Math.round(e.dist()) + "m";
                int dw = font.width(dist);
                // A locate button at the end of the row (only for players the map can show)
                boolean locatable = e.player() != null || e.pos() != null;
                int lb = 13;
                int lx = rx + rowW - lb - 2;
                int distRight = locatable ? lx - 5 : rx + rowW - 7;
                if (locatable) {
                    boolean overL = mouseX >= lx && mouseX < lx + lb && mouseY >= ry + 1 && mouseY < ry + ROW - 3 && overList;
                    boolean focused = e.name().equals(focusName) && System.currentTimeMillis() < focusUntil;
                    Render2D.roundRect(g, lx, ry + 1, lb, ROW - 4, 4, overL ? Theme.withAlpha(accent, 0.85f) : focused ? Theme.withAlpha(accent, 0.45f) : 0x1CFFFFFF);
                    // A small crosshair: ring + centre dot
                    int ic = overL || focused ? 0xFFFFFFFF : 0xFFB9BAC2;
                    int icx = lx + lb / 2;
                    int icy = ry + (ROW - 2) / 2;
                    Render2D.roundOutline(g, icx - 3, icy - 3, 7, 7, 3, ic);
                    g.fill(icx, icy, icx + 1, icy + 1, ic);
                    locateRects.add(new int[]{lx, Math.max(ry + 1, top), lb, Math.min(ry + ROW - 3, top + areaH) - Math.max(ry + 1, top)});
                    locateNames.add(e.name());
                }
                String name = e.name();
                int nameX = rx + 18;
                int room = distRight - dw - 6 - nameX;
                if (font.width(name) > room) {
                    while (name.length() > 3 && font.width(name + "\u2026") > room) name = name.substring(0, name.length() - 1);
                    name = name + "\u2026";
                }
                g.text(font, name, nameX, ry + 3, e.player() != null ? Theme.TEXT : Theme.TEXT_DIM, false);
                g.text(font, dist, distRight - dw, ry + 3, hover ? Theme.TEXT : Theme.TEXT_FAINT, false);
                rowRects.add(new int[]{rx, Math.max(ry, top), rowW, Math.min(ry + ROW - 2, top + areaH) - Math.max(ry, top)});
                rowNames.add(e.name());
            }
            ry += ROW;
        }
        g.disableScissor();
        bar.draw(g, mouseX, mouseY);
    }

    private static String shortDist(double d) {
        long m = Math.round(d);
        return m >= 10000 ? (m / 1000) + "k" : m >= 1000 ? String.format(Locale.ROOT, "%.1fk", m / 1000.0) : m + "m";
    }

    // ------------------------------------------------------------------ input

    @Override
    protected boolean contentClicked(double mx, double my, int button) {
        if (bar.mouseClicked(mx, my)) return true;
        if (button != 0) return false;
        for (int i = 0; i < locateRects.size(); i++) {
            int[] r = locateRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                locate(locateNames.get(i));
                return true;
            }
        }
        for (int i = 0; i < rowRects.size(); i++) {
            int[] r = rowRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                radar.openProfile(rowNames.get(i));
                return true;
            }
        }
        int[] rr = recentreRect;
        if (rr != null && mx >= rr[0] && mx < rr[0] + rr[2] && my >= rr[1] && my < rr[1] + rr[3]) {
            setPan(0, 0);
            return true;
        }
        // A press on the map - dragging moves the map, a plain click picks a player (on release)
        if (mx >= mapX && mx < mapX + mapSize && my >= mapY && my < mapY + mapSize) {
            mapPress = true;
            mapMoved = false;
            pressX = mx;
            pressY = my;
            pressPanX = panX;
            pressPanZ = panZ;
            return true;
        }
        return false;
    }

    /** A click on the map that did not move it: the player dot under it, if any. */
    private boolean clickMap(double mx, double my) {
        {
            int best = -1;
            double bestD = 7 * 7;
            for (int i = 0; i < dotSpots.size(); i++) {
                double dx = dotSpots.get(i)[0] - mx;
                double dy = dotSpots.get(i)[1] - my;
                double d = dx * dx + dy * dy;
                if (d <= bestD) {
                    bestD = d;
                    best = i;
                }
            }
            if (best >= 0) {
                radar.openProfile(dotNames.get(best));
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean contentDragged(double mx, double my) {
        if (mapPress) {
            if (!mapMoved && Math.abs(mx - pressX) + Math.abs(my - pressY) < 3) return true;
            mapMoved = true;
            glideX = Double.NaN;
            double[] d = screenToWorld(mx - pressX, my - pressY);
            setPan(pressPanX - d[0], pressPanZ - d[1]);
            return true;
        }
        return bar.mouseDragged(my);
    }

    @Override
    protected void contentReleased() {
        if (mapPress) {
            mapPress = false;
            if (!mapMoved) clickMap(pressX, pressY);
        }
        bar.release();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        double mx = vx(x);
        double my = vy(y);
        if (mx >= mapX && mx < mapX + mapSize && my >= mapY && my < mapY + mapSize) {
            // Zooms in on the spot under the mouse, not only on you
            zoomAt(scrollY > 0 ? -1 : 1, mx, my);
            return true;
        }
        bar.scrollBy(-(int) Math.signum(scrollY) * ROW * 2);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean typing = search != null && search.isFocused();
        // The Window Key closes the window again (unless you are typing a name)
        if (!typing && radar.windowKey() >= 0 && event.key() == radar.windowKey()) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }
}
