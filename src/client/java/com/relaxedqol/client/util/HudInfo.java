package com.relaxedqol.client.util;

/** What vanilla parts of the HUD are showing right now (filled in by optional mixins / HUD element wrappers). */
public final class HudInfo {
    private static volatile int bossBars;
    private static volatile long bossBarsAt;

    private HudInfo() {}

    public static void bossBarsDrawn(int count) {
        bossBars = count;
        bossBarsAt = System.currentTimeMillis();
    }

    /** Boss bars on screen (0 when none were drawn in the last moment). */
    public static int bossBars() {
        return System.currentTimeMillis() - bossBarsAt < 250 ? bossBars : 0;
    }

    /**
     * The GUI y just under the boss bars, as vanilla lays them out (first bar at y 12, one every 19 px), moved /
     * scaled like the BossBar module draws them. 2 when there are none.
     */
    public static int belowBossBars() {
        int n = bossBars();
        if (n == 0) return 2;
        int bottom = 12 + (n - 1) * 19 + 5;
        com.relaxedqol.client.module.modules.render.BossBar mover =
            com.relaxedqol.client.RelaxedQolClient.enabled(com.relaxedqol.client.module.modules.render.BossBar.class);
        if (mover != null) bottom = mover.transformedY(bottom);
        return bottom + 4;
    }
}
