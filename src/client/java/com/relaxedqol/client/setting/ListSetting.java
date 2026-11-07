package com.relaxedqol.client.setting;

import com.relaxedqol.client.util.PacketRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A set of ids picked from a list: blocks, or serverbound/clientbound packets.
 * Edited in the menu picker or with: .set Module Setting add|remove|clear [id]
 */
public class ListSetting extends Setting<Set<String>> {
    /** STORAGE = blocks with contents (chests, shulkers, hoppers...), CONTAINER_ENTITIES = minecarts, frames... */
    public enum Source { BLOCKS, CAVE_BLOCKS, PACKETS_C2S, PACKETS_S2C, ENTITIES, TRADES, STORAGE, CONTAINER_ENTITIES, ITEMS }

    /**
     * Blocks you actually find underground (caves, ore veins, geodes, dungeons, the Nether / End) - what AutoMiner
     * can be sent to fetch. No fences, planks, wool or other things only players build with.
     */
    private static final Set<String> CAVE_EXTRA = Set.of(
        "ancient_debris", "stone", "deepslate", "granite", "diorite", "andesite", "tuff", "calcite", "dripstone_block",
        "pointed_dripstone", "smooth_basalt", "basalt", "blackstone", "gilded_blackstone", "netherrack", "magma_block",
        "glowstone", "soul_sand", "soul_soil", "obsidian", "crying_obsidian", "gravel", "dirt", "coarse_dirt",
        "rooted_dirt", "clay", "sand", "red_sand", "sandstone", "red_sandstone", "mud", "moss_block", "amethyst_block",
        "budding_amethyst", "amethyst_cluster", "large_amethyst_bud", "medium_amethyst_bud", "small_amethyst_bud",
        "raw_iron_block", "raw_copper_block", "raw_gold_block", "spawner", "trial_spawner", "cobblestone",
        "mossy_cobblestone", "sculk", "sculk_vein", "sculk_sensor", "sculk_catalyst", "sculk_shrieker", "end_stone",
        "ice", "packed_ice", "blue_ice", "suspicious_sand", "suspicious_gravel", "cobweb", "glow_lichen", "terracotta",
        "nether_wart_block", "warped_wart_block", "shroomlight", "bone_block");

    /** Whether a vanilla block id path is something generated underground (see {@link #CAVE_EXTRA}). */
    public static boolean isCaveBlock(String path) {
        return path.endsWith("_ore") || path.startsWith("infested_") || CAVE_EXTRA.contains(path);
    }

    private static boolean isOreId(String id) {
        return id.endsWith("_ore") || id.endsWith("ancient_debris");
    }

    /** Vanilla enchantments and their max level (TradeRoller's list). */
    private static final String[][] ENCHANTS = {
        {"mending", "1"}, {"unbreaking", "3"}, {"efficiency", "5"}, {"fortune", "3"}, {"silk_touch", "1"},
        {"protection", "4"}, {"fire_protection", "4"}, {"blast_protection", "4"}, {"projectile_protection", "4"},
        {"feather_falling", "4"}, {"respiration", "3"}, {"aqua_affinity", "1"}, {"thorns", "3"}, {"depth_strider", "3"},
        {"frost_walker", "2"}, {"soul_speed", "3"}, {"swift_sneak", "3"}, {"sharpness", "5"}, {"smite", "5"},
        {"bane_of_arthropods", "5"}, {"knockback", "2"}, {"fire_aspect", "2"}, {"looting", "3"}, {"sweeping_edge", "3"},
        {"power", "5"}, {"punch", "2"}, {"flame", "1"}, {"infinity", "1"}, {"luck_of_the_sea", "3"}, {"lure", "3"},
        {"loyalty", "3"}, {"impaling", "5"}, {"riptide", "3"}, {"channeling", "1"}, {"multishot", "1"},
        {"quick_charge", "3"}, {"piercing", "4"}, {"density", "5"}, {"breach", "4"}, {"wind_burst", "3"},
        {"binding_curse", "1"}, {"vanishing_curse", "1"}};
    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V"};
    /** Treasure enchantments librarians never sell (only found in loot). */
    private static final Set<String> NOT_TRADED = Set.of("soul_speed", "swift_sneak", "wind_burst");
    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    /**
     * The first-level (Novice) trades of every profession - the only ones TradeRoller can roll, since buying
     * the first trade locks the villager. buy = the villager buys this item from you for an emerald (e.g. a Fletcher:
     * 32 sticks for 1 emerald); otherwise the villager sells it. count = how many (the usual amount).
     */
    private record Novice(String profession, boolean buy, String item, int count) {}

