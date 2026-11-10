package com.relaxedqol.client.gui;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.modules.lovercraft.LoverFellaMode;
import com.relaxedqol.client.util.SkinFaces;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * LoverFella Mode: the LoverCraft look for the whole client. Brand colours (navy, heart red, mustache cyan),
 * the heart + mustache logo, the LC logo, hearts drifting up behind the menu and bobble heads of the team. All of
 * it is drawn from the time (no state to tick), so it is smooth at any frame rate and costs next to nothing.
 */
public final class LoverFx {
    private static final String NS = "relaxed-qol";
    public static final int RED = 0xFFF0284F;
    public static final int CYAN = 0xFF19E3F2;
    public static final int NAVY = 0xFF0C1A3C;
    public static final int NAVY_LIGHT = 0xFF14285A;
    private static final Identifier HEART_LOGO = Identifier.fromNamespaceAndPath(NS, "textures/gui/lovercraft/heart_logo.png");

    private static LoverFellaMode module;

    private LoverFx() {}

    private static LoverFellaMode module() {
        if (module == null && RelaxedQolClient.moduleManager != null) {
            module = RelaxedQolClient.moduleManager.getModule(LoverFellaMode.class);
        }
        return module;
    }

    /** Whether LoverFella Mode itself is on (whatever part of the client is being drawn). */
    public static boolean modeOn() {
        LoverFellaMode m = module();
        return m != null && m.isEnabled() && !com.relaxedqol.client.util.Privacy.hides(m);
    }

    /**
     * The HUD element being drawn right now ("Watermark", "Info Panel", "Module List", a card module's name...),
     * or null while a menu / window is drawn. Every part of the client has its own LoverFella switch.
     */
    private static String scope;

    /** Starts drawing a HUD element; returns the previous scope, to hand back to {@link #endScope}. */
    public static String beginScope(String element) {
        String prev = scope;
        scope = element;
        return prev;
    }

    public static void endScope(String prev) {
        scope = prev;
    }

    /**
     * Whether the LoverCraft look applies to what is drawn right now: the mode is on and the switch for this part
     * (this HUD element, the client windows, or the client menu) is on.
     */
    public static boolean on() {
        if (!modeOn()) return false;
        LoverFellaMode m = module();
        if (scope != null) return m.hudLit(scope);
        net.minecraft.client.gui.screens.Screen open = net.minecraft.client.Minecraft.getInstance().gui.screen();
        // No screen open: only the HUD is being drawn (a part without its own switch)
        if (open == null) return m.hudLit("Other");
        return open instanceof WindowScreen ? m.clientWindows() : m.clientMenu();
    }

    /** The LoverCraft logo instead of the normal watermark in the top-left corner. */
    public static boolean watermarkOn() {
        return modeOn() && module().hudLogo();
    }

    /** Title screen / server list / Esc menu decorations (and only while the client's menu look is used there). */
    private static boolean minecraftMenusOn(LoverFellaMode m) {
        return m != null && m.isEnabled() && m.minecraftMenus()
            && com.relaxedqol.client.module.modules.client.ClickGUI.customButtonsSwitch();
    }

    public static boolean chatTag() {
        LoverFellaMode m = module();
        return m != null && m.isEnabled() && m.chatTag();
    }

    private static double t() {
        return (System.currentTimeMillis() % 3_600_000L) / 1000.0;
    }

    /** The accent: slowly shimmers between heart red and mustache cyan; offset shifts it down lists / across panels. */
    public static int accent(int offset) {
        double k = 0.5 + 0.5 * Math.sin(t() * 0.9 + offset * 0.55);
        // ease towards the two brand colours (less time on the purple in between)
        k = k * k * (3 - 2 * k);
        return Theme.mix(RED, CYAN, (float) k);
    }

