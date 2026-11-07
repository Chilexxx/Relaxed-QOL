package com.relaxedqol.client.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.relaxedqol.RelaxedQolMod;
import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.gui.Toasts;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Account switcher + session refresh.
 *
 * <ul>
 *   <li>Add Microsoft accounts in the game (microsoft.com/link + a short code), switch between them on the title
 *   screen / server list - no restart, no second launcher instance.</li>
 *   <li>The Minecraft session (about 24 hours) is renewed in the background before it runs out, for every account
 *   added here - so "Invalid session" after the game has been open a day is gone. The account the launcher started
 *   the game with is refreshed too once you have added it here once (the launcher never gives the game its refresh
 *   token).</li>
 * </ul>
 *
 * Saved in {@code config/relaxed-qol/accounts.json}. That file holds your Microsoft refresh tokens: anyone who has
 * it can play as you - never share it (or the config folder).
 */
public final class Accounts {
    /** Renew this long before the session runs out. */
    private static final long REFRESH_BEFORE_MS = 60 * 60 * 1000L;
    /** A saved token with less than this left is renewed before switching to it. */
    private static final long MIN_LEFT_TO_USE_MS = 10 * 60 * 1000L;
    private static final long RETRY_MS = 3 * 60 * 1000L;

    public static final class Account {
        public String name;
        public String uuid;
        public String refreshToken;
        public String accessToken;
        public long expiresAt;
        public boolean needsLogin;
        public long added;

        public UUID id() {
            try {
                return UUID.fromString(uuid);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RelaxedQOL-Accounts");
        t.setDaemon(true);
        return t;
    });

    private static final List<Account> ACCOUNTS = new ArrayList<>();
    private static boolean loaded;

    // ---------------------------------------------------------------- live state (read by the window every frame)
    private static volatile boolean busy;
    private static volatile String status = "";
    private static volatile String error = "";
    private static volatile MsAuth.DeviceCode deviceCode;
    private static final java.util.concurrent.atomic.AtomicInteger loginGen = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile boolean loginActive;
    private static volatile String loginStatus = "";
    private static volatile long codeStarted;
    private static volatile boolean separateWindow;
    /** The full set of services for a refresh that happened in a world (put in once you are out). */
    private static volatile SessionSwap.Prepared pendingFull;

    /** The session this client put in (whose expiry it knows) - the game's token itself is never read. */
    private static volatile UUID installedUuid;
    private static volatile long installedExpiresAt;
    private static long lastCheck;
    private static long lastAutoAttempt;
    private static boolean unknownExpiryChecked;

    private Accounts() {}

