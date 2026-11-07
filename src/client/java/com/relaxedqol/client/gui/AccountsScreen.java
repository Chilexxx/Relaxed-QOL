package com.relaxedqol.client.gui;

import com.relaxedqol.client.util.Accounts;
import com.relaxedqol.client.util.MsAuth;
import com.relaxedqol.client.util.SkinFaces;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Accounts window, in the same card look as the other windows (Watch List, Notes).
 *
 * <ul>
 *   <li>Top: your head, name and a status dot - green "Auto-renew on", yellow "Launcher session". Renew = a new
 *   session now.</li>
 *   <li>List: one card per saved account (In use badge, Switch / remove fade in under the mouse), and an
 *   "Add account" card at the end.</li>
 *   <li>Adding (always with a code): microsoft.com/link opens, the code is on your clipboard, type it there and
 * sign in - the window finishes by itself. it opens in its own sign-in window with an empty profile
 *   (like Lunar), so any account can sign in and your browser is never touched.</li>
 * </ul>
 * List and sign-in slide/fade into each other; hovers fade.
 */
public class AccountsScreen extends WindowScreen {
    private static final int ROW = 30;
    private final Anim anim = new Anim();
    private final ScrollBar bar = new ScrollBar();
    private final List<int[]> rects = new ArrayList<>();
    private final List<Object[]> actions = new ArrayList<>();
    private Accounts.Account confirmRemove;
    private long copiedAt;
    /** Opacity of what is being drawn right now (list / sign-in fade into each other). */
    private float alpha = 1f;

    public AccountsScreen(Screen parent) {
        super(parent, Component.literal("Accounts"), "accounts3", 300, 240);
    }

    public static void open(Screen parent) {
        net.minecraft.client.Minecraft.getInstance().gui.setScreen(new AccountsScreen(parent));
    }

    @Override
    protected String titleInfo() {
        int n = Accounts.all().size();
        return n == 1 ? "1 account" : n + " accounts";
    }

    @Override
    protected void initContent() {
        reserve(contentX(), contentY(), contentW() - 60, 28);
    }

    @Override
    public void onClose() {
        // Closing the window drops a sign-in that is still waiting (so the next Add starts clean)
        if (Accounts.loginActive()) Accounts.cancelLogin();
        super.onClose();
    }

    private int a(int color) {
        return alpha >= 1f ? color : Theme.fade(color, alpha);
    }

    // ================================================================== drawing

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        anim.tick();
        rects.clear();
        actions.clear();
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int h = contentH();

        alpha = 1f;
        drawHeader(g, x, y, w, mouseX, mouseY);
        g.fill(x, y + 33, x + w, y + 34, Theme.HAIRLINE);

        int top = y + 40;
        int bottom = y + h - 14;
        float t = anim.smooth("mode", Accounts.loginActive() ? 1f : 0f, 16f);
        if (t < 0.95f) {
            alpha = 1f - t;
            drawList(g, x - Math.round(12 * t), top, w, bottom - top, mouseX, mouseY, t < 0.5f);
        }
        if (t > 0.05f) {
            alpha = t;
            int sx = x + Math.round(12 * (1 - t));
            drawCode(g, sx, top, w, bottom - top, mouseX, mouseY, t > 0.5f);
        }
        alpha = 1f;

