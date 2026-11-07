package com.relaxedqol.client.module.modules.misc;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.ChatUtil;
import com.relaxedqol.client.util.ScreenshotUtil;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proof of every player trade (made for Lovercraft's /trade window): a screenshot of the trade window, saved in
 * .minecraft/screenshots/Trades/ (or the Save Folder you set) when the trade went through. no screenshot is
 * ever taken while an item tooltip is showing, so the last one is from just before you hover the confirm dye.
 *
 * It used to wait for the "Confirm Trade" item before taking any screenshot, and decided "completed" only
 * from whether your inventory changed - so a slightly different item name, or a trade where you only gave, left
 * nothing. Now it screenshots from the moment the trade window opens (again whenever the items change, so the
 * last one is exactly what was traded) and also once more when the confirm item shows. When the window closes:
 * a chat line about the trade decides; otherwise it checks whose items landed in your inventory (the other
 * player's side = completed, your own side coming back = cancelled); otherwise a trade that reached the confirm
 * stage counts as completed.
 */
public class TradeShot extends Module {
    /** Only works on servers whose address contains this (Lovercraft / loverfella). */
    private static final String SERVER = "loverfella";
    /** The trade window's title contains this ("TRADING: with NAME"). */
    private static final String TITLE_WORD = "trading";
    /** The item you click to confirm the trade (the lime dye "Confirm Trade"). */
    private static final String CONFIRM_WORD = "confirm";
    /** The trade window's money slots (yours, theirs). */
    private static final int[] MONEY_SLOTS = {2, 6};
    private final Setting<Boolean> keepCancelled = addSetting(new Setting<>("Keep Cancelled", false),
        "Also keep screenshots of trades that were cancelled (normally deleted)");
    private final Setting<Boolean> chatNote = addSetting(new Setting<>("Chat Message", true),
        "Tell you in chat where the screenshot was saved");
    /** Where the pictures go (empty = .minecraft\\screenshots\\Trades). */
    private final Setting<String> saveFolder = addSetting(new Setting<>("Save Folder", "").prompt(),
        "The folder the trade screenshots are saved in, e.g. C:\\Users\\you\\Pictures\\Trades (%APPDATA% and ~ work too)."
            + " Empty = .minecraft\\screenshots\\Trades");

    private final Setting<Boolean> moneyCard = addSetting(new Setting<>("Show Money", true),
        "A card above the trade window with what both sides offer: money, claim chunks, crate keys, LoverBonds and"
            + " other wallet items (it is in the screenshot)");
    private final Setting<Boolean> watermark = addSetting(new Setting<>("Show Watermark", false),
        "A small \"Relaxed QOL\" mark in the bottom-right corner while a trade window is open (so it is in the screenshot)");

    private static final String FOLDER = "Trades";
    /** Contents on the previous tick: a screenshot is only taken once they stayed the same for a tick, so the
     *  frame it captures already shows the new items / money (the frame is drawn after the tick). */
    private int prevContents;
    /** The money each side offers (last value read), and the trade screen it was read from. */
    private String moneyMine;
    private String moneyTheirs;
    private AbstractContainerScreen<?> tradeScreen;
    /** Claim chunks, keys, LoverBonds and wallet items each side offers (last read). */
    private Extras extrasMine = new Extras();
    private Extras extrasTheirs = new Extras();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** In a trade window right now, and with whom. */
    private boolean inTrade;
    private String partner = "";
    /** "Confirm Trade" has been seen in this trade. */
    private boolean confirmStage;
    private String shotName;
    private File shotFile;
    /** Screenshots still being written (decide only once they are all on disk). */
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    private long lastShotAt;
    private int lastContents;
    private boolean shotTaken;
    private boolean failNoted;
    /** Your inventory on the last tick the window was open. */
    private Map<String, Integer> invBefore;
    /** The items on your side (left) and the other player's side (right) on the last tick the window was open. */
    private Map<String, Integer> mine = new HashMap<>();
    private Map<String, Integer> theirs = new HashMap<>();
    /** After the window closed: when it closed and the latest moment to decide. */
    private long closedAt;
    private long decideBy;
    private Boolean chatVerdict;

    public TradeShot() {
        super("TradeShot", "Screenshots every completed player trade on loverfella (into screenshots/Trades or your own Save Folder)", ModuleCategory.LOVERCRAFT);
        addSetting(new com.relaxedqol.client.setting.ActionSetting("Open Folder", this::openFolder),
            "Opens the folder your trade screenshots are saved in");
    }

    /** Opens the screenshots folder in Explorer (made first if it is not there yet). */
    private void openFolder() {
        File dir = folder();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            ChatUtil.send("§cTradeShot: could not make the folder §f" + dir.getAbsolutePath());
            return;
        }
        try {
            net.minecraft.util.Util.getPlatform().openUri(dir.toURI().toString());
        } catch (RuntimeException e) {
            ChatUtil.send("§7TradeShot folder: §f" + dir.getAbsolutePath());
        }
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        inTrade = false;
        confirmStage = false;
        shotName = null;
        shotFile = null;
        moneyMine = null;
        moneyTheirs = null;
        tradeScreen = null;
        extrasMine = new Extras();
        extrasTheirs = new Extras();
        prevContents = 0;
        shotTaken = false;
        failNoted = false;
        invBefore = null;
        mine = new HashMap<>();
        theirs = new HashMap<>();
        closedAt = 0;
        decideBy = 0;
        chatVerdict = null;
        lastContents = 0;
        lastShotAt = 0;
        partner = "";
    }

    /** Only on loverfella (any address with "loverfella" in it). */
    private boolean rightServer() {
        var data = mc.getCurrentServer();
        return data != null && data.ip != null && data.ip.toLowerCase(Locale.ROOT).contains(SERVER);
    }

    private static String plain(String text) {
        return text.replaceAll("§.", "").toLowerCase(Locale.ROOT);
    }

    @Override
    public void onTick() {
        if (notInGame()) return;
        long now = System.currentTimeMillis();
        String wantTitle = TITLE_WORD;
        boolean tradeOpen = rightServer() && !wantTitle.isEmpty()
            && mc.gui.screen() instanceof AbstractContainerScreen<?> open
            && plain(open.getTitle().getString()).contains(wantTitle);

        if (tradeOpen) {
            AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.gui.screen();
            if (!inTrade || decideBy > 0) {
                // A new trade (also when one opens while the last one is still being decided: decide that first)
                if (decideBy > 0) decide(now, true);
                reset();
                inTrade = true;
                String title = screen.getTitle().getString().replaceAll("§.", "");
                int with = title.toLowerCase(Locale.ROOT).lastIndexOf("with ");
                partner = with >= 0 ? title.substring(with + 5).trim() : "";
                String who = partner.replaceAll("[^A-Za-z0-9_]", "");
                shotName = LocalDateTime.now().format(STAMP) + (who.isEmpty() ? "" : "_" + who) + ".png";
                shotFile = new File(folder(), shotName);
            }
            String confirmWord = CONFIRM_WORD;
            boolean confirmShown = false;
            int contents = 1;
            int index = -1;
            Map<String, Integer> mineNow = new HashMap<>();
            Map<String, Integer> theirsNow = new HashMap<>();
            for (Slot slot : screen.getMenu().slots) {
                index++;
                if (slot.container == mc.player.getInventory()) continue;
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) continue;
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                contents = contents * 31 + id.hashCode() * 7 + stack.getCount() + index * 131;
                if (!confirmWord.isEmpty() && plain(stack.getHoverName().getString()).contains(confirmWord)) {
                    confirmShown = true;
                }
                // Left four columns are yours, right four the other player's (the middle column is the divider)
                int column = index % 9;
                if (column < 4) mineNow.merge(id, stack.getCount(), Integer::sum);
                else if (column > 4) theirsNow.merge(id, stack.getCount(), Integer::sum);
            }
            mine = mineNow;
            theirs = theirsNow;
            // The money both sides offer (from the money slots' item name / lore) - part of the "contents" too
            tradeScreen = screen;
            int[] money = moneySlotIndexes();
            String m1 = moneyAt(screen, money[0]);
            String m2 = moneyAt(screen, money[1]);
            if (m1 != null) moneyMine = m1;
            if (m2 != null) moneyTheirs = m2;
            contents = contents * 31 + java.util.Objects.hashCode(moneyMine) * 17 + java.util.Objects.hashCode(moneyTheirs);
            Extras[] ex = extrasOf(screen);
            extrasMine = ex[0];
            extrasTheirs = ex[1];
            contents = contents * 31 + ex[0].hashCode() * 13 + ex[1].hashCode();
            invBefore = inventoryCounts();
            boolean forceShot = false;
            if (confirmShown && !confirmStage) {
                confirmStage = true;
                forceShot = true;
            }
            // Screenshot from the start, again when the items change (at most every 0.4 s): the last one shows the final
            // trade. never while the mouse is on an item - its tooltip (e.g. the green "Confirm Trade" dye's)
            // covers the trade. So the last picture is the one from just before you go to the confirm dye.
            boolean tooltip = tooltipShowing(screen);
            boolean stable = contents == prevContents;
            prevContents = contents;
            if ((forceShot || contents != lastContents || !shotTaken) && stable && now - lastShotAt > 400 && !tooltip) {
                lastContents = contents;
                lastShotAt = now;
                shotTaken = true;
                pending.incrementAndGet();
                File target = shotFile;
                ScreenshotUtil.captureTo(target, msg -> mc.execute(() -> {
                    pending.decrementAndGet();
                    if (msg.startsWith("failed") && !failNoted) {
                        failNoted = true;
                        ChatUtil.send("§cTradeShot: could not take a screenshot (" + msg + ")");
                    }
                }));
            }
            return;
        }

        if (inTrade) {
            // The trade window closed
            inTrade = false;
            if (!shotTaken || shotName == null) {
                reset();
                return;
            }
            closedAt = now;
            decideBy = now + 4000;
        }
        // Wait at least 1.5 s after closing (the screenshot is written in the background, and the items arrive)
        boolean written = pending.get() <= 0 || now - closedAt > 8000;
        if (decideBy > 0 && written && now - closedAt >= 1500 && (chatVerdict != null || now >= decideBy)) {
            decide(now, false);
            reset();
        }
    }

    private void decide(long now, boolean early) {
        if (shotName == null) return;
        boolean done;
        if (chatVerdict != null) {
            done = chatVerdict;
        } else {
            // Which side's items came into your inventory?
            Map<String, Integer> after = inventoryCounts();
            int gotTheirs = 0;
            int gotMine = 0;
            if (invBefore != null) {
                for (Map.Entry<String, Integer> e : after.entrySet()) {
                    int gained = e.getValue() - invBefore.getOrDefault(e.getKey(), 0);
                    if (gained <= 0) continue;
                    gotTheirs += Math.min(gained, theirs.getOrDefault(e.getKey(), 0));
                    gotMine += Math.min(gained, mine.getOrDefault(e.getKey(), 0));
                }
            }
            if (gotTheirs > gotMine) done = true;
            else if (gotMine > gotTheirs) done = false;
            else done = confirmStage;
        }
        File file = shotFile;
        if (file == null) return;
        if (done || keepCancelled.getValue()) {
            if (chatNote.getValue()) {
                ChatUtil.send("§a📸 Trade" + (partner.isEmpty() ? "" : " with §f" + partner + "§a") + (done ? "" : " §7(cancelled)§a")
                    + " saved: §f" + file.getAbsolutePath());
                if (moneyMine != null || moneyTheirs != null) {
                    ChatUtil.send("§7  Money: §ayou §f" + (moneyMine != null ? moneyMine : "-") + " §7· §6"
                        + (partner.isEmpty() ? "them" : partner) + " §f" + (moneyTheirs != null ? moneyTheirs : "-"));
                }
                if (!extrasMine.isEmpty()) ChatUtil.send("§7  §aYou §7also gave: §f" + String.join("§7, §f", extrasMine.lines()));
                if (!extrasTheirs.isEmpty()) ChatUtil.send("§7  §6" + (partner.isEmpty() ? "They" : partner)
                    + " §7also gave: §f" + String.join("§7, §f", extrasTheirs.lines()));
            }
        } else {
            // Cancelled: no proof needed
            if (!file.delete()) file.deleteOnExit();
        }
    }

    // ------------------------------------------------------------------ money

    private static final java.util.regex.Pattern MONEY = java.util.regex.Pattern.compile(
        "(\\$\\s*[0-9][0-9.,]*\\s*[kKmMbBtT]?\\b|[0-9][0-9.,]*\\s*[kKmMbBtT]?\\s*(?:\\$|coins?\\b|money\\b|dollars?\\b|gems?\\b|tokens?\\b|credits?\\b))");

    private int[] moneySlotIndexes() {
        return MONEY_SLOTS;
    }

    /** The money amount the item in this slot shows (its name or lore, e.g. "$1,500" or "1.5k coins"), or null. */
    private String moneyAt(AbstractContainerScreen<?> screen, int index) {
        var slots = screen.getMenu().slots;
        if (index < 0 || index >= slots.size()) return null;
        ItemStack stack = slots.get(index).getItem();
        if (stack.isEmpty()) return null;
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(stack.getHoverName().getString());
        net.minecraft.world.item.component.ItemLore lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (lore != null) for (net.minecraft.network.chat.Component line : lore.lines()) lines.add(line.getString());
        // A "$1,000" / "1.5k coins" style amount first
        for (String raw : lines) {
            String line = raw.replaceAll("§.", "");
            java.util.regex.Matcher m = MONEY.matcher(line);
            if (m.find()) return m.group(1).trim();
        }
        // Otherwise a number on a line that talks about money
        for (String raw : lines) {
            String line = raw.replaceAll("§.", "");
            String lower = line.toLowerCase(Locale.ROOT);
            if (!(lower.contains("money") || lower.contains("balance") || lower.contains("amount") || lower.contains("offer")
                || lower.contains("cash") || lower.contains("coin"))) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[0-9][0-9.,]*\\s*[kKmMbBtT]?").matcher(line);
            if (m.find()) return m.group().trim();
        }
        return null;
    }

    /**
     * Drawn on the trade window (the client, after the screen): the card with what both sides offer, and the
     * watermark in the bottom-right corner - both end up in the screenshot.
     */
    public void renderTradeOverlay(AbstractContainerScreen<?> screen, net.minecraft.client.gui.GuiGraphicsExtractor g) {
        if (!inTrade || screen != tradeScreen) return;
        net.minecraft.client.gui.Font font = mc.font;
        if (moneyCard.getValue()) {
            // Read straight from the slots at draw time, so the frame always shows what is in the window now
            int[] money = moneySlotIndexes();
            String mineNow = moneyAt(screen, money[0]);
            String theirsNow = moneyAt(screen, money[1]);
            String m1 = mineNow != null ? mineNow : moneyMine != null ? moneyMine : "$0";
            String m2 = theirsNow != null ? theirsNow : moneyTheirs != null ? moneyTheirs : "$0";
            Extras[] ex = extrasOf(screen);
            String other = partner.isEmpty() ? "Them" : partner;
            drawOfferCard(screen, g, font, other, m1, m2, ex[0], ex[1]);
        }
        if (watermark.getValue()) {
            String mark = "Relaxed QOL";
            int tw = font.width(mark);
            int wx = g.guiWidth() - tw - 8;
            int wy = g.guiHeight() - 13;
            com.relaxedqol.client.gui.Render2D.roundRect(g, wx - 9, wy - 3, tw + 14, 14, 6, 0x66000000);
            com.relaxedqol.client.gui.Render2D.roundRect(g, wx - 5, wy + 2, 3, 3, 1, com.relaxedqol.client.gui.Theme.accent());
            g.text(font, mark, wx + 1, wy, 0xB4FFFFFF, false);
        }
    }

    // ------------------------------------------------------------------ claim chunks, keys, LoverBonds, wallet items

    /**
     * What one side offers besides items and money. Only items the server itself made count: they carry the
     * server's own "trading:..." / "CrateKey" tag in their custom data, which players can never add (not with an
     * anvil, not by renaming) - so a renamed nether star or tripwire hook is never counted. The text on the item is
     * only read for the amounts.
     */
    private static final class Extras {
        int chunks;
        long bonds;
        long bondValue;
        final java.util.LinkedHashMap<String, Long> keys = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<String, Long> wallet = new java.util.LinkedHashMap<>();
        /** One item of each kind, drawn as its icon on the card. */
        ItemStack chunkIcon = ItemStack.EMPTY;
        ItemStack bondIcon = ItemStack.EMPTY;
        final Map<String, ItemStack> icons = new HashMap<>();

        boolean isEmpty() {
            return chunks == 0 && bonds == 0 && keys.isEmpty() && wallet.isEmpty();
        }

        /** One short line per thing, e.g. "3 claim chunks", "Daily Key x2", "LoverBond x2 (2,000 LoverBux)". */
        java.util.List<String> lines() {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (chunks > 0) out.add(chunks + (chunks == 1 ? " claim chunk" : " claim chunks"));
            for (var e : keys.entrySet()) out.add(e.getKey() + " \u00d7" + e.getValue());
            if (bonds > 0) out.add("LoverBond \u00d7" + bonds + (bondValue > 0
                ? " (" + String.format(Locale.ROOT, "%,d", bondValue) + " LoverBux)" : ""));
            for (var e : wallet.entrySet()) out.add(e.getKey() + " \u00d7" + e.getValue());
            return out;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(chunks, bonds, bondValue, keys, wallet);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Extras x && x.hashCode() == hashCode();
        }
    }

    private static final java.util.regex.Pattern TIMES = java.util.regex.Pattern.compile("\\s*[\u00d7xX]\\s*([0-9][0-9,]*)\\s*$");
    private static final java.util.regex.Pattern CHUNKS = java.util.regex.Pattern.compile("([0-9][0-9,]*)\\s*chunks?\\b",
        java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern BOND = java.util.regex.Pattern.compile("trading:loverbond\"?\\s*:\\s*\"?([0-9]+)");
    private static final java.util.regex.Pattern CRATE = java.util.regex.Pattern.compile("CrateKey\"?\\s*:\\s*\"([^\"]+)\"");

    /** Both sides' extras: yours (left four columns) and theirs (right four), from the offer rows only. */
    private Extras[] extrasOf(AbstractContainerScreen<?> screen) {
        Extras mineX = new Extras();
        Extras theirsX = new Extras();
        var slots = screen.getMenu().slots;
        int container = 0;
        for (Slot slot : slots) if (slot.container != mc.player.getInventory()) container++;
        int rows = container / 9;
        for (int index = 0; index < container; index++) {
            int row = index / 9;
            int column = index % 9;
            // The top row (heads, money) and the bottom row (status / confirm) are the window itself
            if (row == 0 || row >= rows - 1 || column == 4) continue;
            ItemStack stack = slots.get(index).getItem();
            if (stack.isEmpty()) continue;
            add(column < 4 ? mineX : theirsX, stack);
        }
        return new Extras[]{mineX, theirsX};
    }

    /** Counts this item when it is one of the server's own trade items (see {@link Extras}). */
    private static void add(Extras into, ItemStack stack) {
        Object custom = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (custom == null) return;
        String data = custom.toString();
        boolean server = data.contains("trading:") || data.contains("CrateKey");
        if (!server) return;
        String name = stack.getHoverName().getString().replaceAll("§.", "").trim();
        // Wallet items show how many in their name ("Vote Key x3"); real stacks use the stack size
        long amount = stack.getCount();
        java.util.regex.Matcher times = TIMES.matcher(name);
        if (times.find()) {
            try {
                amount = Long.parseLong(times.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
            }
            name = name.substring(0, times.start()).trim();
        }
        java.util.List<String> lore = new java.util.ArrayList<>();
        net.minecraft.world.item.component.ItemLore il = stack.get(net.minecraft.core.component.DataComponents.LORE);
        if (il != null) for (net.minecraft.network.chat.Component line : il.lines()) lore.add(line.getString().replaceAll("§.", ""));

        String allLore = String.join("\n", lore).toLowerCase(Locale.ROOT);
        if (data.contains("trading:claim_chunks")) {
            // Also its description must say how many chunks (the flag line), like the real ones
            if (!CHUNKS.matcher(allLore).find()) return;
            // "(flag) 3 chunks" on the item; one chunk when it does not say
            int per = 1;
            for (String line : lore) {
                java.util.regex.Matcher m = CHUNKS.matcher(line);
                if (m.find()) {
                    try {
                        per = Integer.parseInt(m.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                }
            }
            into.chunks += per * stack.getCount();
            if (into.chunkIcon.isEmpty()) into.chunkIcon = stack.copyWithCount(1);
            return;
        }
        java.util.regex.Matcher bond = BOND.matcher(data);
        if (bond.find()) {
            if (!allLore.contains("loverbux")) return;
            into.bonds += amount;
            if (into.bondIcon.isEmpty()) into.bondIcon = stack.copyWithCount(1);
            try {
                into.bondValue += Long.parseLong(bond.group(1)) * amount;
            } catch (NumberFormatException ignored) {
            }
            return;
        }
        java.util.regex.Matcher crate = CRATE.matcher(data);
        if (crate.find() || data.matches("(?s).*trading:virtual_item\"?\\s*:\\s*\"[^\"]*_key\".*")) {
            String key = name;
            if (key.isEmpty() && crate.find(0)) {
                String t = crate.group(1);
                key = Character.toUpperCase(t.charAt(0)) + t.substring(1) + " Key";
            }
            if (!allLore.contains("crate")) return;
            key = key.isEmpty() ? "Crate Key" : key;
            into.keys.merge(key, amount, Long::sum);
            into.icons.putIfAbsent(key, stack.copyWithCount(1));
            return;
        }
        if (data.contains("trading:virtual_item") || data.contains("trading:")) {
            if (!(allLore.contains("wallet item") || allLore.contains("account item"))) return;
            String item = name.isEmpty() ? "Wallet item" : name;
            into.wallet.merge(item, amount, Long::sum);
            into.icons.putIfAbsent(item, stack.copyWithCount(1));
        }
    }

    /** One chip on the card: the item's icon and a short label ("2 chunks", "Daily \u00d71", "1,000 LB"). */
    private record Entry(ItemStack icon, String label, int color) {}

    private static List<Entry> entries(Extras x) {
        List<Entry> out = new java.util.ArrayList<>();
        if (x.chunks > 0) out.add(new Entry(x.chunkIcon, x.chunks + (x.chunks == 1 ? " chunk" : " chunks"), 0xFF4ADE80));
        for (var e : x.keys.entrySet()) {
            String k = e.getKey().replaceAll("(?i)\\s*key$", "");
            out.add(new Entry(x.icons.getOrDefault(e.getKey(), ItemStack.EMPTY), (k.isEmpty() ? "Key" : k) + " \u00d7" + e.getValue(), 0xFF60A5FA));
        }
        if (x.bonds > 0) out.add(new Entry(x.bondIcon, x.bondValue > 0
            ? String.format(Locale.ROOT, "%,d LB", x.bondValue) + (x.bonds > 1 ? " (" + x.bonds + ")" : "")
            : "LoverBond \u00d7" + x.bonds, 0xFFF472B6));
        for (var e : x.wallet.entrySet()) out.add(new Entry(x.icons.getOrDefault(e.getKey(), ItemStack.EMPTY), e.getKey() + " \u00d7" + e.getValue(), 0xFF5EEAD4));
        return out;
    }

    private static final int CHIP_H = 13;

    private static int chipWidth(net.minecraft.client.gui.Font font, Entry e) {
        return 14 + font.width(e.label()) + 5;
    }

    /** How many lines the chips need in a column this wide (next to each other, wrapping). */
    private static int chipLines(net.minecraft.client.gui.Font font, List<Entry> list, int colW) {
        if (list.isEmpty()) return 0;
        int lines = 1;
        int x = 0;
        for (Entry e : list) {
            int cw = Math.min(colW, chipWidth(font, e));
            if (x > 0 && x + cw > colW) {
                lines++;
                x = 0;
            }
            x += cw + 3;
        }
        return lines;
    }

    /**
     * The offer card, drawn the way the client draws its own cards (the /homes card in PlayerProfile): the popup
     * surface, a header with the accent dot and a pill, and then the two sides next to each other like in the trade
     * window itself - you on the left, the other player on the right, a thin line between. Each side: a dot, the
     * name and the money as a small tag, then one line per extra thing (claim chunks, keys, LoverBonds, wallet
     * items), each with the item's own icon. Normal text size; always right above the trade window (only drawn a
     * bit smaller when there is truly no room).
     */
    private void drawOfferCard(AbstractContainerScreen<?> screen, net.minecraft.client.gui.GuiGraphicsExtractor g,
                               net.minecraft.client.gui.Font font, String other, String mineAmount, String theirAmount,
                               Extras mineX, Extras theirsX) {
        com.relaxedqol.mixin.AbstractContainerScreenAccessor acc = (com.relaxedqol.mixin.AbstractContainerScreenAccessor) screen;
        float scale = com.relaxedqol.client.gui.OverlayRenderer.hudScale();
        float left = acc.relaxed$getLeftPos() / scale;
        float imageW = acc.relaxed$getImageWidth() / scale;
        float top = acc.relaxed$getTopPos() / scale;
        float screenW = g.guiWidth() / scale;
        int accent = com.relaxedqol.client.gui.Theme.accent();
        final int green = 0xFF4ADE80;
        final int gold = 0xFFFBBF24;

        String[] who = {"You", other};
        String[] cash = {mineAmount, theirAmount};
        int[] colors = {green, gold};
        List<List<Entry>> chips = List.of(entries(mineX), entries(theirsX));

        final int headerH = 18;
        final int nameH = 17;
        // Column width: what the widest line needs (name + money tag, or a chip)
        int colNeed = 0;
        for (int i = 0; i < 2; i++) {
            colNeed = Math.max(colNeed, 12 + font.width(who[i]) + 10 + font.width(cash[i]) + 12);
            for (Entry e : chips.get(i)) colNeed = Math.max(colNeed, chipWidth(font, e));
        }
        // Chips are as wide as their text and sit next to each other, wrapping to a new line only when the column is
        // full. When it would not fit above the window, the card gets wider first (up to the screen) - not smaller.
        float room = Math.max(24f, top - 6f);
        int maxW = Math.round(screenW - 4);
        int w = Math.round(Math.min(maxW, Math.max(imageW, colNeed * 2 + 8 * 2 + 9)));
        int colW;
        int lines;
        int h;
        while (true) {
            colW = (w - 16 - 9) / 2;
            lines = Math.max(chipLines(font, chips.get(0), colW), chipLines(font, chips.get(1), colW));
            h = 4 + headerH + nameH + (lines > 0 ? lines * (CHIP_H + 2) + 2 : 0) + 4;
            if (h <= room || w >= maxW) break;
            w = Math.min(maxW, w + 40);
        }

        // Right above the window; a bit smaller only if even the full width is not enough
        float k = Math.min(1f, room / h);
        float x = left + imageW / 2f - w * k / 2f;
        x = Math.max(2f, Math.min(x, screenW - w * k - 2f));
        float y = top - 4f - h * k;

        g.pose().pushMatrix();
        g.pose().scale(scale, scale);
        g.pose().translate(x, y);
        g.pose().scale(k, k);
        com.relaxedqol.client.gui.Render2D.popup(g, 0, 0, w, h);

        // Header: accent dot, "Trade", a pill with who it is with
        com.relaxedqol.client.gui.Render2D.roundRect(g, 8, 4 + 7, 4, 4, 2, accent);
        g.text(font, "Trade", 17, 4 + 5, 0xFFE4E4EC, false);
        String pill = "with " + other;
        int pillW = Math.min(font.width(pill) + 10, w - 60);
        int px = w - 8 - pillW;
        com.relaxedqol.client.gui.Render2D.roundRect(g, px, 4 + 3, pillW, 12, 4, 0x0DFFFFFF);
        com.relaxedqol.client.gui.Render2D.roundOutline(g, px, 4 + 3, pillW, 12, 4, 0x24FFFFFF);
        g.text(font, clip(font, pill, pillW - 10), px + 5, 4 + 5, com.relaxedqol.client.gui.Theme.TEXT_DIM, false);
        g.fill(8, 4 + 17, w - 8, 4 + 18, 0x14FFFFFF);

        // The line between the two sides
        int mid = 8 + colW + 4;
        g.fill(mid, 4 + headerH + 4, mid + 1, h - 6, 0x14FFFFFF);

        for (int i = 0; i < 2; i++) {
            int c = colors[i];
            int cx = i == 0 ? 8 : mid + 5;
            int ry = 4 + headerH;
            // Dot + name, money tag on the right of the column
            com.relaxedqol.client.gui.Render2D.roundRect(g, cx + 2, ry + 7, 4, 4, 2, com.relaxedqol.client.gui.Theme.withAlpha(c, 0.9f));
            String m = cash[i];
            int mw = font.width(m) + 10;
            int mx = cx + colW - mw;
            com.relaxedqol.client.gui.Render2D.roundRect(g, mx, ry + 3, mw, 12, 5, com.relaxedqol.client.gui.Theme.withAlpha(c, 0.12f));
            com.relaxedqol.client.gui.Render2D.roundOutline(g, mx, ry + 3, mw, 12, 5, com.relaxedqol.client.gui.Theme.withAlpha(c, 0.6f));
            g.text(font, m, mx + 5, ry + 5, com.relaxedqol.client.gui.Theme.mix(c, 0xFFFFFFFF, 0.25f), false);
            g.text(font, clip(font, who[i], mx - 6 - (cx + 10)), cx + 10, ry + 5, com.relaxedqol.client.gui.Theme.TEXT, false);
            ry += nameH;
            // One line per extra thing, each with its own icon
            if (chips.get(i).isEmpty() && lines > 0) {
                g.text(font, "nothing else", cx + 2, ry + 3, com.relaxedqol.client.gui.Theme.TEXT_FAINT, false);
            }
            int chipX = cx;
            for (Entry e : chips.get(i)) {
                int cw = Math.min(colW, chipWidth(font, e));
                if (chipX > cx && chipX + cw > cx + colW) {
                    chipX = cx;
                    ry += CHIP_H + 2;
                }
                int ox = chipX;
                com.relaxedqol.client.gui.Render2D.roundRect(g, ox, ry, cw, CHIP_H, 4, com.relaxedqol.client.gui.Theme.withAlpha(e.color(), 0.08f));
                com.relaxedqol.client.gui.Render2D.roundOutline(g, ox, ry, cw, CHIP_H, 4, com.relaxedqol.client.gui.Theme.withAlpha(e.color(), 0.30f));
                if (!e.icon().isEmpty()) {
                    g.pose().pushMatrix();
                    g.pose().translate(ox + 2, ry + 1.5f);
                    g.pose().scale(0.625f, 0.625f);
                    g.item(e.icon(), 0, 0);
                    g.pose().popMatrix();
                } else {
                    com.relaxedqol.client.gui.Render2D.roundRect(g, ox + 4, ry + 4, 4, 4, 2, e.color());
                }
                g.text(font, clip(font, e.label(), cw - 18), ox + 14, ry + 3, com.relaxedqol.client.gui.Theme.TEXT, false);
                chipX += cw + 3;
            }
        }
        g.pose().popMatrix();
    }

    /** Text cut with an ellipsis to fit in width pixels. */
    private static String clip(net.minecraft.client.gui.Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        String t = text;
        while (t.length() > 1 && font.width(t + "\u2026") > width) t = t.substring(0, t.length() - 1);
        return t + "\u2026";
    }

    /** "$1,500" / "1.5k" / "2m coins" -> 1500 / 1500 / 2000000 (NaN when it is not a number). */
    private static double amountOf(String text) {
        if (text == null) return Double.NaN;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9][0-9.,]*)\\s*([kKmMbBtT])?").matcher(text);
        if (!m.find()) return Double.NaN;
        String digits = m.group(1);
        // "1,500" = thousands separator; "1.5" = decimal; "1.500.000" = thousands separators
        if (digits.chars().filter(ch -> ch == '.').count() > 1) digits = digits.replace(".", "");
        digits = digits.replace(",", "");
        double v;
        try {
            v = Double.parseDouble(digits);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        String suffix = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        return switch (suffix) {
            case "k" -> v * 1_000;
            case "m" -> v * 1_000_000;
            case "b" -> v * 1_000_000_000;
            case "t" -> v * 1_000_000_000_000.0;
            default -> v;
        };
    }

    private static String shortAmount(double v) {
        double a = Math.abs(v);
        String s;
        if (a >= 1_000_000_000) s = String.format(Locale.ROOT, "%.2fb", a / 1_000_000_000);
        else if (a >= 1_000_000) s = String.format(Locale.ROOT, "%.2fm", a / 1_000_000);
        else if (a >= 10_000) s = String.format(Locale.ROOT, "%.1fk", a / 1_000);
        else s = String.format(Locale.ROOT, "%,.0f", a);
        return "$" + s.replace(".00", "").replace(".0k", "k");
    }

    /** The folder the pictures go to: Save Folder, or .minecraft/screenshots/Trades. */
    private File folder() {
        String raw = saveFolder.getValue().trim();
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 1) raw = raw.substring(1, raw.length() - 1).trim();
        if (raw.isEmpty()) return ScreenshotUtil.fileOf(FOLDER);
        // %APPDATA% / %USERPROFILE% style variables and a leading ~
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%([A-Za-z0-9_]+)%").matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = System.getenv(m.group(1));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        raw = out.toString();
        if (raw.startsWith("~")) raw = System.getProperty("user.home") + raw.substring(1);
        File dir = new File(raw);
        // A relative path is taken inside .minecraft
        if (!dir.isAbsolute()) dir = new File(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(), raw);
        return dir;
    }

    /** Whether an item tooltip is on screen right now (the mouse is on an item, nothing on the cursor). */
    private static boolean tooltipShowing(AbstractContainerScreen<?> screen) {
        Slot hovered = ((com.relaxedqol.mixin.AbstractContainerScreenAccessor) screen).relaxed$getHoveredSlot();
        return hovered != null && hovered.hasItem() && screen.getMenu().getCarried().isEmpty();
    }

    /** Chat / server messages (from RelaxedQolClient), to tell a completed trade from a cancelled one. */
    public void onChat(String text) {
        if (decideBy == 0 && !inTrade) return;
        String t = plain(text);
        if (!t.contains("trade")) return;
        if (t.contains("cancel") || t.contains("declin") || t.contains("expired") || t.contains("denied") || t.contains("closed")) chatVerdict = false;
        else if (t.contains("complet") || t.contains("success") || t.contains("accepted") || t.contains("finished")
            || t.contains("traded") || t.contains("confirmed")) chatVerdict = true;
    }

    /** Item id -> how many you carry. */
    private Map<String, Integer> inventoryCounts() {
        Map<String, Integer> out = new HashMap<>();
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            out.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return out;
    }

    @Override
    public String getInfo() {
        return inTrade ? (confirmStage ? "confirming" : "recording") : null;
    }
}
