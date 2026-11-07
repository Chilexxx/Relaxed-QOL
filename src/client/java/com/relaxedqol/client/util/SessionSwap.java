package com.relaxedqol.client.util;

import com.relaxedqol.RelaxedQolMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Puts another Minecraft session into the running game - no restart, no second launcher instance.
 *
 * The game keeps the signed-in account in a few places: the {@code User} (name, UUID, access token - what joining a
 * server uses), the {@code UserApiService} (chat permissions, block list, reports), the {@code ProfileKeyPairManager}
 * (the chat-signing keys servers check), the reporting context, the social manager and the profile / properties
 * futures (your own skin in menus, "multiplayer disabled" flags). Each one is rebuilt for the new token.
 *
 * Everything is found by type through reflection, so a renamed field or a changed constructor only skips that one
 * part (logged) instead of crashing the game. Only the {@code User} is required.
 *
 * {@link #prepare} does the network work (call it off the render thread); {@link #install} must run on the render thread.
 */
public final class SessionSwap {
    private SessionSwap() {}

    /** Everything fetched in the background for one session, ready to be put in. */
    public static final class Prepared {
        final String name;
        final UUID uuid;
        final String token;
        Object userApi;
        Object profileResult;
        Object userProperties;

        Prepared(String name, UUID uuid, String token) {
            this.name = name;
            this.uuid = uuid;
            this.token = token;
        }
    }

    /** Builds the services for this session (network: user API, profile, properties). Off the render thread. */
    public static Prepared prepare(String name, UUID uuid, String token) {
        Prepared p = new Prepared(name, uuid, token);
        Minecraft mc = Minecraft.getInstance();
        try {
            p.userApi = createUserApi(mc, token);
        } catch (Throwable t) {
            warn("user API service", t);
        }
        if (p.userApi != null) {
            try {
                Method fetch = findMethod(p.userApi.getClass(), "fetchProperties", 0);
                if (fetch != null) p.userProperties = fetch.invoke(p.userApi);
            } catch (Throwable t) {
                warn("user properties", t);
            }
        }
        try {
            p.profileResult = fetchProfile(mc, uuid, name);
        } catch (Throwable t) {
            warn("profile", t);
        }
        return p;
    }

    /**
     * Puts the session into the game. {@code full}: also the chat keys / social / reporting services (only while
     * not in a world - the running chat session keeps its keys). Throws if even the User cannot be replaced.
     */
    public static void install(Prepared p, boolean full) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        Object oldUser = mc.getUser();
        Class<?> userClass = oldUser.getClass();
        Object user = newUser(userClass, p.name, p.uuid, p.token, oldUser, p.uuid.equals(mc.getUser().getProfileId()));
        Field userField = fieldOfType(Minecraft.class, userClass);
        if (userField == null) throw new IllegalStateException("Minecraft has no User field");
        set(userField, mc, user);

        if (!full) return;
        Object api = p.userApi;
        if (api != null) {
            for (Field f : instanceFields(Minecraft.class)) {
                if (f.getType().getName().equals("com.mojang.authlib.minecraft.UserApiService")) trySet(f, mc, api, "UserApiService");
            }
        }
        for (Field f : instanceFields(Minecraft.class)) {
            String type = f.getType().getSimpleName();
            try {
                switch (type) {
                    case "ProfileKeyPairManager" -> {
                        if (api == null) break;
                        Method create = findStatic(f.getType(), "create");
                        if (create != null) trySet(f, mc, create.invoke(null, fill(create.getParameterTypes(), mc, api, user)), type);
                    }
                    case "ReportingContext" -> {
                        if (api == null) break;
                        Method create = findStatic(f.getType(), "create");
                        if (create != null) trySet(f, mc, create.invoke(null, fill(create.getParameterTypes(), mc, api, user)), type);
                    }
                    case "PlayerSocialManager" -> {
                        if (api == null) break;
                        for (Constructor<?> c : f.getType().getDeclaredConstructors()) {
                            Object[] args = fill(c.getParameterTypes(), mc, api, user);
                            if (args == null) continue;
                            c.setAccessible(true);
                            trySet(f, mc, c.newInstance(args), type);
                            break;
                        }
                    }
                    case "CompletableFuture" -> {
                        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                        if (n.contains("profile") && futureHolds(f, p.profileResult)) {
                            trySet(f, mc, CompletableFuture.completedFuture(p.profileResult), f.getName());
                        } else if (n.contains("propert") && futureHolds(f, p.userProperties)) {
                            trySet(f, mc, CompletableFuture.completedFuture(p.userProperties), f.getName());
                        }
                    }
                    default -> {
                    }
                }
            } catch (Throwable t) {
                warn(type, t);
            }
        }
    }

    // ------------------------------------------------------------------ building the pieces

    private static Object newUser(Class<?> userClass, String name, UUID uuid, String token, Object old, boolean sameAccount) throws Exception {
        Optional<?> xuid = Optional.empty();
        Optional<?> clientId = Optional.empty();
        if (sameAccount) {
            // Same account, new token: keep what the launcher told the game (used for telemetry only)
            xuid = optional(old, "getXuid");
            clientId = optional(old, "getClientId");
        }
        Exception last = null;
        for (Constructor<?> c : userClass.getConstructors()) {
            Class<?>[] types = c.getParameterTypes();
            boolean hasUuid = false;
            for (Class<?> t : types) if (t == UUID.class) hasUuid = true;
            List<String> strings = new ArrayList<>();
            strings.add(name);
            if (!hasUuid) strings.add(uuid.toString().replace("-", ""));
            strings.add(token);
            Object[] args = new Object[types.length];
            int si = 0;
            int oi = 0;
            boolean ok = true;
            for (int i = 0; i < types.length && ok; i++) {
                Class<?> t = types[i];
                if (t == String.class) {
                    if (si >= strings.size()) ok = false;
                    else args[i] = strings.get(si++);
                } else if (t == UUID.class) {
                    args[i] = uuid;
                } else if (t == Optional.class) {
                    args[i] = oi++ == 0 ? xuid : clientId;
                } else if (t.isEnum()) {
                    Object pick = null;
                    for (Object constant : t.getEnumConstants()) {
                        if (((Enum<?>) constant).name().equals("MSA")) pick = constant;
                    }
                    if (pick == null && t.getEnumConstants().length > 0) pick = t.getEnumConstants()[0];
                    args[i] = pick;
                } else {
                    ok = false;
                }
            }
            if (!ok || si != strings.size()) continue;
            try {
                return c.newInstance(args);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("No usable User constructor");
    }

    private static Optional<?> optional(Object o, String method) {
        try {
            Method m = o.getClass().getMethod(method);
            Object v = m.invoke(o);
            return v instanceof Optional<?> opt ? opt : Optional.empty();
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** {@code new YggdrasilAuthenticationService(proxy).createUserApiService(token)}. */
    private static Object createUserApi(Minecraft mc, String token) throws Exception {
        Class<?> ygg = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService");
        Proxy proxy = proxy(mc);
        Object service = null;
        for (Constructor<?> c : ygg.getConstructors()) {
            Class<?>[] t = c.getParameterTypes();
            if (t.length == 1 && t[0] == Proxy.class) {
                service = c.newInstance(proxy);
                break;
            }
        }
        if (service == null) throw new IllegalStateException("No YggdrasilAuthenticationService(Proxy)");
        Method create = ygg.getMethod("createUserApiService", String.class);
        return create.invoke(service, token);
    }

    private static Proxy proxy(Minecraft mc) {
        try {
            Method m = Minecraft.class.getMethod("getProxy");
            Object v = m.invoke(mc);
            if (v instanceof Proxy p) return p;
        } catch (Throwable ignored) {
            // older / newer name: fall back to the field
        }
        Field f = fieldOfType(Minecraft.class, Proxy.class);
        if (f != null) {
            try {
                f.setAccessible(true);
                Object v = f.get(mc);
                if (v instanceof Proxy p) return p;
            } catch (Throwable ignored) {
                // no proxy known
            }
        }
        return Proxy.NO_PROXY;
    }

    /** {@code sessionService.fetchProfile(uuid, true)}, or a bare profile with just the name and UUID. */
    private static Object fetchProfile(Minecraft mc, UUID uuid, String name) throws Exception {
        Object session = sessionService(mc);
        if (session != null) {
            for (Method m : session.getClass().getMethods()) {
                Class<?>[] t = m.getParameterTypes();
                if (m.getName().equals("fetchProfile") && t.length == 2 && t[0] == UUID.class && t[1] == boolean.class) {
                    Object result = m.invoke(session, uuid, true);
                    if (result != null) return result;
                }
            }
        }
        // Not reachable right now: a ProfileResult with only name + id (skin loads later)
        Class<?> resultClass = Class.forName("com.mojang.authlib.yggdrasil.ProfileResult");
        Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
        Object profile = profileClass.getConstructor(UUID.class, String.class).newInstance(uuid, name);
        for (Constructor<?> c : resultClass.getConstructors()) {
            Class<?>[] t = c.getParameterTypes();
            if (t.length == 1 && t[0] == profileClass) return c.newInstance(profile);
            if (t.length == 2 && t[0] == profileClass && t[1] == java.util.Set.class) return c.newInstance(profile, java.util.Set.of());
        }
        return null;
    }

    private static Object sessionService(Minecraft mc) {
        try {
            Method services = findMethod(Minecraft.class, "services", 0);
            if (services != null) {
                Object s = services.invoke(mc);
                Method ss = findMethod(s.getClass(), "sessionService", 0);
                if (ss != null) return ss.invoke(s);
            }
        } catch (Throwable ignored) {
            // try the getter below
        }
        try {
            Method m = findMethod(Minecraft.class, "getMinecraftSessionService", 0);
            if (m != null) return m.invoke(mc);
        } catch (Throwable ignored) {
            // none
        }
        return null;
    }

    /** Arguments for a factory / constructor, filled by type; null when a parameter is not known. */
    private static Object[] fill(Class<?>[] types, Minecraft mc, Object api, Object user) throws Exception {
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t.isInstance(api)) args[i] = api;
            else if (t.isInstance(user)) args[i] = user;
            else if (t.isInstance(mc)) args[i] = mc;
            else if (t == Path.class) args[i] = FabricLoader.getInstance().getGameDir();
            else if (t == java.io.File.class) args[i] = FabricLoader.getInstance().getGameDir().toFile();
            else if (t.getSimpleName().equals("ReportEnvironment")) {
                Method local = findStatic(t, "local");
                if (local == null) return null;
                args[i] = local.invoke(null);
            } else return null;
        }
        return args;
    }

    // ------------------------------------------------------------------ reflection helpers

    /** Whether a {@code CompletableFuture<T>} field is declared to hold this value's type (never fill it wrongly). */
    private static boolean futureHolds(Field f, Object value) {
        if (value == null) return false;
        if (!(f.getGenericType() instanceof java.lang.reflect.ParameterizedType pt)) return false;
        java.lang.reflect.Type[] args = pt.getActualTypeArguments();
        return args.length == 1 && args[0] instanceof Class<?> c && c.isInstance(value);
    }

    private static List<Field> instanceFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers())) out.add(f);
        return out;
    }

    private static Field fieldOfType(Class<?> owner, Class<?> type) {
        for (Field f : instanceFields(owner)) if (f.getType() == type) return f;
        return null;
    }

    private static Method findMethod(Class<?> c, String name, int params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == params && !Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == params) return m;
        return null;
    }

    private static Method findStatic(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static void set(Field f, Object owner, Object value) throws IllegalAccessException {
        f.setAccessible(true);
        f.set(owner, value);
    }

    private static void trySet(Field f, Object owner, Object value, String what) {
        if (value == null) return;
        try {
            if (!f.getType().isInstance(value)) return;
            set(f, owner, value);
        } catch (Throwable t) {
            warn(what, t);
        }
    }

    private static void warn(String what, Throwable t) {
        RelaxedQolMod.LOGGER.warn("[Accounts] could not update {} for the new session: {}", what, t.toString());
    }
}