        // Status / error line at the bottom
        String err = Accounts.error();
        String msg = !err.isEmpty() ? err : Accounts.status();
        float show = anim.smooth("msg", msg.isEmpty() ? 0f : 1f, 14f);
        if (!msg.isEmpty() && show > 0.05f) {
            int c = err.isEmpty() ? Theme.TEXT_DIM : 0xFFFF7A72;
            int ly = bottom + 4;
            Render2D.roundRect(g, x + 1, ly + 2, 4, 4, 2, Theme.fade(err.isEmpty() ? Theme.accent() : Theme.DANGER, show));
            g.text(font, Theme.ellipsize(msg, w - 10, font::width), x + 9, ly, Theme.fade(c, show), false);
        } else if (msg.isEmpty() && Accounts.inWorld() && !Accounts.loginActive()) {
            g.text(font, Theme.ellipsize("Switching works on the title screen / server list", w, font::width), x, bottom + 4, Theme.TEXT_FAINT, false);
        }
    }

    /** Your head, name and session status. */
    private void drawHeader(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
        String me = Accounts.currentName();
        Render2D.roundRect(g, x, y + 2, 26, 26, 6, 0x1AFFFFFF);
        Render2D.roundOutline(g, x, y + 2, 26, 26, 6, 0x24FFFFFF);
        if (!SkinFaces.draw(g, me, x + 5, y + 7, 16, 0xFFFFFFFF)) g.fill(x + 5, y + 7, x + 21, y + 23, 0xFF3B3B4A);

        String renew = Accounts.busy() && !Accounts.loginActive() ? "Renewing" + dots() : "Renew";
        int bw = Math.max(font.width("Renewing...") + 14, 56);
        int bx = x + w - bw;
        int room = bx - 8 - (x + 33);
        g.text(font, Component.literal(Theme.ellipsize(me, room, font::width)).withStyle(ChatFormatting.BOLD), x + 33, y + 4, Theme.TEXT, false);

        boolean managed = Accounts.currentManaged();
        String left = Accounts.timeLeft(Accounts.currentExpiry());
        String line = managed
            ? "Auto-renew on" + (left.equals("unknown") ? "" : " · " + left + " left")
            : "Launcher session · add it below to auto-renew";
        int dot = managed ? Theme.SUCCESS : Theme.WARNING;
        Render2D.roundRect(g, x + 33, y + 18, 4, 4, 2, dot);
        g.text(font, Theme.ellipsize(line, room - 8, font::width), x + 41, y + 16, Theme.TEXT_FAINT, false);

        button(g, "renew", renew, bx, y + 8, bw, 14, false, mouseX, mouseY, true);
    }

    // ------------------------------------------------------------------ the list

    private void drawList(GuiGraphicsExtractor g, int x, int top, int w, int h, int mouseX, int mouseY, boolean clickable) {
        List<Accounts.Account> list = Accounts.all();
        UUID current = Accounts.currentId();
        boolean world = Accounts.inWorld();
        int gap = 4;
        int total = (list.size() + 1) * (ROW + gap);
        bar.update(x + w - 4, top, h, total, h);
        int cw = w - (total > h ? 8 : 0);
        g.enableScissor(x, top, x + w, top + h);
        int cy = top - bar.scroll;
        for (Accounts.Account acc : list) {
            if (cy + ROW >= top && cy <= top + h) drawAccount(g, acc, x, cy, cw, top, h, current, world, mouseX, mouseY, clickable);
            cy += ROW + gap;
        }
        // "Add account" card at the end
        if (cy + ROW >= top && cy <= top + h) {
            boolean over = clickable && mouseX >= x && mouseX < x + cw && mouseY >= Math.max(cy, top) && mouseY < Math.min(cy + ROW, top + h);
            float hv = anim.smooth("addRow", over ? 1f : 0f, 18f);
            Render2D.roundRect(g, x, cy, cw, ROW, 7, a(Theme.mix(0x08FFFFFF, Theme.withAlpha(Theme.accent(), 0.12f), hv)));
            Render2D.roundOutline(g, x, cy, cw, ROW, 7, a(Theme.mix(0x1FFFFFFF, Theme.withAlpha(Theme.accent(), 0.7f), hv)));
            Render2D.roundRect(g, x + 7, cy + 5, 20, 20, 10, a(Theme.withAlpha(Theme.accent(), 0.16f + 0.2f * hv)));
            Render2D.icon(g, "plus", x + 12, cy + 10, 10, a(Theme.mix(Theme.accent(), 0xFFFFFFFF, 0.3f * hv)));
            g.text(font, Component.literal("Add account").withStyle(ChatFormatting.BOLD), x + 34, cy + 6, a(Theme.TEXT), false);
            g.text(font, Theme.ellipsize("Sign in with a code on microsoft.com/link", cw - 42, font::width), x + 34, cy + 17, a(Theme.TEXT_FAINT), false);
            if (clickable) addRect(x, Math.max(cy, top), cw, Math.min(cy + ROW, top + h) - Math.max(cy, top), "add", null);
        }
        g.disableScissor();
        if (alpha > 0.5f) bar.draw(g, mouseX, mouseY);
    }

    private void drawAccount(GuiGraphicsExtractor g, Accounts.Account acc, int x, int cy, int cw, int top, int h, UUID current,
                             boolean world, int mouseX, int mouseY, boolean clickable) {
        boolean inUse = acc.id() != null && acc.id().equals(current);
        boolean over = clickable && mouseX >= x && mouseX < x + cw && mouseY >= Math.max(cy, top) && mouseY < Math.min(cy + ROW, top + h);
        float hv = anim.smooth(acc.uuid, over ? 1f : 0f, 18f);
        int fill = inUse ? Theme.withAlpha(Theme.accent(), 0.10f) : Theme.mix(0x0DFFFFFF, 0x18FFFFFF, hv);
        int edge = inUse ? Theme.withAlpha(Theme.accent(), 0.45f) : Theme.mix(0x1FFFFFFF, 0x30FFFFFF, hv);
        Render2D.roundRect(g, x, cy, cw, ROW, 7, a(fill));
        Render2D.roundOutline(g, x, cy, cw, ROW, 7, a(edge));
        Render2D.roundRect(g, x + 6, cy + 5, 20, 20, 5, a(0x1AFFFFFF));
        if (alpha > 0.6f && !SkinFaces.draw(g, acc.name, x + 8, cy + 7, 16, 0xFFFFFFFF)) g.fill(x + 8, cy + 7, x + 24, cy + 23, a(0xFF3B3B4A));

        // right side: badge / Switch chip, remove
        int right = x + cw - 7;
        if (inUse) {
            int bw = font.width("In use") + 12;
            right -= bw;
            Render2D.roundRect(g, right, cy + 9, bw, 12, 6, a(Theme.withAlpha(Theme.SUCCESS, 0.18f)));
            Render2D.roundOutline(g, right, cy + 9, bw, 12, 6, a(Theme.withAlpha(Theme.SUCCESS, 0.45f)));
            g.centeredText(font, "In use", right + bw / 2, cy + 11, a(Theme.mix(Theme.SUCCESS, 0xFFFFFFFF, 0.35f)));
        } else if (!world && hv > 0.05f) {
            String label = acc.needsLogin ? "Sign in" : "Switch";
            int bw = font.width(label) + 14;
            right -= bw;
            Render2D.roundRect(g, right, cy + 9, bw, 12, 6, a(Theme.withAlpha(Theme.accent(), 0.85f * hv)));
            g.centeredText(font, label, right + bw / 2, cy + 11, a(Theme.fade(0xFFFFFFFF, Math.max(0.06f, hv))));
        }
        boolean asking = confirmRemove != null && confirmRemove.uuid.equals(acc.uuid);
        int dx = right - 20;
        int dy = cy + 7;
        if (hv > 0.05f || asking) {
            boolean overDel = clickable && mouseX >= dx && mouseX < dx + 16 && mouseY >= dy && mouseY < dy + 16;
            float dh = anim.smooth("del" + acc.uuid, overDel || asking ? 1f : 0f, 20f);
            float vis = asking ? 1f : hv;
            Render2D.roundRect(g, dx, dy, 16, 16, 8, a(Theme.fade(asking ? Theme.DANGER : 0x40FF453A, Math.max(dh, asking ? 1f : 0f) * vis)));
            Render2D.icon(g, "trash", dx + 4, dy + 4, 8, a(Theme.fade(asking ? 0xFFFFFFFF : Theme.mix(Theme.TEXT_DIM, 0xFFFF6961, dh), Math.max(0.06f, vis))));
            if (clickable) addRect(dx, dy, 16, 16, "remove", acc);
        }

        int nx = x + 33;
        int room = dx - 6 - nx;
        g.text(font, Component.literal(Theme.ellipsize(acc.name, room, font::width)).withStyle(ChatFormatting.BOLD), nx, cy + 6, a(Theme.TEXT), false);
        String sub;
        int subColor;
        if (asking) {
            sub = "Click the bin again to remove";
            subColor = 0xFFFF7A72;
        } else if (acc.needsLogin) {
            sub = "Signed out - click to sign in again";
            subColor = 0xFFFF7A72;
        } else if (inUse) {
            sub = "Playing now";
            subColor = Theme.TEXT_FAINT;
        } else {
            sub = world ? "Leave the world to switch" : "Click to switch";
            subColor = Theme.TEXT_FAINT;
        }
        g.text(font, Theme.ellipsize(sub, room, font::width), nx, cy + 17, a(subColor), false);
        if (clickable) addRect(x, Math.max(cy, top), cw, Math.min(cy + ROW, top + h) - Math.max(cy, top), "switch", acc);
    }

    // ------------------------------------------------------------------ adding: code

    private void drawCode(GuiGraphicsExtractor g, int x, int top, int w, int h, int mouseX, int mouseY, boolean clickable) {
        int y = cardHeader(g, x, top, w, h, "Add an account", mouseX, mouseY, clickable);
        MsAuth.DeviceCode code = Accounts.deviceCode();
        int cx = x + w / 2;
        if (code == null) {
            pulse(g, cx - font.width(Accounts.loginStatus()) / 2 - 8, top + h / 2 - 6, true);
            g.centeredText(font, Accounts.loginStatus() + dots(), cx, top + h / 2 - 8, a(Theme.TEXT_DIM));
            return;
        }
        boolean expired = System.currentTimeMillis() > code.expiresAt();
        String lead = Accounts.separateWindow() ? "Paste this code in the sign-in window:" : "Paste this code on microsoft.com/link:";
        g.text(font, Theme.ellipsize(lead, w - 20, font::width), x + 10, y + 3, a(Theme.TEXT_SECONDARY), false);
        y += 22;

        // the code as key caps - click to copy
        String c = code.userCode();
        int cap = 14;
        int gapC = 3;
        int total = c.length() * cap + (c.length() - 1) * gapC;
        int kx = cx - total / 2;
        boolean over = clickable && mouseX >= kx - 4 && mouseX < kx + total + 4 && mouseY >= y - 2 && mouseY < y + 20;
        float hv = anim.smooth("codeHover", over ? 1f : 0f, 18f);
        boolean copied = System.currentTimeMillis() - copiedAt < 1500;
        for (int i = 0; i < c.length(); i++) {
            int px = kx + i * (cap + gapC);
            Render2D.roundRect(g, px, y, cap, 18, 4, a(Theme.mix(0x1AFFFFFF, 0x26FFFFFF, hv)));
            Render2D.roundOutline(g, px, y, cap, 18, 4, a(copied ? Theme.withAlpha(Theme.SUCCESS, 0.8f) : Theme.mix(0x30FFFFFF, Theme.withAlpha(Theme.accent(), 0.8f), hv)));
            g.fill(px + 2, y + 16, px + cap - 2, y + 17, a(0x40000000));
            g.centeredText(font, Component.literal(String.valueOf(c.charAt(i))).withStyle(ChatFormatting.BOLD), px + cap / 2, y + 5,
                a(expired ? Theme.TEXT_FAINT : 0xFFFFFFFF));
        }
        if (clickable) addRect(kx - 4, y - 2, total + 8, 22, "copy", null);
        y += 24;
        g.centeredText(font, copied ? "Copied ✓" : over ? "Click to copy" : "Already on your clipboard - just paste it", cx, y,
            a(copied ? Theme.SUCCESS : Theme.TEXT_FAINT));
        y += 16;

        if (expired) {
            g.text(font, "The code ran out", x + 10, y + 3, a(Theme.WARNING), false);
            int nw = font.width("New code") + 14;
            button(g, "newCode", "New code", x + w - 8 - nw, y, nw, 14, true, mouseX, mouseY, clickable);
        } else {
            String st = Accounts.loginStatus();
            pulse(g, x + 10, y + 3, !st.startsWith("Waiting"));
            g.text(font, Theme.ellipsize(st + dots(), w - 30, font::width), x + 18, y + 1, a(Theme.TEXT_DIM), false);
            int barW = w - 20;
            Render2D.roundRect(g, x + 10, y + 13, barW, 2, 1, a(0x1AFFFFFF));
            Render2D.roundRect(g, x + 10, y + 13, Math.max(2, Math.round(barW * Accounts.codeTimeLeft())), 2, 1, a(Theme.withAlpha(Theme.accent(), 0.8f)));
        }
        drawPickFooter(g, x, top, w, h, mouseX, mouseY, clickable);
    }

    /** Footer: the sign-in runs in its own window - your browser is not touched. */
    private void drawPickFooter(GuiGraphicsExtractor g, int x, int top, int w, int h, int mouseX, int mouseY, boolean clickable) {
        int fy = top + h - 20;
        g.fill(x + 8, fy - 5, x + w - 8, fy - 4, a(0x14FFFFFF));
        boolean own = Accounts.separateWindow();
        String label = "Reopen window ↗";
        int bw = font.width(label) + 14;
        button(g, "reopen", label, x + w - 8 - bw, fy, bw, 14, false, mouseX, mouseY, clickable);
        int room = w - 20 - bw - 6;
        String line1 = own ? "Own sign-in window - any account" : "Opened in your browser";
        String line2 = own ? "Your browser is not touched" : "Uses the account your browser has";
        Render2D.roundRect(g, x + 10, fy + 1, 4, 4, 2, a(own ? Theme.SUCCESS : Theme.WARNING));
        g.text(font, Theme.ellipsize(line1, room - 8, font::width), x + 18, fy - 1, a(Theme.TEXT_SECONDARY), false);
        g.text(font, Theme.ellipsize(line2, room - 8, font::width), x + 18, fy + 9, a(Theme.TEXT_FAINT), false);
    }

    // ------------------------------------------------------------------ pieces

    /** The sign-in card with its title row (accent dot, title, Cancel); returns where its content starts. */
    private int cardHeader(GuiGraphicsExtractor g, int x, int top, int w, int h, String title, int mouseX, int mouseY, boolean clickable) {
        Render2D.roundRect(g, x, top, w, h, 7, a(0x12FFFFFF));
        Render2D.roundOutline(g, x, top, w, h, 7, a(0x1FFFFFFF));
        Render2D.roundRect(g, x + 8, top + 7, 4, 4, 2, a(Theme.accent()));
        g.text(font, title, x + 17, top + 5, a(0xFFE4E4EC), false);
        int cw = font.width("Cancel") + 12;
        button(g, "cancel", "Cancel", x + w - 6 - cw, top + 3, cw, 12, false, mouseX, mouseY, clickable);
        g.fill(x + 8, top + 18, x + w - 8, top + 19, a(0x14FFFFFF));
        return top + 26;
    }

    /** A small dot that breathes while something is going on. */
    private void pulse(GuiGraphicsExtractor g, int x, int y, boolean busy) {
        float p = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / (busy ? 160.0 : 380.0)));
        int col = busy ? Theme.accent() : Theme.TEXT_DIM;
        Render2D.roundRect(g, x - 1, y - 1, 6, 6, 3, a(Theme.withAlpha(col, 0.15f + 0.2f * p)));
        Render2D.roundRect(g, x, y, 4, 4, 2, a(Theme.withAlpha(col, 0.55f + 0.45f * p)));
    }

    /** A pill button with a fading hover, like the rest of the client. */
    private void button(GuiGraphicsExtractor g, String id, String label, int bx, int by, int bw, int bh, boolean primary,
                        int mouseX, int mouseY, boolean clickable) {
        boolean over = clickable && mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
        float hv = anim.smooth("btn" + id, over ? 1f : 0f, 18f);
        if (primary) {
            Render2D.roundRect(g, bx, by, bw, bh, Theme.R_CONTROL, a(Theme.withAlpha(Theme.accent(), 0.7f + 0.25f * hv)));
        } else {
            Render2D.roundRect(g, bx, by, bw, bh, Theme.R_CONTROL, a(Theme.mix(Theme.PILL, 0x22FFFFFF, hv)));
            Render2D.roundOutline(g, bx, by, bw, bh, Theme.R_CONTROL, a(Theme.mix(Theme.BORDER, 0x4DFFFFFF, hv)));
        }
        int tc = primary ? 0xFFFFFFFF : Theme.mix(Theme.TEXT_SECONDARY, Theme.TEXT, hv);
        g.centeredText(font, label, bx + bw / 2, by + (bh - 8) / 2, a(tc));
        if (clickable) addRect(bx, by, bw, bh, id, null);
    }

    private void addRect(int x, int y, int w, int h, String id, Accounts.Account acc) {
        if (h <= 0) return;
        rects.add(new int[]{x, y, w, h});
        actions.add(new Object[]{id, acc});
    }

    private static String dots() {
        return ".".repeat((int) (System.currentTimeMillis() / 400 % 4));
    }

    // ================================================================== input

    @Override
    protected boolean contentClicked(double mx, double my, int button) {
        if (!Accounts.loginActive() && bar.mouseClicked(mx, my)) return true;
        if (button != 0) return false;
        // Smaller things (buttons, bin) were added before the card under them: the first hit wins
        for (int i = 0; i < rects.size(); i++) {
            int[] r = rects.get(i);
            if (mx < r[0] || mx >= r[0] + r[2] || my < r[1] || my >= r[1] + r[3]) continue;
            String what = (String) actions.get(i)[0];
            Accounts.Account acc = (Accounts.Account) actions.get(i)[1];
            if (!what.equals("remove")) confirmRemove = null;
            switch (what) {
                case "renew" -> Accounts.refreshCurrent(true);
                case "add" -> Accounts.startLogin();
                case "cancel" -> Accounts.cancelLogin();
                case "open" -> Accounts.reopenWindow();
                case "newCode" -> Accounts.startLogin();
                case "reopen" -> Accounts.reopenWindow();
                case "copy" -> {
                    Accounts.copyCode();
                    copiedAt = System.currentTimeMillis();
                }
                case "remove" -> {
                    if (confirmRemove != null && confirmRemove.uuid.equals(acc.uuid)) {
                        Accounts.remove(acc);
                        confirmRemove = null;
                    } else {
                        confirmRemove = acc;
                    }
                }
                case "switch" -> {
                    if (acc.needsLogin) Accounts.startLogin();
                    else Accounts.switchTo(acc);
                }
                default -> {
                }
            }
            return true;
        }
        confirmRemove = null;
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
        if (!Accounts.loginActive()) bar.scrollBy(-(int) Math.signum(scrollY) * 20);
        return true;
    }
}
