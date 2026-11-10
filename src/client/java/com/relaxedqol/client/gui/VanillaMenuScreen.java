package com.relaxedqol.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.relaxedqol.client.ConfigManager;
import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.KeySetting;
import com.relaxedqol.client.setting.ListSetting;
import com.relaxedqol.client.setting.Setting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings > Vanilla Style: the client menu built from Minecraft's own buttons and sliders, laid out like the game's
 * Options screens (Video Settings): a centred title, a scrolling list of 150 px wide controls in two columns between
 * the header and footer lines, and Done at the bottom.
 *
 * Three pages: the categories (plus a search box that lists matching modules), the modules of one category
 * ("Fullbright: ON" + a "..." button for its settings), and one module's settings ("Keep Deaths: 5" as a slider,
 * "Beams: ON", modes cycle when clicked, colours / lists / texts open their editor). Esc / Done goes back one page.
 */
public class VanillaMenuScreen extends Screen {
    private static final int HEADER = 33;
    private static final int FOOTER = 33;
    private static final int ROW = 24;
    private static final int COL = 150;
    private static final int GAP = 10;
    private static final Identifier HEADER_LINE = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/header_separator.png");
    private static final Identifier FOOTER_LINE = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/footer_separator.png");
    private static final Identifier LIST_BG = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/menu_list_background.png");
    private static final Identifier LIST_BG_INWORLD = Identifier.fromNamespaceAndPath("minecraft", "textures/gui/inworld_menu_list_background.png");
    private static final Identifier SCROLLER = Identifier.fromNamespaceAndPath("minecraft", "widget/scroller");
    private static final Identifier SCROLLER_BG = Identifier.fromNamespaceAndPath("minecraft", "widget/scroller_background");

    private final Screen parent;
    /** null = the category page; a category = its modules; module = its settings. */
    private ModuleCategory category;
    private Module module;
    /** The page to go back to from a module opened through the search (null = the category page). */
    private ModuleCategory moduleFrom;
    private EditBox search;
    private String searchText = "";

    /** One control in the scrolling list, at its spot in the list (before scrolling). */
    private record Entry(AbstractWidget widget, int baseY, String help) {}

    private final List<Entry> entries = new ArrayList<>();
    private final List<String[]> labels = new ArrayList<>(); // {text, baseY} section titles in the list
    private int contentHeight;
    private double scroll;
    /** A key bind waiting for its key: a setting, or the module's own keybind (setting null). */
    private KeySetting bindingSetting;
    private Module bindingModule;

    public VanillaMenuScreen(Screen parent) {
        this(parent, null, null);
    }

    public VanillaMenuScreen(Screen parent, ModuleCategory category, Module module) {
        super(Component.literal("Relaxed QOL"));
        this.parent = parent;
        this.category = category;
        this.module = module;
    }

    private int top() {
        return HEADER + (category == null && module == null ? 26 : 0);
    }

    private int bottom() {
        return height - FOOTER;
    }

    @Override
    protected void init() {
        entries.clear();
        labels.clear();
        if (category == null && module == null) {
            search = new EditBox(font, width / 2 - (COL * 2 + GAP) / 2, HEADER + 4, COL * 2 + GAP, 18, Component.literal("Search"));
            search.setHint(Component.literal("Search modules..."));
            search.setValue(searchText);
            search.setResponder(text -> {
                searchText = text;
                scroll = 0;
                rebuild();
            });
            addRenderableWidget(search);
        } else {
            search = null;
        }
        addRenderableWidget(Button.builder(Component.literal(module != null || category != null ? "Back" : "Done"), b -> onClose())
            .bounds(width / 2 - 100, height - 27, 200, 20).build());
        buildList();
        layoutList();
    }

    /** Rebuilds the list (after a change that shows / hides settings, or the search). The search box keeps focus. */
    private void rebuild() {
        for (Entry e : entries) removeWidget(e.widget());
        entries.clear();
        labels.clear();
        buildList();
        layoutList();
    }

    // ------------------------------------------------------------------ the pages