    private static final Novice[] NOVICE = {
        new Novice("Armorer", true, "coal", 15), new Novice("Armorer", false, "iron_helmet", 1),
        new Novice("Armorer", false, "iron_chestplate", 1), new Novice("Armorer", false, "iron_leggings", 1),
        new Novice("Armorer", false, "iron_boots", 1),
        new Novice("Butcher", true, "chicken", 14), new Novice("Butcher", true, "porkchop", 7),
        new Novice("Butcher", true, "rabbit", 4), new Novice("Butcher", false, "rabbit_stew", 1),
        new Novice("Cartographer", true, "paper", 24), new Novice("Cartographer", false, "map", 1),
        new Novice("Cleric", true, "rotten_flesh", 32), new Novice("Cleric", false, "redstone", 2),
        new Novice("Farmer", true, "wheat", 20), new Novice("Farmer", true, "potato", 26),
        new Novice("Farmer", true, "carrot", 22), new Novice("Farmer", true, "beetroot", 15),
        new Novice("Farmer", false, "bread", 6),
        new Novice("Fisherman", true, "string", 20), new Novice("Fisherman", true, "coal", 10),
        new Novice("Fisherman", false, "cooked_cod", 6), new Novice("Fisherman", false, "cod_bucket", 1),
        new Novice("Fletcher", true, "stick", 32), new Novice("Fletcher", false, "arrow", 16),
        new Novice("Fletcher", false, "flint", 10),
        new Novice("Leatherworker", true, "leather", 6), new Novice("Leatherworker", false, "leather_leggings", 1),
        new Novice("Leatherworker", false, "leather_chestplate", 1),
        new Novice("Librarian", true, "paper", 24), new Novice("Librarian", false, "bookshelf", 1),
        new Novice("Mason", true, "clay_ball", 10), new Novice("Mason", false, "brick", 10),
        new Novice("Shepherd", true, "white_wool", 18), new Novice("Shepherd", true, "brown_wool", 18),
        new Novice("Shepherd", true, "black_wool", 18), new Novice("Shepherd", true, "gray_wool", 18),
        new Novice("Shepherd", false, "shears", 1),
        new Novice("Toolsmith", true, "coal", 15), new Novice("Toolsmith", false, "stone_axe", 1),
        new Novice("Toolsmith", false, "stone_shovel", 1), new Novice("Toolsmith", false, "stone_pickaxe", 1),
        new Novice("Toolsmith", false, "stone_hoe", 1),
        new Novice("Weaponsmith", true, "coal", 15), new Novice("Weaponsmith", false, "iron_axe", 1),
        new Novice("Weaponsmith", false, "iron_sword", 1),
    };

    /** One choice in the picker. */
    public record Option(String id, String name, ItemStack icon) {}

    private final Source source;
    private int version = 0;
    private List<Option> optionsCache;

    public ListSetting(String name, Source source, String... defaults) {
        super(name, Collections.unmodifiableSet(new LinkedHashSet<>(normalizeAll(defaults))), null, null, null, null);
        this.value = new LinkedHashSet<>(getDefaultValue());
        this.source = source;
    }

    public Source getSource() { return source; }

    /** Increases every time the selection changes, so modules can react. */
    public int getVersion() { return version; }

    @Override
    public boolean isList() { return true; }

    public boolean contains(String id) {
        return value.contains(normalize(id));
    }

    public void toggle(String id) {
        String key = normalize(id);
        if (!value.remove(key)) value.add(key);
        version++;
    }

    public void add(String id) {
        if (value.add(normalize(id))) version++;
    }

    public void remove(String id) {
        if (value.remove(normalize(id))) version++;
    }

    public void clear() {
        value.clear();
        version++;
    }

    @Override
    public void reset() {
        value = new LinkedHashSet<>(getDefaultValue());
        version++;
    }

