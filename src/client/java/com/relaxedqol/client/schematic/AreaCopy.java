package com.relaxedqol.client.schematic;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A box of the world held small: one number a cell (which block of its list stands there) instead of an object a
 * block. Millions of blocks - whole chunks - are copied, written and pasted this way without the game hanging or
 * running out of memory. It also carries what the blocks in it hold (chest contents, sign text...), as far as the
 * side that copies knows it: everything in single player, on a server only what the server shows you.
 *
 * Files are the Sponge format, version 2 (.schem: what WorldEdit and Litematica read too).
 */
public final class AreaCopy {
    /** What one block holds, at its place counted from the lowest corner. */
    public record Held(int x, int y, int z, CompoundTag data) {}

    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    /** Where its lowest corner stood in the world it came from (0 0 0 when the file does not say). */
    public final BlockPos from;
    /** Whole chunks: pasted back at the height they were copied at, not at your feet. */
    public final boolean keepHeight;
    /** (y * sizeZ + z) * sizeX + x -> number in the list; 0 = nothing there. */
    final char[] cells;
    final List<BlockState> palette;
    public final List<Held> held;
    public final long blocks;
    /** Chunks the box touches, and how many of them were loaded when it was copied. */
    public int chunksWanted;
    public int chunksFound;
    /**
     * Columns (z * sizeX + x) that were not copied because their chunk was not loaded: nothing is known about them,
     * so a paste leaves them as they are instead of emptying them. null = everything was copied.
     */
    public java.util.BitSet missing;

