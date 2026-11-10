package com.relaxedqol.client.gui;

import com.relaxedqol.client.util.NotepadStore;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Notepad window: a big text area to write as much as you like (many lines, Enter for a new line), with pages on
 * the left like a notebook. Everything is saved by itself (a second after you stop typing, and when the window
 * closes) in the config folder (notepad.json).
 */
public class NotepadScreen extends WindowScreen {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault());
    private static final int LIST_W = 100;
    private static final int ROW_H = 24;
    /** The page shown last time (the Notepad opens where you left off). */
    private static NotepadStore.Page lastPage;

    private NotepadStore.Page page;
    private MultiLineEditBox editor;
    private final ScrollBar bar = new ScrollBar();
    private final List<int[]> rowRects = new ArrayList<>();
    private final List<NotepadStore.Page> rowPages = new ArrayList<>();
    private boolean confirmDelete;
    /** The word / line count under the text and the page's title, worked out only when the text changes. */
    private String stats;
    private String currentTitle = "Untitled";
    private long copiedAt;

    public NotepadScreen(Screen parent) {
        super(parent, Component.literal("Notepad"), "notepad", 420, 280);
        List<NotepadStore.Page> all = NotepadStore.all();
        page = lastPage != null && all.contains(lastPage) ? lastPage : all.get(0);
    }

    @Override
    protected String titleInfo() {
        int n = NotepadStore.all().size();
        return n == 1 ? "1 page" : n + " pages";
    }

    @Override
    protected int minWidth() {
        return 300;
    }

    @Override
    protected int minHeight() {
        return 160;
    }

    @Override
    protected void initContent() {
        int x = contentX();
        int y = contentY();
        // Left: the pages
        pill(() -> "+  New Page", x, y, LIST_W, 14, this::newPage, 0, () -> false);
        // Right, above the text: copy / delete this page
        int ex = x + LIST_W + 6;
        int ew = contentW() - LIST_W - 6;
        int dw = 70;
        pill(() -> confirmDelete ? "Sure? Delete" : "Delete Page", ex + ew - dw, y, dw, 14, this::deletePage,
            0xFFFF453A, () -> confirmDelete);
        int cw = 54;
        pill(() -> System.currentTimeMillis() - copiedAt < 1500 ? "Copied" : "Copy All", ex + ew - dw - 4 - cw, y, cw, 14,
            this::copyAll, 0, () -> System.currentTimeMillis() - copiedAt < 1500);
        // The text area - as big as the window allows
        int ey = y + 20;
        int eh = Math.max(40, contentY() + contentH() - 12 - ey);
        editor = MultiLineEditBox.builder()
            .setX(ex)
            .setY(ey)
            .setPlaceholder(Component.literal("Write anything here - plans, lists, coordinates, reminders... Enter starts a new line."))
            .build(font, ew, eh, Component.literal("Notepad"));
        editor.setCharacterLimit(NotepadStore.MAX_CHARS);
        editor.setValue(page.text == null ? "" : page.text);
        editor.setValueListener(text -> {
            NotepadStore.changed(page, text);
            stats = null;
        });
        stats = null;
        addRenderableWidget(editor);
        setInitialFocus(editor);
        // The word count line under the text
        reserve(ex, ey + eh + 2, ew, 9);
    }

    private void newPage() {
        confirmDelete = false;
        NotepadStore.Page p = NotepadStore.add();
        if (p == null) {
            Toasts.show("Notepad", "You have " + NotepadStore.MAX_PAGES + " pages - delete one first", Theme.accent(), 2500);
            return;
        }
        switchTo(p);
        bar.scroll = 0;
    }

    private void deletePage() {
        if (!confirmDelete) {
            confirmDelete = true;
            return;
        }
        confirmDelete = false;
        NotepadStore.remove(page);
        switchTo(NotepadStore.all().get(0));
    }

    private void copyAll() {
        minecraft.keyboardHandler.setClipboard(editor.getValue());
        copiedAt = System.currentTimeMillis();
    }

    private void switchTo(NotepadStore.Page p) {
        NotepadStore.save();
        page = p;
        lastPage = p;
        stats = null;
        if (editor != null) {
            editor.setValue(p.text == null ? "" : p.text);
            setFocused(editor);
        }
    }

    @Override
    public void tick() {
        super.tick();
        NotepadStore.saveSoon();
    }

    @Override
    public void removed() {
        lastPage = page;
        NotepadStore.save();
        super.removed();
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        rowRects.clear();
        rowPages.clear();
        int x = contentX();
        int top = contentY() + 20;
        int h = contentY() + contentH() - top;
        List<NotepadStore.Page> pages = NotepadStore.all();
        Render2D.roundRect(g, x, top, LIST_W, h, 2, 0x1A000000);
        int total = pages.size() * ROW_H;
        bar.update(x + LIST_W - 5, top + 3, h - 6, total, h);
        int accent = Theme.accent();
        g.enableScissor(x, top, x + LIST_W, top + h);
        int cy = top - bar.scroll;
        int rw = LIST_W - (total > h ? 8 : 0);
        for (NotepadStore.Page p : pages) {
            if (cy + ROW_H >= top && cy <= top + h) {
                boolean current = p == page;
                boolean hover = mouseX >= x && mouseX < x + rw && mouseY >= cy && mouseY < cy + ROW_H && mouseY >= top && mouseY < top + h;
                if (current) Render2D.roundRect(g, x + 3, cy + 2, rw - 6, ROW_H - 4, 2, Theme.withAlpha(accent, 0.30f));
                else if (hover) Render2D.roundRect(g, x + 3, cy + 2, rw - 6, ROW_H - 4, 2, 0x1AFFFFFF);
                String title = p == page ? currentTitle : p.title();
                g.text(font, Theme.ellipsize(title, rw - 16, font::width), x + 8, cy + 4, current ? 0xFFFFFFFF : 0xFFE5E5EA, false);
                g.text(font, WHEN.format(Instant.ofEpochMilli(p.changed)), x + 8, cy + 14, 0xFF8E8E93, false);
                rowRects.add(new int[]{x, Math.max(cy, top), rw, Math.min(cy + ROW_H, top + h) - Math.max(cy, top)});
                rowPages.add(p);
            }
            cy += ROW_H;
        }
        g.disableScissor();
        bar.draw(g, mouseX, mouseY);
        // Under the text: how much is written
        if (editor != null) {
            if (stats == null) {
                String text = editor.getValue();
                int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
                int lines = 0;
                if (!text.isEmpty()) {
                    lines = 1;
                    for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
                }
                // (the character count is drawn by the text box itself, on the right)
                stats = words + (words == 1 ? " word" : " words") + "  \u00b7  " + lines + (lines == 1 ? " line" : " lines");
                currentTitle = NotepadStore.titleOf(text);
            }
            g.text(font, stats, editor.getX(), editor.getY() + editor.getHeight() + 3, 0xFF8E8E93, false);
        }
    }

    @Override
    protected boolean contentClicked(double mx, double my, int button) {
        if (bar.mouseClicked(mx, my)) return true;
        if (button != 0) return false;
        for (int i = 0; i < rowRects.size(); i++) {
            int[] r = rowRects.get(i);
            if (mx < r[0] || mx >= r[0] + r[2] || my < r[1] || my >= r[1] + r[3]) continue;
            confirmDelete = false;
            if (rowPages.get(i) != page) switchTo(rowPages.get(i));
            return true;
        }
        confirmDelete = false;
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
        // Over the page list: scroll the pages; anywhere else: the text
        double vx = vx(x);
        double vy = vy(y);
        if (vx >= contentX() && vx < contentX() + LIST_W && vy >= contentY() + 20) {
            bar.scrollBy(-(int) Math.signum(scrollY) * 20);
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }
}
