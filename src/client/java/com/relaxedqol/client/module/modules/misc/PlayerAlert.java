package com.relaxedqol.client.module.modules.misc;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.PacketManager;
import com.relaxedqol.client.gui.Render2D;
import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.gui.Toasts;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.ActionSetting;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.ChatUtil;
import com.relaxedqol.client.util.SuggestionProbe;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PlayerAlert (replaces PlayerTracker): tells you - with a pop-up, a chat line and a ping -
 * when someone on its watch list comes online or logs off.
 *
 * Online check: the "/msg NAME" Tab completion (in the background, nothing goes to chat): the server only completes
 * the names of players who are online. The tab list counts too.
 *
 * Where they are: taken live from the LiveMap module (the server's web map) - the zone each player is in. No
 * commands are sent for it.
 */
public class PlayerAlert extends Module {
    private final Setting<String> watchList = addSetting(new Setting<>("Watch List", "").hide());
    // The watch list card is a movable element with its own look (HUD editor)
    private final com.relaxedqol.client.gui.HudWindow cardWindow = new com.relaxedqol.client.gui.HudWindow(
        addSetting(com.relaxedqol.client.gui.HudWindow.xSetting()), addSetting(com.relaxedqol.client.gui.HudWindow.ySetting()),
        addSetting(com.relaxedqol.client.gui.HudWindow.scaleSetting()));
    private final com.relaxedqol.client.gui.CardStyle style = new com.relaxedqol.client.gui.CardStyle("", this::addSetting);

    /** The HUD editor's element (null while the card is not shown). */
    public com.relaxedqol.client.gui.HudElement hudElement() {
        return hudList.getValue() ? new com.relaxedqol.client.gui.HudElement("Watch List", cardWindow, style, this) : null;
    }

    private final Setting<Boolean> hudList = addSetting(new Setting<>("HUD List", true),
        "A card under the HUD: everyone on your watch list, online or not, and where they are (from LiveMap)");
    private final Setting<Boolean> hideOffline = addSetting(new Setting<>("Hide Offline", false),
        "Only show the names that are online in the HUD card (offline names are left out)");
    private final Setting<Boolean> popups = addSetting(new Setting<>("Pop-ups", true),
        "A pop-up when someone on the list comes online or logs off");
    private final Setting<Boolean> chatAlerts = addSetting(new Setting<>("Chat Alerts", true));
    private final Setting<Boolean> sound = addSetting(new Setting<>("Sound", true));
    private final Setting<Integer> probeSeconds = addSetting(new Setting<>("Check Every (s)", 3, 1, 30, 1),
        "How often it asks the server's /msg completion who is online");
    private final Setting<Boolean> showInChat = addSetting(new Setting<>("Show While Chatting", true),
        "Keep the HUD card on screen while the chat box is open");

    /** name (lower case) -> last time /msg completion or the tab list showed them. */
    private final Map<String, Long> lastSeen = new HashMap<>();
    /** Who we told you is online. */
    private final Set<String> online = new LinkedHashSet<>();
    /** The server's own spelling of each name. */
    private final Map<String, String> spelling = new HashMap<>();
    /** Where each player is: name -> short place ("zone 10"), from LiveMap. */
    private final Map<String, String> place = new HashMap<>();
    private long lastAnswerAt;
    private long nextProbe;
    private int probeIndex;
    /** Joining: a single "online now" summary instead of a pop-up per name. */
    private long joinedAt;
    private Object trackedConnection;

    public PlayerAlert() {
        super("PlayerAlert", "Pop-up when someone on your watch list comes online (/msg check) + where they are (LiveMap)",
            ModuleCategory.LOVERCRAFT);
        addSetting(new ActionSetting("Edit List", () -> mc.gui.setScreen(
            new com.relaxedqol.client.gui.PlayerAlertScreen(mc.gui.screen(), this))),
            "Your watch list in a window: who is online and where, click the x to take a name off, + to add");
        addSetting(new ActionSetting("Show List", this::printList), "Your watch list in chat, who is online and where");
    }

    // ------------------------------------------------------------------ watch list

    public Set<String> watched() {
        Set<String> names = new LinkedHashSet<>();
        for (String part : watchList.getValue().split("[,; ]+")) {
            String name = part.trim();
            if (!name.isEmpty()) names.add(name.toLowerCase(Locale.ROOT));
        }
        return names;
    }

    public boolean add(String name) {
        if (!com.relaxedqol.client.util.Names.isTypedName(name)) return false; // Bedrock ".name" too
        Set<String> names = watched();
        if (!names.add(name.toLowerCase(Locale.ROOT))) return false;
        spelling.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        watchList.setValue(String.join(",", names));
        return true;
    }

    public boolean remove(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Set<String> names = watched();
        if (!names.remove(key)) return false;
        online.remove(key);
        lastSeen.remove(key);
        place.remove(key);
        watchList.setValue(String.join(",", names));
        return true;
    }

    public void clearList() {
        watchList.setValue("");
        online.clear();
        lastSeen.clear();
        place.clear();
    }

    public void printList() {
        Set<String> names = watched();
        if (names.isEmpty()) {
            ChatUtil.send("§7PlayerAlert: your watch list is empty - add names with §f.alert add NAME");
            return;
        }
        ChatUtil.send("§6PlayerAlert watch list §7(" + names.size() + "):" + (isEnabled() ? "" : " §8(PlayerAlert is off - only the tab list is checked)"));
        for (String key : names) {
            String where = place.get(key);
            Boolean on = status(key);
            String state = on == null ? " §echecking..." : on ? " §aonline" : " §8offline";
            ChatUtil.send("§7  " + (Boolean.TRUE.equals(on) ? "§a● §f" : "§8● §7") + nice(key) + state
                + (Boolean.TRUE.equals(on) && where != null ? " §7- §e" + where : ""));
        }
    }

    public boolean isOnline(String key) {
        return Boolean.TRUE.equals(status(key));
    }

    private Set<String> tabCache = Set.of();
    private long tabCacheAt;

    /** Lower-case names on the tab list (rebuilt at most 4 times a second). */
    private Set<String> tabNames() {
        long now = System.currentTimeMillis();
        if (now - tabCacheAt < 250) return tabCache;
        tabCacheAt = now;
        Set<String> names = new java.util.HashSet<>();
        ClientPacketListener c = mc.getConnection();
        if (c != null) for (PlayerInfo info : c.getOnlinePlayers()) names.add(info.getProfile().name().toLowerCase(Locale.ROOT));
        tabCache = names;
        return names;
    }

    /**
     * Online / offline right now - also from the tab list and the latest /msg answers, so "Show List" never
     * calls someone offline just because the alert for them has not fired yet. null = not checked yet.
     */
    public Boolean status(String key) {
        if (online.contains(key)) return true;
        if (tabNames().contains(key)) return true;
        long now = System.currentTimeMillis();
        Long seen = lastSeen.get(key);
        long stale = probeSeconds.getValue() * 3000L + 2000;
        if (seen != null && now - seen <= stale) return true;
        // No answer from the server yet since joining / turning it on: we do not know yet
        if (isEnabled() && lastAnswerAt == 0) return null;
        return false;
    }

    public String placeOf(String key) {
        return place.get(key);
    }

    public String nice(String key) {
        return spelling.getOrDefault(key, key);
    }

    // ------------------------------------------------------------------ session

    /** Disconnected: nothing from this server may show on the next one. */
    public void clearSession() {
        lastSeen.clear();
        online.clear();
        place.clear();
        lastAnswerAt = 0;
        nextProbe = 0;
        trackedConnection = null;
    }

    @Override
    protected void onEnable() {
        clearSession();
    }

    @Override
    protected void onDisable() {
        clearSession();
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void onTick() {
        ClientPacketListener c = mc.getConnection();
        if (c == null || mc.player == null) return;
        if (trackedConnection != c.getConnection()) {
            clearSession();
            trackedConnection = c.getConnection();
            joinedAt = System.currentTimeMillis();
        }
        Set<String> watched = watched();
        if (watched.isEmpty()) return;
        long now = System.currentTimeMillis();

        // The tab list: anyone shown there is online
        for (PlayerInfo info : c.getOnlinePlayers()) {
            String name = info.getProfile().name();
            String key = name.toLowerCase(Locale.ROOT);
            if (!watched.contains(key)) continue;
            spelling.put(key, name);
            lastSeen.put(key, now);
        }

        // The live web map - whoever is on it is online, and its world is their zone
        com.relaxedqol.client.module.modules.lovercraft.LiveMap map = com.relaxedqol.client.module.modules.lovercraft.LiveMap.get();
        if (map != null && map.feedsAlert()) {
            for (String key : watched) {
                var mp = map.find(nice(key));
                if (mp == null) continue;
                spelling.putIfAbsent(key, mp.name());
                lastSeen.put(key, now);
                String zone = com.relaxedqol.client.module.modules.lovercraft.LiveMap.zoneLabel(mp);
                if (zone != null) place.put(key, zone);
            }
        }

        // "/msg " + name completion: the server only completes names of players who are online
        if (now >= nextProbe) {
            nextProbe = now + probeSeconds.getValue() * 1000L;
            SuggestionProbe.request("/msg ", words -> onCompletion(words, watched));
            List<String> list = new ArrayList<>(watched);
            int asked = Math.min(list.size(), 8);
            for (int i = 0; i < asked; i++) {
                String key = list.get((probeIndex + i) % list.size());
                SuggestionProbe.request("/msg " + nice(key), words -> onCompletion(words, watched));
            }
            probeIndex = (probeIndex + asked) % Math.max(1, list.size());
        }

        // Online / offline changes
        long round = probeSeconds.getValue() * 1000L;
        long stale = round * 3 + 2000;
        boolean joining = now - joinedAt < 6000;
        List<String> cameOnline = new ArrayList<>();
        for (String key : watched) {
            Long seen = lastSeen.get(key);
            boolean isOnline = seen != null && now - seen <= stale;
            if (isOnline && online.add(key)) {
                cameOnline.add(key);
            } else if (!isOnline && online.contains(key) && lastAnswerAt - (seen != null ? seen : 0) > stale - round) {
                // Only once the server kept answering without them (a lagging server is not "logged off")
                online.remove(key);
                place.remove(key);
                alert(nice(key) + " logged off", "§7● §f" + nice(key) + " §7logged off", 0xFF9AA0B0, false);
            }
        }
        online.retainAll(watched);
        if (!cameOnline.isEmpty()) {
            if (joining && cameOnline.size() > 1) {
                List<String> names = new ArrayList<>();
                for (String key : cameOnline) names.add(nice(key));
                alert(String.join(", ", names) + " online", "§a● §fOnline now: §a" + String.join("§7, §a", names), 0xFF4ADE80, true);
            } else {
                for (String key : cameOnline) alert(nice(key) + " is online", "§a● §f" + nice(key) + " §7is §aonline", 0xFF4ADE80, true);
            }
        }

    }

    private void onCompletion(List<String> words, Set<String> watched) {
        long now = System.currentTimeMillis();
        lastAnswerAt = now;
        for (String word : words) {
            String name = word.trim();
            String key = name.toLowerCase(Locale.ROOT);
            if (!watched.contains(key)) continue;
            spelling.put(key, name);
            lastSeen.put(key, now);
        }
    }

    private void alert(String popup, String chat, int color, boolean ping) {
        if (popups.getValue()) Toasts.show("PlayerAlert", popup, color, ping ? 6000 : 3500);
        if (chatAlerts.getValue()) ChatUtil.send(chat);
        if (ping && sound.getValue() && mc.player != null) {
            mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f);
            mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.5f);
        }
    }

    /** Whether the HUD card may show over the chat box. */
    public boolean showWhileChatting() {
        return showInChat.getValue();
    }

    // ------------------------------------------------------------------ HUD card

    /**
     * The card under the HUD: everyone on the list, online first, with where they are
     * (from LiveMap). top = the HUD pixel row to start at (-1 = right under the HUD's info panel).
     */
    public void renderHud(GuiGraphicsExtractor g, int top) {
        if (!hudList.getValue() || mc.player == null) return;
        Set<String> watched = watched();
        if (watched.isEmpty()) return;
        Font font = mc.font;
        List<String> keys = new ArrayList<>(watched);
        keys.sort((a, b) -> Boolean.compare(isOnline(b), isOnline(a)));
        int listed = keys.size();
        if (hideOffline.getValue()) keys.removeIf(k -> !isOnline(k));
        int accent = Theme.accent();
        float scale = com.relaxedqol.client.gui.OverlayRenderer.hudScale();
        com.relaxedqol.client.module.modules.render.HUD hud =
            RelaxedQolClient.enabled(com.relaxedqol.client.module.modules.render.HUD.class);
        int x = 4;
        int y = top >= 0 ? top + 4 : (hud != null ? hud.leftColumnBottom() + 4 : 4);

        int onlineCount = 0;
        for (String key : keys) if (isOnline(key)) onlineCount++;
        String countText = onlineCount + "/" + listed;
        boolean any = onlineCount > 0;

        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        // LoverFella Mode has its own look for this list
        if (com.relaxedqol.client.gui.LoverFx.playerAlertOn()) {
            List<com.relaxedqol.client.gui.LoverFx.AlertRow> rows = new ArrayList<>();
            for (String key : keys) {
                boolean on = isOnline(key);
                String where = place.get(key);
                rows.add(new com.relaxedqol.client.gui.LoverFx.AlertRow(nice(key), on, on ? (where != null ? where : "online") : "offline"));
            }
            com.relaxedqol.client.gui.LoverFx.drawPlayerAlert(g, font, x, y, rows, onlineCount, listed);
            g.pose().popMatrix();
            return;
        }
        // The shared HUD card look (same body, edge, header and chip as the info panel and Notes), smooth font
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            // Nicer card: each player with their face (a live status dot on its corner), online first, offline
            // ones quieter under a thin divider, and where they are as a small coloured tag
            final int pad = com.relaxedqol.client.gui.HudStyle.PAD;
            final int headerH = com.relaxedqol.client.gui.HudStyle.HEADER_H;
            final int rowH = 14;
            final int green = com.relaxedqol.client.gui.HudStyle.ONLINE;
            List<String> on = new ArrayList<>();
            List<String> off = new ArrayList<>();
            for (String key : keys) (isOnline(key) ? on : off).add(key);
            int w = com.relaxedqol.client.gui.HudStyle.headerWidth(font, "Watch List", countText);
            for (String key : keys) {
                String right = isOnline(key) ? place.getOrDefault(key, "online") : "offline";
                w = Math.max(w, pad + 14 + font.width(nice(key)) + 14 + font.width(right) + 8 + pad);
            }
            w = Math.max(w, 132);
            boolean split = !on.isEmpty() && !off.isEmpty();
            int h = keys.isEmpty() ? headerH : headerH + 5 + keys.size() * rowH + (split ? 5 : 0) + 3;
            // Drawn at its own spot (right under the HUD until you move it), in its own look
            int vw = Math.round(g.guiWidth() / scale);
            int vh = Math.round(g.guiHeight() / scale);
            cardWindow.begin(g, vw, vh, w, h, x, y);
            com.relaxedqol.client.gui.HudStyle.begin(style);
            accent = com.relaxedqol.client.gui.HudStyle.accent();
            x = 0;
            y = 0;
            try {
            com.relaxedqol.client.gui.HudStyle.card(g, x, y, w, h);
            com.relaxedqol.client.gui.HudStyle.header(g, font, x, y, w, "Watch List", countText, any);
            if (!keys.isEmpty()) com.relaxedqol.client.gui.HudStyle.divider(g, x, y + headerH, w);
            float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 420.0);
            int ry = y + headerH + 5;
            List<String> ordered = new ArrayList<>(on);
            ordered.addAll(off);
            for (int i = 0; i < ordered.size(); i++) {
                String key = ordered.get(i);
                boolean online = isOnline(key);
                if (split && i == on.size()) {
                    // A soft divider between who is online and who is not
                    g.fill(x + pad + 14, ry, x + w - pad, ry + 1, 0x12FFFFFF);
                    ry += 5;
                }
                if (online) com.relaxedqol.client.gui.Render2D.roundRect(g, x + 3, ry - 1, w - 6, rowH - 1, 4, 0x0CFFFFFF);
                // Face, faded when offline, with a status dot on its corner
                int fx = x + pad;
                int fy = ry + 1;
                int tint = online ? 0xFFFFFFFF : 0x70FFFFFF;
                if (!com.relaxedqol.client.util.SkinFaces.draw(g, nice(key), fx, fy, 9, tint)) {
                    com.relaxedqol.client.gui.Render2D.roundRect(g, fx, fy, 9, 9, 2, online ? 0x40FFFFFF : 0x20FFFFFF);
                }
                if (online) {
                    com.relaxedqol.client.gui.Render2D.roundRect(g, fx + 6, fy + 6, 5, 5, 2, 0xFF14141B);
                    com.relaxedqol.client.gui.Render2D.roundRect(g, fx + 7, fy + 7, 3, 3, 1, Theme.withAlpha(green, 0.75f + 0.25f * pulse));
                }
                g.text(font, nice(key), fx + 14, ry + 2, online ? com.relaxedqol.client.gui.HudStyle.VALUE : com.relaxedqol.client.gui.HudStyle.FAINT, false);
                String where = place.get(key);
                String right = online ? (where != null ? where : "online") : "offline";
                int tw = font.width(right);
                int rx = x + w - pad - tw;
                if (online && where != null) {
                    // Where they are: a little tag in the accent colour
                    com.relaxedqol.client.gui.Render2D.roundRect(g, rx - 4, ry, tw + 8, rowH - 3, 4, Theme.withAlpha(accent, 0.22f));
                    g.text(font, right, rx, ry + 2, Theme.mix(accent, 0xFFFFFFFF, 0.35f), false);
                } else {
                    g.text(font, right, rx, ry + 2, online ? green : 0xFF5E6170, false);
                }
                ry += rowH;
            }
            } finally {
                com.relaxedqol.client.gui.HudStyle.end();
                cardWindow.end(g);
            }
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }
        g.pose().popMatrix();
    }

    @Override
    public String getInfo() {
        int n = 0;
        for (String key : watched()) if (isOnline(key)) n++;
        return n == 0 ? null : String.valueOf(n);
    }
}
