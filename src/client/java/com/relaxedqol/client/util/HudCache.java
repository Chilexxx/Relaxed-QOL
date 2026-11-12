package com.relaxedqol.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Smart HUD" (part of Settings > FPS Boost), the idea behind Lunar's / Exordium's HUD caching: the client's
 * own HUD (module list, info panel, cards, alerts...) is worked out at most 60 times a second instead of every frame.
 * On the frames in between, the finished shapes and texts of the last time are handed to the game again as they
 * are - all the work of building them (layouts, measuring, colours, animations, minimap, faces) is skipped. At 300
 * FPS that work is done 60 times instead of 300 times a second; what you see is the same.
 *
 * Safe by design: anything the cache cannot repeat exactly (items, 3D previews) makes that HUD layer draw normally
 * for a while; below ~90 FPS it always draws normally (nothing to gain there); and any error switches it off.
 */
public final class HudCache {
    private HudCache() {}

    /** A recorded step: a shape the game took, a text, or a layer step. */
    private sealed interface Op permits Element, Text, Marker {}

    private record Element(Object state) implements Op {}

    private record Text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow, Matrix3x2f pose,
                        Object scissor, boolean smooth) implements Op {}

    private record Marker(String what) implements Op {}

    private static final class Layer {
        List<Op> ops = new ArrayList<>();
        long recordedNs;
        long blockedUntil;
        int guiW;
        int guiH;
        boolean valid;
    }

    private static final Map<String, Layer> LAYERS = new HashMap<>();
    private static Layer recording;
    private static boolean replaying;
    private static boolean broken;
    private static long lastFrameNs;
    private static float frameMs = 16f;
    private static final long REFRESH_NS = 16_666_666L; // 60 times a second

    /** Once per frame, before the client's HUD layers (when no layer is drawn with first = true). */
    public static void beginFrame() {
        frame();
    }

    /** Called once per frame by the first HUD layer: keeps track of the frame time. */
    private static void frame() {
        long now = System.nanoTime();
        if (lastFrameNs != 0) {
            float ms = (now - lastFrameNs) / 1_000_000f;
            if (ms > 0 && ms < 1000) frameMs = frameMs * 0.9f + ms * 0.1f;
        }
        lastFrameNs = now;
    }

    private static boolean enabled() {
        return !broken && com.relaxedqol.client.module.modules.client.ClickGUI.fpsBoost();
    }

    /**
     * Draws one HUD layer through the cache: either repeats the last recording, or runs {@code draw} and records it.
     * first = the first client layer of the frame (measures the frame time).
     */
    public static void layer(String name, GuiGraphicsExtractor g, boolean first, Runnable draw) {
        if (first) frame();
        Minecraft mc = Minecraft.getInstance();
        Layer layer = LAYERS.computeIfAbsent(name, k -> new Layer());
        long now = System.nanoTime();
        boolean fast = frameMs < 11f; // above ~90 FPS
        boolean usable = enabled() && fast && mc.gui.screen() == null && recording == null && !replaying;
        if (!usable || now < layer.blockedUntil) {
            layer.valid = false;
            draw.run();
            return;
        }
        boolean sameSize = layer.guiW == g.guiWidth() && layer.guiH == g.guiHeight();
        long refresh = com.relaxedqol.client.module.modules.client.ClickGUI.lowEnd() ? REFRESH_NS * 2 : REFRESH_NS;
        if (layer.valid && sameSize && now - layer.recordedNs < refresh) {
            if (replay(layer, g)) return;
            layer.valid = false;
        }
        // Record a fresh one
        layer.ops = new ArrayList<>();
        layer.valid = false;
        recording = layer;
        aborted = false;
        addTextCalls = 0;
        textOps = 0;
        try {
            draw.run();
        } finally {
            recording = null;
        }
        // Every text the game took must be one we recorded (otherwise some text would vanish on repeated frames)
        boolean anyShape = false;
        for (Op op : layer.ops) {
            if (op instanceof Element) {
                anyShape = true;
                break;
            }
        }
        // Texts but not a single shape: the shape hook is missing on this game version - never cache
        if (textOps > 0 && !anyShape) {
            broken = true;
            return;
        }
        if (textOps > 0 && addTextCalls == 0) {
            broken = true; // cannot check this on this game version: keep drawing normally
            return;
        }
        if (addTextCalls != textOps) aborted = true;
        if (aborted) {
            layer.blockedUntil = now + 3_000_000_000L;
            layer.ops = new ArrayList<>();
            return;
        }
        layer.recordedNs = now;
        layer.guiW = g.guiWidth();
        layer.guiH = g.guiHeight();
        layer.valid = true;
    }

    private static boolean aborted;
    private static int addTextCalls;
    private static int textOps;

    /** The game took a text (whichever way it came in). */
    public static void onAddText() {
        if (recording()) addTextCalls++;
    }

    private static boolean replay(Layer layer, GuiGraphicsExtractor g) {
        replaying = true;
        try {
            Object state = guiState(g);
            for (Op op : layer.ops) {
                switch (op) {
                    case Element e -> addElement(state, e.state());
                    case Marker m -> marker(state, m.what());
                    case Text t -> {
                        var pose = g.pose();
                        pose.pushMatrix();
                        pose.set(t.pose());
                        boolean pushed = pushScissor(g, t.scissor());
                        if (t.smooth()) SmoothFont.begin();
                        try {
                            g.text(t.font(), t.text(), t.x(), t.y(), t.color(), t.shadow());
                        } finally {
                            if (t.smooth()) SmoothFont.end();
                            if (pushed) popScissor(g);
                            pose.popMatrix();
                        }
                    }
                }
            }
            return true;
        } catch (Throwable e) {
            // Something this game version does differently: never use the cache again (the HUD just draws normally)
            broken = true;
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("Smart HUD: switched off", e);
            return false;
        } finally {
            replaying = false;
        }
    }

    // ------------------------------------------------------------------ recording (called from HudCacheMixin)

    public static boolean recording() {
        return recording != null && !replaying;
    }

    public static void onElement(Object element) {
        if (recording()) recording.ops.add(new Element(element));
    }

    public static void onMarker(String what) {
        if (recording()) recording.ops.add(new Marker(what));
    }

    /** Something the cache cannot repeat (an item, a 3D preview): this layer draws normally for a while. */
    public static void onUnsupported() {
        if (recording()) aborted = true;
    }

    public static void onText(GuiGraphicsExtractor g, Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        if (!recording()) return;
        if ((color >>> 24) != 0) textOps++;
        recording.ops.add(new Text(font, text, x, y, color, shadow, new Matrix3x2f(g.pose()), peekScissor(g), SmoothFont.active()));
    }

    // ------------------------------------------------------------------ the game's GUI state, found by type

    private static java.lang.reflect.Field stateField;
    private static java.lang.reflect.Method addElement;
    private static java.lang.reflect.Method up;
    private static java.lang.reflect.Method nextStratum;
    private static java.lang.reflect.Field scissorField;
    private static java.lang.reflect.Method peek;
    private static java.lang.reflect.Method push;
    private static java.lang.reflect.Method pop;

    private static Object guiState(GuiGraphicsExtractor g) throws ReflectiveOperationException {
        if (stateField == null) {
            for (java.lang.reflect.Field f : GuiGraphicsExtractor.class.getDeclaredFields()) {
                if (f.getType().getSimpleName().equals("GuiRenderState")) {
                    f.setAccessible(true);
                    stateField = f;
                }
            }
            if (stateField == null) throw new NoSuchFieldException("guiRenderState");
            for (java.lang.reflect.Method m : stateField.getType().getMethods()) {
                if (m.getName().equals("addGuiElement") && m.getParameterCount() == 1) addElement = m;
                if (m.getName().equals("up") && m.getParameterCount() == 0) up = m;
                if (m.getName().equals("nextStratum") && m.getParameterCount() == 0) nextStratum = m;
            }
            if (addElement == null || up == null) throw new NoSuchMethodException("GuiRenderState.addGuiElement / up");
        }
        return stateField.get(g);
    }

    private static void addElement(Object state, Object element) throws ReflectiveOperationException {
        addElement.invoke(state, element);
    }

    private static void marker(Object state, String what) throws ReflectiveOperationException {
        if (what.equals("up")) up.invoke(state);
        else if (what.equals("stratum")) {
            if (nextStratum == null) throw new NoSuchMethodException("nextStratum");
            nextStratum.invoke(state);
        }
    }

    private static void findScissor() throws ReflectiveOperationException {
        if (scissorField != null) return;
        for (java.lang.reflect.Field f : GuiGraphicsExtractor.class.getDeclaredFields()) {
            if (f.getType().getSimpleName().equals("ScissorStack")) {
                f.setAccessible(true);
                scissorField = f;
            }
        }
        if (scissorField == null) throw new NoSuchFieldException("scissorStack");
        for (java.lang.reflect.Method m : scissorField.getType().getDeclaredMethods()) {
            m.setAccessible(true);
            if (m.getName().equals("peek") && m.getParameterCount() == 0) peek = m;
            if (m.getName().equals("push") && m.getParameterCount() == 1) push = m;
            if (m.getName().equals("pop") && m.getParameterCount() == 0) pop = m;
        }
        if (peek == null || push == null || pop == null) throw new NoSuchMethodException("ScissorStack");
    }

    private static Object peekScissor(GuiGraphicsExtractor g) {
        try {
            findScissor();
            return peek.invoke(scissorField.get(g));
        } catch (ReflectiveOperationException | RuntimeException e) {
            aborted = true; // cannot tell where text is clipped: do not cache this layer
            return null;
        }
    }

    private static boolean pushScissor(GuiGraphicsExtractor g, Object rect) throws ReflectiveOperationException {
        if (rect == null) return false;
        findScissor();
        push.invoke(scissorField.get(g), rect);
        return true;
    }

    private static void popScissor(GuiGraphicsExtractor g) throws ReflectiveOperationException {
        pop.invoke(scissorField.get(g));
    }
}
