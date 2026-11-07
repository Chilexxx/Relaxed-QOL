package com.relaxedqol.client.util;

import com.relaxedqol.RelaxedQolMod;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Takes a screenshot exactly like F2 does (the game's own screenshot code), into .minecraft/screenshots, under a
 * name we choose. Main thread only.
 *
 * In 26.2 the main render target is no longer on Minecraft (getMainRenderTarget is gone - it moved to the
 * GameRenderer), so nothing is looked up by name any more: the render target type is read from the screenshot
 * method itself, and the object is found by TYPE on Minecraft, then on the GameRenderer (methods and fields).
 * If no "grab" that saves a file exists, the image is taken with "takeScreenshot" and written by us.
 */
public final class ScreenshotUtil {
    private ScreenshotUtil() {}

    private static final String[] SCREENSHOT_CLASSES = {"net.minecraft.client.Screenshot",
        "net.minecraft.client.screenshot.Screenshot", "net.minecraft.client.renderer.Screenshot"};

    /** The .minecraft folder (from Fabric, so it never depends on a renamed game field). */
    private static File gameDir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile();
    }

    /**
     * name: file name relative to the screenshots folder, e.g. "Trades/2026-10-16_judakes.png" (folders are made).
     * done: called with a short message ("saved" / "failed: ..."). Returns false when no way to take one was found.
     */
    public static boolean capture(String name, Consumer<String> done) {
        Minecraft mc = Minecraft.getInstance();
        try {
            File target = fileOf(name);
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            Class<?> screenshot = screenshotClass();
            if (screenshot == null) {
                done.accept("failed: the game's screenshot class was not found");
                return false;
            }

            // 1) The game's own "grab" that writes the file: (File, String, RenderTarget, [int], Consumer)
            for (Method m : screenshot.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || !m.getName().equals("grab")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 4 || p[0] != File.class || p[1] != String.class || p[p.length - 1] != Consumer.class) continue;
                Object[] args = new Object[p.length];
                boolean ok = true;
                for (int i = 0; i < p.length && ok; i++) {
                    if (i == 0) args[i] = gameDir();
                    else if (i == 1) args[i] = name;
                    else if (i == p.length - 1) args[i] = (Consumer<Object>) component -> done.accept("saved");
                    else if (p[i] == int.class) args[i] = 1;
                    else if (p[i] == boolean.class) args[i] = false;
                    else {
                        Object rt = renderTarget(mc, p[i]);
                        if (rt == null) ok = false;
                        args[i] = rt;
                    }
                }
                if (!ok) continue;
                m.invoke(null, args);
                return true;
            }

            // 2) "takeScreenshot(RenderTarget, [int], Consumer<NativeImage>)": we write the image ourselves
            for (Method m : screenshot.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || !m.getName().toLowerCase().contains("screenshot")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 2 || p[p.length - 1] != Consumer.class) continue;
                Object[] args = new Object[p.length];
                boolean ok = true;
                for (int i = 0; i < p.length - 1 && ok; i++) {
                    if (p[i] == int.class) args[i] = 1;
                    else {
                        Object rt = renderTarget(mc, p[i]);
                        if (rt == null) ok = false;
                        args[i] = rt;
                    }
                }
                if (!ok) continue;
                args[p.length - 1] = (Consumer<Object>) image -> writeImage(image, target, done);
                m.invoke(null, args);
                return true;
            }
            done.accept("failed: no screenshot method found");
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
            RelaxedQolMod.LOGGER.warn("Screenshot failed", cause);
            done.accept("failed: " + cause);
            return false;
        }
    }

    private static Class<?> screenshotClass() {
        for (String name : SCREENSHOT_CLASSES) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }

    /**
     * The main render target (an object of the wanted type): a no-argument method or a field of that type on
     * Minecraft, then on the GameRenderer (and any other object Minecraft holds that has one).
     */
    private static Object renderTarget(Minecraft mc, Class<?> type) {
        Object found = byType(mc, type);
        if (found != null) return found;
        // Where it lives in 26.2: the GameRenderer (try it first, then everything else Minecraft holds)
        List<Object> owners = new ArrayList<>();
        for (Object holder : fieldsAndGetters(mc)) {
            if (holder != null && holder.getClass().getSimpleName().contains("GameRenderer")) owners.add(0, holder);
            else if (holder != null && holder.getClass().getName().startsWith("net.minecraft.client")) owners.add(holder);
        }
        for (Object owner : owners) {
            found = byType(owner, type);
            if (found != null) return found;
        }
        return null;
    }

    /** A no-argument method returning the type (names with "main" first), else a field of the type. */
    private static Object byType(Object owner, Class<?> type) {
        Method best = null;
        for (Method m : owner.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || Modifier.isStatic(m.getModifiers()) || !type.isAssignableFrom(m.getReturnType())) continue;
            if (best == null || m.getName().toLowerCase().contains("main")) best = m;
        }
        if (best != null) {
            try {
                Object v = best.invoke(owner);
                if (v != null) return v;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        Object fallback = null;
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !type.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(owner);
                    if (v == null) continue;
                    if (f.getName().toLowerCase().contains("main")) return v;
                    if (fallback == null) fallback = v;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return fallback;
    }

    /** Every object Minecraft holds in its own fields (to look for the render target in). */
    private static List<Object> fieldsAndGetters(Minecraft mc) {
        List<Object> out = new ArrayList<>();
        for (Field f : Minecraft.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(mc);
                if (v != null && !out.contains(v)) out.add(v);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return out;
    }

    /** Writes a NativeImage-like object (writeToFile(File|Path)) to the file, then closes it. */
    private static void writeImage(Object image, File target, Consumer<String> done) {
        Thread writer = new Thread(() -> {
            try {
                Method write = null;
                for (Method m : image.getClass().getMethods()) {
                    if (m.getName().equals("writeToFile") && m.getParameterCount() == 1
                        && (m.getParameterTypes()[0] == File.class || m.getParameterTypes()[0] == Path.class)) write = m;
                }
                if (write == null) {
                    done.accept("failed: cannot write the image");
                    return;
                }
                write.invoke(image, write.getParameterTypes()[0] == File.class ? target : target.toPath());
                done.accept("saved");
            } catch (ReflectiveOperationException | RuntimeException e) {
                RelaxedQolMod.LOGGER.warn("Writing screenshot failed", e);
                done.accept("failed: " + e);
            } finally {
                if (image instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "Relaxed screenshot writer");
        writer.setDaemon(true);
        writer.start();
    }

    /**
     * A screenshot saved to ANY file (e.g. a folder you picked for TradeShot): taken into a temporary file in
     * .minecraft/screenshots first (the game only saves in there), then moved to the target once it is written.
     * done gets "saved" or "failed: ...".
     */
    public static boolean captureTo(File target, Consumer<String> done) {
        String tmpName = ".relaxed-tmp/" + java.util.UUID.randomUUID() + ".png";
        File tmp = fileOf(tmpName);
        return capture(tmpName, msg -> {
            if (!msg.equals("saved")) {
                done.accept(msg);
                return;
            }
            try {
                File parent = target.getAbsoluteFile().getParentFile();
                if (parent != null) parent.mkdirs();
                java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                done.accept("saved");
            } catch (java.io.IOException | RuntimeException e) {
                RelaxedQolMod.LOGGER.warn("Moving screenshot to {} failed", target, e);
                tmp.delete();
                done.accept("failed: could not save to " + target.getParent() + " (" + e.getMessage() + ")");
            }
        });
    }

    /** The file a named screenshot is written to. */
    public static File fileOf(String name) {
        return new File(new File(gameDir(), "screenshots"), name);
    }
}
