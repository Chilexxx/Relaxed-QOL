package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.gui.Anim;
import com.relaxedqol.client.gui.Render2D;
import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Watermark, info panel and active module list. Drawn through Fabric's HUD element registry. */
public class HUD extends Module {
    /** The shared card body colour (HudStyle.BODY at 84%). */
    private static final int BOX = 0xD6151619;

    private final Setting<Integer> size = addSetting(new Setting<>("Scale %", 85, 40, 200, 5));
    private final Setting<Boolean> watermark = addSetting(new Setting<>("Watermark", true));
    private final Setting<Boolean> info = addSetting(new Setting<>("Info Panel", true));
    private final Setting<Boolean> netherCoords = addSetting(new Setting<>("Nether Coords", true));
    /** The speed line can be switched off (off by default). */
    private final Setting<Boolean> speed = addSetting(new Setting<>("Speed", false), "How fast you move (blocks a second)");
    /** The ping line is gone; these are the info panel's lines now (each can be switched off). */
    private final Setting<Boolean> biome = addSetting(new Setting<>("Biome", true), "The biome you are standing in");
    private final Setting<Boolean> direction = addSetting(new Setting<>("Direction", true),
        "Which way you face: North / East... and the axis you walk along (+X, -Z...)");
    private final Setting<Boolean> serverAddress = addSetting(new Setting<>("Server Address", false),
        "The address of the server you are on (singleplayer shows \"Singleplayer\")");
    private final Setting<Boolean> players = addSetting(new Setting<>("Players Online", false),
        "How many players are on the server - the number the server shows in tab (\"ONLINE: 77\"), or the tab list size");
    private final Setting<Boolean> clock = addSetting(new Setting<>("Clock", false), "Your real-world time (HH:mm)");
    private final Setting<Boolean> moduleList = addSetting(new Setting<>("Module List", true));
    /**
     * The full list took half the screen. Compact (default): only the tools you switch on and off (not the
     * look / HUD modules that are always on), newest first, at most a few rows and "+N more".
     */
    private final Setting<String> listStyle = addSetting(Setting.mode("List Style", "Compact", "Compact", "Full"),
        "Compact: at most Max Rows modules (the tools you switch on and off first, newest first) and \"+N\" for the rest."
            + " Full: every module that is on");
    private final Setting<Integer> maxRows = addSetting(new Setting<>("Max Rows", 5, 1, 20, 1)
        .showIf(() -> this.listStyle.getValue().equals("Compact")), "How many modules the compact list shows at most");
    private final Setting<Boolean> moduleInfo = addSetting(new Setting<>("Module Info", true));
    /** The list without boxes behind it (it sat in the way); Boxed = the old look. */
    private final Setting<String> listLook = addSetting(Setting.mode("List Look", "Clean", "Clean", "Boxed"),
        "Clean: just the names with a soft shadow and a small accent mark - no dark boxes in the way. Boxed: the old look");

    // The watermark, info panel and module list are movable elements with their own look (HUD editor)
    private final Setting<Boolean> listRight = addSetting(new Setting<>("List Window Right", true).hide());
    private final com.relaxedqol.client.gui.HudWindow wmWindow = new com.relaxedqol.client.gui.HudWindow(
        addSetting(com.relaxedqol.client.gui.HudWindow.xSetting("Watermark ")),
        addSetting(com.relaxedqol.client.gui.HudWindow.ySetting("Watermark ")),
        addSetting(com.relaxedqol.client.gui.HudWindow.scaleSetting("Watermark ")));
    private final com.relaxedqol.client.gui.HudWindow infoWindow = new com.relaxedqol.client.gui.HudWindow(
        addSetting(com.relaxedqol.client.gui.HudWindow.xSetting("Info ")),
        addSetting(com.relaxedqol.client.gui.HudWindow.ySetting("Info ")),
        addSetting(com.relaxedqol.client.gui.HudWindow.scaleSetting("Info ")));
    private final com.relaxedqol.client.gui.HudWindow listWindow = new com.relaxedqol.client.gui.HudWindow(
        addSetting(com.relaxedqol.client.gui.HudWindow.xSetting("List ")),
        addSetting(com.relaxedqol.client.gui.HudWindow.ySetting("List ")),
        addSetting(com.relaxedqol.client.gui.HudWindow.scaleSetting("List "))).pinRight(listRight);
    private final com.relaxedqol.client.gui.CardStyle watermarkStyle = new com.relaxedqol.client.gui.CardStyle("Watermark ", this::addSetting);
    private final com.relaxedqol.client.gui.CardStyle infoStyle = new com.relaxedqol.client.gui.CardStyle("Info ", this::addSetting);
    private final com.relaxedqol.client.gui.CardStyle moduleListStyle = new com.relaxedqol.client.gui.CardStyle("List ", this::addSetting);

