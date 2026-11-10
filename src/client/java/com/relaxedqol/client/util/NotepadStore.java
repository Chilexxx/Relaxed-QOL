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

/**
 * The Notepad's pages: long, free text (lists, plans, coordinates, whole paragraphs), kept in the config folder
 * (notepad.json) so they survive restarts. Unlike {@link NotesStore} (short notes you pin on the HUD) a page can
 * hold a lot of text over many lines.
 */
public final class NotepadStore {
    private static final String NS = "relaxed-qol";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** How much one page may hold (characters) - plenty for long lists and plans. */
    public static final int MAX_CHARS = 100_000;
    public static final int MAX_PAGES = 50;

    /** One page: its text and when it was last changed. The title is the first line. */
    public static final class Page {
        public String text;
        public long changed;

        public Page(String text) {
            this.text = text;
            this.changed = System.currentTimeMillis();
        }

        /** The first non-empty line, shortened ("Untitled" for an empty page). */
        public String title() {
            return titleOf(text);
        }
    }

    /** The first non-empty line of a text, at most 40 characters (without splitting the whole text). */
    public static String titleOf(String text) {
        if (text != null) {
            int start = 0;
            int n = text.length();
            while (start < n) {
                int end = text.indexOf('\n', start);
                if (end < 0) end = n;
                String line = text.substring(start, Math.min(end, start + 200)).trim();
                if (!line.isEmpty()) return line.length() > 40 ? line.substring(0, 40) : line;
                start = end + 1;
            }
        }
        return "Untitled";
    }

    private static List<Page> pages;
    private static boolean dirty;
    private static long dirtySince;

    private NotepadStore() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(NS).resolve("notepad.json");
    }

    /** All pages, most recently made first. There is always at least one (an empty page to start writing). */
    public static List<Page> all() {
        if (pages == null) {
            pages = new ArrayList<>();
            try {
                Path f = file();
                if (Files.isRegularFile(f)) {
                    List<Page> loaded = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Page>>() {}.getType());
                    if (loaded != null) for (Page p : loaded) if (p != null && p.text != null) pages.add(p);
                }
            } catch (Exception ignored) {
                // a broken file: start empty (it is overwritten on the next save)
            }
        }
        if (pages.isEmpty()) pages.add(new Page(""));
        return pages;
    }

    /** A new empty page at the top. Returns it (null when the page limit is reached). */
    public static Page add() {
        if (all().size() >= MAX_PAGES) return null;
        Page p = new Page("");
        all().add(0, p);
        save();
        return p;
    }

    public static void remove(Page p) {
        all().remove(p);
        save();
    }

    /** The text of a page changed; written to disk a moment later (not on every key press). */
    public static void changed(Page p, String text) {
        if (p == null || text == null || text.equals(p.text)) return;
        p.text = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        p.changed = System.currentTimeMillis();
        if (!dirty) dirtySince = System.currentTimeMillis();
        dirty = true;
    }

    /** Saves when there are changes older than a second (call every tick while the Notepad is open). */
    public static void saveSoon() {
        if (dirty && System.currentTimeMillis() - dirtySince > 1000) save();
    }

    public static void save() {
        dirty = false;
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("notepad.json.tmp");
            Files.writeString(tmp, GSON.toJson(all()), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // kept in memory; saved next time
            dirty = true;
            dirtySince = System.currentTimeMillis();
        }
    }
}
