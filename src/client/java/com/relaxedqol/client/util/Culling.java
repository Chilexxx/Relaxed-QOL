package com.relaxedqol.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * "FPS Boost" (Settings, on by default) - smart culling like Lunar / EntityCulling: entities and block
 * entities (chests, signs, heads, banners, item frames, armor stands, NPCs...) that are completely hidden behind
 * solid blocks are not drawn at all. Spawns and bases are full of them, and drawing things nobody can see is where
 * most of the frames go there. What you CAN see looks exactly the same:
 * - a thing counts as hidden only when every line from your eyes to several points of it is blocked by a full,
 *   solid block (glass, leaves, slabs, fences... never hide anything);
 * - close things (4 blocks), big things, players (their name tags show through walls), glowing entities, anything
 *   with a visible name and floating texts are never culled, and nothing is culled while your camera is inside a
 *   block (spectator / suffocating);
 * - results are kept a few frames (a visible thing is re-checked quickly), so it costs far less than it saves.
 */
public final class Culling {
    private Culling() {}

    /** key -> {checked at (ms), 1 = hidden / 0 = visible}. */
    private static final Map<Integer, long[]> ENTITIES = new HashMap<>();
    private static final Map<Long, long[]> BLOCKS = new HashMap<>();
    /** Spawn spots of particles: block -> {checked at, hidden}. */
    private static final Map<Long, long[]> PARTICLES = new HashMap<>();
    private static Object level;
    private static long lastPrune;
    private static final double MIN_DIST = 4.0;
    private static final long KEEP_VISIBLE_MS = 300;
    private static final long KEEP_HIDDEN_MS = 50;
    private static final long KEEP_HIDDEN_BLOCK_MS = 100;
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

    private static java.lang.reflect.Method glowing;
    private static java.lang.reflect.Method showsName;
    private static boolean looked;

    private static boolean enabled() {
        return com.relaxedqol.client.module.modules.client.ClickGUI.fpsBoost();
    }

