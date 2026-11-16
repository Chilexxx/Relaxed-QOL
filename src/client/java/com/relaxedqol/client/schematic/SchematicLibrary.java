package com.relaxedqol.client.schematic;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Schematics Library: make your own schematic files from what stands in the world - an area between two corners
 * you pick, or the whole chunks around you - and, in single player, put a schematic file into the world in one go.
 *
 * Copying reads only what your game already has; nothing is asked from the server. Big copies (hundreds of chunks,
 * millions of blocks) are held small (AreaCopy), written on a side thread and pasted a slice every tick, so the game
 * keeps running meanwhile.
 */
public final class SchematicLibrary {
    private SchematicLibrary() {}

    /**
     * Chunks out from the one you stand in that one copy takes at most (50 = 101 x 101 chunks). How much really
     * fits is a matter of memory: a copy that would not fit is refused with a line saying what it needs.
     */
    public static final int MAX_RADIUS = 50;

    private static BlockPos corner1;
    private static BlockPos corner2;

    public static BlockPos corner1() {
        return corner1;
    }

    public static BlockPos corner2() {
        return corner2;
    }

    /** Where saved copies go (our own schematics folder; the window and .schem list show it). */
    public static Path folder() {
        return Schematic.folders().get(1);
    }

    /** The block you look at, or the one your feet are in. */
    private static BlockPos pointed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) return hit.getBlockPos().immutable();
        return mc.player.blockPosition();
    }

    public static String setCorner(int which) {
        BlockPos at = pointed();
        if (at == null) return "§cNot in a world.";
        if (which == 1) corner1 = at;
        else corner2 = at;
        cornersAt = System.currentTimeMillis();
        String out = "§aCorner " + which + " §7at §f" + at.toShortString();
        if (corner1 != null && corner2 != null) out += "§7 - area " + areaLabel() + ". §f.schem save <name>";
        return out;
    }

    private static long cornersAt;

    /** When a corner was last set (the box between them shows for a while after). */
    public static long cornersAt() {
        return cornersAt;
    }

    /** A corner on an exact block (the select mode: a click). */
    public static void setCornerAt(int which, BlockPos at) {
        if (which == 1) corner1 = at.immutable();
        else corner2 = at.immutable();
        cornersAt = System.currentTimeMillis();
    }

    public static String areaLabel() {
        if (corner1 == null && corner2 == null) return "No area picked";
        if (corner1 == null || corner2 == null) return "One corner picked";
        return (Math.abs(corner1.getX() - corner2.getX()) + 1) + " x " + (Math.abs(corner1.getY() - corner2.getY()) + 1) + " x "
            + (Math.abs(corner1.getZ() - corner2.getZ()) + 1);
    }

    private static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** Says the line on the game thread (the window or the chat shows it). */
    private static void tell(Consumer<String> report, String line) {
        Minecraft.getInstance().execute(() -> report.accept(line));
    }

    // ---------------------------------------------------------------- Low Impact

    /**
     * Low Impact (a setting of the menu entry, which hands it in here): copies and pastes are a little slower, and in
     * return cost next to no memory - the cells of the copy lie in a file on disk instead of in the game's memory -
     * and leave the frame rate alone: shorter slices, fewer chunks prepared at a time, and both give way at the
     * first dip of the frame rate.
     * A copy or paste works the way this stood when it started.
     */
    public static java.util.function.BooleanSupplier lowImpactOn = () -> false;
    public static Consumer<Boolean> lowImpactSet = on -> {};

    public static boolean lowImpact() {
        return lowImpactOn.getAsBoolean();
    }

    /** "on" / "off", or null to switch over. The line to show. */
    public static String setLowImpact(String want) {
        boolean on = want == null ? !lowImpact() : want.equalsIgnoreCase("on") || want.equalsIgnoreCase("true") || want.equals("1");
        lowImpactSet.accept(on);
        return on ? "§aLow Impact on§7 - copies and pastes are a little slower, but use next to no memory and keep the frame rate steady. Counts from the next copy or paste."
            : "§eLow Impact off§7 - copies and pastes at full speed.";
    }

    /**
     * How many of the chunks that come next a job asks for ahead of time, at most. Getting a chunk - from the
     * save, or made new where nobody has been - is what a paste or a single-player copy waits for most, and the
     * game does that on its other threads: asked for one at a time they sit idle, asked for ahead of time they
     * prepare several at once. Low Impact asks for a few only, so the other threads stay mostly free.
     */
    private static int aheadMost(boolean light) {
        int threads = Runtime.getRuntime().availableProcessors();
        return light ? Math.max(2, Math.min(6, threads / 2)) : Math.max(8, Math.min(48, threads * 2));
    }

    // ---------------------------------------------------------------- a slice every tick

    static {
        // copies and pastes run a slice a tick from here, so the game keeps its frame rate while they are busy
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(SchematicLibrary::tick);
    }

    private static int ticks;

    private static void tick(Minecraft mc) {
        ticks++;
        Copying c = copying;
        if (c != null && c.capture != null && !c.writing) {
            if (mc.level == null || (c.server == null && mc.level != c.level)) {
                drop(c); // left the world it was copying
            } else if (c.server == null) {
                advance(c, c.light ? 3_000_000L : 6_000_000L); // on the game thread itself: 6 ms of a 50 ms tick (Low Impact: 3)
            } else if (!c.queued) {
                c.queued = true;
                c.server.execute(() -> {
                    try {
                        advance(c, c.light ? 8_000_000L : 20_000_000L);
                    } finally {
                        c.queued = false;
                    }
                });
            }
        }
        Paste job = pasting;
        if (job != null && job.copy != null) {
            if (mc.level == null || mc.getSingleplayerServer() != job.server) {
                pasting = null; // left that world
            } else {
                if (ticks % 20 == 0) job.pace(mc.getFps());
                if (!job.queued) {
                    job.queued = true;
                    long nanos = (long) (job.millis * 1_000_000);
                    job.server.execute(() -> {
                        try {
                            job.step(nanos);
                        } finally {
                            job.queued = false;
                        }
                    });
                }
                if (ticks % 200 == 0 && pasting == job) job.report.accept("§7" + pasteState());
            }
        }
    }

    // ---------------------------------------------------------------- copying

    /** One copy under way: read a few chunks a tick, then written on a side thread. */
    private static final class Copying {
        final MinecraftServer server; // null = the level of the game window, read on the game thread
        final net.minecraft.world.level.Level level;
        final String name;
        final Consumer<String> report;
        final boolean light = lowImpact();
        volatile AreaCopy.Capture capture;
        volatile boolean queued;
        volatile boolean writing;

        Copying(MinecraftServer server, net.minecraft.world.level.Level level, String name, Consumer<String> report) {
            this.server = server;
            this.level = level;
            this.name = name;
            this.report = report;
        }
    }

    /** Ends a copy that is not going to be written: what it has read so far is let go of (on the thread that reads). */
    private static void drop(Copying c) {
        if (copying == c) copying = null;
        AreaCopy.Capture capture = c.capture;
        if (capture == null) return;
        if (c.server == null) capture.close();
        else c.server.execute(capture::close);
    }


    private static volatile Copying copying;

    /** "Copying: 43%", or null when no copy is being taken. */
    public static String copyState() {
        Copying c = copying;
        if (c == null) return null;
        AreaCopy.Capture capture = c.capture;
        return (c.writing || capture == null ? "Writing the file" : "Copying: " + capture.percent() + "%") + (c.light ? " (Low Impact)" : "");
    }

    /** Ends the copy that is being taken (nothing is written). */
    public static String stopCopy() {
        Copying c = copying;
        if (c == null || c.writing) return "§7No copy is being taken.";
        drop(c);
        return "§eCopy stopped.";
    }

    /** Saves the picked area exactly as picked. The line to show goes to report when it is done. */
    public static void saveArea(String name, Consumer<String> report) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            report.accept("§cNot in a world.");
            return;
        }
        if (corner1 == null || corner2 == null) {
            report.accept("§cPick two corners first (§f.schem pos1§c and §f.schem pos2§c, looking at a block).");
            return;
        }
        save(Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()),
            Math.max(corner1.getX(), corner2.getX()), Math.max(corner1.getY(), corner2.getY()), Math.max(corner1.getZ(), corner2.getZ()), false, name, report);
    }

    /**
     * Saves the loaded chunks around you, bedrock to sky: radius 0 = the one you stand in, 1 = 3 x 3, 6 = 13 x 13...
     * up to MAX_RADIUS. Works on a server too (a spawn, a base you visit): what your game has loaded is copied, and
     * the file can be pasted in single player. Chunks that are not loaded stay empty (the line says how many were).
     */
    public static void saveChunks(int radius, String name, Consumer<String> report) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            report.accept("§cNot in a world.");
            return;
        }
        radius = Math.max(0, Math.min(radius, MAX_RADIUS));
        int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
        save((cx - radius) << 4, mc.level.getMinY(), (cz - radius) << 4, ((cx + radius) << 4) + 15, mc.level.getMaxY(), ((cz + radius) << 4) + 15, true, name, report);
    }

    private static void save(int x0, int y0, int z0, int x1, int y1, int z1, boolean chunks, String name, Consumer<String> report) {
        Minecraft mc = Minecraft.getInstance();
        if (copying != null) {
            report.accept("§eStill busy with the last copy (" + copyState() + ") - one moment, or §f.schem cancel§e.");
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        ServerLevel world = server == null ? null : server.getLevel(mc.level.dimension());
        if (world != null) {
            // single player: the world itself is read (on its own thread), so chests keep their contents
            Copying job = new Copying(server, world, name, report);
            copying = job;
            server.execute(() -> open(job, () -> new AreaCopy.Capture(world, x0, y0, z0, x1, y1, z1, chunks, chunks, true, job.light).ahead(aheadMost(job.light))));
        } else {
            net.minecraft.world.level.Level level = mc.level;
            Copying job = new Copying(null, level, name, report);
            copying = job;
            open(job, () -> new AreaCopy.Capture(level, x0, y0, z0, x1, y1, z1, chunks, chunks, false, job.light));
        }
    }

    private static void open(Copying job, Supplier<AreaCopy.Capture> make) {
        try {
            AreaCopy.Capture made = make.get();
            job.capture = made;
            if (copying != job) made.close(); // stopped before it began
        } catch (IllegalStateException e) {
            copying = null;
            tell(job.report, "§c" + e.getMessage());
        } catch (OutOfMemoryError e) {
            copying = null;
            tell(job.report, "§cNot enough memory for that much - take fewer chunks, or give the game more memory.");
        }
    }

    /** Reads on for a moment; when every chunk is read, the file is written on a side thread. */
    private static void advance(Copying job, long nanos) {
        AreaCopy.Capture capture = job.capture;
        if (capture == null || job.writing || copying != job) return;
        try {
            if (!capture.step(nanos)) return;
        } catch (RuntimeException e) {
            copying = null;
            capture.close();
            tell(job.report, "§cThe copy stopped: §f" + e.getMessage());
            return;
        }
        job.writing = true;
        Thread t = new Thread(() -> {
            String line;
            try {
                AreaCopy copy = capture.result();
                Path file = copy.write(folder(), job.name);
                String base = file.getFileName().toString().replace(".schem", "");
                line = "§aSaved §f" + file.getFileName() + "§a - " + count(copy.blocks) + " blocks, " + copy.sizeX + " x " + copy.sizeY + " x " + copy.sizeZ
                    + (copy.chunksWanted > 1 ? ", " + copy.chunksFound + (copy.chunksFound < copy.chunksWanted ? " of " + copy.chunksWanted : "") + " chunks" : "")
                    + (copy.held.isEmpty() ? "" : ", " + copy.held.size() + " with contents")
                    + "§7. §f.schem preview " + base + "§7 puts it into a single-player world.";
            } catch (IllegalStateException e) {
                line = "§c" + e.getMessage();
            } catch (IOException | RuntimeException e) {
                line = "§cCould not save it: §f" + e.getMessage();
            } catch (OutOfMemoryError e) {
                line = "§cNot enough memory to write that file - take fewer chunks, give the game more memory, or turn on Low Impact (.schem lowimpact).";
            } finally {
                copying = null;
                capture.close();
            }
            tell(job.report, line);
        }, "Relaxed-Schematic-Save");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- pasting (single player)

    /**
     * Blocks go in without anything else happening: nothing reshapes, drops, falls or starts to flow - an exact
     * copy. The game window is not told block by block either (that is what makes a big paste crawl): every chunk
     * is sent to it whole, once, when it is finished.
     */
    private static final int EXACT = Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

    private static volatile Paste pasting;

    /** "Pasting: 43%", or null when nothing is being pasted. */
    public static String pasteState() {
        Paste job = pasting;
        if (job == null) return null;
        return (job.copy == null ? "Reading the file" : "Pasting: " + (int) (100L * job.chunkAt / Math.max(1, job.chunks.length)) + "%") + (job.light ? " (Low Impact)" : "");
    }

    public static String stopPaste() {
        Paste job = pasting;
        if (job == null) return "§7Nothing is being pasted.";
        job.stop = true;
        return "§eStopping the paste.";
    }

    /**
     * Single player: puts the schematic file into the world - its lowest corner on at, turned quarterTurns times
     * (clockwise, the way the builder turns it). at = null: the corner goes where you stand; whole-chunk copies keep
     * the height they were copied at. withAir also empties the cells of its box that the schematic leaves empty.
     * The blocks arrive as they are in the file, chests get their contents back, and it goes in chunk by chunk, a
     * slice every tick: the game keeps running meanwhile.
     */
    public static void paste(Path file, BlockPos at, int quarterTurns, boolean withAir, Consumer<String> report) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            report.accept("§cNot in a world.");
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            report.accept("§ePaste works in single player only.");
            return;
        }
        ServerLevel world = server.getLevel(mc.level.dimension());
        if (world == null) {
            report.accept("§cThis world is not there on the server side.");
            return;
        }
        if (pasting != null) {
            report.accept("§e" + pasteState() + " - §f.schem paste stop§e to end it.");
            return;
        }
        BlockPos feet = mc.player.blockPosition();
        Paste job = new Paste(server, world, Math.floorMod(quarterTurns, 4), withAir, report);
        pasting = job;
        Thread t = new Thread(() -> {
            AreaCopy copy = null;
            try {
                copy = AreaCopy.read(file, job.light);
                if (copy == null) copy = AreaCopy.ofMap(Schematic.load(file).blocks); // another format: by way of the reader for it
                BlockPos corner = at != null ? at : new BlockPos(feet.getX(), copy.keepHeight ? copy.from.getY() : feet.getY(), feet.getZ());
                tell(report, "§7Pasting §f" + count(copy.blocks) + "§7 blocks at §f" + corner.toShortString() + "§7" + (job.light ? " (Low Impact)" : "") + "...");
                job.begin(copy, corner, feet);
            } catch (IOException | RuntimeException e) {
                pasting = null;
                if (copy != null) copy.close();
                tell(report, "§cCould not paste it: §f" + e.getMessage());
            } catch (OutOfMemoryError e) {
                pasting = null;
                tell(report, "§cNot enough memory to paste that file - give the game more memory, or turn on Low Impact (.schem lowimpact).");
            }
        }, "Relaxed-Schematic-Read");
        t.setDaemon(true);
        t.start();
    }

    /** The same for a copy that is read already (the paste placement had it open for its preview). */
    public static void paste(AreaCopy copy, BlockPos at, int quarterTurns, boolean withAir, Consumer<String> report) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        ServerLevel world = server == null || mc.level == null || mc.player == null ? null : server.getLevel(mc.level.dimension());
        if (world == null) {
            copy.close();
            report.accept("§ePaste works in single player only.");
            return;
        }
        if (pasting != null) {
            copy.close();
            report.accept("§e" + pasteState() + " - §f.schem paste stop§e to end it.");
            return;
        }
        Paste job = new Paste(server, world, Math.floorMod(quarterTurns, 4), withAir, report);
        pasting = job;
        report.accept("§7Pasting §f" + count(copy.blocks) + "§7 blocks at §f" + at.toShortString() + "§7...");
        job.begin(copy, at, mc.player.blockPosition());
    }

    /** One paste under way: chunk after chunk (the nearest first), a slice every tick, so the world keeps running. */
    private static final class Paste {
        final MinecraftServer server;
        final ServerLevel world;
        final int turns;
        boolean withAir;
        final Consumer<String> report;
        BlockPos corner;
        BlockState[] states;
        int outX, outZ;
        long[] chunks;
        java.util.Map<Long, List<AreaCopy.Held>> held = new java.util.HashMap<>();
        volatile AreaCopy copy; // set last: from then on the ticks work on it
        volatile int chunkAt;
        int column;
        net.minecraft.world.level.chunk.LevelChunk chunk;
        boolean chunkChanged;
        long changed;
        int filled;
        long started;
        volatile boolean stop;
        volatile boolean queued;
        // Low Impact: short slices that give way as soon as the frame rate dips, and no pile of loaded chunks
        final boolean light = lowImpact();
        // how long a slice may take, and how many chunks are asked for ahead of time: both steered by the frame rate
        volatile double millis = light ? 5 : 12;
        double best;
        final int aheadMost = SchematicLibrary.aheadMost(light);
        volatile int ahead = Math.max(2, aheadMost / 2);
        int waits;

        Paste(MinecraftServer server, ServerLevel world, int turns, boolean withAir, Consumer<String> report) {
            this.server = server;
            this.world = world;
            this.turns = turns;
            this.withAir = withAir;
            this.report = report;
        }

        void begin(AreaCopy c, BlockPos at, BlockPos near) {
            Rotation rot = switch (turns) {
                case 1 -> Rotation.CLOCKWISE_90;
                case 2 -> Rotation.CLOCKWISE_180;
                case 3 -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
            };
            states = new BlockState[c.palette.size()];
            for (int i = 0; i < states.length; i++) states[i] = c.palette.get(i).rotate(rot);
            corner = at;
            withAir |= c.keepHeight; // whole chunks take the place of what stood there
            outX = turns % 2 == 1 ? c.sizeZ : c.sizeX;
            outZ = turns % 2 == 1 ? c.sizeX : c.sizeZ;
            int cx0 = at.getX() >> 4, cz0 = at.getZ() >> 4, cx1 = (at.getX() + outX - 1) >> 4, cz1 = (at.getZ() + outZ - 1) >> 4;
            List<Long> list = new ArrayList<>();
            for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) list.add(net.minecraft.world.level.ChunkPos.pack(cx, cz));
            int px = near.getX() >> 4, pz = near.getZ() >> 4;
            list.sort(Comparator.comparingLong(k -> {
                long dx = net.minecraft.world.level.ChunkPos.getX(k) - px, dz = net.minecraft.world.level.ChunkPos.getZ(k) - pz;
                return dx * dx + dz * dz;
            }));
            chunks = new long[list.size()];
            for (int i = 0; i < chunks.length; i++) chunks[i] = list.get(i);
            for (AreaCopy.Held h : c.held) {
                held.computeIfAbsent(net.minecraft.world.level.ChunkPos.pack(worldX(c, h.x(), h.z()) >> 4, worldZ(c, h.x(), h.z()) >> 4), k -> new ArrayList<>()).add(h);
            }
            started = System.currentTimeMillis();
            copy = c;
        }

        /** Where the cell x z of the file stands in the world after turning. */
        private int worldX(AreaCopy c, int x, int z) {
            return corner.getX() + switch (turns) {
                case 1 -> c.sizeZ - 1 - z;
                case 2 -> c.sizeX - 1 - x;
                case 3 -> z;
                default -> x;
            };
        }

        private int worldZ(AreaCopy c, int x, int z) {
            return corner.getZ() + switch (turns) {
                case 1 -> x;
                case 2 -> c.sizeZ - 1 - z;
                case 3 -> c.sizeX - 1 - x;
                default -> z;
            };
        }

        /**
         * Once a second: the frame rate against the best seen while pasting. Clearly lower: shorter slices; as good
         * as it gets: longer ones. So a strong computer pastes fast and a weak one stays smooth.
         */
        void pace(int fps) {
            best = Math.max(fps, best * 0.9); // a frame rate that fell for another reason becomes the new normal after a while
            if (light) {
                // gives way at the first dip, and takes a fifth of a tick at most
                if (fps < best * 0.9) {
                    millis = Math.max(2, millis * 0.6);
                    ahead = Math.max(1, ahead / 2);
                } else if (fps > best * 0.96) {
                    millis = Math.min(10, millis * 1.25);
                    ahead = Math.min(aheadMost, ahead + 1);
                }
            } else if (fps < best * 0.7) {
                millis = Math.max(3, millis * 0.6);
                ahead = Math.max(4, ahead / 2);
            } else if (fps > best * 0.9) {
                millis = Math.min(35, millis * 1.25);
                ahead = Math.min(aheadMost, ahead + 4);
            }
        }

        void step(long nanos) {
            if (pasting != this) return;
            try {
                work(nanos);
            } catch (RuntimeException e) {
                pasting = null;
                copy.close();
                tell(report, "§cThe paste stopped: §f" + e);
            }
        }

        private void work(long nanos) {
            long until = System.nanoTime() + nanos;
            AreaCopy c = copy;
            Cells cells = c.cells;
            int sx = c.sizeX, layer = sx * c.sizeZ;
            // the layers of the file that lie inside the world
            int yFrom = Math.max(0, world.getMinY() - corner.getY()), yTo = Math.min(c.sizeY - 1, world.getMaxY() - corner.getY());
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            // the chunk in hand and the next ones: asked for now, so they are there (or on their way) when their turn comes
            for (int i = chunkAt, to = Math.min(chunks.length, chunkAt + 1 + ahead); i < to && !stop; i++) {
                world.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PLAYER_SPAWN,
                    new net.minecraft.world.level.ChunkPos(net.minecraft.world.level.ChunkPos.getX(chunks[i]), net.minecraft.world.level.ChunkPos.getZ(chunks[i])), 0);
            }
            while (chunkAt < chunks.length && !stop) {
                int cx = net.minecraft.world.level.ChunkPos.getX(chunks[chunkAt]), cz = net.minecraft.world.level.ChunkPos.getZ(chunks[chunkAt]);
                if (chunk == null) {
                    chunk = world.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) {
                        // on its way: the next tick finds it, and the world keeps ticking instead of standing and waiting
                        if (++waits < 60) break;
                        chunk = world.getChunk(cx, cz); // three seconds and still not there: fetched the plain way
                    }
                    waits = 0;
                }
                boolean late = false;
                for (; column < 256 && !late; column++) {
                    int wx = (cx << 4) + (column & 15), wz = (cz << 4) + (column >> 4);
                    int dx = wx - corner.getX(), dz = wz - corner.getZ();
                    if (dx < 0 || dz < 0 || dx >= outX || dz >= outZ) continue;
                    int x, z;
                    switch (turns) {
                        case 1 -> {
                            x = dz;
                            z = c.sizeZ - 1 - dx;
                        }
                        case 2 -> {
                            x = c.sizeX - 1 - dx;
                            z = c.sizeZ - 1 - dz;
                        }
                        case 3 -> {
                            x = c.sizeX - 1 - dz;
                            z = dx;
                        }
                        default -> {
                            x = dx;
                            z = dz;
                        }
                    }
                    int base = z * sx + x;
                    if (c.missing != null && c.missing.get(base)) continue; // its chunk was not loaded when copying: left as it is
                    for (int y = yFrom; y <= yTo; y++) {
                        int number = cells.get(y * layer + base);
                        if (number == 0 && !withAir) continue;
                        p.set(wx, corner.getY() + y, wz);
                        BlockState state = states[number];
                        if (chunk.getBlockState(p) == state) continue;
                        if (world.setBlock(state.hasBlockEntity() ? p.immutable() : p, state, EXACT)) {
                            changed++;
                            chunkChanged = true;
                        }
                    }
                    late = (column & 15) == 15 && System.nanoTime() > until;
                }
                if (column >= 256) {
                    finishChunk(c, chunks[chunkAt]);
                    chunkAt++;
                    column = 0;
                    chunk = null;
                    chunkChanged = false;
                }
                if (late || System.nanoTime() > until) break;
            }
            if (stop) {
                if (chunk != null && chunkChanged) show(chunk);
                pasting = null;
                c.close();
                tell(report, "§ePaste stopped at " + (int) (100L * chunkAt / Math.max(1, chunks.length)) + "% (" + count(changed) + " blocks changed).");
            } else if (chunkAt >= chunks.length) {
                pasting = null;
                c.close();
                tell(report, "§aPasted §f" + count(c.blocks) + "§a blocks at §f" + corner.toShortString() + "§a in "
                    + String.format(Locale.ROOT, "%.1f", (System.currentTimeMillis() - started) / 1000.0) + " s§7 (" + count(changed) + " changed"
                    + (filled > 0 ? ", " + filled + " with contents" : "") + ").");
            }
        }

        /** A chunk is done: its chests, signs... get what they held, then the game window gets the whole chunk at once. */
        private void finishChunk(AreaCopy c, long key) {
            List<AreaCopy.Held> here = held.get(key);
            if (here != null) {
                for (AreaCopy.Held h : here) {
                    BlockPos at = new BlockPos(worldX(c, h.x(), h.z()), corner.getY() + h.y(), worldZ(c, h.x(), h.z()));
                    BlockEntity be = world.getBlockEntity(at);
                    if (be == null) continue;
                    try {
                        CompoundTag data = h.data().copy();
                        data.putInt("x", at.getX());
                        data.putInt("y", at.getY());
                        data.putInt("z", at.getZ());
                        be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), data));
                        be.setChanged();
                        filled++;
                        chunkChanged = true;
                    } catch (RuntimeException ignored) {
                        // contents this game cannot read (an older version): the block stands, empty
                    }
                }
            }
            if (chunk != null && chunkChanged) show(chunk);
        }

        private void show(net.minecraft.world.level.chunk.LevelChunk done) {
            done.markUnsaved();
            for (net.minecraft.server.level.ServerPlayer player : world.getChunkSource().chunkMap.getPlayers(done.getPos(), false)) {
                player.connection.chunkSender.markChunkPendingToSend(done);
            }
        }
    }

    // ---------------------------------------------------------------- the file list

    public enum Sort { NAME, NEWEST, LARGEST }

    /** The schematic files, in this order, only those whose name has the search text in it. */
    public static List<Path> files(Sort sort, String search) {
        List<Path> out = new ArrayList<>();
        String want = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        for (Path p : Schematic.listFiles()) {
            if (want.isEmpty() || p.getFileName().toString().toLowerCase(Locale.ROOT).contains(want)) out.add(p);
        }
        if (sort == Sort.NEWEST) out.sort(Comparator.comparing(SchematicLibrary::modified).reversed());
        else if (sort == Sort.LARGEST) out.sort(Comparator.comparingLong(SchematicLibrary::size).reversed());
        return out;
    }

    private static FileTime modified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    public static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }
}
