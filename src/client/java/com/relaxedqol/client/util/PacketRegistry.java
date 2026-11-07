package com.relaxedqol.client.util;

import com.relaxedqol.RelaxedQolMod;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.cookie.CookiePacketTypes;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.ping.PingPacketTypes;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Every in-game packet type, read once from Minecraft's packet type tables. Used by the packet picker. */
public final class PacketRegistry {
    public record Entry(String id, String displayName, PacketFlow flow) {}

    private static List<Entry> serverbound;
    private static List<Entry> clientbound;

    private PacketRegistry() {}

    public static List<Entry> serverbound() {
        load();
        return serverbound;
    }

    public static List<Entry> clientbound() {
        load();
        return clientbound;
    }

    private static synchronized void load() {
        if (serverbound != null) return;
        Map<String, Entry> c2s = new LinkedHashMap<>();
        Map<String, Entry> s2c = new LinkedHashMap<>();
        for (Class<?> table : new Class<?>[]{GamePacketTypes.class, CommonPacketTypes.class, PingPacketTypes.class, CookiePacketTypes.class}) {
            for (Field field : table.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != PacketType.class) continue;
                try {
                    field.setAccessible(true);
                    PacketType<?> type = (PacketType<?>) field.get(null);
                    String id = type.id().toString();
                    Entry entry = new Entry(id, prettify(type.id().getPath()), type.flow());
                    (type.flow() == PacketFlow.SERVERBOUND ? c2s : s2c).putIfAbsent(id, entry);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    RelaxedQolMod.LOGGER.warn("Could not read packet type {}", field.getName());
                }
            }
        }
        serverbound = Collections.unmodifiableList(new ArrayList<>(c2s.values()));
        clientbound = Collections.unmodifiableList(new ArrayList<>(s2c.values()));
    }

    /** Real packet class per "flow|id", learned from the packets that actually go by this session. */
    private static final Map<String, String> SEEN_CLASS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Remembers the real class of a packet that was sent or received (cheap: one map lookup per new type). */
    public static void observe(net.minecraft.network.protocol.Packet<?> packet) {
        try {
            PacketType<?> type = packet.type();
            String key = type.flow() + "|" + type.id();
            if (SEEN_CLASS.containsKey(key)) return;
            Class<?> c = packet.getClass();
            String name = c.getName();
            name = name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
            // Bundles and other wrappers can be anonymous/synthetic classes: keep the guessed name for those
            if (!name.matches("[A-Za-z0-9_.]+") || name.contains("Lambda")) return;
            SEEN_CLASS.put(key, name);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * The packet's real name with its direction, e.g. "ServerboundMovePlayerPacket.Pos  C2S". Uses the real class
     * once one has gone by; until then the vanilla naming pattern (Serverbound / Clientbound + name + Packet).
     */
    public static String realName(String id, boolean serverbound) {
        PacketFlow flow = serverbound ? PacketFlow.SERVERBOUND : PacketFlow.CLIENTBOUND;
        String seen = SEEN_CLASS.get(flow + "|" + id);
        String name = seen;
        if (name == null) {
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            name = (serverbound ? "Serverbound" : "Clientbound") + prettify(path).replace(" ", "") + "Packet";
        }
        return name + "  " + (serverbound ? "C2S" : "S2C");
    }

    /** "move_player_pos" -> "Move Player Pos" */
    public static String prettify(String path) {
        StringBuilder sb = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return sb.toString();
    }
}
