package com.relaxedqol.client.module.modules.lovercraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.relaxedqol.RelaxedQolMod;
import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.ActionSetting;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.ChatUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "LiveMap": reads where players are from the server's live web map (hub.loverfella.com/map) every few seconds,
 * in the background. Players in YOUR zone go onto the LocatorBar even when they are far outside your render distance,
 * the big Radar list shows how far they are, and PlayerAlert takes their zone from the map.
 *
 * The map's data address is found by itself (the map page and its scripts are searched for the players feed, then
 * the usual addresses of BlueMap / squaremap / Dynmap / Pl3xMap are tried). If that does not find it, open the map in
 * your browser, press F12 > Network, find the request that lists the players, and paste its address into Data URL.
 * NPCs are never used: only real names that are on the tab list.
 */
public class LiveMap extends com.relaxedqol.client.module.ActionModule {
    /** LoverCraft's live map player feed (squaremap). */
    private static final String LOVERCRAFT_FEED = "https://map.loverfella.com/map/survival/tiles/players.json";
    private final Setting<Integer> refresh = addSetting(new Setting<>("Refresh (s)", 3, 2, 30, 1),
        "How often the map is read (it runs in the background, nothing is sent to the game server)");
    private final Setting<Boolean> myZone = addSetting(new Setting<>("Only My Zone", true),
        "Only players in the same zone as you (radar, LocatorBar). The World Map can still show everyone");
    private final Setting<Boolean> locator = addSetting(new Setting<>("On LocatorBar", true),
        "Players from the map show on the LocatorBar, also when they are too far away for the game to send them");
    /** The web map's pictures in the game. */
    private final Setting<Boolean> minimapTiles = addSetting(new Setting<>("Web Map On Minimap", true),
        "The Radar minimap and the big Radar window show the web map's picture beyond what your game has loaded, so they"
            + " reach as far as you zoom out (Survival only - not in the Nether)");
    private final Setting<Boolean> feedAlert = addSetting(new Setting<>("Feed PlayerAlert", true),
        "PlayerAlert takes the zone of players on the map from here");

    /** One player read from the map. */
    /** world = the map / world (Survival, Spawn...), zone = the zone square inside it ("Zone 22"), when the map says it. */
    public record MapPlayer(String name, UUID uuid, double x, double y, double z, String world, String zone, boolean foreign) {}

