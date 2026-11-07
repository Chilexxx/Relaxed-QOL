package com.relaxedqol.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The terrain under the Radar ("Style: Minimap") - a top-down map of the blocks around you, like a minimap.
 *
 * Rework (glitches + speed):
 * - Two maps are kept: the normal SURFACE map and a CAVE map (the floor around your height). Walking in or out of a
 *   building / cave used to throw the whole map away and rebuild it chunk by chunk - a patchwork of old and new
 *   colours for a few seconds. Now it just switches to the other map, which is already there.
 * - Trees no longer count as "a roof over you" (leaves are ignored), and the switch only happens after you have been
 *   under / out from under the roof for half a second, so it never flickers in a doorway.
 * - Chunks you have never seen are built first (nearest first), refreshing chunks you already have comes after,
 *   so new terrain appears right away while you fly or walk.
 * - Blocks are read straight from the chunk (no world lookup per block) and only the parts of the map texture that
 *   changed are redrawn, instead of the whole picture 4 times a second.
 */
public final class Minimap {
    private Minimap() {}

    /** One map (surface or cave): chunk key -> 256 colours (ARGB, 0 = unknown), heights, when built. */
    private static final class Layer {
        final Map<Long, int[]> colors = new HashMap<>();
        final Map<Long, int[]> heights = new HashMap<>();
        final Map<Long, Long> builtAt = new HashMap<>();

        void clear() {
            colors.clear();
            heights.clear();
            builtAt.clear();
        }

        void remove(long k) {
            colors.remove(k);
            heights.remove(k);
            builtAt.remove(k);
        }
    }

    private static final Layer SURFACE = new Layer();
    private static final Layer CAVE = new Layer();
    private static Object level;
    /** The height the cave map was built for. */
    private static int caveY;
    /** The map shown now is the cave map (a roof over you for a moment, or the Nether). */
    private static boolean caveMode;
    /** Ticks the "roof over you" state has been different from caveMode (switch after a few). */
    private static int modeDebounce;
    private static long lastUse;
    /** Goes up whenever a chunk's colours change (the map textures are redrawn then). */
    private static int version;
    /** Goes up when the whole picture changes (other map, other world): textures are redrawn completely. */
    private static int epoch;
    private static boolean colorsFailed;
    /** Ticks left of fast rebuilding after you moved up / down in the Nether. */
    private static int caveBoost;
    /** When the radar window was opened (it builds faster for a moment). */
    private static long windowOpenedAt;
    private static boolean windowWasOpen;

    private static Layer cur() {
        return caveMode ? CAVE : SURFACE;
    }

