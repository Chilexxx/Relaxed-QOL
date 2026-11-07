package com.relaxedqol.client.util;

/**
 * Where the scoreboard (sidebar) was drawn this frame. While the vanilla sidebar draws, every plain fill it makes
 * (its title bar and its background) is measured through GuiGraphicsExtractorMixin, so LoverFella Mode knows exactly
 * where its top-left corner is, whatever the server puts in it.
 */
public final class ScoreboardBounds {
    private ScoreboardBounds() {}

    private static boolean capturing;
    private static int minX;
    private static int minY;
    private static int maxX;
    private static int maxY;
    private static int fills;

    public static void begin() {
        capturing = true;
        fills = 0;
        minX = Integer.MAX_VALUE;
        minY = Integer.MAX_VALUE;
        maxX = Integer.MIN_VALUE;
        maxY = Integer.MIN_VALUE;
    }

    public static void end() {
        capturing = false;
    }

    /** From the mixin: a fill(x0, y0, x1, y1, colour) call. */
    public static void onFill(int x0, int y0, int x1, int y1) {
        if (!capturing) return;
        fills++;
        minX = Math.min(minX, Math.min(x0, x1));
        minY = Math.min(minY, Math.min(y0, y1));
        maxX = Math.max(maxX, Math.max(x0, x1));
        maxY = Math.max(maxY, Math.max(y0, y1));
    }

    /** {x, y, w, h} of the sidebar drawn just now, or null when nothing was drawn (no scoreboard, F1...). */
    public static int[] last() {
        if (fills == 0 || maxX <= minX || maxY <= minY) return null;
        return new int[]{minX, minY, maxX - minX, maxY - minY};
    }
}