    // ---------------------------------------------------------------- storage

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("relaxed-qol").resolve("accounts.json");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            List<Account> list = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), new TypeToken<List<Account>>() {}.getType());
            if (list != null) for (Account a : list) if (a != null && a.uuid != null && a.name != null) ACCOUNTS.add(a);
        } catch (Exception e) {
            RelaxedQolMod.LOGGER.warn("[Accounts] could not read accounts.json: {}", e.toString());
        }
    }

    private static synchronized void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("accounts.json.tmp");
            Files.writeString(tmp, GSON.toJson(ACCOUNTS), StandardCharsets.UTF_8);
            ownerOnly(tmp);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.writeString(f, GSON.toJson(ACCOUNTS), StandardCharsets.UTF_8);
            } catch (Exception e2) {
                RelaxedQolMod.LOGGER.warn("[Accounts] could not save accounts.json: {}", e2.toString());
            }
        }
    }

    /**
     * Readable / writable by you only (plain File calls - it only takes rights away from other users, it
     * never makes anything runnable). Windows keeps its user-folder rights.
     */
    private static void ownerOnly(Path p) {
        java.io.File f = p.toFile();
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
    }

    public static synchronized List<Account> all() {
        ensureLoaded();
        return new ArrayList<>(ACCOUNTS);
    }

    private static synchronized Account find(UUID id) {
        ensureLoaded();
        if (id == null) return null;
        for (Account a : ACCOUNTS) if (id.equals(a.id())) return a;
        return null;
    }

    public static synchronized Account findByName(String name) {
        ensureLoaded();
        for (Account a : ACCOUNTS) if (a.name.equalsIgnoreCase(name)) return a;
        return null;
    }

    private static synchronized Account remember(MsAuth.Session s) {
        ensureLoaded();
        Account a = find(s.uuid());
        if (a == null) {
            a = new Account();
            a.added = System.currentTimeMillis();
            ACCOUNTS.add(a);
        }
        a.name = s.name();
        a.uuid = s.uuid().toString();
        a.refreshToken = s.refreshToken();
        a.accessToken = s.accessToken();
        a.expiresAt = s.expiresAt();
        a.needsLogin = false;
        save();
        return a;
    }

    public static synchronized void remove(Account a) {
        ensureLoaded();
        ACCOUNTS.removeIf(x -> x.uuid.equals(a.uuid));
        save();
    }

    // ---------------------------------------------------------------- what the window shows

    public static boolean busy() {
        return busy;
    }

    public static String status() {
        return status;
    }

    public static String error() {
        return error;
    }

    public static MsAuth.DeviceCode deviceCode() {
        return deviceCode;
    }

    public static UUID currentId() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getUser() == null ? null : mc.getUser().getProfileId();
    }

    public static String currentName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getUser() == null ? "?" : mc.getUser().getName();
    }

    /** True when the account playing now is saved here (so its session is renewed by itself). */
    public static boolean currentManaged() {
        Account a = find(currentId());
        return a != null && a.refreshToken != null && !a.needsLogin;
    }

    /**
     * When the session in use runs out (epoch ms), 0 = unknown. from the client's own record of the session it
     * put in - it never reads the game's token. A session the launcher started with is "unknown" until renewed once.
     */
    public static long currentExpiry() {
        UUID id = currentId();
        if (id == null || !id.equals(installedUuid)) return 0;
        return installedExpiresAt;
    }

    public static boolean inWorld() {
        return Minecraft.getInstance().level != null;
    }

    // ---------------------------------------------------------------- actions

    /**
     * Adds a Microsoft account with a code: microsoft.com/link opens in your browser, the code is on your clipboard,
     * you type it there and sign in - the window finishes by itself. (always the code; no addresses to copy.)
     * Always starts fresh - a sign-in still waiting is dropped, so Add never gets stuck.
     */
    public static void startLogin() {
        if (busy) return;
        int gen = loginGen.incrementAndGet();
        loginActive = true;
        error = "";
        loginStatus = "Asking Microsoft for a code";
        deviceCode = null;
        Thread t = new Thread(() -> runCodeLogin(gen), "RelaxedQOL-Login");
        t.setDaemon(true);
        t.start();
    }

    private static boolean live(int gen) {
        return loginGen.get() == gen;
    }

    private static void runCodeLogin(int gen) {
        try {
            MsAuth.DeviceCode code = MsAuth.requestDeviceCode();
            if (!live(gen)) return;
            deviceCode = code;
            codeStarted = System.currentTimeMillis();
            loginStatus = "Waiting for you to sign in";
            Minecraft.getInstance().execute(() -> {
                if (live(gen)) Minecraft.getInstance().keyboardHandler.setClipboard(code.userCode());
            });
            openPage(code.verificationUri(), () -> live(gen));
            MsAuth.Session s = MsAuth.completeDeviceLogin(code, () -> !live(gen), msg -> {
                if (live(gen)) loginStatus = msg;
            });
            finishLogin(gen, s);
        } catch (MsAuth.AuthException e) {
            endLogin(gen, e.getMessage().equals("Cancelled") ? "" : e.getMessage());
        } catch (Throwable t) {
            RelaxedQolMod.LOGGER.warn("[Accounts] sign-in failed", t);
            endLogin(gen, "Sign-in failed: " + t);
        }
    }

    private static void finishLogin(int gen, MsAuth.Session s) {
        if (!live(gen)) return;
        remember(s);
        endLogin(gen, "");
        if (inWorld() || busy) {
            toast("Account added", s.name() + " - switch to it on the title screen / server list", Theme.SUCCESS);
            return;
        }
        busy = true;
        status = "Switching to " + s.name() + "…";
        SessionSwap.Prepared p = SessionSwap.prepare(s.name(), s.uuid(), s.accessToken());
        installOnMain(p, true, "Signed in as " + s.name());
    }

    private static void endLogin(int gen, String message) {
        if (!live(gen)) return;
        closeWindow();
        loginActive = false;
        deviceCode = null;
        loginStatus = "";
        if (message != null && !message.isEmpty()) error = message;
    }

    /** Stops the sign-in right away (the window goes back to the list at once). */
    public static void cancelLogin() {
        loginGen.incrementAndGet();
        closeWindow();
        loginActive = false;
        deviceCode = null;
        loginStatus = "";
    }

    public static boolean loginActive() {
        return loginActive;
    }

    public static String loginStatus() {
        return loginStatus;
    }

    /** 0..1: how much of the code's time is left. */
    public static float codeTimeLeft() {
        MsAuth.DeviceCode c = deviceCode;
        if (c == null) return 0f;
        long total = Math.max(1, c.expiresAt() - codeStarted);
        return Math.max(0f, Math.min(1f, (c.expiresAt() - System.currentTimeMillis()) / (float) total));
    }

    public static void copyCode() {
        MsAuth.DeviceCode c = deviceCode;
        if (c != null) Minecraft.getInstance().keyboardHandler.setClipboard(c.userCode());
    }

    /** Opens the sign-in window again (if you closed it), code copied again. */
    public static void reopenWindow() {
        MsAuth.DeviceCode c = deviceCode;
        copyCode();
        String url = c != null ? c.verificationUri() : "https://www.microsoft.com/link";
        Thread t = new Thread(() -> openPage(url, () -> loginActive), "RelaxedQOL-SignIn");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Opens the page in a separate sign-in window with its own empty profile (like Lunar), so any account
     * can sign in and your own browser is never touched. Falls back to your browser when no Chromium / Firefox is
     * found. Off the render thread.
     */
    private static void openPage(String url, java.util.function.BooleanSupplier stillWanted) {
        if (!stillWanted.getAsBoolean()) return;
        separateWindow = SignInWindow.open(url);
        if (!separateWindow && stillWanted.getAsBoolean()) Minecraft.getInstance().execute(() -> openBrowser(url));
    }

    /** True when the sign-in runs in its own window (your browser untouched). */
    public static boolean separateWindow() {
        return separateWindow;
    }

    private static void closeWindow() {
        Thread t = new Thread(SignInWindow::close, "RelaxedQOL-SignInClose");
        t.setDaemon(true);
        t.start();
    }

    /** Switches the game to this account (only outside a world). Renews its session first when needed. */
    public static void switchTo(Account a) {
        if (busy || a == null) return;
        if (inWorld()) {
            error = "Leave the world first (Disconnect), then switch";
            return;
        }
        UUID id = a.id();
        if (id == null) return;
        if (id.equals(currentId())) {
            error = "";
            status = "";
            return;
        }
        busy = true;
        error = "";
        status = "Switching to " + a.name + "…";
        WORKER.execute(() -> {
            try {
                String token = a.accessToken;
                String name = a.name;
                if (token == null || a.expiresAt - System.currentTimeMillis() < MIN_LEFT_TO_USE_MS || Boolean.FALSE.equals(MsAuth.tokenWorks(token))) {
                    status = "Renewing " + a.name + "'s session…";
                    MsAuth.Session s = MsAuth.refresh(a.refreshToken, msg -> status = msg);
                    remember(s);
                    token = s.accessToken();
                    name = s.name();
                }
                SessionSwap.Prepared p = SessionSwap.prepare(name, id, token);
                installOnMain(p, true, "Now playing as " + name);
            } catch (MsAuth.AuthException e) {
                if (e.needsLogin) markNeedsLogin(a);
                failed(e.getMessage());
            } catch (Throwable t) {
                RelaxedQolMod.LOGGER.warn("[Accounts] switch failed", t);
                failed("Switch failed: " + t);
            }
        });
    }

    /** Gets a fresh session for the account playing now (button / .account refresh, and on its own before it runs out). */
    public static void refreshCurrent(boolean manual) {
        if (busy) return;
        Account a = find(currentId());
        if (a == null || a.refreshToken == null) {
            if (manual) error = "Add " + currentName() + " once with \"+ Add account\" - then its session is renewed by itself";
            return;
        }
        if (a.needsLogin) {
            if (manual) error = a.name + " has to be signed in again (+ Add account)";
            return;
        }
        busy = true;
        if (manual) error = "";
        status = "Renewing your session…";
        WORKER.execute(() -> {
            try {
                MsAuth.Session s = MsAuth.refresh(a.refreshToken, msg -> status = msg);
                if (!s.uuid().toString().equals(a.uuid)) throw new MsAuth.AuthException("Microsoft answered for another account");
                remember(s);
                boolean world = inWorld();
                SessionSwap.Prepared p = SessionSwap.prepare(s.name(), s.uuid(), s.accessToken());
                installOnMain(p, !world, manual ? "Session renewed" : null);
                if (world) pendingFull = p;
            } catch (MsAuth.AuthException e) {
                if (e.needsLogin) {
                    markNeedsLogin(a);
                    toast("Session", a.name + " has to be signed in again (Accounts)", Theme.DANGER);
                }
                failed(manual || e.needsLogin ? e.getMessage() : "");
                if (!manual) RelaxedQolMod.LOGGER.warn("[Accounts] automatic session refresh failed: {}", e.getMessage());
            } catch (Throwable t) {
                RelaxedQolMod.LOGGER.warn("[Accounts] refresh failed", t);
                failed(manual ? "Refresh failed: " + t : "");
            }
        });
    }

    /** Every client tick: renews the session in use before it runs out; finishes a refresh made in a world. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 5000) return;
        lastCheck = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getUser() == null) return;
        SessionSwap.Prepared full = pendingFull;
        if (full != null && mc.level == null && !busy) {
            pendingFull = null;
            if (full.uuid.equals(currentId())) {
                try {
                    SessionSwap.install(full, true);
                } catch (Throwable t) {
                    RelaxedQolMod.LOGGER.warn("[Accounts] could not finish the session refresh", t);
                }
            }
        }
        if (busy || now - lastAutoAttempt < RETRY_MS) return;
        Account a = find(currentId());
        if (a == null || a.refreshToken == null || a.needsLogin) return;
        long exp = currentExpiry();
        if (exp > 0) {
            if (exp - now > REFRESH_BEFORE_MS) return;
        } else {
            // A launcher token we cannot read: renew it once so its expiry is known from then on
            if (unknownExpiryChecked) return;
            unknownExpiryChecked = true;
        }
        lastAutoAttempt = now;
        refreshCurrent(false);
    }

    private static long lastDisconnectCheck;

    /**
     * The disconnected screen opened: if Minecraft no longer takes the session (the "Invalid session" kick), it is
     * renewed right away so you can just join again.
     */
    public static void onDisconnected() {
        long now = System.currentTimeMillis();
        if (busy || now - lastDisconnectCheck < 30_000 || !currentManaged()) return;
        lastDisconnectCheck = now;
        // Only a session the client put in itself (its own saved copy); the game's token is never read
        Account a = find(currentId());
        if (a == null || !a.uuid.equals(String.valueOf(installedUuid)) || a.accessToken == null) return;
        String token = a.accessToken;
        WORKER.execute(() -> {
            if (!Boolean.FALSE.equals(MsAuth.tokenWorks(token))) return;
            Minecraft.getInstance().execute(() -> {
                lastAutoAttempt = System.currentTimeMillis();
                refreshCurrent(false);
                toast("Session", "Your session had run out - renewed, join again", Theme.WARNING);
            });
        });
    }

    // ---------------------------------------------------------------- helpers

    private static void installOnMain(SessionSwap.Prepared p, boolean full, String okMessage) {
        Minecraft.getInstance().execute(() -> {
            try {
                SessionSwap.install(p, full);
                Account saved = find(p.uuid);
                installedUuid = p.uuid;
                installedExpiresAt = saved != null ? saved.expiresAt : 0;
                status = "";
                error = "";
                if (okMessage != null) toast("Accounts", okMessage, Theme.SUCCESS);
                RelaxedQolMod.LOGGER.info("[Accounts] session in use: {}", p.name);
            } catch (Throwable t) {
                RelaxedQolMod.LOGGER.warn("[Accounts] could not put the session in", t);
                status = "";
                error = "Could not switch: " + t;
            } finally {
                busy = false;
            }
        });
    }

    private static void failed(String message) {
        status = "";
        error = message == null ? "" : message;
        busy = false;
    }

    private static synchronized void markNeedsLogin(Account a) {
        a.needsLogin = true;
        save();
    }

    private static void toast(String title, String message, int color) {
        Minecraft.getInstance().execute(() -> Toasts.show(title, message, color, 4000));
    }

    private static void openBrowser(String url) {
        try {
            net.minecraft.util.Util.getPlatform().openUri(url);
        } catch (RuntimeException e) {
            RelaxedQolMod.LOGGER.warn("[Accounts] could not open the browser: {}", e.toString());
        }
    }

    /** "18h 20m" / "35m" / "expired". */
    public static String timeLeft(long expiresAt) {
        if (expiresAt <= 0) return "unknown";
        long ms = expiresAt - System.currentTimeMillis();
        if (ms <= 0) return "expired";
        long min = ms / 60000;
        if (min < 60) return min + "m";
        return (min / 60) + "h " + (min % 60) + "m";
    }

}
