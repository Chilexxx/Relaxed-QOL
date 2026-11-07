package com.relaxedqol.client.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Your in-game notes, kept in the config folder (notes.json) so they survive restarts. */
public final class NotesStore {
    private static final String NS = "relaxed-qol";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One note: its text, whether it is pinned on the HUD, and when it was made / changed. */
    public static final class Note {
        public String text;
        public boolean pinned;
        public long changed;

        public Note(String text) {
            this.text = text;
            this.changed = System.currentTimeMillis();
        }
    }

    private static List<Note> notes;

    private NotesStore() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(NS).resolve("notes.json");
    }

    public static List<Note> all() {
        if (notes == null) {
            notes = new ArrayList<>();
            try {
                Path f = file();
                if (Files.isRegularFile(f)) {
                    List<Note> loaded = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Note>>() {}.getType());
                    if (loaded != null) for (Note n : loaded) if (n != null && n.text != null) notes.add(n);
                }
            } catch (Exception ignored) {
                // a broken file: start empty (it is overwritten on the next save)
            }
        }
        return notes;
    }

    public static List<Note> pinned() {
        List<Note> out = new ArrayList<>();
        for (Note n : all()) if (n.pinned) out.add(n);
        return out;
    }

    public static void add(String text) {
        if (text == null || text.isBlank()) return;
        all().add(0, new Note(text.trim()));
        save();
    }

    public static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(all()), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // kept in memory; saved next time
        }
    }

    /** Word-wraps text to lines no wider than width (long words are cut). */
    public static List<String> wrap(net.minecraft.client.gui.Font font, String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (font.width(candidate) <= width) {
                line = new StringBuilder(candidate);
                continue;
            }
            if (line.length() > 0) out.add(line.toString());
            line = new StringBuilder(word);
            while (font.width(line.toString()) > width && line.length() > 1) {
                int cut = line.length() - 1;
                while (cut > 1 && font.width(line.substring(0, cut)) > width) cut--;
                out.add(line.substring(0, cut));
                line = new StringBuilder(line.substring(cut));
            }
        }
        if (line.length() > 0 || out.isEmpty()) out.add(line.toString());
        return out;
    }
}
