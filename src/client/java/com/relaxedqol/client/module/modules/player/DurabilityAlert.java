package com.relaxedqol.client.module.modules.player;

import com.relaxedqol.client.gui.OverlayRenderer;
import com.relaxedqol.client.gui.Render2D;
import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.gui.Toasts;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.ChatUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Durability Alert (custom QOL): warns you before your armor or the tool / weapon in your hands breaks - a pop-up,
 * a chat line and a low "bass" ping once when it drops under the threshold, and a small red card above the hotbar
 * for as long as it stays that low.
 */
public class DurabilityAlert extends Module {
    private final Setting<Integer> threshold = addSetting(new Setting<>("Warn At %", 15, 1, 50, 1),
        "Warn when an item has less than this much durability left");
    private final Setting<Boolean> armor = addSetting(new Setting<>("Armor", true), "Helmet, chestplate, leggings, boots (and elytra)");
    private final Setting<Boolean> hands = addSetting(new Setting<>("Held Items", true), "The tool / weapon in your main hand and off hand");
    private final Setting<Boolean> popup = addSetting(new Setting<>("Pop-up", true));
    private final Setting<Boolean> chat = addSetting(new Setting<>("Chat Message", true));
    private final Setting<Boolean> sound = addSetting(new Setting<>("Sound", true));
    private final Setting<Boolean> card = addSetting(new Setting<>("Warning Card", true),
        "A small red card above your hotbar while something is low");

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
        EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    private static final String[] SLOT_NAMES = {"Helmet", "Chestplate", "Leggings", "Boots", "Main hand", "Off hand"};

    /** Per slot: the item we already warned about (so one item warns once), or null. */
    private final ItemStack[] warned = new ItemStack[SLOTS.length];
    /** What is low right now, for the card: "Boots 8%". */
    private List<String> low = new ArrayList<>();

    public DurabilityAlert() {
        super("DurabilityAlert", "Warns you before your armor or tools break (pop-up, ping, card above the hotbar)", ModuleCategory.PLAYER);
        setEnabled(true);
    }

    /** Durability left as 0..1, or -1 for items that do not wear out. */
    private static float left(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem() || stack.getMaxDamage() <= 0) return -1f;
        return (stack.getMaxDamage() - stack.getDamageValue()) / (float) stack.getMaxDamage();
    }

    @Override
    protected void onDisable() {
        low = new ArrayList<>();
        for (int i = 0; i < warned.length; i++) warned[i] = null;
    }

    @Override
    public void onTick() {
        if (mc.player == null) {
            low = new ArrayList<>();
            return;
        }
        float limit = threshold.getValue() / 100f;
        List<String> now = new ArrayList<>();
        for (int i = 0; i < SLOTS.length; i++) {
            boolean isArmor = i < 4;
            if ((isArmor && !armor.getValue()) || (!isArmor && !hands.getValue())) {
                warned[i] = null;
                continue;
            }
            ItemStack stack = mc.player.getItemBySlot(SLOTS[i]);
            float f = left(stack);
            if (f < 0 || f >= limit) {
                // Repaired / swapped / fine: warn again next time it drops
                if (f < 0 || f >= limit + 0.03f) warned[i] = null;
                continue;
            }
            int percent = Math.max(0, Math.round(f * 100));
            String what = isArmor ? SLOT_NAMES[i] : stack.getHoverName().getString();
            now.add(what + " " + percent + "%");
            if (warned[i] == null || warned[i].getItem() != stack.getItem()) {
                warned[i] = stack.copy();
                warn(what, percent, stack.getMaxDamage() - stack.getDamageValue());
            }
        }
        low = now;
    }

    private void warn(String what, int percent, int usesLeft) {
        if (popup.getValue()) Toasts.show("Durability", what + " is at " + percent + "% (" + usesLeft + " left)", 0xFFF87171, 5000);
        if (chat.getValue()) ChatUtil.send("§c⚠ §f" + what + " §7is at §c" + percent + "% §7(" + usesLeft + " uses left)");
        if (sound.getValue() && mc.player != null) {
            mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 0.5f);
        }
    }

    /** The warning card above the hotbar (HUD element, only while no screen is open). */
    public void renderHud(GuiGraphicsExtractor g) {
        if (!card.getValue() || low.isEmpty() || mc.player == null) return;
        Font font = mc.font;
        float scale = OverlayRenderer.hudScale();
        String text = "⚠ " + String.join("  ·  ", low);
        int tw = font.width(text);
        int w = tw + 14;
        int h = 14;
        int vw = Math.round(g.guiWidth() / scale);
        // Above the hotbar, hearts and armor bar (about 60 GUI pixels from the bottom)
        int y = Math.round((g.guiHeight() - 62) / scale) - h;
        int x = (vw - w) / 2;
        float pulse = 0.55f + 0.45f * (float) Math.sin(System.currentTimeMillis() / 220.0);
        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        Render2D.roundRect(g, x, y, w, h, 7, 0xD0180C0C);
        Render2D.roundOutline(g, x, y, w, h, 7, Theme.withAlpha(0xFFF87171, 0.4f + 0.5f * pulse));
        g.text(font, text, x + 7, y + 3, 0xFFFCA5A5, false);
        g.pose().popMatrix();
    }

    @Override
    public String getInfo() {
        return low.isEmpty() ? null : String.valueOf(low.size());
    }
}
