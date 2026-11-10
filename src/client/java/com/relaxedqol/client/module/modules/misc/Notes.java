package com.relaxedqol.client.module.modules.misc;

import com.relaxedqol.client.gui.HudCard;
import com.relaxedqol.client.gui.HudWindow;
import com.relaxedqol.client.gui.NotesScreen;
import com.relaxedqol.client.gui.Render2D;
import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.ActionSetting;
import com.relaxedqol.client.setting.KeySetting;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.NotesStore;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * "Notes": a small notepad in the game. Open Notes (or the Notes Key) to write, edit, pin and delete notes;
 * while the module is on, pinned notes show on a movable HUD card. Saved in the config folder (notes.json).
 */
public class Notes extends Module implements HudCard {
    private final KeySetting openKey = addSetting(new KeySetting("Notes Key", -1, true), "Opens the notes window");
    private final Setting<Integer> cardWidth = addSetting(new Setting<>("Card Width", 150, 90, 300, 10),
        "How wide the pinned-notes card on the HUD is");
    private final Setting<Integer> opacity = addSetting(new Setting<>("Background %", 70, 0, 100, 5));
    /** How the pinned notes are listed on the card. */
    private final Setting<String> listStyle = addSetting(Setting.mode("List Style", "Numbers", "Plain", "Bullets", "Numbers"),
        "Plain: each note's first line as a bold title. Bullets: a dot before every note. Numbers: 1. 2. 3.");
    /** Numbers became the default - switched over once for configs saved with the old default. */
    private final Setting<Boolean> numbersDefault = addSetting(new Setting<>("Numbers Default Set", false).hide());
    private final Setting<Integer> winX = addSetting(HudWindow.xSetting());
    private final Setting<Integer> winY = addSetting(HudWindow.ySetting());
    private final Setting<Integer> winScale = addSetting(HudWindow.scaleSetting());
    private final HudWindow window = new HudWindow(winX, winY, winScale);
    private boolean keyWas;
    /** .notes opens the window a moment later (after the chat has closed). */
    private static int openIn = -1;

    public static void openSoon() {
        openIn = 2;
    }

    public Notes() {
        super("Notes", "Write notes in the game; pinned ones show on the HUD", ModuleCategory.MISC);
        addSetting(new ActionSetting("Open Notes", this::open), "Opens the notes window");
    }

    private void open() {
        mc.gui.setScreen(new NotesScreen(mc.gui.screen()));
    }