    /** The HUD editor's elements of this module. */
    public List<com.relaxedqol.client.gui.HudElement> elements() {
        List<com.relaxedqol.client.gui.HudElement> out = new ArrayList<>();
        boolean lover = com.relaxedqol.client.gui.LoverFx.watermarkOn();
        if (watermark.getValue() && !lover) out.add(new com.relaxedqol.client.gui.HudElement("Watermark", wmWindow, watermarkStyle, this));
        if (info.getValue() && !com.relaxedqol.client.gui.LoverFx.infoPanelOn()) {
            out.add(new com.relaxedqol.client.gui.HudElement("Info Panel", infoWindow, infoStyle, this));
        }
        if (moduleList.getValue()) out.add(new com.relaxedqol.client.gui.HudElement("Module List", listWindow, moduleListStyle, this));
        return out;
    }

    private final Anim anim = new Anim();
    /** Bottom of the watermark + info panel (HUD pixels), so other top-left cards can sit right under it. */
    private int leftBottom = 0;

    public HUD() {
        super("HUD", "Watermark, coordinates, FPS, ping and the active module list", ModuleCategory.MISC); // In Misc
        setEnabled(true);
    }

    public int getSizePercent() {
        return size.getValue();
    }

    /** Where the top-left column (watermark / info panel) ends, in HUD pixels. */
    public int leftColumnBottom() {
        return leftBottom;
    }

    public void render(GuiGraphicsExtractor g) {
        if (mc.player == null || mc.level == null) return;
        anim.tick();
        Font font = mc.font;
        float scale = Theme.uiScale(mc.getWindow().getGuiScale(), size.getValue());
        int vw = Math.round(g.guiWidth() / scale);
        int vh = Math.round(g.guiHeight() / scale);

        g.pose().pushMatrix();
        g.pose().scale(scale, scale);

        // The whole HUD in the smooth font, like every other card (one look)
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            int y = 4;
            // Each element has its own LoverFella Mode switch (whether it lights up red / cyan)
            String prev = com.relaxedqol.client.gui.LoverFx.beginScope("Watermark");
            try {
                if (watermark.getValue()) y = drawWatermark(g, font, y, vw, vh) + 4;
                com.relaxedqol.client.gui.LoverFx.beginScope("Info Panel");
                if (info.getValue()) y = drawInfo(g, font, y, vw, vh) + 4;
                leftBottom = y - 4;
                com.relaxedqol.client.gui.LoverFx.beginScope("Module List");
                if (moduleList.getValue()) drawModuleList(g, font, vw, vh);
            } finally {
                com.relaxedqol.client.gui.LoverFx.endScope(prev);
            }
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }

