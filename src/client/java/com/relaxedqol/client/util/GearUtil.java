package com.relaxedqol.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Armor / held item helpers (used by ArmorSpoof and TradeRoller): equipment slots in display order, the player
 * under the crosshair at range, readable enchantment names and durability.
 */
public final class GearUtil {
    private GearUtil() {}

    /** Equipment slots in the order they are shown. */
    public static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    public static final String[] SLOT_NAMES = {"Helmet", "Chestplate", "Leggings", "Boots", "Main hand", "Off hand"};

    /** The player under the crosshair, up to {@code range} blocks away (not just within reach). */
    public static Player playerInView(double range) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getViewVector(1.0f).scale(range));
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isSpectator()) continue;
            AABB box = player.getBoundingBox().inflate(0.15);
            Optional<Vec3> hit = box.clip(eye, end);
            if (hit.isEmpty() && !box.contains(eye)) continue;
            double d = hit.map(eye::distanceToSqr).orElse(0.0);
            if (d < bestDist) {
                bestDist = d;
                best = player;
            }
        }
        return best;
    }

    /** "Protection IV", "Mending"... read from the item's enchantment data (also stored enchantments on books). */
    public static List<String> enchantments(ItemStack stack) {
        List<String> out = new ArrayList<>();
        if (stack == null || stack.isEmpty()) return out;
        Tag tag = NbtUtil.encode(stack);
        if (!(tag instanceof CompoundTag root) || !(root.get("components") instanceof CompoundTag components)) return out;
        for (String key : new String[]{"minecraft:enchantments", "minecraft:stored_enchantments"}) {
            if (!(components.get(key) instanceof CompoundTag ench)) continue;
            CompoundTag levels = ench.get("levels") instanceof CompoundTag l ? l : ench;
            for (String id : levels.keySet()) {
                int level = parseLevel(levels.get(id));
                if (level <= 0) continue;
                String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
                String name = Component.translatable("enchantment." + ns + "." + path).getString();
                if (name.startsWith("enchantment.")) name = PacketRegistry.prettify(path);
                boolean single = path.equals("mending") || path.equals("silk_touch") || path.equals("infinity")
                    || path.equals("channeling") || path.equals("multishot") || path.contains("curse") || path.equals("aqua_affinity")
                    || path.equals("flame");
                String lvl = single && level == 1 ? "" : " " + Component.translatable("enchantment.level." + level).getString();
                if (lvl.trim().startsWith("enchantment.level.")) lvl = " " + level;
                out.add((path.contains("curse") ? "§c" : "") + name + lvl);
            }
        }
        return out;
    }

    private static int parseLevel(Tag tag) {
        if (tag == null) return 0;
        String digits = tag.toString().replaceAll("[^0-9]", "");
        try {
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "123/363" with a colour by how worn it is, or null for items without durability. */
    public static String durability(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem() || stack.getMaxDamage() <= 0) return null;
        int max = stack.getMaxDamage();
        int left = max - stack.getDamageValue();
        float frac = left / (float) max;
        String color = frac > 0.5f ? "§a" : frac > 0.2f ? "§e" : "§c";
        return color + left + "§7/" + max;
    }

    /** 0..1 how much durability is left, or -1 for items without durability. */
    public static float durabilityFraction(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem() || stack.getMaxDamage() <= 0) return -1f;
        return (stack.getMaxDamage() - stack.getDamageValue()) / (float) stack.getMaxDamage();
    }
}
