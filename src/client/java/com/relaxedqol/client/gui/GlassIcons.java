package com.relaxedqol.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Smooth hearts, drumsticks and armor for the Custom Hotbar - drawn once at the screen's real resolution (anti-aliased,
 * with gradients) into a small texture, then shown 1:1 so they are sharp at any GUI scale. Clean Style gets glass: see-
 * through colour, a bright shine on top, a light rim and a soft glow inside; the normal look gets the same icons solid
 * and glossy. Made again only when the GUI scale or the style changes.
 */
public final class GlassIcons {
    private GlassIcons() {}

    /** The icons, in texture order. */
    public enum Icon {
        HEART_FULL, HEART_HALF, HEART_EMPTY,
        POISON_FULL, POISON_HALF, WITHER_FULL, WITHER_HALF, FROZEN_FULL, FROZEN_HALF,
        FLASH_FULL, FLASH_HALF, GOLD_FULL, GOLD_HALF,
        FOOD_FULL, FOOD_HALF, FOOD_EMPTY, HUNGER_FULL, HUNGER_HALF,
        ARMOR_FULL, ARMOR_HALF, ARMOR_EMPTY
    }

    private static final Map<String, Identifier> TEXTURES = new HashMap<>();
    private static boolean broken;
    private static int cell;
    /** Liquid Glass: clear glass that only takes a tint of the colour (the Custom Hotbar's Icon Look). */
    private static boolean liquid;

    public static void setLiquid(boolean on) {
        liquid = on;
    }

    /** False when the icons could not be made (then the caller draws its own). */
    public static boolean available() {
        return !broken;
    }

    /** Draws an icon in a 9 x 9 GUI-pixel box at (x, y). */
    public static boolean draw(GuiGraphicsExtractor g, Icon icon, int x, int y) {
        if (broken) return false;
        int gs = Math.max(1, (int) Minecraft.getInstance().getWindow().getGuiScale());
        boolean clean = CleanStyle.on();
        Identifier id = texture(gs, clean, liquid);
        if (id == null) return false;
        int c = 9 * gs;
        g.pose().pushMatrix();
        g.pose().scale(1f / gs, 1f / gs);
        g.blit(RenderPipelines.GUI_TEXTURED, id, x * gs, y * gs, icon.ordinal() * c, 0, c, c, c * Icon.values().length, c, 0xFFFFFFFF);
        g.pose().popMatrix();
        return true;
    }

