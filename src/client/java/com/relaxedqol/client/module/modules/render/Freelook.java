package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Freelook: look around without turning your player. Bind a key; with "Hold Key" on it works while you hold the key
 * (like Zoom), otherwise the key toggles it. You keep walking / flying the way you were facing.
 * Third Person: the camera orbits behind you (pulled in when a wall is in the way). First Person: just your view turns.
 * Hooks: EntityMixin (mouse turns the camera instead of the player), CameraMixin (camera angle and position).
 */
public class Freelook extends Module {
    private final Setting<String> mode = addSetting(Setting.mode("Mode", "Third Person", "Third Person", "First Person"),
        "Third Person: the camera swings around behind you so you can see yourself and what is around you."
            + " First Person: only your view turns, the camera stays in your head");
    private final Setting<Double> distance = addSetting(new Setting<>("Distance", 4.0, 1.0, 12.0, 0.5),
        "Third Person: how far the camera is from your head (walls pull it closer)");
    private final Setting<Boolean> hold = addSetting(new Setting<>("Hold Key", true),
        "On: Freelook works while you hold its key and snaps back when you let go. Off: the key turns it on / off");
    private final Setting<Boolean> invertPitch = addSetting(new Setting<>("Invert Up/Down", false),
        "Moving the mouse up looks down (and the other way) while freelooking");

    /** No hand / held item in the way while you freelook. */
    private final Setting<Boolean> hideHand = addSetting(new Setting<>("Hide Hand", true),
        "Your hand and the item in it are hidden while you freelook");

    private float yaw;
    private float pitch;
    private boolean heldAtStart;

    public Freelook() {
        super("Freelook", "Hold a key to look around (3rd person) without turning your player", ModuleCategory.RENDER);
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        if (mc.player == null) {
            setEnabled(false);
            return;
        }
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        heldAtStart = getKey() >= 0 && InputConstants.isKeyDown(mc.getWindow(), getKey());
    }

    @Override
    public void onTick() {
        if (mc.player == null) {
            setEnabled(false);
            return;
        }
        // Hold mode: only while the bound key is held (when it was turned on with that key)
        if (hold.getValue() && heldAtStart && getKey() >= 0 && !InputConstants.isKeyDown(mc.getWindow(), getKey())) {
            setEnabled(false);
        }
    }

    /** True while the camera is free (the mixins check this every frame). */
    public boolean isLooking() {
        return isEnabled() && mc.player != null;
    }

    /** Mouse movement while freelooking (called instead of rotating the player). Same maths as vanilla. */
    public void turn(double xo, double yo) {
        yaw += (float) xo * 0.15f;
        float dy = (float) yo * 0.15f;
        pitch = Mth.clamp(pitch + (invertPitch.getValue() ? -dy : dy), -90.0f, 90.0f);
    }

    public float getYaw() {
        return yaw;
    }

    public boolean hidesHand() {
        return hideHand.getValue();
    }

    public float getPitch() {
        return pitch;
    }

    /** Where the camera goes this frame, or null to leave it in your head (First Person mode). */
    public Vec3 cameraPosition(float partialTicks) {
        if (mc.player == null || mc.level == null || !mode.getValue().equals("Third Person")) return null;
        Vec3 eye = mc.player.getEyePosition(partialTicks);
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        double want = distance.getValue();
        Vec3 wanted = eye.subtract(dir.scale(want));
        // A wall between your head and the camera: pull the camera in front of it
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, wanted, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            double d = Math.max(0.0, hit.getLocation().distanceTo(eye) - 0.3);
            return eye.subtract(dir.scale(d));
        }
        return wanted;
    }

    @Override
    public String getInfo() {
        return mode.getValue().equals("Third Person") ? "3rd" : "1st";
    }
}