    private void buildList() {
        int y = top() + 4;
        if (module != null) {
            y = buildModule(y);
        } else if (category != null) {
            y = buildModules(RelaxedQolClient.moduleManager.getModulesByCategory(category), y);
        } else if (!searchText.isBlank()) {
            String q = searchText.trim().toLowerCase(Locale.ROOT);
            List<Module> found = new ArrayList<>();
            for (Module m : RelaxedQolClient.moduleManager.getModules()) {
                if (!m.getCategory().active()) continue;
                if (m.displayName().toLowerCase(Locale.ROOT).contains(q) || m.getName().toLowerCase(Locale.ROOT).contains(q)) found.add(m);
            }
            if (found.isEmpty()) labels.add(new String[]{"No modules found", String.valueOf(y)});
            y = buildModules(found, y);
        } else {
            y = buildCategories(y);
        }
        contentHeight = y - top();
    }

    private int buildCategories(int y) {
        List<ModuleCategory> cats = new ArrayList<>();
        for (ModuleCategory c : ModuleCategory.values()) {
            if (!c.active() || c == ModuleCategory.CLIENT) continue;
            if (RelaxedQolClient.moduleManager.getModulesByCategory(c).isEmpty()) continue;
            cats.add(c);
        }
        int left = width / 2 - COL - GAP / 2;
        for (int i = 0; i < cats.size(); i++) {
            ModuleCategory c = cats.get(i);
            int x = i % 2 == 0 ? left : left + COL + GAP;
            add(Button.builder(Component.literal(c.getName() + "..."), b -> open(c, null)).bounds(x, 0, COL, 20).build(), y, null);
            if (i % 2 == 1 || i == cats.size() - 1) y += ROW;
        }
        y += 8;
        labels.add(new String[]{"Client", String.valueOf(y)});
        y += 14;
        Module settings = settingsModule();
        if (settings != null) {
            add(Button.builder(Component.literal("Settings..."), b -> open(null, settings)).bounds(left, 0, COL, 20).build(), y, null);
        }
        add(Button.builder(Component.literal("Edit HUD & Theme..."), b -> HudEditorScreen.open()).bounds(left + COL + GAP, 0, COL, 20).build(), y, null);
        y += ROW;
        add(Button.builder(Component.literal("Accounts..."), b -> AccountsScreen.open(this)).bounds(left, 0, COL, 20).build(), y, null);
        add(Button.builder(Component.literal("Notepad..."), b -> minecraft.gui.setScreen(new NotepadScreen(this))).bounds(left + COL + GAP, 0, COL, 20).build(), y, null);
        y += ROW;
        return y;
    }

    private Module settingsModule() {
        List<Module> list = RelaxedQolClient.moduleManager.getModulesByCategory(ModuleCategory.CLIENT);
        return list.isEmpty() ? null : list.get(0);
    }

    /** One row per module: "Name: ON" (click = on / off) and "..." for its settings. */
    private int buildModules(List<Module> modules, int y) {
        int left = width / 2 - COL - GAP / 2;
        int full = COL * 2 + GAP;
        for (Module m : modules) {
            boolean settingsOnly = m.isSettingsOnly();
            if (settingsOnly) {
                add(Button.builder(Component.literal(m.displayName() + "..."), b -> open(category, m)).bounds(left, 0, full, 20).build(),
                    y, m.getDescription());
            } else {
                add(Button.builder(onOff(m.displayName(), m.isEnabled()), b -> {
                    m.toggle();
                    b.setMessage(onOff(m.displayName(), m.isEnabled()));
                }).bounds(left, 0, full - 24, 20).build(), y, m.getDescription());
                add(Button.builder(Component.literal("..."), b -> open(category, m)).bounds(left + full - 20, 0, 20, 20).build(),
                    y, "Settings of " + m.displayName());
            }
            y += ROW;
        }
        return y;
    }

    /** The module's settings, two per row like the game's options; on / off and the keybind first. */
    private int buildModule(int y) {
        int left = width / 2 - COL - GAP / 2;
        int full = COL * 2 + GAP;
        Module m = module;
        if (!m.isSettingsOnly()) {
            add(Button.builder(onOff("Enabled", m.isEnabled()), b -> {
                m.toggle();
                b.setMessage(onOff("Enabled", m.isEnabled()));
            }).bounds(left, 0, COL, 20).build(), y, m.getDescription());
            add(Button.builder(Component.literal(keyLabel()), b -> {
                bindingModule = m;
                bindingSetting = null;
                b.setMessage(Component.literal("Keybind: > Press a key <"));
            }).bounds(left + COL + GAP, 0, COL, 20).build(), y, "Click, then press a key (Esc / Backspace = no key)");
            y += ROW;
        }
        int col = 0;
        for (Setting<?> s : m.getSettings()) {
            if (s.isHidden()) continue;
            AbstractWidget w = control(s, 0, COL);
            if (w == null) continue;
            int x = col == 0 ? left : left + COL + GAP;
            w.setX(x);
            add(w, y, s.getHelp());
            col++;
            if (col == 2) {
                col = 0;
                y += ROW;
            }
        }
        if (col != 0) y += ROW;
        y += 4;
        add(Button.builder(Component.literal("Reset to Default"), b -> {
            m.resetSettings();
            ConfigManager.save(ConfigManager.DEFAULT);
            rebuild();
        }).bounds(left, 0, full, 20).build(), y, "Puts every setting of this module back to how it was");
        y += ROW;
        return y;
    }

