package com.relaxedqol.client.module.modules.lovercraft;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the server's resource packs loaded when you leave a server whose address contains "lover", so leaving does
 * not reload everything back to the normal look - and when you join again and the server sends the very same packs
 * (same id and same file hash), the reload is skipped altogether: the server is told they are loaded, because they
 * still are.
 *
 * Safe by design: a pack is only skipped when its exact hash matches one already loaded from that server AND it can
 * be seen in the game's list of active packs; anything new or changed is loaded the normal way. Joining another
 * server or a singleplayer world first removes the kept packs, like vanilla does on leaving. The hooks are in
 * DownloadedPackSourceMixin / ResourcePackPushMixin (optional mixins: on a game version where they do not fit,
 * the game just behaves as normal).
 */
public class KeepServerPacks extends Module {
    private static com.relaxedqol.client.setting.Setting<Boolean> afterRestart;
    /** The kept packs in the order the server sent them (id -> file hash), saved for Keep After Restart. */
    private static final java.util.LinkedHashMap<UUID, String> order = new java.util.LinkedHashMap<>();
    private static boolean restoreTried;

    private static java.nio.file.Path listFile() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("relaxed-qol").resolve("kept-packs.txt");
    }

    /** Remembers which packs (and in which order) for the next start. */
    private static void saveList() {
        try {
            if (afterRestart == null || !afterRestart.getValue() || order.isEmpty()) {
                java.nio.file.Files.deleteIfExists(listFile());
                return;
            }
            StringBuilder b = new StringBuilder();
            for (var e : order.entrySet()) b.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
            java.nio.file.Files.createDirectories(listFile().getParent());
            java.nio.file.Files.writeString(listFile(), b.toString());
        } catch (Exception ignored) {
            // next start just loads them on join
        }
    }

    /**
     * Keep After Restart: once the game has started (title screen, nothing loading), the packs from last time are
     * handed to the game again from Minecraft's own downloads folder - in the same order as the server sent them.
     * Joining then finds them loaded and skips them.
     */
    private static void restoreOnStart() {
        if (restoreTried) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.overlay() != null || mc.gui.screen() == null || mc.level != null) return; // still starting up / in a world
        restoreTried = true;
        if (afterRestart == null || !afterRestart.getValue() || !on()) return;
        try {
            java.nio.file.Path file = listFile();
            if (!java.nio.file.Files.isRegularFile(file)) return;
            java.nio.file.Path downloads = mc.gameDirectory.toPath().resolve("downloads");
            var source = mc.getDownloadedPackSource();
            int n = 0;
            for (String line : java.nio.file.Files.readAllLines(file)) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 2) continue;
                UUID id;
                try {
                    id = UUID.fromString(parts[0]);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String hash = parts[1].toLowerCase(Locale.ROOT);
                java.nio.file.Path pack = downloads.resolve(id.toString()).resolve(hash);
                if (!java.nio.file.Files.isRegularFile(pack)) continue; // gone: loads from the server on join
                if (n == 0) source.allowServerPacks();
                source.pushLocalPack(id, pack);
                order.put(id, hash);
                loaded.add(id + "|" + hash);
                n++;
            }
            if (n > 0) {
                kept = true;
                fromLover = true;
                note("game start: " + n + " LoverCraft packs put back from the downloads folder");
                int count = n;
                com.relaxedqol.client.gui.Toasts.show("Keep Server Packs", "Loading " + count + (count == 1 ? " pack" : " packs")
                    + " from last time - joining will be quick", 0xFF4ADE80, 4000);
            }
        } catch (Exception e) {
            note("game start: could not put the packs back (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Packs from a lover server are still loaded although you left. */
    private static boolean kept;
    /** Set while the kept packs are being removed on purpose (lets vanilla's own cleanup run). */
    private static boolean bypass;
    /** id|hash of every pack a lover server sent (and that got loaded). */
    private static final Set<String> loaded = new HashSet<>();
    /** The packs came from a lover server (decided when they were sent, the address is gone by the time you leave). */
    private static boolean fromLover;
    /** When you joined a lover server again with the packs kept (the server's "remove all packs" right after
     *  joining is ignored for a little while, the same packs come straight back). */
    private static long rejoinedAt;
    /** The packs skipped since joining (each once), for the one note per join. */
    private static final Set<UUID> skippedIds = new HashSet<>();
    private static long lastSkipAt;

    public KeepServerPacks() {
        super("KeepServerPacks", "Keeps LoverCraft's resource packs loaded when you leave, so leaving and joining again"
            + " (or switching accounts) skips the long pack reload", ModuleCategory.LOVERCRAFT);
        setEnabled(true); // On by default
        afterRestart = addSetting(new com.relaxedqol.client.setting.Setting<>("Keep After Restart", false),
            "Also after restarting Minecraft or your PC: LoverCraft's packs (the copies Minecraft keeps in its downloads"
                + " folder) are put back while the game starts, so joining is quick. The game takes longer to start then");
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c -> restoreOnStart());
        addSetting(new com.relaxedqol.client.setting.ActionSetting("Forget Packs", KeepServerPacks::forget),
            "Removes the kept LoverCraft packs and forgets them - the next time you join, they are loaded fresh from the"
                + " server (use it now and then, or when the server's packs look wrong)");
    }

    /** The Forget Packs button: removes the kept packs (the game's own cleanup) and forgets them. */
    private static void forget() {
        Minecraft mc = Minecraft.getInstance();
        int n = loaded.size();
        try {
            Object source = Minecraft.class.getMethod("getDownloadedPackSource").invoke(mc);
            drop(source);
        } catch (ReflectiveOperationException | RuntimeException e) {
            kept = false;
            loaded.clear();
        }
        synchronized (skippedIds) {
            skippedIds.clear();
        }
        com.relaxedqol.client.gui.Toasts.show("Keep Server Packs", n == 0 ? "Nothing was kept"
            : "Forgot " + n + (n == 1 ? " pack" : " packs") + " - they load fresh next time you join", 0xFF4ADE80, 3500);
    }

    /** A grey line in chat about what it did (so a test shows where it goes wrong). */
    private static void note(String text) {
        com.relaxedqol.RelaxedQolMod.LOGGER.info("[KeepPacks] {}", text);
        if (!DEBUG_CHAT) return;
        Minecraft mc = Minecraft.getInstance();
        // While leaving / joining there is no chat to write to: kept and shown once you are in again
        if (mc.player == null) {
            synchronized (later) {
                later.add(text);
            }
            return;
        }
        mc.execute(() -> com.relaxedqol.client.util.ChatUtil.send("§8[KeepPacks] §7" + text));
    }

    private static final java.util.List<String> later = new java.util.ArrayList<>();
    /** The step-by-step lines in chat (for testing); otherwise they only go to the game log. */
    private static final boolean DEBUG_CHAT = false;

    /** Notes from while you were not in a world, shown now. */
    private static void flushNotes() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        java.util.List<String> copy;
        synchronized (later) {
            if (later.isEmpty()) return;
            copy = new java.util.ArrayList<>(later);
            later.clear();
        }
        mc.execute(() -> {
            for (String t : copy) com.relaxedqol.client.util.ChatUtil.send("§8[KeepPacks] §7" + t);
        });
    }

    @Override
    public void onTick() {
        // The one "still loaded" note, once the server is done sending packs
        int n = 0;
        synchronized (skippedIds) {
            if (!skippedIds.isEmpty() && System.currentTimeMillis() - lastSkipAt > 1500) {
                n = skippedIds.size();
                skippedIds.clear();
            }
        }
        if (n > 0) com.relaxedqol.client.gui.Toasts.show("Keep Server Packs",
            n + (n == 1 ? " pack" : " packs") + " still loaded - no reload", 0xFF4ADE80, 3000);
        // In a singleplayer world: LoverCraft's kept packs do not belong there
        if (kept && mc.hasSingleplayerServer()) {
            try {
                Object source = Minecraft.class.getMethod("getDownloadedPackSource").invoke(mc);
                note("singleplayer world: LoverCraft's packs removed");
                drop(source);
            } catch (ReflectiveOperationException | RuntimeException e) {
                kept = false;
            }
        }
    }

    /** This leave was already decided (both of the game's "remove packs" steps ask). */
    private static boolean leaveDecided;

    private static boolean on() {
        if (RelaxedQolClient.moduleManager == null) return false;
        // Switched on, also in the moment you leave (when the server address is already gone)
        KeepServerPacks m = RelaxedQolClient.moduleManager.getModule(KeepServerPacks.class);
        return m != null && m.isEnabled();
    }

    private static boolean loverAddress() {
        var data = Minecraft.getInstance().getCurrentServer();
        return data != null && data.ip != null && data.ip.toLowerCase(Locale.ROOT).contains("lover");
    }

    // ------------------------------------------------------------------ called from the mixins

    /** Leaving a server: true = keep the packs (vanilla's cleanup is skipped). */
    public static boolean keepOnLeave() {
        if (bypass) return false;
        if (leaveDecided) return kept;
        leaveDecided = true;
        if (!on() || !fromLover || loaded.isEmpty()) {
            note("leaving: packs removed (" + (!on() ? "module off" : !fromLover ? "packs were not from a lover server" : "no packs seen") + ")");
            return false;
        }
        kept = true;
        note("leaving: kept " + loaded.size() + " packs loaded");
        return true;
    }

    /** About to join a server or world: kept packs are removed first when it is not a lover server. */
    public static void beforeJoin(Object packSource, boolean localWorld) {
        leaveDecided = false;
        if (!kept) return;
        // The game also switches to "local" when you only leave to the menu - so that is no reason to remove them;
        // a real singleplayer world is handled once it runs (onTick)
        if (localWorld) return;
        var data = Minecraft.getInstance().getCurrentServer();
        if (data == null || data.ip == null) return; // not known yet: keep them
        if (loverAddress() && on()) {
            rejoinedAt = System.currentTimeMillis(); // the same server again: keep them
            note("joining LoverCraft again with " + loaded.size() + " packs still loaded");
            return;
        }
        note("joining another server: LoverCraft's packs removed");
        drop(packSource);
    }

    /** Removes the kept packs by running vanilla's own cleanup. */
    private static void drop(Object packSource) {
        kept = false;
        fromLover = false;
        loaded.clear();
        order.clear();
        saveList();
        bypass = true;
        try {
            Method m = packSource.getClass().getMethod("cleanupAfterDisconnect");
            m.invoke(packSource);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // nothing else to do: the next pack push replaces them anyway
        } finally {
            bypass = false;
        }
    }

    /**
     * The server sends a pack. True = it is already loaded (kept from last time, same hash): the push is skipped and
     * the server is told it loaded. False = let the game load it the normal way.
     */
    public static boolean skipPush(Object listener, Object packet) {
        boolean lover = loverAddress();
        if (!lover) {
            fromLover = false;
            loaded.clear();
            return false;
        }
        fromLover = true;
        UUID id = (UUID) call(packet, "id");
        String hash = String.valueOf(call(packet, "hash"));
        if (id == null || hash.isBlank() || "null".equals(hash)) return false;
        String key = id + "|" + hash.toLowerCase(Locale.ROOT);
        boolean active = stillActive();
        // Already loaded - kept from last time, or sent a second time during this same join (the lobby and then
        // the survival server both send them): never load the very same pack again
        boolean have = loaded.contains(key) && active;
        if (!(on() && have)) {
            note("pack " + id.toString().substring(0, 8) + ": loading (" + (!loaded.contains(key)
                ? (kept ? "new or changed pack" : "first time") : !active ? "packs are not active yet" : "module off") + ")");
        }
        if (on() && have) {
            reply(listener, id, "ACCEPTED");
            reply(listener, id, "DOWNLOADED");
            reply(listener, id, "SUCCESSFULLY_LOADED");
            // One note per join (shown by onTick once the server stopped sending), counting each pack once
            synchronized (skippedIds) {
                skippedIds.add(id);
            }
            lastSkipAt = System.currentTimeMillis();
            return true;
        }
        // A pack with this id but another hash replaces the old one (vanilla does that), so forget the old one
        loaded.removeIf(k -> k.startsWith(id + "|"));
        loaded.add(key);
        order.remove(id);
        order.put(id, hash.toLowerCase(Locale.ROOT));
        saveList();
        return false;
    }

    /** The kept server packs are really still active (the game names them "server/0", "server/1"...). */
    private static boolean stillActive() {
        try {
            Collection<String> ids = Minecraft.getInstance().getResourcePackRepository().getSelectedIds();
            for (String p : ids) if (p.toLowerCase(Locale.ROOT).startsWith("server/")) return true;
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /**
     * The server asks to remove a pack (or all packs, id empty). Right after joining again with the packs kept,
     * that is ignored: servers clear the packs on join and then send the same ones straight back - removing them
     * would mean two full reloads for nothing. True = ignore it.
     */
    public static boolean skipPop(Object packet) {
        // The server clears the packs when you join or switch (lobby -> survival) and then sends the same ones
        // again: the clear is ignored, so the packs are not removed and loaded again for nothing
        if (!on() || !loverAddress() || loaded.isEmpty() || !stillActive()) {
            note("server removes packs: allowed (" + (loaded.isEmpty() ? "none loaded yet" : "packs not active") + ")");
            return false;
        }
        note("server removes packs: ignored (the same ones come straight back)");
        Object id = call(packet, "id");
        if (id instanceof java.util.Optional<?> o) id = o.orElse(null);
        if (id == null) return true;
        String prefix = id + "|";
        for (String k : loaded) if (k.startsWith(prefix)) return true;
        return false;
    }

    private static Object call(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Sends the server a resource pack status for this pack (what the game itself would send). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void reply(Object listener, UUID id, String status) {
        try {
            Object connection = null;
            for (Class<?> c = listener.getClass(); c != null && connection == null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().getName().equals("net.minecraft.network.Connection")) {
                        f.setAccessible(true);
                        connection = f.get(listener);
                        break;
                    }
                }
            }
            if (connection == null) return;
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.common.ServerboundResourcePackPacket");
            Class<?> action = null;
            for (Class<?> inner : packetClass.getDeclaredClasses()) if (inner.isEnum()) action = inner;
            if (action == null) return;
            Object value = Enum.valueOf((Class<Enum>) action, status);
            Constructor<?> make = packetClass.getConstructor(UUID.class, action);
            Object packet = make.newInstance(id, value);
            Class<?> base = Class.forName("net.minecraft.network.protocol.Packet");
            connection.getClass().getMethod("send", base).invoke(connection, packet);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // the server will just not hear back for this one
        }
    }
}
