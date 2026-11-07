package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.gui.HudCard;
import com.relaxedqol.client.gui.HudWindow;
import com.relaxedqol.client.gui.Render2D;
import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.ChatUtil;
import com.relaxedqol.client.util.Colors;
import com.relaxedqol.client.util.GearUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * (Lunar-style "Armor Status"): your armor (and held items) on the HUD with durability - as a percentage,
 * as "left / max" or as a bar - coloured green to red as it wears. Enchanted pieces keep their glint. A piece
 * below the warning level pulses red, and you can get a one-time chat warning for it. Drag / resize it while the
 * menu is open.
 */
public class ArmorStatus extends Module implements HudCard {
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final Setting<String> layout = addSetting(Setting.mode("Layout", "Vertical", "Vertical", "Horizontal"));
    private final Setting<String> show = addSetting(Setting.mode("Durability", "Percent", "Percent", "Left / Max", "Left", "Bar Only", "None"));
    private final Setting<Boolean> held = addSetting(new Setting<>("Held Items", true), "Also your main hand and off hand items");
    private final Setting<Boolean> bars = addSetting(new Setting<>("Bars", true), "A thin durability bar under each piece");
    private final Setting<Boolean> background = addSetting(new Setting<>("Background", true));
    private final Setting<Boolean> hideEmpty = addSetting(new Setting<>("Hide When Empty", true), "No card when you wear nothing");
    private final Setting<Integer> lowAt = addSetting(new Setting<>("Low Warning %", 15, 1, 60, 1),
        "Below this the piece pulses red");
    private final Setting<Boolean> chatWarn = addSetting(new Setting<>("Warn In Chat", true),
        "A chat message once when a piece drops below the warning level");
    private final Setting<String> textColor = addSetting(Colors.setting("Text Colour", "White"),
        "Colour of the numbers when Colour By Wear is off");
    private final Setting<Boolean> wearColor = addSetting(new Setting<>("Colour By Wear", true), "Green when fine, red when nearly broken");
    private final Setting<Boolean> showInChat = addSetting(new Setting<>("Show While Chatting", true),
        "Keep the card on screen while the chat is open");
    private final Setting<Boolean> enchantedBar = addSetting(new Setting<>("Enchanted Armor Bar", true),
        "The armor icons above your hearts get a moving enchant shimmer");
    private final Setting<Boolean> barWearColor = addSetting(new Setting<>("Armor Bar Wear Colour", true),
        "The armor icons above your hearts turn orange / red as your most worn piece gets low");
    private final Setting<Integer> winX = addSetting(HudWindow.xSetting());
    private final Setting<Integer> winY = addSetting(HudWindow.ySetting());
    private final Setting<Integer> winScale = addSetting(HudWindow.scaleSetting());
    private final HudWindow window = new HudWindow(winX, winY, winScale);
    /** Pieces we already warned about (by item + slot), until they are repaired or swapped. */
    private final boolean[] warned = new boolean[6];

    public ArmorStatus() {
        super("ArmorStatus", "Armor and held item durability on your screen", ModuleCategory.RENDER);
    }

    /** This card's own look (HUD editor). */
    private final com.relaxedqol.client.gui.CardStyle style = new com.relaxedqol.client.gui.CardStyle("", this::addSetting);

    @Override
    public com.relaxedqol.client.gui.CardStyle cardStyle() {
        return style;
    }

    @Override
    public HudWindow getWindow() {
        return window;
    }

    private record Piece(EquipmentSlot slot, ItemStack stack, float fraction) {}

    private List<Piece> pieces() {
        List<Piece> out = new ArrayList<>();
        if (mc.player == null) return out;
        for (EquipmentSlot slot : ARMOR) add(out, slot);
        if (held.getValue()) {
            add(out, EquipmentSlot.MAINHAND);
            add(out, EquipmentSlot.OFFHAND);
        }
        return out;
    }

