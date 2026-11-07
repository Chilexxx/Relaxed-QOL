package com.relaxedqol.client.gui;

import com.relaxedqol.client.module.modules.misc.PlayerAlert;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PlayerAlert's watch list: every name as a card - online dot, name, where they are (LiveMap) - with an
 * x to take it off the list (no typing the name again), "+ Add names" and "Clear all" (click twice).
 */
public class PlayerAlertScreen extends WindowScreen {
    private static final int ROW = 18;

    private final PlayerAlert alert;
    private final ScrollBar bar = new ScrollBar();
    /** Remove buttons drawn last frame: {x, y, w, h} -> name. */
    private final List<int[]> removeRects = new ArrayList<>();
    private final List<String> removeNames = new ArrayList<>();
    private long clearArmedAt;

    public PlayerAlertScreen(Screen parent, PlayerAlert alert) {
        super(parent, Component.literal("PlayerAlert - Watch List"), "playeralert2", 380, 260);
        this.alert = alert;
    }

    @Override
    protected boolean compact() {
        return true;
    }

    @Override
    protected boolean rememberPosition() {
        return false;
    }

    @Override
    protected String titleInfo() {
        int on = 0;
        for (String key : alert.watched()) if (alert.isOnline(key)) on++;
        return on + " online";
    }

    @Override
    protected void initContent() {
        int y = contentY() + contentH() - 14;
        pill(() -> "+ Add names", contentX(), y, 86, 14, () -> TextPrompt.open(this, "Add names to watch",
            "exact Minecraft names, several with commas", "", value -> {
                for (String n : value.split("[,; ]+")) if (!n.isBlank()) alert.add(n.trim());
            }), Theme.SUCCESS, () -> false);
        // Copy every name on the list (one per line)
        int copyW = COPY_W;
        pill(() -> System.currentTimeMillis() - copiedAt < 1500 ? "Copied!" : "Copy all", contentX() + contentW() - 96 - 4 - copyW, y, copyW, 14,
            this::copyAll, 0, () -> System.currentTimeMillis() - copiedAt < 1500);
        pill(() -> System.currentTimeMillis() - clearArmedAt < 3000 ? "Sure? Click again" : "Clear all",
            contentX() + contentW() - 96, y, 96, 14, () -> {
                if (System.currentTimeMillis() - clearArmedAt < 3000) {
                    alert.clearList();
                    clearArmedAt = 0;
                } else {
                    clearArmedAt = System.currentTimeMillis();
                }
            }, Theme.DANGER, () -> System.currentTimeMillis() - clearArmedAt < 3000);
    }

    private static final int COPY_W = 58;
    private long copiedAt;
    /** Name rows drawn last frame (click = copy the name): {x, y, w, h} -> name. */
    private final List<int[]> nameRects = new ArrayList<>();
    private final List<String> nameKeys = new ArrayList<>();
    private String copiedKey;
    private long copiedKeyAt;

    private void copyAll() {
        List<String> names = new ArrayList<>();
        for (String key : alert.watched()) names.add(alert.nice(key));
        if (names.isEmpty()) return;
        minecraft.keyboardHandler.setClipboard(String.join("\n", names));
        copiedAt = System.currentTimeMillis();
        Toasts.show("Copied", names.size() + " names", Theme.accent(), 2000);
    }

