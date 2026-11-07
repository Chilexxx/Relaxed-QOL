package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.mixin.OptionInstanceAccessor;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Zoom like a spyglass, without one. Bind a key: with "Hold Key" on it zooms while you hold the key and zooms back
 * out when you let go (otherwise the key toggles it). Smooth in/out, lower mouse sensitivity while zoomed so aiming
 * stays steady, optional cinematic camera.
 *
 * Works by lowering the field of view for a moment (and putting it back exactly). It never stays changed: it is put
 * back as soon as you stop zooming, open any menu, or leave the world.
 */
public class Zoom extends Module {
    private final Setting<Double> factor = addSetting(new Setting<>("Zoom", 4.0, 1.5, 10.0, 0.5));
    private final Setting<Boolean> hold = addSetting(new Setting<>("Hold Key", true));
    // Off by default (snaps in and out). Saved under a new name so an old saved "on" does not carry over.
    private final Setting<Boolean> smooth = addSetting(new Setting<>("Smooth Zoom", false),
        "Glide in and out (Speed sets how fast). Off = zoom snaps in and out instantly");
    /** How fast it glides in/out: 1 = the old slow glide (~0.6 s), 10 = snappy (~0.12 s). Always smooth. */
    private final Setting<Integer> speed = addSetting(new Setting<>("Speed", 7, 1, 10, 1));
    private final Setting<Boolean> lowerSensitivity = addSetting(new Setting<>("Lower Sensitivity", true));
    private final Setting<Boolean> cinematic = addSetting(new Setting<>("Cinematic Camera", false));
    private final Setting<Boolean> scrollZoom = addSetting(new Setting<>("Scroll To Zoom", true),
        "While zoomed, the mouse wheel zooms further in / out (up to 40x) instead of switching hotbar slots."
            + " Starts again from the Zoom value each time you zoom");

    /** Zoom picked with the mouse wheel during this zoom, or -1 = the Zoom setting. */
    private double wheelZoom = -1;

    /** Current zoom (1 = none) and its speed: a critically damped spring, so it eases in AND out (no jolt). */
    private double current = 1.0;
    private double velocity = 0.0;
    private long lastFrame;
    private Integer savedFov;
    private Double savedSensitivity;
    private boolean savedSmooth;
    private boolean sensitivityChanged;
    private boolean heldAtStart;

    /** Zoom amount the FOV hook divides by (AbstractClientPlayerMixin). */
    private static volatile float DIVISOR = 1f;
    /** When the FOV hook last ran: if it works, the FOV setting is never touched. */
    private static volatile long hookSeenAt;

    public static float divisor() {
        return DIVISOR;
    }

    public static void hookSeen() {
        hookSeenAt = System.currentTimeMillis();
    }

    private static boolean hookWorks() {
        return System.currentTimeMillis() - hookSeenAt < 2000;
    }

    public Zoom() {
        super("Zoom", "Zooms in while you hold its key (quick and smooth, lower sensitivity while zoomed)", ModuleCategory.RENDER);
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        heldAtStart = getKey() >= 0 && InputConstants.isKeyDown(mc.getWindow(), getKey());
        wheelZoom = -1;
    }

    /**
     * Mouse wheel (ScrollHook, before the game sees it). Returns true when it zoomed, so the hotbar does not scroll.
     */
    public boolean onScroll(double amount) {
        if (!isEnabled() || !scrollZoom.getValue() || mc.player == null || mc.gui.screen() != null || amount == 0) return false;
        double base = wheelZoom > 0 ? wheelZoom : factor.getValue();
        base *= Math.pow(1.2, Math.signum(amount) * Math.min(3, Math.abs(amount)));
        wheelZoom = Math.max(1.1, Math.min(40.0, base));
        return true;
    }

    @Override
    public void onTick() {
        // Hold mode: zoom only while the bound key is held (when it was turned on with that key)
        if (hold.getValue() && heldAtStart && getKey() >= 0 && !InputConstants.isKeyDown(mc.getWindow(), getKey())) {
            setEnabled(false);
        }
        if (mc.player == null) setEnabled(false);
    }

    /** Called every frame and every tick (even while off, so it can zoom back out smoothly and restore). */
    public void frame() {
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0.016 : Math.min(0.05, (now - lastFrame) / 1.0E9);
        lastFrame = now;
        if (dt <= 0) return;

        boolean menuOpen = mc.gui.screen() != null;
        boolean active = isEnabled() && mc.player != null && !menuOpen;
        double target = active ? (wheelZoom > 0 ? wheelZoom : factor.getValue()) : 1.0;
        if (!smooth.getValue()) {
            current = target;
            velocity = 0;
        } else {
            // Critically damped spring, stepped with its exact solution: quick, but it eases in and settles without
            // overshoot, and stays perfectly stable at any frame rate (the old Euler step needed a soft spring).
            // omega: 9.4 at Speed 1 (the old feel) .. 40 at Speed 10.
            double omega = 6.0 + speed.getValue() * 3.4;
            double d = current - target;
            double a = velocity + omega * d;
            double e = Math.exp(-omega * dt);
            current = target + (d + a * dt) * e;
            velocity = (velocity - omega * a * dt) * e;
            if (Math.abs(target - current) < 0.002 && Math.abs(velocity) < 0.01) {
                current = target;
                velocity = 0;
            }
        }
        if (current < 1.0) current = 1.0;

        // Smooth off: snap. The FOV multiplier hook is always eased by the game itself (that is why turning Smooth
        // off used to change nothing), so snapping goes through the FOV setting instead - applied instantly.
        if (hookWorks() && smooth.getValue()) {
            // Smooth path: only the FOV multiplier changes (fractional, eased by the game every frame)
            restoreFov();
            DIVISOR = (float) current;
            applySensitivity(current > 1.001 && !menuOpen);
        } else {
            DIVISOR = 1f;
            if (menuOpen) current = 1.0; // the fallback changes the real FOV setting: never leave it changed in a menu
            if (current > 1.001) applyFov();
            else restoreFov();
            applySensitivity(current > 1.001);
        }
    }

    private void applySensitivity(boolean zoomed) {
        if (zoomed) {
            if (!sensitivityChanged) {
                savedSensitivity = mc.options.sensitivity().get();
                savedSmooth = mc.options.smoothCamera;
                sensitivityChanged = true;
            }
            if (lowerSensitivity.getValue()) {
                ((OptionInstanceAccessor) (Object) mc.options.sensitivity()).relaxed$setRawValue(savedSensitivity / Math.pow(current, 0.8));
            }
            mc.options.smoothCamera = savedSmooth || (cinematic.getValue() && isEnabled());
        } else if (sensitivityChanged) {
            ((OptionInstanceAccessor) (Object) mc.options.sensitivity()).relaxed$setRawValue(savedSensitivity);
            mc.options.smoothCamera = savedSmooth;
            sensitivityChanged = false;
        }
    }

    /** Fallback when the FOV hook is not available: whole-degree FOV setting (less smooth). */
    private void applyFov() {
        if (savedFov == null) savedFov = mc.options.fov().get();
        int fov = (int) Math.max(1, Math.round(savedFov / current));
        ((OptionInstanceAccessor) (Object) mc.options.fov()).relaxed$setRawValue(fov);
    }

    private void restoreFov() {
        if (savedFov == null) return;
        ((OptionInstanceAccessor) (Object) mc.options.fov()).relaxed$setRawValue(savedFov);
        savedFov = null;
    }

    @Override
    public String getInfo() {
        return String.format(java.util.Locale.ROOT, "%.1fx", wheelZoom > 0 && isEnabled() ? wheelZoom : factor.getValue());
    }
}
