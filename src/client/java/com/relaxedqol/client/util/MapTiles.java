package com.relaxedqol.client.util;

import com.relaxedqol.RelaxedQolMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The live web map's pictures (squaremap tiles) inside the game. The map is cut into 512 x 512 pixel images:
 * tiles/WORLD/ZOOM/X_Z.png. At the highest zoom one pixel is one block, every zoom level below halves that.
 *
 * Tiles are downloaded in the background (two at a time, only the ones on screen, newest wish first), kept on disk
 * (config/relaxed-qol/maptiles) and fetched again after a while, and kept as textures for the most recent ones only.
 * While a sharp tile is still coming, a blurrier one that is already here is stretched over its spot.
 */
public final class MapTiles {
    public static final int TILE = 512;
    private static int maxTextures() {
        return com.relaxedqol.client.module.modules.client.ClickGUI.lowEnd() ? 48 : 110;
    }
    /** Downloads at the same time (the map's server is behind a fast CDN). */
    private static final int PARALLEL = 10;
    private static final long REFRESH_MS = 10 * 60 * 1000L;

    private MapTiles() {}

    /** "https://map.loverfella.com/map/survival/tiles/" - set by LiveMap from its players.json address. */
    private static volatile String base;
    private static volatile String world = "minecraft_overworld";
    /** The world YOU are in on the map when it is another one than the zones' (Spawn): its pictures are used. */
    private static volatile String ownWorld;
    /** The map really has pictures for ownWorld (one was found). */
    private static volatile boolean ownWorldOk;

    /** The world whose pictures are drawn now. */
    private static String activeWorld() {
        String o = ownWorld;
        return o != null && ownWorldOk ? o : world;
    }

    /**
     * From LiveMap: the world you are in on the map (null = the zones' world). For another world (Spawn) one picture
     * where you stand is fetched first - only when the map has it are that world's pictures used.
     */
    public static void setOwnWorld(String w, double x, double z) {
        if (w == null || w.isBlank() || w.equals(world)) {
            ownWorld = null;
            ownWorldOk = false;
            return;
        }
        if (!w.equals(ownWorld)) {
            ownWorld = w;
            ownWorldOk = false;
        }
        if (!ownWorldOk && base != null) {
            int bpt = TILE;
            String k = w + "/" + maxZoom + "/" + (int) Math.floor(x / bpt) + "_" + (int) Math.floor(z / bpt);
            want(k, maxZoom, 0, 0, false);
        }
    }

    /** You are in another world than the zones' (Spawn) and the map has its pictures. */
    public static boolean ownWorldShown() {
        return ownWorld != null && ownWorldOk;
    }
    private static volatile int maxZoom = 3;
    private static volatile boolean settingsRead;

    private static final class Tile {
        Identifier id;
        Object texture;
        long usedAt;
        long loadedAt;
        /** When it first appeared (fades in over a moment). */
        long shownAt;
        /** Texture size in pixels (512, or 128 for a far-away overview tile). */
        int size = TILE;
    }

    /** Overview tiles are the lowest zoom level shrunk to this size - cheap enough to show the whole map. */
    private static final int SMALL = 128;
    private static final String SMALL_TAG = "@s";

    /** Loaded textures (access order = least recently used first). */
    private static final LinkedHashMap<String, Tile> TEXTURES = new LinkedHashMap<>(64, 0.75f, true);
    /** Tiles waiting to be downloaded (newest wish last) and the ones on their way. */
    private static final ArrayDeque<String> WANTED = new ArrayDeque<>();
    private static final Set<String> LOADING = new HashSet<>();
    /** Tiles the map does not have (404): not asked again for a while. */
    private static final Map<String, Long> MISSING = new LinkedHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(PARALLEL, r -> {
        Thread t = new Thread(r, "RelaxedQOL MapTiles");
        t.setDaemon(true);
        return t;
    });
    private static HttpClient http;
    private static boolean failed;

