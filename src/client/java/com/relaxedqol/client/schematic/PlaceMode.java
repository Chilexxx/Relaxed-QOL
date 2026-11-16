package com.relaxedqol.client.schematic;

import com.relaxedqol.client.gui.HudStyle;
import com.relaxedqol.client.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Placing and selecting in the world itself, with a preview, while you move and look as usual:
 *
 * - Schematic placement: the loaded schematic (its ghost) follows where you aim. E takes the spot, Esc puts it back.
 * - Paste placement (single player): the box a file will fill follows where you aim. E pastes it there.
 * - Select area: left click is corner A, right click corner B, the box between them shows. Enter saves it.
 *
 * While one of these is on, its keys (and the mouse buttons and wheel) are its own: E does not open the inventory,
 * a click does not break a block. Everything else - walking, flying, looking - stays as it is.
 */
public final class PlaceMode {
    private PlaceMode() {}

    private enum Kind { BUILD, PASTE, SELECT }

    private static Kind kind;
    private static Level level;

    // placing (BUILD / PASTE)
    private static String name = "";
    private static int sizeX, sizeY, sizeZ;
    private static long blocks;
    private static int turns;
    /** The lowest corner of the turned box. */
    private static BlockPos origin;
    /** Follows the aim; off = stays where it is (right click, or after moving it by hand). */
    private static boolean follow;
    /** Floats at a set distance along the look instead of sitting on what you aim at (Shift + wheel). */
    private static boolean free;
    private static double distance;
    /** Whole-chunk copies: the height they were copied at, kept until you move it up or down yourself. */
    private static int keptY;
    private static boolean keepY;
    private static double wheel;
    // BUILD: where it was before (Esc), and what the builder has been told so far
    private static BlockPos beforeOrigin;
    private static int beforeTurns;
    private static BlockPos applied;
    private static int quiet;
    // PASTE
    private static AreaCopy copy;
    private static String trouble;
    // SELECT: 0 = the block you aim at (in the air 5 blocks ahead when you aim at nothing); else the cell this many
    // blocks ahead of you, also in mid-air, unless a block is nearer
    private static int airReach;

    private static boolean hooked;

