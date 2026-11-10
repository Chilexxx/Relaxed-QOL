package com.relaxedqol.client.util;

import com.relaxedqol.RelaxedQolMod;
import com.relaxedqol.client.ConfigManager;
import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.gui.Toasts;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Setting profiles and sharing.
 * <p>
 * A profile is a full copy of the settings (modules on / off, keys, every setting, HUD layout, style) under a name -
 * e.g. "PvP", "Building", "Recording". Switching keeps the current settings in the profile you leave, then loads the
 * one you pick. A server can be linked to a profile: joining it switches automatically.
 * <p>
 * Sharing: the settings become one line of text (a share code, "RQ1:...") to paste anywhere, or a .txt file in the
 * backups folder. Importing always keeps the settings you had as the profile "Before import" first, so it can be
 * undone. Personal things are never in it: accounts, the market API key, notes, waypoints and friends live in their
 * own files.
 */
public final class Profiles {
    private Profiles() {}

    public static final String MAIN = "Main";
    public static final String BEFORE_IMPORT = "Before import";
    private static final String CODE_PREFIX = "RQ1:";
    /** Setting keys never shared (they say something about you, not about how the client looks / works). */
    private static final String[] PRIVATE_KEYS = {"PlayerAlert.setting.", "LoverCraftMarket.setting.Watch List",
        "LoverCraftMarket.setting.API Address"};

    private static Path dir() throws IOException {
        Path d = ConfigManager.folder().resolve("profiles");
        Files.createDirectories(d);
        return d;
    }

    public static Path backupDir() throws IOException {
        Path d = ConfigManager.folder().resolve("backups");
        Files.createDirectories(d);
        return d;
    }

    /** A name that is safe as a file name (letters, digits, spaces, - and _; at most 24 characters). */
    public static String clean(String name) {
        if (name == null) return "";
        String s = name.replaceAll("[^A-Za-z0-9 _-]", "").trim().replaceAll(" +", " ");
        return s.length() > 24 ? s.substring(0, 24).trim() : s;
    }

    // ------------------------------------------------------------------ the list

