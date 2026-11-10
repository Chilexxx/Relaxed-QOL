package com.relaxedqol.client.gui;

import com.relaxedqol.client.util.Profiles;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Profiles & Sharing.
 * Profiles tab: every profile (the one in use marked), switch / rename / delete, link the server you are on.
 * Share tab: copy your settings as a share code, save them as a file, paste someone's code or pick a file from the
 * backups folder (or drop one on the window) - always asked first, and the old settings are kept to undo.
 */
public class ProfilesScreen extends WindowScreen {
    private static final int ROW_H = 24;
    private static boolean shareTab;
    private static String picked;

    private final ScrollBar bar = new ScrollBar();
    private final List<int[]> rowRects = new ArrayList<>();
    private final List<String> rowNames = new ArrayList<>();
    private final List<Path> fileRows = new ArrayList<>();
    private List<String> profiles = List.of();
    private List<Path> files = List.of();
    private long listedAt;

    /** A delete waiting for its second click. */
    private String deleteArmed;
    private long deleteArmedAt;

    /** Settings waiting for "Use them?" (null = no question open). */
    private Properties pending;
    private String pendingFrom;
    private int[] useBtn;
    private int[] cancelBtn;

    /** A short line under the buttons after an action ("Copied!"...). */
    private String note = "";
    private long noteAt;

    public ProfilesScreen(Screen parent) {
        super(parent, Component.literal("Profiles & Sharing"), "profiles", 380, 262);
    }

    public static void open(Screen parent) {
        net.minecraft.client.Minecraft.getInstance().gui.setScreen(new ProfilesScreen(parent));
    }

    @Override
    protected String titleInfo() {
        return "in use: " + Profiles.active();
    }

    private void say(String text) {
        note = text;
        noteAt = System.currentTimeMillis();
    }

    private void relist() {
        profiles = Profiles.list();
        files = Profiles.backups();
        listedAt = System.currentTimeMillis();
        if (picked == null || !profiles.contains(picked)) picked = Profiles.active();
    }

    private int sideW() {
        return Math.max(118, contentW() * 38 / 100);
    }

    @Override
    protected void initContent() {
        relist();
        int x = contentX();
        int y = contentY();
        int tw = 84;
        pill(() -> "Profiles", x, y, tw, 14, () -> {
            shareTab = false;
            bar.scroll = 0;
            rebuildWidgets();
        }, 0, () -> !shareTab);
        pill(() -> "Share & Backup", x + tw + 4, y, tw + 10, 14, () -> {
            shareTab = true;
            bar.scroll = 0;
            rebuildWidgets();
        }, 0, () -> shareTab);
        if (shareTab) initShare();
        else initProfiles();
    }

    // ------------------------------------------------------------------ profiles tab

    private void initProfiles() {
        int sx = contentX() + contentW() - sideW();
        int sw = sideW();
        pill(() -> "+ New Profile", contentX() + contentW() - 84, contentY(), 84, 14, () ->
            TextPrompt.open(this, "New profile", "e.g. PvP, Building, Recording - starts from the default settings", "", name -> {
                if (!Profiles.create(name)) say("That name is empty or already used");
                else picked = Profiles.clean(name);
                relist();
            }), 0, () -> false);
        int y = contentY() + 52;
        pill(() -> picked != null && picked.equals(Profiles.active()) ? "In use" : "Switch to it", sx, y, sw, 15, () -> {
            if (picked != null && Profiles.switchTo(picked)) {
                relist();
                rebuildWidgets();
            }
        }, 0, () -> picked != null && picked.equals(Profiles.active()));
        y += 19;
        pill(() -> "Rename", sx, y, sw / 2 - 2, 15, () -> {
            if (picked == null) return;
            String from = picked;
            TextPrompt.open(this, "Rename " + from, "New name", from, name -> {
                if (Profiles.rename(from, name)) picked = Profiles.clean(name);
                else say("That name is empty or already used");
                relist();
            });
        }, 0, () -> false);
        pill(() -> deleteArmed() ? "Click again" : "Delete", sx + sw / 2 + 2, y, sw - sw / 2 - 2, 15, this::askDelete,
            Theme.DANGER, this::deleteArmed);
        y += 19;
        pill(() -> {
            String server = Profiles.currentServer();
            if (server.isEmpty()) return "Join a server to link it";
            return picked != null && picked.equals(Profiles.linkedTo(server)) ? "Unlink this server" : "Use on this server";
        }, sx, y, sw, 15, () -> {
            String server = Profiles.currentServer();
            if (server.isEmpty() || picked == null) return;
            boolean linked = picked.equals(Profiles.linkedTo(server));
            Profiles.link(server, linked ? null : picked);
            say(linked ? "Unlinked " + server : "Joining " + server + " now switches to " + picked);
        }, 0, () -> picked != null && picked.equals(Profiles.linkedTo(Profiles.currentServer())));
    }

