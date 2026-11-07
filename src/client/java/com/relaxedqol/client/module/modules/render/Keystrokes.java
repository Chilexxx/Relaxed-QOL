package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.gui.HudCard;
import com.relaxedqol.client.gui.HudWindow;
import com.relaxedqol.client.gui.Render2D;
import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.Colors;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * (Lunar-style "Keystrokes"): W A S D, the mouse buttons with clicks per second and the space bar on your
 * screen. Keys light up when pressed and fade out when let go. Shows the keys you actually bound (an AZERTY "Z" shows as Z).
 *
 * Holding a key (walking) no longer flickers. The game's own "is this key held" flag can drop for a frame now
 * and then (key repeat, other mods / modules touching it), which dimmed the key for a moment every time. Now a key is
 * read from the keyboard / mouse itself, lights up at once (no fade-in), and only starts fading after it has really
 * been let go for a moment.
 */
public class Keystrokes extends Module implements HudCard {
    private final Setting<Boolean> mouse = addSetting(new Setting<>("Mouse Buttons", true));
    private final Setting<Boolean> cps = addSetting(new Setting<>("Show CPS", true), "Clicks per second on the mouse buttons");
    private final Setting<Boolean> space = addSetting(new Setting<>("Space Bar", true));
    private final Setting<Boolean> sneak = addSetting(new Setting<>("Sneak", false), "A Shift bar under the space bar");
    private final Setting<String> pressColor = addSetting(Colors.setting("Pressed Colour", "Accent"));
    private final Setting<Integer> opacity = addSetting(new Setting<>("Background %", 55, 0, 100, 5));
    private final Setting<Integer> fadeMs = addSetting(new Setting<>("Fade (ms)", 120, 0, 400, 10), "How long a key takes to dim after you let go (it lights up at once)");
    private final Setting<Integer> winX = addSetting(HudWindow.xSetting());
    private final Setting<Integer> winY = addSetting(HudWindow.ySetting());
    private final Setting<Integer> winScale = addSetting(HudWindow.scaleSetting());
    private final HudWindow window = new HudWindow(winX, winY, winScale);

    private final float[] glow = new float[8];
    /** When each key was last seen held (a key stays lit this long after, so a dropped frame never blinks it). */
    private final long[] lastHeld = new long[8];
    private static final long HOLD_MS = 90;
    private static java.lang.reflect.Field keyField;
    private static boolean keyFieldLooked;
    private final Deque<Long> leftClicks = new ArrayDeque<>();
    private final Deque<Long> rightClicks = new ArrayDeque<>();
    private boolean leftWas;
    private boolean rightWas;
    private long lastFrame;

    public Keystrokes() {
        super("Keystrokes", "Shows your movement keys, mouse clicks (CPS) and jumps", ModuleCategory.RENDER);
    }

    /** The keys' own look (HUD editor): background, edge, roundness, text colour. */
    private final com.relaxedqol.client.gui.CardStyle style = new com.relaxedqol.client.gui.CardStyle("", this::addSetting);

    @Override
    public com.relaxedqol.client.gui.CardStyle cardStyle() {
        return style;
    }

    @Override
    public HudWindow getWindow() {
        return window;
    }

