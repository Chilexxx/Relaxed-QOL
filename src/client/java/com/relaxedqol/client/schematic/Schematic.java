package com.relaxedqol.client.schematic;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A schematic loaded from disk - Litematica (.litematic), Sponge / WorldEdit (.schem v2 and v3) or vanilla
 * structure blocks (.nbt). Only the blocks are kept (no air), relative to the schematic's corner, so the builder can
 * place them anywhere and rotated.
 */
public final class Schematic {
    public final String name;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    /** Relative position -> state (air left out). */
    public final Map<BlockPos, BlockState> blocks;
    /** Entities that are part of the build (a hopper minecart on its rail...), position relative like the blocks. */
    public final List<Placed> entities;

    /** An entity of the schematic: its type id ("minecraft:hopper_minecart") and where it stands. */
    public record Placed(String id, double x, double y, double z) {}

    /** One kind of item in a container of the schematic and how many of it. */
    public record Stack(String id, int count) {}

    /** What the containers of the build hold (barrels / droppers of an item-counting clock...), position relative. */
    public final Map<BlockPos, List<Stack>> contents = new HashMap<>();

    /** Reads the block entities' "Items" lists; pos(e) gives each one's position (relative to the blocks' corner). */
    private void readContents(Tag listTag, java.util.function.Function<CompoundTag, BlockPos> pos) {
        if (!(listTag instanceof CollectionTag list)) return;
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof CompoundTag e)) continue;
            CompoundTag body = e.get("Data") instanceof CompoundTag d ? d : e;
            if (!(body.get("Items") instanceof CollectionTag items) || items.size() == 0) continue;
            BlockPos at = pos.apply(e);
            if (at == null) continue;
            Map<String, Integer> sum = new LinkedHashMap<>();
            for (int k = 0; k < items.size(); k++) {
                if (!(items.get(k) instanceof CompoundTag it)) continue;
                String id = str(it.get("id"));
                if (id == null || id.isEmpty()) continue;
                if (!id.contains(":")) id = "minecraft:" + id;
                int n = it.get("count") != null ? num(it.get("count")) : num(it.get("Count"));
                if (n > 0) sum.merge(id, n, Integer::sum);
            }
            if (sum.isEmpty()) continue;
            List<Stack> out = new ArrayList<>();
            sum.forEach((id, n) -> out.add(new Stack(id, n)));
            contents.put(at, out);
        }
    }

    /** The containers' contents turned the same way as rotated(quarterTurns) turns the blocks. */
    public Map<BlockPos, List<Stack>> rotatedContents(int quarterTurns) {
        int q = Math.floorMod(quarterTurns, 4);
        if (q == 0 || contents.isEmpty()) return contents;
        Map<BlockPos, List<Stack>> out = new HashMap<>();
        for (Map.Entry<BlockPos, List<Stack>> e : contents.entrySet()) {
            BlockPos p = e.getKey();
            BlockPos r = switch (q) {
                case 1 -> new BlockPos(sizeZ - 1 - p.getZ(), p.getY(), p.getX());
                case 2 -> new BlockPos(sizeX - 1 - p.getX(), p.getY(), sizeZ - 1 - p.getZ());
                default -> new BlockPos(p.getZ(), p.getY(), sizeX - 1 - p.getX());
            };
            out.put(r, e.getValue());
        }
        return out;
    }

    private Schematic(String name, int sx, int sy, int sz, Map<BlockPos, BlockState> blocks) {
        this(name, sx, sy, sz, blocks, List.of());
    }

    private Schematic(String name, int sx, int sy, int sz, Map<BlockPos, BlockState> blocks, List<Placed> entities) {
        this.name = name;
        this.sizeX = sx;
        this.sizeY = sy;
        this.sizeZ = sz;
        this.blocks = blocks;
        this.entities = entities;
    }

    /** Reads an entity list: each entry's id (key "Id" / "id", or inside "nbt" / "Data") and its "Pos" / "pos". */
    private static List<Placed> entities(Tag listTag, double dx, double dy, double dz) {
        List<Placed> out = new ArrayList<>();
        if (!(listTag instanceof CollectionTag list)) return out;
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof CompoundTag e)) continue;
            String id = str(e.get("Id"));
            if (id == null || id.isEmpty()) id = str(e.get("id"));
            CompoundTag inner = e.get("nbt") instanceof CompoundTag n ? n : e.get("Data") instanceof CompoundTag d ? d : null;
            if ((id == null || id.isEmpty()) && inner != null) id = str(inner.get("id"));
            Tag posTag = e.get("Pos") != null ? e.get("Pos") : e.get("pos");
            if ((posTag == null) && inner != null) posTag = inner.get("Pos");
            if (id == null || id.isEmpty() || !(posTag instanceof CollectionTag pl) || pl.size() < 3) continue;
            double[] v = new double[3];
            for (int k = 0; k < 3; k++) v[k] = pl.get(k) instanceof net.minecraft.nbt.NumericTag nt ? nt.box().doubleValue() : 0;
            if (!id.contains(":")) id = "minecraft:" + id;
            out.add(new Placed(id, v[0] + dx, v[1] + dy, v[2] + dz));
        }
        return out;
    }

    // ------------------------------------------------------------------ files

    /** Where schematics are looked for: Litematica's / WorldEdit's folders and our own. */
    public static List<Path> folders() {
        Path game = FabricLoader.getInstance().getGameDir();
        Path config = FabricLoader.getInstance().getConfigDir();
        List<Path> out = new ArrayList<>();
        out.add(game.resolve("schematics"));
        out.add(config.resolve("relaxed-qol").resolve("schematics"));
        out.add(config.resolve("worldedit").resolve("schematics"));
        return out;
    }

    public static List<Path> listFiles() {
        List<Path> out = new ArrayList<>();
        try {
            Files.createDirectories(folders().get(1));
        } catch (IOException ignored) {
            // read-only folder: just list what exists
        }
        for (Path dir : folders()) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.walk(dir, 3)) {
                s.filter(Files::isRegularFile).filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".litematic") || n.endsWith(".schem") || n.endsWith(".schematic") || n.endsWith(".nbt");
                }).forEach(out::add);
            } catch (IOException ignored) {
                // skip unreadable folder
            }
        }
        out.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return out;
    }

    /** Block types in the last file read that this game does not know (left out of the build). */
    private static final java.util.Set<String> UNKNOWN = new java.util.LinkedHashSet<>();
    /** The game version (data version) the file being read was saved in; 0 = not said. */
    private static int fileVersion;

    /** Block types the last loaded file has that this game does not know (they are left out). */
    public static List<String> lastUnknown() {
        return new ArrayList<>(UNKNOWN);
    }

    /** The game version of the file whose block names are read next (AreaCopy reads the same files its own way). */
    static void readingVersion(int dataVersion) {
        fileVersion = dataVersion;
    }

    /**
     * More blocks than this are not read into the builder: every block is an object here, and nobody places
     * millions by hand. Such files (whole chunks from the Schematics Library) are pasted instead.
     */
    public static final int BUILD_LIMIT = 1_500_000;

    public static synchronized Schematic load(Path file) throws IOException {
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (java.util.zip.ZipException notCompressed) {
            // Some tools save structure / schematic files without compressing them
            root = NbtIo.read(file);
            if (root == null) throw new IOException("Not a schematic file");
        }
        UNKNOWN.clear();
        // Saved in an older game version: block names and states are brought up to date the way the game updates
        // old worlds ("grass" -> "short_grass", old fence / wall states...)
        int v = num(root.get("MinecraftDataVersion"));
        if (v <= 0) v = num(root.get("DataVersion"));
        if (v <= 0 && root.get("Schematic") instanceof CompoundTag inner) v = num(inner.get("DataVersion"));
        fileVersion = v;
        String fileName = file.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        String name = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        if (root.get("Regions") instanceof CompoundTag) return litematic(name, root);
        if (root.get("Schematic") instanceof CompoundTag inner) return sponge(name, inner, 3);
        if (root.get("Palette") instanceof CompoundTag) return sponge(name, root, 2);
        if (root.get("blocks") instanceof CollectionTag && root.get("size") != null) return structure(name, root);
        if (root.get("Blocks") instanceof ByteArrayTag && root.get("Data") instanceof ByteArrayTag) return legacy(name, root);
        if (lower.endsWith(".schematic")) throw new IOException("This .schematic file has no blocks in it");
        throw new IOException("Unknown schematic format");
    }

    // ------------------------------------------------------------------ formats

    private static Schematic litematic(String name, CompoundTag root) throws IOException {
        Map<BlockPos, BlockState> out = new HashMap<>();
        CompoundTag regions = (CompoundTag) root.get("Regions");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Map<BlockPos, BlockState> raw = new HashMap<>();
        List<Placed> rawEntities = new ArrayList<>();
        List<Object[]> rawTiles = new ArrayList<>();
        for (String key : regions.keySet()) {
            if (!(regions.get(key) instanceof CompoundTag region)) continue;
            CompoundTag pos = compound(region, "Position");
            CompoundTag size = compound(region, "Size");
            int px = num(pos.get("x")), py = num(pos.get("y")), pz = num(pos.get("z"));
            int sx = num(size.get("x")), sy = num(size.get("y")), sz = num(size.get("z"));
            // Negative sizes grow the other way from Position
            int ox = px + (sx < 0 ? sx + 1 : 0);
            int oy = py + (sy < 0 ? sy + 1 : 0);
            int oz = pz + (sz < 0 ? sz + 1 : 0);
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            rawEntities.addAll(entities(region.get("Entities"), ox, oy, oz));
            if (region.get("TileEntities") instanceof CollectionTag te) rawTiles.add(new Object[]{te, ox, oy, oz});
            List<BlockState> palette = new ArrayList<>();
            if (region.get("BlockStatePalette") instanceof CollectionTag list) {
                for (int i = 0; i < list.size(); i++) palette.add(list.get(i) instanceof CompoundTag c ? stateOf(c) : Blocks.AIR.defaultBlockState());
            }
            if (!(region.get("BlockStates") instanceof LongArrayTag longsTag)) continue;
            long[] longs = longsTag.getAsLongArray();
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            long mask = (1L << bits) - 1L;
            long volume = (long) ax * ay * az;
            for (long index = 0; index < volume; index++) {
                long startBit = index * bits;
                int startLong = (int) (startBit >>> 6);
                int endLong = (int) ((index + 1) * bits - 1 >>> 6);
                int offset = (int) (startBit & 63);
                if (endLong >= longs.length) break;
                long value;
                if (startLong == endLong) {
                    value = (longs[startLong] >>> offset) & mask;
                } else {
                    int endOffset = 64 - offset;
                    value = ((longs[startLong] >>> offset) | (longs[endLong] << endOffset)) & mask;
                }
                if (value <= 0 || value >= palette.size()) continue;
                BlockState state = palette.get((int) value);
                if (state.isAir()) continue;
                int x = (int) (index % ax);
                int z = (int) ((index / ax) % az);
                int y = (int) (index / ((long) ax * az));
                int wx = ox + x, wy = oy + y, wz = oz + z;
                raw.put(new BlockPos(wx, wy, wz), state);
                minX = Math.min(minX, wx); minY = Math.min(minY, wy); minZ = Math.min(minZ, wz);
                maxX = Math.max(maxX, wx); maxY = Math.max(maxY, wy); maxZ = Math.max(maxZ, wz);
            }
        }
        if (raw.isEmpty()) throw new IOException("The schematic has no blocks");
        for (Map.Entry<BlockPos, BlockState> e : raw.entrySet()) out.put(e.getKey().offset(-minX, -minY, -minZ), e.getValue());
        List<Placed> ents = new ArrayList<>();
        for (Placed p : rawEntities) ents.add(new Placed(p.id(), p.x() - minX, p.y() - minY, p.z() - minZ));
        Schematic sch = new Schematic(name, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, out, ents);
        final int mx = minX, my = minY, mz = minZ;
        for (Object[] t : rawTiles) {
            int ox = (int) t[1], oy = (int) t[2], oz = (int) t[3];
            sch.readContents((Tag) t[0], e -> new BlockPos(num(e.get("x")) + ox - mx, num(e.get("y")) + oy - my, num(e.get("z")) + oz - mz));
        }
        return sch;
    }

    private static Schematic sponge(String name, CompoundTag tag, int version) throws IOException {
        int w = num(tag.get("Width"));
        int h = num(tag.get("Height"));
        int l = num(tag.get("Length"));
        CompoundTag paletteTag;
        byte[] data;
        if (version >= 3) {
            CompoundTag blocks = compound(tag, "Blocks");
            paletteTag = compound(blocks, "Palette");
            data = blocks.get("Data") instanceof ByteArrayTag b ? b.getAsByteArray() : new byte[0];
        } else {
            paletteTag = compound(tag, "Palette");
            data = tag.get("BlockData") instanceof ByteArrayTag b ? b.getAsByteArray() : new byte[0];
        }
        Map<Integer, BlockState> palette = new HashMap<>();
        for (String key : paletteTag.keySet()) palette.put(num(paletteTag.get(key)), paletteState(key));
        Map<BlockPos, BlockState> out = new HashMap<>();
        int index = 0;
        int i = 0;
        while (i < data.length) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && i < data.length);
            BlockState state = palette.get(value);
            if (state != null && !state.isAir()) {
                int y = index / (w * l);
                int z = (index % (w * l)) / w;
                int x = index % w;
                out.put(new BlockPos(x, y, z), state);
                if (out.size() > BUILD_LIMIT) {
                    throw new IOException("Too big to build block by block (over " + BUILD_LIMIT / 1_000_000.0 + " million blocks). In single player paste it: .schem paste " + name);
                }
            }
            index++;
        }
        if (out.isEmpty()) throw new IOException("The schematic has no blocks");
        Schematic sch = new Schematic(name, w, h, l, out, entities(tag.get("Entities"), 0, 0, 0));
        Tag be = version >= 3 ? compound(tag, "Blocks").get("BlockEntities") : tag.get("BlockEntities");
        if (be == null) be = tag.get("TileEntities");
        sch.readContents(be, e -> {
            int[] p = ints(e.get("Pos"));
            return p.length >= 3 ? new BlockPos(p[0], p[1], p[2]) : null;
        });
        return sch;
    }

    /**
     * Old MCEdit / WorldEdit .schematic files (before 1.13): block numbers + metadata, turned into today's blocks with
     * the game's own tables (the way it updates old worlds).
     */
    private static Schematic legacy(String name, CompoundTag root) throws IOException {
        int w = num(root.get("Width"));
        int h = num(root.get("Height"));
        int l = num(root.get("Length"));
        byte[] ids = ((ByteArrayTag) root.get("Blocks")).getAsByteArray();
        byte[] meta = ((ByteArrayTag) root.get("Data")).getAsByteArray();
        byte[] add = root.get("AddBlocks") instanceof ByteArrayTag a ? a.getAsByteArray() : null;
        fileVersion = 1451; // the names the table gives are from the first 1.13 snapshots
        Map<Integer, BlockState> cache = new HashMap<>();
        Map<BlockPos, BlockState> out = new HashMap<>();
        int volume = Math.min((long) w * h * l > Integer.MAX_VALUE ? Integer.MAX_VALUE : w * h * l, ids.length);
        for (int i = 0; i < volume; i++) {
            int id = ids[i] & 0xFF;
            if (add != null && (i >> 1) < add.length) {
                id |= (i & 1) == 0 ? (add[i >> 1] & 0x0F) << 8 : (add[i >> 1] & 0xF0) << 4;
            }
            if (id == 0) continue;
            int key = (id << 4) | (i < meta.length ? meta[i] & 0x0F : 0);
            BlockState state = cache.computeIfAbsent(key, k -> {
                try {
                    Tag t = net.minecraft.util.datafix.fixes.BlockStateData.getTag(k).convert(net.minecraft.nbt.NbtOps.INSTANCE).getValue();
                    return t instanceof CompoundTag c ? stateOf(c) : Blocks.AIR.defaultBlockState();
                } catch (RuntimeException | LinkageError e) {
                    return Blocks.AIR.defaultBlockState();
                }
            });
            if (state.isAir()) continue;
            int x = i % w;
            int z = (i / w) % l;
            int y = i / (w * l);
            out.put(new BlockPos(x, y, z), state);
        }
        if (out.isEmpty()) throw new IOException("The schematic has no blocks");
        return new Schematic(name, w, h, l, out);
    }

    private static Schematic structure(String name, CompoundTag root) throws IOException {
        int[] size = ints(root.get("size"));
        List<BlockState> palette = new ArrayList<>();
        Tag pal = root.get("palette");
        if (pal == null && root.get("palettes") instanceof CollectionTag many && many.size() > 0) pal = many.get(0);
        if (pal instanceof CollectionTag list) {
            for (int i = 0; i < list.size(); i++) palette.add(list.get(i) instanceof CompoundTag c ? stateOf(c) : Blocks.AIR.defaultBlockState());
        }
        Map<BlockPos, BlockState> out = new HashMap<>();
        CollectionTag blocks = (CollectionTag) root.get("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            if (!(blocks.get(i) instanceof CompoundTag b)) continue;
            int[] p = ints(b.get("pos"));
            int s = num(b.get("state"));
            if (p.length < 3 || s < 0 || s >= palette.size()) continue;
            BlockState state = palette.get(s);
            if (state.isAir() || state.is(Blocks.STRUCTURE_VOID)) continue;
            out.put(new BlockPos(p[0], p[1], p[2]), state);
        }
        if (out.isEmpty()) throw new IOException("The structure has no blocks");
        return new Schematic(name, size.length > 0 ? size[0] : 1, size.length > 1 ? size[1] : 1, size.length > 2 ? size[2] : 1, out,
            entities(root.get("entities"), 0, 0, 0));
    }

    // ------------------------------------------------------------------ helpers

    static CompoundTag compound(CompoundTag tag, String key) {
        return tag.get(key) instanceof CompoundTag c ? c : new CompoundTag();
    }

    /** Any number tag as int ("12", "12b", "12s", "12L"...). */
    static int num(Tag t) {
        if (!(t instanceof NumericTag)) return 0;
        String s = t.toString();
        while (!s.isEmpty() && Character.isLetter(s.charAt(s.length() - 1))) s = s.substring(0, s.length() - 1);
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int[] ints(Tag t) {
        if (t instanceof IntArrayTag a) return a.getAsIntArray();
        if (t instanceof CollectionTag list) {
            int[] out = new int[list.size()];
            for (int i = 0; i < out.length; i++) out[i] = num(list.get(i));
            return out;
        }
        return new int[0];
    }

    static String str(Tag t) {
        return t instanceof StringTag(String value) ? value : t == null ? "" : t.toString();
    }

    /** A block state saved in an older game version, updated by the game's own data fixer (unchanged if it fails). */
    private static CompoundTag upgrade(CompoundTag c) {
        // No version saved (old Litematica files) but a name this game does not know ("unpowered_repeater",
        // "double_stone_slab"...): it is from before 1.13 - updated from 1.12.2
        if (fileVersion <= 0 && !BuiltInRegistries.BLOCK.containsKey(Identifier.tryParse(str(c.get("Name"))))) return upgradeFrom(c, 1343);
        if (fileVersion <= 0 || fileVersion >= net.minecraft.SharedConstants.WORLD_VERSION) return c;
        return upgradeFrom(c, fileVersion);
    }

    private static CompoundTag upgradeFrom(CompoundTag c, int from) {
        try {
            com.mojang.serialization.Dynamic<Tag> in = new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, c);
            Tag out = net.minecraft.util.datafix.DataFixers.getDataFixer().update(net.minecraft.util.datafix.fixes.References.BLOCK_STATE,
                in, from, net.minecraft.SharedConstants.WORLD_VERSION).getValue();
            return out instanceof CompoundTag up ? up : c;
        } catch (RuntimeException | LinkageError e) {
            return c;
        }
    }

    /** {Name: "minecraft:oak_stairs", Properties: {facing: "north", ...}} */
    private static BlockState stateOf(CompoundTag c) {
        c = upgrade(c);
        Block block = blockOf(str(c.get("Name")));
        BlockState state = block.defaultBlockState();
        if (c.get("Properties") instanceof CompoundTag props) {
            for (String key : props.keySet()) state = with(state, key, str(props.get(key)));
        }
        return state;
    }

    /** A Sponge palette key, updated first when the file is from an older game version. */
    static BlockState paletteState(String text) {
        if (fileVersion <= 0 || fileVersion >= net.minecraft.SharedConstants.WORLD_VERSION) return parseState(text);
        CompoundTag c = new CompoundTag();
        int open = text.indexOf('[');
        c.putString("Name", open >= 0 ? text.substring(0, open) : text);
        if (open >= 0) {
            CompoundTag props = new CompoundTag();
            String inner = text.substring(open + 1, text.endsWith("]") ? text.length() - 1 : text.length());
            for (String pair : inner.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) props.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
            c.put("Properties", props);
        }
        return stateOf(c);
    }

    /** "minecraft:oak_stairs[facing=north,half=bottom]" */
    public static BlockState parseState(String text) {
        String id = text;
        String props = "";
        int open = text.indexOf('[');
        if (open >= 0) {
            id = text.substring(0, open);
            props = text.substring(open + 1, text.endsWith("]") ? text.length() - 1 : text.length());
        }
        BlockState state = blockOf(id).defaultBlockState();
        if (!props.isEmpty()) {
            for (String pair : props.split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) state = with(state, pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return state;
    }

    private static Block blockOf(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            if (!id.isBlank() && !id.contains("%%")) UNKNOWN.add(id); // %%FILTER_ME%% = "no block" in old tables
            return Blocks.AIR;
        }
        return BuiltInRegistries.BLOCK.getValue(key);
    }

    private static BlockState with(BlockState state, String name, String value) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        return property == null ? state : withValue(state, property, value);
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        Optional<T> v = property.getValue(value);
        return v.isPresent() ? state.setValue(property, v.get()) : state;
    }

    // ------------------------------------------------------------------ queries

    /** The blocks rotated (clockwise steps of 90 degrees), still starting at 0 0 0. */
    public Map<BlockPos, BlockState> rotated(int quarterTurns) {
        int q = Math.floorMod(quarterTurns, 4);
        if (q == 0) return blocks;
        Rotation rot = switch (q) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            BlockPos p = e.getKey();
            BlockPos r = switch (q) {
                case 1 -> new BlockPos(sizeZ - 1 - p.getZ(), p.getY(), p.getX());
                case 2 -> new BlockPos(sizeX - 1 - p.getX(), p.getY(), sizeZ - 1 - p.getZ());
                default -> new BlockPos(p.getZ(), p.getY(), sizeX - 1 - p.getX());
            };
            out.put(r, e.getValue().rotate(rot));
        }
        return out;
    }

    /** The entities turned the same way as rotated(quarterTurns) turns the blocks. */
    public List<Placed> rotatedEntities(int quarterTurns) {
        int q = Math.floorMod(quarterTurns, 4);
        if (q == 0 || entities.isEmpty()) return entities;
        List<Placed> out = new ArrayList<>();
        for (Placed p : entities) {
            double x = p.x(), z = p.z();
            double nx, nz;
            switch (q) {
                case 1 -> { nx = sizeZ - z; nz = x; }
                case 2 -> { nx = sizeX - x; nz = sizeZ - z; }
                default -> { nx = z; nz = sizeX - x; }
            }
            out.add(new Placed(p.id(), nx, p.y(), nz));
        }
        return out;
    }

    /** Block -> how many are needed. */
    public Map<Block, Integer> materials() {
        Map<Block, Integer> out = new LinkedHashMap<>();
        for (BlockState s : blocks.values()) out.merge(s.getBlock(), 1, Integer::sum);
        return out;
    }
}