    private boolean deleteArmed() {
        return deleteArmed != null && deleteArmed.equals(picked) && System.currentTimeMillis() - deleteArmedAt < 4000;
    }

    /** First click asks ("Click again"), the second deletes - also the profile in use (it switches away first). */
    private void askDelete() {
        if (picked == null) return;
        if (!deleteArmed()) {
            if (profiles.size() <= 1) {
                Toasts.show("Can't delete " + picked, "It is your only profile - make another one first", Theme.WARNING, 3500);
                return;
            }
            deleteArmed = picked;
            deleteArmedAt = System.currentTimeMillis();
            return;
        }
        String name = picked;
        deleteArmed = null;
        if (Profiles.delete(name)) {
            Toasts.show("Profile deleted", name, Theme.DANGER, 3000);
            picked = Profiles.active();
        } else {
            Toasts.show("Couldn't delete " + name, "It is your only profile", Theme.WARNING, 3500);
        }
        relist();
        rebuildWidgets();
    }

    // ------------------------------------------------------------------ share tab

    private void initShare() {
        int x = contentX();
        int w = contentW();
        int half = (w - 6) / 2;
        int y = contentY() + 50;
        pill(() -> "Copy Share Code", x + 8, y, half - 16, 15, () -> {
            String code = Profiles.shareCode();
            if (code.isEmpty()) {
                say("Could not make the code");
                return;
            }
            minecraft.keyboardHandler.setClipboard(code);
            say("Copied! Paste it anywhere - Discord, a message...");
        }, 0, () -> false);
        pill(() -> "Save as File", x + 8, y + 19, half - 16, 15, () -> {
            String name = Profiles.exportFile();
            say(name.isEmpty() ? "Could not save the file" : "Saved \"" + name + "\" in the backups folder");
            relist();
        }, 0, () -> false);
        int rx = x + half + 6;
        pill(() -> "Paste Code", rx + 8, y, half - 16, 15, () -> {
            String text = minecraft.keyboardHandler.getClipboard();
            Properties props = Profiles.parse(text);
            if (props == null) say("No share code in your clipboard - copy one first");
            else ask(props, "the code you copied");
        }, 0, () -> false);
        pill(() -> "Open Backups Folder", rx + 8, y + 19, half - 16, 15, Profiles::openBackupFolder, 0, () -> false);
    }

    private void ask(Properties props, String from) {
        pending = props;
        pendingFrom = from;
    }

    /** Files dropped on the window: the first one that holds settings is offered for import. */
    public void onFilesDrop(List<Path> paths) {
        for (Path p : paths) {
            Properties props = Profiles.readFile(p);
            if (props != null) {
                shareTab = true;
                ask(props, "\"" + p.getFileName() + "\"");
                rebuildWidgets();
                return;
            }
        }
        say("That file has no settings in it");
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (System.currentTimeMillis() - listedAt > 2000) relist();
        rowRects.clear();
        rowNames.clear();
        fileRows.clear();
        if (pending != null) {
            mouseX = -1000;
            mouseY = -1000;
        }
        if (shareTab) drawShare(g, mouseX, mouseY);
        else drawProfiles(g, mouseX, mouseY);
        // The last action's result, for a few seconds
        if (!note.isEmpty() && System.currentTimeMillis() - noteAt < 5000) {
            String n = Theme.ellipsize(note, contentW(), font::width);
            g.text(font, n, contentX(), contentY() + contentH() - 9, Theme.TEXT_DIM, false);
        }
    }

