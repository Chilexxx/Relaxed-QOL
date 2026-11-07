package com.relaxedqol.client.util;

import com.relaxedqol.client.module.Module;

/**
 * Whether the client's look should be hidden right now (HUD, cards, waypoints...). Nothing in this client hides it,
 * so both answers are always "no"; the checks stay so a future feature can use them.
 */
public final class Privacy {
    private Privacy() {}

    public static boolean hidden() {
        return false;
    }

    public static boolean hides(Module module) {
        return false;
    }
}
