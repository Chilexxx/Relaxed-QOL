package com.relaxedqol.client.gui;

import com.relaxedqol.client.util.NotesStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Notes window: your notes as clean cards, newest first. Click a note to edit it, the pin to show it on the
 * HUD, the cross to delete it (click twice - the first click asks).
 */
public class NotesScreen extends WindowScreen {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault());
    private final ScrollBar bar = new ScrollBar();
    private final List<int[]> rects = new ArrayList<>();
    private final List<Object[]> actions = new ArrayList<>();
    private NotesStore.Note confirmDelete;

    public NotesScreen(Screen parent) {
        super(parent, Component.literal("Notes"), "notes", 320, 230);
    }

    @Override
    protected String titleInfo() {
        int n = NotesStore.all().size();
        return n == 1 ? "1 note" : n + " notes";
    }

    @Override
    protected void initContent() {
        int bw = 74;
        pill(() -> "+  New Note", contentX() + contentW() - bw, contentY(), bw, 14, () -> TextPrompt.open(this, "New note",
            "e.g. buy 3 stacks of glass, meet at /warp shop 8pm", "", NotesStore::add), 0, () -> true);
        // Need more room? The Notepad holds long text over many lines and pages
        int nw = 64;
        pill(() -> "Notepad", contentX() + contentW() - bw - 4 - nw, contentY(), nw, 14,
            () -> minecraft.gui.setScreen(new NotepadScreen(this)), 0, () -> false);
    }

    private static final int YELLOW = 0xFFFFD60A;

    /**
     * A calm list, each note's first line as its title, a grey preview and the date
     * under it, a yellow pin on pinned ones; pin / delete appear on the row under the mouse.
     */
    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        rects.clear();
        actions.clear();
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int pinnedCount = 0;
        for (NotesStore.Note n : NotesStore.all()) if (n.pinned) pinnedCount++;
        g.text(font, pinnedCount == 0 ? "Pin a note to see it on your HUD" : pinnedCount + " pinned on your HUD", x + 2, y + 3, 0xFF8E8E93, false);
        int top = y + 20;
        int h = contentH() - 20;
        List<NotesStore.Note> notes = NotesStore.all();
        Render2D.roundRect(g, x, top, w, h, 8, 0x1A000000);
        if (notes.isEmpty()) {
            Render2D.icon(g, "note", x + w / 2 - 8, top + h / 2 - 26, 16, 0x55FFFFFF);
            g.centeredText(font, Component.literal("No Notes").withStyle(net.minecraft.ChatFormatting.BOLD), x + w / 2, top + h / 2 - 4, 0xFFC7C7CC);
            g.centeredText(font, "Press New Note to write one", x + w / 2, top + h / 2 + 8, 0xFF8E8E93);
            return;
        }
        int textW = w - 60;
        int rowH = 34;
        int total = notes.size() * rowH;
        bar.update(x + w - 5, top + 3, h - 6, total, h);
        g.enableScissor(x, top, x + w, top + h);
        int cy = top - bar.scroll;
        for (int i = 0; i < notes.size(); i++) {
            NotesStore.Note n = notes.get(i);
            int cw = w - (total > h ? 8 : 0);
            if (cy + rowH >= top && cy <= top + h) {
                boolean hover = mouseX >= x && mouseX < x + cw && mouseY >= cy && mouseY < cy + rowH && mouseY >= top && mouseY < top + h;
                if (hover) Render2D.roundRect(g, x + 3, cy + 2, cw - 6, rowH - 4, 6, 0x1AFFFFFF);
                else if (i > 0) g.fill(x + 12, cy, x + cw - 12, cy + 1, 0x14FFFFFF);
                List<String> lines = NotesStore.wrap(font, n.text, textW);
                String title = lines.isEmpty() ? "" : lines.get(0);
                String rest = lines.size() > 1 ? String.join(" ", lines.subList(1, lines.size())) : "";
                int tx = x + 12;
                if (n.pinned) {
                    Render2D.icon(g, "pin", tx, cy + 6, 8, YELLOW);
                    tx += 11;
                }
                g.text(font, Component.literal(cut(title, x + cw - 78 - tx)).withStyle(net.minecraft.ChatFormatting.BOLD), tx, cy + 6, 0xFFF2F2F7, false);
                String date = WHEN.format(Instant.ofEpochMilli(n.changed));
                String preview = rest.isEmpty() ? "No additional text" : rest;
                g.text(font, date, x + 12, cy + 19, 0xFF8E8E93, false);
                int px = x + 12 + font.width(date) + 7;
                g.text(font, cut(preview, x + cw - 78 - px), px, cy + 19, 0xFF6E6E73, false);
                // Pin and delete: quiet icons that light up under the mouse
                int bx = x + cw - 44;
                int by = cy + (rowH - 16) / 2;
                boolean overPin = mouseX >= bx && mouseX < bx + 16 && mouseY >= by && mouseY < by + 16;
                int dx = bx + 20;
                boolean asking = confirmDelete == n;
                boolean overDel = mouseX >= dx && mouseX < dx + 16 && mouseY >= by && mouseY < by + 16;
                // Copy - the note's text on your clipboard
                int ccx = bx - 20;
                boolean overCopy = mouseX >= ccx && mouseX < ccx + 16 && mouseY >= by && mouseY < by + 16;
                boolean justCopied = copiedNote == n && System.currentTimeMillis() - copiedAt < 1500;
                if (hover || asking || justCopied) {
                    if (overCopy || justCopied) Render2D.roundRect(g, ccx, by, 16, 16, 8, justCopied ? 0x5534C759 : 0x22FFFFFF);
                    int ic = justCopied ? 0xFF34C759 : overCopy ? 0xFFFFFFFF : 0xFFAEAEB2;
                    Render2D.roundOutline(g, ccx + 6, by + 3, 7, 8, 2, ic);
                    Render2D.roundRect(g, ccx + 3, by + 6, 7, 8, 2, 0xFF1C1C21);
                    Render2D.roundOutline(g, ccx + 3, by + 6, 7, 8, 2, ic);
                    if (justCopied) g.text(font, "Copied", ccx - font.width("Copied") - 4, by + 4, 0xFF34C759, false);
                }
                rects.add(new int[]{ccx, by, 16, 16});
                actions.add(new Object[]{"copy", n});
                if (hover || asking) {
                    if (overPin) Render2D.roundRect(g, bx, by, 16, 16, 8, 0x22FFFFFF);
                    Render2D.icon(g, "pin", bx + 4, by + 4, 8, n.pinned ? YELLOW : overPin ? 0xFFFFFFFF : 0xFFAEAEB2);
                    if (asking) Render2D.roundRect(g, dx, by, 16, 16, 8, 0xFFFF453A);
                    else if (overDel) Render2D.roundRect(g, dx, by, 16, 16, 8, 0x40FF453A);
                    Render2D.icon(g, "trash", dx + 4, by + 4, 8, asking ? 0xFFFFFFFF : overDel ? 0xFFFF6961 : 0xFFAEAEB2);
                    if (asking) g.text(font, "Click again to delete", dx - font.width("Click again to delete") - 30, by + 4, 0xFFFF6961, false);
                }
                rects.add(new int[]{bx, by, 16, 16});
                actions.add(new Object[]{"pin", n});
                rects.add(new int[]{dx, by, 16, 16});
                actions.add(new Object[]{"delete", n});
                rects.add(new int[]{x, Math.max(cy, top), cw, Math.min(cy + rowH, top + h) - Math.max(cy, top)});
                actions.add(new Object[]{"edit", n});
            }
            cy += rowH;
        }
        g.disableScissor();
        bar.draw(g, mouseX, mouseY);
    }

    /** Which note was copied just now (for the green tick). */
    private NotesStore.Note copiedNote;
    private long copiedAt;

    /** Shortened with "\u2026" to fit the width. */
    private String cut(String text, int width) {
        if (width <= 8) return "";
        if (font.width(text) <= width) return text;
        String s = text;
        while (s.length() > 1 && font.width(s + "\u2026") > width) s = s.substring(0, s.length() - 1);
        return s + "\u2026";
    }

    @Override
    protected boolean contentClicked(double mx, double my, int button) {
        if (bar.mouseClicked(mx, my)) return true;
        if (button != 0) return false;
        for (int i = 0; i < rects.size(); i++) {
            int[] r = rects.get(i);
            if (mx < r[0] || mx >= r[0] + r[2] || my < r[1] || my >= r[1] + r[3]) continue;
            String what = (String) actions.get(i)[0];
            NotesStore.Note n = (NotesStore.Note) actions.get(i)[1];
            switch (what) {
                case "copy" -> {
                    minecraft.keyboardHandler.setClipboard(n.text);
                    copiedNote = n;
                    copiedAt = System.currentTimeMillis();
                }
                case "pin" -> {
                    n.pinned = !n.pinned;
                    NotesStore.save();
                }
                case "delete" -> {
                    if (confirmDelete == n) {
                        NotesStore.all().remove(n);
                        NotesStore.save();
                        confirmDelete = null;
                    } else {
                        confirmDelete = n;
                    }
                }
                default -> TextPrompt.open(this, "Edit note", "Empty = delete the note", n.text, text -> {
                    if (text == null || text.isBlank()) NotesStore.all().remove(n);
                    else {
                        n.text = text.trim();
                        n.changed = System.currentTimeMillis();
                    }
                    NotesStore.save();
                });
            }
            if (!"delete".equals(what)) confirmDelete = null;
            return true;
        }
        confirmDelete = null;
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
        bar.scrollBy(-(int) Math.signum(scrollY) * 20);
        return true;
    }
}
