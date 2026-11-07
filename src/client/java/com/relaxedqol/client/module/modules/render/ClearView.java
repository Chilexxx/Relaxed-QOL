package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;

/**
 * "Clear View" (Lunar-style): a calmer screen. The fire on your screen lower (or gone), no screen shake when
 * you get hit, no blue underwater overlay, and optionally no fog. Purely visual; through optional mixins (a game
 * version that renamed things simply shows the normal view).
 */
public class ClearView extends Module {
    private final Setting<String> fire = addSetting(Setting.mode("Fire Overlay", "Low", "Normal", "Low", "Off"),
        "The flames on your screen while you burn: Low pushes them down so you can see, Off hides them");
    /** How far Low pushes the flames down (bigger = lower, more of the screen clear). */
    private final Setting<Integer> fireHeight = addSetting(new Setting<>("Fire Lower By", 45, 10, 60, 5)
        .showIf(() -> this.fire.getValue().equals("Low")), "Fire Overlay Low: how far the flames are pushed down");
    private final Setting<Boolean> hurtShake = addSetting(new Setting<>("No Hurt Shake", true),
        "The screen no longer tilts / shakes when you take damage");
    private final Setting<Boolean> water = addSetting(new Setting<>("No Water Overlay", true),
        "No blue tint texture over the screen when you are underwater");
    private final Setting<Boolean> clearWater = addSetting(new Setting<>("Clear Water", true),
        "Underwater you can see far instead of a few blocks into murky blue");
    private final Setting<Boolean> fog = addSetting(new Setting<>("No Fog", true),
        "Removes the world fog: the haze far away and the overworld's grey mist (also in rain)");

    private static ClearView instance;

    public ClearView() {
        super("ClearView", "Lower fire, no hurt shake, clear water, no fog", ModuleCategory.RENDER);
        instance = this;
        // Changing a ClearView setting while the module is off switches it on (the settings only work while it
        // is on - that is why they seemed to do nothing)
        for (Setting<?> setting : java.util.List.of(fire, fireHeight, hurtShake, water, clearWater, fog)) {
            setting.onChange(() -> {
                if (!isEnabled() && RelaxedQolClient.moduleManager != null && mc.player != null) {
                    setEnabled(true);
                    com.relaxedqol.client.util.ChatUtil.send("§aClearView §7switched on (its settings only work while it is on)");
                }
            });
        }
    }

    // ------------------------------------------------------------------ is the fire hook reached at all?

    private static volatile long fireHookAt;
    private long burningSince;
    private boolean fireWarned;

    /** From the mixin: the game just drew (or was about to draw) the fire on the screen through our hook. */
    public static void fireHookSeen() {
        fireHookAt = System.currentTimeMillis();
    }

    @Override
    public void onTick() {
        if (mc.player == null || fireWarned || fireMode() == 0) {
            burningSince = 0;
            return;
        }
        long now = System.currentTimeMillis();
        boolean burning = mc.player.isOnFire() && mc.options.getCameraType().isFirstPerson();
        if (!burning) {
            burningSince = 0;
            return;
        }
        if (burningSince == 0) burningSince = now;
        // Burning for 3 s and the fire was never drawn through our hook: this game build draws it differently
        if (now - burningSince > 3000 && now - fireHookAt > 3000) {
            fireWarned = true;
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("ClearView: the fire overlay hook was not reached in this game build");
            com.relaxedqol.client.util.ChatUtil.send("§eClearView§7: this game build draws the fire differently - Fire Overlay"
                + " can't change it here. Please send §flogs/latest.log§7 so it can be fixed.");
        }
    }

    private static ClearView active() {
        ClearView c = instance;
        return c != null && c.isEnabled() && RelaxedQolClient.moduleManager != null ? c : null;
    }

    /** 0 = normal, 1 = low, 2 = off. */
    public static int fireMode() {
        ClearView c = active();
        if (c == null) return 0;
        return switch (c.fire.getValue()) {
            case "Low" -> 1;
            case "Off" -> 2;
            default -> 0;
        };
    }

    /** How much further down the fire is drawn (Low). pushed down much further (it was barely visible). */
    public static float fireOffset() {
        if (fireMode() != 1) return 0f;
        ClearView c = active();
        return c == null ? 0f : c.fireHeight.getValue() / 100f;
    }

    public static boolean noHurtShake() {
        ClearView c = active();
        return c != null && c.hurtShake.getValue();
    }

    public static boolean noWaterOverlay() {
        ClearView c = active();
        return c != null && c.water.getValue();
    }

    public static boolean clearWater() {
        ClearView c = active();
        return c != null && c.clearWater.getValue();
    }

    /** Whether the main way of lowering the fire is reached in this game build (then the fallback stays off). */
    private static volatile long fireConstantAt;

    public static void fireConstantSeen() {
        fireConstantAt = System.currentTimeMillis();
    }

    public static boolean fireConstantWorks() {
        return System.currentTimeMillis() - fireConstantAt < 3000;
    }

    public static boolean noFog() {
        ClearView c = active();
        return c != null && c.fog.getValue();
    }

    @Override
    public String getInfo() {
        return fire.getValue().equals("Normal") ? null : "Fire " + fire.getValue();
    }
}
