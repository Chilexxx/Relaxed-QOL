package com.relaxedqol.client.module.modules.misc;

import com.relaxedqol.client.gui.NotepadScreen;
import com.relaxedqol.client.module.ActionModule;
import com.relaxedqol.client.module.ModuleCategory;

/**
 * "Notepad": a big text window to write as much as you want - long lists, plans, trades, coordinates - over many
 * lines and pages. Click it in the menu, bind a key to it, or type .notepad. Saved by itself (notepad.json).
 * For short notes on the HUD use Notes.
 */
public class Notepad extends ActionModule {
    /** .notepad opens the window a moment later (after the chat has closed). */
    private static int openIn = -1;

    public Notepad() {
        super("Notepad", "A big notepad to write a lot in - many lines and pages, saved automatically", ModuleCategory.MISC);
    }

    public static void openSoon() {
        openIn = 2;
    }

    @Override
    public void run() {
        mc.gui.setScreen(new NotepadScreen(mc.gui.screen()));
    }

    @Override
    public void alwaysTick() {
        if (openIn >= 0 && openIn-- == 0) {
            openIn = -1;
            run();
        }
    }
}
