package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * (Lunar-style "Scrollable Tooltips"): scroll the mouse wheel while an item tooltip is showing to move it
 * up / down (Shift + wheel: sideways), so huge tooltips - full shulkers, books, long lore - can be read to the end.
 * It snaps back when you hover something else.
 */
public class ScrollableTooltips extends Module {
    private final Setting<Integer> speed = addSetting(new Setting<>("Scroll Speed", 12, 2, 40, 1), "Pixels per wheel step");
    private final Setting<Boolean> invert = addSetting(new Setting<>("Invert", false));
    private final Setting<Boolean> resetOnMove = addSetting(new Setting<>("Reset On New Item", true),
        "Snap back when the mouse moves to another slot");
    /**
     * By default the wheel only moves a tooltip while Ctrl is held - otherwise it scrolls whatever is under the
     * mouse (chat, logs, the client's windows) like normal. (A new setting name, so the old "Nothing" default does
     * not carry over.)
     */
    private final Setting<String> modifier = addSetting(Setting.mode("Scroll Tooltips With", "Ctrl", "Ctrl", "Smart", "Alt", "Always"),
        "Ctrl: only Ctrl + wheel moves a tooltip, the wheel alone scrolls chat / logs / lists as normal. Smart: Ctrl + wheel"
            + " anywhere, and the wheel alone in chests and inventories (not the creative menu). Alt: Alt + wheel. Always:"
            + " the wheel always moves a tooltip that is showing");

    private static int scrollX;
    private static int scrollY;
    private static double anchorX = -1;
    private static double anchorY = -1;
    private static long lastTooltip;

    public ScrollableTooltips() {
        super("ScrollableTooltips", "Scroll long item tooltips with the mouse wheel", ModuleCategory.RENDER);
    }

    @Override
    public boolean worksInScreens() {
        return true;
    }

    @Override
    protected void onDisable() {
        scrollX = 0;
        scrollY = 0;
    }

    private static double[] cursor() {
        double[] x = new double[1];
        double[] y = new double[1];
        long window = GLFW.glfwGetCurrentContext();
        if (window != 0L) GLFW.glfwGetCursorPos(window, x, y);
        return new double[]{x[0], y[0]};
    }

    private static double mouseX() {
        return cursor()[0];
    }

    private static double mouseY() {
        return cursor()[1];
    }

    // ------------------------------------------------------------------ chat hovers

    private static java.lang.reflect.Method styleAt;
    private static Object styleAtOwner;
    private static boolean styleAtLooked;

