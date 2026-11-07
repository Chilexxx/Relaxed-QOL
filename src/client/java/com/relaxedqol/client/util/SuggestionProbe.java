package com.relaxedqol.client.util;

import com.relaxedqol.client.PacketManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Asks the server for tab completions of a command line in the background (like pressing Tab in chat) and
 * hands the answers to a callback. Our requests use their own ids so the chat box never sees them.
 */
public final class SuggestionProbe {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(0x5A000000);
    private static final Map<Integer, Consumer<List<String>>> PENDING = new ConcurrentHashMap<>();

    private SuggestionProbe() {}

    /** Sends the request; the callback runs on the main thread with the suggested words. */
    public static void request(String commandLine, Consumer<List<String>> callback) {
        if (Minecraft.getInstance().getConnection() == null) return;
        int id = NEXT_ID.incrementAndGet();
        PENDING.put(id, callback);
        PacketManager.sendDirect(new ServerboundCommandSuggestionPacket(id, commandLine));
        if (PENDING.size() > 200) PENDING.clear();
    }

    /** Network thread. Returns true for answers to our own requests (they are dropped afterwards). */
    public static boolean onReceive(Packet<?> packet) {
        if (!(packet instanceof ClientboundCommandSuggestionsPacket answer)) return false;
        Consumer<List<String>> callback = PENDING.remove(answer.id());
        if (callback == null) return false;
        List<String> words = new ArrayList<>();
        for (ClientboundCommandSuggestionsPacket.Entry entry : answer.suggestions()) words.add(entry.text());
        Minecraft.getInstance().execute(() -> callback.accept(words));
        return true;
    }
}
