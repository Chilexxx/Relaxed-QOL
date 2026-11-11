package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.gui.CleanStyle;
import com.relaxedqol.client.gui.GlassIcons;
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
 * - Hearts &amp; hunger (optional): Minecraft's layout (10 hearts, 10 drumsticks, extra rows for more health) with
 * the client's own icons - glossy see-through glass in Clean Style, clean solid icons otherwise. Poison, wither,
 * freezing, absorption and the hunger effect have their own colours; hearts you just lost light up for a moment.
 * Vanilla Style keeps Minecraft's own hotbar (that is what the style is for).
 */
public class Hotbar extends Module {
    private final Setting<String> bars = addSetting(Setting.mode("Hearts & Hunger", "Styled", "Styled", "Minecraft"),
        "Styled: the normal hearts and drumsticks layout with the client's icons (glossy glass in Clean Style)."
            + " Minecraft: Minecraft's own icons");
    private final Setting<String> iconLook = addSetting(Setting.mode("Icon Look", "Glossy", "Glossy", "Liquid Glass")
            .showIf(() -> this.bars.getValue().equals("Styled")),
        "Glossy: shiny coloured icons. Liquid Glass: clear glass icons - the colour only tints the glass, with a bright"
            + " highlight on top and light gathering at the bottom");
    private final Setting<Boolean> slotNumbers = addSetting(new Setting<>("Slot Numbers", false),
        "Small 1-9 in the corner of each slot");
    private final Setting<Boolean> slide = addSetting(new Setting<>("Smooth Slide", true),
        "The selection glides to the slot you pick instead of jumping");
    private final Setting<Integer> opacity = addSetting(new Setting<>("Background %", 70, 0, 100, 5),
        "How solid the hotbar panel is (0 = only the slots)");

    private float selX = -1;
    private long lastFrame;
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

