package com.relaxedqol.client.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Microsoft sign-in for the account switcher - the same chain the official launcher uses:
 * Microsoft account (device code: you type a short code on microsoft.com/link) -> Xbox Live -> XSTS -> Minecraft
 * access token -> Minecraft profile. Your password never goes through the client.
 *
 * The Microsoft refresh token that comes back is what lets the client get a new Minecraft session later (the game's
 * session runs out after about a day) without signing in again. It only talks to Microsoft, Xbox and Mojang.
 *
 * All calls block - run them off the render thread.
 */
public final class MsAuth {
    /** The Minecraft launcher's own Microsoft app id (the one that may sign in to Xbox Live for Minecraft). */
    private static final String CLIENT_ID = "00000000402b5328";
    private static final String SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";
    private static final String DEVICE_URL = "https://login.live.com/oauth20_connect.srf";
    private static final String TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    private MsAuth() {}

    /** A sign-in failure with a message that can be shown to the player as it is. */
    public static final class AuthException extends Exception {
        /** True: the saved sign-in is no longer accepted - the account has to be signed in again. */
        public final boolean needsLogin;

        public AuthException(String message, boolean needsLogin) {
            super(message);
            this.needsLogin = needsLogin;
        }

        public AuthException(String message) {
            this(message, false);
        }
    }

    /** What the player has to type, and where. */
    public record DeviceCode(String userCode, String deviceCode, String verificationUri, int intervalSec, long expiresAt) {}

    /** A ready Minecraft session plus the Microsoft refresh token to make the next one. */
    public record Session(String name, UUID uuid, String accessToken, long expiresAt, String refreshToken) {}

    // ------------------------------------------------------------------ step 1: Microsoft account

    public static DeviceCode requestDeviceCode() throws AuthException {
        JsonObject o = postForm(DEVICE_URL, Map.of("client_id", CLIENT_ID, "scope", SCOPE, "response_type", "device_code"), "Microsoft");
        String user = str(o, "user_code");
        String device = str(o, "device_code");
        if (user == null || device == null) throw new AuthException("Microsoft did not send a sign-in code: " + brief(o));
        String uri = str(o, "verification_uri");
        if (uri == null) uri = "https://www.microsoft.com/link";
        int interval = o.has("interval") ? Math.max(1, o.get("interval").getAsInt()) : 5;
        long expires = System.currentTimeMillis() + (o.has("expires_in") ? o.get("expires_in").getAsLong() : 900) * 1000L;
        return new DeviceCode(user, device, uri, interval, expires);
    }

    /**
     * Waits until the player has typed the code and signed in (or it runs out / is cancelled), then signs in all
     * the way to Minecraft.
     */
    public static Session completeDeviceLogin(DeviceCode code, BooleanSupplier cancelled, Consumer<String> status) throws AuthException {
        long interval = code.intervalSec() * 1000L;
        while (true) {
            if (cancelled.getAsBoolean()) throw new AuthException("Cancelled");
            if (System.currentTimeMillis() > code.expiresAt()) throw new AuthException("The code ran out - press Add again for a new one");
            sleep(interval, cancelled);
            if (cancelled.getAsBoolean()) throw new AuthException("Cancelled");
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", CLIENT_ID);
            form.put("device_code", code.deviceCode());
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            HttpResponse<String> r = send(formRequest(TOKEN_URL, form), "Microsoft");
            JsonObject o = parse(r.body());
            String error = o == null ? null : str(o, "error");
            if (r.statusCode() == 200 && o != null && str(o, "access_token") != null) {
                status.accept("Signing in to Xbox Live…");
                return fromMicrosoft(str(o, "access_token"), str(o, "refresh_token"), status);
            }
            if ("authorization_pending".equals(error)) continue;
            if ("slow_down".equals(error)) {
                interval += 5000;
                continue;
            }
            if ("expired_token".equals(error)) throw new AuthException("The code ran out - press Add again for a new one");
            if ("authorization_declined".equals(error) || "access_denied".equals(error)) throw new AuthException("Sign-in was declined");
            throw new AuthException("Microsoft sign-in failed: " + (o != null ? brief(o) : "HTTP " + r.statusCode()));
        }
    }

