package com.relaxedqol.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * A soft round glow: one smooth radial fade painted once into a texture (with a little dithering, so a faint glow
 * shows no rings) and drawn in a single quad. Replaces stacks of see-through circles, whose edges showed as lines.
 */
public final class SoftGlow {
    private SoftGlow() {}

    private static final int SIZE = 256;
    private static Identifier id;
    private static boolean broken;

    /** A glow of {@code color} (its alpha = the strength in the middle) fading out to nothing at {@code radius}. */
    public static boolean draw(GuiGraphicsExtractor g, float cx, float cy, float radius, int color) {
        if (radius <= 0) return true;
        Identifier tex = texture();
        if (tex == null) return false;
        float k = 2f * radius / SIZE;
        g.pose().pushMatrix();
        g.pose().translate(cx - radius, cy - radius);
        g.pose().scale(k, k);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0, 0, SIZE, SIZE, SIZE, SIZE, color);
        g.pose().popMatrix();
        return true;
    }

    private static Identifier texture() {
        if (id != null || broken) return id;
        try {
            BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            java.util.Random noise = new java.util.Random(7);
            float half = SIZE / 2f;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    float dx = (x + 0.5f - half) / half;
                    float dy = (y + 0.5f - half) / half;
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    if (d >= 1f) continue;
                    // Smooth bell: full in the middle, gently to nothing at the edge
                    float f = 1f - d;
                    float a = f * f * (3f - 2f * f);
                    int v = Math.round(a * 255f + (noise.nextFloat() - 0.5f) * 10f);
                    v = Math.max(0, Math.min(255, v));
                    if (d > 0.97f) v = Math.min(v, Math.round(a * 255f));
                    img.setRGB(x, y, (v << 24) | 0xFFFFFF);
                }
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", png);
            com.mojang.blaze3d.platform.NativeImage image =
                com.mojang.blaze3d.platform.NativeImage.read(new ByteArrayInputStream(png.toByteArray()));
            Identifier made = Identifier.fromNamespaceAndPath(com.relaxedqol.RelaxedQolMod.MOD_ID, "soft_glow");
            Minecraft.getInstance().getTextureManager().register(made,
                new net.minecraft.client.renderer.texture.DynamicTexture(() -> "Soft glow", image));
            id = made;
        } catch (Exception | LinkageError e) {
            broken = true;
        }
        return id;
    }
}