    private static Vec3 eye(Minecraft mc) {
        try {
            return mc.gameRenderer.mainCamera().position();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void housekeeping(Minecraft mc, long now) {
        if (level != mc.level) {
            level = mc.level;
            ENTITIES.clear();
            BLOCKS.clear();
            PARTICLES.clear();
        }
        if (now - lastPrune > 5000) {
            lastPrune = now;
            ENTITIES.values().removeIf(v -> now - v[0] > 5000);
            BLOCKS.values().removeIf(v -> now - v[0] > 5000);
            PARTICLES.values().removeIf(v -> now - v[0] > 2000);
        }
    }

    /** Whether an entity (already inside your view) is completely hidden and can be skipped. */
    public static boolean entityHidden(Entity e) {
        long t0 = Perf.start();
        try {
            return entityHidden$timed(e);
        } finally {
            Perf.end(Perf.CULLING, t0);
        }
    }

    private static boolean entityHidden$timed(Entity e) {
        if (!enabled() || e == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || e == mc.getCameraEntity() || e == mc.player) return false;
        if (e instanceof net.minecraft.world.entity.player.Player) return false;
        if (e.getClass().getSimpleName().contains("TextDisplay")) return false;
        if (special(e)) return false;
        AABB box = e.getBoundingBox();
        if (box.getXsize() > 3 || box.getYsize() > 4 || box.getZsize() > 3) return false;
        Vec3 eye = eye(mc);
        if (eye == null || insideBlock(mc, eye)) return false;
        Vec3 c = box.getCenter();
        if (c.distanceToSqr(eye) < MIN_DIST * MIN_DIST) return false;
        long now = System.currentTimeMillis();
        housekeeping(mc, now);
        long[] cached = ENTITIES.get(e.getId());
        if (cached != null && now - cached[0] < (cached[1] == 1 ? KEEP_HIDDEN_MS : KEEP_VISIBLE_MS)) return cached[1] == 1;
        double x0 = box.minX + 0.05, x1 = box.maxX - 0.05, y0 = box.minY + 0.05, y1 = box.maxY - 0.05, z0 = box.minZ + 0.05, z1 = box.maxZ - 0.05;
        boolean hidden = !visible(mc, eye, c.x, c.y, c.z) && !visible(mc, eye, c.x, y1, c.z) && cornersHidden(mc, eye, x0, y0, z0, x1, y1, z1);
        ENTITIES.put(e.getId(), new long[]{now, hidden ? 1 : 0});
        return hidden;
    }

    /** Whether a block entity at pos (chest, sign, head...) is completely hidden and can be skipped. */
    public static boolean blockHidden(BlockPos pos, Object state) {
        long t0 = Perf.start();
        try {
            return blockHidden$timed(pos, state);
        } finally {
            Perf.end(Perf.CULLING, t0);
        }
    }

    private static boolean blockHidden$timed(BlockPos pos, Object state) {
        if (!enabled() || pos == null) return false;
        if (state != null) {
            String n = state.getClass().getSimpleName();
            // Beams that are seen over walls
            if (n.contains("Beacon") || n.contains("Gateway") || n.contains("EndPortal")) return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        Vec3 eye = eye(mc);
        if (eye == null || insideBlock(mc, eye)) return false;
        double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
        double dx = cx - eye.x, dy = cy - eye.y, dz = cz - eye.z;
        if (dx * dx + dy * dy + dz * dz < MIN_DIST * MIN_DIST) return false;
        long now = System.currentTimeMillis();
        housekeeping(mc, now);
        long key = pos.asLong();
        long[] cached = BLOCKS.get(key);
        if (cached != null && now - cached[0] < (cached[1] == 1 ? KEEP_HIDDEN_BLOCK_MS : KEEP_VISIBLE_MS)) return cached[1] == 1;
        double a = 0.08, b = 0.92;
        boolean hidden = !visible(mc, eye, cx, cy, cz)
            && cornersHidden(mc, eye, pos.getX() + a, pos.getY() + a, pos.getZ() + a, pos.getX() + b, pos.getY() + b, pos.getZ() + b);
        BLOCKS.put(key, new long[]{now, hidden ? 1 : 0});
        return hidden;
    }

    /** All 8 corners of the box are out of sight (stops at the first one that can be seen). */
    private static boolean cornersHidden(Minecraft mc, Vec3 eye, double x0, double y0, double z0, double x1, double y1, double z1) {
        for (int i = 0; i < 8; i++) {
            if (visible(mc, eye, (i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1)) return false;
        }
        return true;
    }

    /**
     * A particle about to be made at (x, y, z) where you cannot possibly see it - every line from your eyes to
     * its spot AND to the spot 3 blocks above it (smoke and the like rise) is blocked by full, solid blocks. Such
     * particles are simply not made: nothing visible changes, and spawns / farms full of particle effects behind
     * walls stop costing frames. Never within 8 blocks, never for long-travelling effects (fireworks, explosions,
     * beams...). The answer is kept a moment per block, so a fountain of particles costs one check.
     */
    public static boolean particleHidden(net.minecraft.core.particles.ParticleOptions options, double x, double y, double z) {
        if (!enabled()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        Vec3 eye = eye(mc);
        if (eye == null) return false;
        double dx = x - eye.x, dy = y - eye.y, dz = z - eye.z;
        if (dx * dx + dy * dy + dz * dz < 64) return false;
        if (travels(options)) return false;
        if (insideBlock(mc, eye)) return false;
        long now = System.currentTimeMillis();
        housekeeping(mc, now);
        long key = BlockPos.asLong((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        long[] cached = PARTICLES.get(key);
        if (cached != null && now - cached[0] < (cached[1] == 1 ? 150 : 400)) return cached[1] == 1;
        boolean hidden = !visible(mc, eye, x, y, z) && !visible(mc, eye, x, y + 3, z);
        if (PARTICLES.size() > 20000) PARTICLES.clear();
        PARTICLES.put(key, new long[]{now, hidden ? 1 : 0});
        return hidden;
    }

    private static final Map<Object, Boolean> TRAVELS = new java.util.IdentityHashMap<>();

    /** Effects that fly far from where they start or are seen from far away. */
    private static boolean travels(net.minecraft.core.particles.ParticleOptions options) {
        if (options == null) return true;
        Object type = options.getType();
        if (type == null) return true;
        Boolean known = TRAVELS.get(type);
        if (known != null) return known;
        String id = "";
        try {
            Object key = net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getKey((net.minecraft.core.particles.ParticleType<?>) type);
            id = String.valueOf(key);
        } catch (RuntimeException | LinkageError e) {
            // unknown: treat as travelling
        }
        boolean far = id.isEmpty() || id.contains("firework") || id.contains("explosion") || id.contains("sonic") || id.contains("flash")
            || id.contains("end_rod") || id.contains("dragon") || id.contains("campfire") || id.contains("totem") || id.contains("vibration")
            || id.contains("trail") || id.contains("elder_guardian") || id.contains("shriek") || id.contains("gust");
        TRAVELS.put(type, far);
        return far;
    }

    /** Glowing entities and ones whose name shows (both are drawn through walls by the game). */
    private static boolean special(Entity e) {
        if (!looked) {
            looked = true;
            try {
                glowing = Entity.class.getMethod("isCurrentlyGlowing");
            } catch (NoSuchMethodException ignored) {
                glowing = null;
            }
            try {
                showsName = Entity.class.getMethod("shouldShowName");
            } catch (NoSuchMethodException ignored) {
                showsName = null;
            }
        }
        // Not sure (the game named these differently): better to draw it
        if (glowing == null || showsName == null) return true;
        try {
            return Boolean.TRUE.equals(glowing.invoke(e)) || Boolean.TRUE.equals(showsName.invoke(e));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return true;
        }
    }

    private static boolean insideBlock(Minecraft mc, Vec3 eye) {
        POS.set((int) Math.floor(eye.x), (int) Math.floor(eye.y), (int) Math.floor(eye.z));
        return mc.level.getBlockState(POS).isSolidRender();
    }

    /**
     * Walks the blocks on the straight line from the eye to (tx, ty, tz) (3D DDA). False as soon as a full, solid
     * block is in the way; the target's own block does not count.
     */
    private static boolean visible(Minecraft mc, Vec3 eye, double tx, double ty, double tz) {
        double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
        int x = (int) Math.floor(eye.x), y = (int) Math.floor(eye.y), z = (int) Math.floor(eye.z);
        int ex = (int) Math.floor(tx), ey = (int) Math.floor(ty), ez = (int) Math.floor(tz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
        double tmx = dx == 0 ? Double.MAX_VALUE : (dx > 0 ? (x + 1 - eye.x) : (eye.x - x)) * tdx;
        double tmy = dy == 0 ? Double.MAX_VALUE : (dy > 0 ? (y + 1 - eye.y) : (eye.y - y)) * tdy;
        double tmz = dz == 0 ? Double.MAX_VALUE : (dz > 0 ? (z + 1 - eye.z) : (eye.z - z)) * tdz;
        for (int steps = 0; steps < 600; steps++) {
            if (tmx < tmy && tmx < tmz) {
                if (tmx > 1) return true;
                x += sx;
                tmx += tdx;
            } else if (tmy < tmz) {
                if (tmy > 1) return true;
                y += sy;
                tmy += tdy;
            } else {
                if (tmz > 1) return true;
                z += sz;
                tmz += tdz;
            }
            if (x == ex && y == ey && z == ez) return true;
            POS.set(x, y, z);
            if (mc.level.getBlockState(POS).isSolidRender()) return false;
        }
        return true;
    }
}