    private static String keyName(KeyMapping key, String fallback) {
        try {
            String s = key.getTranslatedKeyMessage().getString();
            if (s.isEmpty()) return fallback;
            return s.length() > 5 ? s.substring(0, 5) : s.toUpperCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * Whether a key is held, read from the keyboard / mouse itself (steady while you hold it), with the game's
     * own flag as a fallback. While a screen is open (chat, inventory) only the game's flag counts, so typing in chat
     * doesn't light the keys.
     */
    private boolean held(KeyMapping key) {
        if (key.isDown()) return true;
        if (mc.gui.screen() != null || mc.getWindow() == null) return false;
        try {
            com.mojang.blaze3d.platform.InputConstants.Key bound = boundKey(key);
            if (bound == null || bound.getValue() < 0) return false;
            if (bound.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM) {
                return com.mojang.blaze3d.platform.InputConstants.isKeyDown(mc.getWindow(), bound.getValue());
            }
            if (bound.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
                long window = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
                return window != 0L && org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, bound.getValue()) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // the game's flag only
        }
        return false;
    }

    /** The key / button a key binding is set to (its field, found by type so a renamed field still works). */
    private static com.mojang.blaze3d.platform.InputConstants.Key boundKey(KeyMapping key) {
        if (!keyFieldLooked) {
            keyFieldLooked = true;
            for (Class<?> c = KeyMapping.class; c != null && keyField == null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() != com.mojang.blaze3d.platform.InputConstants.Key.class
                        || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    // The default key is final; the key you bound is the one that changes
                    if (keyField == null || !java.lang.reflect.Modifier.isFinal(f.getModifiers())) keyField = f;
                }
            }
            if (keyField != null) {
                try {
                    keyField.setAccessible(true);
                } catch (RuntimeException e) {
                    keyField = null;
                }
            }
        }
        if (keyField == null) return null;
        try {
            return (com.mojang.blaze3d.platform.InputConstants.Key) keyField.get(key);
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    private static int cpsOf(Deque<Long> clicks, long now) {
        while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000) clicks.pollFirst();
        return clicks.size();
    }

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int vw, int vh) {
        if (mc.options == null) return;
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        KeyMapping[] keys = {mc.options.keyUp, mc.options.keyLeft, mc.options.keyDown, mc.options.keyRight,
            mc.options.keyAttack, mc.options.keyUse, mc.options.keyJump, mc.options.keyShift};
        boolean[] down = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) down[i] = held(keys[i]);
        boolean left = down[4];
        boolean right = down[5];
        if (left && !leftWas) leftClicks.addLast(now);
        if (right && !rightWas) rightClicks.addLast(now);
        leftWas = left;
        rightWas = right;
        float speed = fadeMs.getValue() <= 0 ? 1000f : 1000f / fadeMs.getValue();
        for (int i = 0; i < keys.length; i++) {
            if (down[i]) lastHeld[i] = now;
            boolean lit = down[i] || now - lastHeld[i] < HOLD_MS;
            // Pressed: fully lit straight away and it stays that way while held. Let go: a smooth fade out.
            if (lit || fadeMs.getValue() <= 0) glow[i] = lit ? 1f : 0f;
            else glow[i] = Math.max(0f, glow[i] - dt * speed);
        }

        int k = 22;
        int gap = 2;
        int w = k * 3 + gap * 2;
        int h = k * 2 + gap;
        if (mouse.getValue()) h += gap + k;
        if (space.getValue()) h += gap + 12;
        if (sneak.getValue()) h += gap + 12;
        // By default it sits higher up on the left, clear of the chat (it used to cover the chat lines)
        int chatTop = vh - Math.round(150 / com.relaxedqol.client.gui.OverlayRenderer.hudScale());
        window.begin(g, vw, vh, w, h, 4, Math.max(30, chatTop - h - 8));
        int accent = Colors.of(pressColor.getValue());
        // W on top, A S D below
        key(g, font, k + gap, 0, k, k, keyName(keys[0], "W"), null, glow[0], accent);
        key(g, font, 0, k + gap, k, k, keyName(keys[1], "A"), null, glow[1], accent);
        key(g, font, k + gap, k + gap, k, k, keyName(keys[2], "S"), null, glow[2], accent);
        key(g, font, (k + gap) * 2, k + gap, k, k, keyName(keys[3], "D"), null, glow[3], accent);
        int y = (k + gap) * 2;
        if (mouse.getValue()) {
            int mw = (w - gap) / 2;
            key(g, font, 0, y, mw, k, "LMB", cps.getValue() ? cpsOf(leftClicks, now) + " CPS" : null, glow[4], accent);
            key(g, font, mw + gap, y, w - mw - gap, k, "RMB", cps.getValue() ? cpsOf(rightClicks, now) + " CPS" : null, glow[5], accent);
            y += k + gap;
        }
        if (space.getValue()) {
            key(g, font, 0, y, w, 12, null, null, glow[6], accent);
            int lw = w / 3;
            g.fill((w - lw) / 2, y + 5, (w + lw) / 2, y + 6, Theme.mix(0xFFFFFFFF, 0xFF101018, glow[6] * 0.8f));
            y += 12 + gap;
        }
        if (sneak.getValue()) key(g, font, 0, y, w, 12, keyName(keys[7], "SHIFT"), null, glow[7], accent);
        window.end(g);
    }

    private void key(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String label, String sub, float on, int accent) {
        boolean custom = style.isCustom();
        int bgAlpha = Math.round((custom ? com.relaxedqol.client.gui.HudStyle.bodyAlpha() * 100 : opacity.getValue()) * 2.55f);
        int base = Colors.withAlpha(custom ? com.relaxedqol.client.gui.HudStyle.bodyColor() : 0xFF111214, bgAlpha);
        int keyR = custom ? Math.min(com.relaxedqol.client.gui.HudStyle.RADIUS, 8) : 4;
        int idleEdge = custom ? com.relaxedqol.client.gui.HudStyle.EDGE : Theme.HAIRLINE;
        int idleText = custom ? com.relaxedqol.client.gui.HudStyle.VALUE : 0xFFF2F2F7;
        int lit = Colors.withAlpha(accent, Math.max(bgAlpha, 200));
        Render2D.roundRect(g, x, y, w, h, keyR, Theme.mix(base, lit, on));
        Render2D.roundOutline(g, x, y, w, h, keyR, Theme.mix(idleEdge, Colors.withAlpha(accent, 255), on));
        // No more highlight line along the top of a held key (it looked like a stray line above the key)
        int textColor = Theme.mix(idleText, 0xFF0B0B12, on * 0.85f);
        // Crisp text (whole screen pixels per font pixel, on the pixel grid) at any card size
        if (label != null) {
            float ty = sub == null ? y + (h - 8) / 2f : y + 3;
            window.crispText(g, font, label, x + w / 2f, ty, textColor, 1f, true, false);
        }
        if (sub != null) {
            window.crispText(g, font, sub, x + w / 2f, y + h - 9, Theme.mix(0xFFB0B0BE, 0xFF202028, on * 0.85f), 0.75f, true, false);
        }
    }
}
