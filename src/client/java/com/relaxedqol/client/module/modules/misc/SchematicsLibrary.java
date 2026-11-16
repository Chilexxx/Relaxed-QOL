package com.relaxedqol.client.module.modules.misc;

import com.relaxedqol.client.gui.SchematicLibraryScreen;
import com.relaxedqol.client.module.ActionModule;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.schematic.SchematicLibrary;
import com.relaxedqol.client.setting.Setting;

/**
 * "Schematics Library": copy what stands in the world into a schematic file - an area between two corners you
 * click (also in mid-air), or the whole chunks around you - on a server too (what your game has loaded), and paste
 * a file into a single-player world, placed by eye with a preview. Click it in the menu, bind a key, or type .schem.
 */
public class SchematicsLibrary extends ActionModule {
    /** .schem opens the window a moment later (after the chat has closed). */
    private static int openIn = -1;
    private final Setting<Boolean> lowImpact = addSetting(new Setting<>("Low Impact", false),
        "Copies and pastes are a little slower, but cost next to no memory - the copy lies in a file on disk instead of in the game's memory - "
            + "and keep the frame rate steady. For big copies, or a computer with little memory. Also .schem lowimpact");

    private final com.relaxedqol.client.setting.ActionSetting open = addSetting(new com.relaxedqol.client.setting.ActionSetting("Open Library", this::run),
        "The Schematics Library window: your files, Select an area, copy Chunks, Paste");
    private final com.relaxedqol.client.setting.ActionSetting select = addSetting(new com.relaxedqol.client.setting.ActionSetting("Select Area", () -> {
            String no = com.relaxedqol.client.schematic.PlaceMode.select();
            if (no != null) com.relaxedqol.client.util.ChatUtil.send(no);
        }),
        "Pick an area in the world: left click = corner 1, right click = corner 2 (also in mid-air), Enter saves it as a file");
    private final com.relaxedqol.client.setting.ActionSetting stop = addSetting(new com.relaxedqol.client.setting.ActionSetting("Stop Copy / Paste", () -> {
            String a = SchematicLibrary.copyState() != null ? SchematicLibrary.stopCopy() : null;
            String b = SchematicLibrary.pasteState() != null ? SchematicLibrary.stopPaste() : null;
            com.relaxedqol.client.util.ChatUtil.send(a == null && b == null ? "§7Nothing is being copied or pasted." : (a != null ? a : "") + (a != null && b != null ? " " : "") + (b != null ? b : ""));
        }),
        "Ends a copy or a paste that is running (what is done so far stays)");

    public SchematicsLibrary() {
        super("Schematics Library", "Copy an area or whole chunks into a schematic file (on a server too) and paste files in single player", ModuleCategory.MISC);
        SchematicLibrary.lowImpactOn = lowImpact::getValue;
        SchematicLibrary.lowImpactSet = lowImpact::setValue;
    }

    public static void openSoon() {
        openIn = 2;
    }

    @Override
    public void run() {
        mc.gui.setScreen(new SchematicLibraryScreen(mc.gui.screen()));
    }

    @Override
    public void alwaysTick() {
        if (openIn >= 0 && openIn-- == 0 && mc.player != null) mc.gui.setScreen(new SchematicLibraryScreen(null));
    }
}
