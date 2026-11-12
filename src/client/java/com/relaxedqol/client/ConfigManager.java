package com.relaxedqol.client;

import com.relaxedqol.RelaxedQolMod;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Saves and loads module states, keybinds and settings as .properties files in
 * .minecraft/config/relaxed-qol/. The "default" config is loaded on startup and saved on exit.
 */
public final class ConfigManager {
    public static final String DEFAULT = "default";

    /** Old module name -> new name (older saved settings still apply). */
    private static final java.util.Map<String, String> RENAMED = java.util.Map.of("InventoryTweaks", "InvTweaks",
        "PlayerTracker", "PlayerAlert",
        "ModPanel", "QuickPanel");
    /** Old full setting key -> new one. */
    private static final java.util.Map<String, String> RENAMED_SETTINGS = java.util.Map.of();


    private ConfigManager() {}

    private static Path dir() throws IOException {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("relaxed-qol");
        Files.createDirectories(dir);
        return dir;
    }

    public static boolean isValidName(String name) {
        return name.matches("[A-Za-z0-9_-]{1,32}");
    }

    public static boolean save(String name) {
        try {
            write(dir().resolve(name + ".properties"), snapshot());
            return true;
        } catch (IOException e) {
            RelaxedQolMod.LOGGER.error("Failed to save config {}", name, e);
            return false;
        }
    }

    /** Every module's on / off, key and settings as they are right now (what a config file holds). */
    public static Properties snapshot() {
        Properties props = new Properties();
        for (Module module : RelaxedQolClient.moduleManager.getModules()) {
            String prefix = module.getName() + ".";
            props.setProperty(prefix + "enabled", String.valueOf(module.isEnabled()));
            props.setProperty(prefix + "key", String.valueOf(module.getKey()));
            for (Setting<?> setting : module.getSettings()) {
                if (setting.isSaved()) {
                    props.setProperty(prefix + "setting." + setting.getName(), setting.serialize());
                }
            }
        }
        return props;
    }

    /** Writes settings to a file (through a temporary file, so a crash never leaves half a file). */
    public static void write(Path file, Properties props) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            props.store(writer, "Relaxed QOL config");
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Reads a settings file (null when it is missing or broken). */
    public static Properties read(Path file) {
        if (!Files.isRegularFile(file)) return null;
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
            return props;
        } catch (IOException | IllegalArgumentException e) {
            RelaxedQolMod.LOGGER.error("Failed to read config {}", file, e);
            return null;
        }
    }

    /** The client's config folder (.minecraft/config/relaxed-qol). */
    public static Path folder() throws IOException {
        return dir();
    }

    public static boolean load(String name) {
        Properties props;
        try {
            props = read(dir().resolve(name + ".properties"));
        } catch (IOException e) {
            RelaxedQolMod.LOGGER.error("Failed to load config {}", name, e);
            return false;
        }
        if (props == null) return false;
        apply(props);
        return true;
    }

    /**
     * Puts saved settings into effect. Keys the client doesn't know (from another client or an older / newer version)
     * are skipped; settings the file doesn't have keep their value.
     */
    public static void apply(Properties props) {

        // Modules that were renamed: their old saved settings still apply under the new name
        for (java.util.Map.Entry<String, String> rename : RENAMED.entrySet()) {
            String oldPrefix = rename.getKey() + ".";
            String newPrefix = rename.getValue() + ".";
            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith(oldPrefix)) continue;
                String moved = newPrefix + key.substring(oldPrefix.length());
                if (props.getProperty(moved) == null) props.setProperty(moved, props.getProperty(key));
            }
        }


        // Settings that were renamed: the old saved value still applies under the new name
        for (java.util.Map.Entry<String, String> rename : RENAMED_SETTINGS.entrySet()) {
            String saved = props.getProperty(rename.getKey());
            if (saved != null && props.getProperty(rename.getValue()) == null) props.setProperty(rename.getValue(), saved);
        }

        for (Module module : RelaxedQolClient.moduleManager.getModules()) {
            String prefix = module.getName() + ".";
            for (Setting<?> setting : module.getSettings()) {
                if (!setting.isSaved()) continue;
                String value = props.getProperty(prefix + "setting." + setting.getName());
                if (value != null) setting.parse(value);
            }
            String key = props.getProperty(prefix + "key");
            if (key != null) {
                try {
                    module.setKey(Integer.parseInt(key));
                } catch (NumberFormatException ignored) {
                }
            }
            String enabled = props.getProperty(prefix + "enabled");
            if (enabled != null && module.savesEnabledState()) {
                module.setEnabled(Boolean.parseBoolean(enabled));
            }
        }
    }

    public static List<String> list() {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir())) {
            files.map(path -> path.getFileName().toString())
                .filter(file -> file.endsWith(".properties"))
                .map(file -> file.substring(0, file.length() - ".properties".length()))
                .sorted()
                .forEach(names::add);
        } catch (IOException e) {
            RelaxedQolMod.LOGGER.error("Failed to list configs", e);
        }
        return names;
    }

    public static void saveFriends() {
        try {
            Files.write(dir().resolve("friends.txt"), FriendManager.getFriends(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            RelaxedQolMod.LOGGER.error("Failed to save friends", e);
        }
    }

    public static void loadFriends() {
        try {
            Path file = dir().resolve("friends.txt");
            if (Files.exists(file)) {
                FriendManager.setAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            RelaxedQolMod.LOGGER.error("Failed to load friends", e);
        }
    }
}
