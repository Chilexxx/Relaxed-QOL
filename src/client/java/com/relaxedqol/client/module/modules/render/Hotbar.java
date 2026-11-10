package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.gui.CleanStyle;
import com.relaxedqol.client.gui.Render2D;
import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.gui.VanillaStyle;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Custom Hotbar: the hotbar, hearts and hunger drawn in the client's style instead of Minecraft's textures, in the same
 * places (so nothing else on the HUD moves).
 * - Hotbar: one panel in the style (glass in Clean Style), soft slots, a selection that slides from slot to slot with a
 * glow in the accent colour, the offhand item in its own box.
 * - Hearts &amp; hunger (optional): two smooth bars with the numbers in them - health red (green when poisoned, grey
 * with wither, icy when freezing) with absorption in gold and a light "just lost" part that drains after a hit;
 * hunger orange with saturation as a thin line.
 * Vanilla Style keeps Minecraft's own hotbar (that is what the style is for).
 */
public class Hotbar extends Module {
    private final Setting<String> bars = addSetting(Setting.mode("Hearts & Hunger", "Clean Bars", "Clean Bars", "Minecraft"),
        "Clean Bars: health and hunger as two smooth bars with numbers. Minecraft: the normal hearts and drumsticks");
    private final Setting<Boolean> numbers = addSetting(new Setting<>("Numbers", true).showIf(() -> this.bars.getValue().equals("Clean Bars")),
        "Health and hunger numbers in the bars");
    private final Setting<Boolean> slotNumbers = addSetting(new Setting<>("Slot Numbers", false),
        "Small 1-9 in the corner of each slot");
    private final Setting<Boolean> slide = addSetting(new Setting<>("Smooth Slide", true),
        "The selection glides to the slot you pick instead of jumping");
    private final Setting<Integer> opacity = addSetting(new Setting<>("Background %", 70, 0, 100, 5),
        "How solid the hotbar panel is (0 = only the slots)");

    private float selX = -1;
    private long lastFrame;
    private float shownHealth = -1;
    private float lostHealth = -1;
    private float prevHealth = -1;
    private long lostAt;

    public Hotbar() {
        super("Hotbar", "The hotbar, hearts and hunger drawn in your style, with a smooth sliding selection",
            ModuleCategory.RENDER);
    }

    /** The client draws the hotbar (not in Vanilla Style, not in spectator mode). */
    public boolean drawsHotbar() {
        return !VanillaStyle.on() && mc.player != null && !mc.player.isSpectator();
    }

    /** The client draws hearts and hunger as bars. */
    public boolean drawsBars() {
        return drawsHotbar() && bars.getValue().equals("Clean Bars") && mc.gameMode != null && mc.gameMode.canHurtPlayer();
    }

    // ------------------------------------------------------------------ look

    private int radius(int h) {
        return Math.min(h / 2, Math.max(2, Theme.R_SURFACE));
    }

