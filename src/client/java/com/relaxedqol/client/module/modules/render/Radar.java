package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.gui.HudWindow;
import com.relaxedqol.client.gui.RadarScreen;
import com.relaxedqol.client.gui.Render2D;
import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.ActionSetting;
import com.relaxedqol.client.setting.KeySetting;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.CommandUtil;
import com.relaxedqol.client.util.EntityUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Player radar. A small card in the corner that turns with you (up = the way you look), showing players only -
 * NPCs (plugin "players" with no real name / tab entry) are always left out.
 *
 * Open Window (or the Window Key) opens the big radar: a large, sharp map with every player's name, and next to it
 * the list of names. Click a name (in the list or on the map) to run "/profile NAME" (Profile Command).
 *
 * Performance: the players are collected at most 20 times a second (not every frame, not from every entity in the
 * world - only the level's player list), and only their positions are smoothed per frame.
 */
public class Radar extends Module {
    private final Setting<Integer> size = addSetting(new Setting<>("Size", 96, 60, 180, 4), "Size of the small HUD card");
    /** As far as your render distance by default (players further away are not sent by the server anyway). */
    private final Setting<String> rangeMode = addSetting(Setting.mode("Range Mode", "Render Distance", "Render Distance", "Custom"),
        "Render Distance: the card reaches as far as your render distance (as far as the server sends players)."
            + " Custom: use Range");
    private final Setting<Integer> range = addSetting(new Setting<>("Range", 64, 16, 512, 8), "Custom range in blocks (card edge)");
    private final Setting<String> style = addSetting(Setting.mode("Style", "Minimap", "Radar", "Minimap"),
        "Radar: the dark card. Minimap: the blocks and the world around you under the dots, like a minimap");
    private final Setting<Boolean> northUp = addSetting(new Setting<>("North Up", true),
        "North is always at the top (your arrow turns). Off: the map turns with you (up = the way you look)."
            + " Only for the minimap on the HUD - the big radar window is always north up");
    /** The minimap is zoomed in on its own (a whole render distance on a small card was an unreadable smear). */
    private final Setting<Integer> mapZoom = addSetting(new Setting<>("Minimap Zoom", 48, 16, 2048, 4),
        "Minimap style: how many blocks from you to the card's edge (smaller = more zoomed in). Players further away"
            + " still show, pinned to the edge");
    private final Setting<Boolean> edgePins = addSetting(new Setting<>("Edge Pins", false),
        "Players outside the card show on its edge, pointing the way they are");
    private final Setting<Boolean> names = addSetting(new Setting<>("Player Names", true), "Names under the dots on the HUD card");
    /** Faces instead of dots (card and big window). */
    private final Setting<Boolean> heads = addSetting(new Setting<>("Player Heads", true),
        "Show each player's face (small) instead of a coloured dot - on the card and in the big window");
    /** Players in your zone from the web map (LiveMap), also outside render distance. */
    private final Setting<Boolean> mapPlayers = addSetting(new Setting<>("Live Map Players", true),
        "Also show players from the live web map (LiveMap on) who are too far away for the game to send - as rings,"
            + " or pinned to the edge when they are off the card");
    private final Setting<Integer> headSize = addSetting(new Setting<>("Head Size", 7, 5, 12, 1).showIf(() -> this.heads.getValue()),
        "How big the faces are on the card");
    private final KeySetting windowKey = addSetting(new KeySetting("Window Key", -1, true),
        "Opens (and closes) the big radar window. Works even while the Radar card is off");
    private final Setting<String> profileCommand = addSetting(new Setting<>("Profile Command", "/profile {name}").hide());
    /** The big window's own zoom, map orientation and list mode (changed with its buttons, saved). */
    private final Setting<Integer> windowRange = addSetting(new Setting<>("Window Range", 128, 16, 8192, 8).hide());
    private final Setting<Boolean> windowEveryone = addSetting(new Setting<>("Window Everyone", false).hide());
    private final Setting<Boolean> windowTerrain = addSetting(new Setting<>("Window Terrain", true).hide());
    private final HudWindow window = new HudWindow(addSetting(HudWindow.xSetting()), addSetting(HudWindow.ySetting()),
        addSetting(HudWindow.scaleSetting()));

    /** Players collected on the last refresh (not you, alive, NPCs / cave players filtered out as set). */
    private List<Player> tracked = new ArrayList<>();
    private long trackedAt;
    private Object trackedLevel;
    private boolean windowKeyWasDown;

    /** Blocks from you to the card's edge (render distance or the Range setting). */
    public int rangeBlocks() {
        if (rangeMode.getValue().equals("Custom")) return range.getValue();
        return Math.max(32, Math.min(512, mc.options.getEffectiveRenderDistance() * 16));
    }

    public boolean minimap() {
        return style.getValue().equals("Minimap");
    }

    /** What the card shows from you to its edge: the minimap zoom, or the radar range. */
    public int cardRange() {
        // With the web map underneath, the minimap can zoom out past what the game has loaded
        if (minimap() && com.relaxedqol.client.module.modules.lovercraft.LiveMap.minimapTiles()) return mapZoom.getValue();
        return minimap() ? Math.min(mapZoom.getValue(), loadedLimit()) : rangeBlocks();
    }

    /**
     * The minimap never zooms out further than the world you have loaded (your render distance), so its
     * corners - even with the map turned - never show the empty void past the loaded chunks. The big radar window
     * keeps its own range.
     */
    public int loadedLimit() {
        // The world the SERVER really sent you (Hypixel sends only a few chunks around you, far less than your
        // render distance) - measured, not assumed, so the map is zoomed in onto real terrain instead of mostly black
        int chunks = Math.max(2, Math.min(mc.options.getEffectiveRenderDistance(), loadedRadius()));
        int blocks = chunks * 16;
        return Math.max(16, (int) Math.floor(blocks / Math.sqrt(2) * 0.85 - 8));
    }

    private int loadedRadiusCache = 8;
    private long loadedRadiusAt;

    /** How many chunks around you are really loaded (the smallest of the four directions), checked twice a second. */
    private int loadedRadius() {
        long now = System.currentTimeMillis();
        if (now - loadedRadiusAt < 500 || mc.level == null || mc.player == null) return loadedRadiusCache;
        loadedRadiusAt = now;
        int pcx = mc.player.chunkPosition().x(), pcz = mc.player.chunkPosition().z();
        int best = 32;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int r = 0;
            while (r < 32 && mc.level.hasChunk(pcx + d[0] * (r + 1), pcz + d[1] * (r + 1))) r++;
            best = Math.min(best, r);
        }
        loadedRadiusCache = Math.max(2, best);
        return loadedRadiusCache;
    }

    private int prefetchTimer;

    /**
     * Every few seconds the web map's pictures around you are fetched in the background (at the radar
     * window's zoom and the card's zoom), so opening the radar shows the map at once instead of loading it then.
     */
    private void prefetchTiles() {
        if (mc.player == null || !com.relaxedqol.client.module.modules.lovercraft.LiveMap.minimapTiles()) return;
        if (prefetchTimer-- > 0) return;
        prefetchTimer = 60;
        double gui = Math.max(1, mc.getWindow().getGuiScale());
        // The window's map: about 3/4 of the screen's height across
        double windowRadius = mc.getWindow().getHeight() * 0.38;
        double wr = Math.max(16, windowRangeShown());
        com.relaxedqol.client.util.MapTiles.prefetch(mc.player.getX(), mc.player.getZ(), windowRadius / wr, windowRadius);
        if (minimap()) {
            double cardRadius = 60 * gui;
            double cr = Math.max(16, cardRange());
            com.relaxedqol.client.util.MapTiles.prefetch(mc.player.getX(), mc.player.getZ(), cardRadius / cr, cardRadius);
        }
    }

    @Override
    public void onTick() {
        prefetchTiles();
        // While the big window shows terrain it gets the whole (bigger) area built, not just the card's
        boolean window = mc.gui.screen() instanceof com.relaxedqol.client.gui.RadarScreen && windowTerrain();
        if (window) com.relaxedqol.client.gui.Minimap.tick(Math.max(windowRangeShown(), minimap() ? cardRange() : 0));
        else if (minimap()) com.relaxedqol.client.gui.Minimap.tick(cardRange());
    }

    public Radar() {
        super("Radar", "Player radar card + a big radar window with a clickable name list (/profile)", ModuleCategory.LOVERCRAFT);
        addSetting(new ActionSetting("Open Window", this::openWindow),
            "The big radar: large map, sharp names, and a list of everyone on it - click a name to run /profile");
    }

    public HudWindow getWindow() {
        return window;
    }

    /** The radar card's own look (HUD editor). */
    private final com.relaxedqol.client.gui.CardStyle cardLook = new com.relaxedqol.client.gui.CardStyle("", this::addSetting);

    public com.relaxedqol.client.gui.CardStyle cardStyle() {
        return cardLook;
    }

    public boolean showsCard() {
        return isEnabled();
    }

    // ------------------------------------------------------------------ players

    /**
     * The players to show, refreshed at most every 50 ms (the card and the window share it). Positions are read
     * from the entities each frame, so movement stays smooth.
     */
    public List<Player> players() {
        long now = System.currentTimeMillis();
        if (mc.level == null || mc.player == null) {
            tracked = new ArrayList<>();
            trackedLevel = null;
            return tracked;
        }
        if (now - trackedAt < 50 && trackedLevel == mc.level) return tracked;
        trackedAt = now;
        trackedLevel = mc.level;
        List<Player> list = new ArrayList<>();
        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isRemoved()) continue;
            // NPCs are always left out (no more Hide NPCs switch)
            if (EntityUtil.isNpc(player)) continue;
            list.add(player);
        }
        tracked = list;
        return list;
    }

    /** The real account name (what /profile needs), not a nickname or team prefix. */
    public static String nameOf(Player player) {
        String name = player.getGameProfile().name();
        return name != null && !name.isEmpty() ? name : player.getName().getString();
    }

    public boolean hidesNpcs() {
        return true; // Always on
    }

    // ------------------------------------------------------------------ big window

    public void openWindow() {
        if (mc.player == null) return;
        mc.gui.setScreen(new RadarScreen(mc.gui.screen(), this));
    }

    /** Every tick, also while the card is off: the Window Key opens the big window. */
    public void keyTick() {
        int key = windowKey.getKey();
        boolean down = key >= 0 && mc.getWindow() != null && InputConstants.isKeyDown(mc.getWindow(), key);
        if (down && !windowKeyWasDown && mc.gui.screen() == null && mc.player != null) openWindow();
        windowKeyWasDown = down;
        // The big window's terrain keeps building while it is open, even with the card off
        if (!isEnabled() && mc.gui.screen() instanceof RadarScreen && windowTerrain()) {
            com.relaxedqol.client.gui.Minimap.tick(windowRangeShown());
        }
    }

    public int windowKey() {
        return windowKey.getKey();
    }

    /** Runs the Profile Command for this player (from the big window). */
    public void openProfile(String name) {
        if (name == null || name.isEmpty()) return;
        String template = "/profile {name}".trim();
        if (template.isEmpty()) template = "/profile {name}";
        String line = template.contains("{name}") ? template.replace("{name}", name) : template + " " + name;
        if (!line.startsWith("/") && !line.startsWith(".")) line = "/" + line;
        mc.gui.setScreen(null); // Always closes first, so the /profile menu is never hidden behind it
        CommandUtil.run(line);
    }

    public int windowRange() {
        return windowRange.getValue();
    }

    /** What the big window really shows: with terrain on never past the loaded world (render distance). */
    public int windowRangeShown() {
        if (windowTerrain() && com.relaxedqol.client.module.modules.lovercraft.LiveMap.minimapTiles()) return windowRange.getValue();
        return windowTerrain() ? Math.min(windowRange.getValue(), loadedLimit()) : windowRange.getValue();
    }

    public void setWindowRange(int blocks) {
        windowRange.setValue(blocks);
    }

    /**
     * The big radar window is always north up: it shows your zone from the live map, and a turning map there only
     * gets in the way. North Up / Turns With You is only for the minimap on the HUD.
     */
    public boolean windowNorthUp() {
        return true;
    }

    public boolean windowEveryone() {
        return windowEveryone.getValue();
    }

    public void setWindowEveryone(boolean on) {
        windowEveryone.setValue(on);
    }

    /** The skin id of a name from the tab list (map entries may have none, or a Bedrock one). */
    public java.util.UUID uuidOf(String name, java.util.UUID fallback) {
        return com.relaxedqol.client.util.Names.tabId(name, fallback);
    }

    public boolean liveMapPlayers() {
        return mapPlayers.getValue();
    }

    public boolean heads() {
        return heads.getValue();
    }

    public void setHeads(boolean on) {
        heads.setValue(on);
    }

    /** The window's Terrain button and the Style setting are one and the same (Minimap = terrain on). */
    public boolean windowTerrain() {
        return minimap();
    }

    public void setWindowTerrain(boolean on) {
        windowTerrain.setValue(on);
        style.setValue(on ? "Minimap" : "Radar");
        com.relaxedqol.client.ConfigManager.save(com.relaxedqol.client.ConfigManager.DEFAULT);
    }

    // ------------------------------------------------------------------ HUD card

    /**
     * A clip box that sits exactly on the card's edges. The game rounds a scaled clip box down (start and width
     * separately), so on a scaled HUD the map stopped 1-2 pixels short of the right and bottom edge and the dark
     * card showed there as a bar. Here the corners are worked out on screen and rounded to the nearest pixel.
     */
    private static void scissorExact(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        org.joml.Vector2f a = g.pose().transformPosition(x0, y0, new org.joml.Vector2f());
        org.joml.Vector2f b = g.pose().transformPosition(x1, y1, new org.joml.Vector2f());
        g.pose().pushMatrix();
        g.pose().identity();
        g.enableScissor(Math.round(Math.min(a.x, b.x)), Math.round(Math.min(a.y, b.y)),
            Math.round(Math.max(a.x, b.x)), Math.round(Math.max(a.y, b.y)));
        g.pose().popMatrix();
    }

    /** Drawn in HUD (virtual) coordinates. */
    public void render(GuiGraphicsExtractor g, Font font, int vw, int vh, float partialTicks) {
        if (mc.player == null || mc.level == null) return;
        int s = size.getValue();
        window.begin(g, vw, vh, s, s, vw - s - 6, vh - s - 6);
        int x = 0;
        int y = 0;
        int cx = x + s / 2;
        int cy = y + s / 2;
        int accent = Theme.accent();

        // A custom look from the HUD editor (background, edge, roundness, shadow)
        boolean custom = cardLook.isCustom();
        int body = custom ? Theme.withAlpha(com.relaxedqol.client.gui.HudStyle.bodyColor(), Math.max(0f, com.relaxedqol.client.gui.HudStyle.bodyAlpha())) : 0;
        // No drop shadow behind the minimap - next to the screen edge it showed as a black rim on the right
        if (!minimap() && (!custom || com.relaxedqol.client.gui.HudStyle.shadow())) Render2D.shadow(g, x, y, s, s, 8);
        // The minimap is a square picture (sharp corners, the map fills it); the radar keeps its round card
        if (minimap()) g.fill(x, y, x + s, y + s, custom ? body : 0xF0101018);
        else Render2D.roundRect(g, x, y, s, s, custom ? com.relaxedqol.client.gui.HudStyle.RADIUS : 8, custom ? body : 0xC0101018);
        if (custom && (com.relaxedqol.client.gui.HudStyle.EDGE >>> 24) != 0) {
            if (minimap()) Render2D.roundOutline(g, x, y, s, s, 0, com.relaxedqol.client.gui.HudStyle.EDGE);
            else Render2D.roundOutline(g, x, y, s, s, com.relaxedqol.client.gui.HudStyle.RADIUS, com.relaxedqol.client.gui.HudStyle.EDGE);
        }
        if (minimap()) {
            net.minecraft.world.phys.Vec3 at = mc.player.getPosition(partialTicks);
            double yawRad = Math.toRadians(mc.player.getViewYRot(partialTicks));
            double turn = northUp.getValue() ? 0 : Math.PI - yawRad;
            scissorExact(g, x, y, x + s, y + s);
            // The web map's picture underneath - it fills everything past the world the game has loaded
            if (com.relaxedqol.client.module.modules.lovercraft.LiveMap.minimapTiles()) {
                com.relaxedqol.client.util.MapTiles.draw(g, cx, cy, (s / 2.0 - 5) / cardRange(), turn, at.x, at.z, s / 2.0, 255);
            }
            // One sharp texture that covers the whole card (corners too); the old cell map if not possible
            if (!com.relaxedqol.client.gui.Minimap.drawTexture(g, false, cx, cy, s / 2.0 - 5, cardRange(), turn, at.x, at.z, 255)) {
                com.relaxedqol.client.gui.Minimap.draw(g, cx, cy, s / 2.0 - 5, cardRange(), turn, 1.5, at.x, at.z, 255);
            }
            g.disableScissor();
            // A crisp square frame
            g.fill(x, y, x + s, y + 1, 0x50FFFFFF);
            g.fill(x, y + s - 1, x + s, y + s, 0x50FFFFFF);
            g.fill(x, y + 1, x + 1, y + s - 1, 0x50FFFFFF);
            g.fill(x + s - 1, y + 1, x + s, y + s - 1, 0x50FFFFFF);
        }
        // Range ring and cross (the minimap only gets the map itself)
        if (!minimap()) {
            Render2D.roundOutline(g, x + s / 4, y + s / 4, s / 2, s / 2, 0x18FFFFFF);
            g.fill(cx, y + 4, cx + 1, y + s - 4, 0x18FFFFFF);
            g.fill(x + 4, cy, x + s - 4, cy + 1, 0x18FFFFFF);
        }

        double yaw = Math.toRadians(mc.player.getViewYRot(partialTicks));
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        net.minecraft.world.phys.Vec3 me = mc.player.getPosition(partialTicks);
        double px = me.x;
        double pz = me.z;
        double r = cardRange();
        double detect = Math.max(r, rangeBlocks());
        double pixelsPerBlock = (s / 2.0 - 5) / r;

        scissorExact(g, x + 2, y + 2, x + s - 2, y + s - 2);
        List<Object[]> labels = new ArrayList<>();
        // Everyone to show - players the game sent you, plus (LiveMap) players in your zone from the web map
        // who are too far away for the game to send
        List<Object[]> marks = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Player player : players()) {
            net.minecraft.world.phys.Vec3 pos = player.getPosition(partialTicks);
            marks.add(new Object[]{nameOf(player), player.getUUID(), pos.x, pos.z, EntityUtil.color(player), false});
            seen.add(nameOf(player).toLowerCase(java.util.Locale.ROOT));
        }
        com.relaxedqol.client.module.modules.lovercraft.LiveMap map = mapPlayers.getValue()
            ? com.relaxedqol.client.module.modules.lovercraft.LiveMap.get() : null;
        if (map != null) {
            for (var mp : map.inMyZoneSquare()) {
                if (!seen.add(mp.name().toLowerCase(java.util.Locale.ROOT))) continue;
                marks.add(new Object[]{mp.name(), uuidOf(mp.name(), mp.uuid()), mp.x(), mp.z(), 0xFF9CA3AF, true});
            }
        }
        // Where the N sits on the edge (worked out first, so the edge pins can make room for it)
        double nRight = northUp.getValue() ? 0 : sin;
        double nForward = northUp.getValue() ? 1 : -cos;
        double nHalf = s / 2.0 - 7;
        int nx = cx + (int) Math.round(nRight * nHalf);
        int ny = cy - (int) Math.round(nForward * nHalf);
        for (Object[] mk : marks) {
            String name = (String) mk[0];
            java.util.UUID uuid = (java.util.UUID) mk[1];
            double dx = (Double) mk[2] - px;
            double dz = (Double) mk[3] - pz;
            boolean fromMap = (Boolean) mk[5];
            double dist2 = dx * dx + dz * dz;
            boolean outside = dist2 > r * r;
            if (!fromMap && dist2 > detect * detect) continue;
            if (outside && !edgePins.getValue()) continue;
            // World offset into card space: north up, or turned so up = the way you look
            double right = northUp.getValue() ? dx : -dx * cos - dz * sin;
            double forward = northUp.getValue() ? -dz : -dx * sin + dz * cos;
            if (outside) {
                // Outside the card: pinned to its edge, in the direction they are
                double half = s / 2.0 - 6;
                double k = half / Math.max(Math.abs(right * pixelsPerBlock), Math.abs(forward * pixelsPerBlock));
                right *= k;
                forward *= k;
            }
            int ex = cx + (int) Math.round(right * pixelsPerBlock);
            int ey = cy - (int) Math.round(forward * pixelsPerBlock);
            // A pin that would sit on the N slides along the edge next to it, so the N is never covered
            if (outside && Math.abs(ex - nx) < 10 && Math.abs(ey - ny) < 10) {
                if (Math.abs(ey - cy) >= Math.abs(ex - cx)) ex = ex < nx || (ex == nx && right < 0) ? nx - 10 : nx + 10;
                else ey = ey < ny || (ey == ny && forward > 0) ? ny - 10 : ny + 10;
            }
            int color = (Integer) mk[4];
            if (outside) color = Theme.withAlpha(color, 0.7f);
            int labelY = ey + 3;
            if (heads.getValue() && !outside) {
                // Their face with a thin rim in their radar colour (map players: a grey rim, a little faded)
                int hs = headSize.getValue();
                int tint = fromMap ? 0xC8FFFFFF : 0xFFFFFFFF;
                Render2D.roundRect(g, ex - hs / 2 - 2, ey - hs / 2 - 2, hs + 4, hs + 4, 2, 0xC0000000);
                Render2D.roundRect(g, ex - hs / 2 - 1, ey - hs / 2 - 1, hs + 2, hs + 2, 1, color);
                if (!com.relaxedqol.client.util.Heads.draw(g, uuid, ex - hs / 2, ey - hs / 2, hs, tint)
                    && !com.relaxedqol.client.util.SkinFaces.draw(g, name, uuid, ex - hs / 2, ey - hs / 2, hs, tint)) {
                    g.fill(ex - hs / 2, ey - hs / 2, ex - hs / 2 + hs, ey - hs / 2 + hs, 0xFF2A2B31);
                }
                labelY = ey + hs / 2 + 2;
            } else if (fromMap && !outside) {
                // A far player from the web map: a ring instead of a filled dot
                Render2D.roundRect(g, ex - 3, ey - 3, 6, 6, 3, 0xC0000000);
                Render2D.roundOutline(g, ex - 3, ey - 3, 6, 6, 3, color);
            } else {
                if (minimap()) Render2D.roundRect(g, ex - 3, ey - 3, 6, 6, 2, 0xC0000000);
                Render2D.roundRect(g, ex - 2, ey - 2, 4, 4, 1, color);
            }
            if (names.getValue() && !outside) labels.add(new Object[]{name, ex, labelY, color, fromMap, dist2});
        }
        // Cleaner names: a tight, sharp label under each player, sized to the letters as they are really drawn,
        // nearest first - a name that would cover another one is left out (hover the big radar to see everyone)
        labels.sort((a, b) -> Double.compare((Double) a[5], (Double) b[5]));
        List<int[]> taken = new ArrayList<>();
        for (Object[] l : labels) {
            String name = (String) l[0];
            if (name.length() > 14) name = name.substring(0, 13) + "\u2026";
            int ex = (Integer) l[1];
            int ly = (Integer) l[2];
            boolean fromMap = (Boolean) l[4];
            float size = 0.7f;
            float tw = window.crispWidth(font, name, size);
            int pw = Math.round(tw) + 5;
            int ph = 7;
            int[] rect = {ex - pw / 2, ly, pw, ph};
            boolean free = true;
            for (int[] t : taken) {
                if (rect[0] < t[0] + t[2] && rect[0] + rect[2] > t[0] && rect[1] < t[1] + t[3] && rect[1] + rect[3] > t[1]) {
                    free = false;
                    break;
                }
            }
            if (!free) continue;
            taken.add(rect);
            Render2D.roundRect(g, rect[0], rect[1], pw, ph, 3, 0xB4101114);
            int text = fromMap ? 0xFFC9CBD3 : 0xFFF4F4F7;
            window.crispText(g, font, name, ex, ly + 0.5f, text, size, true, false);
        }
        g.disableScissor();

        // You, as one smooth arrow with a dark rim (the stepped pixel arrow looked like a bent stick); N marks
        // north on the edge
        Render2D.smoothArrow(g, cx + 0.5f, cy + 0.5f, 4.5f, northUp.getValue() ? yaw + Math.PI : 0,
            Theme.mix(accent, 0xFFFFFFFF, 0.15f), 0xE0000000);
        window.crispText(g, font, "N", nx + 1, ny - 4, 0xFFFF6B6B, 1.0f, true, true);
        window.end(g);
    }

    @Override
    public String getInfo() {
        return String.valueOf(tracked.size());
    }
}
