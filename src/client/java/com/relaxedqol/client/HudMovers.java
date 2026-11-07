package com.relaxedqol.client;

import com.relaxedqol.client.module.modules.render.ActionBar;
import com.relaxedqol.client.module.modules.render.BossBar;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/**
 * Wraps vanilla HUD parts so modules can move / scale / hide them (boss bars, action bar). The vanilla
 * drawing is kept as is - it just runs inside a moved and scaled view - so it always looks exactly like vanilla
 * and nothing breaks when a module is off.
 */
public final class HudMovers {
    private HudMovers() {}

    /** A dashed accent frame with a label, drawn in the moved / scaled view of a HUD part. */
    private static void previewBox(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean hidden) {
        int accent = com.relaxedqol.client.gui.Theme.accent();
        long t = System.currentTimeMillis();
        float pulse = 0.55f + 0.25f * (float) Math.sin(t / 220.0);
        com.relaxedqol.client.gui.Render2D.roundRect(g, x, y, w, h, 5, com.relaxedqol.client.gui.Theme.withAlpha(accent, 0.12f));
        // Dashes along the edges
        int dash = 6;
        int off = (int) ((t / 60) % (dash * 2));
        int c = com.relaxedqol.client.gui.Theme.withAlpha(accent, pulse);
        for (int i = -off; i < w; i += dash * 2) {
            int a = Math.max(0, i);
            int b = Math.min(w, i + dash);
            if (b > a) {
                g.fill(x + a, y, x + b, y + 1, c);
                g.fill(x + a, y + h - 1, x + b, y + h, c);
            }
        }
        for (int i = -off; i < h; i += dash * 2) {
            int a = Math.max(0, i);
            int b = Math.min(h, i + dash);
            if (b > a) {
                g.fill(x, y + a, x + 1, y + b, c);
                g.fill(x + w - 1, y + a, x + w, y + b, c);
            }
        }
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        String text = hidden ? label + " (hidden)" : label;
        int tw = font.width(text);
        com.relaxedqol.client.gui.Render2D.roundRect(g, x + (w - tw) / 2 - 4, y - 11, tw + 8, 10, 4, 0xC0101018);
        g.text(font, text, x + (w - tw) / 2, y - 10, 0xFFFFFFFF, false);
    }

