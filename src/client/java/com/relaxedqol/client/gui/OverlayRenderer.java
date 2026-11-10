package com.relaxedqol.client.gui;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.modules.render.HUD;
import com.relaxedqol.client.module.modules.render.Radar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the movable cards (the radar). In game they are part of the HUD;
 * while the menu is open the menu draws them on top (so they are not blurred) and lets you move/resize them.
 */
public final class OverlayRenderer {
    private OverlayRenderer() {}

    /** Scale of HUD coordinates (HUD "Scale %" setting). */
    public static float hudScale() {
        Minecraft mc = Minecraft.getInstance();
        HUD hud = RelaxedQolClient.moduleManager.getModule(HUD.class);
        return Theme.uiScale(mc.getWindow().getGuiScale(), hud != null ? hud.getSizePercent() : 100);
    }

    public static void renderAll(GuiGraphicsExtractor g, float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float scale = hudScale();
        int vw = Math.round(g.guiWidth() / scale);
        int vh = Math.round(g.guiHeight() / scale);

        Theme.refresh();
        // The cards look the same with the menu open as in game (the open menu draws them inside its smooth font)
        int smoothDepth = com.relaxedqol.client.util.SmoothFont.pause();
        try {
            g.pose().pushMatrix();
            g.pose().scale(scale, scale);
            Radar radar = RelaxedQolClient.enabled(Radar.class);
            if (radar != null) {
                // Every card is drawn in its own look (HUD editor), or the theme's
                HudStyle.begin(radar.cardStyle());
                String prev = LoverFx.beginScope("Radar");
                try {
                    radar.render(g, mc.font, vw, vh, partialTicks);
                } finally {
                    LoverFx.endScope(prev);
                    HudStyle.end();
                }
            }
            // Card modules (armor status, keystrokes...)
            // While Tab is held only the armor card stays (the radar is drawn above; the scoreboard is vanilla's)
            boolean tab = com.relaxedqol.client.util.TabHud.open();
            for (com.relaxedqol.client.module.Module m : RelaxedQolClient.moduleManager.getEnabledModules()) {
                if (tab && !(m instanceof com.relaxedqol.client.module.modules.render.ArmorStatus)) continue;
                if (m instanceof HudCard card && card.showsCard()) {
                    HudStyle.begin(card.cardStyle());
                    String prev = LoverFx.beginScope(m.getName());
                    try {
                        card.render(g, mc.font, vw, vh);
                    } finally {
                        LoverFx.endScope(prev);
                        HudStyle.end();
                    }
                }
            }
            g.pose().popMatrix();
        } finally {
            com.relaxedqol.client.util.SmoothFont.resume(smoothDepth);
        }
    }

    // ------------------------------------------------------------------ moving / resizing cards on any GUI

    private static HudWindow moving;
    private static HudWindow resizing;
    private static int offsetX;
    private static int offsetY;
    /** A click (press + release without dragging) on the radar card opens the big radar window. */
    private static double pressX;
    private static double pressY;
    private static boolean pressMoved;

    /** Mouse press on a card (drag to move, corner to resize). Returns true when a card was hit. */
    public static boolean mouseClicked(double guiX, double guiY, int button) {
        if (button != 0) return false;
        float s = hudScale();
        double hx = guiX / s;
        double hy = guiY / s;
        List<HudWindow> windows = visibleWindows();
        for (int i = windows.size() - 1; i >= 0; i--) {
            HudWindow window = windows.get(i);
            if (window.onGrip(hx, hy)) {
                resizing = window;
                return true;
            }
            if (window.contains(hx, hy)) {
                moving = window;
                pressX = guiX;
                pressY = guiY;
                pressMoved = false;
                offsetX = (int) hx - window.getX();
                offsetY = (int) hy - window.getY();
                return true;
            }
        }
        return false;
    }

    public static boolean mouseDragged(double guiX, double guiY) {
        if (moving == null && resizing == null) return false;
        float s = hudScale();
        double hx = guiX / s;
        double hy = guiY / s;
        if (moving != null && !pressMoved && Math.abs(guiX - pressX) + Math.abs(guiY - pressY) < 3) return true;
        if (moving != null) {
            pressMoved = true;
            moving.moveTo((int) hx - offsetX, (int) hy - offsetY);
        }
        else resizing.resizeTo(hx, hy);
        return true;
    }

    public static boolean mouseReleased() {
        boolean was = moving != null || resizing != null;
        if (moving != null && !pressMoved) {
            Radar radar = RelaxedQolClient.enabled(Radar.class);
            if (radar != null && moving == radar.getWindow()) {
                moving = null;
                Minecraft.getInstance().execute(radar::openWindow);
                return true;
            }
        }
        moving = null;
        resizing = null;
        return was;
    }

    /** Outline + grip on the cards under the mouse, so it is clear they can be moved. */
    public static void drawEditFrames(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        float s = hudScale();
        double hx = mouseX / s;
        double hy = mouseY / s;
        g.pose().pushMatrix();
        g.pose().scale(s, s);
        for (HudWindow window : visibleWindows()) {
            boolean hot = window.contains(hx, hy) || window == moving || window == resizing;
            if (hot) window.drawEditFrame(g, true, Theme.accent());
        }
        g.pose().popMatrix();
    }

    /** Cards that are on screen right now and can be moved/resized, topmost last. */
    public static List<HudWindow> visibleWindows() {
        List<HudWindow> windows = new ArrayList<>();
        for (HudElement e : elements()) windows.add(e.window());
        windows.removeIf(w -> !w.isVisible());
        return windows;
    }

    /**
     * Every HUD element that can be moved and styled (watermark, info panel, module list, the cards, the
     * watch list), visible or not, bottom-most first.
     */
    public static List<HudElement> elements() {
        List<HudElement> out = new ArrayList<>();
        if (RelaxedQolClient.moduleManager == null) return out;
        HUD hud = RelaxedQolClient.enabled(HUD.class);
        if (hud != null) out.addAll(hud.elements());
        com.relaxedqol.client.module.modules.misc.PlayerAlert alert =
            RelaxedQolClient.enabled(com.relaxedqol.client.module.modules.misc.PlayerAlert.class);
        if (alert != null) {
            HudElement e = alert.hudElement();
            if (e != null) out.add(e);
        }
        Radar radar = RelaxedQolClient.enabled(Radar.class);
        if (radar != null && radar.showsCard()) out.add(new HudElement("Radar", radar.getWindow(), radar.cardStyle(), radar));
        for (com.relaxedqol.client.module.Module m : RelaxedQolClient.moduleManager.getEnabledModules()) {
            if (m instanceof HudCard card) out.add(new HudElement(m.getName(), card.getWindow(), card.cardStyle(), m));
        }
        return out;
    }
}