        g.pose().popMatrix();
    }

    /**
     * The watermark at its own spot (movable in the HUD editor, with its own look). Returns where the next
     * automatically placed element starts: under it while it sits at its automatic spot, else the same y.
     */
    private int drawWatermark(GuiGraphicsExtractor g, Font font, int y, int vw, int vh) {
        if (com.relaxedqol.client.gui.LoverFx.watermarkOn()) return com.relaxedqol.client.gui.LoverFx.drawWatermark(g, font, y);
        Component title = Component.literal("Relaxed").withStyle(ChatFormatting.BOLD);
        String rest = " QOL";
        String version = "26.2";
        // The same card as everything else - accent mark, accent "Relaxed", white "QOL", faint version
        int pad = com.relaxedqol.client.gui.HudStyle.PAD;
        int w = pad + 9 + font.width(title) + font.width(rest) + 6 + font.width(version) + pad;
        int h = com.relaxedqol.client.gui.HudStyle.HEADER_H;
        wmWindow.begin(g, vw, vh, w, h, 4, y);
        com.relaxedqol.client.gui.HudStyle.begin(watermarkStyle);
        try {
            int accent = com.relaxedqol.client.gui.HudStyle.accent();
            boolean shadow = com.relaxedqol.client.gui.HudStyle.textShadow();
            com.relaxedqol.client.gui.HudStyle.card(g, 0, 0, w, h);
            com.relaxedqol.client.gui.HudStyle.mark(g, pad, h / 2, accent);
            int x = pad + 9;
            int ty = h / 2 - 4;
            g.text(font, title, x, ty, accent, shadow);
            x += font.width(title);
            g.text(font, rest, x, ty, com.relaxedqol.client.gui.HudStyle.VALUE, shadow);
            x += font.width(rest) + 6;
            g.text(font, version, x, ty, com.relaxedqol.client.gui.HudStyle.FAINT, shadow);
        } finally {
            com.relaxedqol.client.gui.HudStyle.end();
            wmWindow.end(g);
        }
        return wmWindow.isAuto() ? y + wmWindow.getH() : y - 4;
    }

    /** The info panel text is rebuilt 20 times a second, not every frame (String.format is not free). */
    private List<String[]> infoLines = List.of();
    private long infoAt;
    private int infoLabelW;
    private int infoValueW;
    private long infoShrinkSince;

    /** The widest digit in the font that is drawing right now (smooth or pixel). */
    private static char widestDigit(Font font) {
        char best = '0';
        int bw = -1;
        for (char c = '0'; c <= '9'; c++) {
            int w = font.width(String.valueOf(c));
            if (w > bw) {
                bw = w;
                best = c;
            }
        }
        return best;
    }

    private int drawInfo(GuiGraphicsExtractor g, Font font, int y, int vw, int vh) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - infoAt >= 50 || infoLines.isEmpty()) {
            infoAt = nowMs;
            infoLines = buildInfo();
            int labelW = 0;
            int valueW = 0;
            // Measured as if every digit were the widest one, so the panel does not twitch while the numbers
            // change (walking), and it only shrinks once it has been too wide for a few seconds
            char wide = widestDigit(font);
            for (String[] line : infoLines) {
                labelW = Math.max(labelW, font.width(line[0]));
                valueW = Math.max(valueW, font.width(line[1].replaceAll("[0-9]", String.valueOf(wide))));
            }
            if (labelW >= infoLabelW || nowMs - infoShrinkSince > 3000) infoLabelW = labelW;
            if (valueW >= infoValueW) {
                infoValueW = valueW;
                infoShrinkSince = nowMs;
            } else if (nowMs - infoShrinkSince > 3000) {
                infoValueW = valueW;
                infoShrinkSince = nowMs;
            }
        }
        List<String[]> lines = infoLines;
        int labelW = infoLabelW;
        int valueW = infoValueW;
        // LoverFella Mode has its own info panel
        if (com.relaxedqol.client.gui.LoverFx.infoPanelOn()) {
            return com.relaxedqol.client.gui.LoverFx.drawInfoPanel(g, font, y, lines, labelW, valueW);
        }
        // The shared card; labels dim, values white
        int pad = com.relaxedqol.client.gui.HudStyle.PAD;
        // The same space above the first line as below the last (it was 6 on top, 5 under)
        int w = pad + labelW + 10 + valueW + pad;
        int h = 6 + lines.size() * 10 + 4;
        infoWindow.begin(g, vw, vh, w, h, 4, y);
        com.relaxedqol.client.gui.HudStyle.begin(infoStyle);
        try {
            boolean shadow = com.relaxedqol.client.gui.HudStyle.textShadow();
            com.relaxedqol.client.gui.HudStyle.card(g, 0, 0, w, h);
            int ly = 6;
            for (String[] line : lines) {
                g.text(font, line[0], pad, ly, com.relaxedqol.client.gui.HudStyle.LABEL, shadow);
                g.text(font, line[1], pad + labelW + 10, ly, com.relaxedqol.client.gui.HudStyle.VALUE, shadow);
                ly += 10;
            }
        } finally {
            com.relaxedqol.client.gui.HudStyle.end();
            infoWindow.end(g);
        }
        return infoWindow.isAuto() ? y + infoWindow.getH() : y - 4;
    }

    private List<String[]> buildInfo() {
        List<String[]> lines = new ArrayList<>();
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();
        lines.add(new String[]{"XYZ", String.format(Locale.ROOT, "%.1f  %.1f  %.1f", px, py, pz)});
        if (netherCoords.getValue()) {
            boolean nether = mc.level.dimension() == Level.NETHER;
            double factor = nether ? 8.0 : 0.125;
            lines.add(new String[]{nether ? "Overworld" : "Nether",
                String.format(Locale.ROOT, "%.1f  %.1f", px * factor, pz * factor)});
        }
        double dx = px - mc.player.xo;
        double dz = pz - mc.player.zo;
        if (speed.getValue()) lines.add(new String[]{"Speed", String.format(Locale.ROOT, "%.1f b/s", Math.sqrt(dx * dx + dz * dz) * 20)});
        if (direction.getValue()) lines.add(new String[]{"Facing", facing()});
        if (biome.getValue()) lines.add(new String[]{"Biome", biomeName()});
        lines.add(new String[]{"FPS", String.valueOf(mc.getFps())});
        if (serverAddress.getValue()) {
            var server = mc.getCurrentServer();
            lines.add(new String[]{"Server", server != null && server.ip != null ? server.ip : "Singleplayer"});
        }
        if (players.getValue() && mc.getConnection() != null) {
            // The number the server itself shows in tab ("ONLINE: 77"), not a count of tab entries
            lines.add(new String[]{"Online", String.valueOf(com.relaxedqol.client.util.TabInfo.onlineCount())});
        }
        if (clock.getValue()) {
            lines.add(new String[]{"Time", java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))});
        }
        return lines;
    }

    /** "North (-Z)" from where you look. */
    private String facing() {
        float yaw = net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot());
        int i = Math.floorMod(Math.round(yaw / 90f), 4);
        return switch (i) {
            case 0 -> "South (+Z)";
            case 1 -> "West (-X)";
            case 2 -> "North (-Z)";
            default -> "East (+X)";
        };
    }

    private String cachedBiomeId = "";
    private String cachedBiomeName = "";

    /** The biome's name ("Dark Forest"), from the game's translations. */
    private String biomeName() {
        var holder = mc.level.getBiome(mc.player.blockPosition());
        String id = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("");
        if (!id.equals(cachedBiomeId)) {
            cachedBiomeId = id;
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
            String name = Component.translatable("biome." + ns + "." + path).getString();
            if (name.startsWith("biome.") || name.isEmpty()) {
                StringBuilder b = new StringBuilder();
                for (String w : path.split("_")) {
                    if (w.isEmpty()) continue;
                    if (b.length() > 0) b.append(' ');
                    b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
                }
                name = b.length() > 0 ? b.toString() : "?";
            }
            cachedBiomeName = name;
        }
        return cachedBiomeName;
    }

    /** One row of the module list, measured once per frame (the sort used to re-measure and re-ask getInfo()). */
    private record Entry(Module module, String extra, int width, float progress) {}

    private void drawModuleList(GuiGraphicsExtractor g, Font font, int screenW, int screenH) {
        // Every module that is on, or still sliding out after being turned off
        List<Entry> shown = new ArrayList<>();
        for (Module module : RelaxedQolClient.moduleManager.getModules()) {
            if (module == this || module.isSettingsOnly() || !module.getCategory().active()
                || module instanceof com.relaxedqol.client.module.modules.misc.QuickPanel) continue;
            float p = anim.smooth(module, module.isEnabled() ? 1 : 0, 14);
            if (p <= 0.01f) continue;
            String extra = extra(module);
            int width = font.width(module.displayName()) + (extra != null ? 4 + font.width(extra) : 0);
            shown.add(new Entry(module, extra, width, p));
        }
        boolean compact = listStyle.getValue().equals("Compact");
        int more = 0;
        if (compact) {
            // The compact list left out almost every module, so only one ever showed. Now every module that is
            // on counts; the tools you switch on and off come first (newest first), the always-on look modules after
            // them, and only the first Max Rows (5) are drawn with "+N more" under them.
            shown.removeIf(e -> e.module().getName().equals("ClickGUI"));
            long now = System.currentTimeMillis();
            for (Module m : RelaxedQolClient.moduleManager.getModules()) {
                boolean on = m.isEnabled();
                Boolean was = lastState.put(m, on);
                // Already on when the game started: oldest; switched on just now: newest
                if (on && (was == null || !was)) enabledAt.put(m, was == null ? 0L : now);
            }
            shown.sort((a, b) -> {
                boolean la = LOOK_MODULES.contains(a.module().getName());
                boolean lb = LOOK_MODULES.contains(b.module().getName());
                if (la != lb) return la ? 1 : -1;
                int byTime = Long.compare(enabledAt.getOrDefault(b.module(), 0L), enabledAt.getOrDefault(a.module(), 0L));
                return byTime != 0 ? byTime : Integer.compare(b.width(), a.width());
            });
            int max = maxRows.getValue();
            // Modules still sliding out do not count as hidden
            int visible = 0;
            for (Entry e : shown) if (e.module().isEnabled()) visible++;
            if (visible > max) {
                more = visible - max;
                List<Entry> keep = new ArrayList<>();
                int on = 0;
                for (Entry e : shown) {
                    if (e.module().isEnabled()) {
                        if (on >= max) continue;
                        on++;
                    }
                    keep.add(e);
                }
                shown = keep;
            }
        }
        shown.sort((a, b) -> Integer.compare(b.width(), a.width()));
        final int hidden = more;
        if (shown.isEmpty() && hidden == 0) return;

        // The list is a movable element - work out its box, then draw inside it (right-aligned in the box,
        // and the box sits against the right edge of the screen until you drag it somewhere else)
        boolean clean = listLook.getValue().equals("Clean");
        int rowStep = clean ? 10 : 11;
        float rows = 0;
        int widest = 0;
        for (Entry e : shown) {
            rows += e.progress();
            widest = Math.max(widest, e.width());
        }
        if (hidden > 0) widest = Math.max(widest, font.width("+" + hidden + " more"));
        int vw = widest + 18;
        int boxH = Math.round(rows * rowStep) + (hidden > 0 ? rowStep : 0) + 8;
        listWindow.begin(g, screenW, screenH, vw, boxH, screenW - vw, 0);
        com.relaxedqol.client.gui.HudStyle.begin(moduleListStyle);
        try {
            if (clean) drawCleanList(g, font, vw, shown, hidden);
            else drawBoxedList(g, font, vw, shown, hidden);
        } finally {
            com.relaxedqol.client.gui.HudStyle.end();
            listWindow.end(g);
        }
    }

    /** The boxed list look (local to the list's box: vw = the box's width). */
    private void drawBoxedList(GuiGraphicsExtractor g, Font font, int vw, List<Entry> shown, int hidden) {
        boolean custom = moduleListStyle.isCustom();
        int box = custom ? Theme.withAlpha(com.relaxedqol.client.gui.HudStyle.bodyColor(),
            Math.max(0f, com.relaxedqol.client.gui.HudStyle.bodyAlpha())) : BOX;
        int y = 4;
        int index = 0;
        for (Entry e : shown) {
            Module module = e.module();
            float p = e.progress();
            int w = e.width() + 10;
            int h = 11;
            int right = vw - 2;
            int x = right - Math.round(w * p);
            int accent = custom ? com.relaxedqol.client.gui.HudStyle.accent() : Theme.accent(index);

            // Flat rows stacked flush, each with a full-height accent line on its right edge
            // (the rows together form one continuous vertical line down the side of the screen)
            g.fill(x, y, right - 2, y + h, Theme.fade(box, p));
            // A plain accent line on the right edge (no colour gradient between rows)
            g.fill(right - 2, y, right, y + Math.max(1, Math.round(h * p)), Theme.fade(accent, p));
            boolean ts = com.relaxedqol.client.gui.HudStyle.textShadow();
            g.text(font, module.displayName(), x + 4, y + 2, Theme.fade(com.relaxedqol.client.gui.HudStyle.VALUE, Math.max(p, 0.1f)), ts);
            if (e.extra() != null) {
                g.text(font, e.extra(), x + 4 + font.width(module.displayName()) + 4, y + 2,
                    Theme.fade(com.relaxedqol.client.gui.HudStyle.LABEL, Math.max(p, 0.1f)), ts);
            }
            y += Math.round(h * p);
            index++;
        }
        if (hidden > 0) {
            // A clear "+5 more" row under the list
            String t = "+" + hidden + " more";
            int w = font.width(t) + 10;
            int right = vw - 2;
            int h = 11;
            g.fill(right - w, y, right - 2, y + h, Theme.fade(box, 0.85f));
            g.text(font, t, right - w + 4, y + 2, com.relaxedqol.client.gui.HudStyle.FAINT, false);
        }
    }

    /**
     * Clean module list: right-aligned names with a soft shadow, the info dim after them and a slim rounded
     * accent mark on the right - no background boxes, so it no longer covers the world behind it.
     */
    private void drawCleanList(GuiGraphicsExtractor g, Font font, int vw, List<Entry> shown, int hidden) {
        int right = vw - 4;
        int y = 4;
        int index = 0;
        int rowH = 10;
        boolean custom = moduleListStyle.isCustom();
        // Clean: names with a soft shadow - a custom style can switch the shadow off and give the list a background
        boolean ts = !custom || com.relaxedqol.client.gui.HudStyle.textShadow();
        if (custom && com.relaxedqol.client.gui.HudStyle.bodyAlpha() > 0.01f) {
            float rowsH = 0;
            for (Entry e : shown) rowsH += e.progress() * rowH;
            com.relaxedqol.client.gui.HudStyle.card(g, 0, 0, vw, Math.round(rowsH) + (hidden > 0 ? rowH : 0) + 8);
        }
        for (Entry e : shown) {
            Module module = e.module();
            float p = e.progress();
            int accent = custom ? com.relaxedqol.client.gui.HudStyle.accent() : Theme.accent(index);
            int w = e.width();
            // Slides in from the right edge while it turns on / off
            int x = right - 6 - Math.round(w * p) - Math.round((1 - p) * 8);
            int alphaText = Theme.fade(com.relaxedqol.client.gui.HudStyle.VALUE, Math.max(p, 0.05f));
            g.text(font, module.displayName(), x, y + 1, alphaText, ts);
            if (e.extra() != null) {
                g.text(font, e.extra(), x + font.width(module.displayName()) + 4, y + 1,
                    Theme.fade(com.relaxedqol.client.gui.HudStyle.LABEL, Math.max(p, 0.05f)), ts);
            }
            Render2D.roundRect(g, right - 2, y + 1, 2, rowH - 2, 1, Theme.fade(accent, p * 0.85f));
            y += Math.round(rowH * p);
            index++;
        }
        if (hidden > 0) {
            String t = "+" + hidden + " more";
            g.text(font, t, right - 6 - font.width(t), y + 1, com.relaxedqol.client.gui.HudStyle.LABEL, ts);
        }
    }

    /** Modules that are "always on" parts of the look / HUD - the compact list leaves them out. */
    private static final java.util.Set<String> LOOK_MODULES = java.util.Set.of("HUD", "ClickGUI", "Chat", "ActionBar", "BossBar",
        "ArmorStatus", "Keystrokes", "CustomSky", "BlockOutline", "ScrollableTooltips", "LocatorBar", "Radar", "Waypoints",
        "LoverFellaMode", "Scoreboard", "InvTweaks", "PlayerAlert", "TradeShot",
        "Fullbright", "Zoom", "Freelook");
    private final java.util.Map<Module, Long> enabledAt = new java.util.HashMap<>();
    private final java.util.Map<Module, Boolean> lastState = new java.util.HashMap<>();

    private String extra(Module module) {
        return moduleInfo.getValue() ? module.getInfo() : null;
    }

}
