package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.gui.Render2D;
import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.EntityUtil;
import com.relaxedqol.client.util.Heads;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lunar-style locator bar: where nearby players are, as their face (skin head) on a compass strip.
 *
 * Drawn IN the experience bar by default (not floating above it), heads 8 px by default, a cleaner look -
 * a dark rim and a soft coloured ring around each head, crisp labels on a dark pill, compass letters (N E S W) on the
 * free-floating styles, and in the XP bar only the player you look most towards gets a name label (so the hearts
 * and food bar stay readable). Players behind you sit on the edges with an arrow; above / below arrows when someone
 * is well above or below you. Friends get their colour.
 */
public class LocatorBar extends Module {
    private final Setting<String> position = addSetting(Setting.mode("Position", "In XP Bar", "In XP Bar", "Above Hotbar", "Top"),
        "In XP Bar: on the experience bar itself. Above Hotbar: a strip just above it. Top: at the top of the screen");
    private final Setting<Integer> range = addSetting(new Setting<>("Range", 256, 16, 1024, 16), "Only players this close (blocks)");
    private final Setting<Integer> arc = addSetting(new Setting<>("View Angle", 120, 60, 360, 10),
        "How much of the world the bar covers, centred on where you look (360 = all around)");
    private final Setting<String> labels = addSetting(Setting.mode("Names", "Smart", "Smart", "All", "Off"),
        "Smart: in the XP bar only the player you look most towards gets a label, elsewhere everyone. All / Off");
    private final Setting<Boolean> distance = addSetting(new Setting<>("Distance", true), "Distance next to the name");
    private final Setting<Boolean> edges = addSetting(new Setting<>("Show Behind On Edges", true),
        "Players outside the view angle sit at the ends of the bar with an arrow");
    private final Setting<Boolean> compass = addSetting(new Setting<>("Compass Letters", true),
        "N / E / S / W on the bar (not in the XP bar, where they would cover the bar)");
    private final Setting<Integer> headSize = addSetting(new Setting<>("Head Size", 8, 6, 20, 1));
    private final Setting<Integer> width = addSetting(new Setting<>("Bar Width", 182, 100, 400, 2),
        "Width of the free-floating styles (In XP Bar always matches the experience bar)");
    private final Setting<Boolean> npcs = addSetting(new Setting<>("Show NPCs", false), "Server NPCs that look like players");
    /** The game's own locator dots sat right on top of our heads (e.g. on LoverCraft). */
    private final Setting<Boolean> hideVanilla = addSetting(new Setting<>("Hide Vanilla Dots", true),
        "Hides the game's own coloured locator dots, so only these heads show (they used to cover the heads)");

    /** Read for every sprite the game draws (LocatorDotsMixin), so a plain flag, set while the bar draws. */
    public static volatile boolean hideDotsNow;

    @Override
    protected void onDisable() {
        hideDotsNow = false;
    }

    /** Whether the vanilla locator dots should be skipped (LocatorBarRendererMixin). */
    public static boolean hidesVanillaDots() {
        LocatorBar bar = com.relaxedqol.client.RelaxedQolClient.enabled(LocatorBar.class);
        return bar != null && bar.hideVanilla.getValue();
    }

    public LocatorBar() {
        super("LocatorBar", "Player heads and names on the experience bar, like a compass", ModuleCategory.RENDER);
        setEnabled(true); // On by default
    }

    /** A mark is a player in the world, or one from the live map (player == null, far away). */
    private record Mark(Player player, String name, java.util.UUID uuid, double dist, float rel, double dy, boolean fromMap) {}

    private boolean inXpBar() {
        return position.getValue().equals("In XP Bar");
    }

    private List<Player> candidates = new ArrayList<>();
    private long candidatesAt;
    private Object candidatesLevel;

