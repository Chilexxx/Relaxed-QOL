package com.relaxedqol.client.gui;

import com.relaxedqol.client.ConfigManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * "What's new": shown once after an update (on the title screen), and any time from Settings > What's New or
 * .whatsnew. The text comes from assets/relaxed-qol/changelog.txt:
 * <pre>
 * # 1.1 | 10 Nov 2026
 * + Setting Profiles: keep different setups and switch in a click     (NEW)
 * * Pop-ups: follow the style you picked                              (IMPROVED)
 * - Account button: long names no longer overlap                      (FIXED)
 * </pre>
 * A release is "seen" once the window is closed; a new "#" line at the top shows it again.
 */
public class ChangelogScreen extends WindowScreen {
    private record Entry(char kind, String title, String body) {}

    private record Release(String version, String date, List<Entry> entries) {}

    private static List<Release> cache;

    private final ScrollBar bar = new ScrollBar();
    private final long shownAt = System.currentTimeMillis();
    /** Wrapped text per entry for the width it was wrapped at. */
    private final java.util.Map<Entry, List<String>> wrapped = new java.util.IdentityHashMap<>();
    private int wrapW = -1;

    public ChangelogScreen(Screen parent) {
        super(parent, Component.literal("What's New"), "changelog", 330, 290);
    }

    public static void open(Screen parent) {
        net.minecraft.client.Minecraft.getInstance().gui.setScreen(new ChangelogScreen(parent));
    }

    // ------------------------------------------------------------------ the text

