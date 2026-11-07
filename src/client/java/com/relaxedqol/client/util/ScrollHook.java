package com.relaxedqol.client.util;

import com.relaxedqol.RelaxedQolMod;
import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.modules.render.Zoom;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;

/**
 * Sees the mouse wheel before the game does (for Zoom's scroll-to-zoom). It wraps the game's own GLFW scroll
 * callback: when Zoom uses a scroll it is swallowed (no hotbar scrolling), every other scroll goes to the game as usual.
 */
public final class ScrollHook {
    /** Kept so neither callback is ever garbage collected while GLFW still points at it. */
    private static GLFWScrollCallback ours;
    private static GLFWScrollCallback previous;

    private ScrollHook() {}

    /** Call once on the main thread after the game window exists (CLIENT_STARTED). */
    public static void install() {
        if (ours != null) return;
        try {
            long window = GLFW.glfwGetCurrentContext();
            if (window == 0L) return;
            ours = GLFWScrollCallback.create((handle, dx, dy) -> {
                try {
                    Zoom zoom = RelaxedQolClient.enabled(Zoom.class);
                    if (zoom != null && zoom.onScroll(dy)) return;
                    // Wheel over a tooltip scrolls the tooltip
                    if (com.relaxedqol.client.module.modules.render.ScrollableTooltips.onScroll(dx, dy)) return;
                } catch (RuntimeException e) {
                    RelaxedQolMod.LOGGER.warn("Scroll hook failed", e);
                }
                if (previous != null) previous.invoke(handle, dx, dy);
            });
            previous = GLFW.glfwSetScrollCallback(window, ours);
        } catch (Throwable t) {
            // Never break the mouse: without the hook Zoom simply has no scroll-to-zoom
            RelaxedQolMod.LOGGER.warn("Could not install the scroll hook", t);
        }
    }
}