    /** The players that may show on the bar (not you, no spectators, NPCs only when wanted), refreshed every 200 ms. */
    private List<Player> candidates() {
        long now = System.currentTimeMillis();
        if (now - candidatesAt < 200 && candidatesLevel == mc.level) return candidates;
        candidatesAt = now;
        candidatesLevel = mc.level;
        List<Player> list = new ArrayList<>();
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            if (!npcs.getValue() && EntityUtil.isNpc(p)) continue;
            list.add(p);
        }
        candidates = list;
        return list;
    }

    public void render(GuiGraphicsExtractor g) {
        hideDotsNow = hideVanilla.getValue();
        if (mc.player == null || mc.level == null) return;
        Vec3 me = mc.player.position();
        float yaw = mc.player.getYRot();
        List<Mark> marks = new ArrayList<>();
        int maxD = range.getValue();
        // Who can be on the bar is worked out a few times a second (the NPC check is not free with a full
        // spawn); where they are on the bar is still updated every frame, so the dots move smoothly
        for (Player p : candidates()) {
            if (p.isRemoved()) continue;
            double d = p.position().distanceTo(me);
            if (d > maxD) continue;
            double dx = p.getX() - me.x;
            double dz = p.getZ() - me.z;
            float angle = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
            marks.add(new Mark(p, p.getGameProfile().name(), p.getUUID(), d, Mth.wrapDegrees(angle - yaw), p.getY() - me.y, false));
        }
        // Players in your zone from the live web map, also far outside your render distance
        com.relaxedqol.client.module.modules.lovercraft.LiveMap map = com.relaxedqol.client.module.modules.lovercraft.LiveMap.get();
        if (map != null && map.feedsLocator()) {
            java.util.Set<String> have = new java.util.HashSet<>();
            for (Mark m : marks) have.add(m.name().toLowerCase(java.util.Locale.ROOT));
            for (Player p : mc.level.players()) have.add(p.getGameProfile().name().toLowerCase(java.util.Locale.ROOT));
            for (var mp : map.usable()) {
                if (!have.add(mp.name().toLowerCase(java.util.Locale.ROOT))) continue;
                double dx = mp.x() - me.x;
                double dz = mp.z() - me.z;
                double d = Math.sqrt(dx * dx + dz * dz);
                float angle = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
                java.util.UUID id = com.relaxedqol.client.util.Names.tabId(mp.name(), mp.uuid());
                marks.add(new Mark(null, mp.name(), id, d, Mth.wrapDegrees(angle - yaw), mp.y() - me.y, true));
            }
        }
        boolean xp = inXpBar();
        int cx = g.guiWidth() / 2;
        int barW = xp ? 182 : Math.min(width.getValue(), g.guiWidth() - 20);
        int barY;
        if (xp) barY = g.guiHeight() - 27;                       // the middle of the experience bar
        else if (position.getValue().equals("Top")) barY = 12;
        else barY = g.guiHeight() - 29 - 14;
        int left = cx - barW / 2;
        float half = arc.getValue() / 2f;
        int accent = Theme.accent();

        if (!xp) {
            // A soft strip with faded ends, a centre tick and compass letters
            Render2D.horizontalGradient(g, left, barY, barW / 2, 1, 0x00FFFFFF, 0x66FFFFFF);
            Render2D.horizontalGradient(g, cx, barY, barW - barW / 2, 1, 0x66FFFFFF, 0x00FFFFFF);
            g.fill(cx, barY - 3, cx + 1, barY + 4, Theme.withAlpha(accent, 0.95f));
            if (compass.getValue()) drawCompass(g, cx, barY, barW, yaw, half);
        } else {
            // Only a tiny accent notch in the middle of the XP bar, so the bar itself stays readable
            g.fill(cx, barY - 3, cx + 1, barY - 1, Theme.withAlpha(accent, 0.9f));
        }
        if (marks.isEmpty()) return;
        // Far ones first, so the nearest is drawn on top
        marks.sort(Comparator.comparingDouble(Mark::dist).reversed());
        int hs = headSize.getValue();
        Mark focus = null;
        for (Mark m : marks) if (Math.abs(m.rel()) <= half && (focus == null || Math.abs(m.rel()) < Math.abs(focus.rel()))) focus = m;
        String labelMode = labels.getValue();
        for (Mark m : marks) {
            float rel = m.rel();
            boolean outside = Math.abs(rel) > half;
            if (outside && !edges.getValue()) continue;
            float f = Math.max(-1f, Math.min(1f, rel / half));
            int x = Math.round(cx + f * (barW / 2f - hs / 2f - 2));
            int y = barY - hs / 2;
            boolean friend = m.player() != null ? EntityUtil.isFriend(m.player())
                : com.relaxedqol.client.FriendManager.isFriend(m.name());
            // Map players (far away) get a softer ring so they read as "far"
            int ring = friend ? EntityUtil.FRIEND_COLOR : outside ? 0x80FFFFFF : m.fromMap() ? 0xFF9CA3AF : 0xFFFFFFFF;
            float fade = outside ? 0.55f : m.fromMap() ? 0.7f : (float) Math.max(0.5, 1 - m.dist() / maxD * 0.5);
            // Dark rim, then a thin coloured ring, then the face
            Render2D.roundRect(g, x - hs / 2 - 2, y - 2, hs + 4, hs + 4, 3, Theme.withAlpha(0xFF0B0B12, 0.85f * fade));
            Render2D.roundRect(g, x - hs / 2 - 1, y - 1, hs + 2, hs + 2, 2, Theme.withAlpha(ring, 0.9f * fade));
            int tint = Theme.withAlpha(0xFFFFFFFF, fade);
            if (!Heads.draw(g, m.uuid(), x - hs / 2, y, hs, tint)
                && !com.relaxedqol.client.util.SkinFaces.draw(g, m.name(), x - hs / 2, y, hs, tint)) {
                g.fill(x - hs / 2, y, x + hs / 2, y + hs, Theme.withAlpha(EntityUtil.PLAYER_COLOR, fade));
            }
            if (outside) {
                String arrow = rel < 0 ? "◀" : "▶";
                Render2D.crispText(g, mc.font, arrow, rel < 0 ? x - hs / 2 - 7 : x + hs / 2 + 2, barY - 3,
                    Theme.withAlpha(0xFFFFFFFF, 0.7f), 0.75f, false, true);
            } else if (Math.abs(m.dy()) > 4 && !m.fromMap()) { // Map heights are rough - no arrow for them
                Render2D.crispText(g, mc.font, m.dy() > 0 ? "▲" : "▼", x + hs / 2 + 1, y - 3,
                    Theme.withAlpha(0xFFFFFFFF, 0.8f), 0.6f, false, true);
            }
            boolean label = !outside && switch (labelMode) {
                case "All" -> true;
                case "Off" -> false;
                default -> !xp || m == focus;
            };
            if (label) {
                String name = m.name();
                long dm = Math.round(m.dist());
                String dist = dm >= 10000 ? (dm / 1000) + "k" : dm >= 1000 ? String.format(java.util.Locale.ROOT, "%.1fk", dm / 1000.0) : dm + "m";
                String text = distance.getValue() ? name + "  " + dist : name;
                labelPill(g, text, x, y - 9, friend ? EntityUtil.FRIEND_COLOR : 0xFFF2F2F7, fade);
            }
        }
    }

    /** N / E / S / W where they are on the strip. */
    private void drawCompass(GuiGraphicsExtractor g, int cx, int barY, int barW, float yaw, float half) {
        String[] names = {"S", "W", "N", "E"};
        for (int i = 0; i < 4; i++) {
            float rel = Mth.wrapDegrees(i * 90f - yaw);
            if (Math.abs(rel) > half) continue;
            float f = rel / half;
            int x = Math.round(cx + f * (barW / 2f - 4));
            float alpha = 1f - Math.abs(f) * 0.6f;
            g.fill(x, barY - 1, x + 1, barY + 2, Theme.withAlpha(0xFFFFFFFF, 0.5f * alpha));
            Render2D.crispText(g, mc.font, names[i], x + 0.5f, barY + 3, Theme.withAlpha(names[i].equals("N") ? Theme.accent() : 0xFFE5E7EB, alpha),
                0.6f, true, true);
        }
    }

    /** A small, sharp label on a dark pill, centred on x. */
    private void labelPill(GuiGraphicsExtractor g, String text, int x, int y, int color, float alpha) {
        float size = 0.6f;
        float w = Render2D.crispWidth(mc.font, text, size);
        int pw = Math.round(w) + 6;
        int ph = 7;
        Render2D.roundRect(g, x - pw / 2, y - 1, pw, ph, 3, Theme.withAlpha(0xFF0B0B12, 0.75f * alpha));
        Render2D.crispText(g, mc.font, text, x, y, Theme.withAlpha(color, alpha), size, true, false);
    }
}
