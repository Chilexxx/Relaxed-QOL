package com.relaxedqol.client.util;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The first time you are in a world after installing, chat shows once that the client comes with no warranty
 * and is used at your own risk (the full text is in LICENSE and DISCLAIMER.md). Remembered in the config folder.
 */
public final class Disclaimer {
    private static boolean done;

    private Disclaimer() {}

    /** Every client tick (cheap once shown). */
    public static void tick(Minecraft mc) {
        if (done || mc.player == null || mc.player.tickCount < 40) return;
        done = true;
        Path marker = FabricLoader.getInstance().getConfigDir().resolve("relaxed-qol").resolve("disclaimer-shown");
        try {
            if (Files.exists(marker)) return;
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "shown");
        } catch (Exception ignored) {
            // shown again next time - harmless
        }
        ChatUtil.send("§7Relaxed QOL is free to use, provided §fAS IS§7, without any warranty. You use it at"
            + " your own risk: the authors are not responsible for bans, lost items, crashes or any other damage."
            + " Follow each server's rules. Full text: LICENSE and DISCLAIMER.md.");
    }
}