    private AreaCopy(int sizeX, int sizeY, int sizeZ, BlockPos from, boolean keepHeight, char[] cells, List<BlockState> palette, List<Held> held, long blocks) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.from = from;
        this.keepHeight = keepHeight;
        this.cells = cells;
        this.palette = palette;
        this.held = held;
        this.blocks = blocks;
    }

    /** Stops with a plain sentence when the game has no room left for this many cells. */
    private static void room(long count) {
        if (count > 1_000_000_000L) throw new IllegalStateException("That is too much for one copy (" + count / 1_000_000 + " million cells) - take fewer chunks.");
        // two bytes a cell and nothing else of that size: the file is written from the cells and read into them
        long need = count * 2 + (64L << 20);
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        if (need > free) {
            System.gc();
            free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        }
        if (need > free) {
            throw new IllegalStateException("Not enough memory for that much (needs about " + (need >> 20) + " MB, " + (free >> 20)
                + " MB free) - take fewer chunks, or give the game more memory.");
        }
    }

    // ------------------------------------------------------------------ from the world

    /**
     * A copy being taken out of the world, a few chunks at a time, so the game does not stand still while hundreds
     * of chunks are read. Everything here runs on the thread that owns the level.
     *
     * load = false: only chunks that are loaded right now are read (a server: what your game has).
     * load = true (single player, the world itself): chunks are brought in from the save as they are needed, so the
     * area can be far bigger than what is around you. It then goes over the area twice: first which heights hold
     * anything, then the blocks.
     */
    public static final class Capture {
        private final Level level;
        private final int x0, z0, x1, z1;
        private int y0, y1;
        private final boolean trimHeight, keepHeight, load;
        private final int cx0, cz0, across, wanted;
        private int sx, sy, sz;
        private char[] cells;
        private java.util.BitSet read;
        private final Reference2IntOpenHashMap<BlockState> numbers = new Reference2IntOpenHashMap<>();
        private final List<BlockState> palette = new ArrayList<>();
        private final List<Held> held = new ArrayList<>();
        private BlockState last;
        private int lastNumber;
        private long blocks;
        private int found;
        // 0 = finding the heights (load only), 1 = reading the blocks
        private volatile int phase;
        private volatile int next;
        private int there, low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;

        /** Stops with a plain sentence when there is nothing to copy, or no room for it. */
        public Capture(Level level, int x0, int y0, int z0, int x1, int y1, int z1, boolean trimHeight, boolean keepHeight, boolean load) {
            this.level = level;
            this.x0 = x0;
            this.z0 = z0;
            this.x1 = x1;
            this.z1 = z1;
            this.trimHeight = trimHeight;
            this.keepHeight = keepHeight;
            this.load = load;
            this.y0 = Math.max(y0, level.getMinY());
            this.y1 = Math.min(y1, level.getMaxY());
            cx0 = x0 >> 4;
            cz0 = z0 >> 4;
            int cx1 = x1 >> 4, cz1 = z1 >> 4;
            across = cx1 - cx0 + 1;
            wanted = across * (cz1 - cz0 + 1);
            numbers.defaultReturnValue(-1);
            palette.add(Blocks.AIR.defaultBlockState());
            if (!trimHeight) {
                // an area picked by hand is taken as picked: its size is known, nothing to look for first
                there = 1;
                low = this.y0;
                high = this.y1;
                room();
            } else if (!load) {
                for (int i = 0; i < wanted; i++) heights(level.getChunkSource().getChunkNow(cx0 + i % across, cz0 + i / across));
                room();
            }
        }

        private LevelChunk chunkAt(int cx, int cz) {
            return load ? level.getChunk(cx, cz) : level.getChunkSource().getChunkNow(cx, cz);
        }

        private void heights(LevelChunk chunk) {
            if (chunk == null) return;
            there++;
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                if (sections[i] == null || sections[i].hasOnlyAir()) continue;
                int base = chunk.getSectionYFromSectionIndex(i) << 4;
                low = Math.min(low, base);
                high = Math.max(high, base + 15);
            }
        }

        /** The heights are known: makes room for the cells. */
        private void room() {
            if (there == 0) throw new IllegalStateException("None of those chunks are loaded.");
            if (low > high) throw new IllegalStateException("Nothing there to copy (only air).");
            if (trimHeight) {
                y0 = Math.max(y0, low);
                y1 = Math.min(y1, high);
            }
            if (y0 > y1) throw new IllegalStateException("Nothing there to copy (only air).");
            sx = x1 - x0 + 1;
            sy = y1 - y0 + 1;
            sz = z1 - z0 + 1;
            long count = (long) sx * sy * sz;
            AreaCopy.room(count);
            cells = new char[(int) count];
            read = new java.util.BitSet(sx * sz);
            phase = 1;
            next = 0;
        }

        public int percent() {
            int done = (int) (100L * next / Math.max(1, wanted));
            return load && trimHeight ? (phase == 0 ? done / 3 : 33 + done * 2 / 3) : done;
        }

        /** Works for about this long. true = all of it is read. */
        public boolean step(long nanos) {
            long until = System.nanoTime() + nanos;
            if (phase == 0) {
                while (next < wanted) {
                    heights(chunkAt(cx0 + next % across, cz0 + next / across));
                    next++;
                    if (System.nanoTime() > until) {
                        // what is known so far already says whether there is room for it: no need to go through
                        // thousands of chunks first only to say no at the end
                        if (low <= high) AreaCopy.room((long) (x1 - x0 + 1) * (z1 - z0 + 1) * (Math.min(y1, high) - Math.max(y0, low) + 1));
                        return false;
                    }
                }
                room();
                return false;
            }
            while (next < wanted) {
                chunk(cx0 + next % across, cz0 + next / across);
                next++;
                if (System.nanoTime() > until) break;
            }
            return next >= wanted;
        }

        private void chunk(int cx, int cz) {
            LevelChunk chunk = chunkAt(cx, cz);
            if (chunk == null) return;
            found++;
            int bx0 = Math.max(x0, cx << 4), bx1 = Math.min(x1, (cx << 4) + 15);
            int bz0 = Math.max(z0, cz << 4), bz1 = Math.min(z1, (cz << 4) + 15);
            for (int z = bz0; z <= bz1; z++) read.set((z - z0) * sx + (bx0 - x0), (z - z0) * sx + (bx1 - x0) + 1);
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                if (section == null || section.hasOnlyAir()) continue;
                int base = chunk.getSectionYFromSectionIndex(i) << 4;
                int by0 = Math.max(y0, base), by1 = Math.min(y1, base + 15);
                for (int y = by0; y <= by1; y++) {
                    for (int z = bz0; z <= bz1; z++) {
                        int at = ((y - y0) * sz + (z - z0)) * sx + (bx0 - x0);
                        for (int x = bx0; x <= bx1; x++, at++) {
                            BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                            if (state.isAir()) continue;
                            int number;
                            if (state == last) {
                                number = lastNumber;
                            } else {
                                number = numbers.getInt(state);
                                if (number < 0) {
                                    number = palette.size();
                                    if (number > Character.MAX_VALUE) throw new IllegalStateException("Too many different blocks in one copy.");
                                    palette.add(state);
                                    numbers.put(state, number);
                                }
                                last = state;
                                lastNumber = number;
                            }
                            cells[at] = (char) number;
                            blocks++;
                        }
                    }
                }
            }
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                BlockPos p = be.getBlockPos();
                if (p.getX() < x0 || p.getX() > x1 || p.getY() < y0 || p.getY() > y1 || p.getZ() < z0 || p.getZ() > z1) continue;
                try {
                    held.add(new Held(p.getX() - x0, p.getY() - y0, p.getZ() - z0, be.saveWithFullMetadata(level.registryAccess())));
                } catch (RuntimeException ignored) {
                    // this one block keeps its contents to itself; the block is still copied
                }
            }
        }

        /** The finished copy. Stops with a plain sentence when it holds nothing. */
        public AreaCopy result() {
            if (blocks == 0) throw new IllegalStateException("Nothing there to copy (only air).");
            AreaCopy out = new AreaCopy(sx, sy, sz, new BlockPos(x0, y0, z0), keepHeight, cells, palette, held, blocks);
            if (trimHeight) out = out.trimmedHeight();
            out.chunksWanted = wanted;
            out.chunksFound = found;
            if (read.cardinality() < sx * sz) {
                java.util.BitSet not = new java.util.BitSet(sx * sz);
                not.set(0, sx * sz);
                not.andNot(read);
                out.missing = not;
            }
            return out;
        }
    }

    /** The same without the empty layers at the bottom and the top. */
    private AreaCopy trimmedHeight() {
        int layer = sizeX * sizeZ;
        int first = 0;
        while (first < cells.length && cells[first] == 0) first++;
        int lastCell = cells.length - 1;
        while (lastCell > first && cells[lastCell] == 0) lastCell--;
        int lowY = first / layer, highY = lastCell / layer;
        if (lowY == 0 && highY == sizeY - 1) return this;
        List<Held> moved = new ArrayList<>(held.size());
        for (Held h : held) moved.add(new Held(h.x(), h.y() - lowY, h.z(), h.data()));
        return new AreaCopy(sizeX, highY - lowY + 1, sizeZ, from.above(lowY), keepHeight, Arrays.copyOfRange(cells, lowY * layer, (highY + 1) * layer),
            palette, moved, blocks);
    }

    /** Blocks given by place (counted from anywhere): the box around them. Its from() is their lowest corner. */
    public static AreaCopy ofMap(Map<BlockPos, BlockState> map) {
        if (map.isEmpty()) throw new IllegalStateException("There are no blocks in it.");
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos p : map.keySet()) {
            x0 = Math.min(x0, p.getX());
            y0 = Math.min(y0, p.getY());
            z0 = Math.min(z0, p.getZ());
            x1 = Math.max(x1, p.getX());
            y1 = Math.max(y1, p.getY());
            z1 = Math.max(z1, p.getZ());
        }
        int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
        long count = (long) sx * sy * sz;
        room(count);
        char[] cells = new char[(int) count];
        Reference2IntOpenHashMap<BlockState> numbers = new Reference2IntOpenHashMap<>();
        numbers.defaultReturnValue(-1);
        List<BlockState> palette = new ArrayList<>();
        palette.add(Blocks.AIR.defaultBlockState());
        long blocks = 0;
        for (Map.Entry<BlockPos, BlockState> e : map.entrySet()) {
            if (e.getValue().isAir()) continue;
            int number = numbers.getInt(e.getValue());
            if (number < 0) {
                number = palette.size();
                palette.add(e.getValue());
                numbers.put(e.getValue(), number);
            }
            BlockPos p = e.getKey();
            cells[((p.getY() - y0) * sz + (p.getZ() - z0)) * sx + (p.getX() - x0)] = (char) number;
            blocks++;
        }
        return new AreaCopy(sx, sy, sz, new BlockPos(x0, y0, z0), false, cells, palette, new ArrayList<>(), blocks);
    }

    // ------------------------------------------------------------------ the file

    /** Writes it as name.schem into the folder (a name that is taken gets a number behind it). Returns the file. */
    public Path write(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        String base = name.replaceAll("[^A-Za-z0-9 _.-]", "_").trim();
        if (base.isEmpty()) base = "schematic";
        Path file = dir.resolve(base + ".schem");
        for (int n = 2; Files.exists(file); n++) file = dir.resolve(base + "_" + n + ".schem");
        // the cells as numbers of 7 bits a byte (the top bit says "more follows"): first how long that gets
        long length = 0;
        for (char c : cells) length += c < 128 ? 1 : c < 16384 ? 2 : 3;
        if (length > Integer.MAX_VALUE - 64) throw new IOException("Too big for one file - take fewer chunks");
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 2);
        root.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        root.putShort("Width", (short) sizeX);
        root.putShort("Height", (short) sizeY);
        root.putShort("Length", (short) sizeZ);
        root.put("Offset", new IntArrayTag(new int[] {from.getX(), from.getY(), from.getZ()}));
        CompoundTag meta = new CompoundTag();
        meta.putString("Name", base);
        meta.putBoolean("RelaxedKeepHeight", keepHeight);
        if (missing != null) meta.put("RelaxedNotCopied", new ByteArrayTag(missing.toByteArray()));
        root.put("Metadata", meta);
        CompoundTag names = new CompoundTag();
        for (int i = 0; i < palette.size(); i++) names.putInt(BlockStateParser.serialize(palette.get(i)), i);
        root.putInt("PaletteMax", palette.size());
        root.put("Palette", names);
        ListTag list = new ListTag();
        for (Held h : held) {
            CompoundTag e = h.data().copy();
            String id = Schematic.str(e.get("id"));
            e.remove("id");
            e.remove("x");
            e.remove("y");
            e.remove("z");
            e.putString("Id", id);
            e.put("Pos", new IntArrayTag(new int[] {h.x(), h.y(), h.z()}));
            list.add(e);
        }
        root.put("BlockEntities", list);
        // The file is written tag by tag, the cells straight from where they are held: handing the game one
        // array of all of them to write would take as much memory again as the copy itself. The sizes and the
        // list of blocks come before the cells, so that reading can put the cells in place as they come.
        Path part = file.resolveSibling(file.getFileName() + ".part");
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
            new java.util.zip.GZIPOutputStream(Files.newOutputStream(part), 1 << 16), 1 << 16))) {
            out.writeByte(Tag.TAG_COMPOUND);
            out.writeUTF("");
            for (String key : new String[] {"Version", "DataVersion", "Width", "Height", "Length", "Offset", "Metadata", "PaletteMax", "Palette"}) tag(out, key, root.get(key));
            out.writeByte(Tag.TAG_BYTE_ARRAY);
            out.writeUTF("BlockData");
            out.writeInt((int) length);
            byte[] buffer = new byte[1 << 16];
            int o = 0;
            for (char c : cells) {
                if (o > buffer.length - 3) {
                    out.write(buffer, 0, o);
                    o = 0;
                }
                int number = c;
                while ((number & -128) != 0) {
                    buffer[o++] = (byte) (number & 127 | 128);
                    number >>>= 7;
                }
                buffer[o++] = (byte) number;
            }
            out.write(buffer, 0, o);
            tag(out, "BlockEntities", list);
            out.writeByte(Tag.TAG_END);
        } catch (IOException | RuntimeException | Error e) {
            Files.deleteIfExists(part);
            throw e;
        }
        Files.move(part, file);
        return file;
    }

    private static void tag(java.io.DataOutputStream out, String key, Tag tag) throws IOException {
        out.writeByte(tag.getId());
        out.writeUTF(key);
        tag.write(out);
    }

    /**
     * The tags of a schematic file. The cells of a Sponge version 2 file are not among them when they could be read
     * straight into place (cells, still in the numbers of the file): the game reading them into a tag first would
     * take as much memory again.
     */
    private static final class Parsed {
        final CompoundTag root = new CompoundTag();
        char[] cells;
    }

    private static Parsed parse(java.io.InputStream stream) throws IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(stream, 1 << 16));
        if (in.readByte() != Tag.TAG_COMPOUND) return null;
        in.readUTF();
        Parsed out = new Parsed();
        NbtAccounter accounter = NbtAccounter.unlimitedHeap();
        while (true) {
            byte type = in.readByte();
            if (type == Tag.TAG_END) break;
            String key = in.readUTF();
            long count = (long) (Schematic.num(out.root.get("Width")) & 0xFFFF) * (Schematic.num(out.root.get("Height")) & 0xFFFF) * (Schematic.num(out.root.get("Length")) & 0xFFFF);
            if (type != Tag.TAG_BYTE_ARRAY || !key.equals("BlockData") || out.cells != null || count <= 0) {
                // (cells that come before the sizes land here too: they are read the plain way further on)
                out.root.put(key, net.minecraft.nbt.TagTypes.getType(type).load(in, accounter));
                continue;
            }
            room(count);
            char[] cells = new char[(int) count];
            long left = in.readInt();
            byte[] buffer = new byte[1 << 16];
            int index = 0, value = 0, shift = 0;
            while (left > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (n < 0) throw new java.io.EOFException("The schematic file ends too early");
                left -= n;
                for (int i = 0; i < n; i++) {
                    byte b = buffer[i];
                    value |= (b & 0x7F) << shift;
                    if ((b & 0x80) != 0 && shift < 28) {
                        shift += 7;
                        continue;
                    }
                    if (index < cells.length) cells[index++] = value >= 0 && value < Character.MAX_VALUE ? (char) value : Character.MAX_VALUE;
                    value = 0;
                    shift = 0;
                }
            }
            out.cells = cells;
        }
        return out;
    }

    /** Reads a Sponge .schem file (version 2 or 3) straight into the small form. null = the file is another format. */
    public static AreaCopy read(Path file) throws IOException {
        synchronized (Schematic.class) {
            Parsed parsed;
            try (java.io.InputStream in = new java.util.zip.GZIPInputStream(Files.newInputStream(file), 1 << 16)) {
                parsed = parse(in);
            } catch (java.util.zip.ZipException notCompressed) {
                try (java.io.InputStream in = Files.newInputStream(file)) {
                    parsed = parse(in);
                }
            }
            if (parsed == null) return null;
            CompoundTag root = parsed.root;
            CompoundTag tag;
            int version;
            if (root.get("Schematic") instanceof CompoundTag inner) {
                tag = inner;
                version = 3;
            } else if (root.get("Palette") instanceof CompoundTag && (parsed.cells != null || root.get("BlockData") instanceof ByteArrayTag)) {
                tag = root;
                version = 2;
            } else {
                return null;
            }
            Schematic.readingVersion(Schematic.num(tag.get("DataVersion")));
            int w = Schematic.num(tag.get("Width")) & 0xFFFF, h = Schematic.num(tag.get("Height")) & 0xFFFF, l = Schematic.num(tag.get("Length")) & 0xFFFF;
            CompoundTag blocksTag = version >= 3 ? Schematic.compound(tag, "Blocks") : tag;
            CompoundTag names = Schematic.compound(blocksTag, "Palette");
            long count = (long) w * h * l;
            if (count <= 0) throw new IOException("The schematic has no blocks");
            // the numbers of the file -> ours (0 = nothing; a block this game does not know is left out)
            int top = 0;
            for (String key : names.keySet()) top = Math.max(top, Schematic.num(names.get(key)));
            if (top >= Character.MAX_VALUE) throw new IOException("Too many different blocks in this file");
            char[] ours = new char[top + 1];
            List<BlockState> palette = new ArrayList<>();
            palette.add(Blocks.AIR.defaultBlockState());
            for (String key : names.keySet()) {
                BlockState state = Schematic.paletteState(key);
                if (state == null || state.isAir()) continue;
                ours[Schematic.num(names.get(key))] = (char) palette.size();
                palette.add(state);
            }
            char[] cells = version == 2 ? parsed.cells : null;
            long blocks = 0;
            if (cells != null) {
                // read straight into place: only the numbers of the file still have to become ours
                for (int i = 0; i < cells.length; i++) {
                    char number = cells[i] < ours.length ? ours[cells[i]] : 0;
                    cells[i] = number;
                    if (number != 0) blocks++;
                }
            } else {
                byte[] data = blocksTag.get(version >= 3 ? "Data" : "BlockData") instanceof ByteArrayTag b ? b.getAsByteArray() : new byte[0];
                room(count);
                cells = new char[(int) count];
                int index = 0, i = 0;
                while (i < data.length && index < cells.length) {
                    int value = 0, shift = 0;
                    byte b;
                    do {
                        b = data[i++];
                        value |= (b & 0x7F) << shift;
                        shift += 7;
                    } while ((b & 0x80) != 0 && i < data.length);
                    char number = value >= 0 && value < ours.length ? ours[value] : 0;
                    if (number != 0) {
                        cells[index] = number;
                        blocks++;
                    }
                    index++;
                }
            }
            if (blocks == 0) throw new IOException("The schematic has no blocks");
            List<Held> held = new ArrayList<>();
            Tag listTag = blocksTag.get("BlockEntities");
            if (listTag == null) listTag = tag.get("TileEntities");
            if (listTag instanceof CollectionTag list) {
                for (int k = 0; k < list.size(); k++) {
                    if (!(list.get(k) instanceof CompoundTag e)) continue;
                    int[] p = Schematic.ints(e.get("Pos"));
                    if (p.length < 3) continue;
                    CompoundTag body = e.get("Data") instanceof CompoundTag d ? d.copy() : e.copy();
                    body.remove("Pos");
                    body.remove("Id");
                    body.remove("Data");
                    String id = Schematic.str(e.get("Id"));
                    if (id.isEmpty()) id = Schematic.str(e.get("id"));
                    if (id.isEmpty()) continue;
                    body.putString("id", id);
                    held.add(new Held(p[0], p[1], p[2], body));
                }
            }
            int[] offset = Schematic.ints(tag.get("Offset"));
            BlockPos from = offset.length >= 3 ? new BlockPos(offset[0], offset[1], offset[2]) : BlockPos.ZERO;
            boolean keep = Schematic.compound(tag, "Metadata").get("RelaxedKeepHeight") != null && Schematic.num(Schematic.compound(tag, "Metadata").get("RelaxedKeepHeight")) != 0;
            AreaCopy out = new AreaCopy(w, h, l, from, keep, cells, palette, held, blocks);
            if (Schematic.compound(tag, "Metadata").get("RelaxedNotCopied") instanceof ByteArrayTag not) out.missing = java.util.BitSet.valueOf(not.getAsByteArray());
            return out;
        }
    }
}
