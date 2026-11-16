package com.relaxedqol.client.gui;

import com.relaxedqol.client.schematic.PlaceMode;
import com.relaxedqol.client.schematic.Schematic;
import com.relaxedqol.client.schematic.SchematicLibrary;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Schematics Library window: the schematic files on the left (Litematica's .minecraft/schematics folder and ours),
 * on the right what you can do - copy an area or whole chunks out of the world into a file (on a server too), and
 * paste a file into a single-player world.
 */
public class SchematicLibraryScreen extends WindowScreen {
    private static final int FILE_ROW = 16;
    private final ScrollBar fileBar = new ScrollBar();
    private List<Path> files;
    private final List<int[]> fileRects = new ArrayList<>();
    private final List<Path> fileShown = new ArrayList<>();
    private Path selected;
    private SchematicLibrary.Sort sort = SchematicLibrary.Sort.NAME;
    private String message;
    private int messageColor;
    private long messageAt;

    public SchematicLibraryScreen(Screen parent) {
        super(parent, Component.literal("Schematics Library"), "schematics", 430, 250);
        files = SchematicLibrary.files(sort, "");
    }

    @Override
    protected boolean compact() {
        return true;
    }

    @Override
    protected String titleInfo() {
        String busy = SchematicLibrary.pasteState() != null ? SchematicLibrary.pasteState() : SchematicLibrary.copyState();
        return busy != null ? busy : files.size() + " files";
    }

    private static String strip(String text) {
        return text.replaceAll("§.", "");
    }

    private int listW() {
        return Math.min(200, contentW() * 48 / 100);
    }

    private void say(String text, int color) {
        message = text;
        messageColor = color;
        messageAt = System.currentTimeMillis();
    }

    /** A line from the library (a copy saved, a paste done - maybe long after the click): here, or in chat when the window is gone. */
    private void report(String line) {
        files = SchematicLibrary.files(sort, "");
        if (net.minecraft.client.Minecraft.getInstance().gui.screen() == this) say(strip(line), line.startsWith("§c") ? 0xFFFF4D5E : line.startsWith("§a") ? Theme.SUCCESS : Theme.TEXT_DIM);
        else com.relaxedqol.client.util.ChatUtil.send(line);
    }

    @Override
    protected void initContent() {
        int rx = contentX() + listW() + 8;
        int rw = contentW() - listW() - 8;
        int half = (rw - 3) / 2;
        int y = contentY() + 12;
        // ---- copy from the world
        pill(() -> "Select Area", rx, y, half, 14, () -> {
            String no = PlaceMode.select(); // the window closes: left click = corner A, right click = corner B, Enter saves
            if (no != null) say(strip(no), 0xFFFF4D5E);
        }, 0, () -> SchematicLibrary.corner1() != null && SchematicLibrary.corner2() != null);
        pill(() -> "Save Area", rx + half + 3, y, rw - half - 3, 14, () -> TextPrompt.open(this, "Save the area (" + SchematicLibrary.areaLabel() + ") as", "name", "",
            name -> SchematicLibrary.saveArea(name, this::report)), 0, () -> false);
        // whole chunks: first the name, then how many
        pill(() -> SchematicLibrary.copyState() != null ? "Stop Copy" : "Save Chunks Around Me", rx, y + 17, rw, 14, () -> {
            if (SchematicLibrary.copyState() != null) {
                say(strip(SchematicLibrary.stopCopy()), Theme.TEXT_DIM);
                return;
            }
            TextPrompt.open(this, "Save the chunks around you as", "name", "", name ->
            TextPrompt.open(this, "How many chunks out from you? 0 = this one, 1 = 3 x 3, 6 = 13 x 13, up to " + SchematicLibrary.MAX_RADIUS, "number", "1", number -> {
                int radius;
                try {
                    radius = Integer.parseInt(number.trim());
                } catch (NumberFormatException e) {
                    say("That is not a number", 0xFFFF4D5E);
                    return;
                }
                say("Copying the chunks...", Theme.TEXT_DIM);
                SchematicLibrary.saveChunks(radius, name, this::report);
            }));
        }, 0, () -> SchematicLibrary.copyState() != null);
        // ---- paste (single player)
        int py = y + 17 * 2 + 16;
        pill(() -> SchematicLibrary.pasteState() != null ? "Stop Paste" : "Paste Picked File", rx, py, rw, 14, () -> {
            if (SchematicLibrary.pasteState() != null) {
                say(strip(SchematicLibrary.stopPaste()), Theme.TEXT_DIM);
            } else if (selected == null) {
                say("Click a file in the list first", 0xFFFBBF24);
            } else {
                // out into the world: the box it will fill follows your aim, E pastes it there
                String no = PlaceMode.pasteFile(selected);
                if (no != null) say(strip(no), 0xFFFF4D5E);
            }
        }, Theme.SUCCESS, () -> SchematicLibrary.pasteState() != null);
        // ---- the list
        int fy = py + 17 + 16;
        int third = (rw - 6) / 3;
        pill(() -> sort == SchematicLibrary.Sort.NAME ? "A-Z" : sort == SchematicLibrary.Sort.NEWEST ? "Newest" : "Largest", rx, fy, third, 14, () -> {
            sort = SchematicLibrary.Sort.values()[(sort.ordinal() + 1) % SchematicLibrary.Sort.values().length];
            files = SchematicLibrary.files(sort, "");
        }, 0, () -> false);
        pill(() -> "Refresh", rx + third + 3, fy, third, 14, () -> files = SchematicLibrary.files(sort, ""), 0, () -> false);
        pill(() -> "Folder", rx + (third + 3) * 2, fy, rw - (third + 3) * 2, 14, () -> {
            Path dir = SchematicLibrary.folder();
            try {
                java.nio.file.Files.createDirectories(dir);
            } catch (java.io.IOException ignored) {
                // shown anyway
            }
            net.minecraft.util.Util.getPlatform().openUri(dir.toUri().toString());
        }, 0, () -> false);
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = contentX();
        int y = contentY();
        int h = contentH();
        int lw = listW();
        int accent = Theme.accent();
        fileRects.clear();
        fileShown.clear();
        // ---- files
        Render2D.roundRect(g, x, y, lw, h, 6, 0x22000000);
        Render2D.roundOutline(g, x, y, lw, h, 6, 0x18FFFFFF);
        if (files.isEmpty()) {
            g.text(font, "No schematics yet.", x + 6, y + 6, Theme.TEXT_DIM, false);
            g.text(font, "Copy an area or chunks,", x + 6, y + 20, Theme.TEXT_FAINT, false);
            g.text(font, "or put .schem / .litematic", x + 6, y + 30, Theme.TEXT_FAINT, false);
            g.text(font, "files in the folder.", x + 6, y + 40, Theme.TEXT_FAINT, false);
        } else {
            int total = files.size() * FILE_ROW;
            fileBar.update(x + lw - 4, y + 2, h - 4, total, h - 4);
            g.enableScissor(x + 1, y + 1, x + lw - 1, y + h - 1);
            int ry = y + 3 - fileBar.scroll;
            int rw = lw - 6 - (total > h - 4 ? 6 : 0);
            for (Path f : files) {
                if (ry + FILE_ROW >= y && ry <= y + h) {
                    boolean picked = f.equals(selected);
                    boolean hover = mouseX >= x + 3 && mouseX < x + 3 + rw && mouseY >= ry && mouseY < ry + FILE_ROW - 2;
                    if (picked || hover) Render2D.roundRect(g, x + 3, ry, rw, FILE_ROW - 2, 4, picked ? Theme.withAlpha(accent, 0.3f) : 0x20FFFFFF);
                    String n = f.getFileName().toString();
                    String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
                    String base = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
                    int room = rw - font.width(ext) - 14;
                    if (font.width(base) > room) base = font.plainSubstrByWidth(base, room - font.width("…")) + "…";
                    g.text(font, base, x + 7, ry + 3, picked ? Theme.TEXT : Theme.TEXT_DIM, false);
                    g.text(font, ext, x + 3 + rw - 4 - font.width(ext), ry + 3, Theme.TEXT_FAINT, false);
                    fileShown.add(f);
                    fileRects.add(new int[]{x + 3, Math.max(ry, y), rw, Math.min(ry + FILE_ROW - 2, y + h) - Math.max(ry, y)});
                }
                ry += FILE_ROW;
            }
            g.disableScissor();
            fileBar.draw(g, mouseX, mouseY);
        }
        // ---- the headings over the button groups, and what is picked
        int rx = x + lw + 8;
        int rw = contentW() - lw - 8;
        g.text(font, "COPY FROM THE WORLD", rx, y + 1, Theme.TEXT_FAINT, false);
        int py = y + 12 + 17 * 2 + 16;
        g.text(font, "PASTE  (single player)", rx, py - 11, Theme.TEXT_FAINT, false);
        int fy = py + 17 + 16;
        g.text(font, "FILES", rx, fy - 11, Theme.TEXT_FAINT, false);
        int ty = fy + 22;
        if (selected != null) {
            g.text(font, fit(selected.getFileName().toString(), rw), rx, ty, Theme.TEXT, false);
            g.text(font, kb(SchematicLibrary.size(selected)), rx, ty + 11, Theme.TEXT_FAINT, false);
        } else {
            g.text(font, fit("Works on a server too: copy what", rw), rx, ty, Theme.TEXT_FAINT, false);
            g.text(font, fit("you see, paste it in single player.", rw), rx, ty + 10, Theme.TEXT_FAINT, false);
        }
        if (message != null && System.currentTimeMillis() - messageAt < 6000) {
            String text = fit(message, rw - 12);
            int w = font.width(text) + 12;
            int my = y + h - 15;
            Render2D.roundRect(g, rx + rw - w, my, w, 14, 5, 0xF0101018);
            Render2D.roundOutline(g, rx + rw - w, my, w, 14, 5, messageColor);
            g.text(font, text, rx + rw - w + 6, my + 3, messageColor, false);
        }
    }

    private static String kb(long bytes) {
        return bytes < 1024 ? bytes + " bytes" : bytes < 1024 * 1024 ? bytes / 1024 + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    /** The text cut with ".." to fit maxW pixels. */
    private String fit(String text, int maxW) {
        if (font.width(text) <= maxW) return text;
        String cut = text;
        while (cut.length() > 1 && font.width(cut + "..") > maxW) cut = cut.substring(0, cut.length() - 1);
        return cut + "..";
    }

    @Override
    protected boolean contentClicked(double mx, double my, int button) {
        if (fileBar.mouseClicked(mx, my)) return true;
        for (int i = 0; i < fileRects.size(); i++) {
            int[] r = fileRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                selected = fileShown.get(i);
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean contentDragged(double mx, double my) {
        return fileBar.mouseDragged(my);
    }

    @Override
    protected void contentReleased() {
        fileBar.release();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        fileBar.scrollBy(-(int) Math.signum(scrollY) * FILE_ROW);
        return true;
    }
}
