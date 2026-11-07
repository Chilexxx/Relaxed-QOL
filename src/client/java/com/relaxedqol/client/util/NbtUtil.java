package com.relaxedqol.client.util;

import com.mojang.serialization.DynamicOps;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.SnbtPrinterTagVisitor;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Item data as NBT (26.2 items store "data components"; this is the same data the /give command uses). */
public final class NbtUtil {
    private NbtUtil() {}

    /** The whole stack: id, count and every component that differs from the default item. */
    public static Tag encode(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new CompoundTag();
        Minecraft mc = Minecraft.getInstance();
        DynamicOps<Tag> ops = mc.level != null
            ? mc.level.registryAccess().createSerializationContext(NbtOps.INSTANCE)
            : NbtOps.INSTANCE;
        try {
            return ItemStack.CODEC.encodeStart(ops, stack).result().orElseGet(CompoundTag::new);
        } catch (Exception e) {
            CompoundTag error = new CompoundTag();
            error.putString("error", String.valueOf(e.getMessage()));
            return error;
        }
    }

    /** A /give command that recreates the item (components included). */
    public static String giveCommand(ItemStack stack) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        Tag tag = encode(stack);
        String components = "";
        if (tag instanceof CompoundTag compound && compound.get("components") instanceof CompoundTag parts && !parts.isEmpty()) {
            StringBuilder sb = new StringBuilder("[");
            for (String key : parts.keySet()) {
                if (sb.length() > 1) sb.append(',');
                Tag value = parts.get(key);
                if (key.startsWith("!")) sb.append(key);
                else sb.append(key).append('=').append(value);
            }
            components = sb.append(']').toString();
        }
        return "/give @s " + id + components + " " + stack.getCount();
    }

    /** One line, for copying (SNBT). */
    public static String snbt(ItemStack stack) {
        return encode(stack).toString();
    }

    /** Indented, one entry per line. */
    public static List<String> prettyLines(ItemStack stack) {
        String pretty = new SnbtPrinterTagVisitor().visit(encode(stack));
        List<String> lines = new ArrayList<>();
        for (String line : pretty.split("\n")) {
            if (!line.isBlank()) lines.add(line.replace("\t", "  ").replace("    ", "  "));
        }
        return lines;
    }

    // ------------------------------------------------------------------ placed blocks

    /**
     * A placed block as one compound: block id, position, block state properties and the block entity data this
     * client has (signs, banners, spawners, beehives, skulls, lecterns... - the server never sends chest contents
     * to players; see {@link #queryServerBlock}).
     */
    public static Tag encodeBlock(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        CompoundTag root = new CompoundTag();
        if (mc.level == null || pos == null) return root;
        BlockState state = mc.level.getBlockState(pos);
        root.putString("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        root.putString("pos", pos.getX() + " " + pos.getY() + " " + pos.getZ());
        try {
            Tag encoded = BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state).result().orElse(null);
            if (encoded instanceof CompoundTag c && c.get("Properties") instanceof CompoundTag props && !props.isEmpty()) {
                root.put("state", props);
            }
        } catch (RuntimeException ignored) {
            // no properties then
        }
        BlockEntity blockEntity = mc.level.getBlockEntity(pos);
        if (blockEntity != null) {
            CompoundTag data = saveBlockEntity(blockEntity);
            root.put("block_entity", data != null ? data : new CompoundTag());
            root.putString("note", "Client copy: only what the server shares (no chest contents). Ops: Ask Server.");
        }
        return root;
    }

    /**
     * Block entity data, found by name so it keeps working when the save methods change shape between versions
     * (26.x ships unobfuscated, so the names are the real ones at runtime too).
     */
    private static CompoundTag saveBlockEntity(BlockEntity blockEntity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        Object registries = mc.level.registryAccess();
        for (String name : new String[]{"saveWithFullMetadata", "saveWithId", "saveWithoutMetadata", "saveCustomOnly"}) {
            for (java.lang.reflect.Method m : blockEntity.getClass().getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isInstance(registries)) continue;
                try {
                    Object result = m.invoke(blockEntity, registries);
                    if (result instanceof CompoundTag tag) return tag;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // try the next one
                }
            }
        }
        return null;
    }

    /**
     * Asks the server for the block's full data (the vanilla F3+I query). The server only answers operators
     * (permission level 2); everyone else gets no answer. Returns false when the query is not available.
     */
    public static boolean queryServerBlock(BlockPos pos, java.util.function.Consumer<CompoundTag> callback) {
        Minecraft mc = Minecraft.getInstance();
        Object connection = mc.getConnection();
        if (connection == null) return false;
        try {
            Object handler = connection.getClass().getMethod("getDebugQueryHandler").invoke(connection);
            if (handler == null) return false;
            for (java.lang.reflect.Method m : handler.getClass().getMethods()) {
                if (!m.getName().equals("queryBlockEntityTag") || m.getParameterCount() != 2) continue;
                java.util.function.Consumer<Object> answer = tag -> {
                    if (tag instanceof CompoundTag c) callback.accept(c);
                };
                m.invoke(handler, pos, answer);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not available in this version
        }
        return false;
    }

    /**
     * An entity (armor stand, item frame, mob, player...) as one compound: type, uuid, position and everything
     * the game saves for it (armor stand equipment, pose, flags, custom name...). Falls back to the basics when the
     * full save is not possible on the client.
     */
    public static Tag encodeEntity(net.minecraft.world.entity.Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        CompoundTag root = new CompoundTag();
        if (entity == null) return root;
        root.putString("entity", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        root.putString("uuid", entity.getUUID().toString());
        root.putString("pos", String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", entity.getX(), entity.getY(), entity.getZ()));
        if (entity.getCustomName() != null) root.putString("custom_name", entity.getCustomName().getString());
        try {
            net.minecraft.world.level.storage.TagValueOutput out = mc.level != null
                ? net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, mc.level.registryAccess())
                : net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(net.minecraft.util.ProblemReporter.DISCARDING);
            entity.saveWithoutId(out);
            CompoundTag data = out.buildResult();
            if (data != null && !data.isEmpty()) root.put("data", data);
        } catch (RuntimeException | LinkageError e) {
            // The basics below then
        }
        if (root.get("data") == null && entity instanceof net.minecraft.world.entity.LivingEntity living) {
            CompoundTag equipment = new CompoundTag();
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) equipment.put(slot.getName(), encode(stack));
            }
            if (!equipment.isEmpty()) root.put("equipment", equipment);
        }
        root.putString("note", "Client copy: what the server shares with you. Ops: Ask Server for everything.");
        return root;
    }

    /** Op only: the server's full data of an entity (the vanilla F3+I entity query). */
    public static boolean queryServerEntity(int entityId, java.util.function.Consumer<CompoundTag> callback) {
        Minecraft mc = Minecraft.getInstance();
        Object connection = mc.getConnection();
        if (connection == null) return false;
        try {
            Object handler = connection.getClass().getMethod("getDebugQueryHandler").invoke(connection);
            if (handler == null) return false;
            for (java.lang.reflect.Method m : handler.getClass().getMethods()) {
                if (!m.getName().equals("queryEntityTag") || m.getParameterCount() != 2) continue;
                java.util.function.Consumer<Object> answer = tag -> {
                    if (tag instanceof CompoundTag c) callback.accept(c);
                };
                m.invoke(handler, entityId, answer);
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not available in this version
        }
        return false;
    }

    /** The block compound with the server's full block entity data in place of the client copy. */
    public static Tag withServerData(Tag block, CompoundTag server) {
        CompoundTag root = block instanceof CompoundTag c ? c.copy() : new CompoundTag();
        root.put("block_entity", server);
        root.remove("note");
        root.putString("source", "server");
        return root;
    }

    /** A /setblock command that recreates the block: id, [state] and {block entity data}. */
    public static String setblockCommand(BlockPos pos, Tag tag) {
        StringBuilder sb = new StringBuilder("/setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " ");
        if (!(tag instanceof CompoundTag root)) return sb.append("minecraft:air").toString();
        sb.append(root.get("block") instanceof StringTag(String id) ? id : "minecraft:air");
        if (root.get("state") instanceof CompoundTag props && !props.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (String key : props.keySet()) {
                if (!first) sb.append(',');
                first = false;
                Tag value = props.get(key);
                sb.append(key).append('=').append(value instanceof StringTag(String text) ? text : String.valueOf(value));
            }
            sb.append(']');
        }
        if (root.get("block_entity") instanceof CompoundTag data) {
            CompoundTag copy = data.copy();
            for (String key : new String[]{"id", "x", "y", "z"}) copy.remove(key);
            if (!copy.isEmpty()) sb.append(copy);
        }
        return sb.toString();
    }

    /** Colour for every character of SNBT text: keys, strings, numbers, punctuation. */
    public static int[] colours(String text, int key, int string, int number, int other) {
        int n = text.length();
        int[] colours = new int[n];
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && !(text.charAt(j) == c && text.charAt(j - 1) != '\\')) j++;
                int end = Math.min(n, j + 1);
                int color = followedByColon(text, end) ? key : string;
                for (int k = i; k < end; k++) colours[k] = color;
                i = end;
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '+' || c == '!') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || "_-.+!".indexOf(text.charAt(j)) >= 0)) j++;
                String word = text.substring(i, j);
                int color;
                if (followedByColon(text, j)) color = key;
                else if (word.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?[bBsSlLfFdD]?")) color = number;
                else color = string;
                for (int k = i; k < j; k++) colours[k] = color;
                i = j;
            } else {
                colours[i] = other;
                i++;
            }
        }
        return colours;
    }

    private static boolean followedByColon(String text, int from) {
        int j = from;
        while (j < text.length() && text.charAt(j) == ' ') j++;
        return j < text.length() && text.charAt(j) == ':';
    }
}
