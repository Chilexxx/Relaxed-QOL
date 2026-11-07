package com.relaxedqol.client.gui;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.setting.ListSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Searchable checklist for picking blocks, items, entities or packets (opened from a list setting, or from the Core
 * Protect window's Items button). a normal client window like every other one - the same frame, title bar,
 * buttons, text field and scroll bar, movable and resizable - instead of its own full-screen panel.
 */
public class ListPickerScreen extends WindowScreen {
    private static final int ROW_H = 22;
    /** Height of the toolbar / footer controls. */
    private static final int BAR_H = 16;

    private final Module module;
    private final ListSetting setting;
    private final ScrollBar bar = new ScrollBar();
    private EditBox searchBox;
    private boolean onlySelected;
    private int listY;
    private int listH;

    public ListPickerScreen(Screen parent, Module module, ListSetting setting) {
        super(parent, Component.literal(module.getName() + "  ›  " + setting.getName()), "listpicker", 340, 400);
        this.module = module;
        this.setting = setting;
    }

    @Override
    protected String titleInfo() {
        return setting.getValue().size() + " selected";
    }

    @Override
    protected void initContent() {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        // Toolbar: the search field, then an "All | Selected" switch and Clear - all the same height, in one line
        int allW = pillWidth("All");
        int selW = pillWidth("Selected 999");
        int clearW = pillWidth("Clear");
        int right = x + w;
        int cx = right - clearW;
        int sx = cx - 8 - selW;
        int ax = sx - 2 - allW;
        String typed = searchBox != null ? searchBox.getValue() : "";
        searchBox = new EditBox(font, x, y, ax - 6 - x, BAR_H, Component.literal("Search"));
        searchBox.setHint(Component.literal("Search " + kind() + "\u2026"));
        searchBox.setMaxLength(32);
        searchBox.setValue(typed);
        searchBox.setResponder(s -> bar.scroll = 0);
        addRenderableWidget(searchBox);
        setFocused(searchBox);
        pill(() -> "All", ax, y, allW, BAR_H, () -> {
            onlySelected = false;
            bar.scroll = 0;
        }, 0, () -> !onlySelected);
        pill(() -> {
            int n = setting.getValue().size();
            return n > 0 ? "Selected " + n : "Selected";
        }, sx, y, selW, BAR_H, () -> {
            onlySelected = true;
            bar.scroll = 0;
        }, 0, () -> onlySelected);
        pill(() -> "Clear", cx, y, clearW, BAR_H, setting::clear, 0, () -> false);
        int doneW = Math.max(pillWidth("Done"), 60);
        pill(() -> "Done", right - doneW, contentY() + contentH() - BAR_H, doneW, BAR_H, this::onClose, 0, () -> true);
        reserve(x, contentY() + contentH() - BAR_H, Math.max(0, w - doneW - 8), BAR_H);
    }

    /** What the list holds, for the search hint ("blocks", "items"...). */
    private String kind() {
        String n = setting.getName().toLowerCase(Locale.ROOT);
        for (String k : new String[]{"block", "item", "entit", "packet", "mob", "player"}) {
            if (n.contains(k)) return switch (k) {
                case "entit" -> "entities";
                default -> k + "s";
            };
        }
        return "the list";
    }

    // The filtered list is only rebuilt when the search / filter / selection changes
    private List<ListSetting.Option> filteredCache;
    private String cacheSearch;
    private boolean cacheOnlySelected;
    private int cacheVersion;

    private String search() {
        return searchBox == null ? "" : searchBox.getValue().trim();
    }

    private List<ListSetting.Option> filtered() {
        String s = search();
        if (filteredCache != null && s.equals(cacheSearch) && onlySelected == cacheOnlySelected
            && (!onlySelected || setting.getVersion() == cacheVersion)) {
            return filteredCache;
        }
        String query = s.toLowerCase(Locale.ROOT);
        List<ListSetting.Option> result = new ArrayList<>();
        for (ListSetting.Option option : setting.getOptions()) {
            if (onlySelected && !setting.contains(option.id())) continue;
            if (!query.isEmpty() && !option.name().toLowerCase(Locale.ROOT).contains(query) && !option.id().contains(query)) continue;
            result.add(option);
        }
        filteredCache = result;
        cacheSearch = s;
        cacheOnlySelected = onlySelected;
        cacheVersion = setting.getVersion();
        return result;
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int x = contentX();
        int w = contentW();
        int accent = Theme.accent();
        listY = contentY() + BAR_H + 6;
        listH = contentY() + contentH() - BAR_H - 6 - listY;

        List<ListSetting.Option> options = filtered();
        int total = options.size() * ROW_H + 4;
        bar.update(x + w - 4, listY, listH, total, listH);
        // One calm, recessed list area; rows inside it are flat (no box per row), split by hairlines
        Render2D.roundRect(g, x, listY, w, listH, Theme.R_ROW, 0x1F000000);
        Render2D.roundOutline(g, x, listY, w, listH, Theme.R_ROW, Theme.HAIRLINE);

        g.enableScissor(x + 1, listY + 1, x + w - 6, listY + listH - 1);
        int first = Math.max(0, (bar.scroll - 2) / ROW_H);
        int last = Math.min(options.size(), first + listH / ROW_H + 2);
        int rx = x + 3;
        int rw = w - 12;
        ListSetting.Source source = setting.getSource();
        boolean packets = source == ListSetting.Source.PACKETS_C2S || source == ListSetting.Source.PACKETS_S2C;
        for (int i = first; i < last; i++) {
            ListSetting.Option option = options.get(i);
            int ry = listY + 2 + i * ROW_H - bar.scroll;
            boolean selected = setting.contains(option.id());
            boolean hover = mouseX >= rx && mouseX < rx + rw && mouseY >= ry && mouseY < ry + ROW_H
                && mouseY >= listY && mouseY < listY + listH;
            if (i > 0) g.fill(rx + 6, ry, rx + rw - 6, ry + 1, 0x0DFFFFFF);
            if (hover || selected) {
                int fill = selected ? Theme.withAlpha(accent, hover ? 0.16f : 0.10f) : 0x0FFFFFFF;
                Render2D.roundRect(g, rx, ry + 1, rw, ROW_H - 2, Theme.R_CONTROL, fill);
            }
            int mid = ry + ROW_H / 2;
            // Check box on the left, like any checklist
            int cbx = rx + 6;
            int cby = mid - 5;
            if (selected) {
                Render2D.roundRect(g, cbx, cby, 10, 10, 3, accent);
                Render2D.checkmark(g, cbx + 1, cby + 1, 0xFFFFFFFF);
            } else {
                Render2D.roundRect(g, cbx, cby, 10, 10, 3, 0x14FFFFFF);
                Render2D.roundOutline(g, cbx, cby, 10, 10, 3, hover ? 0x80FFFFFF : 0x40FFFFFF);
            }
            int tx = cbx + 10 + 8;
            if (!option.icon().isEmpty()) {
                g.item(option.icon(), tx, mid - 8);
                tx += 16 + 6;
            }
            // The id / real packet name, faint and right-aligned - only when it says more than the name does
            String sub = packets
                ? com.relaxedqol.client.util.PacketRegistry.realName(option.id(), source == ListSetting.Source.PACKETS_C2S)
                : option.id().startsWith("minecraft:") ? option.id().substring(10) : option.id();
            String plainName = option.name().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (sub.equalsIgnoreCase(plainName)) sub = "";
            int room = rx + rw - 8 - tx;
            String subShown = sub.isEmpty() ? "" : Theme.ellipsize(sub, Math.max(0, room / 2 - 6), font::width);
            int subW = subShown.isEmpty() ? 0 : font.width(subShown) + 10;
            String name = Theme.ellipsize(option.name(), room - subW, font::width);
            g.text(font, name, tx, mid - 4, selected || hover ? Theme.TEXT : Theme.TEXT_SECONDARY, false);
            if (!subShown.isEmpty()) g.text(font, subShown, rx + rw - 8 - font.width(subShown), mid - 4, Theme.TEXT_FAINT, false);
        }
        g.disableScissor();
        if (options.isEmpty()) {
            String empty = onlySelected && search().isEmpty() ? "Nothing selected yet"
                : search().isEmpty() ? "Nothing to pick" : "No match for \"" + search() + "\"";
            g.centeredText(font, Theme.ellipsize(empty, w - 16, font::width), x + w / 2, listY + listH / 2 - 4, Theme.TEXT_FAINT);
        }
        bar.draw(g, mouseX, mouseY);

        // Footer: what is shown / picked, next to Done
        int doneW = Math.max(pillWidth("Done"), 60);
        String foot = options.size() + " shown  \u00B7  Esc to go back";
        int fy = contentY() + contentH() - BAR_H + (BAR_H - 8) / 2;
        g.text(font, Theme.ellipsize(foot, w - doneW - 12, font::width), x + 2, fy, Theme.TEXT_FAINT, false);
    }

    @Override
    protected boolean contentClicked(double mx, double my, int button) {
        if (bar.mouseClicked(mx, my)) return true;
        int x = contentX();
        int w = contentW();
        if (mx >= x + 3 && mx < x + w - 9 && my >= listY && my < listY + listH) {
            // (rows start 2 px into the list, ROW_H apart - the same as drawContent)
            int index = (int) ((my - listY - 2 + bar.scroll) / ROW_H);
            List<ListSetting.Option> options = filtered();
            if (index >= 0 && index < options.size()) setting.toggle(options.get(index).id());
            // Typing still goes to the search
            if (searchBox != null) setFocused(searchBox);
            return true;
        }
        return false;
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
        bar.scrollBy(-(int) Math.signum(scrollY) * ROW_H * 3);
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        // Just type: the search field always gets it
        if (searchBox != null && getFocused() != searchBox) setFocused(searchBox);
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Esc with a search typed: clear it first; Ctrl+Backspace clears it all
        if (searchBox != null && !searchBox.getValue().isEmpty()) {
            if (event.key() == InputConstants.KEY_ESCAPE || (event.key() == InputConstants.KEY_BACKSPACE && event.hasControlDown())) {
                searchBox.setValue("");
                bar.scroll = 0;
                return true;
            }
        }
        if (event.key() == InputConstants.KEY_BACKSPACE && searchBox != null && getFocused() != searchBox) setFocused(searchBox);
        return super.keyPressed(event);
    }
}