    /**
     * In chat, only ITEM hovers ([i] items shown in chat) scroll. Hovering a player's name shows their info card
     * (rank, stats...) - the wheel used to move that card instead of scrolling the chat. The hover under the mouse is
     * found through the chat screen's own "what text is under the mouse" method (looked up by reflection, so a game
     * version that names it differently just keeps the old behaviour).
     */
    private static boolean chatBlocksScroll(Minecraft mc) {
        if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen chat)) return false;
        net.minecraft.network.chat.Style style = hoveredChatStyle(mc, chat);
        if (style == null) return false;
        Object hover;
        try {
            hover = style.getHoverEvent();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
        if (hover == null) return false;
        String kind = hover.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        try {
            Object action = hover.getClass().getMethod("action").invoke(hover);
            if (action != null) kind += " " + action.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // the class name says it
        }
        return !kind.contains("item");
    }

    private static net.minecraft.network.chat.Style hoveredChatStyle(Minecraft mc, net.minecraft.client.gui.screens.ChatScreen chat) {
        double scale = Math.max(1, mc.getWindow().getGuiScale());
        double gx = mouseX() / scale;
        double gy = mouseY() / scale;
        if (!styleAtLooked) {
            styleAtLooked = true;
            styleAt = findStyleMethod(chat.getClass());
            styleAtOwner = styleAt != null ? "screen" : null;
            if (styleAt == null) {
                Object component = chatComponent(mc);
                if (component != null) {
                    styleAt = findStyleMethod(component.getClass());
                    styleAtOwner = styleAt != null ? component : null;
                }
            }
        }
        if (styleAt == null) return null;
        try {
            Object owner = "screen".equals(styleAtOwner) ? chat : styleAtOwner;
            Class<?>[] p = styleAt.getParameterTypes();
            Object r = p[0] == double.class ? styleAt.invoke(owner, gx, gy) : styleAt.invoke(owner, (int) gx, (int) gy);
            return r instanceof net.minecraft.network.chat.Style st ? st : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The game's chat component, found in its HUD object by type (no name needed). */
    private static Object chatComponent(Minecraft mc) {
        try {
            Object gui = mc.gui;
            for (Class<?> k = gui.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    if (f.getType() != net.minecraft.client.gui.components.ChatComponent.class) continue;
                    f.setAccessible(true);
                    return f.get(gui);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // not found
        }
        return null;
    }

    private static java.lang.reflect.Method findStyleMethod(Class<?> type) {
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() != net.minecraft.network.chat.Style.class || p.length != 2) continue;
                boolean dd = p[0] == double.class && p[1] == double.class;
                boolean ii = p[0] == int.class && p[1] == int.class;
                if (!dd && !ii) continue;
                try {
                    m.setAccessible(true);
                    return m;
                } catch (RuntimeException e) {
                    // not reachable
                }
            }
        }
        return null;
    }

    /** Called by the tooltip hook each time a tooltip is drawn: the shift to apply, or null for none. */
    public static int[] offsetForFrame() {
        ScrollableTooltips m = RelaxedQolClient.enabled(ScrollableTooltips.class);
        if (m == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() == null) return null;
        if (chatBlocksScroll(mc)) {
            // A player's info card in chat: never moved
            scrollX = 0;
            scrollY = 0;
            anchorX = -1;
            return null;
        }
        lastTooltip = System.currentTimeMillis();
        double mx = mouseX();
        double my = mouseY();
        if (m.resetOnMove.getValue() && anchorX >= 0) {
            // Another slot (window pixels: a slot is about 18 GUI pixels)
            double slotPx = 16 * Math.max(1, mc.getWindow().getGuiScale());
            if (Math.abs(mx - anchorX) > slotPx || Math.abs(my - anchorY) > slotPx) {
                scrollX = 0;
                scrollY = 0;
                anchorX = -1;
            }
        }
        if (scrollX == 0 && scrollY == 0) return null;
        return new int[]{scrollX, scrollY};
    }

    /** Mouse wheel (from ScrollHook): true when it moved a tooltip (then the screen does not get it). */
    public static boolean onScroll(double dx, double dy) {
        ScrollableTooltips m = RelaxedQolClient.enabled(ScrollableTooltips.class);
        if (m == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() == null || System.currentTimeMillis() - lastTooltip > 120) return false;
        // The wheel over a player's name in chat scrolls the chat, it does not move their info card
        if (chatBlocksScroll(mc)) return false;
        long window = GLFW.glfwGetCurrentContext();
        boolean shift = window != 0L && (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS);
        if (window != 0L) {
            boolean ctrl = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
            boolean alt = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
            String mode = m.modifier.getValue();
            if (mode.equals("Ctrl") && !ctrl) return false;
            if (mode.equals("Alt") && !alt) return false;
            if (mode.equals("Smart") && !ctrl) {
                // Without Ctrl only in chests / inventories, where the wheel has nothing else to scroll
                net.minecraft.client.gui.screens.Screen sc = mc.gui.screen();
                if (!(sc instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)
                    || sc instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen) return false;
            }
        }
        int step = m.speed.getValue() * (m.invert.getValue() ? -1 : 1);
        if (anchorX < 0) {
            anchorX = mouseX();
            anchorY = mouseY();
        }
        if (dx != 0) scrollX += (int) Math.round(dx * step);
        if (dy != 0) {
            if (shift) scrollX += (int) Math.round(dy * step);
            else scrollY += (int) Math.round(dy * step);
        }
        scrollX = Math.max(-4000, Math.min(4000, scrollX));
        scrollY = Math.max(-8000, Math.min(8000, scrollY));
        return true;
    }
}