    private void copyOne(String key) {
        String name = alert.nice(key);
        minecraft.keyboardHandler.setClipboard(name);
        copiedKey = key;
        copiedKeyAt = System.currentTimeMillis();
        Toasts.show("Copied", name, Theme.accent(), 2000);
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = contentX();
        int top = contentY();
        int w = contentW();
        int accent = Theme.accent();
        removeRects.clear();
        removeNames.clear();
        nameRects.clear();
        nameKeys.clear();

        List<String> keys = new ArrayList<>(alert.watched());
        keys.sort((a, b) -> {
            int c = Boolean.compare(alert.isOnline(b), alert.isOnline(a));
            return c != 0 ? c : a.compareTo(b);
        });
        int listTop = top;
        int listH = contentH() - 20;
        if (keys.isEmpty()) {
            Render2D.roundRect(g, x, listTop, w, listH, 8, 0x14FFFFFF);
            g.centeredText(font, "Nobody on your watch list yet", x + w / 2, listTop + listH / 2 - 10, Theme.TEXT_DIM);
            g.centeredText(font, "Click \"+ Add names\" below", x + w / 2, listTop + listH / 2 + 2, Theme.TEXT_FAINT);
            return;
        }
        int total = keys.size() * ROW;
        g.enableScissor(x, listTop, x + w, listTop + listH);
        int y = listTop - bar.scroll;
        boolean overList = mouseY >= listTop && mouseY < listTop + listH;
        int cardW = w - (total > listH ? 6 : 0);
        for (String key : keys) {
            if (y + ROW >= listTop && y <= listTop + listH) {
                boolean on = alert.isOnline(key);
                boolean hover = overList && mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + ROW - 2;
                Render2D.roundRect(g, x, y, cardW, ROW - 2, 6, hover ? 0x22FFFFFF : 0x12FFFFFF);
                if (on) Render2D.roundOutline(g, x, y, cardW, ROW - 2, 6, Theme.withAlpha(Theme.SUCCESS, 0.35f));
                Render2D.roundRect(g, x + 7, y + 6, 4, 4, 2, on ? Theme.SUCCESS : 0x50FFFFFF);
                // Click a name to copy it ("copied" flashes next to it)
                String nice = alert.nice(key);
                int nameW = font.width(nice);
                boolean overName = hover && mouseX >= x + 14 && mouseX < x + 18 + nameW;
                g.text(font, nice, x + 16, y + 4, overName ? accent : on ? Theme.TEXT : Theme.TEXT_DIM, false);
                if (overName) g.fill(x + 16, y + 13, x + 16 + nameW, y + 14, Theme.withAlpha(accent, 0.8f));
                nameRects.add(new int[]{x + 14, Math.max(y, listTop), nameW + 4, ROW - 2});
                nameKeys.add(key);
                // Remove button (x)
                int bx = x + cardW - 18;
                boolean overX = hover && mouseX >= bx && mouseX < bx + 14;
                Render2D.roundRect(g, bx, y + 2, 14, ROW - 6, 5, overX ? Theme.withAlpha(Theme.DANGER, 0.6f) : 0x1EFFFFFF);
                g.centeredText(font, "✕", bx + 7, y + 4, overX ? Theme.TEXT : Theme.TEXT_FAINT);
                removeRects.add(new int[]{bx, Math.max(y + 2, listTop), 14, ROW - 6});
                removeNames.add(key);
                // Copy button
                int cbx = bx - 17;
                boolean overC = hover && mouseX >= cbx && mouseX < cbx + 14;
                Render2D.roundRect(g, cbx, y + 2, 14, ROW - 6, 5, overC ? Theme.withAlpha(accent, 0.55f) : 0x1EFFFFFF);
                // A drawn "copy" icon: two overlapping pages
                int ic = overC ? Theme.TEXT : Theme.TEXT_DIM;
                Render2D.roundOutline(g, cbx + 5, y + 4, 6, 7, 1, ic);
                g.fill(cbx + 3, y + 6, cbx + 5, y + 7, ic);
                g.fill(cbx + 3, y + 6, cbx + 4, y + 13, ic);
                g.fill(cbx + 3, y + 12, cbx + 9, y + 13, ic);
                nameRects.add(new int[]{cbx, Math.max(y + 2, listTop), 14, ROW - 6});
                nameKeys.add(key);
                // Where they are / online state
                String where = alert.placeOf(key);
                boolean justCopied = key.equals(copiedKey) && System.currentTimeMillis() - copiedKeyAt < 1200;
                String right = justCopied ? "copied!" : !on ? "offline" : where != null ? where : "online";
                int color = justCopied ? accent : where != null && on ? accent : on ? Theme.SUCCESS : Theme.TEXT_FAINT;
                int rightX = Math.max(x + 20 + nameW + 6, cbx - 6 - font.width(right));
                if (rightX + font.width(right) <= cbx - 3) g.text(font, right, rightX, y + 4, color, false);
            }
            y += ROW;
        }
        g.disableScissor();
        bar.update(x + w - 3, listTop, listH, total, listH);
        bar.draw(g, mouseX, mouseY);
    }

    @Override
    protected boolean contentClicked(double mx, double my, int button) {
        if (bar.mouseClicked(mx, my)) return true;
        for (int i = 0; i < removeRects.size(); i++) {
            int[] r = removeRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                alert.remove(removeNames.get(i));
                return true;
            }
        }
        for (int i = 0; i < nameRects.size(); i++) {
            int[] r = nameRects.get(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                copyOne(nameKeys.get(i));
                return true;
            }
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
        bar.scrollBy(-(int) Math.signum(scrollY) * 14);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
