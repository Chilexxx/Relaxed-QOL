package com.relaxedqol.client.util;

import net.minecraft.client.Minecraft;

/**
 * "is this a Nether-like world" for the minimap and the surface checks. Servers often run their Nether as its
 * own world with its own name (e.g. "megasurvival_nether"), which is not the vanilla Level.NETHER key - so the
 * minimap drew the bedrock roof (one grey square) instead of the caves. A world counts as Nether-like when it is the
 * vanilla Nether, its name says "nether", or its dimension type has a ceiling.
 */
public final class Dims {
    private static Object cachedLevel;
    private static boolean cachedCeiling;

    private Dims() {}

    public static boolean ceiling() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        if (cachedLevel == mc.level) return cachedCeiling;
        cachedLevel = mc.level;
        cachedCeiling = compute(mc);
        return cachedCeiling;
    }

    private static boolean compute(Minecraft mc) {
        if (mc.level.dimension() == net.minecraft.world.level.Level.NETHER) return true;
        try {
            String id = mc.level.dimension().identifier().toString().toLowerCase(java.util.Locale.ROOT);
            if (id.contains("nether") || id.contains("hell")) return true;
        } catch (RuntimeException | LinkageError ignored) {
            // no name
        }
        try {
            Object type = mc.level.getClass().getMethod("dimensionType").invoke(mc.level);
            if (type != null) {
                Object v = type.getClass().getMethod("hasCeiling").invoke(type);
                if (Boolean.TRUE.equals(v)) return true;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // this version has no such flag
        }
        return false;
    }
}
