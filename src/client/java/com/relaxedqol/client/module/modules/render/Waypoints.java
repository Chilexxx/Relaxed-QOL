package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.ActionSetting;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.ChatUtil;
import com.relaxedqol.client.waypoint.WaypointManager;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Waypoints with a beam, name and distance, visible through walls from any distance. A waypoint is added
 * automatically where you die. Add your own with ".waypoint add <name>" (or the button), list/remove with
 * ".waypoint list/remove". Nether coordinates: overworld points are shown at /8 in the nether and the other way.
 */
public class Waypoints extends Module {
    private final Setting<Boolean> deaths = addSetting(new Setting<>("Death Points", true));
    private final Setting<Integer> keepDeaths = addSetting(new Setting<>("Keep Deaths", 5, 1, 50, 1));
    private final Setting<Boolean> beams = addSetting(new Setting<>("Beams", true));
    private final Setting<Boolean> tracers = addSetting(new Setting<>("Tracers", false));
    private final Setting<Boolean> crossDimension = addSetting(new Setting<>("Nether/Overworld", true));
    private final Setting<Double> textSize = addSetting(new Setting<>("Text Size", 1.0, 0.5, 3.0, 0.1));

    private boolean wasDead;

    public Waypoints() {
        super("Waypoints", "Waypoints with beams and distance; adds one where you die", ModuleCategory.RENDER);
        addSetting(new ActionSetting("Add Here", () -> {
            if (mc.player == null) return;
            String name = "Point " + (WaypointManager.forServer().size() + 1);
            WaypointManager.add(name, mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), false);
            ChatUtil.send("§aAdded waypoint §f" + name + " §7(rename: .waypoint add <name>)");
        }));
        addSetting(new ActionSetting("Remove Nearest", () -> {
            String removed = WaypointManager.removeNearest();
            ChatUtil.send(removed != null ? "§cRemoved nearest waypoint §f" + removed : "§7No waypoints to remove");
        }));
        addSetting(new ActionSetting("Clear Death Points", () -> ChatUtil.send("§7Removed §f" + WaypointManager.clearDeaths() + " §7death points")));
        addSetting(new ActionSetting("Open Menu", this::openMenu),
            "The waypoint menu: every waypoint with its colour, coordinates and distance - add, edit, recolour, hide, delete");
        setEnabled(true);
    }

    /** Opens the waypoint menu (also with the Menu Key, any time). */
    private final com.relaxedqol.client.setting.KeySetting menuKey = addSetting(
        new com.relaxedqol.client.setting.KeySetting("Menu Key", -1, true), "Opens the waypoint menu (works while Waypoints is off too)");
    private boolean menuKeyWas;

    public void openMenu() {
        if (mc.player == null) return;
        mc.gui.setScreen(new com.relaxedqol.client.gui.WaypointsScreen(mc.gui.screen()));
    }

    /** Every tick, also while off: the Menu Key. */
    public void keyTick() {
        int key = menuKey.getKey();
        boolean down = key >= 0 && mc.getWindow() != null && com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), key);
        if (down && !menuKeyWas && mc.gui.screen() == null && mc.player != null) openMenu();
        menuKeyWas = down;
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        boolean dead = mc.player.isDeadOrDying() || mc.gui.screen() instanceof DeathScreen;
        if (dead && !wasDead && deaths.getValue()) {
            String time = new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(new java.util.Date());
            // The zone too, as the server's scoreboard shows it
            String zone = com.relaxedqol.client.util.TabInfo.sidebarZone();
            WaypointManager.add("Death " + time + (zone != null ? " (" + zone + ")" : ""), mc.player.getBlockX(),
                mc.player.getBlockY(), mc.player.getBlockZ(), true);
            WaypointManager.trimDeaths(keepDeaths.getValue());
            ChatUtil.send(String.format(Locale.ROOT, "§cYou died at §f%d %d %d", mc.player.getBlockX(), mc.player.getBlockY(),
                mc.player.getBlockZ()) + (zone != null ? " §cin §f" + zone : "") + " §7(waypoint added)");
        }
        wasDead = dead;
    }

    @Override
    public void onRender3D(float partialTicks) {
        if (mc.player == null || mc.level == null) return;
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 cam = camera.position();
        Vec3 tracerStart = cam.add(Vec3.directionFromRotation(camera.xRot(), camera.yRot()));
        String dim = WaypointManager.currentDimension();
        for (WaypointManager.Waypoint w : WaypointManager.forServer()) {
            if (!w.visible()) continue;
            double x = w.x() + 0.5;
            double y = w.y();
            double z = w.z() + 0.5;
            if (!w.dimension().equals(dim)) {
                if (!crossDimension.getValue()) continue;
                boolean toNether = w.dimension().endsWith("overworld") && dim.endsWith("the_nether");
                boolean toOverworld = w.dimension().endsWith("the_nether") && dim.endsWith("overworld");
                if (toNether) {
                    x /= 8;
                    z /= 8;
                } else if (toOverworld) {
                    x *= 8;
                    z *= 8;
                } else {
                    continue;
                }
            }
            Vec3 point = new Vec3(x, y, z);
            double dist = point.distanceTo(cam);
            // Very far points are drawn closer along the same direction so they stay visible
            Vec3 shown = dist > 200 ? cam.add(point.subtract(cam).normalize().scale(200)) : point;
            double scaleFactor = dist > 200 ? 200 / dist : 1;
            int color = w.color();
            if (beams.getValue()) {
                Gizmos.line(shown, shown.add(0, 60 * scaleFactor, 0), color, 2.5f).setAlwaysOnTop();
                Gizmos.cuboid(new AABB(shown.subtract(0.5 * scaleFactor, 0, 0.5 * scaleFactor), shown.add(0.5 * scaleFactor, scaleFactor, 0.5 * scaleFactor)),
                    GizmoStyle.strokeAndFill(color, 2f, (color & 0xFFFFFF) | 0x30000000)).setAlwaysOnTop();
            }
            if (tracers.getValue()) Gizmos.line(tracerStart, shown, color, 1.2f).setAlwaysOnTop();
            float scale = (float) (0.3 * textSize.getValue() * Math.max(1.0, Math.min(dist, 200) / 10.0));
            String label = w.name() + " §7[" + Math.round(dist) + "m]";
            Gizmos.billboardText(label, shown.add(0, scaleFactor + scale * 1.2, 0), new TextGizmo.Style(color, scale, OptionalDouble.empty()))
                .setAlwaysOnTop();
        }
    }

    @Override
    public String getInfo() {
        return String.valueOf(WaypointManager.forServer().size());
    }
}
