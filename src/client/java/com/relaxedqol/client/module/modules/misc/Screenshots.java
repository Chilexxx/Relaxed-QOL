package com.relaxedqol.client.module.modules.misc;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.gui.Toasts;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Locale;

/**
 * "Screenshots" (Lunar-style): after F2 a chat line with buttons - Copy (the picture itself, ready to paste
 * in Discord), Open, Folder and Delete - and every screenshot is on your clipboard right away. Copying a picture uses
 * the system's own clipboard tool (Windows: PowerShell, macOS: osascript, Linux: xclip / wl-copy) in the background,
 * so the game never hitches.
 * No longer a module - one switch in Settings (gear) > Screenshot Tools (on by default). While it is on, the
 * game's own "Saved screenshot as ..." line is left out, so each screenshot gets one chat line (the client's).
 */
public final class Screenshots {
    private static final String COMMAND = "qolshot";

    private static volatile File last;
    private static Thread watcher;

    private Screenshots() {}

    /** Settings > Screenshot Tools. */
    private static boolean on() {
        return com.relaxedqol.client.module.modules.client.ClickGUI.screenshotTools();
    }

    /** The game's "Saved screenshot as ..." chat line (left out while the client's own line is on). */
    public static boolean isVanillaScreenshotLine(Component message) {
        if (message == null || !on()) return false;
        try {
            if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t) {
                return t.getKey().equals("screenshot.success");
            }
        } catch (RuntimeException | LinkageError ignored) {
            // fall through to the text check
        }
        return message.getString().startsWith("Saved screenshot as ");
    }

    private static File folder() {
        return FabricLoader.getInstance().getGameDir().resolve("screenshots").toFile();
    }

    /** Client command behind the chat buttons: /relaxedshot copy|delete <file name>. */
    public static void registerCommand() {
        startWatcher();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
            ClientCommands.literal(COMMAND)
                .then(ClientCommands.literal("copy").then(ClientCommands.argument("file", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        File f = resolve(StringArgumentType.getString(ctx, "file"));
                        if (f != null) copy(f, true);
                        return 1;
                    })))
                .then(ClientCommands.literal("delete").then(ClientCommands.argument("file", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        File f = resolve(StringArgumentType.getString(ctx, "file"));
                        if (f != null && f.delete()) {
                            if (f.equals(last)) last = null;
                            Toasts.show("Screenshot", "Deleted " + f.getName(), 0xFFF87171, 2500);
                        }
                        return 1;
                    })))));
    }

    /** Only plain file names inside the screenshots folder (never a path somewhere else). */
    private static File resolve(String name) {
        String n = name.trim();
        if (n.isEmpty() || n.contains("/") || n.contains("\\") || n.contains("..")) return null;
        File f = new File(folder(), n);
        return f.isFile() ? f : null;
    }

    /** Watches the screenshots folder in the background (cheap; no polling of the folder). */
    private static synchronized void startWatcher() {
        if (watcher != null) return;
        watcher = new Thread(() -> {
            try {
                Path dir = folder().toPath();
                Files.createDirectories(dir);
                try (WatchService ws = FileSystems.getDefault().newWatchService()) {
                    dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE);
                    while (true) {
                        WatchKey key = ws.take();
                        for (WatchEvent<?> ev : key.pollEvents()) {
                            if (!(ev.context() instanceof Path p)) continue;
                            String name = p.getFileName().toString();
                            if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) continue;
                            File f = dir.resolve(p).toFile();
                            waitUntilWritten(f);
                            net.minecraft.client.Minecraft.getInstance().execute(() -> onNewScreenshot(f));
                        }
                        if (!key.reset()) break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                com.relaxedqol.RelaxedQolMod.LOGGER.warn("Screenshots: cannot watch the screenshots folder", e);
            }
        }, "Relaxed screenshot watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** The game writes the file in the background: wait until its size stops changing (max ~3 s). */
    private static void waitUntilWritten(File f) throws InterruptedException {
        long size = -1;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(100);
            long now = f.length();
            if (now > 0 && now == size) return;
            size = now;
        }
    }

    private static void onNewScreenshot(File f) {
        last = f;
        if (RelaxedQolClient.moduleManager == null || !on()) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        copy(f, false);
        if (mc.player != null) {
            MutableComponent line = Component.literal("§8[§b📷§8] §7" + f.getName() + "  ");
            line.append(button("Copy", 0xFF34D399, new ClickEvent.RunCommand("/" + COMMAND + " copy " + f.getName()), "Copy the picture to your clipboard"));
            line.append(Component.literal(" "));
            line.append(button("Open", 0xFF60A5FA, new ClickEvent.OpenFile(f.getAbsolutePath()), "Open the picture"));
            line.append(Component.literal(" "));
            line.append(button("Folder", 0xFFA78BFA, new ClickEvent.OpenFile(folder().getAbsolutePath()), "Open the screenshots folder"));
            line.append(Component.literal(" "));
            line.append(button("Delete", 0xFFF87171, new ClickEvent.RunCommand("/" + COMMAND + " delete " + f.getName()), "Delete this screenshot"));
            mc.player.sendSystemMessage(line);
        }
    }

    private static Component button(String label, int color, ClickEvent click, String hover) {
        return Component.literal("[" + label + "]").setStyle(Style.EMPTY.withColor(color & 0xFFFFFF).withBold(true)
            .withClickEvent(click).withHoverEvent(new HoverEvent.ShowText(Component.literal(hover).withStyle(ChatFormatting.GRAY))));
    }

    /** Puts the picture itself on the clipboard (background thread). */
    public static void copy(File f, boolean announce) {
        Thread t = new Thread(() -> {
            boolean ok = copyImage(f);
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                if (ok) {
                    if (announce) Toasts.show("Screenshot", "Copied - paste it with Ctrl+V", 0xFF34D399, 2500);
                    else Toasts.show("Screenshot", "Saved and copied to your clipboard", 0xFF34D399, 2500);
                } else {
                    Toasts.show("Screenshot", "Could not copy the picture on this computer", 0xFFF87171, 4000);
                }
            });
        }, "Relaxed screenshot copy");
        t.setDaemon(true);
        t.start();
    }

    private static boolean copyImage(File f) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String path = f.getAbsolutePath();
        try {
            Process p;
            if (os.contains("win")) {
                String ps = "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
                    + "$img = [System.Drawing.Image]::FromFile('" + path.replace("'", "''") + "'); "
                    + "[System.Windows.Forms.Clipboard]::SetImage($img); $img.Dispose()";
                p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-STA", "-WindowStyle", "Hidden", "-Command", ps)
                    .redirectErrorStream(true).start();
            } else if (os.contains("mac")) {
                p = new ProcessBuilder("osascript", "-e",
                    "set the clipboard to (read (POSIX file \"" + path.replace("\"", "\\\"") + "\") as «class PNGf»)")
                    .redirectErrorStream(true).start();
            } else {
                boolean wayland = System.getenv("WAYLAND_DISPLAY") != null;
                p = (wayland ? new ProcessBuilder("wl-copy", "--type", "image/png")
                    : new ProcessBuilder("xclip", "-selection", "clipboard", "-t", "image/png", "-i"))
                    .redirectInput(f).redirectErrorStream(true).start();
            }
            p.getInputStream().readAllBytes();
            return p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