    /** A whole texture drawn at (x, y), w x h. */
    private static void image(GuiGraphicsExtractor g, Identifier tex, int x, int y, int w, int h, int color) {
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, w, h, w, h, color);
    }

    // ------------------------------------------------------------------ HUD watermark

    /** The HUD watermark in LoverCraft style. Returns its height. */
    public static int drawWatermark(GuiGraphicsExtractor g, Font font, int y) {
        LoverFellaMode m = module();
        boolean logo = m == null || m.logos();
        Component lover = Component.literal("Lᴏᴠᴇʀ").withStyle(ChatFormatting.BOLD);
        Component craft = Component.literal("Cʀᴀꜰᴛ").withStyle(ChatFormatting.BOLD);
        String heart = " ♥";
        int iconW = logo ? 18 : 0;
        int w = 8 + iconW + font.width(lover) + font.width(craft) + font.width(heart) + 8;
        int h = 18;
        Render2D.shadow(g, 4, y, w, h, 8);
        Render2D.roundRect(g, 4, y, w, h, 8, Theme.withAlpha(NAVY, 0.86f));
        Render2D.roundOutline(g, 4, y, w, h, 8, Theme.withAlpha(accent(0), 0.55f));
        int x = 10;
        if (logo) {
            // The heart gently beats
            double beat = Math.max(0, Math.sin(t() * 6.0)) * Math.max(0, Math.sin(t() * 1.5));
            int s = 14 + (int) Math.round(beat * 2);
            image(g, HEART_LOGO, x + 1 - (s - 14) / 2, y + 2 - (s - 14) / 2, s, s, 0xFFFFFFFF);
            x += iconW;
        }
        g.text(font, lover, x, y + 5, RED, false);
        x += font.width(lover);
        g.text(font, craft, x, y + 5, CYAN, false);
        x += font.width(craft);
        g.text(font, heart, x, y + 5, accent(3), false);
        return y + h;
    }

    // ------------------------------------------------------------------ HUD info panel

    /** Whether the HUD info panel is drawn in LoverCraft style. */
    public static boolean infoPanelOn() {
        LoverFellaMode m = module();
        return m != null && m.isEnabled() && m.infoPanel();
    }

    /**
     * The HUD info panel in LoverCraft style: a navy card with a red-to-cyan edge, a little heart in front of every
     * line (red and cyan in turn, softly pulsing), soft stripes, and LoverFella peeking over its top-right edge.
     * lines = {label, value}. Returns the bottom.
     */
    public static int drawInfoPanel(GuiGraphicsExtractor g, Font font, int y, List<String[]> lines, int labelW, int valueW) {
        double t = t();
        int x = 4;
        int rowH = 10;
        int w = 18 + labelW + 8 + valueW + 9;
        int h = 6 + lines.size() * rowH + 3;
        LoverFellaMode m = module();
        String peeker = m != null ? m.scoreboardName() : "LoverFella";
        // LoverFella peeking from behind the card (drawn first, so the card covers his chin)
        int faceX = x + w - 22;
        int bob = (int) Math.round(Math.sin(t * 1.3) * 1.2);
        int faceY = y - 8 + bob;
        boolean peek = y >= 10;
        if (peek) {
            if (!SkinFaces.draw(g, peeker, faceX, faceY, 10, 0xFFFFFFFF)) g.fill(faceX, faceY, faceX + 10, faceY + 10, 0xFF2A3350);
        }
        Render2D.shadow(g, x, y, w, h, 8);
        Render2D.roundRect(g, x, y, w, h, 8, Theme.withAlpha(NAVY, 0.88f));
        Render2D.roundOutline(g, x, y, w, h, 8, Theme.withAlpha(accent(1), 0.5f));
        int ly = y + 5;
        for (int i = 0; i < lines.size(); i++) {
            String[] line = lines.get(i);
            if (i % 2 == 1) g.fill(x + 7, ly - 1, x + w - 4, ly + rowH - 1, 0x0EFFFFFF);
            float pulse = 0.65f + 0.35f * (float) Math.sin(t * 2.2 + i * 0.9);
            // The hearts follow the red-to-cyan edge smoothly instead of switching colour row by row
            float along = lines.size() <= 1 ? 0f : i / (float) (lines.size() - 1);
            Render2D.icon(g, "heart_soft", x + 8, ly + 1, 7, Theme.withAlpha(Theme.mix(RED, CYAN, along), pulse));
            g.text(font, line[0], x + 18, ly, 0xFFAFC2EA, false);
            g.text(font, line[1], x + 18 + labelW + 8, ly, 0xFFFFFFFF, false);
            ly += rowH;
        }
        // His hands on the edge of the card
        if (peek) {
            SkinFaces.Skin skin = SkinFaces.skin(peeker);
            int handY = y - 1;
            if (skin != null) {
                SkinFaces.part(g, skin, 44, 29, 3, 3, faceX - 2, handY, 1, 0xFFFFFFFF);
                SkinFaces.part(g, skin, 44, 29, 3, 3, faceX + 9, handY, 1, 0xFFFFFFFF);
            } else {
                g.fill(faceX - 2, handY, faceX + 1, handY + 3, 0xFFE2B48C);
                g.fill(faceX + 9, handY, faceX + 12, handY + 3, 0xFFE2B48C);
            }
        }
        return y + h;
    }

    // ------------------------------------------------------------------ Esc menu, title screen, server list

    /** Behind the buttons of the pause menu / title screen / server list: a navy wash and drifting hearts. */
    public static void menuBackground(net.minecraft.client.gui.screens.Screen screen, GuiGraphicsExtractor g) {
        LoverFellaMode m = module();
        if (!minecraftMenusOn(m)) return;
        int vw = screen.width;
        int vh = screen.height;
        // Calm and clean - a soft navy tint that deepens towards the edges, a few faint hearts, one hairline
        g.fill(0, 0, vw, vh, 0x220A1636);
        g.fillGradient(0, 0, vw, Math.min(70, vh / 4), 0x500A1636, 0x000A1636);
        g.fillGradient(0, vh - Math.min(90, vh / 3), vw, vh, 0x000A1636, 0x600A1636);
        if (m.hearts()) drawCalmHearts(g, vw, vh, t());
    }

    /** Fewer, slower, fainter hearts than in the client menu: a hint of LoverCraft, not a sticker sheet. */
    private static void drawCalmHearts(GuiGraphicsExtractor g, int vw, int vh, double t) {
        int n = 9;
        for (int i = 0; i < n; i++) {
            double seed = Math.abs(Math.sin(i * 12.9898 + 3.1) * 43758.5453) % 1.0;
            double speed = 5 + seed * 6;
            double span = vh + 40;
            double y = vh + 20 - ((t * speed + seed * span * 3) % span);
            double x = (i + 0.5) / n * vw + Math.sin(t * 0.4 + i * 1.7) * 18;
            int size = 5 + (int) Math.round(seed * 5);
            float fade = (float) Math.max(0, Math.min(1.0, Math.min((y + 20) / (vh * 0.5), (vh + 20 - y) / 60.0)));
            if (fade <= 0.02f) continue;
            int color = Theme.withAlpha(i % 2 == 0 ? RED : CYAN, (0.07f + (float) seed * 0.09f) * fade);
            Render2D.icon(g, "heart_soft", (int) Math.round(x) - size / 2, (int) Math.round(y) - size / 2, size, color);
        }
    }

    /**
     * On top of the pause menu / title screen: LoverFella and his brothers sitting on the top button, at its ends -
     * never on another button, a list or a label - and a soft red-to-cyan underline on the button under the
     * mouse. No badge: the menus stay clean.
     */
    public static void menuForeground(net.minecraft.client.gui.screens.Screen screen, GuiGraphicsExtractor g, int mx, int my) {
        LoverFellaMode m = module();
        if (!minecraftMenusOn(m)) return;
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        // The Esc menu has its own, smaller figures spread over several buttons
        if (screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
            pauseForeground(screen, g, font, m, mx, my);
            return;
        }
        List<int[]> perches = new java.util.ArrayList<>();
        List<int[]> blocked = new java.util.ArrayList<>();
        List<net.minecraft.client.gui.components.AbstractWidget> widgets = net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen);
        for (net.minecraft.client.gui.components.AbstractWidget w : widgets) {
            if (!w.visible) continue;
            boolean seatable = w instanceof net.minecraft.client.gui.components.Button && w.getWidth() >= 90 && w.getY() >= 34;
            // Anything that is not a seat must stay clear (its own seat is checked below)
            if (!seatable) blocked.add(new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()});
            if (!(w instanceof net.minecraft.client.gui.components.Button)) continue;
            if (w.isHovered() && w.active) {
                int lx = w.getX() + 4;
                int lw = w.getWidth() - 8;
            }
            if (!seatable) continue;
            // Never over the button's own label: only its two ends are seats
            int tw = font.width(w.getMessage()) + 16;
            blocked.add(new int[]{w.getX() + (w.getWidth() - tw) / 2, w.getY() - 30, tw, w.getHeight() + 30});
            perches.add(new int[]{w.getX(), w.getY(), w.getWidth()});
        }
        // Keep the screen's title free too
        blocked.add(new int[]{screen.width / 2 - font.width(screen.getTitle()) / 2 - 8, 0, font.width(screen.getTitle()) + 16, 30});
        if (m.sitters() && !perches.isEmpty()) {
            // A seat counts only when nothing at all is in the 26 px above it (another button, a list, a text box)
            List<int[]> free = new java.util.ArrayList<>();
            for (int[] p : perches) {
                boolean clear = true;
                for (net.minecraft.client.gui.components.AbstractWidget w : widgets) {
                    if (!w.visible || (w.getX() == p[0] && w.getY() == p[1])) continue;
                    if (w.getX() < p[0] + p[2] && w.getX() + w.getWidth() > p[0] && w.getY() < p[1] && w.getY() + w.getHeight() > p[1] - 26) clear = false;
                }
                if (clear) free.add(p);
            }
            drawSitters(g, free, blocked, mx, my);
        }
    }

    /**
     * The Esc menu. LoverFella, Ben, Isaac... (the Sitters list) sit on the ends of several buttons, a little
     * smaller than on the title screen. A seat is only used when the figure stays clear of every button label (and of
     * the screen title) - at most it covers a corner of the button above - and no two figures share a button.
     */
    private static void pauseForeground(net.minecraft.client.gui.screens.Screen screen, GuiGraphicsExtractor g, Font font,
                                        LoverFellaMode m, int mx, int my) {
        List<net.minecraft.client.gui.components.AbstractWidget> widgets = net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen);
        List<int[]> labels = new java.util.ArrayList<>();
        List<net.minecraft.client.gui.components.AbstractWidget> buttons = new java.util.ArrayList<>();
        for (net.minecraft.client.gui.components.AbstractWidget w : widgets) {
            if (!w.visible) continue;
            if (w instanceof net.minecraft.client.gui.components.Button) {
                if (w.isHovered() && w.active) {
                    int lx = w.getX() + 4;
                    int lw = w.getWidth() - 8;
                }
                if (w.getWidth() >= 40 && w.getY() >= 30) buttons.add(w);
            }
            // The label of every widget (its text, with a little room) must stay visible
            int tw = Math.min(w.getWidth(), font.width(w.getMessage()) + 10);
            labels.add(new int[]{w.getX() + (w.getWidth() - tw) / 2, w.getY() + 2, tw, Math.max(1, w.getHeight() - 4)});
        }
        // The screen title
        int titleW = font.width(screen.getTitle()) + 16;
        labels.add(new int[]{(screen.width - titleW) / 2, 0, titleW, 52});
        if (!m.sitters() || buttons.isEmpty()) return;
        List<String> names = m.sitterNames();
        if (names.isEmpty()) return;

        // Figure scale: about 3/4 size, at a whole number of screen pixels per skin pixel (sharp, even pixels)
        int gs = Math.max(1, net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale());
        final float s = Math.max(1, Math.round(gs * 0.75f)) / (float) gs;
        final int halfW = (int) Math.ceil(9 * s);   // half the figure's width at that scale
        final int up = (int) Math.ceil(23 * s);     // how far it reaches above the seat
        final int inset = (int) Math.ceil(15 * s);  // seat distance from the button's end
        // Every possible seat: the left and right end of each button, top to bottom
        List<int[]> seats = new java.util.ArrayList<>(); // {cx, seatY, buttonIndex}
        buttons.sort((a, b) -> a.getY() != b.getY() ? Integer.compare(a.getY(), b.getY()) : Integer.compare(a.getX(), b.getX()));
        for (int i = 0; i < buttons.size(); i++) {
            net.minecraft.client.gui.components.AbstractWidget b = buttons.get(i);
            for (int side = 0; side < 2; side++) {
                int cx = side == 0 ? b.getX() + inset : b.getX() + b.getWidth() - inset;
                int top = b.getY();
                boolean clear = true;
                for (int[] r : labels) {
                    if (cx - halfW < r[0] + r[2] && cx + halfW > r[0] && top - up < r[1] + r[3] && top + 6 > r[1]) {
                        clear = false;
                        break;
                    }
                }
                if (clear) seats.add(new int[]{cx, top, i});
            }
        }
        if (seats.isEmpty()) return;
        // Spread the figures over the seats, one per button, alternating sides so it does not look like a column
        int want = Math.min(names.size(), Math.min(4, buttons.size()));
        java.util.Set<Integer> usedButtons = new java.util.HashSet<>();
        List<int[]> picked = new java.util.ArrayList<>();
        double step = (double) seats.size() / want;
        for (int n = 0; n < want; n++) {
            int start = (int) Math.floor(n * step + step / 2);
            for (int k = 0; k < seats.size(); k++) {
                int[] seat = seats.get((start + k) % seats.size());
                if (usedButtons.contains(seat[2])) continue;
                usedButtons.add(seat[2]);
                picked.add(seat);
                break;
            }
        }
        double t = t();
        for (int n = 0; n < picked.size(); n++) {
            int[] seat = picked.get(n);
            String name = names.get(n);
            double phase = Math.floorMod(name.toLowerCase(java.util.Locale.ROOT).hashCode() >> 6, 628) / 100.0;
            boolean near = Math.abs(mx - seat[0]) < halfW + 2 && my > seat[1] - up && my < seat[1] + 6;
            scaledSitter(g, name, seat[0], seat[1], s, t, phase, near);
        }
    }

    // ------------------------------------------------------------------ PlayerAlert's list

    public static boolean playerAlertOn() {
        LoverFellaMode m = module();
        return m != null && m.isEnabled() && m.alertStyle();
    }

    /** One row of PlayerAlert's list: the account name to show, online or not, and what is on the right. */
    public record AlertRow(String name, boolean online, String right) {}

    /**
     * PlayerAlert's HUD list in LoverCraft style: a navy card with a red-to-cyan edge, a heart that beats while anyone
     * you watch is online, faces, online players on a soft cyan wash with their zone in cyan, offline ones quiet.
     */
    public static void drawPlayerAlert(GuiGraphicsExtractor g, Font font, int x, int y, List<AlertRow> rows, int online, int listed) {
        LoverFellaMode m = module();
        boolean faces = m == null || m.alertFaces();
        double t = t();
        int pad = 7;
        int headerH = 18;
        int rowH = 13;
        Component title = Component.literal("Watch List").withStyle(ChatFormatting.BOLD);
        String count = online + "/" + listed;
        int w = pad + 12 + font.width(title) + 12 + font.width(count) + 10 + pad;
        for (AlertRow r : rows) w = Math.max(w, pad + (faces ? 13 : 7) + font.width(r.name()) + 12 + font.width(r.right()) + pad);
        w = Math.max(w, 118);
        int h = rows.isEmpty() ? headerH : headerH + 2 + rows.size() * rowH + 4;
        boolean any = online > 0;
        Render2D.shadow(g, x, y, w, h, 8);
        Render2D.roundRect(g, x, y, w, h, 8, Theme.withAlpha(NAVY, 0.9f));
        g.fillGradient(x + 1, y + 1, x + w - 1, y + headerH, 0x14FFFFFF, 0x00FFFFFF);
        Render2D.roundOutline(g, x, y, w, h, 8, any ? Theme.withAlpha(CYAN, 0.55f) : 0x26FFFFFF);
        // Header: a heart that beats while someone is online, the title, the count in a red / cyan pill
        double beat = any ? Math.max(0, Math.sin(t * 6.0)) * Math.max(0, Math.sin(t * 1.5)) : 0;
        int hs = 8 + (int) Math.round(beat * 2);
        Render2D.icon(g, "heart_soft", x + pad + 1 - (hs - 8) / 2, y + 5 - (hs - 8) / 2, hs, any ? RED : Theme.withAlpha(RED, 0.45f));
        g.text(font, title, x + pad + 12, y + 5, any ? 0xFFFFFFFF : 0xFFAFC2EA, false);
        int pw = font.width(count) + 14 + Math.round(count.length() * 0.6f); // The count always fits inside
        int px = x + w - pad - pw;
        int ph = Math.max(14, font.lineHeight + 5); // Tall enough for the digits
        int py = y + (headerH - ph) / 2;
        Render2D.roundRect(g, px, py, pw, ph, 6, any ? Theme.withAlpha(CYAN, 0.25f) : 0x20FFFFFF);
        Render2D.roundOutline(g, px, py, pw, ph, 6, any ? Theme.withAlpha(CYAN, 0.8f) : 0x30FFFFFF);
        g.text(font, count, px + (pw - font.width(count) + 1) / 2, py + (ph - font.lineHeight + 1) / 2, any ? 0xFFFFFFFF : 0xFF8FA3C9, false);
        if (!rows.isEmpty()) g.fill(x + pad, y + headerH, x + w - pad, y + headerH + 1, 0x1CFFFFFF);
        int ry = y + headerH + 3;
        for (int i = 0; i < rows.size(); i++) {
            AlertRow r = rows.get(i);
            if (r.online()) Render2D.roundRect(g, x + pad - 2, ry, w - pad * 2 + 4, rowH - 1, 4, Theme.withAlpha(CYAN, 0.09f));
            int nx = x + pad + 2;
            if (faces) {
                if (!SkinFaces.draw(g, r.name(), nx, ry + 2, 8, r.online() ? 0xFFFFFFFF : 0x80FFFFFF)) {
                    Render2D.roundRect(g, nx, ry + 2, 8, 8, 2, 0x30FFFFFF);
                }
                if (r.online()) {
                    float pulse = 0.6f + 0.4f * (float) Math.sin(t * 3.0 + i);
                    Render2D.roundRect(g, nx + 6, ry + 7, 3, 3, 1, Theme.withAlpha(0xFF4ADE80, pulse));
                }
                nx += 12;
            } else {
                Render2D.roundRect(g, nx, ry + 4, 4, 4, 2, r.online() ? CYAN : 0x50FFFFFF);
                nx += 7;
            }
            g.text(font, r.name(), nx, ry + 2, r.online() ? 0xFFFFFFFF : 0xFF6F7FA3, false);
            g.text(font, r.right(), x + w - pad - font.width(r.right()), ry + 2, r.online() ? CYAN : 0xFF56658A, false);
            ry += rowH;
        }
    }

    // ------------------------------------------------------------------ on the scoreboard

    public static boolean scoreboardSitterOn() {
        LoverFellaMode m = module();
        return m != null && m.isEnabled() && m.scoreboardSitter();
    }

    /**
     * LoverFella and his brothers IN the letters of the scoreboard's title (box = {x, y, w, h} of the sidebar):
     * one sits on the foot of the "L" (legs dangling), one peeks out of the opening of the "C", one sits on the last
     * letter. Small, so the server's name stays readable. When the title has no plain L / C (a picture font), the
     * spots fall where those letters usually are.
     */
    public static void drawScoreboardSitter(GuiGraphicsExtractor g, int[] box) {
        LoverFellaMode m = module();
        if (m == null || box[1] < 12) return;
        Component title = com.relaxedqol.client.util.TabInfo.sidebarTitle();
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        double t = t();
        String[] who = m.titleSitters();
        // Where the title's letters really are. Two kinds of server titles:
        // - letters in a big font: each letter is measured where the game puts it;
        // - one picture of the whole word (often followed by "move back" spacing, so the game thinks the title is
        //   narrow): the picture is the widest character, and its letters are spread evenly over it.
        List<int[]> glyphs = title != null ? glyphs(font, title) : List.of();
        int titleW = title != null ? font.width(title) : Math.max(20, box[2] - 8);
        int inner = box[2] - 4;
        int tx = box[0] + 2 + inner / 2 - titleW / 2;
        int baseline = box[1] + 1 + 8;
        int sum = 0;
        int[] widest = null;
        for (int[] gl : glyphs) {
            sum += gl[1];
            if (widest == null || gl[1] > widest[1]) widest = gl;
        }
        boolean picture = widest != null && glyphs.size() <= 3 && widest[1] >= 30;
        float[] l;
        float[] c;
        float[] last;
        float letterW;
        if (picture || glyphs.isEmpty()) {
            float px0 = picture ? widest[0] : 0;
            float pw = picture ? widest[1] - 1 : titleW;
            int n = Math.max(4, Math.min(16, title != null && !picture ? title.getString().trim().length() : 10));
            letterW = pw / n;
            l = new float[]{px0, letterW};
            c = new float[]{px0 + letterW * Math.round(n * 0.5f), letterW};
            last = new float[]{px0 + pw - letterW, letterW};
        } else {
            int[] fl = find(glyphs, 'L', 'ʟ');
            int[] fc = find(glyphs, 'C', 'ᴄ');
            int[] fe = glyphs.get(glyphs.size() - 1);
            float avg = sum / (float) glyphs.size();
            letterW = Math.max(3f, avg - Math.max(1f, avg / 6f));
            l = fl != null ? new float[]{fl[0], fl[1] - Math.max(1f, fl[1] / 6f)} : new float[]{glyphs.get(0)[0], letterW};
            int[] mid = glyphs.get(Math.min(glyphs.size() - 1, Math.round(glyphs.size() * 0.5f)));
            c = fc != null ? new float[]{fc[0], fc[1] - Math.max(1f, fc[1] / 6f)} : new float[]{mid[0], letterW};
            last = new float[]{fe[0], fe[1] - Math.max(1f, fe[1] / 6f)};
        }
        // Letters are about 1.3 times as tall as wide; a big font sits a little above the line's baseline
        float letterH = Math.max(7f, letterW * 1.3f);
        float bottom = baseline - Math.max(0f, (letterH - 7f) * 0.19f);
        float top = bottom - letterH;
        float k = Math.max(1f, letterH / 7f);
        // A sitter is 20 px from seat to the top of the head: a bit smaller than a letter
        // Smaller - the seat-to-head part is about half a letter, so the letters stay readable
        float s = Math.max(0.22f, Math.min(0.5f, letterH * 0.5f / 20f));
        float stroke = Math.max(1f, Math.round(k));
        g.nextStratum(); // in front of the title's letters
        // 1) LoverFella sits in the corner of the L, on its foot, legs over the front
        if (who[0] != null) {
            float glyphW = l[1];
            float cx = tx + l[0] + stroke + (glyphW - stroke) * 0.58f + 0.5f;
            scaledSitter(g, who[0], cx, bottom - stroke, s, t, 0.3, (t % 11.0) < 1.4);
        }
        // 2) Isaac sits inside the C, on its bottom stroke, legs over the front
        if (who[1] != null) {
            float glyphW = c[1];
            float cx = tx + c[0] + stroke + (glyphW - stroke) * 0.48f;
            scaledSitter(g, who[1], cx, bottom - stroke, s, t, 1.3, (t % 9.0) < 1.2);
        }
        // 3) Ben sits on top of the last letter, legs over its front
        if (who[2] != null) {
            float cx = tx + last[0] + last[1] / 2f;
            scaledSitter(g, who[2], cx, top + 0.5f, s, t, 2.4, (t % 13.0) > 11.6);
        }
        // Now and then a small heart floats up out of the C
        double p = (t % 5.0) / 5.0;
        if (p < 0.7) {
            int hx = Math.round(tx + c[0] + c[1] / 2f + (float) Math.sin(t * 2.0) * 1.5f);
            int hy = Math.round(top - 2 - (float) p * 12);
            Render2D.icon(g, "heart_soft", hx, hy, 4, Theme.withAlpha(RED, (float) (0.85 * (1 - p / 0.7))));
        }
    }

    /** A sitting figure drawn small (scale s) with its seat at (cx, seat). */
    private static void scaledSitter(GuiGraphicsExtractor g, String name, float cx, float seat, float s, double t, double phase, boolean wave) {
        g.pose().pushMatrix();
        g.pose().translate(cx, seat);
        g.pose().scale(s, s);
        drawSitter(g, name, 0, 0, phase, t, wave, false, -200);
        g.pose().popMatrix();
    }

    /** Every visible character of the title: {x from the title's left, advance width, the character}. */
    private static List<int[]> glyphs(Font font, Component title) {
        List<int[]> out = new ArrayList<>();
        int[] x = {0};
        title.visit((style, text) -> {
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                String ch = new String(Character.toChars(cp));
                int w = font.width(Component.literal(ch).withStyle(style));
                if (!Character.isWhitespace(cp) && w > 0) out.add(new int[]{x[0], w, cp});
                x[0] += w;
                i += Character.charCount(cp);
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return out;
    }

    private static int[] find(List<int[]> glyphs, char upper, char small) {
        for (int[] gl : glyphs) {
            if (gl[2] == upper || gl[2] == small) return gl;
        }
        return null;
    }

    // ------------------------------------------------------------------ menu backdrop

    /** Behind the menu panels (virtual menu coordinates): navy wash, drifting hearts, the LC logo and the bobble heads. */
    public static void drawMenuBackdrop(GuiGraphicsExtractor g, Font font, int vw, int vh, int mx, int my) {
        drawMenuBackdrop(g, font, vw, vh, mx, my, List.of());
    }

    /** avoid = rectangles {x, y, w, h} (menu coordinates) the logo must not cover, e.g. the radar card. */
    public static void drawMenuBackdrop(GuiGraphicsExtractor g, Font font, int vw, int vh, int mx, int my, List<int[]> avoid) {
        LoverFellaMode m = module();
        if (m == null) return;
        double t = t();
        g.fill(0, 0, vw, vh, 0x400A1636);
        if (m.hearts()) drawHearts(g, vw, vh, t);
        int headsRight = m.bobbleHeads() ? drawBobbleHeads(g, font, m.headNames(), vw, vh, mx, my, t) : 0;
        if (m.logos()) {
            // A clean badge (the beating heart + mustache and the LᴏᴠᴇʀCʀᴀꜰᴛ wordmark) instead of the big LC
            // picture, in the first free spot - never on the radar / minimap, a HUD card or the bobble heads
            int bw = 120;
            int bh = 70;
            int[][] spots = {{vw - bw - 14, vh - bh - 12}, {vw - bw - 14, vh / 2 - bh / 2}, {(vw - bw) / 2, vh - bh - 12},
                {Math.max(headsRight + 12, vw / 3), vh - bh - 12}};
            for (int[] spot : spots) {
                boolean free = spot[0] >= headsRight + 6 || spot[1] + bh < vh - 70;
                for (int[] r : avoid) {
                    if (spot[0] < r[0] + r[2] + 4 && spot[0] + bw > r[0] - 4 && spot[1] - 4 < r[1] + r[3] + 4 && spot[1] + bh + 4 > r[1] - 4) free = false;
                }
                if (free) {
                    badge = new int[]{spot[0], spot[1], bw, bh};
                    badgeAt = System.currentTimeMillis();
                    drawBadge(g, font, spot[0], spot[1], bw, t, badgeHit(mx, my));
                    break;
                }
            }
        }
    }

    /** Where the menu badge was drawn last (menu coordinates), so a click on it opens the YouTube channel. */
    private static int[] badge;
    private static long badgeAt;
    public static final String YOUTUBE = "https://www.youtube.com/@LoverFella";

    /** Whether (mx, my) in menu coordinates is on the LoverCraft badge drawn just now. */
    public static boolean badgeHit(double mx, double my) {
        int[] b = badge;
        if (b == null || System.currentTimeMillis() - badgeAt > 250 || !on()) return false;
        return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
    }

    /** A click on the LoverCraft badge in the menu opens LoverFella's YouTube channel. */
    public static void openYoutube() {
        com.relaxedqol.client.util.Links.open(YOUTUBE);
    }

    /** The menu badge: a soft glow, the beating heart + mustache logo, the wordmark and the server address. */
    private static void drawBadge(GuiGraphicsExtractor g, Font font, int x, int y, int w, double t, boolean hover) {
        int cx = x + w / 2;
        double beat = Math.max(0, Math.sin(t * 5.5)) * Math.max(0, Math.sin(t * 1.4));
        float bob = (float) (Math.sin(t * 1.2) * 1.5);
        // Glow: a few soft rings in the shimmering accent
        int accent = accent(0);
        for (int i = 4; i >= 1; i--) {
            int r = 16 + i * 4;
            Render2D.roundRect(g, cx - r, y + 24 - r + Math.round(bob), r * 2, r * 2, r, Theme.withAlpha(accent, hover ? 0.07f : 0.035f));
        }
        int s = 40 + (int) Math.round(beat * 4);
        image(g, HEART_LOGO, cx - s / 2, y + 24 - s / 2 + Math.round(bob), s, s, 0xFFFFFFFF);
        Component lover = Component.literal("Lᴏᴠᴇʀ").withStyle(ChatFormatting.BOLD);
        Component craft = Component.literal("Cʀᴀꜰᴛ").withStyle(ChatFormatting.BOLD);
        int tw = font.width(lover) + font.width(craft);
        int ty = y + 50;
        g.text(font, lover, cx - tw / 2, ty, RED, true);
        g.text(font, craft, cx - tw / 2 + font.width(lover), ty, CYAN, true);
        // Pointing at it shows where a click goes
        String ip = hover ? "YouTube ↗" : "play.loverfella.com";
        g.text(font, ip, cx - font.width(ip) / 2, ty + 11, hover ? 0xFFFFFFFF : 0x99C8D2EA, false);
    }

    /** Hearts rising slowly from the bottom, swaying, fading out near the top. */
    private static void drawHearts(GuiGraphicsExtractor g, int vw, int vh, double t) {
        int n = 16;
        for (int i = 0; i < n; i++) {
            double seed = Math.abs(Math.sin(i * 12.9898) * 43758.5453) % 1.0;
            double speed = 9 + seed * 14;
            double span = vh + 40;
            double y = vh + 20 - ((t * speed + seed * span * 3) % span);
            double x = (i + 0.5) / n * vw + Math.sin(t * 0.7 + i * 1.7) * 14;
            int size = 6 + (int) Math.round(seed * 7);
            float fade = (float) Math.min(1.0, Math.min((y + 20) / (vh * 0.45), 1.0));
            if (fade <= 0.02f) continue;
            int color = Theme.withAlpha(i % 2 == 0 ? RED : CYAN, (0.16f + (float) seed * 0.22f) * fade);
            Render2D.icon(g, "heart_soft", (int) Math.round(x) - size / 2, (int) Math.round(y) - size / 2, size, color);
        }
    }

    private static final int[] SHIRTS = {0xFF8E1B22, 0xFF1B6E8E, 0xFF5B2A86, 0xFF2E7D4F, 0xFF9C5B1B, 0xFF374151};

    /**
     * Bobble heads on springs along the bottom-left; point at one and it wobbles harder. spaced by their name
     * tags (long names no longer overlap) with uneven gaps, so they do not stand in a stiff row. Returns the right
     * edge of the last one.
     */
    private static int drawBobbleHeads(GuiGraphicsExtractor g, Font font, List<String> names, int vw, int vh, int mx, int my, double t) {
        int head = 26;
        int baseY = vh - 16;
        // Up to 10 heads - first the gaps get tighter, then the whole row is drawn smaller, so it always fits
        // on the screen (at most ~70% of its width, the badge keeps its corner)
        int n = names.size();
        int[] slots = new int[n];
        int[] gaps = new int[n];
        int natural = 0;
        int tight = 0;
        for (int i = 0; i < n; i++) {
            String name = names.get(i);
            int seed = name.toLowerCase(java.util.Locale.ROOT).hashCode();
            slots[i] = Math.max(46, font.width(name) + 8);
            gaps[i] = 8 + Math.floorMod(seed, 22);
            natural += slots[i] + gaps[i];
            tight += slots[i] + 4;
        }
        int available = Math.max(120, (int) (vw * 0.7f) - 14);
        boolean squeeze = natural > available;
        float scale = 1f;
        if (squeeze) {
            for (int i = 0; i < n; i++) gaps[i] = 4;
            if (tight > available) scale = Math.max(0.45f, available / (float) tight);
        }
        final int originX = 14;
        g.pose().pushMatrix();
        if (scale != 1f) {
            g.pose().translate(originX, vh);
            g.pose().scale(scale, scale);
            g.pose().translate(-originX, -vh);
            // The mouse in the row's own (scaled) coordinates
            mx = Math.round(originX + (mx - originX) / scale);
            my = Math.round(vh + (my - vh) / scale);
        }
        int cursor = originX;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            int seed = name.toLowerCase(java.util.Locale.ROOT).hashCode();
            int lw = font.width(name) + 8;
            int slot = slots[i];
            int cx = cursor + slot / 2;
            cursor += slot + gaps[i];
            int lift = Math.floorMod(seed >> 5, 3) * 2; // not all on exactly the same line
            boolean near = Math.abs(mx - cx) < slot / 2 && my > baseY - 70 && my < vh;
            double phase = Math.floorMod(seed >> 9, 628) / 100.0;
            double amp = near ? 0.42 : 0.12 + Math.floorMod(seed >> 3, 7) * 0.012;
            double speed = near ? 7.5 : 1.9 + Math.floorMod(seed >> 7, 9) * 0.12;
            double angle = Math.sin(t * speed + phase) * amp;
            double bounce = Math.abs(Math.sin(t * speed * 0.5 + phase)) * (near ? 4 : 1.5);

            // Body: a small rounded torso in a shirt colour (LoverFella: his red jacket)
            int bodyW = 20;
            int bodyH = 16;
            int bodyY = baseY - bodyH - lift;
            int shirt = name.equalsIgnoreCase("LoverFella") ? 0xFF8E1B22 : SHIRTS[Math.floorMod(seed, SHIRTS.length)];
            Render2D.roundRect(g, cx - bodyW / 2, bodyY, bodyW, bodyH, 5, shirt);
            Render2D.roundRect(g, cx - 3, bodyY + 2, 6, bodyH - 4, 2, 0x40FFFFFF);

            // Spring neck
            int neckTop = bodyY - 8 - (int) Math.round(bounce);
            for (int k = 0; k < 4; k++) {
                int yy = bodyY - 2 - k * 2 - (int) Math.round(bounce * k / 4.0);
                int off = k % 2 == 0 ? -2 : 2;
                g.fill(cx + off - 2, yy, cx + off + 2, yy + 1, 0xFFB8C0CC);
            }

            // Head on the spring, rotating around the neck
            g.pose().pushMatrix();
            g.pose().translate(cx, neckTop);
            g.pose().rotate((float) angle);
            Render2D.roundRect(g, -head / 2 - 2, -head - 2, head + 4, head + 4, 5, 0x80000000);
            if (!SkinFaces.draw(g, name, -head / 2, -head, head, 0xFFFFFFFF)) {
                Render2D.roundRect(g, -head / 2, -head, head, head, 4, 0xFF2A3350);
                String q = name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
                g.text(font, q, -font.width(q) / 2, -head / 2 - 4, 0xFFFFFFFF, false);
            }
            g.pose().popMatrix();

            // Name tag, under its own head
            int ly = vh - 13 - lift;
            Render2D.roundRect(g, cx - lw / 2, ly, lw, 11, 5, Theme.withAlpha(NAVY, 0.92f));
            Render2D.roundOutline(g, cx - lw / 2, ly, lw, 11, 5, Theme.withAlpha(i % 2 == 0 ? RED : CYAN, 0.7f));
            g.text(font, name, cx - lw / 2 + 4, ly + 2, near ? 0xFFFFFFFF : 0xFFD8E2F5, false);
        }
        g.pose().popMatrix();
        return names.isEmpty() ? 0 : Math.round(originX + (cursor - originX) * scale);
    }

    // ------------------------------------------------------------------ figures sitting on the windows

    /**
     * Little full-body figures sitting on the top edge of the menu windows, legs dangling over the title bar.
     * perches = {x, topY, w} per window (menu coordinates). They swing their legs, nod, and wave when you point at them.
     */
    public static void drawSitters(GuiGraphicsExtractor g, List<int[]> perches, int mx, int my) {
        drawSitters(g, perches, List.of(), mx, my);
    }

    /**
     * The figures are spread evenly over the free parts of the window edges, in the order you typed them, and
     * never under the search bar / gear (blocked = {x, y, w, h} rectangles to keep clear). Adding a name only slides
     * the others over a little - before, every figure jumped to another window and pose, some sat on top of each
     * other, and with more names than windows the new ones never showed up at all.
     */
    public static void drawSitters(GuiGraphicsExtractor g, List<int[]> perches, List<int[]> blocked, int mx, int my) {
        LoverFellaMode m = module();
        if (m == null || !m.sitters() || perches.isEmpty()) return;
        List<String> names = m.sitterNames();
        if (names.isEmpty()) return;
        double t = t();
        // Free stretches of window edge: {x0, x1, seatY}
        java.util.List<int[]> segs = new java.util.ArrayList<>();
        for (int[] p : perches) {
            java.util.List<int[]> parts = new java.util.ArrayList<>();
            parts.add(new int[]{p[0] + 14, p[0] + p[2] - 16});
            for (int[] b : blocked) {
                // A figure reaches from 22 px above its seat to 8 px below it
                if (b[1] > p[1] + 8 || b[1] + b[3] < p[1] - 24) continue;
                int bx0 = b[0] - 16;
                int bx1 = b[0] + b[2] + 16;
                java.util.List<int[]> next = new java.util.ArrayList<>();
                for (int[] sgm : parts) {
                    if (bx1 <= sgm[0] || bx0 >= sgm[1]) {
                        next.add(sgm);
                        continue;
                    }
                    if (bx0 > sgm[0]) next.add(new int[]{sgm[0], bx0});
                    if (bx1 < sgm[1]) next.add(new int[]{bx1, sgm[1]});
                }
                parts = next;
            }
            for (int[] sgm : parts) if (sgm[1] - sgm[0] >= 24) segs.add(new int[]{sgm[0], sgm[1], p[1]});
        }
        segs.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[2], b[2]));
        int total = 0;
        for (int[] sgm : segs) total += sgm[1] - sgm[0];
        if (total <= 0) return;
        // Room for everyone at 36 px apart at least (a lying figure is 30 px long)
        int n = Math.min(names.size(), Math.max(1, total / 36));
        for (int i = 0; i < n; i++) {
            String name = names.get(i);
            int seed = name.toLowerCase(java.util.Locale.ROOT).hashCode();
            // Evenly spread, nudged a little per name so it does not look like a ruler
            double slot = (double) total / n;
            double at = (i + 0.5) * slot + (Math.floorMod(seed, 9) - 4) * Math.min(1.0, (slot - 36) / 20.0);
            int cx = 0;
            int seat = 0;
            double left = at;
            for (int[] sgm : segs) {
                int len = sgm[1] - sgm[0];
                if (left <= len) {
                    cx = sgm[0] + (int) Math.round(left);
                    seat = sgm[2];
                    break;
                }
                left -= len;
            }
            if (seat == 0 && cx == 0) {
                int[] last = segs.get(segs.size() - 1);
                cx = last[1];
                seat = last[2];
            }
            // The same pose for the same name, every time (LoverFella himself always sits on the edge)
            int pose = name.equalsIgnoreCase("LoverFella") ? 0 : Math.floorMod(seed >> 4, 3);
            boolean near = Math.abs(mx - cx) < 14 && my > seat - 26 && my < seat + 12;
            double phase = Math.floorMod(seed >> 6, 628) / 100.0;
            if (pose == 1) drawLying(g, name, cx, seat, phase, t, near, mx);
            else drawSitter(g, name, cx, seat, phase, t, near, pose == 2, mx);
        }
    }

    /** Sitting on the edge, legs dangling. lean = leaning back a little, one leg kicking more. */
    private static void drawSitter(GuiGraphicsExtractor g, String name, int cx, int seat, double i, double t, boolean wave,
                                   boolean lean, int mx) {
        if (lean) {
            g.pose().pushMatrix();
            g.pose().translate(cx, seat);
            g.pose().rotate(-0.14f);
            g.pose().translate(-cx, -seat);
        }
        drawSitterBody(g, name, cx, seat, i, t, wave, lean, mx);
        if (lean) g.pose().popMatrix();
    }

    private static void drawSitterBody(GuiGraphicsExtractor g, String name, int cx, int seat, double i, double t, boolean wave,
                                       boolean lean, int mx) {
        SkinFaces.Skin skin = SkinFaces.skin(name);
        boolean modern = skin != null && skin.height() >= 64;
        int k = 1;
        int left = cx - 8;
        // Legs: only the shins show, hanging over the edge (the thighs point at you); they swing in turn
        double swing = Math.sin(t * (lean ? 2.4 : 3.2) + i * 1.7);
        int rl = (int) Math.round(swing * (lean ? 2.2 : 1.2));
        int ll = (int) Math.round(-swing * (lean ? 0.4 : 1.2));
        // A soft shadow on the window edge
        Render2D.roundRect(g, left - 1, seat - 1, 18, 3, 1, 0x50000000);
        if (skin != null) {
            SkinFaces.part(g, skin, 4, 24, 4, 8, left + 4, seat + rl, k, 0xFFFFFFFF);
            if (modern) SkinFaces.part(g, skin, 4, 40, 4, 8, left + 4, seat + rl, k, 0xFFFFFFFF);
            SkinFaces.part(g, skin, modern ? 20 : 4, modern ? 56 : 24, 4, 8, left + 8, seat + ll, k, 0xFFFFFFFF);
            if (modern) SkinFaces.part(g, skin, 4, 56, 4, 8, left + 8, seat + ll, k, 0xFFFFFFFF);
            // Body
            SkinFaces.part(g, skin, 20, 20, 8, 12, left + 4, seat - 12, k, 0xFFFFFFFF);
            if (modern) SkinFaces.part(g, skin, 20, 36, 8, 12, left + 4, seat - 12, k, 0xFFFFFFFF);
            // Arms (the right one waves when you point at the figure)
            SkinFaces.part(g, skin, modern ? 36 : 44, modern ? 52 : 20, 4, 12, left + 12, seat - 12, k, 0xFFFFFFFF);
            if (modern) SkinFaces.part(g, skin, 52, 52, 4, 12, left + 12, seat - 12, k, 0xFFFFFFFF);
            g.pose().pushMatrix();
            g.pose().translate(left + 2, seat - 11);
            double armAngle = wave ? Math.PI - 0.35 + Math.sin(t * 12) * 0.35 : Math.sin(t * 1.6 + i) * 0.06;
            g.pose().rotate((float) armAngle);
            SkinFaces.part(g, skin, 44, 20, 4, 12, -2, -1, k, 0xFFFFFFFF);
            if (modern) SkinFaces.part(g, skin, 44, 36, 4, 12, -2, -1, k, 0xFFFFFFFF);
            g.pose().popMatrix();
        } else {
            int shirt = name.equalsIgnoreCase("LoverFella") ? 0xFF8E1B22 : SHIRTS[Math.floorMod(name.hashCode(), SHIRTS.length)];
            g.fill(left + 4, seat + rl, left + 8, seat + 8 + rl, 0xFF1F2430);
            g.fill(left + 8, seat + ll, left + 12, seat + 8 + ll, 0xFF1F2430);
            g.fill(left, seat - 12, left + 16, seat, shirt);
        }
        // Head, nodding a little (on the neck)
        g.pose().pushMatrix();
        g.pose().translate(cx, seat - 12);
        // Nods a little and looks towards your mouse
        double look = Math.max(-0.22, Math.min(0.22, (mx - cx) / 400.0));
        g.pose().rotate((float) (Math.sin(t * 1.9 + i * 2.1) * 0.08 + look));
        if (skin != null) {
            SkinFaces.part(g, skin, 8, 8, 8, 8, -4, -8, k, 0xFFFFFFFF);
            SkinFaces.part(g, skin, 40, 8, 8, 8, -4, -8, k, 0xFFFFFFFF);
        } else if (!SkinFaces.draw(g, name, -4, -8, 8, 0xFFFFFFFF)) {
            g.fill(-4, -8, 4, 0, 0xFF2A3350);
        }
        g.pose().popMatrix();
    }

    /** Lying on top of the window on its stomach, head propped up, lower legs kicking in the air (side view). */
    private static void drawLying(GuiGraphicsExtractor g, String name, int cx, int seat, double phase, double t, boolean wave, int mx) {
        SkinFaces.Skin skin = SkinFaces.skin(name);
        Render2D.roundRect(g, cx - 14, seat - 1, 30, 3, 1, 0x50000000);
        if (skin == null) {
            int shirt = name.equalsIgnoreCase("LoverFella") ? 0xFF8E1B22 : SHIRTS[Math.floorMod(name.hashCode(), SHIRTS.length)];
            g.fill(cx - 6, seat - 4, cx + 6, seat, shirt);
            g.fill(cx - 12, seat - 4, cx - 6, seat, 0xFF1F2430);
            if (!SkinFaces.draw(g, name, cx + 6, seat - 10, 8, 0xFFFFFFFF)) g.fill(cx + 6, seat - 10, cx + 14, seat - 2, 0xFF2A3350);
            return;
        }
        // Lower legs, bent up at the knee and kicking in turn
        for (int leg = 0; leg < 2; leg++) {
            double kick = Math.sin(t * (wave ? 7 : 2.6) + phase + leg * 1.9) * 0.55;
            g.pose().pushMatrix();
            g.pose().translate(cx - 12, seat - 2);
            g.pose().rotate((float) (-0.35 + kick));
            SkinFaces.part(g, skin, 0, 26, 4, 6, -2, -6, 1, leg == 0 ? 0xFFFFFFFF : 0xFFDDDDDD);
            g.pose().popMatrix();
        }
        // Thighs and body, lying along the edge (shoulders on the right)
        g.pose().pushMatrix();
        g.pose().translate(cx - 6, seat - 4);
        g.pose().rotate((float) (Math.PI / 2));
        SkinFaces.part(g, skin, 0, 20, 4, 6, 0, 0, 1, 0xFFFFFFFF);
        g.pose().popMatrix();
        g.pose().pushMatrix();
        g.pose().translate(cx + 6, seat - 4);
        g.pose().rotate((float) (Math.PI / 2));
        SkinFaces.part(g, skin, 16, 20, 4, 12, 0, 0, 1, 0xFFFFFFFF);
        g.pose().popMatrix();
        // Forearm propping the head up
        SkinFaces.part(g, skin, 40, 26, 4, 6, cx + 4, seat - 6, 1, 0xFFFFFFFF);
        // Head (its side), bobbing gently, turning a little towards your mouse
        double look = Math.max(-0.2, Math.min(0.2, (mx - cx) / 500.0));
        g.pose().pushMatrix();
        g.pose().translate(cx + 9, seat - 4);
        g.pose().rotate((float) (Math.sin(t * 1.5 + phase) * 0.07 + look));
        SkinFaces.part(g, skin, 0, 8, 8, 8, -3, -8, 1, 0xFFFFFFFF);
        SkinFaces.part(g, skin, 32, 8, 8, 8, -3, -8, 1, 0xFFFFFFFF);
        g.pose().popMatrix();
    }
}
