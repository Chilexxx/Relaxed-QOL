package com.relaxedqol.client.util;

import com.relaxedqol.RelaxedQolMod;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * A separate sign-in window, like Lunar's - so adding an account never touches your own browser.
 *
 * A Chromium browser that is already installed (Chrome, Brave, Edge, Vivaldi; Firefox as a last resort) is started
 * as a small app window (no tabs, no address bar) with its OWN brand-new, empty profile in a temp folder. Nothing
 * from your browser is in it (no cookies, no Microsoft sign-in), so Microsoft lets you sign in with any account, and
 * nothing you sign in to there ends up in your browser. When the sign-in is done (or cancelled) the window is
 * closed and the temp profile deleted.
 *
 * Blocking calls - use off the render thread.
 */
public final class SignInWindow {
    private static Process process;
    private static Path profile;

    private SignInWindow() {}

    /** Opens the page in a separate sign-in window. False: no suitable browser found (use the normal one). */
    public static synchronized boolean open(String url) {
        close();
        try {
            profile = Files.createTempDirectory("relaxedqol-signin");
        } catch (IOException e) {
            RelaxedQolMod.LOGGER.warn("[Accounts] no temp folder for the sign-in window: {}", e.toString());
            return false;
        }
        for (String[] browser : browsers()) {
            String exe = browser[0];
            boolean firefox = browser[1].equals("firefox");
            List<String> cmd = new ArrayList<>();
            cmd.add(exe);
            if (firefox) {
                cmd.addAll(List.of("-profile", profile.toString(), "-no-remote", "-new-instance", url));
            } else {
                cmd.addAll(List.of(
                    "--user-data-dir=" + profile,
                    "--app=" + url,
                    "--window-size=500,700",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--disable-sync"));
            }
            try {
                process = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                RelaxedQolMod.LOGGER.info("[Accounts] sign-in window opened with {}", new File(exe).getName());
                return true;
            } catch (IOException e) {
                RelaxedQolMod.LOGGER.warn("[Accounts] could not start {}: {}", exe, e.toString());
            }
        }
        deleteProfileLater(profile);
        profile = null;
        return false;
    }

    /** Closes the sign-in window and deletes its temp profile. */
    public static synchronized void close() {
        Process p = process;
        Path dir = profile;
        process = null;
        profile = null;
        if (p != null) {
            try {
                p.descendants().forEach(ProcessHandle::destroy);
                p.destroy();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        if (dir != null) deleteProfileLater(dir);
    }

    public static synchronized boolean isOpen() {
        return process != null && process.isAlive();
    }

    // ------------------------------------------------------------------ finding a browser

    /** {exe, kind}: installed browsers, best first. */
    private static List<String[]> browsers() {
        List<String[]> out = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            for (String exe : new String[]{"chrome.exe", "brave.exe", "msedge.exe", "vivaldi.exe"}) {
                String path = windowsAppPath(exe);
                if (path != null) out.add(new String[]{path, "chromium"});
            }
            String ff = windowsAppPath("firefox.exe");
            if (ff != null) out.add(new String[]{ff, "firefox"});
        } else if (os.contains("mac")) {
            String[][] apps = {
                {"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "chromium"},
                {"/Applications/Brave Browser.app/Contents/MacOS/Brave Browser", "chromium"},
                {"/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge", "chromium"},
                {"/Applications/Vivaldi.app/Contents/MacOS/Vivaldi", "chromium"},
                {"/Applications/Firefox.app/Contents/MacOS/firefox", "firefox"}};
            for (String[] a : apps) if (new File(a[0]).canExecute()) out.add(a);
        } else {
            for (String exe : new String[]{"google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "brave-browser", "microsoft-edge", "vivaldi"}) {
                String path = onPath(exe);
                if (path != null) out.add(new String[]{path, "chromium"});
            }
            String ff = onPath("firefox");
            if (ff != null) out.add(new String[]{ff, "firefox"});
        }
        return out;
    }

    /**
     * Where a browser is installed on Windows, or null. the standard install folders are checked directly
     * (no "reg" process): Program Files, Program Files (x86) and your own AppData\\Local (per-user installs).
     */
    private static String windowsAppPath(String exe) {
        String rel = switch (exe) {
            case "chrome.exe" -> "Google\\Chrome\\Application\\chrome.exe";
            case "brave.exe" -> "BraveSoftware\\Brave-Browser\\Application\\brave.exe";
            case "msedge.exe" -> "Microsoft\\Edge\\Application\\msedge.exe";
            case "vivaldi.exe" -> "Vivaldi\\Application\\vivaldi.exe";
            case "firefox.exe" -> "Mozilla Firefox\\firefox.exe";
            default -> null;
        };
        if (rel == null) return null;
        for (String base : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")}) {
            if (base == null) continue;
            File f = new File(base, rel);
            if (f.isFile()) return f.getPath();
        }
        return null;
    }

    private static String onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, exe);
            if (f.canExecute()) return f.getPath();
        }
        return null;
    }

    // ------------------------------------------------------------------ cleanup

    /** Deletes the temp profile once the browser has let go of its files (a few seconds). */
    private static void deleteProfileLater(Path dir) {
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (deleteTree(dir)) return;
            }
            dir.toFile().deleteOnExit();
        }, "RelaxedQOL-SignInCleanup");
        t.setDaemon(true);
        t.start();
    }

    private static boolean deleteTree(Path dir) {
        if (!Files.exists(dir)) return true;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return !Files.exists(dir);
    }
}