    private void add(List<Piece> out, EquipmentSlot slot) {
        ItemStack stack = mc.player.getItemBySlot(slot);
        if (stack.isEmpty()) return;
        out.add(new Piece(slot, stack, GearUtil.durabilityFraction(stack)));
    }

    @Override
    public void onTick() {
        if (mc.player == null || !chatWarn.getValue()) return;
        for (int i = 0; i < GearUtil.SLOTS.length; i++) {
            ItemStack stack = mc.player.getItemBySlot(GearUtil.SLOTS[i]);
            float f = GearUtil.durabilityFraction(stack);
            boolean low = f >= 0 && f * 100f < lowAt.getValue();
            if (low && !warned[i]) {
                warned[i] = true;
                String left = GearUtil.durability(stack);
                ChatUtil.send("§c⚠ §f" + stack.getHoverName().getString() + " §7is almost broken: " + (left != null ? left : ""));
                mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 0.8f, 0.6f);
            } else if (!low) {
                warned[i] = false;
            }
        }
    }

    @Override
    public boolean showsCard() {
        if (mc.player == null) return false;
        if (!showInChat.getValue() && mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen) return false;
        return !(hideEmpty.getValue() && pieces().isEmpty());
    }

    private static final net.minecraft.resources.Identifier ARMOR_FULL = net.minecraft.resources.Identifier.withDefaultNamespace("hud/armor_full");
    private static final net.minecraft.resources.Identifier ARMOR_HALF = net.minecraft.resources.Identifier.withDefaultNamespace("hud/armor_half");
    private static final net.minecraft.resources.Identifier ARMOR_EMPTY = net.minecraft.resources.Identifier.withDefaultNamespace("hud/armor_empty");

    /**
     * The vanilla armor bar (above the hearts) redrawn with a tint: an enchant shimmer that runs along it and / or a
     * colour by how worn your armor is. Returns false to let vanilla draw it (both options off).
     */
    public boolean drawArmorBar(GuiGraphicsExtractor g) {
        if (!enchantedBar.getValue() && !barWearColor.getValue()) return false;
        if (mc.player == null || mc.gameMode == null || !mc.gameMode.canHurtPlayer()) return false;
        int armor = mc.player.getArmorValue();
        if (armor <= 0) return true;
        // Where vanilla puts it (Fabric keeps track of the bar heights, extra heart rows included)
        int left = g.guiWidth() / 2 - 91;
        int y = g.guiHeight() - net.fabricmc.fabric.api.client.rendering.v1.hud.HudStatusBarHeightRegistry.getHeight(
            net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.ARMOR_BAR);
        // Most worn armor piece decides the wear colour
        float worst = 1f;
        for (EquipmentSlot slot : ARMOR) {
            float f = GearUtil.durabilityFraction(mc.player.getItemBySlot(slot));
            if (f >= 0) worst = Math.min(worst, f);
        }
        int base = 0xFFFFFFFF;
        if (barWearColor.getValue() && worst * 100f < 50f) {
            base = worst * 100f < lowAt.getValue() ? Theme.DANGER : Theme.WARNING;
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            int v = i * 2 + 1;
            net.minecraft.resources.Identifier sprite = v < armor ? ARMOR_FULL : v == armor ? ARMOR_HALF : ARMOR_EMPTY;
            int color = base;
            if (enchantedBar.getValue() && v <= armor) {
                // A soft purple glint that travels along the bar every 2.4 s
                double phase = (now % 2400L) / 2400.0 * 14.0 - 2.0;
                float glint = (float) Math.max(0, 1 - Math.abs(i - phase) / 2.2);
                float pulse = 0.12f + 0.08f * (float) Math.sin(now / 420.0 + i * 0.6);
                color = Theme.mix(base, 0xFFC9A2FF, Math.min(0.85f, pulse + glint * 0.7f));
            }
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, sprite, left + i * 8, y, 9, 9, color);
        }
        return true;
    }

    private String label(ItemStack stack, float f) {
        if (f < 0) return stack.getCount() > 1 ? String.valueOf(stack.getCount()) : "";
        int max = stack.getMaxDamage();
        int left = max - stack.getDamageValue();
        return switch (show.getValue()) {
            case "Percent" -> Math.round(f * 100) + "%";
            case "Left / Max" -> left + "/" + max;
            case "Left" -> String.valueOf(left);
            default -> "";
        };
    }

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int vw, int vh) {
        List<Piece> list = pieces();
        if (list.isEmpty()) {
            if (hideEmpty.getValue()) return;
        }
        boolean vertical = layout.getValue().equals("Vertical");
        int textW = 0;
        for (Piece p : list) textW = Math.max(textW, font.width(label(p.stack(), p.fraction())));
        int cell = vertical ? 18 + (textW > 0 ? textW + 4 : 0) : Math.max(20, textW + 4);
        int rowH = vertical ? 18 : 18 + (textW > 0 ? 10 : 0) + (bars.getValue() ? 2 : 0);
        int n = Math.max(1, list.size());
        int w = vertical ? cell + 8 : n * cell + 6;
        int h = vertical ? n * rowH + 6 : rowH + 6;
        // By default right next to the radar card (bottom-aligned, on its left); without the radar the
        // bottom-right corner of the screen
        int autoX = vw - w - 4;
        int autoY = vh - h - 4;
        Radar radar = com.relaxedqol.client.RelaxedQolClient.enabled(Radar.class);
        if (radar != null && radar.getWindow().isVisible()) {
            com.relaxedqol.client.gui.HudWindow rw = radar.getWindow();
            float scale = winScale.getValue() / 100f;
            int sw = Math.round(w * scale);
            int sh = Math.round(h * scale);
            if (rw.getX() - sw - 4 >= 0) {
                autoX = rw.getX() - sw - 4;
                autoY = rw.getY() + rw.getH() - sh;
            } else {
                autoX = rw.getX() + rw.getW() - sw;
                autoY = rw.getY() - sh - 4;
            }
        }
        window.begin(g, vw, vh, w, h, autoX, autoY);
        if (background.getValue()) {
            // The shared HUD card look
            com.relaxedqol.client.gui.HudStyle.card(g, 0, 0, w, h);
        }
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) {
            Piece p = list.get(i);
            int x = vertical ? 4 : 3 + i * cell + (cell - 16) / 2;
            int y = vertical ? 3 + i * rowH + 1 : 3;
            boolean low = p.fraction() >= 0 && p.fraction() * 100f < lowAt.getValue();
            if (low) {
                // A calm, slow pulse behind a piece that is about to break (it used to flash several times a second)
                float pulse = 0.22f + 0.12f * (float) Math.sin(now / 450.0);
                Render2D.roundRect(g, x - 1, y - 1, 18, 18, Theme.R_CONTROL, Theme.withAlpha(Theme.DANGER, pulse));
            }
            g.item(p.stack(), x, y);
            int c = p.fraction() < 0 ? Theme.TEXT
                : wearColor.getValue() ? Colors.health(p.fraction()) : Colors.of(textColor.getValue());
            if (low) c = Theme.DANGER;
            String text = label(p.stack(), p.fraction());
            if (!text.isEmpty()) {
                if (vertical) g.text(font, text, x + 20, y + 4, c, true);
                else g.text(font, text, 3 + i * cell + (cell - font.width(text)) / 2, y + 18, c, true);
            }
            if (bars.getValue() && p.fraction() >= 0) {
                int bw = vertical ? (textW > 0 ? textW : 14) : cell - 6;
                int bx = vertical ? x + 20 : 3 + i * cell + 3;
                int by = vertical ? y + 14 : y + (text.isEmpty() ? 17 : 27);
                g.fill(bx, by, bx + bw, by + 1, Theme.HAIRLINE);
                int fw = Math.max(1, Math.round(bw * p.fraction()));
                g.fill(bx, by, bx + fw, by + 1, c);
            }
        }
        window.end(g);
    }
}