    private void drawProfiles(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = contentX();
        int top = contentY() + 20;
        int lw = contentW() - sideW() - 8;
        int h = contentH() - 20 - 12;
        UiBits.well(g, x, top, lw, h);
        String active = Profiles.active();
        Map<String, String> links = Profiles.allLinks();
        int total = profiles.size() * ROW_H + 4;
        bar.update(x + lw - 5, top + 2, h - 4, total, h);
        int rw = lw - (total > h ? 10 : 4);
        g.enableScissor(x, top, x + lw, top + h);
        int cy = top + 2 - bar.scroll;
        for (String name : profiles) {
            if (cy + ROW_H >= top && cy < top + h) {
                boolean over = UiBits.in(mouseX, mouseY, x + 2, Math.max(cy, top), rw, ROW_H - 2) && mouseY < top + h;
                UiBits.row(g, x + 2, cy, rw, ROW_H - 2, name.equals(picked), over);
                g.text(font, Theme.ellipsize(name, rw - 60, font::width), x + 8, cy + 3, Theme.TEXT, false);
                int servers = 0;
                for (String p : links.values()) if (p.equals(name)) servers++;
                String saved = Profiles.savedAt(name);
                String sub = (saved.isEmpty() ? "" : "saved " + saved) + (servers == 0 ? "" : (saved.isEmpty() ? "" : " · ")
                    + servers + (servers == 1 ? " server" : " servers"));
                g.text(font, Theme.ellipsize(sub, rw - 14, font::width), x + 8, cy + 12, Theme.TEXT_FAINT, false);
                if (name.equals(active)) {
                    int tw = font.width("IN USE") + 8;
                    UiBits.tag(g, font, "IN USE", x + 2 + rw - tw - 5, cy + 5, Theme.SUCCESS);
                }
                rowRects.add(new int[]{x + 2, Math.max(cy, top), rw, Math.min(cy + ROW_H - 2, top + h) - Math.max(cy, top)});
                rowNames.add(name);
            }
            cy += ROW_H;
        }
        g.disableScissor();
        bar.draw(g, mouseX, mouseY);

        // The picked profile
        int sx = x + contentW() - sideW();
        int sw = sideW();
        if (picked == null) return;
        g.text(font, Component.literal(Theme.ellipsize(picked, sw, font::width)).withStyle(net.minecraft.ChatFormatting.BOLD),
            sx, top + 4, Theme.TEXT, false);
        String saved = Profiles.savedAt(picked);
        g.text(font, Theme.ellipsize(picked.equals(active) ? "The settings you use now" : saved.isEmpty() ? "" : "Saved " + saved,
            sw, font::width), sx, top + 16, Theme.TEXT_FAINT, false);
        // Its servers, under the buttons
        int ly = contentY() + 52 + 19 * 3 + 6;
        List<String> mine = new ArrayList<>();
        for (Map.Entry<String, String> e : links.entrySet()) if (e.getValue().equals(picked)) mine.add(e.getKey());
        if (!mine.isEmpty()) {
            g.text(font, "Switches on when you join:", sx, ly, Theme.TEXT_DIM, false);
            ly += 11;
            for (String s : mine) {
                if (ly > contentY() + contentH() - 22) break;
                g.text(font, Theme.ellipsize("· " + s, sw, font::width), sx, ly, Theme.TEXT_SECONDARY, false);
                ly += 10;
            }
        } else {
            for (String line : UiBits.wrap(font, "Switching keeps your current settings in the profile you leave.", sw)) {
                if (ly > contentY() + contentH() - 22) break;
                g.text(font, line, sx, ly, Theme.TEXT_FAINT, false);
                ly += 10;
            }
        }
    }