    /** Called every tick while a minimap is visible: builds / refreshes chunks around you. */
    public static void tick(int rangeBlocks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            SURFACE.clear();
            CAVE.clear();
            level = null;
            return;
        }
        lastUse = System.currentTimeMillis();
        BlockPos feet = mc.player.blockPosition();
        if (level != mc.level) {
            SURFACE.clear();
            CAVE.clear();
            level = mc.level;
            caveY = feet.getY();
            caveMode = false;
            modeDebounce = 0;
            version++;
            epoch++;
        }
        // Roof over you? Leaves do not count (standing under a tree is not being indoors)
        // Any Nether-like world (servers name their Nether world themselves - it was only the vanilla key before)
        boolean nether = com.relaxedqol.client.util.Dims.ceiling();
        // Always the view from above - the cave map is only used in the Nether (its top is the bedrock roof)
        boolean roof = nether;
        if (roof != caveMode) {
            // Half a second under / out from under it before the map switches (doorways, overhangs)
            if (nether || ++modeDebounce >= 10) {
                caveMode = roof;
                modeDebounce = 0;
                version++;
                epoch++;
            }
        } else {
            modeDebounce = 0;
        }
        // The cave map belongs to a height: moved up or down a lot = its chunks are refreshed (the old picture stays
        // until each one is redone, so it never goes blank)
        if (Math.abs(caveY - feet.getY()) > 3) {
            caveY = feet.getY();
            CAVE.builtAt.replaceAll((k, v) -> 0L);
            caveBoost = 60; // Rebuild the new height quickly (3 s with a bigger time slice), nearest first
        }
        Layer layer = cur();
        // Enough chunks to fill the whole (turned) card, up to what the game has loaded
        int wanted = (int) Math.ceil(rangeBlocks * 1.45 / 16.0) + 1;
        int cr = Math.max(2, Math.min(Math.min(48, mc.options.getEffectiveRenderDistance() + 1), wanted));
        int pcx = feet.getX() >> 4;
        int pcz = feet.getZ() >> 4;
        long now = System.currentTimeMillis();
        // Pass 1: chunks this map does not have yet (nearest first) with a bigger time budget, so the map fills in
        // at once. Pass 2: refresh what is there (near chunks more often), with what is left of a small budget.
        long start = System.nanoTime();
        // Much smaller time slices per tick (they used to take up to 9 ms of every tick while you moved,
        // which cost real FPS) - the map still fills in quickly, just spread over a few more ticks
        // Smaller again (Low-End PC: half) - every millisecond here is taken from your frames
        boolean low = com.relaxedqol.client.module.modules.client.ClickGUI.lowEnd();
        long newBudget = layer.colors.isEmpty() ? 4_000_000L : low ? 800_000L : 1_500_000L;
        // The radar window is a menu (no gameplay to keep smooth): a much bigger slice while it is open, biggest
        // in its first seconds, so the terrain is there almost at once
        boolean windowOpen = mc.gui.screen() instanceof com.relaxedqol.client.gui.RadarScreen;
        if (windowOpen && !windowWasOpen) windowOpenedAt = now;
        windowWasOpen = windowOpen;
        if (windowOpen) newBudget = Math.max(newBudget, now - windowOpenedAt < 3000 ? (low ? 4_000_000L : 8_000_000L) : (low ? 2_000_000L : 4_000_000L));
        boolean timeLeft = true;
        outer:
        for (int ring = 0; ring <= cr; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    long key = key(pcx + dx, pcz + dz);
                    if (layer.builtAt.containsKey(key)) continue;
                    build(mc, layer, pcx + dx, pcz + dz, key);
                    if (System.nanoTime() - start > newBudget) {
                        timeLeft = false;
                        break outer;
                    }
                }
            }
        }
        if (timeLeft) {
            long refreshDeadline = start + (caveMode && caveBoost > 0 ? (low ? 1_000_000L : 2_000_000L) : (low ? 300_000L : 600_000L));
            if (caveBoost > 0) caveBoost--;
            outer2:
            for (int ring = 0; ring <= cr; ring++) {
                long maxAge = ring <= 2 ? 2000 : ring <= 6 ? 8000 : 30000;
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        long key = key(pcx + dx, pcz + dz);
                        Long built = layer.builtAt.get(key);
                        if (built == null || now - built < maxAge) continue;
                        build(mc, layer, pcx + dx, pcz + dz, key);
                        if (System.nanoTime() > refreshDeadline) break outer2;
                    }
                }
            }
        }
        // Forget far chunks
        forgetFar(SURFACE, pcx, pcz, cr);
        forgetFar(CAVE, pcx, pcz, cr);
    }

    /** Something solid over your head (a tree's leaves do not count). */
    private static boolean roofOver(Minecraft mc, BlockPos feet) {
        int top = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, feet.getX(), feet.getZ()) - 1;
        if (top <= feet.getY() + 2) return false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int end = Math.min(top, feet.getY() + 64);
        for (int y = feet.getY() + 2; y <= end; y++) {
            pos.set(feet.getX(), y, feet.getZ());
            BlockState st = mc.level.getBlockState(pos);
            if (st.isAir() || st.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
            if (!st.getCollisionShape(mc.level, pos).isEmpty()) return true;
        }
        return false;
    }

    private static void forgetFar(Layer layer, int pcx, int pcz, int cr) {
        if (layer.colors.size() < 6000) return;
        Iterator<Map.Entry<Long, int[]>> it = layer.colors.entrySet().iterator();
        while (it.hasNext()) {
            long k = it.next().getKey();
            int cx = (int) (k >> 32);
            int cz = (int) k;
            if (Math.abs(cx - pcx) > cr + 6 || Math.abs(cz - pcz) > cr + 6) {
                it.remove();
                layer.builtAt.remove(k);
                layer.heights.remove(k);
            }
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** Works out the 256 colours of one chunk for this map. False when the chunk is not loaded. */
    private static boolean build(Minecraft mc, Layer layer, int cx, int cz, long key) {
        net.minecraft.world.level.chunk.LevelChunk chunk = mc.level.getChunkSource()
            .getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        if (chunk == null) return false;
        boolean cave = layer == CAVE;
        int[] colors = new int[256];
        int[] heights = new int[256];
        int[] water = new int[256];
        boolean[] roofed = new boolean[256];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = mc.level.getMinY();
        boolean any = false;
        net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
        int topIndex = chunk.getHighestFilledSectionIndex();
        int sectionBase = chunk.getSectionYFromSectionIndex(0);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = (cx << 4) + lx;
                int z = (cz << 4) + lz;
                int i = lz * 16 + lx;
                // The top block of the column. Cave map: when that is well above you, the floor you would walk on
                // near your height instead (or the wall, darker, when the column is solid at your head)
                // The top block is found in the chunk itself, skipping blocks without a map colour (barrier /
                // light ceilings over a spawn). The game's height map was wrong at some spawns (custom spawn worlds
                // send none, or the invisible ceiling counted as "the top"), so the map there stayed dark and patchy.
                int y = topY(mc, sections, topIndex, chunk, x, z, lx, lz, pos, minY);
                boolean covered = false;
                if (cave && y > caveY + 3) {
                    // Smart cave view (the Nether): look from your HEAD height. Solid there = a wall (shown
                    // darker); otherwise go down through the air to the floor you would walk on (or the lava sea).
                    // Read straight from the chunk's sections with a per-block-type "solid" cache - the old way asked
                    // the world for every block's shape, so Nether chunks took ages and the map came and went.
                    int head = caveY + 1;
                    BlockState hs = sectionState(sections, sectionBase, lx, head, lz);
                    if (hs != null && solid(mc, hs, pos.set(x, head, z))) {
                        y = head;
                        covered = true;
                    } else {
                        int lowest = Math.max(minY, caveY - 48);
                        int found = Integer.MIN_VALUE;
                        for (int yy = head - 1; yy >= lowest; yy--) {
                            BlockState st = sectionState(sections, sectionBase, lx, yy, lz);
                            if (st == null) continue;
                            if (!st.getFluidState().isEmpty() || solid(mc, st, pos.set(x, yy, z))) {
                                found = yy;
                                break;
                            }
                        }
                        if (found != Integer.MIN_VALUE) {
                            y = found;
                            covered = true;
                        } else {
                            // A big drop with nothing under it in reach: left empty (not a random colour)
                            colors[i] = 0;
                            heights[i] = lowest;
                            continue;
                        }
                    }
                }
                pos.set(x, y, z);
                BlockState state = y < minY ? null : chunk.getBlockState(pos);
                // Like a real map, look through blocks with no map colour (glass, barriers, light blocks...)
                // to the first one below that has one
                for (int guard = 0; state != null && guard < 48 && y > minY && !state.isAir() && state.getFluidState().isEmpty()
                    && colorOf(mc, state, pos) == 0; guard++) {
                    y--;
                    pos.set(x, y, z);
                    state = chunk.getBlockState(pos);
                }
                // Nothing there (the void around a spawn island, a column not sent yet): left empty, never a
                // random colour
                if (state == null || (state.isAir() && state.getFluidState().isEmpty())) {
                    colors[i] = 0;
                    heights[i] = y < minY ? minY : y;
                    continue;
                }
                int c = colorOf(mc, state, pos);
                colors[i] = c;
                heights[i] = y;
                roofed[i] = covered;
                if (c != 0) any = true;
                // How deep the water is (shallow water is lighter, like on a real map)
                if (!state.getFluidState().isEmpty() && !state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                    int depth = 1;
                    for (int yy = y - 1; yy >= Math.max(minY, y - 12); yy--) {
                        pos.set(x, yy, z);
                        if (chunk.getBlockState(pos).getFluidState().isEmpty()) break;
                        depth++;
                    }
                    water[i] = depth;
                }
            }
        }
        // Relief: light from the north-west, the slope decides how light / dark, water by depth
        int[] north = layer.heights.get(key(cx, cz - 1));
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = lz * 16 + lx;
                if (colors[i] == 0) continue;
                int up = lz > 0 ? heights[i - 16] : (north != null ? north[15 * 16 + lx] : heights[i]);
                int left = lx > 0 ? heights[i - 1] : heights[i];
                // A missing neighbour column (void) does not make a cliff edge
                if (lz > 0 && colors[i - 16] == 0) up = heights[i];
                if (lx > 0 && colors[i - 1] == 0) left = heights[i];
                float slope = (heights[i] - up) * 0.7f + (heights[i] - left) * 0.3f;
                float f = 1f + Math.max(-0.32f, Math.min(0.22f, slope * 0.09f));
                if (water[i] > 0) f = 1.18f - Math.min(0.42f, water[i] * 0.045f);
                if (roofed[i] && heights[i] > caveY + 1) f *= 0.62f; // walls in a cave a little darker (clearer tunnels)
                colors[i] = vivid(shade(colors[i], f));
            }
        }
        layer.heights.put(key, heights);
        layer.builtAt.put(key, System.currentTimeMillis());
        int[] before = layer.colors.get(key);
        // A chunk the server has not filled in yet (all empty): not kept as "built", so it is tried again soon
        if (!any && before == null) {
            layer.builtAt.put(key, System.currentTimeMillis() - 9000);
        }
        if (before == null || !java.util.Arrays.equals(before, colors)) {
            layer.colors.put(key, colors);
            if (layer == cur()) {
                version++;
                markDirty(CARD, key);
                markDirty(WINDOW, key);
            }
        }
        return true;
    }

    /** The block at (lx, y, lz) of a chunk, read from its section (null = outside the chunk / empty section). */
    private static BlockState sectionState(net.minecraft.world.level.chunk.LevelChunkSection[] sections, int sectionBase,
                                           int lx, int y, int lz) {
        int si = (y >> 4) - sectionBase;
        if (si < 0 || si >= sections.length) return null;
        net.minecraft.world.level.chunk.LevelChunkSection sec = sections[si];
        if (sec == null || sec.hasOnlyAir()) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        return sec.getBlockState(lx, y & 15, lz);
    }

    /** Whether a kind of block is something you stand on / bump into - worked out once per block type. */
    private static final java.util.IdentityHashMap<BlockState, Boolean> SOLID = new java.util.IdentityHashMap<>();

    private static boolean solid(Minecraft mc, BlockState st, BlockPos pos) {
        if (st.isAir()) return false;
        Boolean known = SOLID.get(st);
        if (known != null) return known;
        boolean v;
        try {
            v = !st.getCollisionShape(mc.level, pos).isEmpty();
        } catch (RuntimeException e) {
            v = true;
        }
        SOLID.put(st, v);
        return v;
    }

    /** The highest block of a column that shows on a map (first one with a map colour, or a fluid); below minY = none. */
    private static int topY(Minecraft mc, net.minecraft.world.level.chunk.LevelChunkSection[] sections, int topIndex,
                            net.minecraft.world.level.chunk.LevelChunk chunk, int x, int z, int lx, int lz,
                            BlockPos.MutableBlockPos pos, int minY) {
        for (int si = Math.min(topIndex, sections.length - 1); si >= 0; si--) {
            net.minecraft.world.level.chunk.LevelChunkSection sec = sections[si];
            if (sec == null || sec.hasOnlyAir()) continue;
            int base = net.minecraft.core.SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(si));
            for (int ly = 15; ly >= 0; ly--) {
                BlockState st = sec.getBlockState(lx, ly, lz);
                if (st.isAir()) continue;
                if (!st.getFluidState().isEmpty()) return base + ly;
                pos.set(x, base + ly, z);
                if (colorOf(mc, st, pos) != 0) return base + ly;
            }
        }
        return minY - 1;
    }

    private static void markDirty(Tex t, long key) {
        if (t.dirty.size() > 2048) {
            // Not drawn for a while (window closed): just redraw everything next time
            t.dirty.clear();
            t.builtEpoch = -1;
            return;
        }
        t.dirty.add(key);
    }

    private static int colorOf(Minecraft mc, BlockState state, BlockPos pos) {
        // Grass, leaves and water take the colour of the biome they are in (a map colour is the same everywhere:
        // bright green grass also in a savanna or a swamp) - the same biome colours the game paints them with
        int biome = biomeTint(mc, state, pos);
        if (biome != 0) return biome;
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) ? 0xFFD9480F : 0xFF2F5FD0;
        }
        if (!colorsFailed) {
            try {
                int rgb = state.getMapColor(mc.level, pos).col;
                if (rgb != 0) return 0xFF000000 | rgb;
                // A block with no map colour (glass, barriers...): see-through, like on a real map
                return 0;
            } catch (LinkageError | RuntimeException e) {
                colorsFailed = true; // this game version names it differently: plain colours from here on
            }
        }
        // Fallback: a steady colour per block
        int h = state.getBlock().getDescriptionId().hashCode(); // the same every launch
        return 0xFF000000 | ((h & 0x7F7F7F) + 0x303030);
    }

    private static boolean biomeFailed;

    /** The biome's colour for grass / leaves / water here, as the block shows it in the world (0 = not such a block). */
    private static int biomeTint(Minecraft mc, BlockState state, BlockPos pos) {
        if (biomeFailed || mc.level == null) return 0;
        try {
            if (!state.getFluidState().isEmpty()) {
                if (!state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return 0;
                return scale(net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(mc.level, pos), 0.82f);
            }
            var b = state.getBlock();
            if (b == net.minecraft.world.level.block.Blocks.GRASS_BLOCK || b == net.minecraft.world.level.block.Blocks.SHORT_GRASS
                || b == net.minecraft.world.level.block.Blocks.TALL_GRASS || b == net.minecraft.world.level.block.Blocks.FERN
                || b == net.minecraft.world.level.block.Blocks.LARGE_FERN || b == net.minecraft.world.level.block.Blocks.SUGAR_CANE) {
                return scale(net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(mc.level, pos), 0.80f);
            }
            if (b == net.minecraft.world.level.block.Blocks.BIRCH_LEAVES) return scale(0x80A755, 0.72f);
            if (b == net.minecraft.world.level.block.Blocks.SPRUCE_LEAVES) return scale(0x619961, 0.72f);
            if (b == net.minecraft.world.level.block.Blocks.CHERRY_LEAVES || b == net.minecraft.world.level.block.Blocks.AZALEA_LEAVES
                || b == net.minecraft.world.level.block.Blocks.FLOWERING_AZALEA_LEAVES) return 0; // not tinted
            if (state.is(net.minecraft.tags.BlockTags.LEAVES) || b == net.minecraft.world.level.block.Blocks.VINE) {
                return scale(net.minecraft.client.renderer.BiomeColors.getAverageFoliageColor(mc.level, pos), 0.72f);
            }
            return 0;
        } catch (LinkageError | RuntimeException e) {
            biomeFailed = true; // plain map colours then
            return 0;
        }
    }

    /** A tint as the block shows it: the textures are grey, a bit darker than the tint itself. */
    private static int scale(int rgb, float f) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((rgb & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** A little more saturation, so the map does not look washed out. */
    private static int vivid(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int grey = (r * 3 + g * 6 + b) / 10;
        r = Math.max(0, Math.min(255, grey + Math.round((r - grey) * 1.18f)));
        g = Math.max(0, Math.min(255, grey + Math.round((g - grey) * 1.18f)));
        b = Math.max(0, Math.min(255, grey + Math.round((b - grey) * 1.18f)));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int shade(int argb, float f) {
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((argb & 0xFF) * f));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** The colour of one block column (0 = unknown). */
    private static int colorAt(int x, int z) {
        int[] c = cur().colors.get(key(x >> 4, z >> 4));
        return c == null ? 0 : c[(z & 15) * 16 + (x & 15)];
    }

    /**
     * Draws the map centred on (cx, cy) in the current pose: radiusPx pixels = rangeBlocks blocks. rotation in
     * radians (0 = north up; pass Math.PI - yaw for "turns with you"). Cells are cellPx pixels (merged in runs).
     * The caller sets the scissor.
     */
    public static void draw(net.minecraft.client.gui.GuiGraphicsExtractor g, int cx, int cy, double radiusPx, double rangeBlocks,
                            double rotation, double cellPx, double playerX, double playerZ, int alpha) {
        if (cur().colors.isEmpty()) return;
        double ppb = radiusPx / rangeBlocks;
        // Blocks per cell (at least 1), so a cell is about cellPx pixels
        int step = Math.max(1, (int) Math.round(cellPx / ppb));
        double cell = step * ppb;
        // Draw a bit more than the circle so turned corners are covered too
        int reach = (int) Math.ceil(rangeBlocks * 1.45 / step) * step;
        int px = (int) Math.floor(playerX);
        int pz = (int) Math.floor(playerZ);
        // Snap the grid to whole cells so it does not shimmer while you walk
        int gx0 = Math.floorDiv(px - reach, step) * step;
        int gz0 = Math.floorDiv(pz - reach, step) * step;
        int a = Math.max(0, Math.min(255, alpha)) << 24;
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        if (rotation != 0) g.pose().rotate((float) rotation);
        for (int z = gz0; z <= pz + reach; z += step) {
            float y0 = (float) ((z - playerZ) * ppb);
            int ys = (int) Math.floor(y0);
            int ye = (int) Math.ceil(y0 + cell);
            int runStart = gx0;
            int runColor = colorAt(gx0, z);
            for (int x = gx0 + step; x <= px + reach + step; x += step) {
                int c = x <= px + reach ? colorAt(x, z) : -1;
                if (c == runColor) continue;
                if (runColor != 0) {
                    int xs = (int) Math.floor((runStart - playerX) * ppb);
                    int xe = (int) Math.ceil((x - playerX) * ppb);
                    g.fill(xs, ys, xe, ye, (runColor & 0x00FFFFFF) | a);
                }
                runStart = x;
                runColor = c;
            }
        }
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------ drawn as one texture

    /**
     * One map texture (the HUD card and the big radar window have their own): every texel is one block column
     * (or a few, for big ranges), so the map is as detailed as the screen allows instead of chunky 2-3 px cells,
     * and drawing it costs one quad instead of hundreds of rectangles. Made through reflection, so a game version
     * that changed these classes just falls back to the old way of drawing.
     */
    private static final class Tex {
        final int size;
        final String name;
        Object image;
        Object texture;
        net.minecraft.resources.Identifier id;
        long pointer;
        java.lang.reflect.Method upload;
        java.lang.reflect.Method setPixel;
        int centerX = Integer.MIN_VALUE;
        int centerZ;
        int bpt;
        int builtVersion = -1;
        int builtEpoch = -1;
        long builtAt;
        boolean failed;
        /** Chunks whose colours changed since the texture was drawn (only those parts are redrawn). */
        final java.util.Set<Long> dirty = new java.util.HashSet<>();

        Tex(int size, String name) {
            this.size = size;
            this.name = name;
        }
    }

    private static final Tex CARD = new Tex(256, "minimap_card");
    private static final Tex WINDOW = new Tex(1024, "minimap_window");

    private static boolean create(Tex t) {
        if (t.texture != null) return true;
        if (t.failed) return false;
        try {
            Class<?> imageClass = Class.forName("com.mojang.blaze3d.platform.NativeImage");
            Object image = null;
            for (java.lang.reflect.Constructor<?> c : imageClass.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 3 && p[0] == int.class && p[1] == int.class && p[2] == boolean.class) {
                    image = c.newInstance(t.size, t.size, true);
                    break;
                }
            }
            if (image == null) throw new IllegalStateException("no NativeImage(int, int, boolean)");
            Class<?> dyn = Class.forName("net.minecraft.client.renderer.texture.DynamicTexture");
            Object texture = null;
            for (java.lang.reflect.Constructor<?> c : dyn.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 2 && p[0] == java.util.function.Supplier.class && p[1] == imageClass) {
                    String label = "relaxed " + t.name;
                    texture = c.newInstance((java.util.function.Supplier<String>) () -> label, image);
                } else if (p.length == 1 && p[0] == imageClass) {
                    texture = c.newInstance(image);
                }
                if (texture != null) break;
            }
            if (texture == null) throw new IllegalStateException("no DynamicTexture(NativeImage)");
            net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.fromNamespaceAndPath("relaxed-qol", t.name);
            Object manager = Minecraft.class.getMethod("getTextureManager").invoke(Minecraft.getInstance());
            boolean registered = false;
            for (java.lang.reflect.Method m : manager.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("register") && p.length == 2 && p[0] == net.minecraft.resources.Identifier.class
                    && p[1].isAssignableFrom(dyn)) {
                    m.invoke(manager, id, texture);
                    registered = true;
                    break;
                }
            }
            if (!registered) throw new IllegalStateException("no TextureManager.register");
            java.lang.reflect.Method upload = dyn.getMethod("upload");
            long pointer = 0;
            try {
                pointer = (Long) imageClass.getMethod("getPointer").invoke(image);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // no pointer: set pixel by pixel
            }
            java.lang.reflect.Method setPixel = null;
            if (pointer == 0) {
                for (String n : new String[]{"setPixel", "setPixelRGBA", "setPixelABGR"}) {
                    try {
                        setPixel = imageClass.getMethod(n, int.class, int.class, int.class);
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // next name
                    }
                }
                if (setPixel == null) throw new IllegalStateException("no way to write pixels");
            }
            t.image = image;
            t.texture = texture;
            t.id = id;
            t.upload = upload;
            t.pointer = pointer;
            t.setPixel = setPixel;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            t.failed = true;
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("Minimap: no texture map on this game version, using the simple one", e);
            return false;
        }
    }

    /**
     * Redraws the texture around (px, pz): completely when you moved enough, the scale changed or the other map is
     * shown now; otherwise only the chunks that changed.
     */
    private static void refresh(Tex t, double rangeBlocks, double playerX, double playerZ) {
        // The small card's picture reaches further (3.6 x the radius, was 2.9) so a turned map never runs out
        // at a side or corner (that showed the dark card behind it as a rim)
        int bpt = Math.max(1, (int) Math.ceil(rangeBlocks * (t == CARD ? 3.6 : 2.9) / t.size));
        int px = (int) Math.floor(playerX);
        int pz = (int) Math.floor(playerZ);
        long now = System.currentTimeMillis();
        int moveLimit = Math.max(2, t.size * bpt / (t == CARD ? 16 : 10)); // The card re-centres sooner
        boolean full = t.centerX == Integer.MIN_VALUE || Math.abs(px - t.centerX) > moveLimit || Math.abs(pz - t.centerZ) > moveLimit
            || bpt != t.bpt || t.builtEpoch != epoch;
        if (!full) {
            if (t.dirty.isEmpty() || now - t.builtAt < 80) return;
        }
        int cx = full ? Math.floorDiv(px, bpt) * bpt : t.centerX;
        int cz = full ? Math.floorDiv(pz, bpt) * bpt : t.centerZ;
        int n = t.size;
        int half = n / 2;
        Map<Long, int[]> colors = cur().colors;
        try {
            if (full) {
                writeArea(t, colors, cx, cz, bpt, 0, 0, n, n);
            } else {
                for (long k : t.dirty) {
                    int chx = (int) (k >> 32);
                    int chz = (int) k;
                    // The texels that show this chunk
                    int tx0 = Math.floorDiv((chx << 4) - cx, bpt) + half;
                    int tz0 = Math.floorDiv((chz << 4) - cz, bpt) + half;
                    int tx1 = Math.floorDiv((chx << 4) + 15 - cx, bpt) + half + 1;
                    int tz1 = Math.floorDiv((chz << 4) + 15 - cz, bpt) + half + 1;
                    tx0 = Math.max(0, tx0);
                    tz0 = Math.max(0, tz0);
                    tx1 = Math.min(n, tx1);
                    tz1 = Math.min(n, tz1);
                    if (tx0 >= tx1 || tz0 >= tz1) continue;
                    writeArea(t, colors, cx, cz, bpt, tx0, tz0, tx1, tz1);
                }
            }
            t.dirty.clear();
            t.upload.invoke(t.texture);
            // The small card is smoothed; the big window keeps every block crisp (it is drawn much larger)
            if (t != WINDOW) smooth(t.texture);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            t.failed = true;
            t.texture = null;
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("Minimap: texture update failed, using the simple map", e);
            return;
        }
        t.centerX = cx;
        t.centerZ = cz;
        t.bpt = bpt;
        t.builtVersion = version;
        t.builtEpoch = epoch;
        t.builtAt = now;
    }

    private static java.lang.reflect.Field samplerField;
    private static Object linearSampler;
    private static boolean smoothFailed;

    /**
     * The map texture is sampled smoothly (linear) instead of pixel by pixel, so the turning map no longer
     * shimmers and looks jagged - it glides like a real minimap. Found by reflection; if this game version does it
     * differently, the map just stays sharp-edged.
     */
    private static void smooth(Object texture) {
        if (smoothFailed || texture == null) return;
        try {
            if (linearSampler == null) {
                Class<?> rs = Class.forName("com.mojang.blaze3d.systems.RenderSystem");
                Object cache = rs.getMethod("getSamplerCache").invoke(null);
                java.lang.reflect.Method get = null;
                for (java.lang.reflect.Method m : cache.getClass().getMethods()) {
                    if (m.getName().equals("getSampler") && m.getParameterCount() == 5) get = m;
                }
                if (get == null) throw new IllegalStateException("no sampler cache");
                Class<?>[] p = get.getParameterTypes();
                Object clamp = enumConst(p[0], "CLAMP_TO_EDGE", "CLAMP");
                Object linear = enumConst(p[2], "LINEAR");
                linearSampler = get.invoke(cache, clamp, clamp, linear, linear, false);
            }
            if (samplerField == null) {
                for (Class<?> c = texture.getClass(); c != null && samplerField == null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType().isInstance(linearSampler)) {
                            f.setAccessible(true);
                            samplerField = f;
                            break;
                        }
                    }
                }
                if (samplerField == null) throw new IllegalStateException("no sampler field");
            }
            if (samplerField.get(texture) != linearSampler) samplerField.set(texture, linearSampler);
        } catch (Throwable e) {
            smoothFailed = true;
            try {
                texture.getClass().getMethod("setFilter", boolean.class, boolean.class).invoke(texture, true, false);
            } catch (Throwable ignored) {
                // stays sharp
            }
        }
    }

    private static Object enumConst(Class<?> type, String... names) {
        for (Object c : type.getEnumConstants()) {
            for (String n : names) if (((Enum<?>) c).name().equals(n)) return c;
        }
        throw new IllegalStateException("no " + names[0]);
    }

    /** Writes texels [tx0, tx1) x [tz0, tz1) of the texture centred on block (cx, cz). */
    private static void writeArea(Tex t, Map<Long, int[]> colors, int cx, int cz, int bpt, int tx0, int tz0, int tx1, int tz1)
        throws ReflectiveOperationException {
        int n = t.size;
        int half = n / 2;
        long lastKey = Long.MIN_VALUE;
        int[] chunk = null;
        for (int tz = tz0; tz < tz1; tz++) {
            int wz = cz + (tz - half) * bpt;
            int lastRgb = -1;
            for (int tx = tx0; tx < tx1; tx++) {
                int wx = cx + (tx - half) * bpt;
                long k = key(wx >> 4, wz >> 4);
                if (k != lastKey) {
                    chunk = colors.get(k);
                    lastKey = k;
                }
                int argb = chunk == null ? 0 : chunk[(wz & 15) * 16 + (wx & 15)];
                if ((argb >>> 24) != 0) {
                    lastRgb = argb & 0xFFFFFF;
                } else {
                    // Not loaded: see-through, but in the colour next to it - the smooth (linear) scaling mixes in
                    // this colour, so the edge of the loaded world fades out instead of drawing a dark line
                    int near = lastRgb;
                    if (near < 0 && t.pointer != 0 && tz > 0) {
                        int above = org.lwjgl.system.MemoryUtil.memGetInt(t.pointer + ((long) (tz - 1) * n + tx) * 4L);
                        near = ((above & 0xFF) << 16) | (above & 0xFF00) | ((above >> 16) & 0xFF);
                    }
                    argb = near < 0 ? 0 : near;
                }
                // Memory order is R, G, B, A: as a little-endian int that is 0xAABBGGRR
                int abgr = (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
                if (t.pointer != 0) {
                    org.lwjgl.system.MemoryUtil.memPutInt(t.pointer + ((long) tz * n + tx) * 4L, abgr);
                } else {
                    String name = t.setPixel.getName();
                    t.setPixel.invoke(t.image, tx, tz, name.equals("setPixel") ? argb : abgr);
                }
            }
        }
    }

    /**
     * Draws the map as one texture (sharp, one block per pixel where the screen allows). Same arguments as
     * {@link #draw}; window = the big radar window (its own, bigger texture). False = not possible on this game
     * version (the caller then uses {@link #draw}).
     */
    public static boolean drawTexture(net.minecraft.client.gui.GuiGraphicsExtractor g, boolean window, int cx, int cy, double radiusPx,
                                      double rangeBlocks, double rotation, double playerX, double playerZ, int alpha) {
        Tex t = window ? WINDOW : CARD;
        if (!create(t)) return false;
        lastUse = System.currentTimeMillis();
        refresh(t, rangeBlocks, playerX, playerZ);
        if (t.texture == null) return false;
        double ppb = radiusPx / rangeBlocks;
        float sizePx = (float) (t.size * t.bpt * ppb);
        // The texture's middle is the texel at (centerX, centerZ): where that is relative to you
        float offX = (float) ((t.centerX - playerX) * ppb);
        float offZ = (float) ((t.centerZ - playerZ) * ppb);
        int color = (Math.max(0, Math.min(255, alpha)) << 24) | 0xFFFFFF;
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        if (rotation != 0) g.pose().rotate((float) rotation);
        g.pose().translate(offX - sizePx / 2f, offZ - sizePx / 2f);
        g.pose().scale(sizePx / t.size, sizePx / t.size);
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, t.id, 0, 0, 0, 0, t.size, t.size, t.size, t.size, color);
        g.pose().popMatrix();
        return true;
    }

    /** True when the minimap was drawn in the last few seconds (the tick keeps building only then). */
    public static boolean inUse() {
        return System.currentTimeMillis() - lastUse < 3000;
    }
}