    /** Hooks the tick and the HUD (once, when the client starts). */
    public static void init() {
        if (hooked) return;
        hooked = true;
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(PlaceMode::tick);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
            net.minecraft.resources.Identifier.fromNamespaceAndPath(com.relaxedqol.RelaxedQolMod.MOD_ID, "place_mode"), (graphics, delta) -> hud(graphics));
    }

    public static boolean active() {
        return kind != null;
    }

    /**
     * A schematic loaded for building, as the placement mode moves it about: the builder module, where the client
     * has one (it puts itself here). Without one only pasting and selecting are on offer.
     */
    public interface Loaded {
        Path file();

        /** Width, height and length as the file has them (not turned), or null when nothing is loaded. */
        int[] size();

        int blockCount();

        int quarterTurns();

        BlockPos origin();

        boolean building();

        void placeAt(BlockPos at);

        void turn(int steps);
    }

    public static Loaded loaded;

    private static Loaded builder() {
        return loaded;
    }

    private static int outX() {
        return turns % 2 == 1 ? sizeZ : sizeX;
    }

    private static int outZ() {
        return turns % 2 == 1 ? sizeX : sizeZ;
    }

    private static void begin(Kind k) {
        Minecraft mc = Minecraft.getInstance();
        kind = k;
        level = mc.level;
        follow = true;
        free = false;
        distance = 6;
        wheel = 0;
        keepY = false;
        letGo();
        trouble = null;
        applied = null;
        quiet = 0;
        if (mc.gui.screen() != null) mc.gui.setScreen(null);
    }

    // ---------------------------------------------------------------- starting and ending

    /** The schematic loaded in the builder: move it around with its preview. */
    public static String placeLoaded() {
        Minecraft mc = Minecraft.getInstance();
        Loaded b = builder();
        if (mc.player == null || mc.level == null) return "§cNot in a world.";
        if (b == null || b.file() == null || b.size() == null) return "§cLoad a schematic first (§f.schem load <name>§c).";
        if (b.building()) return "§cPause the build first (§f.schem pause§c).";
        begin(Kind.BUILD);
        name = b.file().getFileName().toString();
        int[] s = b.size();
        sizeX = s[0];
        sizeY = s[1];
        sizeZ = s[2];
        blocks = b.blockCount();
        turns = b.quarterTurns();
        origin = b.origin() != null ? b.origin() : mc.player.blockPosition();
        beforeOrigin = b.origin();
        beforeTurns = turns;
        applied = b.origin();
        return null;
    }

    /** Single player: a file straight from the folder - the box it will fill follows your aim, E pastes it. */
    public static String pasteFile(Path file) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return "§cNot in a world.";
        if (mc.getSingleplayerServer() == null) return "§ePaste works in single player only.";
        if (SchematicLibrary.pasteState() != null) return "§e" + SchematicLibrary.pasteState() + " - §f.schem paste stop§e to end it.";
        begin(Kind.PASTE);
        name = file.getFileName().toString();
        sizeX = sizeY = sizeZ = 1;
        blocks = 0;
        turns = 0;
        origin = mc.player.blockPosition();
        Level startedIn = level;
        Thread t = new Thread(() -> {
            AreaCopy read = null;
            String failed = null;
            try {
                read = AreaCopy.read(file, SchematicLibrary.lowImpact());
                if (read == null) read = AreaCopy.ofMap(Schematic.load(file).blocks);
            } catch (java.io.IOException | RuntimeException e) {
                failed = e.getMessage() == null ? e.toString() : e.getMessage();
            } catch (OutOfMemoryError e) {
                failed = "Not enough memory to read that file";
            }
            AreaCopy got = read;
            String why = failed;
            mc.execute(() -> {
                if (kind != Kind.PASTE || level != startedIn || !name.equals(file.getFileName().toString())) {
                    if (got != null) got.close(); // the mode was left while the file was read
                    return;
                }
                if (got == null) {
                    trouble = why;
                    return;
                }
                copy = got;
                sizeX = got.sizeX;
                sizeY = got.sizeY;
                sizeZ = got.sizeZ;
                blocks = got.blocks;
                keepY = got.keepHeight;
                keptY = got.from.getY();
            });
        }, "Relaxed-Schematic-Read");
        t.setDaemon(true);
        t.start();
        return null;
    }

    /** Pick the two corners of an area by clicking. */
    public static String select() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return "§cNot in a world.";
        begin(Kind.SELECT);
        airReach = 0;
        return null;
    }

    /** The file read for the preview is not going to be pasted: one held on disk (Low Impact) gives its file back. */
    private static void letGo() {
        if (copy != null) copy.close();
        copy = null;
    }

    private static void end() {
        kind = null;
        letGo();
        level = null;
        net.minecraft.client.KeyMapping.releaseAll();
    }

    /** Leaves the mode; placing: back to where the schematic was. */
    public static void cancel() {
        if (kind == Kind.BUILD) {
            Loaded b = builder();
            if (b != null && b.file() != null) {
                if (b.quarterTurns() != beforeTurns) b.turn(beforeTurns - b.quarterTurns());
                if (beforeOrigin != null && !beforeOrigin.equals(b.origin())) b.placeAt(beforeOrigin);
            }
        }
        end();
    }

    private static void confirm() {
        if (kind == Kind.BUILD) {
            Loaded b = builder();
            if (b != null && b.file() != null && !origin.equals(b.origin())) b.placeAt(origin);
            ChatUtil.send("§aPlaced at §f" + origin.toShortString() + "§7 - §f.schem build§7 to build it" + (Minecraft.getInstance().getSingleplayerServer() != null ? ", §f.schem paste§7 to paste it" : "") + ".");
            end();
        } else if (kind == Kind.PASTE) {
            if (copy == null) return; // still reading
            AreaCopy ready = copy;
            copy = null; // the paste has it now
            BlockPos at = origin;
            int q = turns;
            end();
            SchematicLibrary.paste(ready, at, q, false, ChatUtil::send);
        } else if (kind == Kind.SELECT) {
            if (SchematicLibrary.corner1() == null || SchematicLibrary.corner2() == null) return;
            end();
            String area = SchematicLibrary.areaLabel();
            com.relaxedqol.client.util.Later.run(() -> com.relaxedqol.client.gui.TextPrompt.open(null, "Save the area (" + area + ") as", "name", "",
                typed -> SchematicLibrary.saveArea(typed, ChatUtil::send)));
        }
    }

    // ---------------------------------------------------------------- every tick

    private static void tick(Minecraft mc) {
        if (kind == null) return;
        if (mc.level == null || mc.level != level || mc.player == null) {
            end(); // another world: nothing to put back there
            return;
        }
        if (kind == Kind.SELECT) return;
        Loaded b = builder();
        if (kind == Kind.BUILD && (b == null || b.file() == null || b.building())) {
            end();
            return;
        }
        if (follow && mc.gui.screen() == null) {
            BlockPos at = aim(mc, !free);
            origin = new BlockPos(at.getX() - outX() / 2, keepY ? keptY : at.getY(), at.getZ() - outZ() / 2);
        }
        if (kind == Kind.BUILD) {
            // the ghost itself follows: at once when it is small, else when the aim has rested a moment
            if (origin.equals(applied)) {
                quiet = 0;
            } else if (blocks <= 30_000 || ++quiet >= 5) {
                b.placeAt(origin);
                applied = origin;
                quiet = 0;
            }
        }
    }

    /** The block the look lands on (the free cell in front of what it hits), or the point at the set distance. */
    private static BlockPos aim(Minecraft mc, boolean surface) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 dir = mc.player.getViewVector(1f);
        if (surface) {
            double reach = (mc.options.getEffectiveRenderDistance() + 2) * 16.0;
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockPos at = hit.getBlockPos().relative(hit.getDirection());
                distance = Math.max(2, Vec3.atCenterOf(at).distanceTo(eye));
                return at;
            }
        }
        Vec3 p = eye.add(dir.scale(distance));
        return BlockPos.containing(p.x, Math.max(mc.level.getMinY(), Math.min(mc.level.getMaxY(), p.y)), p.z);
    }

    private static void move(int forward, int right, int up) {
        Minecraft mc = Minecraft.getInstance();
        Direction f = mc.player.getDirection();
        Direction r = f.getClockWise();
        follow = false;
        if (up != 0) keepY = false;
        origin = origin.offset(f.getStepX() * forward + r.getStepX() * right, up, f.getStepZ() * forward + r.getStepZ() * right);
        quiet = 5; // a move by hand shows at once
    }

    private static void rotate(int steps) {
        steps = Math.floorMod(steps, 4);
        if (steps == 0) return;
        int cx = origin.getX() + outX() / 2, cz = origin.getZ() + outZ() / 2;
        if (kind == Kind.BUILD) {
            Loaded b = builder();
            if (b == null) return;
            b.turn(steps);
            turns = b.quarterTurns();
            applied = b.origin();
        } else {
            turns = (turns + steps) % 4;
        }
        origin = new BlockPos(cx - outX() / 2, origin.getY(), cz - outZ() / 2); // turned about its middle
        quiet = 5;
    }

    private static void ground() {
        Minecraft mc = Minecraft.getInstance();
        int cx = origin.getX() + outX() / 2, cz = origin.getZ() + outZ() / 2;
        if (!mc.level.hasChunk(cx >> 4, cz >> 4)) return;
        follow = false;
        free = false;
        keepY = false;
        origin = new BlockPos(origin.getX(), mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz), origin.getZ());
        quiet = 5;
    }

    // ---------------------------------------------------------------- keys, buttons, wheel (from the input mixins)

    private static boolean listening() {
        return kind != null && Minecraft.getInstance().gui.screen() == null;
    }

    private static boolean mine(int key) {
        if (key == 69 || key == 256) return true; // E, Esc
        if (kind == Kind.SELECT) return key == 257 || key == 335; // Enter
        return key == 82 || key == 71 || (key >= 262 && key <= 267); // R, G, arrows, Page Up / Down
    }

    /** A key went down (1), repeats (2) or up (0). true = it was ours: the game does not get it. */
    public static boolean key(int action, int key) {
        if (!listening() || !mine(key)) return false;
        if (action != 1) return true;
        switch (key) {
            case 256 -> cancel();
            case 69 -> {
                if (kind == Kind.SELECT) cancel();
                else confirm();
            }
            case 257, 335 -> confirm();
            case 82 -> rotate(1);
            case 71 -> ground();
            case 265 -> move(1, 0, 0);
            case 264 -> move(-1, 0, 0);
            case 263 -> move(0, -1, 0);
            case 262 -> move(0, 1, 0);
            case 266 -> move(0, 0, 1);
            case 267 -> move(0, 0, -1);
            default -> {
            }
        }
        return true;
    }

    /**
     * Select area: the cell a click picks right now - the block you aim at, or a cell in the air in front of you
     * (so corners can be set in mid-air, around a floating build or above a roof). It shows as a small cube.
     */
    private static BlockPos target(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 dir = mc.player.getViewVector(1f);
        double far = airReach > 0 ? airReach : (mc.options.getEffectiveRenderDistance() + 2) * 16.0;
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(far)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.BLOCK) return hit.getBlockPos().immutable();
        return BlockPos.containing(eye.add(dir.scale(airReach > 0 ? airReach : 5)));
    }

    /** A mouse button went down (1) or up (0). true = it was ours. */
    public static boolean button(int button, int action) {
        if (!listening() || button > 1) return false;
        if (action != 1) return true;
        if (kind == Kind.SELECT) {
            SchematicLibrary.setCornerAt(button == 0 ? 1 : 2, target(Minecraft.getInstance()));
        } else if (button == 1) {
            follow = !follow;
            if (follow) free = false;
        }
        return true;
    }

    /** The wheel turned. true = it was ours. */
    public static boolean scroll(double amount) {
        if (!listening()) return false;
        if (!Double.isFinite(amount) || amount == 0) return true;
        Minecraft mc = Minecraft.getInstance();
        if (kind == Kind.SELECT) {
            // up from "aim": 5 blocks ahead, then a block a notch; below 1: back to "aim"
            if (airReach == 0) airReach = amount > 0 ? 5 : 0;
            else airReach = Math.max(0, Math.min(128, airReach + (int) Math.signum(amount)));
            return true;
        }
        boolean shift = com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), 340) || com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), 344);
        if (shift) {
            // nearer / further along the look, free of what stands there
            if (!free) distance = Math.max(2, Vec3.atCenterOf(origin.offset(outX() / 2, 0, outZ() / 2)).distanceTo(mc.player.getEyePosition()));
            free = true;
            follow = true;
            distance = Math.max(2, Math.min((mc.options.getEffectiveRenderDistance() + 2) * 16.0, distance + amount * Math.max(1, distance / 8)));
        } else {
            wheel += amount;
            int steps = (int) wheel;
            if (steps != 0) {
                wheel -= steps;
                rotate(steps);
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- what shows

    /** In the world: the box being placed, or the selected area (also a while after its corners were set by command). */
    public static void render() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (kind == Kind.BUILD || kind == Kind.PASTE) {
            if (origin == null) return;
            AABB box = new AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + outX(), origin.getY() + sizeY, origin.getZ() + outZ());
            // the edges show through the ground (a whole-chunk copy sits at the height it came from, often below you);
            // the faint body only where it is in the open
            Gizmos.cuboid(box, GizmoStyle.stroke(follow ? 0xFF7CC4FF : 0xFFFFD166, 2.5f)).setAlwaysOnTop();
            // (not while you stand inside it: the whole view would be tinted)
            if (mc.player == null || !box.contains(mc.gameRenderer.mainCamera().position())) Gizmos.cuboid(box, GizmoStyle.fill(follow ? 0x287CC4FF : 0x28FFD166));
            return;
        }
        BlockPos a = SchematicLibrary.corner1(), b = SchematicLibrary.corner2();
        if (kind != Kind.SELECT && System.currentTimeMillis() - SchematicLibrary.cornersAt() > 60_000) return;
        if (kind == Kind.SELECT && mc.player != null && mc.gui.screen() == null) {
            // the cell the next click picks (seen through blocks, so a cell in the air behind a tree still shows)
            Gizmos.cuboid(new AABB(target(mc)).inflate(0.02), GizmoStyle.strokeAndFill(0xFFFFFFFF, 2f, 0x30FFFFFF)).setAlwaysOnTop();
        }
        if (a != null) Gizmos.cuboid(new AABB(a).inflate(0.01), GizmoStyle.stroke(0xFF4ADE80, 3f));
        if (b != null) Gizmos.cuboid(new AABB(b).inflate(0.01), GizmoStyle.stroke(0xFFFF6B6B, 3f));
        if (a != null && b != null) {
            Gizmos.cuboid(new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1), GizmoStyle.strokeAndFill(0xFFFFFFFF, 2f, 0x18FFFFFF));
        }
    }

    private static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** A key and what it does, drawn as a small key cap with its label. */
    private record Chip(String key, String what) {}

    /**
     * On screen: a card above the hotbar in the look of the other HUD cards (same size, same header with a status
     * chip) and in the smooth font: what is being placed, where, and the keys as rounded key caps.
     */
    private static void hud(GuiGraphicsExtractor g) {
        if (kind == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null || mc.player == null) return;
        String title, status, main, side, under;
        boolean lit;
        List<Chip> chips = new ArrayList<>();
        if (kind == Kind.SELECT) {
            BlockPos a = SchematicLibrary.corner1(), b = SchematicLibrary.corner2();
            title = "Select area";
            lit = a != null && b != null;
            status = lit ? SchematicLibrary.areaLabel() : a == null ? "Pick corner A" : "Pick corner B";
            main = "Corner A   " + (a == null ? "not set" : a.toShortString());
            side = "";
            under = "Corner B   " + (b == null ? "not set" : b.toShortString());
            chips.add(new Chip("Left click", "Corner A"));
            chips.add(new Chip("Right click", "Corner B"));
            chips.add(new Chip("Wheel", airReach == 0 ? "Reach: aim" : "Reach: " + airReach + " blocks"));
            chips.add(new Chip("Enter", "Save"));
            chips.add(new Chip("Esc", "Cancel"));
        } else {
            title = kind == Kind.BUILD ? "Schematic placement" : "Paste placement";
            lit = follow;
            status = follow ? (free ? "Floating" : "Follows aim") : "Locked";
            String shown = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            main = shown;
            if (trouble != null) {
                side = "";
                under = "Could not read it: " + trouble;
            } else if (kind == Kind.PASTE && copy == null) {
                side = "";
                under = "Reading the file...";
            } else {
                side = count(blocks) + " blocks";
                under = outX() + " x " + sizeY + " x " + outZ() + "   at   " + origin.toShortString();
            }
            chips.add(new Chip("E", kind == Kind.BUILD ? "Confirm" : "Paste here"));
            chips.add(new Chip("Esc", "Cancel"));
            chips.add(new Chip("R", "Rotate"));
            chips.add(new Chip("Right click", follow ? "Lock" : "Follow aim"));
            chips.add(new Chip("Shift + Wheel", "Distance"));
            chips.add(new Chip("Arrows", "Move"));
            chips.add(new Chip("PgUp  PgDn", "Height"));
            chips.add(new Chip("G", "Ground"));
        }
        Font font = mc.font;
        // the size of the other HUD cards (the HUD's own Scale % setting), so it sits among them as one of them
        float s = com.relaxedqol.client.gui.OverlayRenderer.hudScale();
        if (s <= 0) s = 1f;
        int screenW = (int) (mc.getWindow().getGuiScaledWidth() / s), screenH = (int) (mc.getWindow().getGuiScaledHeight() / s);
        g.pose().pushMatrix();
        g.pose().scale(s, s);
        com.relaxedqol.client.util.SmoothFont.beginAlways();
        try {
            int pad = HudStyle.PAD, capPad = 4, capGap = 5, gap = 12, rowH = 15, limit = Math.min(330, screenW - 24);
            boolean shadow = HudStyle.textShadow();
            // the key caps, wrapped into rows
            List<List<Chip>> rows = new ArrayList<>();
            List<Integer> widths = new ArrayList<>();
            List<Chip> row = new ArrayList<>();
            int rowW = 0;
            for (Chip c : chips) {
                int w = font.width(c.key()) + capPad * 2 + capGap + font.width(c.what());
                if (!row.isEmpty() && rowW + gap + w > limit) {
                    rows.add(row);
                    widths.add(rowW);
                    row = new ArrayList<>();
                    rowW = 0;
                }
                rowW += (row.isEmpty() ? 0 : gap) + w;
                row.add(c);
            }
            if (!row.isEmpty()) {
                rows.add(row);
                widths.add(rowW);
            }
            while (font.width(main) + 16 + font.width(side) > limit && main.length() > 10) main = main.substring(0, main.length() - 4) + "..";
            while (font.width(under) > limit && under.length() > 10) under = under.substring(0, under.length() - 4) + "..";
            int inner = Math.max(HudStyle.headerWidth(font, title, status) - pad * 2, Math.max(font.width(main) + 16 + font.width(side), font.width(under)));
            for (int w : widths) inner = Math.max(inner, w);
            int w = inner + pad * 2;
            int h = HudStyle.HEADER_H + 1 + 6 + 11 + 11 + 7 + rows.size() * rowH - (rowH - 11) + pad;
            int x = (screenW - w) / 2, y = screenH - (int) (62 / s) - h;
            HudStyle.card(g, x, y, w, h);
            HudStyle.header(g, font, x, y, w, title, status, lit);
            HudStyle.divider(g, x, y + HudStyle.HEADER_H, w);
            int ty = y + HudStyle.HEADER_H + 7;
            g.text(font, main, x + pad, ty, HudStyle.VALUE, shadow);
            if (!side.isEmpty()) g.text(font, side, x + w - pad - font.width(side), ty, HudStyle.LABEL, shadow);
            g.text(font, under, x + pad, ty + 11, trouble != null ? 0xFFFF6B6B : HudStyle.LABEL, shadow);
            int cy = ty + 11 + 11 + 7;
            for (List<Chip> line : rows) {
                int cx = x + pad;
                for (Chip c : line) {
                    int kw = font.width(c.key()) + capPad * 2;
                    com.relaxedqol.client.gui.Render2D.roundRect(g, cx, cy - 2, kw, 12, 3, 0x1CFFFFFF);
                    com.relaxedqol.client.gui.Render2D.roundOutline(g, cx, cy - 2, kw, 12, 3, 0x26FFFFFF);
                    g.text(font, c.key(), cx + capPad, cy, HudStyle.VALUE, shadow);
                    g.text(font, c.what(), cx + kw + capGap, cy, HudStyle.LABEL, shadow);
                    cx += kw + capGap + font.width(c.what()) + gap;
                }
                cy += rowH;
            }
        } finally {
            com.relaxedqol.client.util.SmoothFont.endAlways();
            g.pose().popMatrix();
        }
    }
}