    private String keyLabel() {
        return "Keybind: " + (module.getKey() < 0 ? "None" : com.relaxedqol.client.util.KeyUtil.name(module.getKey()));
    }

    /** Minecraft's own control for a setting. */
    private AbstractWidget control(Setting<?> s, int x, int w) {
        String name = s.getName();
        if (s.isAction()) {
            com.relaxedqol.client.setting.ActionSetting action = (com.relaxedqol.client.setting.ActionSetting) s;
            return Button.builder(Component.literal(name), b -> action.run()).bounds(x, 0, w, 20).build();
        }
        if (s.isKey()) {
            KeySetting key = (KeySetting) s;
            return Button.builder(Component.literal(name + ": " + key.display()), b -> {
                bindingSetting = key;
                bindingModule = null;
                b.setMessage(Component.literal(name + ": > Press a key <"));
            }).bounds(x, 0, w, 20).build();
        }
        if (s.isBoolean()) {
            @SuppressWarnings("unchecked")
            Setting<Boolean> bool = (Setting<Boolean>) s;
            return Button.builder(onOff(name, bool.getValue()), b -> {
                bool.setValue(!bool.getValue());
                changed();
            }).bounds(x, 0, w, 20).build();
        }
        if (s.isNumber()) {
            return new NumberSlider(x, w, s);
        }
        if (s.isMode()) {
            return Button.builder(Component.literal(name + ": " + s.display()), b -> {
                s.cycle(1);
                changed();
            }).bounds(x, 0, w, 20).build();
        }
        if (s.isColor()) {
            com.relaxedqol.client.setting.ColorSetting color = (com.relaxedqol.client.setting.ColorSetting) s;
            return Button.builder(Component.literal(name + ": " + color.label()), b ->
                ColorPickerScreen.open(this, color, () -> ConfigManager.save(ConfigManager.DEFAULT))).bounds(x, 0, w, 20).build();
        }
        if (s.isList()) {
            Module owner = module;
            return Button.builder(Component.literal(name + "..."), b ->
                minecraft.gui.setScreen(new ListPickerScreen(this, owner, (ListSetting) s))).bounds(x, 0, w, 20).build();
        }
        if (s.isText()) {
            return Button.builder(Component.literal(name + ": " + s.display()), b -> {
                String help = s.getHelp() != null ? s.getHelp() : "";
                TextPrompt.open(this, name, help, s.display(), value -> {
                    s.parse(value.trim());
                    ConfigManager.save(ConfigManager.DEFAULT);
                });
            }).bounds(x, 0, w, 20).build();
        }
        return null;
    }

    /** A setting changed: save, and rebuild (other settings may show / hide, labels change). */
    private void changed() {
        ConfigManager.save(ConfigManager.DEFAULT);
        // Switching Vanilla Style off here goes straight back to the normal menu
        if (!com.relaxedqol.client.module.modules.client.ClickGUI.vanillaStyle()) {
            minecraft.gui.setScreen(new ClickGuiScreen(parent));
            return;
        }
        rebuild();
    }

    private static Component onOff(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    /** Minecraft's slider for a number setting ("Keep Deaths: 5"). */
    private final class NumberSlider extends AbstractSliderButton {
        private final Setting<?> setting;

        NumberSlider(int x, int w, Setting<?> setting) {
            super(x, 0, w, 20, Component.literal(setting.getName() + ": " + setting.display()), fraction(setting));
            this.setting = setting;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(setting.getName() + ": " + setting.display()));
        }

        @Override
        protected void applyValue() {
            setting.setFraction(value);
        }
    }

    private static double fraction(Setting<?> s) {
        double min = s.getMin().doubleValue();
        double max = s.getMax().doubleValue();
        double v = ((Number) s.getValue()).doubleValue();
        return max <= min ? 0 : Math.max(0, Math.min(1, (v - min) / (max - min)));
    }

