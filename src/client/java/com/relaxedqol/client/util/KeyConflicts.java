package com.relaxedqol.client.util;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.setting.KeySetting;
import com.relaxedqol.client.setting.Setting;

import java.util.ArrayList;
import java.util.List;

/** Warns in chat when a key you just bound is already used by another module or key setting. */
public final class KeyConflicts {
    private KeyConflicts() {}

    /** setting = null: the module's own toggle key. */
    public static void check(Module owner, Setting<?> setting, int key) {
        if (key < 0 || RelaxedQolClient.moduleManager == null) return;
        List<String> others = new ArrayList<>();
        for (Module m : RelaxedQolClient.moduleManager.getModules()) {
            if (m.getKey() == key && !(m == owner && setting == null)) others.add(m.displayName());
            for (Setting<?> s : m.getSettings()) {
                if (s instanceof KeySetting ks && ks.getKey() == key && s != setting) others.add(m.displayName() + " > " + s.getName());
            }
        }
        if (others.isEmpty()) return;
        String what = owner == null ? "" : owner.displayName() + (setting != null ? " > " + setting.getName() : "");
        String msg = "Key " + KeyUtil.name(key) + " is also used by " + String.join(", ", others);
        ChatUtil.send("§c⚠ §f" + msg + (what.isEmpty() ? "" : " §7(just set for " + what + ")"));
        com.relaxedqol.client.gui.Toasts.show("Key used twice", msg, 0xFFFBBF24, 5000);
    }
}