    private static Identifier texture(int gs, boolean clean, boolean liquid) {
        String key = gs + (clean ? "c" : "n") + (liquid ? "l" : "");
        Identifier id = TEXTURES.get(key);
        if (id != null) return id;
        try {
            BufferedImage img = paint(gs, clean, liquid);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", png);
            com.mojang.blaze3d.platform.NativeImage image =
                com.mojang.blaze3d.platform.NativeImage.read(new ByteArrayInputStream(png.toByteArray()));
            id = Identifier.fromNamespaceAndPath(com.relaxedqol.RelaxedQolMod.MOD_ID, "glass_icons/" + key);
            Minecraft.getInstance().getTextureManager().register(id,
                new net.minecraft.client.renderer.texture.DynamicTexture(() -> "Glass icons " + key, image));
            TEXTURES.put(key, id);
            return id;
        } catch (Exception | LinkageError e) {
            broken = true;
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("Hotbar: could not make the glass icons, using the simple ones", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ painting

    private static BufferedImage paint(int gs, boolean clean, boolean liquid) {
        cell = 9 * gs;
        Icon[] all = Icon.values();
        BufferedImage img = new BufferedImage(cell * all.length, cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        for (int i = 0; i < all.length; i++) {
            Graphics2D cg = (Graphics2D) g.create(i * cell, 0, cell, cell);
            if (liquid) paintLiquid(cg, all[i]);
            else paintIcon(cg, all[i], clean);
            cg.dispose();
        }
        g.dispose();
        return img;
    }

    /** The shape and its two colours for an icon: {shape, light, dark}. */
    private static Object[] look(String n) {
        Shape shape;
        int light;
        int dark;
        if (n.startsWith("FOOD") || n.startsWith("HUNGER")) {
            shape = drumstick();
            boolean hunger = n.startsWith("HUNGER");
            light = hunger ? 0xFFD6E58A : 0xFFFFC98A;
            dark = hunger ? 0xFF5E7524 : 0xFFC4561E;
        } else if (n.startsWith("ARMOR")) {
            shape = chestplate();
            light = 0xFFFFFFFF;
            dark = 0xFF8D9BB0;
        } else {
            shape = heart();
            light = switch (n.substring(0, n.indexOf('_'))) {
                case "POISON" -> 0xFFC4EC8A;
                case "WITHER" -> 0xFFA0A0A0;
                case "FROZEN" -> 0xFFE4F6FF;
                case "FLASH" -> 0xFFFFFFFF;
                case "GOLD" -> 0xFFFFEDA0;
                default -> 0xFFFF8A94;
            };
            dark = switch (n.substring(0, n.indexOf('_'))) {
                case "POISON" -> 0xFF467F1E;
                case "WITHER" -> 0xFF262626;
                case "FROZEN" -> 0xFF4FA6DA;
                case "FLASH" -> 0xFFE8E8EE;
                case "GOLD" -> 0xFFD08C0A;
                default -> 0xFFC0142A;
            };
        }
        return new Object[]{shape, light, dark};
    }

    private static void paintIcon(Graphics2D g, Icon icon, boolean clean) {
        String n = icon.name();
        boolean empty = n.endsWith("EMPTY");
        boolean half = n.endsWith("HALF");
        Object[] lk = look(n);
        Shape shape = (Shape) lk[0];
        int light = (int) lk[1];
        int dark = (int) lk[2];
        // Shadow under it, then the empty glass, then the filled part (clipped to its half)
        Shape shadow = AffineTransform.getTranslateInstance(0, cell * 0.06).createTransformedShape(shape);
        g.setColor(new Color(0, 0, 0, clean ? 70 : 90));
        g.fill(shadow);
        paintEmpty(g, shape, clean);
        if (!empty) {
            Graphics2D f = (Graphics2D) g.create();
            if (half) {
                // Hearts and armor fill from the left, drumsticks from the right (like Minecraft)
                boolean right = n.startsWith("FOOD") || n.startsWith("HUNGER");
                f.clip(new Rectangle2D.Double(right ? cell / 2.0 : 0, 0, cell / 2.0, cell));
            }
            paintFilled(f, shape, light, dark, clean, n.startsWith("FOOD") || n.startsWith("HUNGER"));
            f.dispose();
        }
        paintRim(g, shape, clean, empty);
    }

    // ------------------------------------------------------------------ Liquid Glass

    /**
     * Clear glass, like a real glass charm: the body is mostly see-through and only tinted; the colour gathers in a
     * thick inner edge (glass is coloured most where it is thickest), light pools at the bottom, a sharp white
     * highlight runs along the top, the rim catches light on top and darkens below.
     */
    private static void paintLiquid(Graphics2D g, Icon icon) {
        String n = icon.name();
        boolean empty = n.endsWith("EMPTY");
        boolean half = n.endsWith("HALF");
        boolean food = n.startsWith("FOOD") || n.startsWith("HUNGER");
        Object[] lk = look(n);
        Shape shape = (Shape) lk[0];
        int light = (int) lk[1];
        int dark = (int) lk[2];
        // A faint shadow so it stands off the world
        g.setColor(new Color(0, 0, 0, 45));
        g.fill(AffineTransform.getTranslateInstance(0, cell * 0.05).createTransformedShape(shape));
        // Empty glass: nearly clear
        g.setColor(new Color(255, 255, 255, 22));
        g.fill(shape);
        Graphics2D e = (Graphics2D) g.create();
        e.clip(shape);
        e.setStroke(new BasicStroke(cell * 0.16f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        e.setColor(new Color(255, 255, 255, 34));
        e.draw(shape);
        e.dispose();
        if (!empty) {
            Graphics2D f = (Graphics2D) g.create();
            if (half) f.clip(new Rectangle2D.Double(food ? cell / 2.0 : 0, 0, cell / 2.0, cell));
            f.clip(shape);
            // The tint: light at the top, deeper at the bottom, still see-through
            f.setPaint(new GradientPaint(0, 0, color(light, 70), 0, cell, color(dark, 140)));
            f.fill(new Rectangle2D.Double(0, 0, cell, cell));
            // The thick coloured edge inside
            f.setStroke(new BasicStroke(cell * 0.22f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            f.setPaint(new GradientPaint(0, 0, color(light, 110), 0, cell, color(dark, 245)));
            f.draw(shape);
            // A darker band just under the highlight (glass bends the light away there) - gives it depth
            f.setPaint(new GradientPaint(0, cell * 0.30f, color(dark, 0), 0, cell * 0.48f, color(dark, 70)));
            f.fill(new Rectangle2D.Double(0, cell * 0.30, cell, cell * 0.18));
            // Light pooling at the bottom (it comes through the glass and gathers there)
            f.setPaint(new RadialGradientPaint(cell * 0.5f, cell * 0.86f, cell * 0.42f, new float[]{0f, 1f},
                new Color[]{new Color(255, 255, 255, 120), new Color(255, 255, 255, 0)}));
            f.fill(new Rectangle2D.Double(0, 0, cell, cell));
            f.dispose();
        }
        // The highlight: a sharp white curve along the top, clipped to the shape
        Graphics2D h = (Graphics2D) g.create();
        h.clip(shape);
        h.setStroke(new BasicStroke(Math.max(1f, cell * 0.075f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Shape inner = AffineTransform.getTranslateInstance(cell * 0.05, cell * 0.07).createTransformedShape(
            scaled(shape, 0.86));
        h.clip(new Rectangle2D.Double(0, 0, cell, cell * 0.42));
        h.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, empty ? 150 : 235), cell, 0, new Color(255, 255, 255, 40)));
        h.draw(inner);
        h.dispose();
        // A second, softer reflection on the lower right edge (light from behind, bent through the glass)
        Graphics2D b = (Graphics2D) g.create();
        b.clip(new Rectangle2D.Double(cell * 0.55, cell * 0.45, cell * 0.45, cell * 0.55));
        b.setStroke(new BasicStroke(Math.max(1f, cell * 0.06f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        b.setColor(new Color(255, 255, 255, empty ? 50 : 110));
        b.draw(scaled(shape, 0.84));
        b.dispose();
        // A tiny sparkle
        double d = Math.max(1.0, cell * 0.09);
        g.setColor(new Color(255, 255, 255, 240));
        g.fill(new Ellipse2D.Double(cell * (food ? 0.55 : 0.22), cell * (food ? 0.20 : 0.24), d, d));
        // The rim: bright where light hits (top), darker below
        Graphics2D r = (Graphics2D) g.create();
        r.setStroke(new BasicStroke(Math.max(1f, cell / 14f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        r.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 200), 0, cell,
            empty ? new Color(255, 255, 255, 60) : color(dark, 200)));
        r.draw(shape);
        r.dispose();
    }

    /** The shape made smaller around its own middle. */
    private static Shape scaled(Shape s, double f) {
        Rectangle2D b = s.getBounds2D();
        AffineTransform t = new AffineTransform();
        t.translate(b.getCenterX(), b.getCenterY());
        t.scale(f, f);
        t.translate(-b.getCenterX(), -b.getCenterY());
        return t.createTransformedShape(s);
    }

    /** Empty: dark see-through glass with a faint shine. */
    private static void paintEmpty(Graphics2D g, Shape shape, boolean clean) {
        g.setColor(new Color(0, 0, 0, clean ? 80 : 150));
        g.fill(shape);
        Graphics2D s = (Graphics2D) g.create();
        s.clip(shape);
        s.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, clean ? 60 : 35), 0, cell * 0.5f, new Color(255, 255, 255, 0)));
        s.fill(new Rectangle2D.Double(0, 0, cell, cell * 0.5));
        s.dispose();
    }

    /** Filled: the colour from light (top) to deep (bottom), a soft glow at the bottom and a bright shine on top. */
    private static void paintFilled(Graphics2D g, Shape shape, int light, int dark, boolean clean, boolean food) {
        int alpha = clean ? 225 : 255;
        g.setPaint(new GradientPaint(0, 0, color(light, alpha), 0, cell, color(dark, alpha)));
        g.fill(shape);
        Graphics2D s = (Graphics2D) g.create();
        s.clip(shape);
        // Light coming through the glass at the bottom
        float r = cell * 0.55f;
        s.setPaint(new RadialGradientPaint(cell * 0.5f, cell * 0.92f, r, new float[]{0f, 1f},
            new Color[]{color(light, clean ? 120 : 70), color(light, 0)}));
        s.fill(new Rectangle2D.Double(0, 0, cell, cell));
        // The shine: a bright soft band over the top
        Shape shine = new Ellipse2D.Double(cell * 0.10, -cell * 0.18, cell * 0.80, cell * 0.62);
        s.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, clean ? 205 : 160), 0, cell * 0.44f, new Color(255, 255, 255, 0)));
        s.fill(shine);
        // A small sparkle on the upper left
        double d = cell * 0.13;
        s.setColor(new Color(255, 255, 255, 235));
        s.fill(new Ellipse2D.Double(cell * (food ? 0.52 : 0.20), cell * (food ? 0.16 : 0.20), d, d));
        s.dispose();
    }

    /** The edge: a light rim on top fading to a darker one below (glass catches the light on its top edge). */
    private static void paintRim(Graphics2D g, Shape shape, boolean clean, boolean empty) {
        float w = Math.max(1f, cell / 13f);
        Graphics2D s = (Graphics2D) g.create();
        s.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        if (clean) {
            s.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, empty ? 150 : 190), 0, cell,
                new Color(255, 255, 255, empty ? 45 : 60)));
        } else {
            s.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 110), 0, cell, new Color(0, 0, 0, 200)));
        }
        s.draw(shape);
        s.dispose();
    }

    private static Color color(int argb, int alpha) {
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, alpha);
    }

    // ------------------------------------------------------------------ shapes (in the cell, a little inset)

    private static Shape heart() {
        double s = cell;
        double p = s * 0.06;
        double w = s - 2 * p;
        double h = s - 2 * p;
        Path2D path = new Path2D.Double();
        path.moveTo(p + w * 0.50, p + h * 0.93);
        path.curveTo(p + w * 0.18, p + h * 0.70, p - w * 0.02, p + h * 0.48, p + w * 0.03, p + h * 0.27);
        path.curveTo(p + w * 0.08, p + h * 0.05, p + w * 0.40, p - h * 0.02, p + w * 0.50, p + h * 0.22);
        path.curveTo(p + w * 0.60, p - h * 0.02, p + w * 0.92, p + h * 0.05, p + w * 0.97, p + h * 0.27);
        path.curveTo(p + w * 1.02, p + h * 0.48, p + w * 0.82, p + h * 0.70, p + w * 0.50, p + h * 0.93);
        path.closePath();
        return path;
    }

    private static Shape drumstick() {
        double s = cell;
        Area a = new Area(new Ellipse2D.Double(s * 0.30, s * 0.06, s * 0.64, s * 0.56));
        a.transform(AffineTransform.getRotateInstance(Math.toRadians(-35), s * 0.62, s * 0.34));
        // The bone: a short rounded stick with a knob at its end
        Shape stick = new RoundRectangle2D.Double(s * 0.12, s * 0.52, s * 0.38, s * 0.15, s * 0.15, s * 0.15);
        Area bone = new Area(stick);
        bone.transform(AffineTransform.getRotateInstance(Math.toRadians(-45), s * 0.38, s * 0.6));
        a.add(bone);
        a.add(new Area(new Ellipse2D.Double(s * 0.06, s * 0.70, s * 0.17, s * 0.17)));
        a.add(new Area(new Ellipse2D.Double(s * 0.16, s * 0.79, s * 0.17, s * 0.15)));
        return a;
    }

    private static Shape chestplate() {
        double s = cell;
        Path2D p = new Path2D.Double();
        p.moveTo(s * 0.08, s * 0.20);
        p.quadTo(s * 0.20, s * 0.08, s * 0.34, s * 0.07);
        p.quadTo(s * 0.50, s * 0.24, s * 0.66, s * 0.07);
        p.quadTo(s * 0.80, s * 0.08, s * 0.92, s * 0.20);
        p.lineTo(s * 0.86, s * 0.46);
        p.lineTo(s * 0.76, s * 0.46);
        p.lineTo(s * 0.76, s * 0.86);
        p.quadTo(s * 0.76, s * 0.93, s * 0.68, s * 0.93);
        p.lineTo(s * 0.32, s * 0.93);
        p.quadTo(s * 0.24, s * 0.93, s * 0.24, s * 0.86);
        p.lineTo(s * 0.24, s * 0.46);
        p.lineTo(s * 0.14, s * 0.46);
        p.closePath();
        return p;
    }
}
