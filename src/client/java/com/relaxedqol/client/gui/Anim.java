package com.relaxedqol.client.gui;

import java.util.HashMap;
import java.util.Map;

/**
 * Frame-rate independent smoothing for UI animations.
 *
 * Values are stored in small mutable cells, so animating does not box a new Float into the map every frame for
 * every row (that churned thousands of objects per second while the menu was open). Use stable key objects
 * (a module, a setting, a cached key) rather than building "prefix:" + name strings each frame.
 */
public final class Anim {
    private static final class Cell {
        float v;

        Cell(float v) {
            this.v = v;
        }
    }

    private final Map<Object, Cell> values = new HashMap<>();
    private long lastNanos = System.nanoTime();
    private float delta = 0;

    /** Call once per frame before using {@link #approach}. */
    public void tick() {
        long now = System.nanoTime();
        // The theme's Animation % speeds every UI animation up or down (0 % = no animations at all)
        float speed = Theme.ANIM;
        delta = speed <= 0.01f ? 10f : Math.min(0.1f * Math.max(1f, speed), (now - lastNanos) / 1_000_000_000f * speed);
        lastNanos = now;
    }

    private Cell cell(Object key, float initial) {
        Cell c = values.get(key);
        if (c == null) {
            c = new Cell(initial);
            values.put(key, c);
        }
        return c;
    }

    /** Moves the value stored under key towards target. speed = how many units per second. */
    public float approach(Object key, float target, float speed) {
        Cell c = cell(key, target);
        float step = speed * delta;
        if (Math.abs(target - c.v) <= step) c.v = target;
        else c.v += Math.signum(target - c.v) * step;
        return c.v;
    }

    /** Smooth ease-out: fast at first, slows near the target. rate ~ 10-30 (higher = snappier). */
    public float smooth(Object key, float target, float rate) {
        Cell c = cell(key, target);
        if (c.v == target) return target;
        c.v += (target - c.v) * (1 - (float) Math.exp(-rate * delta));
        if (Math.abs(target - c.v) < 0.002f) c.v = target;
        return c.v;
    }

    public void set(Object key, float value) {
        cell(key, value).v = value;
    }

    public float get(Object key, float fallback) {
        Cell c = values.get(key);
        return c != null ? c.v : fallback;
    }
}