    private volatile List<MapPlayer> players = List.of();
    private volatile long updatedAt;
    private volatile String status = "not read yet";
    private volatile String usedUrl = "";
    private volatile boolean busy;
    /** What the last read did (for Test / Status). */
    private volatile String pageResult = "";
    private volatile int tried;
    private volatile String lastError = "";
    /** The middle of every "Zone N" square on the map: {zone number, x, z}. Read from the map's markers. */
    private volatile List<double[]> zoneCenters = List.of();
    private volatile long zonesAt;
    private volatile String zonesUrl = "";
    private long nextAt;
    private int failures;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RelaxedQOL LiveMap");
        t.setDaemon(true);
        return t;
    });
    private HttpClient http;

    public LiveMap() {
        super("LiveMap", "Opens the live web map in the game. In the background it always reads where players are: radar"
            + " and LocatorBar beyond render distance, zones for PlayerAlert", ModuleCategory.LOVERCRAFT);
        addSetting(new ActionSetting("Test / Status", this::printStatus), "Reads the map now and tells you what it found");
        com.relaxedqol.client.util.MapTiles.configure(LOVERCRAFT_FEED, "minecraft_overworld");
    }

    /**
     * LiveMap is a button now - clicking it (or its keybind) opens the World Map. The map is always read in the
     * background, so this returns the module whenever its category is active.
     */
    public static LiveMap get() {
        var mm = RelaxedQolClient.moduleManager;
        if (mm == null) return null;
        LiveMap m = mm.getModule(LiveMap.class);
        return m != null && m.getCategory().active() ? m : null;
    }

    /** The button / keybind: opens the World Map. */
    @Override
    public void run() {
        openWorldMap();
    }

    public void openWorldMap() {
        if (mc.player == null) return;
        // Right away (after the click that asked for it) - it used to wait until the menu was closed
        nextAt = 0; // fresh players now
        mc.execute(() -> mc.gui.setScreen(new com.relaxedqol.client.gui.WorldMapScreen(null, this)));
    }

    /** The key that opens the map (the module's keybind) also closes it. */
    public int mapKey() {
        return getKey();
    }

    private static boolean offMapCached;
    private static long offMapAt;

    /**
     * You are somewhere the web map does not show - Spawn, the Wildlands (a separate area that is not on the live map,
     * whose coordinates overlap spawn / zone 1). Then the minimap and radar show only the real world around you: no web
     * map pictures, no map players, no zone limits. Known by the scoreboard's title ("WILDLANDS"), or by your own
     * entry on the map being somewhere else than you really are.
     */
    public static boolean offMap() {
        long now = System.currentTimeMillis();
        if (now - offMapAt < 500) return offMapCached;
        offMapAt = now;
        offMapCached = computeOffMap();
        return offMapCached;
    }

    /** "30th Oct • Spawn 1" / "1st Nov · Zone 19": the date, a dot, then the place. */
    private static final java.util.regex.Pattern PLACE_LINE = java.util.regex.Pattern.compile(
        "(?i)\\b\\d{1,2}(?:st|nd|rd|th)?\\s+[a-z]{3,9}\\.?\\s*[•·|\\-]\\s*(.+)$");

    private static boolean computeOffMap() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return false;
        // The scoreboard's title AND its lines ("WILDLANDS" may be a line, not the title; "Zone lands" under it)
        java.util.List<String> texts = new java.util.ArrayList<>(com.relaxedqol.client.util.TabInfo.sidebarLines());
        net.minecraft.network.chat.Component title = com.relaxedqol.client.util.TabInfo.sidebarTitle();
        if (title != null) texts.add(title.getString());
        String zone = com.relaxedqol.client.util.TabInfo.sidebarZone();
        if (zone != null) texts.add("zone " + zone);
        for (String line : texts) {
            if (line == null) continue;
            // The scoreboard's "30th Oct • Spawn 1" line says where you are - "Zone <number>" and Spawn are on the
            // web map; the Wildlands ("Zone lands") and any other place are separate worlds
            String plainLine = line.replaceAll("§.", "").trim();
            java.util.regex.Matcher place = PLACE_LINE.matcher(plainLine);
            if (place.find()) {
                String where = place.group(1).trim().toLowerCase(java.util.Locale.ROOT);
                // Spawn is on the web map too (the middle of Zone 1) - only other places are separate worlds
                if (!where.matches("(zone\\s*\\d+|spawn).*")) return true;
            }
            String t = line.replaceAll("§.", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
            if (t.contains("wildland") || t.contains("zonelands") || t.equals("zonelands") || t.endsWith("zonelands")) return true;
        }
        LiveMap m = com.relaxedqol.client.RelaxedQolClient.moduleManager == null ? null
            : com.relaxedqol.client.RelaxedQolClient.moduleManager.getModule(LiveMap.class);
        if (m == null || !m.fresh()) return false;
        String me = mc.player.getGameProfile().name();
        for (MapPlayer p : m.players) {
            if (!p.name().equalsIgnoreCase(me) || p.foreign()) continue;
            double dx = p.x() - mc.player.getX();
            double dz = p.z() - mc.player.getZ();
            // The map updates every few seconds: only a big difference means "not where the map thinks"
            return dx * dx + dz * dz > 600.0 * 600.0;
        }
        return false;
    }

    /** Whether the minimap / radar should draw web map tiles beyond the loaded world (module on or off). */
    public static boolean minimapTiles() {
        // At Spawn (not one of the zones): the map's own Spawn pictures when it has them
        if (offMap() && !com.relaxedqol.client.util.MapTiles.ownWorldShown()) return false;
        LiveMap m = com.relaxedqol.client.RelaxedQolClient.moduleManager == null ? null
            : com.relaxedqol.client.RelaxedQolClient.moduleManager.getModule(LiveMap.class);
        return m != null && m.minimapTiles.getValue() && m.getCategory().active()
            && com.relaxedqol.client.util.MapTiles.available();
    }


    /** Every player on the map (fresh data, any zone). */
    public List<MapPlayer> all() {
        return fresh() ? players : List.of();
    }

    private List<double[]> spacingFor;
    private double spacing;

    /**
     * The zone square a spot is in: {centre x, centre z, half its width}, or null when the zones are not known.
     * The zones are an even grid, so the width is the distance between neighbouring zone centres.
     */
    public double[] zoneBoxAt(double x, double z) {
        if (offMap()) return null; // The Wildlands have no zones
        return zoneSquare(x, z);
    }

    /**
     * The zone square around (x, z) on the map {centre x, centre z, half width} - wherever YOU are (also at spawn):
     * for the World Map, which shows the whole map's zones whether you are in one or not.
     */
    public double[] zoneSquare(double x, double z) {
        List<double[]> centers = zoneCenters;
        if (centers.size() < 4) return null;
        if (spacingFor != centers) {
            // The typical distance to the nearest neighbouring zone (the median), so one odd marker can not make
            // the zones look tiny
            List<Double> nearest = new ArrayList<>();
            for (int i = 0; i < centers.size(); i++) {
                double best = Double.MAX_VALUE;
                for (int j = 0; j < centers.size(); j++) {
                    if (i == j) continue;
                    double d = Math.max(Math.abs(centers.get(i)[1] - centers.get(j)[1]), Math.abs(centers.get(i)[2] - centers.get(j)[2]));
                    if (d > 64) best = Math.min(best, d);
                }
                if (best < Double.MAX_VALUE) nearest.add(best);
            }
            nearest.sort(Double::compare);
            spacing = nearest.isEmpty() ? 0 : nearest.get(nearest.size() / 2);
            spacingFor = centers;
        }
        if (spacing <= 0) return null;
        double[] pick = null;
        double bd = Double.MAX_VALUE;
        for (double[] c : centers) {
            double d = (c[1] - x) * (c[1] - x) + (c[2] - z) * (c[2] - z);
            if (d < bd) {
                bd = d;
                pick = c;
            }
        }
        return pick == null ? null : new double[]{pick[1], pick[2], spacing / 2.0};
    }

    /**
     * Every map player inside the zone SQUARE you stand in (by their position - no tab-list or zone-number
     * matching, which could leave people out). Falls back to usable() when the zones are not known.
     */
    public List<MapPlayer> inMyZoneSquare() {
        if (mc.player == null || offMap()) return List.of();
        double[] box = zoneBoxAt(mc.player.getX(), mc.player.getZ());
        if (box == null) return usable();
        String me = mc.player.getGameProfile().name();
        List<MapPlayer> out = new ArrayList<>();
        for (MapPlayer p : all()) {
            if (p.foreign() || p.name().equalsIgnoreCase(me)) continue;
            if (Math.abs(p.x() - box[0]) <= box[2] && Math.abs(p.z() - box[1]) <= box[2]) out.add(p);
        }
        return out;
    }

    /** The zone centres {number, x, z} found on the map. */
    public List<double[]> zoneCenters() {
        return zoneCenters;
    }

    public boolean feedsLocator() {
        return locator.getValue();
    }

    public boolean feedsAlert() {
        return feedAlert.getValue();
    }

    /** Data older than this is not used any more (the map stopped answering). */
    private boolean fresh() {
        return System.currentTimeMillis() - updatedAt < Math.max(15000, refresh.getValue() * 4000L);
    }

    // ------------------------------------------------------------------ what other modules use

    /** Players on the map you can use here: fresh, real names, not you, in your zone (if set). */
    private List<MapPlayer> usableCache = List.of();
    private long usableAt;
    private Object usableFor;

    public List<MapPlayer> usable() {
        // Worked out at most 4 times a second (it was every frame, for the radar, LocatorBar and the HUD list)
        long now = System.currentTimeMillis();
        if (usableFor == players && now - usableAt < 250) return usableCache;
        usableCache = computeUsable();
        usableAt = now;
        usableFor = players;
        return usableCache;
    }

    private List<MapPlayer> computeUsable() {
        if (!fresh() || mc.player == null || offMap()) return List.of();
        String me = mc.player.getGameProfile().name();
        // Only names on the tab list (keeps NPCs and stale map entries out) - always
        Set<String> tab = com.relaxedqol.client.util.Names.tabNames();
        String myWorld = myWorld();
        List<MapPlayer> out = new ArrayList<>();
        for (MapPlayer p : players) {
            if (p.name().equalsIgnoreCase(me)) continue;
            if (tab != null && !tab.contains(p.name().toLowerCase(Locale.ROOT))) continue;
            if (myZone.getValue() && !sameZone(p, myWorld)) continue;
            out.add(p);
        }
        return out;
    }

    /** A player on the map by name (any zone), or null. */
    public MapPlayer find(String name) {
        if (!fresh() || name == null) return null;
        for (MapPlayer p : players) if (p.name().equalsIgnoreCase(name)) return p;
        return null;
    }

    /** "Zone 22" (or the map's world name) for showing where someone is. */
    public static String zoneLabel(MapPlayer p) {
        if (p == null) return null;
        LiveMap self = get();
        if ((p.zone() == null || p.zone().isBlank()) && self != null) {
            String z = self.zoneAt(p.x(), p.z());
            if (z != null) return "Zone " + z;
        }
        if (p.zone() != null && !p.zone().isBlank()) {
            String d = p.zone().replaceAll("\\D", "");
            return d.isEmpty() ? p.zone() : "Zone " + d;
        }
        if (p.world() == null || p.world().isBlank()) return null;
        String w = p.world();
        int slash = Math.max(w.lastIndexOf('/'), w.lastIndexOf(':'));
        if (slash >= 0 && slash < w.length() - 1) w = w.substring(slash + 1);
        Matcher m = Pattern.compile("(?i)zone[ _-]*(\\d+)").matcher(w);
        if (m.find()) return "Zone " + m.group(1);
        return w.replace('_', ' ');
    }

    /** The world you are in on the map: your own entry there, else null. */
    private String myWorld() {
        if (mc.player == null) return null;
        MapPlayer me = find(mc.player.getGameProfile().name());
        return me != null ? me.world() : null;
    }

    private boolean sameZone(MapPlayer p, String myWorld) {
        if (p.foreign()) return false; // "Elsewhere" on the map / another world (BlueMap)
        // Another world (Survival / Eternal / Spawn tabs): never
        if (myWorld != null && p.world() != null && !p.world().isBlank() && !norm(myWorld).equals(norm(p.world()))) return false;
        // The zone square - from the map entry, compared with yours (your map entry, else the scoreboard)
        String mine = myZoneDigits();
        String theirs = p.zone() == null ? zoneAt(p.x(), p.z()) : p.zone().replaceAll("\\D", "");
        if (mine != null && theirs != null && !theirs.isEmpty()) return mine.equals(theirs);
        // The map gives no zone per player: same world is all we can check (the tab list filter keeps it to people
        // the server shows you)
        return true;
    }

    /** "22" - your zone: the square you stand in (from the map's zone markers), else your map entry / the scoreboard. */
    private String myZoneDigits() {
        if (mc.player != null) {
            String here = zoneAt(mc.player.getX(), mc.player.getZ());
            if (here != null) return here;
        }
        if (mc.player != null) {
            MapPlayer me = find(mc.player.getGameProfile().name());
            if (me != null && me.zone() != null) {
                String d = me.zone().replaceAll("\\D", "");
                if (!d.isEmpty()) return d;
            }
        }
        String sb = scoreboardZone();
        if (sb == null) return null;
        String d = sb.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }

    private static String norm(String s) {
        String t = s.toLowerCase(Locale.ROOT);
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf(':'));
        if (slash >= 0 && slash < t.length() - 1) t = t.substring(slash + 1);
        return t.replaceAll("[^a-z0-9]", "");
    }

    private static final Pattern ZONE_WORD = Pattern.compile("(?i)\\bzone\\s*[:#\\-]?\\s*([A-Za-z0-9_]+)");

    /** "22" from a scoreboard line like "28th Oct • Zone 22". */
    private static String scoreboardZone() {
        for (String line : com.relaxedqol.client.util.TabInfo.sidebarLines()) {
            Matcher m = ZONE_WORD.matcher(line.replaceAll("§.", ""));
            if (m.find()) return m.group(1);
        }
        net.minecraft.network.chat.Component title = com.relaxedqol.client.util.TabInfo.sidebarTitle();
        if (title != null) {
            Matcher m = ZONE_WORD.matcher(title.getString().replaceAll("§.", ""));
            if (m.find()) return m.group(1);
        }
        return com.relaxedqol.client.util.TabInfo.sidebarZone();
    }

    private Set<String> tabNames() {
        Set<String> out = new HashSet<>();
        if (mc.getConnection() == null) return out;
        for (net.minecraft.client.multiplayer.PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String n = info.getProfile().name();
            if (n != null && !com.relaxedqol.client.util.Names.isFakeEntry(n, info.getProfile().id())) out.add(n.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // ------------------------------------------------------------------ reading the map (background)

    /** Always, in the background (it used to need the module switched on). */
    @Override
    public void alwaysTick() {
        if (mc.player == null || busy) return;
        long now = System.currentTimeMillis();
        if (now < nextAt) return;
        // Slower when it keeps failing (no hammering a site that is down)
        nextAt = now + refresh.getValue() * 1000L * Math.min(10, 1 + failures);
        busy = true;
        worker.submit(() -> {
            try {
                readOnce("", "", "");
            } catch (Throwable t) {
                status = "error: " + t.getClass().getSimpleName();
                failures++;
            } finally {
                busy = false;
            }
        });
    }


    private HttpClient http() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build();
        }
        return http;
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
            .header("User-Agent", "Mozilla/5.0 RelaxedQOL-LiveMap").header("Accept", "application/json, text/plain, */*").GET().build();
        HttpResponse<String> res = http().send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new java.io.IOException("HTTP " + res.statusCode() + " from " + url);
        return res.body();
    }

    private void readOnce(String manual, String known, String page) {
        tried = 0;
        lastError = "";
        String url = !manual.isEmpty() ? manual : !known.isEmpty() ? known : LOVERCRAFT_FEED;
        if (!url.isEmpty()) {
            Parsed p = tryUrl(url);
            if (p != null) {
                accept(url, p);
                readZones(url, p);
                return;
            }
            if (!manual.isEmpty()) {
                status = "Data URL gives no player list";
                failures++;
                return;
            }
        }
        // Find it: addresses named in the map page / its scripts first, then the usual ones
        for (String candidate : candidates(page)) {
            Parsed p = tryUrl(candidate);
            if (p != null) {
                String found = candidate;
                accept(found, p);
                readZones(found, p);
                return;
            }
        }
        status = "the map did not answer (map.loverfella.com down, or blocked by your network)";
        failures++;
    }

    private void accept(String url, Parsed p) {
        // The map's pictures live next to players.json, in the folder of the world most players are in
        java.util.Map<String, Integer> worlds = new java.util.HashMap<>();
        for (MapPlayer mp : p.players) if (mp.world() != null) worlds.merge(mp.world(), 1, Integer::sum);
        String top = null;
        int best = 0;
        for (var e : worlds.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                top = e.getKey();
            }
        }
        com.relaxedqol.client.util.MapTiles.configure(url, top);
        players = Collections.unmodifiableList(p.players);
        // Somewhere else than the zones (Spawn): that world's pictures from the map, if it has them
        if (mc.player != null) {
            String me = mc.player.getGameProfile().name();
            String mine = null;
            for (MapPlayer mp : p.players) if (mp.name().equalsIgnoreCase(me)) mine = mp.world();
            com.relaxedqol.client.util.MapTiles.setOwnWorld(mine, mc.player.getX(), mc.player.getZ());
        }
        updatedAt = System.currentTimeMillis();
        usedUrl = url;
        failures = 0;
        status = p.players.size() + " players on the map";
    }

    /** The result of one read: players found, and whether it looked like a player feed at all. */
    private static final class Parsed {
        final List<MapPlayer> players = new ArrayList<>();
        boolean looksRight;
    }

    private Parsed tryUrl(String url) {
        tried++;
        try {
            String body = get(url);
            String t = body.trim();
            if (!(t.startsWith("{") || t.startsWith("["))) return null;
            JsonElement root = JsonParser.parseString(t);
            Parsed p = new Parsed();
            walk(root, null, p, 0);
            return p.looksRight || !p.players.isEmpty() ? p : null;
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return null;
        }
    }

    private static final String[] NAME_KEYS = {"account", "username", "ign", "playerName", "name", "player", "displayName", "display_name"};
    private static final String[] WORLD_KEYS = {"world", "dimension", "dim", "worldName", "world_name", "map", "mapId", "server"};
    /** LoverCraft's map is ONE world cut into zone squares - the zone is its own field, not the world. */
    private static final String[] ZONE_KEYS = {"zone", "zoneName", "zone_name", "zoneId", "region", "area", "shard", "instance"};
    private static final String[] POS_KEYS = {"position", "pos", "location", "loc", "coords", "coordinates"};
    private static final Pattern NAME_OK = Pattern.compile("[.*~!+]?[A-Za-z0-9_]{3,16}");

    /** Goes through any JSON shape and picks out objects that look like a player with coordinates. */
    private void walk(JsonElement e, String world, Parsed out, int depth) {
        if (e == null || depth > 12 || out.players.size() > 2000) return;
        if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) walk(c, world, out, depth + 1);
            return;
        }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        if (o.has("players") || o.has("onlinePlayers") || o.has("online_players")) out.looksRight = true;
        String w = string(o, WORLD_KEYS);
        if (w != null) world = w;
        MapPlayer p = player(o, world);
        if (p != null) {
            out.players.add(p);
            out.looksRight = true;
            return;
        }
        for (String k : o.keySet()) walk(o.get(k), world, out, depth + 1);
    }

    private MapPlayer player(JsonObject o, String world) {
        String name = string(o, NAME_KEYS);
        if (name == null) return null;
        name = name.replaceAll("§.", "").replaceAll("<[^>]+>", "").trim();
        if (!NAME_OK.matcher(name).matches()) return null;
        if (isNpc(o)) return null;
        double[] xyz = coords(o);
        if (xyz == null) {
            for (String k : POS_KEYS) {
                if (o.has(k)) {
                    JsonElement pe = o.get(k);
                    if (pe.isJsonObject()) xyz = coords(pe.getAsJsonObject());
                    else if (pe.isJsonArray()) xyz = coords(pe.getAsJsonArray());
                    if (xyz != null) break;
                }
            }
        }
        if (xyz == null) return null;
        UUID id = null;
        String u = string(o, new String[]{"uuid", "id"});
        if (u != null) {
            try {
                id = UUID.fromString(u.length() == 32 ? u.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5") : u);
            } catch (IllegalArgumentException ignored) {
                // not a uuid
            }
        }
        boolean foreign = false;
        for (String k : new String[]{"foreign", "elsewhere", "hidden", "offMap"}) {
            if (o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean() && o.get(k).getAsBoolean()) foreign = true;
        }
        String zone = string(o, ZONE_KEYS);
        if (zone == null) {
            Double zn = number(o, "zone");
            if (zn != null) zone = String.valueOf(zn.intValue());
        }
        return new MapPlayer(name, id, xyz[0], xyz[1], xyz[2], world, zone, foreign);
    }

    private static boolean isNpc(JsonObject o) {
        for (String k : new String[]{"npc", "isNpc", "is_npc", "fake"}) {
            if (o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isBoolean() && o.get(k).getAsBoolean()) return true;
        }
        String type = string(o, new String[]{"type", "kind", "entityType"});
        return type != null && !type.toLowerCase(Locale.ROOT).contains("player");
    }

    private static double[] coords(JsonObject o) {
        Double x = number(o, "x");
        Double z = number(o, "z");
        if (x == null || z == null) return null;
        Double y = number(o, "y");
        return new double[]{x, y == null ? 64 : y, z};
    }

    private static double[] coords(JsonArray a) {
        if (a.size() < 2) return null;
        try {
            if (a.size() == 2) return new double[]{a.get(0).getAsDouble(), 64, a.get(1).getAsDouble()};
            return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Double number(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return null;
        JsonPrimitive p = o.get(key).getAsJsonPrimitive();
        try {
            return p.isNumber() ? p.getAsDouble() : p.isString() ? Double.parseDouble(p.getAsString()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String string(JsonObject o, String[] keys) {
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isString()) {
                String v = o.get(k).getAsString();
                if (!v.isBlank()) return v;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ finding the data address

    private static final Pattern QUOTED = Pattern.compile("[\"'`]((?:https?:)?/?/?[A-Za-z0-9_.\\-/{}$:?=&%]*?(?:player|online|live|marker)[A-Za-z0-9_.\\-/{}$:?=&%]*)[\"'`]",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT = Pattern.compile("<script[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private List<String> candidates(String page) {
        Set<String> out = new LinkedHashSet<>();
        URI base;
        try {
            base = URI.create(page.isEmpty() ? "https://hub.loverfella.com/map" : page);
        } catch (IllegalArgumentException e) {
            return new ArrayList<>(out);
        }
        String origin = base.getScheme() + "://" + base.getHost();
        try {
            String html = get(base.toString());
            pageResult = "map page read (" + html.length() + " characters)";
            List<String> texts = new ArrayList<>();
            texts.add(html);
            Matcher sm = SCRIPT.matcher(html);
            int scripts = 0;
            while (sm.find() && scripts < 8) {
                try {
                    texts.add(get(base.resolve(sm.group(1)).toString()));
                    scripts++;
                } catch (Exception ignored) {
                    // next script
                }
            }
            for (String text : texts) {
                Matcher m = QUOTED.matcher(text);
                while (m.find() && out.size() < 40) {
                    String path = m.group(1);
                    if (path.contains("${") || path.contains("{") || path.endsWith(".png") || path.endsWith(".js") || path.endsWith(".css")) continue;
                    if (!path.contains("/")) continue;
                    try {
                        out.add(path.startsWith("http") ? path : path.startsWith("//") ? base.getScheme() + ":" + path : base.resolve(path).toString());
                    } catch (IllegalArgumentException ignored) {
                        // not an address
                    }
                }
            }
        } catch (Exception e) {
            pageResult = "map page NOT readable: " + e.getClass().getSimpleName() + (e.getMessage() != null ? " " + e.getMessage() : "");
            RelaxedQolMod.LOGGER.debug("LiveMap: map page not readable", e);
        }
        String p = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
        for (String guess : new String[]{"/api/map/players", "/api/players", "/api/live/players", "/api/map/live", "/api/map",
            "/players.json", "/tiles/players.json", "/live/players.json", "/maps/world/live/players.json", "/data/players.json",
            "/api/online", "/players"}) {
            out.add(origin + p + guess);
            out.add(origin + guess);
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ zone squares from the map's markers

    /** The zone (number as text) whose middle is nearest to x / z - the zones are an even grid. Null = not known. */
    public String zoneAt(double x, double z) {
        List<double[]> centers = zoneCenters;
        if (centers.isEmpty()) return null;
        double best = Double.MAX_VALUE;
        double[] pick = null;
        for (double[] c : centers) {
            double d = (c[1] - x) * (c[1] - x) + (c[2] - z) * (c[2] - z);
            if (d < best) {
                best = d;
                pick = c;
            }
        }
        return pick == null ? null : String.valueOf((int) pick[0]);
    }

    private static final Pattern ZONE_LABEL = Pattern.compile("(?i)zone\\s*#?\\s*(\\d+)");

    /**
     * Squaremap keeps its markers next to players.json: ".../tiles/WORLD/markers.json". Every marker whose label /
     * tooltip says "Zone N" gives that zone's middle (the average of its points). Read again every 10 minutes.
     */
    private void readZones(String playersUrl, Parsed p) {
        if (System.currentTimeMillis() - zonesAt < 10 * 60 * 1000L && !zoneCenters.isEmpty()) return;
        zonesAt = System.currentTimeMillis();
        if (!playersUrl.contains("players.json")) return;
        Set<String> worlds = new LinkedHashSet<>();
        for (MapPlayer mp : p.players) if (mp.world() != null && !mp.world().isBlank()) worlds.add(mp.world());
        worlds.add("minecraft_overworld");
        worlds.add("world");
        for (String world : worlds) {
            String url = playersUrl.replace("players.json", world + "/markers.json");
            try {
                JsonElement root = JsonParser.parseString(get(url));
                java.util.Map<Integer, double[]> sums = new java.util.TreeMap<>();
                collectZones(root, sums, 0);
                if (sums.isEmpty()) continue;
                List<double[]> out = new ArrayList<>();
                for (var e : sums.entrySet()) {
                    double[] v = e.getValue();
                    if (v[2] > 0) out.add(new double[]{e.getKey(), v[0] / v[2], v[1] / v[2]});
                }
                zoneCenters = Collections.unmodifiableList(out);
                zonesUrl = url;
                return;
            } catch (Exception ignored) {
                // next world name
            }
        }
    }

    /** Finds "Zone N" markers anywhere in the markers file and adds up their points (zone -> {sum x, sum z, count}). */
    private void collectZones(JsonElement e, java.util.Map<Integer, double[]> sums, int depth) {
        if (e == null || depth > 14) return;
        if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) collectZones(c, sums, depth + 1);
            return;
        }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        Integer zone = null;
        for (String k : new String[]{"tooltip", "popup", "label", "name", "title", "text", "id"}) {
            if (!o.has(k) || !o.get(k).isJsonPrimitive()) continue;
            Matcher m = ZONE_LABEL.matcher(o.get(k).getAsString().replaceAll("<[^>]+>", " "));
            if (m.find()) {
                zone = Integer.parseInt(m.group(1));
                break;
            }
        }
        if (zone != null) {
            double[] acc = new double[3];
            addPoints(o, acc, 0);
            if (acc[2] > 0) {
                double[] sum = sums.computeIfAbsent(zone, k -> new double[3]);
                // One centre per marker (a big polygon does not outweigh a label)
                sum[0] += acc[0] / acc[2];
                sum[1] += acc[1] / acc[2];
                sum[2] += 1;
                return;
            }
        }
        for (String k : o.keySet()) collectZones(o.get(k), sums, depth + 1);
    }

    /** Adds every {x, z} point inside an element (points, corners, the icon's point...). */
    private static void addPoints(JsonElement e, double[] acc, int depth) {
        if (e == null || depth > 10) return;
        if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray()) addPoints(c, acc, depth + 1);
            return;
        }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        Double x = number(o, "x");
        Double z = number(o, "z");
        if (x != null && z != null) {
            acc[0] += x;
            acc[1] += z;
            acc[2] += 1;
            return;
        }
        for (String k : o.keySet()) addPoints(o.get(k), acc, depth + 1);
    }

    // ------------------------------------------------------------------ status

    /**
     * Test / Status now really reads the map (also while the module is off) and reports when the answer is in -
     * it used to print right away, before anything was read ("not read yet").
     */
    private void printStatus() {
        ChatUtil.send("§7LiveMap: reading the map...");
        if (busy) {
            ChatUtil.send("§7LiveMap: a read is already running - press Test again in a few seconds");
            return;
        }
        busy = true;
        worker.submit(() -> {
            try {
                readOnce("", "", "");
            } catch (Throwable t) {
                status = "error: " + t.getClass().getSimpleName();
            } finally {
                busy = false;
                nextAt = System.currentTimeMillis() + refresh.getValue() * 1000L;
                mc.execute(this::report);
            }
        });
    }

    private void report() {
        boolean ok = updatedAt > 0 && fresh();
        ChatUtil.send((ok ? "§aLiveMap: §f" : "§cLiveMap: §f") + status);
        if (!pageResult.isEmpty()) ChatUtil.send("§7  " + pageResult);
        ChatUtil.send("§7  zones on the map: §f" + zoneCenters.size() + (zonesUrl.isEmpty() ? " §8(no zone markers found)" : ""));
        if (!ok && !lastError.isEmpty()) ChatUtil.send("§7  last error: §f" + lastError);
        String w = myWorld();
        String sb = scoreboardZone();
        String zd = myZoneDigits();
        ChatUtil.send("§7  your world: §f" + (w != null ? w : "?") + "§7, your zone: §f" + (zd != null ? zd : "unknown")
            + "§7, players on map: §f" + players.size() + "§7, usable: §f" + usable().size());
        ChatUtil.send("§7  separate area (Spawn / Wildlands, not on the map): §f" + (offMap() ? "yes - web map off here" : "no"));
        if (zd == null) {
            // Show what the scoreboard holds, so the zone line can be found
            java.util.List<String> lines = com.relaxedqol.client.util.TabInfo.sidebarLines();
            ChatUtil.send("§7  scoreboard lines read: §f" + lines.size());
            for (int i = 0; i < Math.min(6, lines.size()); i++) ChatUtil.send("§8    " + lines.get(i).replaceAll("§.", ""));
            net.minecraft.network.chat.Component title = com.relaxedqol.client.util.TabInfo.sidebarTitle();
            if (title != null) ChatUtil.send("§8    title: " + title.getString().replaceAll("§.", ""));
        }
        if (!ok) ChatUtil.send("§7  It tries again every few seconds. If it keeps failing, send these lines.");
    }

    @Override
    public String getInfo() {
        return fresh() ? String.valueOf(usable().size()) : null;
    }
}
