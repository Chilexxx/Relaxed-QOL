package com.relaxedqol.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** A player's face (with the hat layer) from their tab-list skin, at any size. */
public final class Heads {
    private Heads() {}

    /** Draws the face of the player with this id at (x, y), size x size. False when the skin is not known. */
    public static boolean draw(GuiGraphicsExtractor g, UUID id, int x, int y, int size, int color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || id == null) return false;
        PlayerInfo info = mc.getConnection().getPlayerInfo(id);
        if (info == null) return false;
        try {
            Identifier texture = info.getSkin().body().texturePath();
            int tex = size * 8;
            g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, size, size, size, size, tex, tex, color);
            g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, size * 5, size, size, size, tex, tex, color);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