    /** A new Minecraft session from a saved Microsoft refresh token (no browser, nothing to type). */
    public static Session refresh(String refreshToken, Consumer<String> status) throws AuthException {
        if (refreshToken == null || refreshToken.isBlank()) throw new AuthException("This account has to be signed in again", true);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("scope", SCOPE);
        HttpResponse<String> r = send(formRequest(TOKEN_URL, form), "Microsoft");
        JsonObject o = parse(r.body());
        if (r.statusCode() != 200 || o == null || str(o, "access_token") == null) {
            String error = o == null ? null : str(o, "error");
            boolean dead = "invalid_grant".equals(error) || "interaction_required".equals(error) || "invalid_request".equals(error);
            throw new AuthException(dead ? "Microsoft wants you to sign in again (the saved sign-in ran out or was revoked)"
                : "Microsoft refresh failed: " + (o != null ? brief(o) : "HTTP " + r.statusCode()), dead);
        }
        status.accept("Signing in to Xbox Live…");
        // Microsoft hands out a new refresh token each time (the old one keeps working for a while, but use the new one)
        String newRefresh = str(o, "refresh_token");
        return fromMicrosoft(str(o, "access_token"), newRefresh != null ? newRefresh : refreshToken, status);
    }

    // ------------------------------------------------------------------ steps 2-5: Xbox Live -> XSTS -> Minecraft

    private static Session fromMicrosoft(String msAccess, String msRefresh, Consumer<String> status) throws AuthException {
        // Xbox Live user token. The launcher's app id takes the plain ticket; "d=" / "t=" are tried if it is refused.
        JsonObject xbl = null;
        AuthException last = null;
        for (String prefix : new String[]{"", "d=", "t="}) {
            JsonObject props = new JsonObject();
            props.addProperty("AuthMethod", "RPS");
            props.addProperty("SiteName", "user.auth.xboxlive.com");
            props.addProperty("RpsTicket", prefix + msAccess);
            JsonObject body = new JsonObject();
            body.add("Properties", props);
            body.addProperty("RelyingParty", "http://auth.xboxlive.com");
            body.addProperty("TokenType", "JWT");
            HttpResponse<String> r = send(jsonRequest(XBL_URL, body, null), "Xbox Live");
            JsonObject o = parse(r.body());
            if (r.statusCode() == 200 && o != null && str(o, "Token") != null) {
                xbl = o;
                break;
            }
            last = new AuthException("Xbox Live sign-in failed (HTTP " + r.statusCode() + ")");
        }
        if (xbl == null) throw last;
        String xblToken = str(xbl, "Token");

        status.accept("Getting the Minecraft token…");
        JsonObject xprops = new JsonObject();
        xprops.addProperty("SandboxId", "RETAIL");
        JsonArray users = new JsonArray();
        users.add(xblToken);
        xprops.add("UserTokens", users);
        JsonObject xbody = new JsonObject();
        xbody.add("Properties", xprops);
        xbody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xbody.addProperty("TokenType", "JWT");
        HttpResponse<String> xr = send(jsonRequest(XSTS_URL, xbody, null), "Xbox Live");
        JsonObject xsts = parse(xr.body());
        if (xr.statusCode() != 200 || xsts == null || str(xsts, "Token") == null) {
            long xerr = xsts != null && xsts.has("XErr") ? xsts.get("XErr").getAsLong() : 0;
            throw new AuthException(xstsMessage(xerr, xr.statusCode()), xerr != 0);
        }
        String uhs = userHash(xsts);
        if (uhs == null) uhs = userHash(xbl);
        if (uhs == null) throw new AuthException("Xbox Live sent no user hash");

        JsonObject mcBody = new JsonObject();
        mcBody.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + str(xsts, "Token"));
        HttpResponse<String> mr = send(jsonRequest(MC_LOGIN_URL, mcBody, null), "Minecraft");
        JsonObject mc = parse(mr.body());
        if (mr.statusCode() == 429) throw new AuthException("Minecraft says: too many sign-ins - wait a minute and try again");
        if (mr.statusCode() != 200 || mc == null || str(mc, "access_token") == null) {
            throw new AuthException("Minecraft sign-in failed: " + (mc != null ? brief(mc) : "HTTP " + mr.statusCode()));
        }
        String mcToken = str(mc, "access_token");
        long expiresIn = mc.has("expires_in") ? mc.get("expires_in").getAsLong() : 86400;
        long expiresAt = jwtExpiry(mcToken);
        if (expiresAt <= 0) expiresAt = System.currentTimeMillis() + expiresIn * 1000L;