    /** The panel behind the slots / a bar: glass in Clean Style, the theme's panel otherwise. */
    private void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
        float a = opacity.getValue() / 100f;
        if (a <= 0.01f) return;
        if (CleanStyle.on()) {
            int glass = Theme.mix(Theme.PANEL, Theme.accent(), 0.08f + com.relaxedqol.client.module.modules.client.ClickGUI.glassTint() * 0.4f);
            Render2D.roundRect(g, x, y, w, h, r, Theme.withAlpha(glass, 0.62f * a));
            if (Theme.SHEEN > 0.01f) {
                Render2D.roundRect(g, x + 1, y + 1, w - 2, h / 2, Math.max(0, r - 1),
                    Theme.withAlpha(0xFFFFFFFF, Math.min(0.14f, 0.06f * Theme.SHEEN) * a));
            }
            Render2D.roundOutline(g, x, y, w, h, r, Theme.withAlpha(0xFFFFFFFF, 0.22f * a));
            return;
        }
        Render2D.roundRect(g, x, y, w, h, r, Theme.withAlpha(Theme.panel(), 0.85f * a));
        Render2D.roundOutline(g, x, y, w, h, r, Theme.fade(Theme.BORDER, Math.max(0.4f, a)));
    }

    // ------------------------------------------------------------------ hotbar

    public void renderHotbar(GuiGraphicsExtractor g) {
        Player p = mc.player;
        if (p == null) return;
        int cx = g.guiWidth() / 2;
        int x = cx - 91;
        int y = g.guiHeight() - 22;
        int r = radius(22);
        int accent = Theme.accent();
        panel(g, x, y, 182, 22, r);
        int sr = Math.max(1, Math.min(r - 1, Theme.R_CONTROL + 1));
        for (int i = 0; i < 9; i++) {
            Render2D.roundRect(g, x + 2 + i * 20, y + 2, 18, 18, sr, 0x12FFFFFF);
        }
        // The selection glides to the picked slot
        int sel = p.getInventory().getSelectedSlot();
        float target = x + 1 + sel * 20;
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 1f : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        if (selX < 0 || !slide.getValue() || Theme.ANIM <= 0.01f || Math.abs(selX - target) > 400) selX = target;
        else selX += (target - selX) * Math.min(1f, dt * 18f * Theme.ANIM);
        int sx = Math.round(selX);
        Render2D.glow(g, sx, y + 1, 20, 20, accent, 1f);
        Render2D.roundRect(g, sx, y + 1, 20, 20, sr + 1, Theme.withAlpha(accent, CleanStyle.on() ? 0.22f : 0.28f));
        Render2D.roundOutline(g, sx, y + 1, 20, 20, sr + 1, Theme.withAlpha(accent, 0.9f));
        // Items
        for (int i = 0; i < 9; i++) {
            ItemStack stack = p.getInventory().getItem(i);
            int ix = x + 3 + i * 20;
            if (!stack.isEmpty()) {
                g.item(stack, ix, y + 3);
                g.itemDecorations(mc.font, stack, ix, y + 3);
            }
            if (slotNumbers.getValue()) {
                g.pose().pushMatrix();
                g.pose().translate(x + 4 + i * 20, y + 3);
                g.pose().scale(0.5f, 0.5f);
                g.text(mc.font, String.valueOf(i + 1), 0, 0, i == sel ? 0xFFFFFFFF : 0x90FFFFFF, false);
                g.pose().popMatrix();
            }
        }
        // Offhand in its own box on the side of the off hand
        ItemStack off = p.getOffhandItem();
        if (!off.isEmpty()) {
            boolean left = p.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
            int ox = left ? x - 26 : x + 182 + 4;
            panel(g, ox, y, 22, 22, r);
            Render2D.roundRect(g, ox + 2, y + 2, 18, 18, sr, 0x12FFFFFF);
            g.item(off, ox + 3, y + 3);
            g.itemDecorations(mc.font, off, ox + 3, y + 3);
        }
    }

    // ------------------------------------------------------------------ hearts & hunger

    private static final int H = 9;

    public void renderHealth(GuiGraphicsExtractor g) {
        Player p = mc.player;
        if (p == null) return;
        int x = g.guiWidth() / 2 - 91;
        int y = g.guiHeight() - 39;
        int w = 81;
        float max = Math.max(1f, p.getMaxHealth());
        float hp = Math.max(0f, p.getHealth());
        float abs = p.getAbsorptionAmount();
        // Smooth bar, and a light "just lost" part that drains a moment after a hit
        long now = System.currentTimeMillis();
        if (shownHealth < 0) shownHealth = lostHealth = prevHealth = hp;
        if (hp < prevHealth - 0.01f) lostAt = now;
        prevHealth = hp;
        shownHealth += (hp - shownHealth) * 0.3f;
        if (hp >= lostHealth) lostHealth = hp;
        else if (now - lostAt > 450) lostHealth += (hp - lostHealth) * 0.12f;
        int color = 0xFFE5484D;
        if (p.hasEffect(MobEffects.POISON)) color = 0xFF7CB342;
        if (p.hasEffect(MobEffects.WITHER)) color = 0xFF5B5B5B;
        if (p.isFullyFrozen()) color = 0xFF8FD3FF;
        int r = Math.min(4, Math.max(1, Theme.R_CONTROL));
        track(g, x, y, w, r);
        int lost = Math.round(w * Math.min(1f, lostHealth / max));
        if (lost > 0) Render2D.roundRect(g, x, y, lost, H, r, 0x60FFFFFF);
        int fill = Math.round(w * Math.min(1f, shownHealth / max));
        if (fill > 0) Render2D.roundRect(g, x, y, fill, H, r, color);
        if (fill > 2) g.fill(x + 1, y + 1, x + fill - 1, y + 2, 0x30FFFFFF);
        // Absorption: gold, over the end of the bar
        if (abs > 0) {
            int aw = Math.round(w * Math.min(1f, abs / max));
            Render2D.roundRect(g, x + w - aw, y + H - 3, aw, 3, 1, 0xFFF2C14E);
        }
        if (numbers.getValue()) {
            String t = fmt(hp) + (abs > 0 ? " +" + fmt(abs) : "");
            g.text(mc.font, t, x + 4, y + 1, 0xFFFFFFFF, true);
        }
    }

    public void renderFood(GuiGraphicsExtractor g) {
        Player p = mc.player;
        if (p == null) return;
        // On a horse etc. Minecraft shows its hearts there instead
        if (p.getVehicle() instanceof LivingEntity) return;
        int w = 81;
        int x = g.guiWidth() / 2 + 91 - w;
        int y = g.guiHeight() - 39;
        int food = p.getFoodData().getFoodLevel();
        float sat = p.getFoodData().getSaturationLevel();
        int color = p.hasEffect(MobEffects.HUNGER) ? 0xFF8D9B3A : 0xFFE8A13A;
        int r = Math.min(4, Math.max(1, Theme.R_CONTROL));
        track(g, x, y, w, r);
        // Hunger empties from the left, like the drumsticks
        int fill = Math.round(w * Math.min(1f, food / 20f));
        if (fill > 0) Render2D.roundRect(g, x + w - fill, y, fill, H, r, color);
        if (fill > 2) g.fill(x + w - fill + 1, y + 1, x + w - 1, y + 2, 0x30FFFFFF);
        int sw = Math.round(w * Math.min(1f, sat / 20f));
        if (sw > 0) Render2D.roundRect(g, x + w - sw, y + H - 2, sw, 2, 1, 0xFFFFE08A);
        if (numbers.getValue()) {
            String t = String.valueOf(food);
            g.text(mc.font, t, x + w - 4 - mc.font.width(t), y + 1, 0xFFFFFFFF, true);
        }
    }

    private void track(GuiGraphicsExtractor g, int x, int y, int w, int r) {
        if (CleanStyle.on()) {
            Render2D.roundRect(g, x, y, w, H, r, 0x50000000);
            Render2D.roundOutline(g, x, y, w, H, r, 0x30FFFFFF);
        } else {
            Render2D.roundRect(g, x, y, w, H, r, Theme.withAlpha(Theme.panel(), 0.8f));
            Render2D.roundOutline(g, x, y, w, H, r, Theme.BORDER);
        }
    }

    private static String fmt(float v) {
        float rounded = Math.round(v * 2) / 2f;
        return rounded == (int) rounded ? String.valueOf((int) rounded) : String.valueOf(rounded);
    }
}