    /** Connected to the LoverCraft server (the only one whose web map this reads). */
    public static boolean onLoverCraft() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getCurrentServer();
        if (server == null || server.ip == null) return false;
        return server.ip.toLowerCase(java.util.Locale.ROOT).contains("loverfella");
    }

    /** From LiveMap: where the tiles are (the players.json address) and which world they show. */
    public static void configure(String playersUrl, String worldName) {
        if (playersUrl == null || !playersUrl.contains("players.json")) return;
        String b = playersUrl.substring(0, playersUrl.indexOf("players.json"));
        if (!b.equals(base)) {
            base = b;
            settingsRead = false;
        }
        if (worldName != null && !worldName.isBlank()) world = worldName;
    }

    /** Whether tiles can be shown now (LiveMap knows the map, and you are in a world it shows - not the Nether / End). */
    public static boolean available() {
        if (base == null || failed || !onLoverCraft()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || Dims.ceiling()) return false;
        try {
            String id = mc.level.dimension().identifier().getPath().toLowerCase(java.util.Locale.ROOT);
            if (id.contains("end")) return false;
        } catch (RuntimeException ignored) {
            // no name
        }
        return true;
    }

    public static int maxZoom() {
        return maxZoom;
    }

    /** Blocks one tile covers at a zoom level. */
    public static int blocksPerTile(int zoom) {
        return TILE << Math.max(0, maxZoom - zoom);
    }

    /** The zoom level whose pixels are closest to the screen's pixels at this many screen pixels per block. */
    public static int zoomFor(double screenPixelsPerBlock) {
        if (screenPixelsPerBlock <= 0) return 0;
        int down = (int) Math.floor(Math.log(1.0 / screenPixelsPerBlock) / Math.log(2) + 0.35);
        return Math.max(0, Math.min(maxZoom, maxZoom - Math.max(0, down)));
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the map around (centerX, centerZ) at (cx, cy) in the current pose: ppb = GUI pixels per block, rotation in
     * radians like Minimap.draw (0 = north up). radiusPx = how far from the middle must be covered. The caller clips.
     */
    public static void draw(GuiGraphicsExtractor g, float cx, float cy, double ppb, double rotation, double centerX, double centerZ,
                            double radiusPx, int alpha) {
        draw(g, cx, cy, ppb, rotation, centerX, centerZ, radiusPx, alpha, null);
    }

    /**
     * Like {@link #draw}, but only tiles inside bounds {minX, minZ, maxX, maxZ} (blocks) are loaded and drawn -
     * the World Map passes the zone grid, so fully zoomed out it does not try to fill the empty space around the world
     * (that was hundreds of tiles, so it gave up and showed nothing).
     */
    public static void draw(GuiGraphicsExtractor g, float cx, float cy, double ppb, double rotation, double centerX, double centerZ,
                            double radiusPx, int alpha, double[] bounds) {
        if (!available() || ppb <= 0) return;
        readSettingsOnce();
        int zoom = zoomFor(ppb * SmoothFont.pixelsPer(g.pose()));
        double reachBlocks = radiusPx * 1.45 / ppb;
        int bpt;
        int tx0;
        int tx1;
        int tz0;
        int tz1;
        // A blurrier zoom level when the sharp one would need too many tiles (a big window far zoomed out)
        while (true) {
            bpt = blocksPerTile(zoom);
            tx0 = (int) Math.floor((centerX - reachBlocks) / bpt);
            tx1 = (int) Math.floor((centerX + reachBlocks) / bpt);
            tz0 = (int) Math.floor((centerZ - reachBlocks) / bpt);
            tz1 = (int) Math.floor((centerZ + reachBlocks) / bpt);
            if (bounds != null) {
                tx0 = Math.max(tx0, (int) Math.floor(bounds[0] / bpt));
                tz0 = Math.max(tz0, (int) Math.floor(bounds[1] / bpt));
                tx1 = Math.min(tx1, (int) Math.floor(bounds[2] / bpt));
                tz1 = Math.min(tz1, (int) Math.floor(bounds[3] / bpt));
                if (tx1 < tx0 || tz1 < tz0) return;
            }
            if ((long) (tx1 - tx0 + 1) * (tz1 - tz0 + 1) <= 42 || zoom == 0) break;
            zoom--;
        }
        // Zoomed out further than that - the lowest zoom level as small overview tiles (128 px each), so the map
        // shows at every zoom instead of disappearing
        long count = (long) (tx1 - tx0 + 1) * (tz1 - tz0 + 1);
        boolean small = zoom == 0 && count > 42;
        if (count > (small ? 1600 : 64)) return;
        int color = (Math.max(0, Math.min(255, alpha)) << 24) | 0xFFFFFF;
        long now = System.currentTimeMillis();
        frameNeeded = 0;
        frameReady = 0;
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        if (rotation != 0) g.pose().rotate((float) rotation);
        // Furthest from the middle first - the newest wish is fetched first, so the middle of the view loads first
        double ctx = centerX / bpt - 0.5;
        double ctz = centerZ / bpt - 0.5;
        List<int[]> order = new ArrayList<>();
        for (int tz = tz0; tz <= tz1; tz++) for (int tx = tx0; tx <= tx1; tx++) order.add(new int[]{tx, tz});
        order.sort((a, b) -> Double.compare(Math.hypot(b[0] - ctx, b[1] - ctz), Math.hypot(a[0] - ctx, a[1] - ctz)));
        for (int[] o : order) {
            int tx = o[0];
            int tz = o[1];
            float sx = (float) ((tx * (double) bpt - centerX) * ppb);
            float sz = (float) ((tz * (double) bpt - centerZ) * ppb);
            float size = (float) (bpt * ppb);
            if (small) drawSmall(g, tx, tz, sx, sz, size, color, now);
            else drawTile(g, zoom, tx, tz, sx, sz, size, color, now);
        }
        g.pose().popMatrix();
        lastProgress = frameNeeded == 0 ? 1f : frameReady / (float) frameNeeded;
    }

    /**
     * Asks for the tiles a map of this size would show around (centerX, centerZ) - without drawing - so they
     * are already downloaded (or read from disk) when the radar window / minimap opens. screenPpb = real screen pixels
     * per block, radiusPx = the map's half size in screen pixels. Nearest tiles are asked for last (= fetched first).
     */
    public static void prefetch(double centerX, double centerZ, double screenPpb, double radiusPx) {
        if (!available() || screenPpb <= 0) return;
        readSettingsOnce();
        int zoom = zoomFor(screenPpb);
        double reach = radiusPx * 1.2 / screenPpb;
        int bpt;
        int tx0;
        int tx1;
        int tz0;
        int tz1;
        while (true) {
            bpt = blocksPerTile(zoom);
            tx0 = (int) Math.floor((centerX - reach) / bpt);
            tx1 = (int) Math.floor((centerX + reach) / bpt);
            tz0 = (int) Math.floor((centerZ - reach) / bpt);
            tz1 = (int) Math.floor((centerZ + reach) / bpt);
            if ((long) (tx1 - tx0 + 1) * (tz1 - tz0 + 1) <= 36 || zoom == 0) break;
            zoom--;
        }
        if ((long) (tx1 - tx0 + 1) * (tz1 - tz0 + 1) > 36) return;
        long now = System.currentTimeMillis();
        double ctx = centerX / bpt - 0.5;
        double ctz = centerZ / bpt - 0.5;
        List<int[]> order = new ArrayList<>();
        for (int tz = tz0; tz <= tz1; tz++) for (int tx = tx0; tx <= tx1; tx++) order.add(new int[]{tx, tz});
        order.sort((a, b) -> Double.compare(Math.hypot(b[0] - ctx, b[1] - ctz), Math.hypot(a[0] - ctx, a[1] - ctz)));
        // The overview two levels up first (one download covers 16 tiles, so something shows at once)
        if (zoom >= 2) {
            int f = 4;
            for (int[] o : order) {
                int px = Math.floorDiv(o[0], f);
                int pz = Math.floorDiv(o[1], f);
                if (peek(zoom - 2, px, pz, now) == null) want(key(zoom - 2, px, pz), zoom - 2, px, pz, false);
            }
        }
        for (int[] o : order) get(zoom, o[0], o[1], now);
    }

    /** An overview tile (lowest zoom, shrunk). */
    private static void drawSmall(GuiGraphicsExtractor g, int tx, int tz, float sx, float sz, float size, int color, long now) {
        drawSmallAt(g, 0, tx, tz, sx, sz, size, color, now);
    }

    /**
     * An overview tile at this zoom level (shrunk). The live map does not have every big square of its furthest
     * zoom level (its outer ring is missing there, although players stand in it), so a square it says it does not
     * have is drawn from its four quarters one zoom level in - also shrunk - and so on up to two levels in.
     */
    private static void drawSmallAt(GuiGraphicsExtractor g, int zoom, int tx, int tz, float sx, float sz, float size, int color, long now) {
        String k = key(zoom, tx, tz) + SMALL_TAG;
        Tile t;
        synchronized (TEXTURES) {
            t = TEXTURES.get(k);
        }
        if (t == null && isMissing(k) && zoom < Math.min(2, maxZoom)) {
            float h = size / 2f;
            for (int j = 0; j < 2; j++) {
                for (int i = 0; i < 2; i++) drawSmallAt(g, zoom + 1, tx * 2 + i, tz * 2 + j, sx + i * h, sz + j * h, h, color, now);
            }
            return;
        }
        frameNeeded++;
        if (t != null || isMissing(k)) frameReady++;
        if (t == null) {
            // The full one may be here already (zoomed in a moment ago)
            Tile full = peek(zoom, tx, tz, now);
            if (full != null) blit(g, full.id, sx, sz, size, 0, 0, TILE, TILE, color);
            want(k, zoom, tx, tz, false);
            return;
        }
        t.usedAt = now;
        float fade = Math.min(1f, (now - t.shownAt) / 220f);
        int a = Math.round(((color >>> 24) & 0xFF) * fade);
        blit(g, t.id, sx, sz, size, 0, 0, t.size, t.size, (a << 24) | (color & 0xFFFFFF));
    }

    /** One tile at (sx, sz) size x size; a parent tile's part when this one is not here yet. */
    private static void drawTile(GuiGraphicsExtractor g, int zoom, int tx, int tz, float sx, float sz, float size, int color, long now) {
        Tile t = get(zoom, tx, tz, now);
        frameNeeded++;
        // A spot the map has no picture for (outside the world) counts as done
        if (t != null || isMissing(key(zoom, tx, tz))) frameReady++;
        float fade = t == null ? 0f : Math.min(1f, (now - t.shownAt) / 220f);
        if (t != null && fade >= 1f) {
            blit(g, t.id, sx, sz, size, 0, 0, TILE, color);
            return;
        }
        // The overview two levels up is asked for too - one small download covers 16 tiles, so something shows
        // almost at once while the sharp ones come in
        if (t == null && zoom >= 2) want(key(zoom - 2, Math.floorDiv(tx, 4), Math.floorDiv(tz, 4)), 0, 0, 0, false);
        // A blurrier stand-in: the same spot cut out of a lower zoom level that is already loaded
        for (int up = 1; up <= Math.min(4, zoom); up++) {
            int f = 1 << up;
            int px = Math.floorDiv(tx, f);
            int pz = Math.floorDiv(tz, f);
            Tile p = peek(zoom - up, px, pz, now);
            if (p == null) continue;
            int sub = TILE / f;
            blit(g, p.id, sx, sz, size, (tx - px * f) * sub, (tz - pz * f) * sub, sub, color);
            break;
        }
        // The sharp tile fading in over the stand-in
        if (t != null) {
            int a = Math.round(((color >>> 24) & 0xFF) * fade);
            blit(g, t.id, sx, sz, size, 0, 0, TILE, (a << 24) | (color & 0xFFFFFF));
        }
    }

    /** Of the tiles the last map drawn needs, how many are really here (sharp or overview), 0..1. */
    private static int frameNeeded;
    private static int frameReady;
    private static float lastProgress = 1f;

    public static float progress() {
        return lastProgress;
    }

    /** How many tiles are on their way right now (for a "loading" hint). */
    public static int loading() {
        synchronized (WANTED) {
            return LOADING.size() + WANTED.size();
        }
    }

    private static void blit(GuiGraphicsExtractor g, Identifier id, float x, float y, float size, int u, int v, int region, int color) {
        blit(g, id, x, y, size, u, v, region, TILE, color);
    }

    private static void blit(GuiGraphicsExtractor g, Identifier id, float x, float y, float size, int u, int v, int region, int tex, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        // One screen pixel bigger, so there are never seams between tiles (at any zoom, also when the map turns)
        float k = (size + 1f) / region;
        g.pose().scale(k, k);
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id, 0, 0, u, v, region, region, tex, tex, color);
        g.pose().popMatrix();
    }

    private static String key(int zoom, int tx, int tz) {
        return activeWorld() + "/" + zoom + "/" + tx + "_" + tz;
    }

    /** A loaded tile (asked for when not here). */
    private static Tile get(int zoom, int tx, int tz, long now) {
        String k = key(zoom, tx, tz);
        Tile t;
        synchronized (TEXTURES) {
            t = TEXTURES.get(k);
        }
        if (t != null) {
            t.usedAt = now;
            if (now - t.loadedAt > REFRESH_MS) want(k, zoom, tx, tz, true);
            return t;
        }
        want(k, zoom, tx, tz, false);
        return null;
    }

    /** A loaded tile, without asking for it. */
    private static Tile peek(int zoom, int tx, int tz, long now) {
        synchronized (TEXTURES) {
            Tile t = TEXTURES.get(key(zoom, tx, tz));
            if (t != null) t.usedAt = now;
            return t;
        }
    }

    // ------------------------------------------------------------------ loading

    private static boolean isMissing(String k) {
        synchronized (WANTED) {
            Long miss = MISSING.get(k);
            return miss != null && System.currentTimeMillis() - miss < REFRESH_MS;
        }
    }

    private static void want(String k, int zoom, int tx, int tz, boolean refresh) {
        synchronized (WANTED) {
            if (LOADING.contains(k)) return;
            Long miss = MISSING.get(k);
            if (miss != null && System.currentTimeMillis() - miss < REFRESH_MS) return;
            WANTED.remove(k);
            WANTED.addLast(k);
            while (WANTED.size() > 400) WANTED.pollFirst();
            pump();
        }
    }

    /** Starts downloads while fewer than two are running (newest wish first). */
    private static void pump() {
        while (LOADING.size() < PARALLEL && !WANTED.isEmpty()) {
            String k = WANTED.pollLast();
            LOADING.add(k);
            String b = base;
            POOL.submit(() -> load(b, k));
        }
    }

    private static Path diskFile(String k) {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("relaxed-qol").resolve("maptiles")
            .resolve(k.replace(':', '_') + ".png");
    }

    private static void load(String b, String key) {
        byte[] bytes = null;
        String k = key;
        boolean small = key.endsWith(SMALL_TAG);
        if (small) key = key.substring(0, key.length() - SMALL_TAG.length());
        try {
            Path file = diskFile(key);
            boolean fresh = Files.isRegularFile(file)
                && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < REFRESH_MS;
            boolean isLoaded;
            synchronized (TEXTURES) {
                isLoaded = TEXTURES.containsKey(k);
            }
            if (fresh && isLoaded) {
                // Another look already brought it up to date: nothing to fetch
                synchronized (TEXTURES) {
                    Tile t = TEXTURES.get(k);
                    if (t != null) t.loadedAt = System.currentTimeMillis();
                }
            } else if (!isLoaded && Files.isRegularFile(file)) {
                // A copy on disk shows AT ONCE - also an old one; an old one is fetched again right after
                byte[] disk = Files.readAllBytes(file);
                Object image = small ? shrunk(disk) : readImage(disk);
                if (image != null) {
                    final Object img = image;
                    final boolean stale = !fresh;
                    Minecraft.getInstance().execute(() -> {
                        install(k, img);
                        if (stale) {
                            synchronized (TEXTURES) {
                                Tile t = TEXTURES.get(k);
                                if (t != null) t.loadedAt = 0; // asks for the new picture on the next look
                            }
                        }
                    });
                }
            } else if (!fresh && b != null) {
                if (http == null) {
                    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build();
                }
                HttpRequest req = HttpRequest.newBuilder(URI.create(b + key + ".png")).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 RelaxedQOL-MapTiles").GET().build();
                HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() == 404) {
                    synchronized (WANTED) {
                        MISSING.put(k, System.currentTimeMillis());
                        while (MISSING.size() > 4000) MISSING.remove(MISSING.keySet().iterator().next());
                    }
                } else if (res.statusCode() / 100 == 2) {
                    bytes = res.body();
                    try {
                        Files.createDirectories(file.getParent());
                        Files.write(file, bytes);
                    } catch (Exception ignored) {
                        // no disk cache then
                    }
                }
            }
            // Offline / the site did not answer: an older copy from disk is better than nothing
            if (bytes == null && !isLoaded && Files.isRegularFile(file)) bytes = Files.readAllBytes(file);
            if (bytes != null) {
                Object image = small ? shrunk(bytes) : readImage(bytes);
                if (image != null) {
                    final Object img = image;
                    Minecraft.getInstance().execute(() -> install(k, img));
                }
            }
        } catch (Exception e) {
            RelaxedQolMod.LOGGER.debug("MapTiles: {} failed", key, e);
        } finally {
            synchronized (WANTED) {
                LOADING.remove(k);
                pump();
            }
        }
    }

    // ------------------------------------------------------------------ textures (by reflection, like Minimap)

    private static java.lang.reflect.Method readMethod;

    private static Object readImage(byte[] bytes) throws Exception {
        Class<?> imageClass = Class.forName("com.mojang.blaze3d.platform.NativeImage");
        if (readMethod == null) readMethod = imageClass.getMethod("read", InputStream.class);
        return readMethod.invoke(null, new ByteArrayInputStream(bytes));
    }

    private static boolean shrinkFailed;

    /**
     * A tile shrunk to 128 x 128 (an average of each 4 x 4 block of pixels) for the overview - 16 times less
     * graphics memory. Falls back to the full picture if this Java has no image tools.
     */
    private static Object shrunk(byte[] bytes) throws Exception {
        if (shrinkFailed) return readImage(bytes);
        try {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
            if (src == null) return readImage(bytes);
            int w = src.getWidth();
            int h = src.getHeight();
            int f = Math.max(1, w / SMALL);
            Class<?> imageClass = Class.forName("com.mojang.blaze3d.platform.NativeImage");
            Object image = imageClass.getConstructor(int.class, int.class, boolean.class).newInstance(SMALL, SMALL, true);
            long pointer = 0;
            try {
                pointer = (Long) imageClass.getMethod("getPointer").invoke(image);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // pixel by pixel
            }
            java.lang.reflect.Method setPixel = null;
            if (pointer == 0) {
                for (String n : new String[]{"setPixel", "setPixelRGBA", "setPixelABGR"}) {
                    try {
                        setPixel = imageClass.getMethod(n, int.class, int.class, int.class);
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // next
                    }
                }
                if (setPixel == null) throw new IllegalStateException("no way to write pixels");
            }
            int[] row = new int[w * f];
            for (int ty = 0; ty < SMALL; ty++) {
                int y0 = Math.min(h - f, ty * f);
                src.getRGB(0, y0, w, f, row, 0, w);
                for (int tx = 0; tx < SMALL; tx++) {
                    int x0 = Math.min(w - f, tx * f);
                    long a = 0;
                    long r = 0;
                    long gg = 0;
                    long bb = 0;
                    for (int dy = 0; dy < f; dy++) {
                        for (int dx = 0; dx < f; dx++) {
                            int c = row[dy * w + x0 + dx];
                            int ca = (c >>> 24) & 0xFF;
                            a += ca;
                            r += ((c >> 16) & 0xFF) * ca;
                            gg += ((c >> 8) & 0xFF) * ca;
                            bb += (c & 0xFF) * ca;
                        }
                    }
                    int n = f * f;
                    int oa = (int) (a / n);
                    int or = a == 0 ? 0 : (int) (r / a);
                    int og = a == 0 ? 0 : (int) (gg / a);
                    int ob = a == 0 ? 0 : (int) (bb / a);
                    int argb = (oa << 24) | (or << 16) | (og << 8) | ob;
                    int abgr = (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
                    if (pointer != 0) {
                        org.lwjgl.system.MemoryUtil.memPutInt(pointer + ((long) ty * SMALL + tx) * 4L, abgr);
                    } else {
                        setPixel.invoke(image, tx, ty, setPixel.getName().equals("setPixel") ? argb : abgr);
                    }
                }
            }
            return image;
        } catch (Throwable e) {
            shrinkFailed = true;
            RelaxedQolMod.LOGGER.debug("MapTiles: no image shrinking here, using full tiles", e);
            return readImage(bytes);
        }
    }

    private static int imageWidth(Object image) {
        try {
            return (Integer) image.getClass().getMethod("getWidth").invoke(image);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return TILE;
        }
    }

    private static int counter;

    /** On the game thread: the image becomes a texture (replacing an older one of the same tile). */
    private static void install(String k, Object image) {
        String o = ownWorld;
        if (o != null && k.startsWith(o + "/")) ownWorldOk = true;
        try {
            Class<?> imageClass = Class.forName("com.mojang.blaze3d.platform.NativeImage");
            Class<?> dyn = Class.forName("net.minecraft.client.renderer.texture.DynamicTexture");
            Object texture = null;
            for (java.lang.reflect.Constructor<?> c : dyn.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 2 && p[0] == java.util.function.Supplier.class && p[1] == imageClass) {
                    String label = "relaxed map tile " + k;
                    texture = c.newInstance((java.util.function.Supplier<String>) () -> label, image);
                } else if (p.length == 1 && p[0] == imageClass) {
                    texture = c.newInstance(image);
                }
                if (texture != null) break;
            }
            if (texture == null) throw new IllegalStateException("no DynamicTexture(NativeImage)");
            try {
                dyn.getMethod("upload").invoke(texture);
            } catch (NoSuchMethodException ignored) {
                // uploaded by the constructor
            }
            Identifier id = Identifier.fromNamespaceAndPath("relaxed-qol", "maptile/" + (counter++));
            Object manager = Minecraft.class.getMethod("getTextureManager").invoke(Minecraft.getInstance());
            boolean registered = false;
            for (java.lang.reflect.Method m : manager.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("register") && p.length == 2 && p[0] == Identifier.class && p[1].isAssignableFrom(dyn)) {
                    m.invoke(manager, id, texture);
                    registered = true;
                    break;
                }
            }
            if (!registered) throw new IllegalStateException("no TextureManager.register");
            Tile t = new Tile();
            t.size = imageWidth(image);
            t.id = id;
            t.texture = texture;
            t.usedAt = System.currentTimeMillis();
            t.loadedAt = t.usedAt;
            Tile old;
            synchronized (TEXTURES) {
                old = TEXTURES.get(k);
            }
            // A refreshed tile replaces the old one without fading in again
            t.shownAt = old != null ? 0 : t.usedAt;
            synchronized (TEXTURES) {
                old = TEXTURES.put(k, t);
            }
            if (old != null) release(manager, old);
            evict(manager);
        } catch (Throwable e) {
            failed = true;
            RelaxedQolMod.LOGGER.warn("MapTiles: textures not possible on this game version - web map tiles are off", e);
        }
    }

    /** Only the most recently used tiles stay in graphics memory. */
    private static void evict(Object manager) {
        synchronized (TEXTURES) {
            Iterator<Map.Entry<String, Tile>> it = TEXTURES.entrySet().iterator();
            // A 128 px overview tile weighs 1/16 of a full one
            int max = maxTextures() * 16;
            int weight = 0;
            for (Tile t : TEXTURES.values()) weight += t.size >= TILE ? 16 : 1;
            while (weight > max && it.hasNext()) {
                Tile t = it.next().getValue();
                it.remove();
                weight -= t.size >= TILE ? 16 : 1;
                release(manager, t);
            }
        }
    }

    private static void release(Object manager, Tile t) {
        try {
            for (java.lang.reflect.Method m : manager.getClass().getMethods()) {
                if (m.getName().equals("release") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == Identifier.class) {
                    m.invoke(manager, t.id);
                    return;
                }
            }
            t.texture.getClass().getMethod("close").invoke(t.texture);
        } catch (Throwable ignored) {
            // let it be
        }
    }

    /** Forgets every texture (the map address changed / Refresh). The disk cache is kept. */
    public static void clearTextures() {
        try {
            Object manager = Minecraft.class.getMethod("getTextureManager").invoke(Minecraft.getInstance());
            synchronized (TEXTURES) {
                for (Tile t : TEXTURES.values()) release(manager, t);
                TEXTURES.clear();
            }
        } catch (Throwable ignored) {
            // nothing to free
        }
    }

    /** Refresh: every tile is fetched again from the web on its next look. */
    public static void refreshAll() {
        clearTextures();
        try {
            Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("relaxed-qol").resolve("maptiles");
            if (Files.isDirectory(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile).forEach(f -> {
                        try {
                            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(0));
                        } catch (Exception ignored) {
                            // skip
                        }
                    });
                }
            }
        } catch (Exception ignored) {
            // no cache
        }
        synchronized (WANTED) {
            MISSING.clear();
        }
    }

    // ------------------------------------------------------------------ the map's own settings (zoom levels)

    private static void readSettingsOnce() {
        if (settingsRead || base == null) return;
        settingsRead = true;
        String b = base;
        String w = world;
        POOL.submit(() -> {
            try {
                if (http == null) {
                    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build();
                }
                HttpRequest req = HttpRequest.newBuilder(URI.create(b + w + "/settings.json")).timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 RelaxedQOL-MapTiles").GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 != 2) return;
                var root = com.google.gson.JsonParser.parseString(res.body()).getAsJsonObject();
                if (root.has("zoom") && root.get("zoom").isJsonObject()) {
                    var z = root.getAsJsonObject("zoom");
                    if (z.has("max")) maxZoom = Math.max(0, Math.min(8, z.get("max").getAsInt()));
                }
            } catch (Exception ignored) {
                // keep the squaremap default (3)
            }
        });
    }
}