        status.accept("Loading your profile…");
        HttpRequest pr = HttpRequest.newBuilder(URI.create(MC_PROFILE_URL)).timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + mcToken).header("Accept", "application/json").GET().build();
        HttpResponse<String> prr = send(pr, "Minecraft");
        if (prr.statusCode() == 404) throw new AuthException("This Microsoft account does not own Minecraft: Java Edition", true);
        JsonObject profile = parse(prr.body());
        if (prr.statusCode() != 200 || profile == null || str(profile, "id") == null || str(profile, "name") == null) {
            throw new AuthException("Could not load the Minecraft profile (HTTP " + prr.statusCode() + ")");
        }
        return new Session(str(profile, "name"), undashed(str(profile, "id")), mcToken, expiresAt, msRefresh);
    }

    private static String xstsMessage(long xerr, int http) {
        if (xerr == 2148916233L) return "This Microsoft account has no Xbox profile yet - sign in once on xbox.com, then try again";
        if (xerr == 2148916235L) return "Xbox Live is not available in your country";
        if (xerr == 2148916236L || xerr == 2148916237L) return "This account needs adult verification on xbox.com first";
        if (xerr == 2148916238L) return "This is a child account - it has to be added to a Microsoft family first";
        return "Xbox Live refused the sign-in (" + (xerr != 0 ? "XErr " + xerr : "HTTP " + http) + ")";
    }

    private static String userHash(JsonObject o) {
        try {
            JsonArray xui = o.getAsJsonObject("DisplayClaims").getAsJsonArray("xui");
            return xui.get(0).getAsJsonObject().get("uhs").getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ tokens

    /** When a Minecraft access token (a JWT) runs out, in epoch ms; 0 when it cannot be read. */
    public static long jwtExpiry(String token) {
        if (token == null) return 0;
        String[] parts = token.split("\\.");
        if (parts.length < 2) return 0;
        try {
            String json = new String(Base64.getUrlDecoder().decode(pad(parts[1])), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return o.has("exp") ? o.get("exp").getAsLong() * 1000L : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String pad(String b64) {
        int m = b64.length() % 4;
        return m == 0 ? b64 : b64 + "====".substring(m);
    }

    /** Whether Minecraft still accepts this access token (one cheap profile call). Null = could not tell (offline). */
    public static Boolean tokenWorks(String token) {
        try {
            HttpRequest pr = HttpRequest.newBuilder(URI.create(MC_PROFILE_URL)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).GET().build();
            int code = HTTP.send(pr, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code == 200) return true;
            if (code == 401) return false;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static UUID undashed(String id) {
        String s = id.replace("-", "");
        if (s.length() != 32) return UUID.fromString(id);
        return UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-"
            + s.substring(16, 20) + "-" + s.substring(20));
    }

    // ------------------------------------------------------------------ HTTP helpers

    private static HttpRequest formRequest(String url, Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(sb.toString())).build();
    }

    private static HttpRequest jsonRequest(String url, JsonObject body, String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .header("x-xbl-contract-version", "1")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return b.build();
    }

    private static JsonObject postForm(String url, Map<String, String> form, String who) throws AuthException {
        HttpResponse<String> r = send(formRequest(url, form), who);
        JsonObject o = parse(r.body());
        if (r.statusCode() != 200 || o == null) {
            throw new AuthException(who + " answered HTTP " + r.statusCode() + (o != null ? ": " + brief(o) : ""));
        }
        return o;
    }

    private static HttpResponse<String> send(HttpRequest request, String who) throws AuthException {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Cancelled");
        } catch (Exception e) {
            throw new AuthException("Could not reach " + who + " (" + e.getClass().getSimpleName() + ") - are you online?");
        }
    }

    private static JsonObject parse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonElement e = JsonParser.parseString(body);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A short error text from an answer (never any token in it). */
    private static String brief(JsonObject o) {
        String d = str(o, "error_description");
        if (d == null) d = str(o, "errorMessage");
        if (d == null) d = str(o, "error");
        if (d == null) d = str(o, "Message");
        if (d == null) return "unexpected answer";
        return d.length() > 140 ? d.substring(0, 140) + "…" : d;
    }

    private static void sleep(long ms, BooleanSupplier cancelled) throws AuthException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancelled.getAsBoolean()) return;
            try {
                Thread.sleep(Math.min(250, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuthException("Cancelled");
            }
        }
    }
}
