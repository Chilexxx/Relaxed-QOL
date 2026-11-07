package com.relaxedqol.client.module.modules.player;

import com.relaxedqol.client.gui.Render2D;
import com.relaxedqol.client.gui.Theme;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.mixin.AbstractContainerScreenAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * InvTweaks (nothing is ever clicked for you). Shulker box tools in every
 * inventory / chest:
 * - Shulker Preview: hover a filled shulker box to see what is inside (Hover, or only while holding Shift).
 *   Coloured in the box's own colour, with how many slots are used. Compact: every item once, with its total.
 * - (the fill bars on shulker items were removed.)
 * - Highlight Matches: hover any item and every shulker box that contains it lights up.
 */
public class InventoryTweaks extends Module {
    private final Setting<String> preview = addSetting(Setting.mode("Shulker Preview", "Hover", "Hover", "Hold Key", "Off"),
        "Hover (always): the contents show as soon as you point at a filled shulker box. Hold Key: only while you hold"
            + " the Preview Key. Off: no preview");
    private final com.relaxedqol.client.setting.KeySetting previewKey = addSetting(
        new com.relaxedqol.client.setting.KeySetting("Preview Key", 340, false),
        "With Shulker Preview on Hold Key: hold this to see inside (default Left Shift)");
    private final Setting<Boolean> compact = addSetting(new Setting<>("Compact Preview", false),
        "Show each kind of item once with its total (e.g. 1,728 cobblestone) instead of the 27 slots");
    private final Setting<Boolean> tint = addSetting(new Setting<>("Shulker Colour", true),
        "The preview frame takes the colour of the shulker box (red box = red frame)");
    private final Setting<Integer> previewScale = addSetting(new Setting<>("Preview Size %", 100, 60, 150, 5),
        "How big the contents preview is drawn");
    /** The fill bar on shulker items is gone (hidden setting kept so old configs load). */
    private final Setting<Boolean> fillBars = addSetting(new Setting<>("Fill Bars", false).hide());
    /** Vanilla = looks like the game's own shulker box screen; Dark = a clean dark card. */
    private final Setting<String> previewLook = addSetting(Setting.mode("Preview Look", "Card", "Card", "Vanilla"),
        "Card: a clean card in the box's colour with its icon and name. Vanilla: like the game's own shulker box window");
    /** The Vanilla look in the plain grey of your inventory instead of the box / client colour. */
    private final Setting<Boolean> vanillaPlain = addSetting(new Setting<>("Inventory Colour", true)
        .showIf(() -> this.previewLook.getValue().equals("Vanilla")),
        "Preview Look Vanilla: the preview has the same plain grey as your inventory (no box or client colour)");
    private final Setting<Boolean> highlight = addSetting(new Setting<>("Highlight Matches", true),
        "Hover any item and every shulker box in the screen that has that item inside gets an outline");

    private static final int SLOTS = 27;

    /** Dye colours of the 16 shulker boxes, by item id prefix. */
    private static final Map<String, Integer> DYES = new LinkedHashMap<>();
    static {
        DYES.put("white", 0xFFF9FAFB);
        DYES.put("orange", 0xFFF97316);
        DYES.put("magenta", 0xFFD946EF);
        DYES.put("light_blue", 0xFF7DD3FC);
        DYES.put("yellow", 0xFFFACC15);
        DYES.put("lime", 0xFF84CC16);
        DYES.put("pink", 0xFFF9A8D4);
        DYES.put("gray", 0xFF6B7280);
        DYES.put("light_gray", 0xFFD1D5DB);
        DYES.put("cyan", 0xFF06B6D4);
        DYES.put("purple", 0xFFA855F7);
        DYES.put("blue", 0xFF3B82F6);
        DYES.put("brown", 0xFFA16207);
        DYES.put("green", 0xFF16A34A);
        DYES.put("red", 0xFFEF4444);
        DYES.put("black", 0xFF3F3F46);
    }

