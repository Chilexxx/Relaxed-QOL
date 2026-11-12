package com.relaxedqol.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The edge of a pane of liquid glass: a thin bright rim that is brightest where the light hits (the top-left corner,
 * and a softer catch on the bottom-right where it comes back out), and a soft glow just inside it where the glass
 * bends the light. Painted once per size into a small smooth texture at screen resolution, then laid around any box
 * in nine pieces (four corners and four stretched edges), so it is sharp at every GUI scale and costs only eight
 * quads per box.
 */
public final class GlassRim {
    private GlassRim() {}

    private static final Map<String, Identifier> TEXTURES = new HashMap<>();
    private static boolean broken;

    /** A big pane (windows, menu panels, cards): rim plus the soft inner glow. */
    public static boolean surface(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, float strength) {
        return draw(g, x, y, w, h, r, strength, true);
    }

    /** A small piece (buttons, chips, switches, slots): just the bright rim. */
    public static boolean control(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, float strength) {
        return draw(g, x, y, w, h, r, strength, false);
    }

    private static boolean draw(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, float strength, boolean glow) {
        if (broken) return false;
        if (w < 4 || h < 4 || strength <= 0.01f) return true;
        // Screen pixels per unit here (GUI scale times any card / menu scale), so the edge is painted for exactly
        // the size it lands on: crisp and the same thickness on a scaled HUD card as anywhere else
        float k = Math.max(1f, Math.round(com.relaxedqol.client.util.SmoothFont.pixelsPer(g.pose()) * 4f) / 4f);
        int half = Math.min(w, h) / 2;
        r = Math.max(0, Math.min(r, half));
        int c = Math.min(Math.max(r + 4, glow ? 12 : 6), half);
        if (c < 2) return true;
        Identifier id = texture(k, r, c, glow);
        if (id == null) return false;
        int s = Math.round(c * k);
        int t = 2 * s;
        int col = (Math.round(Math.min(1f, strength) * 255) << 24) | 0xFFFFFF;
        int X = Math.round(x * k);
        int Y = Math.round(y * k);
        int W = Math.round((x + w) * k) - X;
        int H = Math.round((y + h) * k) - Y;
        g.pose().pushMatrix();
        g.pose().scale(1f / k, 1f / k);
        g.blit(RenderPipelines.GUI_TEXTURED, id, X, Y, 0, 0, s, s, t, t, col);
        g.blit(RenderPipelines.GUI_TEXTURED, id, X + W - s, Y, s, 0, s, s, t, t, col);
        g.blit(RenderPipelines.GUI_TEXTURED, id, X, Y + H - s, 0, s, s, s, t, t, col);
        g.blit(RenderPipelines.GUI_TEXTURED, id, X + W - s, Y + H - s, s, s, s, s, t, t, col);
        int midW = W - 2 * s;
        int midH = H - 2 * s;
        if (midW > 0) {
            stretch(g, id, X + s, Y, s - 1, 0, 1, s, midW, 1f, t, col);
            stretch(g, id, X + s, Y + H - s, s - 1, s, 1, s, midW, 1f, t, col);
        }
        if (midH > 0) {
            stretch(g, id, X, Y + s, 0, s - 1, s, 1, 1f, midH, t, col);
            stretch(g, id, X + W - s, Y + s, s, s - 1, s, 1, 1f, midH, t, col);
        }
        g.pose().popMatrix();
        return true;
    }

    /** One strip of the texture (one pixel thick) stretched along an edge. */
    private static void stretch(GuiGraphicsExtractor g, Identifier id, int x, int y, int u, int v, int uw, int vh,
                                float sx, float sy, int t, int col) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(sx, sy);
        g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, u, v, uw, vh, t, t, col);
        g.pose().popMatrix();
    }

    private static Identifier texture(float k, int r, int c, boolean glow) {
        String key = Math.round(k * 4) + "_" + r + "_" + c + (glow ? "g" : "c");
        Identifier id = TEXTURES.get(key);
        if (id != null) return id;
        if (TEXTURES.size() > 400) return null; // never grows without end
        try {
            BufferedImage img = paint(k, r, c, glow);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", png);
            com.mojang.blaze3d.platform.NativeImage image =
                com.mojang.blaze3d.platform.NativeImage.read(new ByteArrayInputStream(png.toByteArray()));
            id = Identifier.fromNamespaceAndPath(com.relaxedqol.RelaxedQolMod.MOD_ID, "glass_rim/" + key);
            Minecraft.getInstance().getTextureManager().register(id,
                new net.minecraft.client.renderer.texture.DynamicTexture(() -> "Glass rim " + key, image));
            TEXTURES.put(key, id);
            return id;
        } catch (Exception | LinkageError e) {
            broken = true;
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("Could not make the glass edge, glass is drawn without it", e);
            return null;
        }
    }

    /** A small rounded box (2c x 2c GUI pixels) with the lit rim; its four quarters are the four corners. */
    private static BufferedImage paint(float gs, int r, int c, boolean glow) {
        int t = 2 * Math.round(c * gs);
        float rad = r * gs;
        float half = t / 2f;
        float rimW = Math.max(1f, gs * 0.7f);
        float glowFall = gs * (glow ? 1.6f : 0.7f);
        float reach = c * gs * 0.95f;
        float lx = -0.7071f;
        float ly = -0.7071f;
        BufferedImage img = new BufferedImage(t, t, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < t; py++) {
            for (int px = 0; px < t; px++) {
                float x = px + 0.5f - half;
                float y = py + 0.5f - half;
                float qx = Math.abs(x) - (half - rad);
                float qy = Math.abs(y) - (half - rad);
                float ox = Math.max(qx, 0);
                float oy = Math.max(qy, 0);
                float d = (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - rad;
                float outer = clamp(0.5f - d);
                if (outer <= 0) continue;
                // The direction the edge faces here
                float nx;
                float ny;
                if (qx > 0 && qy > 0) {
                    float len = (float) Math.sqrt(qx * qx + qy * qy);
                    nx = Math.signum(x) * qx / len;
                    ny = Math.signum(y) * qy / len;
                } else if (qx > qy) {
                    nx = Math.signum(x);
                    ny = 0;
                } else {
                    nx = 0;
                    ny = Math.signum(y);
                }
                float lit = Math.max(0, nx * lx + ny * ly);
                float back = Math.max(0, -(nx * lx + ny * ly));
                // Hot spots at the top-left and bottom-right corners that fade along the edges
                float dTL = (float) Math.hypot(px + 0.5f, py + 0.5f);
                float dBR = (float) Math.hypot(t - px - 0.5f, t - py - 0.5f);
                float hotTL = sq(clamp(1f - dTL / reach));
                float hotBR = sq(clamp(1f - dBR / reach));
                float light = 0.34f + 0.60f * sq(sq(lit)) + 0.40f * sq(sq(back)) + 0.70f * hotTL + 0.40f * hotBR;
                float depth = -d;
                float rim = Math.min(outer, clamp(rimW - depth + 0.5f)) * Math.min(1f, light);
                float bend = depth > rimW ? (float) Math.exp(-(depth - rimW) / glowFall) : 1f;
                float glowA = outer * bend * (glow ? 0.38f : 0.14f) * (0.35f + 0.9f * sq(lit) + 0.4f * sq(back) + hotTL * 0.8f);
                float a = clamp(Math.max(rim, Math.min(1f, glowA)));
                if (a <= 0.002f) continue;
                img.setRGB(px, py, (Math.round(a * 255) << 24) | 0xFFFFFF);
            }
        }
        return img;
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static float sq(float v) {
        return v * v;
    }
}