    @Override
    public void setValue(Set<String> newValue) {
        if (newValue == null) return;
        value = new LinkedHashSet<>(normalizeAll(newValue.toArray(new String[0])));
        version++;
    }

    @Override
    public void cycle(int direction) {
        // Lists are edited in the picker screen
    }

    /** Accepts "add id", "remove id", "clear", "reset" or a comma separated list (config format). */
    @Override
    public boolean parse(String text) {
        String t = text.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.equals("clear")) {
            clear();
            return true;
        }
        if (lower.equals("reset")) {
            reset();
            return true;
        }
        if (lower.startsWith("add ")) {
            String id = t.substring(4).trim();
            if (!isKnown(id)) return false;
            add(id);
            return true;
        }
        if (lower.startsWith("remove ")) {
            remove(t.substring(7).trim());
            return true;
        }
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        for (String part : t.split(",")) {
            if (!part.isBlank()) parsed.add(normalize(part.trim()));
        }
        value = parsed;
        version++;
        return true;
    }

    @Override
    public String serialize() {
        return String.join(",", value);
    }

    @Override
    public String display() {
        return value.size() + " selected";
    }

    public boolean isKnown(String id) {
        String key = normalize(id);
        for (Option option : getOptions()) {
            if (option.id().equals(key)) return true;
        }
        return false;
    }

    /** Every choice for this list, sorted by name. Built once, on first use. */
    public List<Option> getOptions() {
        if (optionsCache == null) {
            List<Option> options = new ArrayList<>();
            switch (source) {
                case BLOCKS -> {
                    for (Block block : BuiltInRegistries.BLOCK) {
                        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
                        if (key == null) continue;
                        ItemStack icon = block.asItem() == Items.AIR ? ItemStack.EMPTY : new ItemStack(block.asItem());
                        options.add(new Option(key.toString(), block.getName().getString(), icon));
                    }
                }
                case CAVE_BLOCKS -> {
                    for (Block block : BuiltInRegistries.BLOCK) {
                        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
                        if (key == null || !key.getNamespace().equals("minecraft") || !isCaveBlock(key.getPath())) continue;
                        ItemStack icon = block.asItem() == Items.AIR ? ItemStack.EMPTY : new ItemStack(block.asItem());
                        options.add(new Option(key.toString(), block.getName().getString(), icon));
                    }
                }
                case PACKETS_C2S -> {
                    for (PacketRegistry.Entry entry : PacketRegistry.serverbound()) {
                        options.add(new Option(entry.id(), entry.displayName(), ItemStack.EMPTY));
                    }
                }
                case PACKETS_S2C -> {
                    for (PacketRegistry.Entry entry : PacketRegistry.clientbound()) {
                        options.add(new Option(entry.id(), entry.displayName(), ItemStack.EMPTY));
                    }
                }
                case TRADES -> {
                    // Enchanted books: every enchantment a librarian can sell, at every level ("at least this level")
                    ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
                    for (String[] e : ENCHANTS) {
                        if (NOT_TRADED.contains(e[0])) continue;
                        int max = Integer.parseInt(e[1]);
                        String name = net.minecraft.network.chat.Component.translatable("enchantment.minecraft." + e[0]).getString();
                        if (name.startsWith("enchantment.")) name = PacketRegistry.prettify(e[0]);
                        for (int level = max; level >= 1; level--) {
                            String label = max == 1 ? name : name + " " + ROMAN[level] + (level == max ? " (max)" : "");
                            options.add(new Option("ench:" + e[0] + ":" + level, "\u2726 " + label, book));
                        }
                    }
                    // Only first-level (Novice) trades - what a fresh villager can offer and you lock in by
                    // trading once - both what they sell you and what they buy from you (e.g. 32 sticks -> 1 emerald)
                    java.util.Map<String, String> seen = new java.util.LinkedHashMap<>();
                    java.util.Map<String, Novice> first = new java.util.LinkedHashMap<>();
                    for (Novice n : NOVICE) {
                        String id = (n.buy() ? "buy:" : "") + "minecraft:" + n.item();
                        seen.merge(id, n.profession(), (a, b) -> a.contains(b) ? a : a + " / " + b);
                        first.putIfAbsent(id, n);
                    }
                    for (java.util.Map.Entry<String, Novice> e : first.entrySet()) {
                        Novice n = e.getValue();
                        Identifier key = Identifier.tryParse("minecraft:" + n.item());
                        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) continue;
                        ItemStack icon = new ItemStack(BuiltInRegistries.ITEM.getValue(key));
                        String name = icon.getHoverName().getString();
                        String label = n.buy()
                            ? "Buys " + n.count() + " " + name + " \u2192 Emerald"
                            : (n.count() > 1 ? n.count() + " " : "") + name;
                        options.add(new Option(e.getKey(), label + "  \u00B7 " + seen.get(e.getKey()), icon));
                    }
                }
                case STORAGE -> {
                    for (Block block : BuiltInRegistries.BLOCK) {
                        if (!(block instanceof net.minecraft.world.level.block.EntityBlock)) continue;
                        Identifier key = BuiltInRegistries.BLOCK.getKey(block);
                        if (key == null) continue;
                        String path = key.getPath();
                        // Blocks worth looking up: storage, redstone with slots, pots, frames' blocks...
                        if (path.contains("sign") || path.contains("banner") || path.contains("skull") || path.contains("head")
                            || path.contains("bed") || path.contains("spawner") || path.contains("portal") || path.contains("gateway")
                            || path.contains("sculk") || path.contains("conduit") || path.contains("bell") || path.contains("piston")
                            || path.contains("comparator") || path.contains("daylight") || path.contains("command_block")
                            || path.contains("structure") || path.contains("jigsaw") || path.contains("beehive") || path.contains("bee_nest")
                            || path.contains("campfire") || path.contains("lectern") || path.contains("vault") || path.contains("creaking")
                            || path.contains("trial") || path.contains("enchanting") || path.contains("beacon") || path.contains("test")) continue;
                        ItemStack icon = block.asItem() == Items.AIR ? ItemStack.EMPTY : new ItemStack(block.asItem());
                        options.add(new Option(key.toString(), block.getName().getString(), icon));
                    }
                }
                case CONTAINER_ENTITIES -> {
                    for (net.minecraft.world.entity.EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                        Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                        if (key == null) continue;
                        String path = key.getPath();
                        if (!(path.contains("minecart") || path.contains("item_frame") || path.contains("chest_boat")
                            || path.contains("chest_raft") || path.equals("armor_stand") || path.equals("llama")
                            || path.equals("trader_llama") || path.equals("donkey") || path.equals("mule"))) continue;
                        options.add(new Option(key.toString(), type.getDescription().getString(), ItemStack.EMPTY));
                    }
                }
                case ITEMS -> {
                    // every block and item
                    for (net.minecraft.world.item.Item item : BuiltInRegistries.ITEM) {
                        if (item == Items.AIR) continue;
                        Identifier key = BuiltInRegistries.ITEM.getKey(item);
                        if (key == null) continue;
                        ItemStack icon = new ItemStack(item);
                        options.add(new Option(key.toString(), icon.getHoverName().getString(), icon));
                    }
                }
                case ENTITIES -> {
                    for (net.minecraft.world.entity.EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                        Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                        if (key == null) continue;
                        options.add(new Option(key.toString(), type.getDescription().getString(), ItemStack.EMPTY));
                    }
                }
            }
            // Enchantments first (they are what people roll for), ores first in the cave list, then everything by name
            boolean oresFirst = source == Source.CAVE_BLOCKS;
            options.sort((a, b) -> {
                boolean ea = a.id().startsWith("ench:");
                boolean eb = b.id().startsWith("ench:");
                if (ea != eb) return ea ? -1 : 1;
                if (ea) return 0;
                if (oresFirst) {
                    boolean oa = isOreId(a.id());
                    boolean ob = isOreId(b.id());
                    if (oa != ob) return oa ? -1 : 1;
                }
                return a.name().compareToIgnoreCase(b.name());
            });
            optionsCache = options;
        }
        return optionsCache;
    }

    private static String normalize(String id) {
        String key = id.trim().toLowerCase(Locale.ROOT);
        return key.contains(":") ? key : "minecraft:" + key;
    }

    private static List<String> normalizeAll(String[] ids) {
        List<String> result = new ArrayList<>();
        for (String id : ids) result.add(normalize(id));
        return result;
    }
}
