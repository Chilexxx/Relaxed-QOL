package com.relaxedqol.client.module.modules.misc;

import com.relaxedqol.client.gui.SchematicLibraryScreen;
import com.relaxedqol.client.module.ActionModule;
import com.relaxedqol.client.module.ModuleCategory;

/**
 * "Schematics Library": copy what stands in the world into a schematic file - an area between two corners you
 * click (also in mid-air), or the whole chunks around you - on a server too (what your game has loaded), and paste
 * a file into a single-player world, placed by eye with a preview. Click it in the menu, bind a key, or type .schem.
 */
public class SchematicsLibrary extends ActionModule {
    /** .schem opens the window a moment later (after the chat has closed). */
    private static int openIn = -1;

    public SchematicsLibrary() {
        super("Schematics Library", "Copy an area or whole chunks into a schematic file (on a server too) and paste files in single player", ModuleCategory.MISC);
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
