package com.relaxedqol.client.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The face of ANY player by name (for LoverFella Mode's bobble heads), not only people in the tab list.
 * Someone in the tab list is drawn from there; anyone else's skin is downloaded once from Mojang in the background
 * and kept for the session. LoverFella's face ships with the client, so his head is there even offline.
 */
public final class SkinFaces {
    private static final String NS = "relaxed-qol";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Identifier LOVERFELLA = Identifier.fromNamespaceAndPath(NS, "textures/gui/lovercraft/face_loverfella.png");

    /** name (lower case) -> {texture, skin height in px (64 or 32)}. */
    private static final Map<String, Object[]> READY = new ConcurrentHashMap<>();
    /** Downloaded, waiting to become a texture on the render thread. */
    private static final Map<String, byte[]> DOWNLOADED = new ConcurrentHashMap<>();
    private static final Set<String> ASKED = ConcurrentHashMap.newKeySet();
    /** A download that failed (Mojang busy, rate limit, timeout) is tried again later instead of never. */
    private static final Map<String, Long> RETRY_AT = new ConcurrentHashMap<>();
    private static final Map<String, Integer> TRIES = new ConcurrentHashMap<>();
    private static boolean broken;
    private static int registerFailures;
    /** The id each name has on the live map / tab list (skips the name lookup, which is the slow, rate-limited part). */
    private static final Map<String, java.util.UUID> IDS = new ConcurrentHashMap<>();
    /** Skins are fetched three at a time (150 map players at once used to hit Mojang's limit, so most stayed blank). */
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "Skin faces");
        t.setDaemon(true);
        return t;
    });

    /** Whether to start a download for this name now. */
    private static boolean ask(String key) {
        if (broken || READY.containsKey(key)) return false;
        if (ASKED.add(key)) return true;
        Long retry = RETRY_AT.get(key);
        if (retry == null || System.currentTimeMillis() < retry) return false;
        RETRY_AT.remove(key);
        return true;
    }

    private static void failed(String key) {
        int tries = TRIES.merge(key, 1, Integer::sum);
        if (tries <= 5) RETRY_AT.put(key, System.currentTimeMillis() + Math.min(120_000L, 8_000L << (tries - 1)));
    }

    private SkinFaces() {}

    // ------------------------------------------------------------------ no waiting for skins at start-up

    /** Skins are kept on disk (config/relaxed-qol/skins), so the next start has them at once. */
    private static java.nio.file.Path cacheFile(String key) {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("relaxed-qol").resolve("skins")
            .resolve(key.replaceAll("[^a-z0-9_]", "_") + ".png");
    }

    /** A saved skin is fetched fresh again after this long (people change skins). */
    private static final long CACHE_FRESH_MS = 3L * 24 * 60 * 60 * 1000;

    /**
     * Starts loading these skins right away - from the disk copy when there is one (instant), else from Mojang -
     * so the figures on the title screen are dressed from the first frame instead of a second later.
     */
    public static void prefetch(java.util.Collection<String> names) {
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            String key = name.trim().toLowerCase(Locale.ROOT);
            if (ask(key)) download(name.trim(), key);
        }
    }

    /** The disk copy into the "ready to become a texture" queue. True when it is fresh enough to skip downloading. */
    private static boolean fromDisk(String key) {
        try {
            java.nio.file.Path f = cacheFile(key);
            if (!java.nio.file.Files.isRegularFile(f)) return false;
            byte[] png = java.nio.file.Files.readAllBytes(f);
            if (png.length == 0) return false;
            if (!READY.containsKey(key)) DOWNLOADED.put(key, png);
            return System.currentTimeMillis() - java.nio.file.Files.getLastModifiedTime(f).toMillis() < CACHE_FRESH_MS;
        } catch (Exception e) {
            return false;
        }
    }

    private static void toDisk(String key, byte[] png) {
        try {
            java.nio.file.Path f = cacheFile(key);
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.write(f, png);
        } catch (Exception ignored) {
            // no disk copy: it is simply downloaded again next time
        }
    }

    /** Draws the face (with the hat layer) of this player at (x, y), size x size. False only when nothing can be drawn. */
    public static boolean draw(GuiGraphicsExtractor g, String name, int x, int y, int size, int color) {
        return draw(g, name, null, x, y, size, color);
    }

    /**
     * Like {@link #draw(GuiGraphicsExtractor, String, int, int, int, int)}, with the player's id when it is known
     * (live map): their skin is then fetched straight away. While a skin is not here yet - or the player has none
     * (default skin, Bedrock) - the game's own default face for that id is drawn, never an empty square.
     */
    public static boolean draw(GuiGraphicsExtractor g, String name, java.util.UUID id, int x, int y, int size, int color) {
        if (name == null || name.isEmpty()) return id != null && defaultFace(g, id, x, y, size, color);
        if (id != null) IDS.putIfAbsent(name.toLowerCase(Locale.ROOT), id);
        Minecraft mc = Minecraft.getInstance();
        // 1) In the tab list: their skin is already loaded (looked up in the once-a-second name table - walking
        //    the whole tab list for every face, every frame, cost a lot of FPS on full servers)
        if (mc.getConnection() != null) {
            java.util.UUID tab = Names.tabId(name, null);
            if (tab != null && Heads.draw(g, tab, x, y, size, color)) return true;
        }
        String key = name.toLowerCase(Locale.ROOT);
        registerPending();
        Object[] ready = READY.get(key);
        if (ready != null) {
            try {
                Identifier tex = (Identifier) ready[0];
                int texH = size * ((Integer) ready[1] / 8);
                g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, size, size, size, size, size * 8, texH, color);
                g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, size * 5, size, size, size, size * 8, texH, color);
                return true;
            } catch (RuntimeException e) {
                READY.remove(key);
            }
        }
        if (ask(key)) download(name, key);
        // 2) LoverFella's own face is built in (also while his skin is still downloading)
        if (key.equals("loverfella")) {
            g.blit(RenderPipelines.GUI_TEXTURED, LOVERFELLA, x, y, 0, 0, size, size, size, size, color);
            return true;
        }
        // 3) The game's default face for them (Steve, Alex... - the same one the game itself would show)
        java.util.UUID known = id != null ? id : IDS.get(key);
        return defaultFace(g, known != null ? known
            : java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), x, y, size, color);
    }

    private static java.lang.reflect.Method defaultGet;
    private static java.lang.reflect.Method defaultBody;
    private static java.lang.reflect.Method defaultPath;
    private static boolean defaultBroken;

    /** The game's default skin face for this id (found by reflection, so a renamed class only means no face). */
    private static boolean defaultFace(GuiGraphicsExtractor g, java.util.UUID id, int x, int y, int size, int color) {
        if (defaultBroken || id == null) return false;
        try {
            if (defaultGet == null) {
                defaultGet = Class.forName("net.minecraft.client.resources.DefaultPlayerSkin").getMethod("get", java.util.UUID.class);
            }
            Object skin = defaultGet.invoke(null, id);
            if (defaultBody == null) defaultBody = skin.getClass().getMethod("body");
            Object body = defaultBody.invoke(skin);
            if (defaultPath == null) defaultPath = body.getClass().getMethod("texturePath");
            Identifier tex = (Identifier) defaultPath.invoke(body);
            int t = size * 8;
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, size, size, size, size, t, t, color);
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, size * 5, size, size, size, t, t, color);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            defaultBroken = true;
            return false;
        }
    }

    /** A whole skin: its texture and height (64 = modern skin with overlays and own left limbs, 32 = old). */
    public record Skin(Identifier texture, int height) {}

    /** The full skin of this player (tab list first, else downloaded), or null while it is not known yet. */
    public static Skin skin(String name) {
        if (name == null || name.isEmpty()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            java.util.UUID tab = Names.tabId(name, null);
            PlayerInfo info = tab == null ? null : mc.getConnection().getPlayerInfo(tab);
            if (info != null) {
                try {
                    return new Skin(info.getSkin().body().texturePath(), 64);
                } catch (RuntimeException ignored) {
                    // fall through to the downloaded skin
                }
            }
        }
        String key = name.toLowerCase(Locale.ROOT);
        registerPending();
        Object[] ready = READY.get(key);
        if (ready != null) return new Skin((Identifier) ready[0], (Integer) ready[1]);
        if (ask(key)) download(name, key);
        return null;
    }

    /**
     * Draws the skin area (u, v, w, h in skin pixels) at (x, y), k screen pixels per skin pixel. Whole skins only
     * (64 wide), so the same call works for every player.
     */
    public static void part(GuiGraphicsExtractor g, Skin skin, int u, int v, int w, int h, int x, int y, int k, int color) {
        g.blit(RenderPipelines.GUI_TEXTURED, skin.texture(), x, y, u * k, v * k, w * k, h * k, 64 * k, skin.height() * k, color);
    }

    /** Turns downloaded skins into textures (render thread only). */
    private static void registerPending() {
        if (DOWNLOADED.isEmpty()) return;
        for (String key : DOWNLOADED.keySet()) {
            byte[] png = DOWNLOADED.remove(key);
            if (png == null) continue;
            try {
                com.mojang.blaze3d.platform.NativeImage image =
                    com.mojang.blaze3d.platform.NativeImage.read(new ByteArrayInputStream(png));
                int h = image.getHeight();
                Identifier id = Identifier.fromNamespaceAndPath(NS, "lovercraft_skin/" + key.replaceAll("[^a-z0-9_]", "_"));
                Minecraft.getInstance().getTextureManager().register(id,
                    new net.minecraft.client.renderer.texture.DynamicTexture(() -> "LoverCraft skin " + key, image));
                READY.put(key, new Object[]{id, h >= 64 ? 64 : 32});
            } catch (Exception | LinkageError e) {
                // A broken picture: try that one again later. Several in a row = this game version builds textures
                // differently: tab-list heads (and LoverFella's own) still work
                failed(key);
                if (++registerFailures >= 3) broken = true;
            }
        }
    }

    private static void download(String name, String key) {
        POOL.submit(() -> {
            // The copy on disk first (instant); a fresh one means no download at all
            if (fromDisk(key)) return;
            try {
                // The id from the live map / tab list when there is one; else two ways to turn the name into an id
                // (the old Mojang API is sometimes slow or rate-limited)
                java.util.UUID mapId = IDS.get(key);
                // Bedrock players (Floodgate ids) have no Java skin to fetch: their default face is shown
                if (mapId != null && mapId.getMostSignificantBits() == 0) return;
                String id = mapId != null && mapId.version() == 4 ? mapId.toString().replace("-", "") : null;
                for (String url : new String[]{"https://api.mojang.com/users/profiles/minecraft/" + name,
                    "https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name}) {
                    if (id != null) break;
                    try {
                        String profile = get(url);
                        if (profile == null) continue;
                        JsonObject o = JsonParser.parseString(profile).getAsJsonObject();
                        if (o.has("id")) {
                            id = o.get("id").getAsString();
                            break;
                        }
                    } catch (Exception ignored) {
                        // next
                    }
                }
                if (id == null) {
                    failed(key);
                    return;
                }
                String session = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id);
                if (session == null) {
                    failed(key);
                    return;
                }
                JsonObject s = JsonParser.parseString(session).getAsJsonObject();
                for (var p : s.getAsJsonArray("properties")) {
                    JsonObject prop = p.getAsJsonObject();
                    if (!"textures".equals(prop.get("name").getAsString())) continue;
                    String json = new String(Base64.getDecoder().decode(prop.get("value").getAsString()), StandardCharsets.UTF_8);
                    JsonObject textures = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures");
                    if (textures == null || !textures.has("SKIN")) return; // a default (Steve / Alex) skin
                    String url = textures.getAsJsonObject("SKIN").get("url").getAsString().replace("http://", "https://");
                    HttpResponse<byte[]> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                    if (res.statusCode() == 200 && res.body().length > 0) {
                        // A newer skin than the disk copy replaces the texture (and the copy)
                        READY.remove(key);
                        DOWNLOADED.put(key, res.body());
                        toDisk(key, res.body());
                    } else {
                        failed(key);
                    }
                    return;
                }
            } catch (Exception e) {
                // offline / busy: tried again in a little while (LoverFella keeps his built-in face meanwhile)
                failed(key);
            }
        });
    }

    private static String get(String url) throws Exception {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
            .header("User-Agent", "Mozilla/5.0").GET().build(), HttpResponse.BodyHandlers.ofString());
        return res.statusCode() == 200 ? res.body() : null;
    }
}