    private static List<Release> releases() {
        if (cache != null) return cache;
        List<Release> out = new ArrayList<>();
        String path = "/assets/" + com.relaxedqol.RelaxedQolMod.MOD_ID + "/changelog.txt";
        try (InputStream in = ChangelogScreen.class.getResourceAsStream(path)) {
            if (in != null) {
                Release cur = null;
                for (String raw : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    String line = raw.strip();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("#")) {
                        String[] parts = line.substring(1).split("\\|", 2);
                        cur = new Release(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "", new ArrayList<>());
                        out.add(cur);
                    } else if (cur != null && "+*-".indexOf(line.charAt(0)) >= 0) {
                        String text = line.substring(1).trim();
                        int colon = text.indexOf(": ");
                        cur.entries().add(colon > 0 && colon < 40
                            ? new Entry(line.charAt(0), text.substring(0, colon), text.substring(colon + 2))
                            : new Entry(line.charAt(0), "", text));
                    }
                }
            }
        } catch (Exception e) {
            com.relaxedqol.RelaxedQolMod.LOGGER.warn("What's New: could not read the changelog", e);
        }
        cache = out;
        return out;
    }

    private static Path seenFile() throws java.io.IOException {
        return ConfigManager.folder().resolve("changelog-seen.txt");
    }

    /** The newest release hasn't been shown yet (a first install counts as seen: nothing is "new" then). */
    public static boolean unseen() {
        List<Release> r = releases();
        if (r.isEmpty()) return false;
        try {
            Path f = seenFile();
            if (!Files.isRegularFile(f)) {
                // First start with the client: remember the current release without popping up
                boolean firstRun = !Files.isRegularFile(ConfigManager.folder().resolve(ConfigManager.DEFAULT + ".properties"));
                if (firstRun) {
                    markSeen();
                    return false;
                }
                return true;
            }
            return !Files.readString(f, StandardCharsets.UTF_8).trim().equals(r.get(0).version());
        } catch (Exception e) {
            return false;
        }
    }

    private static void markSeen() {
        List<Release> r = releases();
        if (r.isEmpty()) return;
        try {
            Files.writeString(seenFile(), r.get(0).version(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // shown again next time
        }
    }

    private static boolean checked;

    /** From the title screen: once per game start, the window if there is something new. */
    public static void maybeShow(Screen titleScreen) {
        if (checked) return;
        checked = true;
        if (!unseen()) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.gui.screen() == titleScreen) open(titleScreen);
        });
    }

    /** The client's name as it is installed ("Relaxed QOL", "Chillaxed Client"...). */
    private static String clientName() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(com.relaxedqol.RelaxedQolMod.MOD_ID)
            .map(c -> c.getMetadata().getName()).orElse("the client");
    }

    // ------------------------------------------------------------------ window

    @Override
    protected void initContent() {
        int w = 90;
        pill(() -> "Got it", contentX() + contentW() - w, contentY() + contentH() - 15, w, 15, this::onClose, 0, () -> true);
    }

    @Override
    public void onClose() {
        markSeen();
        super.onClose();
    }

    private static final int HERO_H = 46;

    private static int kindColor(char kind) {
        return switch (kind) {
            case '+' -> Theme.accent();
            case '*' -> 0xFF5AA9FF;
            default -> Theme.SUCCESS;
        };
    }

    private static String kindLabel(char kind) {
        return switch (kind) {
            case '+' -> "NEW";
            case '*' -> "BETTER";
            default -> "FIXED";
        };
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        List<Release> list = releases();
        drawHero(g, x, y, w, list.isEmpty() ? null : list.get(0));

        // The list of changes
        int top = y + HERO_H + 6;
        int h = y + contentH() - 21 - top;
        int textX = 50;
        int textW = w - textX - 12;
        if (textW != wrapW) {
            wrapped.clear();
            wrapW = textW;
        }
        int total = 0;
        for (Release r : list) {
            total += 18;
            for (Entry e : r.entries()) total += entryHeight(e, textW);
            total += 6;
        }
        bar.update(x + w - 4, top, h, total, h);
        g.enableScissor(x, top, x + w, top + h);
        int cy = top - bar.scroll;
        long age = System.currentTimeMillis() - shownAt;
        int index = 0;
        for (int ri = 0; ri < list.size(); ri++) {
            Release r = list.get(ri);
            // Release line: "1.1" + date, and a hairline to the right
            if (cy + 18 > top && cy < top + h) {
                Component v = Component.literal(ri == 0 ? r.version() + "  ·  latest" : r.version()).withStyle(ChatFormatting.BOLD);
                g.text(font, v, x + 2, cy + 4, ri == 0 ? Theme.TEXT : Theme.TEXT_SECONDARY, false);
                int vx = x + 2 + font.width(v) + 6;
                if (!r.date().isEmpty()) {
                    g.text(font, r.date(), vx, cy + 4, Theme.TEXT_FAINT, false);
                    vx += font.width(r.date()) + 6;
                }
                if (vx < x + w - 8) g.fill(vx, cy + 8, x + w - 8, cy + 9, Theme.HAIRLINE);
            }
            cy += 18;
            for (Entry e : r.entries()) {
                int eh = entryHeight(e, textW);
                if (cy + eh > top && cy < top + h) {
                    // Each line slides in a moment after the one above when the window opens
                    float t = Math.max(0f, Math.min(1f, (age - index * 35L) / 260f));
                    float ease = Render2D.easeOut(t);
                    int dx = Math.round((1f - ease) * 10);
                    drawEntry(g, e, x + dx, cy, textX, textW, ease);
                }
                cy += eh;
                index++;
            }
            cy += 6;
        }
        g.disableScissor();
        bar.draw(g, mouseX, mouseY);
        // Soft fade where the list runs under the hero / buttons
        if (!VanillaStyle.on() && bar.scroll > 0) g.fillGradient(x, top, x + w - 6, top + 6, 0x30000000, 0x00000000);

        String hint = "Open again: Settings > What's New";
        g.text(font, Theme.ellipsize(hint, w - 100, font::width), x, y + contentH() - 11, Theme.TEXT_FAINT, false);
    }

    /** The top: a soft accent glow, the client's name, "What's new" big, and the version as a tag. */
    private void drawHero(GuiGraphicsExtractor g, int x, int y, int w, Release latest) {
        int accent = Theme.accent();
        if (VanillaStyle.on()) {
            g.fill(x, y, x + w, y + HERO_H, 0x80000000);
            VanillaStyle.bevel(g, x, y, w, HERO_H);
        } else {
            Render2D.roundRect(g, x, y, w, HERO_H, Theme.R_ROW, Theme.withAlpha(accent, CleanStyle.on() ? 0.10f : 0.14f));
            Render2D.clipRounded(g, x, y, w, HERO_H, Theme.R_ROW, () ->
                Render2D.horizontalGradient(g, x, y, w, HERO_H, Theme.withAlpha(accent, 0.32f), 0x00000000));
            if (CleanStyle.on()) Render2D.roundRect(g, x + 1, y + 1, w - 2, HERO_H / 2, Math.max(0, Theme.R_ROW - 1), 0x10FFFFFF);
            Render2D.roundOutline(g, x, y, w, HERO_H, Theme.R_ROW, Theme.withAlpha(accent, 0.45f));
        }
        g.text(font, clientName().toUpperCase(java.util.Locale.ROOT), x + 10, y + 8,
            VanillaStyle.on() ? VanillaStyle.TEXT_GRAY : Theme.mix(accent, 0xFFFFFFFF, 0.5f), VanillaStyle.on());
        // "What's new" at 1.5x
        g.pose().pushMatrix();
        g.pose().translate(x + 10, y + 21);
        g.pose().scale(1.5f, 1.5f);
        g.text(font, Component.literal("What's new").withStyle(ChatFormatting.BOLD), 0, 0, 0xFFFFFFFF, VanillaStyle.on());
        g.pose().popMatrix();
        if (latest != null) {
            String v = "v" + latest.version();
            int tw = font.width(v) + 8;
            UiBits.tag(g, font, v, x + w - tw - 10, y + 8, accent);
            if (!latest.date().isEmpty()) {
                g.text(font, latest.date(), x + w - 10 - font.width(latest.date()), y + 24, Theme.TEXT_DIM, VanillaStyle.on());
            }
        }
    }

    private List<String> lines(Entry e, int textW) {
        return wrapped.computeIfAbsent(e, k -> {
            String text = k.title().isEmpty() ? k.body() : k.title() + " - " + k.body();
            // Bold names are a little wider than measured: leave them room
            return UiBits.wrap(font, text, textW - k.title().length() - 2);
        });
    }

    private int entryHeight(Entry e, int textW) {
        return lines(e, textW).size() * 10 + 6;
    }

    private void drawEntry(GuiGraphicsExtractor g, Entry e, int x, int y, int textX, int textW, float alpha) {
        int color = kindColor(e.kind());
        String label = kindLabel(e.kind());
        int a = Math.round(alpha * 255);
        if (a < 8) return;
        // The tag, fixed width so the text lines up
        int tagW = 40;
        int tx = x + 4;
        if (VanillaStyle.on()) {
            g.fill(tx, y + 1, tx + tagW, y + 11, Theme.fade(0xFF000000, alpha));
            g.fill(tx, y + 1, tx + 1, y + 11, Theme.fade(color, alpha));
            g.centeredText(font, label, tx + tagW / 2, y + 2, Theme.fade(0xFFFFFFFF, alpha));
        } else {
            int r = Math.min(5, Theme.R_CONTROL + 1);
            Render2D.roundRect(g, tx, y + 1, tagW, 10, r, Theme.fade(Theme.withAlpha(color, 0.20f), alpha));
            Render2D.roundOutline(g, tx, y + 1, tagW, 10, r, Theme.fade(Theme.withAlpha(color, 0.50f), alpha));
            g.centeredText(font, label, tx + tagW / 2, y + 2, Theme.fade(Theme.mix(color, 0xFFFFFFFF, 0.55f), alpha));
        }
        // The text: its name bright, the explanation softer
        List<String> ls = lines(e, textW);
        int ly = y + 2;
        boolean inTitle = !e.title().isEmpty();
        int titleLen = e.title().length();
        int done = 0;
        for (String line : ls) {
            int lx = x + textX;
            if (inTitle && done < titleLen) {
                int cut = Math.min(line.length(), titleLen - done);
                String head = line.substring(0, cut);
                String rest = line.substring(cut);
                g.text(font, Component.literal(head).withStyle(ChatFormatting.BOLD), lx, ly, Theme.fade(Theme.TEXT, alpha), VanillaStyle.on());
                lx += font.width(Component.literal(head).withStyle(ChatFormatting.BOLD));
                if (!rest.isEmpty()) g.text(font, rest, lx, ly, Theme.fade(Theme.TEXT_DIM, alpha), VanillaStyle.on());
            } else {
                g.text(font, line, lx, ly, Theme.fade(Theme.TEXT_DIM, alpha), VanillaStyle.on());
            }
            done += line.length() + 1;
            ly += 10;
        }
    }

    @Override
    protected boolean contentClicked(double mx, double my, int button) {
        return bar.mouseClicked(mx, my);
    }

    @Override
    protected boolean contentDragged(double mx, double my) {
        return bar.mouseDragged(my);
    }

    @Override
    protected void contentReleased() {
        bar.release();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        bar.scrollBy(-(int) Math.signum(scrollY) * 30);
        return true;
    }
}