    @Override
    public void alwaysTick() {
        if (openIn >= 0 && openIn-- == 0 && mc.player != null) {
            openIn = -1;
            open();
        }
        int k = openKey.getKey();
        boolean typing = mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen
            || (mc.gui.screen() != null && (mc.gui.screen().getFocused() instanceof net.minecraft.client.gui.components.EditBox
                || mc.gui.screen().getFocused() instanceof net.minecraft.client.gui.components.MultiLineEditBox));
        boolean down = k >= 0 && mc.getWindow() != null && !typing
            && com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), k);
        if (down && !keyWas && mc.player != null && !(mc.gui.screen() instanceof NotesScreen)) open();
        keyWas = down;
    }

    /** This card's own look (HUD editor). */
    private final com.relaxedqol.client.gui.CardStyle style = new com.relaxedqol.client.gui.CardStyle("", this::addSetting);

    @Override
    public com.relaxedqol.client.gui.CardStyle cardStyle() {
        return style;
    }

    @Override
    public HudWindow getWindow() {
        return window;
    }

    @Override
    public boolean showsCard() {
        return !NotesStore.pinned().isEmpty();
    }

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int vw, int vh) {
        List<NotesStore.Note> pinned = NotesStore.pinned();
        if (pinned.isEmpty()) return;
        // While the open Mod / Quick Panel lies over the card, the card is not drawn (no text showing through)
        if (window.getW() > 0) {
            float hs = com.relaxedqol.client.gui.OverlayRenderer.hudScale();
            if (com.relaxedqol.client.gui.ScreenPanelOverlay.coversGui(window.getX() * hs, window.getY() * hs,
                window.getW() * hs, window.getH() * hs)) return;
        }
        // Drawn as a clean notes card, in the smooth font
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            drawCard(g, font, vw, vh, pinned);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
    }

    /** The widest "N." of 1..count (so the text after the numbers always lines up). */
    private static int widestNumber(Font font, int count) {
        int w = 0;
        for (int i = 1; i <= count; i++) w = Math.max(w, font.width(i + "."));
        return w;
    }

    private static final int YELLOW = 0xFFFFD60A;

    private void drawCard(GuiGraphicsExtractor g, Font font, int vw, int vh, List<NotesStore.Note> pinned) {
        if (!numbersDefault.getValue()) {
            numbersDefault.setValue(true);
            if (listStyle.getValue().equals("Plain")) listStyle.setValue("Numbers");
        }
        int w = cardWidth.getValue();
        boolean lover = com.relaxedqol.client.gui.LoverFx.on();
        // The shared HUD card look (same padding, header and colours as the info panel and Players list)
        int pad = com.relaxedqol.client.gui.HudStyle.PAD;
        int headerH = com.relaxedqol.client.gui.HudStyle.HEADER_H;
        String style = listStyle.getValue();
        boolean list = !style.equals("Plain");
        boolean numbers = style.equals("Numbers");
        // Room for the marker in front of every note ("•" or "12.")
        // A clean number column (the widest number + room), bullets a soft dot
        int indent = !list ? 0 : numbers ? widestNumber(font, pinned.size()) + 7 : 10;
        int textW = w - pad * 2 - indent;
        List<List<String>> notes = new ArrayList<>();
        int gap = 7;
        int lineH = 10;
        int h = headerH + 6;
        for (NotesStore.Note n : pinned) {
            List<String> l = NotesStore.wrap(font, n.text, textW - 4);
            if (l.isEmpty()) l = List.of("");
            notes.add(l);
            h += l.size() * lineH + gap;
        }
        h += 6 - gap;
        int[] auto = freeSpot(vw, vh, w, h);
        window.begin(g, vw, vh, w, h, auto[0], auto[1]);
        float a = Math.max(0.55f, opacity.getValue() / 100f);
        int accent = Theme.accent();
        int marker = lover ? com.relaxedqol.client.gui.LoverFx.CYAN : accent;
        if (lover) {
            Render2D.shadow(g, 0, 0, w, h, 12);
            Render2D.roundRect(g, 0, 0, w, h, 12, Theme.withAlpha(0xFF141A2E, a));
            Render2D.roundOutline(g, 0, 0, w, h, 12, 0x24FFFFFF);
            Render2D.icon(g, "heart_soft", pad, (headerH - 9) / 2, 9, com.relaxedqol.client.gui.LoverFx.RED);
            window.crispText(g, font, net.minecraft.network.chat.Component.literal("Notes").withStyle(net.minecraft.ChatFormatting.BOLD),
                pad + 13, (headerH - 8) / 2, 0xFFF2F2F7, 1f, false, false);
        } else {
            com.relaxedqol.client.gui.HudStyle.card(g, 0, 0, w, h, a);
            com.relaxedqol.client.gui.HudStyle.header(g, font, 0, 0, w, "Notes", String.valueOf(pinned.size()), false);
        }
        com.relaxedqol.client.gui.HudStyle.divider(g, 0, headerH, w);
        int y = headerH + 6;
        for (int n = 0; n < notes.size(); n++) {
            List<String> lines = notes.get(n);
            // A hairline between notes (starting under the text, not the numbers) - calm, like a real list
            if (n > 0) g.fill(pad + indent, y - 4, w - pad, y - 3, 0x14FFFFFF);
            if (list) {
                if (numbers) {
                    // The number in the accent colour, dimmed, every one starting at the same spot
                    window.crispText(g, font, (n + 1) + ".", pad, y, Theme.withAlpha(marker, 0.8f), 1f, false, false);
                } else {
                    Render2D.smoothCircle(g, pad + 3f, y + 4.5f, 2.2f, marker, 1f);
                }
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (i == 0 && !list) {
                    window.crispText(g, font, net.minecraft.network.chat.Component.literal(line).withStyle(net.minecraft.ChatFormatting.BOLD),
                        pad, y, com.relaxedqol.client.gui.HudStyle.VALUE, 1f, false, false);
                } else {
                    window.crispText(g, font, line, pad + indent, y,
                        list ? com.relaxedqol.client.gui.HudStyle.VALUE : com.relaxedqol.client.gui.HudStyle.LABEL, 1f, false, false);
                }
                y += lineH;
            }
            y += gap;
        }
        window.end(g);
    }

    /**
     * Where the card goes until you drag it - the first spot on the left side (or the right) that no other
     * HUD card (keystrokes, armor, radar...) is using.
     */
    private int[] freeSpot(int vw, int vh, int w, int h) {
        float k = winScale.getValue() / 100f;
        int sw = Math.round(w * k);
        int sh = Math.round(h * k);
        // (not right under the HUD info panel: PlayerAlert's list sits there)
        int[][] spots = {{4, vh / 2 - sh / 2}, {4, vh - sh - 46}, {vw - sw - 4, vh / 2 - sh / 2}, {vw - sw - 4, 44}};
        // With Keystrokes on, the notes sit right above it, lined up with its left edge
        com.relaxedqol.client.module.modules.render.Keystrokes keys =
            com.relaxedqol.client.RelaxedQolClient.enabled(com.relaxedqol.client.module.modules.render.Keystrokes.class);
        if (keys != null && keys.getWindow().isVisible()) {
            HudWindow kw = keys.getWindow();
            int ax = Math.max(0, Math.min(kw.getX(), vw - sw));
            int ay = kw.getY() - sh - 6;
            if (ay >= 2) {
                int[][] more = new int[spots.length + 1][];
                more[0] = new int[]{ax, ay};
                System.arraycopy(spots, 0, more, 1, spots.length);
                spots = more;
            }
        }
        for (int[] spot : spots) {
            boolean free = true;
            for (HudWindow other : com.relaxedqol.client.gui.OverlayRenderer.visibleWindows()) {
                if (other == window || !other.isVisible()) continue;
                if (spot[0] < other.getX() + other.getW() + 3 && spot[0] + sw > other.getX() - 3
                    && spot[1] < other.getY() + other.getH() + 3 && spot[1] + sh > other.getY() - 3) {
                    free = false;
                    break;
                }
            }
            if (free) return spot;
        }
        return spots[0];
    }

    @Override
    public String getInfo() {
        int n = NotesStore.pinned().size();
        return n == 0 ? null : n + " pinned";
    }
}