    public static List<String> list() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir())) {
            files.map(p -> p.getFileName().toString())
                .filter(f -> f.endsWith(".properties"))
                .map(f -> f.substring(0, f.length() - ".properties".length()))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(out::add);
        } catch (IOException e) {
            RelaxedQolMod.LOGGER.warn("Profiles: could not list them", e);
        }
        String active = active();
        if (!out.contains(active)) out.add(0, active);
        return out;
    }

    /** The profile in use (Main until another is picked). */
    public static String active() {
        try {
            Path f = dir().resolve("active.txt");
            String s = Files.isRegularFile(f) ? clean(Files.readString(f, StandardCharsets.UTF_8)) : "";
            return s.isEmpty() ? MAIN : s;
        } catch (IOException e) {
            return MAIN;
        }
    }

    private static void setActive(String name) throws IOException {
        Files.writeString(dir().resolve("active.txt"), name, StandardCharsets.UTF_8);
    }

    public static boolean exists(String name) {
        try {
            return Files.isRegularFile(dir().resolve(name + ".properties"));
        } catch (IOException e) {
            return false;
        }
    }

    /** When the profile was last saved, e.g. "12 Nov 14:05" (empty when unknown). */
    public static String savedAt(String name) {
        try {
            Path f = dir().resolve(name + ".properties");
            if (!Files.isRegularFile(f)) return "";
            LocalDateTime t = LocalDateTime.ofInstant(Files.getLastModifiedTime(f).toInstant(), java.time.ZoneId.systemDefault());
            return t.format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH));
        } catch (IOException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ changing them

    /** Keeps the current settings in the active profile (done before every switch and import). */
    public static void saveActive() {
        try {
            ConfigManager.write(dir().resolve(active() + ".properties"), ConfigManager.snapshot());
        } catch (IOException e) {
            RelaxedQolMod.LOGGER.warn("Profiles: could not save {}", active(), e);
        }
    }

    /** A new profile from the current settings, and switches to it. False when the name is empty or taken. */
    public static boolean create(String name) {
        String n = clean(name);
        if (n.isEmpty() || exists(n) || n.equalsIgnoreCase(active())) return false;
        saveActive();
        try {
            ConfigManager.write(dir().resolve(n + ".properties"), ConfigManager.snapshot());
            setActive(n);
        } catch (IOException e) {
            return false;
        }
        Toasts.show("Profile created", n + " - a copy of your current settings", Theme.SUCCESS, 3500);
        return true;
    }

    /** Switches to another profile: the current settings stay in the one you leave. */
    public static boolean switchTo(String name) {
        String n = clean(name);
        if (n.isEmpty() || n.equals(active())) return false;
        Properties props;
        try {
            props = ConfigManager.read(dir().resolve(n + ".properties"));
        } catch (IOException e) {
            props = null;
        }
        if (props == null) return false;
        saveActive();
        ConfigManager.apply(props);
        ConfigManager.save(ConfigManager.DEFAULT);
        try {
            setActive(n);
        } catch (IOException ignored) {
            // still switched for this session
        }
        com.relaxedqol.client.gui.ClickGuiScreen.styleChanged();
        Toasts.show("Profile: " + n, "Settings, HUD and style switched", Theme.accent(), 3000);
        return true;
    }

    public static boolean rename(String from, String to) {
        String n = clean(to);
        if (n.isEmpty() || exists(n)) return false;
        try {
            boolean wasActive = from.equals(active());
            if (wasActive) saveActive();
            Path src = dir().resolve(from + ".properties");
            if (Files.isRegularFile(src)) Files.move(src, dir().resolve(n + ".properties"));
            if (wasActive) setActive(n);
            // Servers linked to it follow the new name
            Properties links = links();
            for (String k : links.stringPropertyNames()) if (from.equals(links.getProperty(k))) links.setProperty(k, n);
            saveLinks(links);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Deletes a profile (never the one in use). */
    public static boolean delete(String name) {
        if (name.equals(active())) return false;
        try {
            Files.deleteIfExists(dir().resolve(name + ".properties"));
            Properties links = links();
            links.entrySet().removeIf(e -> name.equals(e.getValue()));
            saveLinks(links);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ servers

    private static Properties links() {
        try {
            Properties p = ConfigManager.read(dir().resolve("servers.properties"));
            return p != null ? p : new Properties();
        } catch (IOException e) {
            return new Properties();
        }
    }

    private static void saveLinks(Properties p) {
        try {
            ConfigManager.write(dir().resolve("servers.properties"), p);
        } catch (IOException e) {
            RelaxedQolMod.LOGGER.warn("Profiles: could not save the server links", e);
        }
    }

    /** The address of the server you are on, lower case without the default port ("" in single player). */
    public static String currentServer() {
        var data = Minecraft.getInstance().getCurrentServer();
        if (data == null || data.ip == null) return "";
        return data.ip.toLowerCase(Locale.ROOT).trim().replaceFirst(":25565$", "");
    }

    /** The profile linked to a server ("" = none). */
    public static String linkedTo(String server) {
        return server.isEmpty() ? "" : links().getProperty(server, "");
    }

    /** Links (or with null, unlinks) the current server to a profile. */
    public static void link(String server, String profile) {
        if (server.isEmpty()) return;
        Properties p = links();
        if (profile == null) p.remove(server);
        else p.setProperty(server, profile);
        saveLinks(p);
    }

    /** All server links: address -> profile. */
    public static java.util.Map<String, String> allLinks() {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        Properties p = links();
        for (String k : p.stringPropertyNames()) out.put(k, p.getProperty(k));
        return out;
    }

    /** From joining a server: switch to its linked profile. */
    public static void onJoin() {
        String profile = linkedTo(currentServer());
        if (!profile.isEmpty() && !profile.equals(active()) && exists(profile)) switchTo(profile);
    }

    // ------------------------------------------------------------------ sharing

    /** The current settings as one line of text to share (nothing personal in it). */
    public static String shareCode() {
        Properties props = ConfigManager.snapshot();
        props.keySet().removeIf(k -> isPrivate(k.toString()));
        try {
            StringWriter text = new StringWriter();
            props.store(text, null);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
                gz.write(text.toString().getBytes(StandardCharsets.UTF_8));
            }
            return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isPrivate(String key) {
        for (String p : PRIVATE_KEYS) if (key.startsWith(p)) return true;
        return false;
    }

    /** Reads a share code (or a whole settings file pasted / dropped in). Null when it isn't one. */
    public static Properties parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        try {
            Properties props = new Properties();
            int at = t.indexOf(CODE_PREFIX);
            if (at >= 0) {
                String b64 = t.substring(at + CODE_PREFIX.length()).split("\\s")[0];
                byte[] raw = Base64.getUrlDecoder().decode(b64);
                try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                    props.load(new StringReader(new String(gz.readAllBytes(), StandardCharsets.UTF_8)));
                }
            } else {
                props.load(new StringReader(t));
            }
            // Must look like client settings: module keys like "Name.enabled"
            long known = props.stringPropertyNames().stream().filter(k -> k.endsWith(".enabled") || k.contains(".setting.")).count();
            return known >= 3 ? props : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    /** How many settings of the code this client knows (shown before importing). */
    public static int known(Properties props) {
        int n = 0;
        var mm = com.relaxedqol.client.RelaxedQolClient.moduleManager;
        if (mm == null) return 0;
        for (var m : mm.getModules()) {
            String prefix = m.getName() + ".";
            for (String k : props.stringPropertyNames()) if (k.startsWith(prefix)) n++;
        }
        return n;
    }

    /**
     * Uses shared settings: the current ones are kept as the profile "Before import" first (so it can be undone), then
     * the shared ones are put into effect. Personal settings in the code (if any) are ignored.
     */
    public static void importSettings(Properties props, String from) {
        try {
            ConfigManager.write(dir().resolve(BEFORE_IMPORT + ".properties"), ConfigManager.snapshot());
        } catch (IOException e) {
            RelaxedQolMod.LOGGER.warn("Profiles: could not keep the settings before importing", e);
        }
        props.keySet().removeIf(k -> isPrivate(k.toString()));
        ConfigManager.apply(props);
        ConfigManager.save(ConfigManager.DEFAULT);
        saveActive();
        com.relaxedqol.client.gui.ClickGuiScreen.styleChanged();
        Toasts.show("Settings imported", from + " - undo: switch to the profile \"" + BEFORE_IMPORT + "\"", Theme.SUCCESS, 6000);
    }

    /** Saves the current settings as a share-code file in the backups folder; returns its name ("" when it failed). */
    public static String exportFile() {
        try {
            String name = active() + " " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm", Locale.ROOT)) + ".txt";
            Files.writeString(backupDir().resolve(name), shareCode() + System.lineSeparator(), StandardCharsets.UTF_8);
            return name;
        } catch (IOException e) {
            return "";
        }
    }

    /** The share-code / settings files in the backups folder, newest first. */
    public static List<Path> backups() {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(backupDir())) {
            files.filter(p -> {
                String f = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return Files.isRegularFile(p) && (f.endsWith(".txt") || f.endsWith(".properties"));
            }).forEach(out::add);
        } catch (IOException e) {
            return out;
        }
        out.sort((a, b) -> {
            try {
                return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
            } catch (IOException e) {
                return 0;
            }
        });
        return out;
    }

    /** A file's settings (share code or settings file), or null. */
    public static Properties readFile(Path file) {
        try {
            if (Files.size(file) > 2_000_000) return null;
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static void openBackupFolder() {
        try {
            net.minecraft.util.Util.getPlatform().openUri(backupDir().toUri().toString());
        } catch (IOException | RuntimeException e) {
            Toasts.show("Could not open the folder", e.getMessage() == null ? "" : e.getMessage(), Theme.DANGER, 4000);
        }
    }
}
