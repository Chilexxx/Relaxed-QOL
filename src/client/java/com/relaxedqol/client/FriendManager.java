package com.relaxedqol.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Friend list. Names are stored case-insensitively and saved by ConfigManager. */
public final class FriendManager {
    private static final Set<String> FRIENDS = new LinkedHashSet<>();

    private FriendManager() {}

    public static boolean isFriend(String name) {
        return name != null && FRIENDS.contains(name.toLowerCase(Locale.ROOT));
    }

    public static boolean add(String name) {
        boolean added = FRIENDS.add(name.toLowerCase(Locale.ROOT));
        if (added) ConfigManager.saveFriends();
        return added;
    }

    public static boolean remove(String name) {
        boolean removed = FRIENDS.remove(name.toLowerCase(Locale.ROOT));
        if (removed) ConfigManager.saveFriends();
        return removed;
    }

    public static void clear() {
        FRIENDS.clear();
        ConfigManager.saveFriends();
    }

    public static List<String> getFriends() {
        return Collections.unmodifiableList(new ArrayList<>(FRIENDS));
    }

    /** Used by ConfigManager when loading; does not trigger a save. */
    static void setAll(List<String> names) {
        FRIENDS.clear();
        for (String name : names) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) FRIENDS.add(trimmed.toLowerCase(Locale.ROOT));
        }
    }
}
