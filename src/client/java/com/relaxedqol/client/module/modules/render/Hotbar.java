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

    /**
     * The panel behind the slots: frosted glass in Clean Style (see-through, a light frost, a shine fading down from
     * the top, a thin bright edge), the theme's panel otherwise (LoverFella's navy while that is on).
     */
    private void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
        float a = opacity.getValue() / 100f;
        if (a <= 0.01f) return;
        if (CleanStyle.on()) {
            int tint = Theme.mix(0xFF0A0B0E, Theme.accent(), com.relaxedqol.client.module.modules.client.ClickGUI.glassTint() * 0.5f);
            Render2D.roundRect(g, x, y, w, h, r, Theme.withAlpha(tint, 0.38f * a));
            Render2D.roundRect(g, x, y, w, h, r, Theme.withAlpha(0xFFFFFFFF, 0.06f * a));
            if (Theme.SHEEN > 0.01f) {
                int top = Theme.withAlpha(0xFFFFFFFF, Math.min(0.22f, 0.10f * Theme.SHEEN) * a);
                Render2D.clipRounded(g, x, y, w, h, r, () -> g.fillGradient(x, y, x + w, y + h * 2 / 3, top, 0x00FFFFFF));
            }
            Render2D.roundOutline(g, x, y, w, h, r, Theme.withAlpha(0xFFFFFFFF, 0.30f * Math.max(0.5f, a)));
            return;
        }
        Render2D.roundRect(g, x, y, w, h, r, Theme.withAlpha(Theme.panel(), 0.80f * a));
        Render2D.clipRounded(g, x, y, w, h, r, () -> g.fill(x, y + 1, x + w, y + 2, Theme.withAlpha(0xFFFFFFFF, 0.05f * a)));
        Render2D.roundOutline(g, x, y, w, h, r, Theme.fade(Theme.BORDER, Math.max(0.5f, a)));
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
        boolean clean = CleanStyle.on();
        panel(g, x, y, 182, 22, r);
        // Quiet dividers between the slots instead of boxes
        for (int i = 1; i < 9; i++) g.fill(x + 1 + i * 20, y + 6, x + 2 + i * 20, y + 16, clean ? 0x18FFFFFF : 0x12FFFFFF);
        // The selection glides to the picked slot
        int sel = p.getInventory().getSelectedSlot();
        float target = x + 1 + sel * 20;
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 1f : Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        if (selX < 0 || !slide.getValue() || Theme.ANIM <= 0.01f || Math.abs(selX - target) > 400) selX = target;
        else selX += (target - selX) * Math.min(1f, dt * 18f * Theme.ANIM);
        int sx = Math.round(selX);
        int sr = Math.max(2, Math.min(r - 1, Theme.R_CONTROL + 2));
        Render2D.glow(g, sx + 1, y + 2, 18, 18, accent, 0.7f);
        if (clean) {
            // A brighter pane of glass with a thin accent edge and a short accent line at the bottom
            Render2D.roundRect(g, sx + 1, y + 2, 18, 18, sr, Theme.withAlpha(Theme.mix(0xFFFFFFFF, accent, 0.35f), 0.20f));
            Render2D.roundOutline(g, sx + 1, y + 2, 18, 18, sr, Theme.withAlpha(Theme.mix(0xFFFFFFFF, accent, 0.6f), 0.75f));
        } else {
            Render2D.roundRect(g, sx + 1, y + 2, 18, 18, sr, Theme.withAlpha(accent, 0.22f));
            Render2D.roundOutline(g, sx + 1, y + 2, 18, 18, sr, Theme.withAlpha(accent, 0.85f));
        }
        Render2D.roundRect(g, sx + 7, y + 19, 6, 1, 0, accent);
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
                g.pose().translate(x + 3 + i * 20, y + 2.5f);
                g.pose().scale(0.5f, 0.5f);
                g.text(mc.font, String.valueOf(i + 1), 0, 0, i == sel ? 0xD0FFFFFF : 0x60FFFFFF, false);
                g.pose().popMatrix();
            }
        }
        // Offhand in its own small pane on the side of the off hand
        ItemStack off = p.getOffhandItem();
        if (!off.isEmpty()) {
            boolean left = p.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
            int ox = left ? x - 26 : x + 182 + 4;
            panel(g, ox, y, 22, 22, r);
            g.item(off, ox + 3, y + 3);
            g.itemDecorations(mc.font, off, ox + 3, y + 3);
        }
    }

    // ------------------------------------------------------------------ hearts & hunger

    /** Thin bars (4 px) with the number beside them, on the hearts' row - clear of the experience bar below. */
    private static final int BAR_H = 4;

    private int textY(GuiGraphicsExtractor g) {
        return g.guiHeight() - 41;
    }

    private int barY(GuiGraphicsExtractor g) {
        return g.guiHeight() - 39;
    }

    public void renderHealth(GuiGraphicsExtractor g) {
        Player p = mc.player;
        if (p == null) return;
        int x0 = g.guiWidth() / 2 - 91;
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
        int light = 0xFFFF6B74;
        int dark = 0xFFD8323E;
        if (p.hasEffect(MobEffects.POISON)) { light = 0xFFA5D66B; dark = 0xFF5E9A2E; }
        if (p.hasEffect(MobEffects.WITHER)) { light = 0xFF8A8A8A; dark = 0xFF474747; }
        if (p.isFullyFrozen()) { light = 0xFFBFEAFF; dark = 0xFF6CBDE8; }
        // The number first, then the bar in the room that is left
        int tx = x0;
        if (numbers.getValue()) {
            String t = fmt(hp);
            g.text(mc.font, t, tx, textY(g), 0xFFFFFFFF, true);
            tx += mc.font.width(t);
            if (abs > 0) {
                String a = "+" + fmt(abs);
                g.text(mc.font, a, tx + 1, textY(g), 0xFFF5CF5B, true);
                tx += mc.font.width(a) + 1;
            }
            tx += 4;
        }
        int w = x0 + 81 - tx;
        if (w < 12) return;
        int y = barY(g);
        track(g, tx, y, w);
        int lost = Math.round(w * Math.min(1f, lostHealth / max));
        if (lost > 0) Render2D.roundRect(g, tx, y, lost, BAR_H, BAR_H / 2, 0x80FFFFFF);
        fill(g, tx, y, Math.round(w * Math.min(1f, shownHealth / max)), light, dark);
        if (abs > 0) {
            int aw = Math.max(2, Math.round(w * Math.min(1f, abs / max)));
            fill(g, tx + w - aw, y, aw, 0xFFFFE08A, 0xFFE9A92B);
        }
    }

    public void renderFood(GuiGraphicsExtractor g) {
        Player p = mc.player;
        if (p == null) return;
        // On a horse etc. Minecraft shows its hearts there instead
        if (p.getVehicle() instanceof LivingEntity) return;
        int right = g.guiWidth() / 2 + 91;
        int food = p.getFoodData().getFoodLevel();
        float sat = p.getFoodData().getSaturationLevel();
        boolean hungry = p.hasEffect(MobEffects.HUNGER);
        int light = hungry ? 0xFFC2D16B : 0xFFFFC66B;
        int dark = hungry ? 0xFF7E8F2E : 0xFFE58A2A;
        int end = right;
        if (numbers.getValue()) {
            String t = String.valueOf(food);
            g.text(mc.font, t, right - mc.font.width(t), textY(g), 0xFFFFFFFF, true);
            end -= mc.font.width(t) + 4;
        }
        int x = g.guiWidth() / 2 + 10;
        int w = end - x;
        if (w < 12) return;
        int y = barY(g);
        track(g, x, y, w);
        // Hunger empties from the left, like the drumsticks
        int fw = Math.round(w * Math.min(1f, food / 20f));
        fill(g, x + w - fw, y, fw, light, dark);
        // Saturation: a lighter part over the end of the bar
        int sw = Math.min(fw, Math.round(w * Math.min(1f, sat / 20f)));
        if (sw > 1) Render2D.roundRect(g, x + w - sw, y, sw, BAR_H, BAR_H / 2, 0x55FFF6D8);
    }

    private void track(GuiGraphicsExtractor g, int x, int y, int w) {
        if (CleanStyle.on()) {
            Render2D.roundRect(g, x, y, w, BAR_H, BAR_H / 2, 0x55000000);
            Render2D.roundRect(g, x, y, w, BAR_H, BAR_H / 2, 0x10FFFFFF);
        } else {
            Render2D.roundRect(g, x, y, w, BAR_H, BAR_H / 2, Theme.withAlpha(Theme.panel(), 0.85f));
        }
    }

    /** A filled part with a soft left-to-right shade and a little light on its top edge. */
    private static void fill(GuiGraphicsExtractor g, int x, int y, int w, int light, int dark) {
        if (w <= 0) return;
        Render2D.clipRounded(g, x, y, w, BAR_H, BAR_H / 2, () -> {
            Render2D.horizontalGradient(g, x, y, w, BAR_H, dark, light);
            g.fill(x, y, x + w, y + 1, 0x40FFFFFF);
        });
    }

    private static String fmt(float v) {
        float rounded = Math.round(v * 2) / 2f;
        return rounded == (int) rounded ? String.valueOf((int) rounded) : String.valueOf(rounded);
    }
}
