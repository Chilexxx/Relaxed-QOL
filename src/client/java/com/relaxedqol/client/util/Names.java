package com.relaxedqol.client.util;

import java.util.regex.Pattern;

/**
 * What counts as a real player name.
 *
 * Java names are 3-16 letters, digits and _. Bedrock players who join through Geyser / Floodgate get a prefix in
 * front of their gamertag - "." by default (".Steve123"), some servers use "*", "~", "!" or "+" - and their name
 * can be shorter. Everything that tracks players (Radar, LocatorBar, PlayerAlert, the big radar window) uses this,
 * so Bedrock players are no longer thrown away as "NPCs" or "not a valid name".
 */
public final class Names {
    private Names() {}

    /** Floodgate prefixes seen in the wild. */
    public static final String BEDROCK_PREFIXES = ".*~!+";

    private static final Pattern JAVA = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** Prefix + gamertag (Floodgate replaces spaces with _ and cuts the whole name at 16, some setups don't). */
    private static final Pattern BEDROCK = Pattern.compile("[.*~!+][A-Za-z0-9_]{1,16}");

    /** A Java or a Bedrock (prefixed) player name. */
    public static boolean isPlayerName(String name) {
        if (name == null) return false;
        return JAVA.matcher(name).matches() || BEDROCK.matcher(name).matches();
    }

    /**
     * Tab-list entries that are not real players: the filler slots servers use to lay out their tab list
     * ("00009", "!00012"...) and NPC / hologram profiles. A real player has a letter in their name and a normal
     * account id (Bedrock players through Floodgate are recognised by their prefix).
     */
    private static java.util.Map<String, java.util.UUID> tabIds = java.util.Map.of();
    private static long tabIdsAt;
    private static Object tabIdsConnection;

    /**
     * The skin id of a name on the tab list, from a table rebuilt once a second (the radar, LocatorBar and world
     * map used to walk the whole tab list for every player, every frame). fallback when not on the tab list.
     */
    public static java.util.UUID tabId(String name, java.util.UUID fallback) {
        if (name == null) return fallback;
        var mc = net.minecraft.client.Minecraft.getInstance();
        var conn = mc.getConnection();
        long now = System.currentTimeMillis();
        if (conn == null) return fallback;
        if (conn != tabIdsConnection || now - tabIdsAt > 1000) {
            java.util.Map<String, java.util.UUID> m = new java.util.HashMap<>();
            for (net.minecraft.client.multiplayer.PlayerInfo info : conn.getOnlinePlayers()) {
                String n = info.getProfile().name();
                if (n != null) m.put(n.toLowerCase(java.util.Locale.ROOT), info.getProfile().id());
            }
            tabIds = m;
            tabIdsAt = now;
            tabIdsConnection = conn;
        }
        java.util.UUID id = tabIds.get(name.toLowerCase(java.util.Locale.ROOT));
        return id != null ? id : fallback;
    }

    /** Lower-case names on the tab list (the same once-a-second table). */
    public static java.util.Set<String> tabNames() {
        tabId("", null);
        return tabIds.keySet();
    }

    public static boolean isFakeEntry(String name, java.util.UUID id) {
        if (!isPlayerName(name)) return true;
        boolean letter = false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isLetter(name.charAt(i))) {
                letter = true;
                break;
            }
        }
        if (!letter) return true;
        if (id != null && !isBedrock(name)) {
            int v = id.version();
            if (v != 4 && v != 3) return true;
        }
        return false;
    }

    /** Lenient check for names the user types in (2 letters is fine, the server decides the rest). */
    public static boolean isTypedName(String name) {
        if (name == null) return false;
        return name.matches("[.*~!+]?[A-Za-z0-9_]{2,16}");
    }

    public static boolean isBedrock(String name) {
        return name != null && !name.isEmpty() && BEDROCK_PREFIXES.indexOf(name.charAt(0)) >= 0;
    }

    /** The regex fragment for a name inside a longer pattern (no groups). */
    public static final String REGEX = "[.*~!+]?[A-Za-z0-9_]{1,16}";
}