    public InventoryTweaks() {
        super("InvTweaks", "Shulker preview on hover, highlight shulkers holding an item", ModuleCategory.PLAYER);
        setEnabled(true);
    }

    // ------------------------------------------------------------------ shulker helpers

    /** Item id path, e.g. "red_shulker_box". */
    private static String path(ItemStack stack) {
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.getPath();
    }

    public static boolean isShulker(ItemStack stack) {
        return !stack.isEmpty() && path(stack).endsWith("shulker_box");
    }

    /** The items stored in a container item (shulker box), padded to 27 slots; null when there is no data. */
    public static List<ItemStack> contentsOf(ItemStack stack) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return null;
        List<ItemStack> items = new ArrayList<>(contents.allItemsCopyStream().toList());
        while (items.size() < SLOTS) items.add(ItemStack.EMPTY);
        return items;
    }

    private static int usedSlots(List<ItemStack> items) {
        int used = 0;
        for (ItemStack s : items) if (!s.isEmpty()) used++;
        return used;
    }

    /** The shulker's dye colour (plain shulker box = the accent colour). */
    private static int colorOf(ItemStack stack) {
        String p = path(stack);
        if (p.length() > "_shulker_box".length() && p.endsWith("_shulker_box")) {
            Integer dye = DYES.get(p.substring(0, p.length() - "_shulker_box".length()));
            if (dye != null) return dye;
        }
        return Theme.accent();
    }

    /** Each kind of item once, with the total across the box (most first). */
    private static List<ItemStack> merged(List<ItemStack> items) {
        Map<Item, Integer> totals = new LinkedHashMap<>();
        Map<Item, ItemStack> first = new LinkedHashMap<>();
        for (ItemStack s : items) {
            if (s.isEmpty()) continue;
            totals.merge(s.getItem(), s.getCount(), Integer::sum);
            first.putIfAbsent(s.getItem(), s);
        }
        List<ItemStack> out = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : totals.entrySet()) {
            ItemStack copy = first.get(e.getKey()).copy();
            copy.setCount(e.getValue());
            out.add(copy);
        }
        out.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
        return out;
    }

    private boolean previewActive() {
        String mode = preview.getValue();
        if (mode.equals("Off")) return false;
        if (mode.equals("Hold Key") || mode.equals("Hold Shift")) {
            int key = previewKey.getKey();
            if (key < 0) key = 340;
            boolean down = mc.getWindow() != null && InputConstants.isKeyDown(mc.getWindow(), key);
            // Left Shift also accepts Right Shift
            if (!down && key == 340 && mc.getWindow() != null) down = InputConstants.isKeyDown(mc.getWindow(), 344);
            return down;
        }
        return true;
    }

    // ------------------------------------------------------------------ drawing (after the container screen)

    /** Called after every container screen is drawn (RelaxedQolClient). */
    public void renderScreen(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
        Slot hovered = a.relaxed$getHoveredSlot();
        // The slot under the mouse, worked out here too (the screen's own value can lag a frame behind or be empty)
        if (hovered == null || !hovered.hasItem()) hovered = slotAt(screen, a, mouseX, mouseY);
        ItemStack hoveredStack = hovered != null && hovered.hasItem() ? hovered.getItem() : ItemStack.EMPTY;
        boolean bars = false; // No fill bars any more
        // The item to look for inside shulkers: the hovered one (not a shulker itself)
        Item wanted = highlight.getValue() && !hoveredStack.isEmpty() && !isShulker(hoveredStack) ? hoveredStack.getItem() : null;

        if (bars || wanted != null) {
            boolean stratum = false;
            int left = a.relaxed$getLeftPos();
            int top = a.relaxed$getTopPos();
            for (Slot slot : screen.getMenu().slots) {
                if (!slot.isActive() || !slot.hasItem()) continue;
                ItemStack stack = slot.getItem();
                if (!isShulker(stack)) continue;
                List<ItemStack> items = contentsOf(stack);
                int used = items == null ? 0 : usedSlots(items);
                boolean match = false;
                if (wanted != null && items != null) {
                    for (ItemStack s : items) {
                        if (!s.isEmpty() && s.getItem() == wanted) {
                            match = true;
                            break;
                        }
                    }
                }
                if (!bars && !match) continue;
                if (!stratum) {
                    g.nextStratum(); // on top of the items
                    stratum = true;
                }
                int sx = left + slot.x;
                int sy = top + slot.y;
                if (match) {
                    int accent = Theme.accent();
                    Render2D.roundRect(g, sx - 1, sy - 1, 18, 18, 3, Theme.withAlpha(accent, 0.25f));
                    Render2D.roundOutline(g, sx - 1, sy - 1, 18, 18, 3, accent);
                }
                if (bars) {
                    // 13 px bar at the bottom of the slot, like the durability bar
                    g.fill(sx + 2, sy + 13, sx + 15, sy + 15, 0xFF000000);
                    int w = Math.round(13f * used / SLOTS);
                    int color = used >= SLOTS ? 0xFFEF4444 : used == 0 ? 0xFF6B7280 : used >= 20 ? 0xFFFACC15 : 0xFF4ADE80;
                    if (used == 0) g.fill(sx + 2, sy + 13, sx + 3, sy + 14, color);
                    else g.fill(sx + 2, sy + 13, sx + 2 + Math.max(1, w), sy + 14, color);
                }
            }
        }

        if (!hoveredStack.isEmpty() && isShulker(hoveredStack) && previewActive()) drawPreview(screen, g, hovered, mouseX, mouseY);
    }

    /** The slot under the mouse (null = none). */
    private static Slot slotAt(AbstractContainerScreen<?> screen, AbstractContainerScreenAccessor a, int mouseX, int mouseY) {
        int left = a.relaxed$getLeftPos();
        int top = a.relaxed$getTopPos();
        for (Slot slot : screen.getMenu().slots) {
            if (!slot.isActive()) continue;
            int sx = left + slot.x;
            int sy = top + slot.y;
            if (mouseX >= sx - 1 && mouseX < sx + 17 && mouseY >= sy - 1 && mouseY < sy + 17) return slot;
        }
        return null;
    }

    /** A small note over the box when there is nothing to show (so the preview never just silently does nothing). */
    private void drawNote(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, Slot slot, String text) {
        AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
        Font font = mc.font;
        int w = font.width(text) + 14;
        int h = 16;
        int slotX = a.relaxed$getLeftPos() + slot.x + 8;
        int slotY = a.relaxed$getTopPos() + slot.y;
        int x = Math.max(2, Math.min(slotX - w / 2, screen.width - w - 2));
        int y = slotY - h - 20;
        if (y < 2) y = Math.min(slotY + 22, screen.height - h - 2);
        g.nextStratum();
        Render2D.shadow(g, x, y, w, h, 5);
        Render2D.roundRect(g, x, y, w, h, 5, 0xF412131A);
        Render2D.roundOutline(g, x, y, w, h, 5, Theme.withAlpha(colorOf(slot.getItem()), 0.6f));
        g.text(font, text, x + 7, y + 4, 0xFFC9CDD9, false);
    }

    private void drawPreview(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY) {
        ItemStack box = slot.getItem();
        List<ItemStack> items = contentsOf(box);
        if (items == null) {
            // The server did not send what is inside (some servers hide it) - say so instead of showing nothing
            drawNote(screen, g, slot, "Contents not sent by the server");
            return;
        }
        int used = usedSlots(items);
        if (used == 0) {
            drawNote(screen, g, slot, "Empty");
            return;
        }
        List<ItemStack> shown = compact.getValue() ? merged(items) : items;
        int cols = 9;
        int rows = Math.max(1, (shown.size() + cols - 1) / cols);
        int w = cols * 18 + 14;
        boolean card = !previewLook.getValue().equals("Vanilla");
        int h = rows * 18 + (card ? CARD_HEADER + 9 : 24);
        float s = previewScale.getValue() / 100f;
        int sw = Math.round(w * s);
        int sh = Math.round(h * s);
        // Centred right above the shulker, clear of the item's name tooltip; no room above = right under it
        AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
        int slotX = a.relaxed$getLeftPos() + slot.x + 8;
        int slotY = a.relaxed$getTopPos() + slot.y;
        int x = Math.max(2, Math.min(slotX - sw / 2, screen.width - sw - 2));
        int y = Math.min(slotY, mouseY) - sh - 20;
        if (y < 2) y = Math.min(slotY + 22, screen.height - sh - 2);

        g.nextStratum();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(s, s);
        int frame = tint.getValue() ? colorOf(box) : Theme.accent();
        if (previewLook.getValue().equals("Vanilla")) drawVanilla(g, mc.font, box.getHoverName().getString(), used, shown, w, h,
            tint.getValue() && !vanillaPlain.getValue() ? colorOf(box) : 0);
        else drawCard(g, mc.font, box, box.getHoverName().getString(), used, shown, w, h, tint.getValue() ? colorOf(box) : Theme.accent());
        g.pose().popMatrix();
    }

    /**
     * "Vanilla" look: exactly like the game's own container screens - light grey panel with the bevelled
     * edge, the box's name in dark grey, and the classic sunken slots. Shulker Colour tints the panel a little.
     */
    private static void drawVanilla(GuiGraphicsExtractor g, Font font, String title, int used, List<ItemStack> items, int w, int h, int dye) {
        int panel = dye == 0 ? 0xFFC6C6C6 : Theme.mix(0xFFC6C6C6, 0xFF000000 | (dye & 0xFFFFFF), 0.22f);
        int light = 0xFFFFFFFF;
        int dark = Theme.mix(panel, 0xFF000000, 0.55f);
        // Black outline with the corner pixels left out, like vanilla panels
        g.fill(1, 0, w - 1, 1, 0xFF000000);
        g.fill(1, h - 1, w - 1, h, 0xFF000000);
        g.fill(0, 1, 1, h - 1, 0xFF000000);
        g.fill(w - 1, 1, w, h - 1, 0xFF000000);
        g.fill(1, 1, w - 1, h - 1, panel);
        // Bevel: light top-left, dark bottom-right
        g.fill(1, 1, w - 2, 3, light);
        g.fill(1, 1, 3, h - 2, light);
        g.fill(3, h - 3, w - 1, h - 1, dark);
        g.fill(w - 3, 3, w - 1, h - 1, dark);
        String info = used + "/" + SLOTS;
        int infoW = font.width(info);
        String shown = com.relaxedqol.client.gui.Theme.ellipsize(title, w - 22 - infoW, font::width);
        g.text(font, shown, 8, 6, 0xFF404040, false);
        g.text(font, info, w - 8 - infoW, 6, used >= SLOTS ? 0xFFAA2222 : 0xFF606060, false);
        for (int i = 0; i < items.size(); i++) {
            int cx = 7 + (i % 9) * 18;
            int cy = 17 + (i / 9) * 18;
            // Sunken slot: dark top-left edge, white bottom-right edge, mid grey inside
            g.fill(cx - 1, cy - 1, cx + 17, cy, 0xFF373737);
            g.fill(cx - 1, cy - 1, cx, cy + 17, 0xFF373737);
            g.fill(cx, cy + 16, cx + 17, cy + 17, 0xFFFFFFFF);
            g.fill(cx + 16, cy, cx + 17, cy + 17, 0xFFFFFFFF);
            g.fill(cx, cy, cx + 16, cy + 16, 0xFF8B8B8B);
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;
            g.item(stack, cx, cy);
            g.itemDecorations(font, stack, cx, cy);
        }
    }

    /**
     * "Card" look: a clean dark card with a calm header in the box's own colour (one even tint, no fade, no
     * dot), the box's name in the smooth font at full size (sharp at any GUI scale), a small "12/27" chip on the
     * right, then the items in softly sunken slots.
     */
    private static final int CARD_HEADER = 17;

    private static void drawCard(GuiGraphicsExtractor g, Font font, ItemStack box, String title, int used, List<ItemStack> items,
                                 int w, int h, int dye) {
        int tone = 0xFF000000 | (dye & 0xFFFFFF);
        Render2D.shadow(g, 0, 0, w, h, 6);
        Render2D.roundRect(g, 0, 0, w, h, 6, 0xF412131A);
        // Header: one even wash of the box colour and a hairline under it
        Render2D.roundRectTop(g, 0, 0, w, CARD_HEADER, 6, Theme.withAlpha(tone, 0.20f));
        g.fill(1, CARD_HEADER, w - 1, CARD_HEADER + 1, Theme.withAlpha(tone, 0.45f));
        Render2D.roundOutline(g, 0, 0, w, h, 6, Theme.withAlpha(Theme.mix(tone, 0xFFFFFFFF, 0.25f), 0.55f));

        String info = used + "/" + SLOTS;
        int textY = (CARD_HEADER - 8) / 2 + 1;
        com.relaxedqol.client.util.SmoothFont.begin();
        try {
            int infoW = font.width(info);
            int chipW = infoW + 8;
            int chipX = w - 5 - chipW;
            Render2D.roundRect(g, chipX, 3, chipW, CARD_HEADER - 6, (CARD_HEADER - 6) / 2,
                used >= SLOTS ? 0x40F87171 : 0x26FFFFFF);
            g.text(font, info, chipX + 4, textY, used >= SLOTS ? 0xFFFCA5A5 : 0xFFC9CDD9, false);
            int room = chipX - 8 - 6;
            String shown = com.relaxedqol.client.gui.Theme.ellipsize(title, room, font::width);
            g.text(font, shown, 7, textY, 0xFFF4F5FA, false);
        } finally {
            com.relaxedqol.client.util.SmoothFont.end();
        }

        // Slots: softly sunken wells
        for (int i = 0; i < items.size(); i++) {
            int cx = 7 + (i % 9) * 18;
            int cy = CARD_HEADER + 5 + (i / 9) * 18;
            ItemStack stack = items.get(i);
            Render2D.roundRect(g, cx - 1, cy - 1, 18, 18, 3, stack.isEmpty() ? 0x1E000000 : 0x38000000);
            if (stack.isEmpty()) continue;
            g.item(stack, cx, cy);
            g.itemDecorations(font, stack, cx, cy);
        }
    }

    /** "Dark" look: a clean, flat dark card - title, count, and simple slots. */
    private static void drawGrid(GuiGraphicsExtractor g, Font font, String title, int used, List<ItemStack> items, int w, int h, int frame) {
        Render2D.shadow(g, 0, 0, w, h, 6);
        Render2D.roundRect(g, 0, 0, w, h, 6, 0xF412131A);
        Render2D.roundOutline(g, 0, 0, w, h, 6, Theme.withAlpha(Theme.mix(0xFFFFFFFF, frame, 0.35f), 0.22f));
        String info = used + "/" + SLOTS;
        int infoW = font.width(info);
        String shown = com.relaxedqol.client.gui.Theme.ellipsize(title, w - 22 - infoW, font::width);
        g.text(font, shown, 8, 6, Theme.TEXT, false);
        g.text(font, info, w - 8 - infoW, 6, used >= SLOTS ? 0xFFF87171 : Theme.TEXT_FAINT, false);
        for (int i = 0; i < items.size(); i++) {
            int cx = 7 + (i % 9) * 18;
            int cy = 17 + (i / 9) * 18;
            ItemStack stack = items.get(i);
            Render2D.roundRect(g, cx - 1, cy - 1, 18, 18, 2, stack.isEmpty() ? 0x10FFFFFF : 0x1CFFFFFF);
            if (stack.isEmpty()) continue;
            g.item(stack, cx, cy);
            g.itemDecorations(font, stack, cx, cy);
        }
    }
}
