package com.relaxedqol.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The player count the SERVER shows in its tab list ("ONLINE: 77" in the header / footer or in a tab entry),
 * instead of counting tab entries (tab plugins fill the list with fake entries, and vanished / other-world players
 * may be missing). Falls back to counting the tab list when the server shows no number.
 */
public final class TabInfo {
    private static final Pattern ONLINE = Pattern.compile(
        "(?i)(?:\\bonline|\\bplayers?(?:\\s+online)?)\\s*(?:[:»>\\-|=]\\s*)?(\\d{1,6})(?:\\s*/\\s*\\d{1,6})?");
    private static final Pattern ONLINE_AFTER = Pattern.compile("(?i)\\b(\\d{1,6})(?:\\s*/\\s*\\d{1,6})?\\s+(?:players?\\s+)?online\\b");

    private static long cachedAt;
    private static int cached = -1;
    private static Field header;
    private static Field footer;
    private static Object tabOverlay;
    private static boolean looked;

    /** The tab header / footer exactly as the server sent them (read from the packet - always works). */
    private static volatile Component packetHeader;
    private static volatile Component packetFooter;

    private TabInfo() {}

    /** Network thread: remember the tab list header and footer. */
    public static void onPacket(net.minecraft.network.protocol.Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundTabListPacket tab) {
            packetHeader = tab.header();
            packetFooter = tab.footer();
            cachedAt = 0;
        }
    }

    /** Players online as the server shows it, or -1 when it shows no such number. */
    public static int serverCount() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < 1000) return cached;
        cachedAt = now;
        cached = find();
        return cached;
    }

    /** What the HUD shows: the server's own number, else the tab list size. */
    public static int onlineCount() {
        int n = serverCount();
        if (n >= 0) return n;
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() == null ? 0 : mc.getConnection().getOnlinePlayers().size();
    }

    private static int find() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return -1;
        List<String> texts = new ArrayList<>();
        if (packetHeader != null) texts.add(packetHeader.getString());
        if (packetFooter != null) texts.add(packetFooter.getString());
        if (texts.isEmpty()) for (Component c : headerFooter()) if (c != null) texts.add(c.getString());
        // The tab list (header / footer) always wins - "ONLINE: 195" there is this server's own count, while a
        // sidebar line may count the whole network. Footers that flip between pages keep the last tab number for a
        // while, so the HUD does not jump to another number on a page without it.
        for (String t : texts) {
            int n = parse(t);
            if (n >= 0) {
                lastTab = n;
                lastTabAt = System.currentTimeMillis();
                return n;
            }
        }
        if (lastTab >= 0 && System.currentTimeMillis() - lastTabAt < 60_000) return lastTab;
        // The sidebar scoreboard ("Online: 77" on the right of the screen)
        for (String line : sidebarLines()) {
            int n = parse(line);
            if (n >= 0) return n;
        }
        // Tab plugins often put it in one of the (fake) entries
        try {
            for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
                Component display = info.getTabListDisplayName();
                if (display == null) continue;
                int n = parse(display.getString());
                if (n >= 0) return n;
            }
        } catch (RuntimeException ignored) {
            // nothing
        }
        return -1;
    }

    /** The number right after "Online" ("ONLINE: 77", "Online » 77") wins over any other "players" number. */
    private static final Pattern ONLINE_EXACT = Pattern.compile("(?i)\\bonline\\s*(?:players?)?\\s*[:»>\\-|=]?\\s*(\\d{1,6})");

    private static int lastTab = -1;
    private static long lastTabAt;

    static int parse(String raw) {
        if (raw == null || raw.isEmpty()) return -1;
        String text = plain(raw.replaceAll("§.", ""));
        for (String line : text.split("\n")) {
            Matcher m = ONLINE_EXACT.matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        for (String line : text.split("\n")) {
            Matcher m = ONLINE.matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1));
            m = ONLINE_AFTER.matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /** Small-caps letters servers use for a "fancy" look (ᴏɴʟɪɴᴇ), in a-z order. */
    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    /**
     * Fancy server text back to plain letters and digits, so "ᴏɴʟɪɴᴇ: 195" (small caps), full-width
     * "ＯＮＬＩＮＥ：１９５" and the like are read just like "ONLINE: 195".
     */
    static String plain(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int small = SMALL_CAPS.indexOf(cp);
            if (small >= 0) {
                out.append((char) ('a' + small));
            } else if (cp >= 0xFF01 && cp <= 0xFF5E) {
                out.append((char) (cp - 0xFF01 + 0x21)); // full-width ASCII
            } else if (Character.isDigit(cp) && cp > 0x7F) {
                out.append((char) ('0' + Character.digit(cp, 10)));
            } else {
                out.appendCodePoint(cp);
            }
        }
        return out.toString();
    }

    /** The tab list header and footer (found by reflection, so a renamed field just means "no number"). */
    private static List<Component> headerFooter() {
        List<Component> out = new ArrayList<>();
        try {
            Object overlay = overlay();
            if (overlay == null) return out;
            if (header != null) out.add((Component) header.get(overlay));
            if (footer != null) out.add((Component) footer.get(overlay));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // nothing
        }
        return out;
    }

    private static Object overlay() {
        if (tabOverlay != null) return tabOverlay;
        // Keep trying (it used to give up for good when the HUD did not exist yet)
        if (looked && System.currentTimeMillis() % 5000 > 50) return null;
        looked = true;
        try {
            Minecraft mc = Minecraft.getInstance();
            // The HUD object (mc.gui.hud in 26.x, or mc.gui itself in older versions) holds the tab overlay
            List<Object> holders = new ArrayList<>();
            holders.add(mc.gui);
            for (Field f : allFields(mc.gui.getClass())) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                if (!f.getType().getName().startsWith("net.minecraft.client.gui")) continue;
                f.setAccessible(true);
                Object v = f.get(mc.gui);
                if (v != null) holders.add(v);
            }
            for (Object holder : holders) {
                for (Field f : allFields(holder.getClass())) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    String type = f.getType().getSimpleName();
                    if (!type.contains("TabOverlay") && !type.contains("TabList")) continue;
                    f.setAccessible(true);
                    Object overlay = f.get(holder);
                    if (overlay == null) continue;
                    List<Field> comps = new ArrayList<>();
                    for (Field c : allFields(overlay.getClass())) {
                        if (Modifier.isStatic(c.getModifiers()) || c.getType() != Component.class) continue;
                        c.setAccessible(true);
                        comps.add(c);
                        if (c.getName().equalsIgnoreCase("header")) header = c;
                        if (c.getName().equalsIgnoreCase("footer")) footer = c;
                    }
                    if (header == null && comps.size() >= 1) header = comps.get(0);
                    if (footer == null && comps.size() >= 2) footer = comps.get(1);
                    tabOverlay = overlay;
                    return overlay;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // nothing
        }
        return null;
    }

    private static List<Field> allFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) out.add(f);
        }
        return out;
    }

    /** The sidebar scoreboard's lines as drawn (team prefix + name + suffix), found by reflection (safe on any version). */
    public static List<String> sidebarLines() {
        List<String> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return out;
        try {
            Object board = mc.level.getClass().getMethod("getScoreboard").invoke(mc.level);
            if (board == null) return out;
            Object objective = null;
            for (java.lang.reflect.Method m : board.getClass().getMethods()) {
                if (!m.getName().equals("getDisplayObjective") || m.getParameterCount() != 1) continue;
                Class<?> slotType = m.getParameterTypes()[0];
                if (!slotType.isEnum()) continue;
                for (Object c : slotType.getEnumConstants()) {
                    if (((Enum<?>) c).name().equals("SIDEBAR")) objective = m.invoke(board, c);
                }
            }
            if (objective == null) return out;
            java.lang.reflect.Method list = null;
            for (java.lang.reflect.Method m : board.getClass().getMethods()) {
                if (m.getName().equals("listPlayerScores") && m.getParameterCount() == 1) list = m;
            }
            java.lang.reflect.Method teamOf = null;
            for (java.lang.reflect.Method m : board.getClass().getMethods()) {
                if (m.getName().equals("getPlayersTeam") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) teamOf = m;
            }
            if (list == null) return out;
            Object entries = list.invoke(board, objective);
            if (!(entries instanceof java.util.Collection<?> col)) return out;
            // In the order the sidebar shows them (highest score at the top)
            List<Object> sorted = new ArrayList<>(col);
            sorted.sort((a, b) -> Integer.compare(scoreOf(b), scoreOf(a)));
            for (Object e : sorted) {
                String owner = null;
                try {
                    Object o = e.getClass().getMethod("owner").invoke(e);
                    owner = o == null ? null : o.toString();
                } catch (ReflectiveOperationException ignored) {
                    // no owner
                }
                if (owner == null) continue;
                String line = owner;
                if (teamOf != null) {
                    Object team = teamOf.invoke(board, owner);
                    if (team instanceof net.minecraft.world.scores.PlayerTeam pt) {
                        line = net.minecraft.world.scores.PlayerTeam.formatNameForTeam(pt, Component.literal(owner)).getString();
                    }
                }
                try {
                    Object display = e.getClass().getMethod("display").invoke(e);
                    if (display instanceof Component dc) line = line + " " + dc.getString();
                } catch (ReflectiveOperationException ignored) {
                    // no custom display
                }
                out.add(line);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // no sidebar
        }
        return out;
    }

    private static int scoreOf(Object entry) {
        try {
            Object v = entry.getClass().getMethod("value").invoke(entry);
            return v instanceof Number n ? n.intValue() : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private static final Pattern ZONE_LABEL = Pattern.compile(
        "(?i)\\b(zone|region|area|location)\\b\\s*[:»>|\\-]+\\s*(.+)$");
    private static final Pattern ZONE_HEADER = Pattern.compile("(?i)^[^\\p{L}\\p{N}]*(zone|region|area|location)[^\\p{L}\\p{N}]*$");

    /**
     * The zone you are in, as the server's scoreboard shows it ("Zone: Spawn", or a "Zone" line with the name
     * on the line under it). Null when the scoreboard shows no zone.
     */
    public static String sidebarZone() {
        List<String> lines = sidebarLines();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).replaceAll("§.", "").trim();
            Matcher m = ZONE_LABEL.matcher(l);
            if (m.find()) {
                String v = m.group(2).replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N})]+$", "").trim();
                if (!v.isEmpty()) return v;
            }
            if (ZONE_HEADER.matcher(l).matches() && i + 1 < lines.size()) {
                String v = lines.get(i + 1).replaceAll("§.", "").replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N})]+$", "").trim();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    private static final Pattern CLAIM_OWNER = Pattern.compile("([.*~!+]?[A-Za-z0-9_]{2,16})\\s*['\u2019\u02BC`]\\s*s\\s+claim", Pattern.CASE_INSENSITIVE);

    /** The owner of the claim you stand in, from the scoreboard ("Clarissa273828's Claim"), or null. */
    public static String claimOwner() {
        for (String line : sidebarLines()) {
            String l = line.replaceAll("§.", "");
            Matcher m = CLAIM_OWNER.matcher(l);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** The scoreboard's title (the server name at the top of the sidebar), or null. */
    private static Component cachedTitle;
    private static long titleAt;
    private static Object titleLevel;

    /** Read a few times a second instead of every frame (it is looked up by reflection). */
    public static Component sidebarTitle() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (titleLevel == mc.level && now - titleAt < 300) return cachedTitle;
        titleAt = now;
        titleLevel = mc.level;
        cachedTitle = readSidebarTitle();
        return cachedTitle;
    }

    private static Component readSidebarTitle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        try {
            Object board = mc.level.getClass().getMethod("getScoreboard").invoke(mc.level);
            if (board == null) return null;
            for (java.lang.reflect.Method m : board.getClass().getMethods()) {
                if (!m.getName().equals("getDisplayObjective") || m.getParameterCount() != 1) continue;
                Class<?> slotType = m.getParameterTypes()[0];
                if (!slotType.isEnum()) continue;
                for (Object c : slotType.getEnumConstants()) {
                    if (!((Enum<?>) c).name().equals("SIDEBAR")) continue;
                    Object objective = m.invoke(board, c);
                    if (objective == null) return null;
                    Object title = objective.getClass().getMethod("getDisplayName").invoke(objective);
                    return title instanceof Component tc ? tc : null;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // no sidebar
        }
        return null;
    }
}
