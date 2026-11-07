package com.relaxedqol.client.waypoint;

import com.relaxedqol.RelaxedQolMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Saved waypoints, per server and dimension, in config/relaxed-qol/waypoints.txt. */
public final class WaypointManager {
    /** visible: shown in the world (the waypoint menu's eye toggle). */
    public record Waypoint(String server, String dimension, String name, int x, int y, int z, int color, boolean death, long time,
                           boolean visible) {
        public Waypoint(String server, String dimension, String name, int x, int y, int z, int color, boolean death, long time) {
            this(server, dimension, name, x, y, z, color, death, time, true);
        }

        String serialize() {
            return String.join("|", server, dimension, name.replace("|", "/"), String.valueOf(x), String.valueOf(y), String.valueOf(z),
                Integer.toHexString(color), death ? "death" : "manual", String.valueOf(time), visible ? "shown" : "hidden");
        }

        static Waypoint parse(String line) {
            String[] p = line.split("\\|");
            if (p.length < 8) return null;
            try {
                return new Waypoint(p[0], p[1], p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]),
                    (int) Long.parseLong(p[6], 16), p[7].equals("death"), p.length > 8 ? Long.parseLong(p[8]) : 0,
                    p.length <= 9 || !p[9].equals("hidden"));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static final List<Waypoint> WAYPOINTS = new CopyOnWriteArrayList<>();
    private static boolean loaded;
    private static final int[] COLORS = {0xFF22D3EE, 0xFFA855F7, 0xFF34D399, 0xFFFBBF24, 0xFFF472B6, 0xFF60A5FA, 0xFFF97316};

    private WaypointManager() {}

    private static Path file() throws IOException {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("relaxed-qol");
        Files.createDirectories(dir);
        return dir.resolve("waypoints.txt");
    }

    public static void load() {
        loaded = true;
        WAYPOINTS.clear();
        try {
            Path file = file();
            if (!Files.exists(file)) return;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                Waypoint w = Waypoint.parse(line.trim());
                if (w != null) WAYPOINTS.add(w);
            }
        } catch (IOException e) {
            RelaxedQolMod.LOGGER.error("Failed to load waypoints", e);
        }
    }

    public static void save() {
        List<String> lines = new ArrayList<>();
        lines.add("# server|dimension|name|x|y|z|color|type|time|shown");
        for (Waypoint w : WAYPOINTS) lines.add(w.serialize());
        try {
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            RelaxedQolMod.LOGGER.error("Failed to save waypoints", e);
        }
    }

    /** "play.example.net" or "singleplayer:My World". */
    public static String currentServer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) return "singleplayer:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        if (mc.getConnection() != null && mc.getConnection().getServerData() != null) {
            return mc.getConnection().getServerData().ip.toLowerCase(Locale.ROOT);
        }
        return "unknown";
    }

    public static String currentDimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.dimension().identifier().toString() : "?";
    }

    /** Waypoints of this server (all dimensions). */
    public static List<Waypoint> forServer() {
        if (!loaded) load();
        String server = currentServer();
        List<Waypoint> result = new ArrayList<>();
        for (Waypoint w : WAYPOINTS) if (w.server().equals(server)) result.add(w);
        return result;
    }

    public static Waypoint add(String name, int x, int y, int z, boolean death) {
        if (!loaded) load();
        remove(name);
        int color = death ? 0xFFEF4444 : COLORS[Math.floorMod(name.hashCode(), COLORS.length)];
        Waypoint w = new Waypoint(currentServer(), currentDimension(), name, x, y, z, color, death, System.currentTimeMillis());
        WAYPOINTS.add(w);
        save();
        return w;
    }

    /** Removes the waypoint closest to the player on this server. Returns its name, or null. */
    public static String removeNearest() {
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return null;
        String server = currentServer();
        Waypoint best = null;
        double bestSq = Double.MAX_VALUE;
        for (Waypoint w : WAYPOINTS) {
            if (!w.server().equals(server)) continue;
            double dsq = player.distanceToSqr(w.x() + 0.5, w.y(), w.z() + 0.5);
            if (dsq < bestSq) {
                bestSq = dsq;
                best = w;
            }
        }
        if (best == null) return null;
        WAYPOINTS.remove(best);
        save();
        return best.name();
    }

    /** The colours the waypoint menu offers. */
    public static final int[] PALETTE = {0xFFEF4444, 0xFFF97316, 0xFFFBBF24, 0xFFA3E635, 0xFF34D399, 0xFF22D3EE, 0xFF60A5FA,
        0xFF818CF8, 0xFFA855F7, 0xFFF472B6, 0xFFF4F4F8, 0xFF94A3B8};

    /** A new waypoint with everything set (the waypoint menu). Replaces one of the same name on this server. */
    public static Waypoint addFull(String name, String dimension, int x, int y, int z, int color, boolean visible) {
        if (!loaded) load();
        remove(name);
        Waypoint w = new Waypoint(currentServer(), dimension, name, x, y, z, color, false, System.currentTimeMillis(), visible);
        WAYPOINTS.add(w);
        save();
        return w;
    }

    /** Swaps a waypoint for an edited copy (same place in the list). */
    public static void update(Waypoint old, Waypoint edited) {
        int i = WAYPOINTS.indexOf(old);
        if (i >= 0) WAYPOINTS.set(i, edited);
        else WAYPOINTS.add(edited);
        save();
    }

    public static void delete(Waypoint w) {
        if (WAYPOINTS.remove(w)) save();
    }

    public static boolean remove(String name) {
        String server = currentServer();
        boolean removed = WAYPOINTS.removeIf(w -> w.server().equals(server) && w.name().equalsIgnoreCase(name));
        if (removed) save();
        return removed;
    }

    /** Keeps only the newest {@code keep} death points of this server. */
    public static void trimDeaths(int keep) {
        String server = currentServer();
        List<Waypoint> deaths = new ArrayList<>();
        for (Waypoint w : WAYPOINTS) if (w.server().equals(server) && w.death()) deaths.add(w);
        deaths.sort((a, b) -> Long.compare(b.time(), a.time()));
        for (int i = keep; i < deaths.size(); i++) WAYPOINTS.remove(deaths.get(i));
        save();
    }

    public static int clearDeaths() {
        String server = currentServer();
        int before = WAYPOINTS.size();
        WAYPOINTS.removeIf(w -> w.server().equals(server) && w.death());
        save();
        return before - WAYPOINTS.size();
    }
}