    // ------------------------------------------------------------------ list, scrolling

    private void add(AbstractWidget w, int baseY, String help) {
        entries.add(new Entry(w, baseY, help));
        addRenderableWidget(w);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight + 4 - (bottom() - top()));
    }

    /** Puts every control at its scrolled spot; ones outside the list area are hidden. */
    private void layoutList() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
        for (Entry e : entries) {
            int y = e.baseY() - (int) scroll;
            e.widget().setY(y);
            e.widget().visible = y >= top() && y + e.widget().getHeight() <= bottom();
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (maxScroll() > 0) {
            scroll -= scrollY * ROW;
            layoutList();
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    // ------------------------------------------------------------------ keys, navigation

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (bindingSetting != null || bindingModule != null) {
            boolean none = key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE;
            if (bindingSetting != null) {
                if (none) {
                    if (bindingSetting.allowsNone()) bindingSetting.setValue(-1);
                    else bindingSetting.reset();
                } else {
                    bindingSetting.setValue(key);
                    com.relaxedqol.client.util.KeyConflicts.check(module, bindingSetting, key);
                }
            } else {
                bindingModule.setKey(none ? -1 : key);
                if (!none) com.relaxedqol.client.util.KeyConflicts.check(bindingModule, null, key);
            }
            bindingSetting = null;
            bindingModule = null;
            ConfigManager.save(ConfigManager.DEFAULT);
            rebuild();
            return true;
        }
        return super.keyPressed(event);
    }

    private void open(ModuleCategory c, Module m) {
        VanillaMenuScreen next = new VanillaMenuScreen(parent, c, m);
        next.moduleFrom = category;
        // Back from a module found by the search goes to the search again
        if (m != null && category == null && module == null) next.searchText = searchText;
        if (m != null && category == null && module == null) next.backToSearch = true;
        minecraft.gui.setScreen(next);
    }

    /** Opened from the search on the first page: Back returns there with the same search. */
    private boolean backToSearch;

    @Override
    public void onClose() {
        ConfigManager.save(ConfigManager.DEFAULT);
        if (module != null) {
            VanillaMenuScreen back = new VanillaMenuScreen(parent, backToSearch ? null : moduleFrom, null);
            if (backToSearch) back.searchText = searchText;
            minecraft.gui.setScreen(back);
        } else if (category != null) {
            minecraft.gui.setScreen(new VanillaMenuScreen(parent));
        } else {
            minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int top = top();
        int bottom = bottom();
        String title = module != null ? module.displayName() : category != null ? category.getName() : "Relaxed QOL";
        g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        // The hovered control's explanation, under the title
        String help = null;
        for (Entry e : entries) {
            if (e.widget().visible && e.help() != null && e.widget().isHovered()) help = e.help();
        }
        if (help == null && module != null) help = module.getDescription();
        if (help != null && !help.isEmpty()) {
            String shown = Theme.ellipsize(help, width - 40, font::width);
            g.centeredText(font, shown, width / 2, 22, 0xFFA0A0A0);
        }
        // Section titles inside the list
        for (String[] label : labels) {
            int y = Integer.parseInt(label[1]) - (int) scroll;
            if (y >= top && y + 9 <= bottom) g.centeredText(font, label[0], width / 2, y + 2, 0xFFFFFFFF);
        }
        // The scroll bar on the right, like the game's lists
        int max = maxScroll();
        if (max > 0) {
            int sx = width / 2 + COL + GAP / 2 + 10;
            int h = bottom - top;
            int thumb = Math.max(32, h * h / (contentHeight + 4));
            int ty = top + (int) ((h - thumb) * (scroll / max));
            g.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BG, sx, top, 6, h);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER, sx, ty, 6, thumb);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        // The darker list area between the header and footer lines, like the game's options screens
        int top = HEADER;
        int bottom = bottom();
        Identifier bg = minecraft.level != null ? LIST_BG_INWORLD : LIST_BG;
        g.blit(RenderPipelines.GUI_TEXTURED, bg, 0, top, 0, 0, width, bottom - top, 32, 32, 0xFFFFFFFF);
        g.blit(RenderPipelines.GUI_TEXTURED, HEADER_LINE, 0, top - 2, 0, 0, width, 2, 32, 2, 0xFFFFFFFF);
        g.blit(RenderPipelines.GUI_TEXTURED, FOOTER_LINE, 0, bottom, 0, 0, width, 2, 32, 2, 0xFFFFFFFF);
    }
}