    public static void install() {
        // LoverFella Mode: LoverFella sits on the top-left corner of the scoreboard. The vanilla sidebar is drawn
        // as always; while it draws, its size is measured, then the figure is drawn on its corner.
        try {
            HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD, old -> (g, delta) -> {
                // Out of the way of the F3 lines on the right
                if (com.relaxedqol.client.util.DebugHud.open()) return;
                com.relaxedqol.client.module.modules.render.Scoreboard board =
                    RelaxedQolClient.enabled(com.relaxedqol.client.module.modules.render.Scoreboard.class);
                if (board != null && board.hidden()) return;
                boolean sitter = com.relaxedqol.client.gui.LoverFx.scoreboardSitterOn();
                if (board == null && !sitter) {
                    old.extractRenderState(g, delta);
                    return;
                }
                // Scoreboard: vanilla's sidebar drawn through a moved / scaled view. Its natural box (measured
                // last frame) becomes a HUD card, so it can be dragged and resized like the other cards.
                int[] nat = board != null ? board.natural() : null;
                boolean moved = nat != null;
                if (moved) {
                    float hs = com.relaxedqol.client.gui.OverlayRenderer.hudScale();
                    int vw = Math.round(g.guiWidth() / hs);
                    int vh = Math.round(g.guiHeight() / hs);
                    g.pose().pushMatrix();
                    g.pose().scale(hs, hs);
                    board.getWindow().begin(g, vw, vh, Math.max(1, Math.round(nat[2] / hs)), Math.max(1, Math.round(nat[3] / hs)),
                        Math.round(nat[0] / hs), Math.round((nat[1] + nat[3] * (1f - board.sizeScale()) / 2f) / hs));
                    g.pose().scale(1f / hs, 1f / hs);
                    g.pose().translate(-nat[0], -nat[1]);
                }
                com.relaxedqol.client.util.ScoreboardBounds.begin();
                try {
                    old.extractRenderState(g, delta);
                } finally {
                    com.relaxedqol.client.util.ScoreboardBounds.end();
                }
                int[] box = com.relaxedqol.client.util.ScoreboardBounds.last();
                if (box != null && sitter) com.relaxedqol.client.gui.LoverFx.drawScoreboardSitter(g, box);
                if (moved) {
                    board.getWindow().end(g);
                    g.pose().popMatrix();
                }
                if (board != null) board.setNatural(box);
            });
        } catch (RuntimeException e) {
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("LoverFella Mode: could not wrap the scoreboard", e);
        }
        // ArmorStatus: the armor icons above the hearts with an enchant shimmer / wear colour
        try {
            HudElementRegistry.replaceElement(VanillaHudElements.ARMOR_BAR, old -> (g, delta) -> {
                com.relaxedqol.client.module.modules.render.ArmorStatus status =
                    RelaxedQolClient.enabled(com.relaxedqol.client.module.modules.render.ArmorStatus.class);
                if (status == null || !status.drawArmorBar(g)) old.extractRenderState(g, delta);
            });
        } catch (RuntimeException e) {
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("ArmorStatus: could not wrap the armor bar", e);
        }
        // LocatorBar: drawn right after the experience / locator bar (hidden with F1 like it)
        try {
            HudElementRegistry.attachElementAfter(VanillaHudElements.INFO_BAR,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(com.relaxedqol.RelaxedQolMod.MOD_ID, "locator_heads"), (g, delta) -> {
                    com.relaxedqol.client.module.modules.render.LocatorBar bar =
                        RelaxedQolClient.enabled(com.relaxedqol.client.module.modules.render.LocatorBar.class);
                    if (bar != null && !com.relaxedqol.client.util.DebugHud.open()) bar.render(g);
                });
        } catch (RuntimeException e) {
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("LocatorBar: could not add its HUD layer", e);
        }
        // BlockOutline: hide the vanilla outline while the module draws its own
        try {
            net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outline) ->
                RelaxedQolClient.enabled(com.relaxedqol.client.module.modules.render.BlockOutline.class) == null);
        } catch (RuntimeException | LinkageError e) {
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("BlockOutline: could not hook the vanilla outline", e);
        }
        try {
            HudElementRegistry.replaceElement(VanillaHudElements.BOSS_BAR, old -> (g, delta) -> {
                BossBar mover = RelaxedQolClient.enabled(BossBar.class);
                if (mover == null) {
                    old.extractRenderState(g, delta);
                    return;
                }
                if (mover.hidden() && !mover.previewing()) return;
                float s = mover.scale();
                int cx = g.guiWidth() / 2;
                g.pose().pushMatrix();
                g.pose().translate(cx + mover.offsetX(), mover.offsetY());
                g.pose().scale(s, s);
                g.pose().translate(-cx, 0);
                if (!mover.hidden()) old.extractRenderState(g, delta);
                // Where (and how big) the boss bars go while you change the settings
                if (mover.previewing()) previewBox(g, cx - 95, 2, 190, 30, "Boss Bar " + Math.round(s * 100) + "%", mover.hidden());
                g.pose().popMatrix();
            });
        } catch (RuntimeException e) {
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("BossBar: could not wrap the boss bar", e);
        }
        try {
            HudElementRegistry.replaceElement(VanillaHudElements.OVERLAY_MESSAGE, old -> (g, delta) -> {
                ActionBar mover = RelaxedQolClient.enabled(ActionBar.class);
                if (mover == null) {
                    old.extractRenderState(g, delta);
                    return;
                }
                if (mover.hidden() && !mover.previewing()) return;
                float s = mover.scale();
                // Vanilla draws it centred at (width / 2, height - 68): scale around that point
                int cx = g.guiWidth() / 2;
                int cy = g.guiHeight() - 68;
                g.pose().pushMatrix();
                g.pose().translate(cx + mover.offsetX(), cy + mover.offsetY());
                g.pose().scale(s, s);
                g.pose().translate(-cx, -cy);
                if (!mover.hidden()) old.extractRenderState(g, delta);
                // An outline of where the action bar text goes (and how big) while you change the settings
                if (mover.previewing()) previewBox(g, cx - 100, cy - 8, 200, 16, "Action Bar " + Math.round(s * 100) + "%", mover.hidden());
                g.pose().popMatrix();
            });
        } catch (RuntimeException e) {
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("ActionBar: could not wrap the action bar", e);
        }
    }
}