    private void drawShare(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = contentX();
        int w = contentW();
        int half = (w - 6) / 2;
        int top = contentY() + 20;
        // Two cards: share yours / use someone's
        UiBits.well(g, x, top, half, 68);
        UiBits.well(g, x + half + 6, top, w - half - 6, 68);
        g.text(font, Component.literal("Share yours").withStyle(net.minecraft.ChatFormatting.BOLD), x + 8, top + 7, Theme.TEXT, false);
        g.text(font, Component.literal("Use someone's").withStyle(net.minecraft.ChatFormatting.BOLD), x + half + 14, top + 7,
            Theme.TEXT, false);
        g.text(font, Theme.ellipsize("Modules, keys, HUD, style", half - 16, font::width), x + 8, top + 18, Theme.TEXT_FAINT, false);
        g.text(font, Theme.ellipsize("A code, or drop a file here", w - half - 22, font::width), x + half + 14, top + 18,
            Theme.TEXT_FAINT, false);

        // Files in the backups folder
        int ly = top + 76;
        g.text(font, "Backups folder", x, ly, Theme.TEXT_DIM, false);
        String hint = "click one to use it";
        g.text(font, hint, x + w - font.width(hint), ly, Theme.TEXT_FAINT, false);
        int lt = ly + 11;
        int h = contentY() + contentH() - 12 - lt;
        if (h < 14) return;
        UiBits.well(g, x, lt, w, h);
        if (files.isEmpty()) {
            for (int i = 0; i < 2; i++) {
                String line = i == 0 ? "Nothing here yet" : "Save as File puts your settings here; put files from friends here too";
                g.centeredText(font, Theme.ellipsize(line, w - 10, font::width), x + w / 2, lt + h / 2 - 9 + i * 11,
                    i == 0 ? Theme.TEXT_DIM : Theme.TEXT_FAINT);
            }
            return;
        }
        int rowH = 16;
        int total = files.size() * rowH + 4;
        bar.update(x + w - 5, lt + 2, h - 4, total, h);
        int rw = w - (total > h ? 10 : 4);
        g.enableScissor(x, lt, x + w, lt + h);
        int cy = lt + 2 - bar.scroll;
        for (Path p : files) {
            if (cy + rowH >= lt && cy < lt + h) {
                boolean over = UiBits.in(mouseX, mouseY, x + 2, Math.max(cy, lt), rw, rowH - 1) && mouseY < lt + h;
                UiBits.row(g, x + 2, cy, rw, rowH - 1, false, over);
                String name = p.getFileName().toString();
                String when = "";
                try {
                    when = java.time.LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH));
                } catch (java.io.IOException ignored) {
                    // no date
                }
                g.text(font, Theme.ellipsize(name, rw - 50, font::width), x + 8, cy + 4, over ? Theme.TEXT : Theme.TEXT_SECONDARY, false);
                g.text(font, when, x + 2 + rw - 6 - font.width(when), cy + 4, Theme.TEXT_FAINT, false);
                rowRects.add(new int[]{x + 2, Math.max(cy, lt), rw, Math.min(cy + rowH - 1, lt + h) - Math.max(cy, lt)});
                fileRows.add(p);
            }
            cy += rowH;
        }
        g.disableScissor();
        bar.draw(g, mouseX, mouseY);
    }

    /** "Use these settings?" over the window. */
    @Override
    protected void drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        useBtn = null;
        cancelBtn = null;
        if (pending == null) return;
        g.fill(wx, wy + TITLE_H, wx + ww, wy + wh, 0x60000000);
        int w = Math.min(ww - 20, 250);
        int h = 92;
        int x = wx + (ww - w) / 2;
        int y = wy + (wh - h) / 2 + 6;
        UiBits.dialog(g, x, y, w, h);
        g.text(font, Component.literal("Use these settings?").withStyle(net.minecraft.ChatFormatting.BOLD), x + 10, y + 9, Theme.TEXT, false);
        int known = Profiles.known(pending);
        String line1 = known + " settings from " + pendingFrom;
        g.text(font, Theme.ellipsize(line1, w - 20, font::width), x + 10, y + 23, Theme.TEXT_SECONDARY, false);
        int ly = y + 35;
        for (String l : UiBits.wrap(font, "Yours are kept as the profile \"" + Profiles.BEFORE_IMPORT + "\" - switch to it to undo.", w - 20)) {
            g.text(font, l, x + 10, ly, Theme.TEXT_FAINT, false);
            ly += 10;
        }
        int bw = (w - 26) / 2;
        int by = y + h - 22;
        useBtn = new int[]{x + 10, by, bw, 15};
        cancelBtn = new int[]{x + 16 + bw, by, bw, 15};
        UiBits.button(g, font, "Use them", useBtn[0], by, bw, 15, UiBits.in(mouseX, mouseY, useBtn[0], by, bw, 15), true);
        UiBits.button(g, font, "Cancel", cancelBtn[0], by, bw, 15, UiBits.in(mouseX, mouseY, cancelBtn[0], by, bw, 15), false);
    }

    @Override
    protected boolean overlayClicked(double mx, double my, int button) {
        if (pending == null) return false;
        if (useBtn != null && UiBits.in(mx, my, useBtn[0], useBtn[1], useBtn[2], useBtn[3])) {
            Profiles.importSettings(pending, pendingFrom);
            pending = null;
            picked = Profiles.active();
            relist();
            rebuildWidgets();
        } else if (cancelBtn != null && UiBits.in(mx, my, cancelBtn[0], cancelBtn[1], cancelBtn[2], cancelBtn[3])) {
            pending = null;
        }
        return true;
    }

    @Override
    protected boolean contentClicked(double mx, double my, int button) {
        if (bar.mouseClicked(mx, my)) return true;
        if (button != 0) return false;
        for (int i = 0; i < rowRects.size(); i++) {
            int[] r = rowRects.get(i);
            if (!UiBits.in(mx, my, r[0], r[1], r[2], r[3])) continue;
            if (shareTab) {
                Path p = fileRows.get(i);
                Properties props = Profiles.readFile(p);
                if (props == null) say("\"" + p.getFileName() + "\" has no settings in it");
                else ask(props, "\"" + p.getFileName() + "\"");
            } else {
                picked = rowNames.get(i);
                deleteArmed = null;
            }
            return true;
        }
        return false;
    }

    @Override
    protected boolean contentDragged(double mx, double my) {
        return bar.mouseDragged(my);
    }

    @Override
    protected void contentReleased() {
        bar.release();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        bar.scrollBy(-(int) Math.signum(scrollY) * 24 * 2);
        return true;
    }
}