    /** The client draws the hearts and hunger icons. */
    public boolean drawsBars() {
        return drawsHotbar() && bars.getValue().equals("Styled") && mc.gameMode != null && mc.gameMode.canHurtPlayer();
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
            // Frosted glass: a light veil over the world (not a dark box), a breath of the accent, the glossy finish
            float tint = com.relaxedqol.client.module.modules.client.ClickGUI.glassTint();
            Render2D.roundRect(g, x, y, w, h, r, Theme.withAlpha(0xFF000000, 0.20f * a));
            Render2D.roundRect(g, x, y, w, h, r, Theme.withAlpha(0xFFFFFFFF, 0.09f * a));
            if (tint > 0.01f) Render2D.roundRect(g, x, y, w, h, r, Theme.withAlpha(Theme.accent(), Math.min(0.14f, tint * 0.2f) * a));
            CleanStyle.gloss(g, x, y, w, h, r);
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
        boolean clean = CleanStyle.on();
        // Clean Style: a fully rounded glass bar; otherwise the theme's roundness
        int r = clean ? 11 : radius(22);
        int accent = Theme.accent();
        panel(g, x, y, 182, 22, r);
        // Slots: barely-there wells, so the bar reads as one clean piece and the items stand out
        int tr = clean ? 7 : Math.max(2, Math.min(r - 1, Theme.R_CONTROL + 1));
        for (int i = 0; i < 9; i++) {
            Render2D.roundRect(g, x + 2 + i * 20, y + 2, 18, 18, tr, clean ? 0x0CFFFFFF : 0x0AFFFFFF);
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
        int sr = tr + 1;
        if (clean) {
            // A lighter pane of glass: soft fill, a fine bright rim, light on its top half
            Render2D.glow(g, sx, y + 1, 20, 20, accent, 0.35f);
            Render2D.roundRect(g, sx, y + 1, 20, 20, sr, Theme.withAlpha(Theme.mix(0xFFFFFFFF, accent, 0.20f), 0.16f));
            Render2D.clipRounded(g, sx, y + 1, 20, 20, sr, () -> g.fillGradient(sx, y + 1, sx + 20, y + 10, 0x2EFFFFFF, 0x00FFFFFF));
            Render2D.roundOutline(g, sx, y + 1, 20, 20, sr, 0x80FFFFFF);
        } else {
            Render2D.glow(g, sx + 1, y + 2, 18, 18, accent, 0.5f);
            Render2D.roundRect(g, sx + 1, y + 2, 18, 18, sr, Theme.withAlpha(accent, 0.20f));
            Render2D.roundOutline(g, sx + 1, y + 2, 18, 18, sr, Theme.withAlpha(accent, 0.80f));
        }
        // A small accent pill under the picked slot
        Render2D.smoothCircle(g, sx + 10f, y + 21f, 0.9f, accent, 0.8f);
        Render2D.roundRect(g, sx + 7, y + 20, 6, 1, 0, Theme.withAlpha(accent, 0.9f));
        // Items, with a neater stack count
        for (int i = 0; i < 9; i++) {
            ItemStack stack = p.getInventory().getItem(i);
            int ix = x + 3 + i * 20;
            if (!stack.isEmpty()) drawItem(g, stack, ix, y + 3);
            if (slotNumbers.getValue()) {
                g.pose().pushMatrix();
                g.pose().translate(x + 4 + i * 20, y + 3);
                g.pose().scale(0.5f, 0.5f);
                g.text(mc.font, String.valueOf(i + 1), 0, 0, i == sel ? 0xB0FFFFFF : 0x48FFFFFF, false);
                g.pose().popMatrix();
            }
        }
        // Offhand in its own small pane on the side of the off hand
        ItemStack off = p.getOffhandItem();
        if (!off.isEmpty()) {
            boolean left = p.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
            int ox = left ? x - 26 : x + 182 + 4;
            panel(g, ox, y, 22, 22, clean ? 11 : r);
            drawItem(g, off, ox + 3, y + 3);
        }
    }

    /**
     * An item with its durability bar and cooldown as Minecraft draws them, but the stack count smaller and tucked
     * into the corner (Minecraft's big "64" covers half the item).
     */
    private void drawItem(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        g.item(stack, x, y);
        int count = stack.getCount();
        g.itemDecorations(mc.font, count > 1 ? stack.copyWithCount(1) : stack, x, y);
        if (count > 1) {
            String c = String.valueOf(count);
            float sc = 0.75f;
            g.pose().pushMatrix();
            g.pose().translate(x + 17 - mc.font.width(c) * sc, y + 16 - 8 * sc);
            g.pose().scale(sc, sc);
            g.text(mc.font, c, 0, 0, 0xFFFFFFFF, true);
            g.pose().popMatrix();
        }
    }

    // ------------------------------------------------------------------ hearts & hunger

    /** Icon shapes (9 x 8): # = filled, b = bone (drumstick handle). */
    private static final String[] HEART = {
        ".###.###.",
        "#########",
        "#########",
        "#########",
        ".#######.",
        "..#####..",
        "...###...",
        "....#....",
    };
    private static final String[] FOOD = {
        "....###..",
        "...#####.",
        "..#######",
        "..######.",
        "..#####..",
        ".bb###...",
        ".b.......",
        "bb.......",
    };

    /** Each icon pixel: 0 = outside, 1 = edge, 2 = inside, 3 = bone (worked out once). */
    private static final int[][] HEART_MAP = map(HEART);
    private static final int[][] FOOD_MAP = map(FOOD);

    private static int[][] map(String[] rows) {
        int h = rows.length;
        int w = rows[0].length();
        int[][] m = new int[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                char c = rows[y].charAt(x);
                if (c == '.') continue;
                if (c == 'b') {
                    m[y][x] = 3;
                    continue;
                }
                boolean edge = false;
                int[][] n = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] d : n) {
                    int nx = x + d[0];
                    int ny = y + d[1];
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h || rows[ny].charAt(nx) == '.') edge = true;
                }
                m[y][x] = edge ? 1 : 2;
            }
        }
        return m;
    }

    /**
     * One icon at (x, y). fill: how much of it is filled (0, 0.5 = left / right half, 1), fromRight = the half that
     * fills is the right one (drumsticks). light / dark = the colour from top to bottom; flash = just lost (white).
     */
    private static void icon(GuiGraphicsExtractor g, int[][] m, int x, int y, float fill, boolean fromRight, int light,
                             int dark, boolean flash) {
        boolean clean = CleanStyle.on();
        int h = m.length;
        int w = m[0].length;
        int split = fill >= 1f ? w : fill <= 0f ? 0 : 5;
        for (int row = 0; row < h; row++) {
            float t = row / (float) (h - 1);
            int body = Theme.mix(light, dark, t);
            // Drawn in runs of the same colour (a few fills per row instead of one per pixel)
            int runStart = -1;
            int runColor = 0;
            for (int col = 0; col <= w; col++) {
                int color = 0;
                if (col < w) {
                    int v = m[row][col];
                    boolean filled = fromRight ? col >= w - split : col < split;
                    if (v == 1) {
                        // The edge: soft and see-through on glass, a crisp dark line otherwise
                        color = clean ? (filled ? Theme.withAlpha(Theme.mix(dark, 0xFF000000, 0.45f), 0.85f) : 0x80FFFFFF)
                            : 0xFF141414;
                    } else if (v == 2 || v == 3) {
                        if (!filled) color = clean ? 0x40000000 : 0xFF2B2B30;
                        else if (flash) color = 0xE6FFFFFF;
                        else if (v == 3) color = clean ? 0xE6F5EFE0 : 0xFFEDE6D3;
                        else color = clean ? Theme.withAlpha(body, 0.88f) : body;
                        // Gloss: a light spot top-left, like light on glass
                        if (filled && v == 2 && row <= 2 && col <= 3 && (row + col) <= 4 && (row + col) >= 2) {
                            color = Theme.mix(color, 0xFFFFFFFF, clean ? 0.65f : 0.45f);
                        }
                    }
                }
                if (color != runColor || col == w) {
                    if (runStart >= 0 && runColor != 0) g.fill(x + runStart, y + row, x + col, y + row + 1, runColor);
                    runStart = col;
                    runColor = color;
                }
            }
        }
    }

    /** The smooth glass icon, when it could be made. */
    private static boolean smooth(GuiGraphicsExtractor g, GlassIcons.Icon icon, int x, int y) {
        return GlassIcons.available() && GlassIcons.draw(g, icon, x, y);
    }

    private static GlassIcons.Icon heartIcon(Player p, float fill, boolean flash) {
        if (fill <= 0f) return GlassIcons.Icon.HEART_EMPTY;
        boolean full = fill >= 1f;
        if (flash) return full ? GlassIcons.Icon.FLASH_FULL : GlassIcons.Icon.FLASH_HALF;
        if (p.hasEffect(MobEffects.WITHER)) return full ? GlassIcons.Icon.WITHER_FULL : GlassIcons.Icon.WITHER_HALF;
        if (p.hasEffect(MobEffects.POISON)) return full ? GlassIcons.Icon.POISON_FULL : GlassIcons.Icon.POISON_HALF;
        if (p.isFullyFrozen()) return full ? GlassIcons.Icon.FROZEN_FULL : GlassIcons.Icon.FROZEN_HALF;
        return full ? GlassIcons.Icon.HEART_FULL : GlassIcons.Icon.HEART_HALF;
    }

    /** Armor in Minecraft's place (above the hearts), with the client's icons. Nothing when you wear none. */
    public void renderArmor(GuiGraphicsExtractor g) {
        Player p = mc.player;
        if (p == null) return;
        GlassIcons.setLiquid(iconLook.getValue().equals("Liquid Glass"));
        int armor = p.getArmorValue();
        if (armor <= 0) return;
        int left = g.guiWidth() / 2 - 91;
        int y = g.guiHeight() - net.fabricmc.fabric.api.client.rendering.v1.hud.HudStatusBarHeightRegistry.getHeight(
            net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.ARMOR_BAR);
        for (int i = 0; i < 10; i++) {
            int v = armor - i * 2;
            GlassIcons.Icon ic = v >= 2 ? GlassIcons.Icon.ARMOR_FULL : v == 1 ? GlassIcons.Icon.ARMOR_HALF : GlassIcons.Icon.ARMOR_EMPTY;
            if (!smooth(g, ic, left + i * 8, y)) return;
        }
    }

    public void renderHealth(GuiGraphicsExtractor g) {
        Player p = mc.player;
        if (p == null) return;
        GlassIcons.setLiquid(iconLook.getValue().equals("Liquid Glass"));
        int left = g.guiWidth() / 2 - 91;
        int top = g.guiHeight() - 39;
        float max = Math.max(1f, p.getMaxHealth());
        float hp = Math.max(0f, p.getHealth());
        float abs = p.getAbsorptionAmount();
        // Hearts you just lost light up for a moment
        long now = System.currentTimeMillis();
        if (lostHealth < 0) lostHealth = prevHealth = hp;
        if (hp < prevHealth - 0.01f) lostAt = now;
        prevHealth = hp;
        if (hp >= lostHealth || now - lostAt > 600) lostHealth = hp;
        int light = 0xFFFF7A84;
        int dark = 0xFFC81E32;
        if (p.hasEffect(MobEffects.POISON)) { light = 0xFFB6E07A; dark = 0xFF4F8A25; }
        if (p.hasEffect(MobEffects.WITHER)) { light = 0xFF8C8C8C; dark = 0xFF2E2E2E; }
        if (p.isFullyFrozen()) { light = 0xFFD2F1FF; dark = 0xFF5FB3E3; }
        int hearts = (int) Math.ceil(max / 2f);
        int absHearts = (int) Math.ceil(abs / 2f);
        int total = hearts + absHearts;
        int rows = (int) Math.ceil(total / 10f);
        int rowH = Math.max(10 - (rows - 2), 3);
        boolean shake = hp <= 4;
        long tick = now / 50;
        for (int i = total - 1; i >= 0; i--) {
            int x = left + (i % 10) * 8;
            int y = top - (i / 10) * rowH;
            if (shake) y += (int) ((tick * 31 + i * 17) % 3) - 1;
            if (i < hearts) {
                float v = hp - i * 2;
                float fill = v >= 2 ? 1f : v >= 1 ? 0.5f : 0f;
                float lv = lostHealth - i * 2;
                boolean flash = fill < 1f && lv > Math.max(0f, v);
                float shown = flash ? (lv >= 2 ? 1f : 0.5f) : fill;
                if (!smooth(g, heartIcon(p, shown, flash), x, y)) {
                    icon(g, HEART_MAP, x, y, shown, false, light, dark, flash);
                }
            } else {
                float v = abs - (i - hearts) * 2;
                GlassIcons.Icon gold = v >= 2 ? GlassIcons.Icon.GOLD_FULL : GlassIcons.Icon.GOLD_HALF;
                if (!smooth(g, gold, x, y)) icon(g, HEART_MAP, x, y, v >= 2 ? 1f : 0.5f, false, 0xFFFFE590, 0xFFD69A12, false);
            }
        }
    }

    public void renderFood(GuiGraphicsExtractor g) {
        Player p = mc.player;
        if (p == null) return;
        GlassIcons.setLiquid(iconLook.getValue().equals("Liquid Glass"));
        // On a horse etc. Minecraft shows its hearts there instead
        if (p.getVehicle() instanceof LivingEntity) return;
        int right = g.guiWidth() / 2 + 91;
        int top = g.guiHeight() - 39;
        int food = p.getFoodData().getFoodLevel();
        float sat = p.getFoodData().getSaturationLevel();
        boolean hungry = p.hasEffect(MobEffects.HUNGER);
        int light = hungry ? 0xFFC9DA7A : 0xFFFFC27A;
        int dark = hungry ? 0xFF5E7524 : 0xFFC8622A;
        long tick = System.currentTimeMillis() / 50;
        for (int i = 0; i < 10; i++) {
            int x = right - i * 8 - 9;
            int y = top;
            // Like Minecraft: the drumsticks jitter when you have no saturation left
            if (sat <= 0f && tick % (food * 3L + 1) == 0) y += (int) ((tick * 7 + i * 13) % 3) - 1;
            int v = food - i * 2;
            float fill = v >= 2 ? 1f : v == 1 ? 0.5f : 0f;
            GlassIcons.Icon ic = fill <= 0f ? GlassIcons.Icon.FOOD_EMPTY
                : hungry ? (fill >= 1f ? GlassIcons.Icon.HUNGER_FULL : GlassIcons.Icon.HUNGER_HALF)
                : (fill >= 1f ? GlassIcons.Icon.FOOD_FULL : GlassIcons.Icon.FOOD_HALF);
            if (!smooth(g, ic, x, y)) icon(g, FOOD_MAP, x, y, fill, true, light, dark, false);
        }
    }
}
