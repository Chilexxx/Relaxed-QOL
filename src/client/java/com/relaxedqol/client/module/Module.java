package com.relaxedqol.client.module;

import com.relaxedqol.RelaxedQolMod;
import com.relaxedqol.client.setting.Setting;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {
    protected final Minecraft mc = Minecraft.getInstance();

    private final String name;
    private final String description;
    private final ModuleCategory category;
    private boolean enabled = false;
    /** GLFW key code, or -1 for no keybind. */
    private int key = -1;

    private final List<Setting<?>> settings = new ArrayList<>();

    protected Module(String name, String description, ModuleCategory category) {
        this.name = name;
        this.description = description;
        this.category = category;
    }

    /** Toggle by the player (GUI, keybind, command). */
    public void toggle() {
        if (isSettingsOnly()) return;
        setEnabled(!enabled);
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        try {
            if (enabled) onEnable();
            else onDisable();
        } catch (Exception e) {
            RelaxedQolMod.LOGGER.error("Error toggling module {}", name, e);
        }
    }

    protected void onEnable() {}

    protected void onDisable() {}

    /** Called every client tick while the module is enabled. */
    public void onTick() {}

    /** Called every tick whether the module is on or not (for keys that open a module's window...). */
    public void alwaysTick() {}

    /** Called every frame while enabled, inside the world render pass. Draw with net.minecraft.gizmos.Gizmos. */
    public void onRender3D(float partialTicks) {}

    /** Modules that only hold settings (no on/off). Clicking them in the menu opens their settings. */
    public boolean isSettingsOnly() {
        return false;
    }

    /** Short extra text shown next to the name in the HUD module list (e.g. a mode), or null. */
    public String getInfo() {
        return null;
    }

    /** Whether this module's keybind also works while a chest/inventory is open (Packets modules always do). */
    public boolean worksInScreens() {
        return false;
    }

    /** Whether the enabled state is restored from the config on startup. */
    public boolean savesEnabledState() {
        return true;
    }

    protected <S extends Setting<?>> S addSetting(S setting) {
        // The "Reset to Default" button always stays the last row
        int at = resetButton != null ? settings.indexOf(resetButton) : -1;
        if (at >= 0) settings.add(at, setting);
        else settings.add(setting);
        return setting;
    }

    /** Adds a setting with an explanation that shows when you hover it in the menu. */
    protected <S extends Setting<?>> S addSetting(S setting, String help) {
        setting.describe(help);
        return addSetting(setting);
    }

    /** Every module with settings gets this button at the end of its settings. */
    private com.relaxedqol.client.setting.ActionSetting resetButton;
    private long resetArmedAt;

    /**
     * Puts every setting of this module back to its default (on/off switches, numbers, modes, lists, keys).
     * Hidden ones (card positions, stored names) are left alone; so is whether the module is on, and its keybind.
     */
    public void resetSettings() {
        // Click twice (within 3 s) so one stray click never wipes a module's settings
        long now = System.currentTimeMillis();
        if (now - resetArmedAt > 3000) {
            resetArmedAt = now;
            com.relaxedqol.client.util.ChatUtil.send("§e" + name + "§7: click §fReset to Default§7 again to reset all its settings");
            return;
        }
        resetArmedAt = 0;
        for (Setting<?> setting : settings) {
            if (setting.isAction() || setting.isPermanentlyHidden()) continue;
            setting.reset();
        }
        com.relaxedqol.client.util.ChatUtil.send("§a" + name + "§7: settings reset to default");
        com.relaxedqol.client.ConfigManager.save(com.relaxedqol.client.ConfigManager.DEFAULT);
    }

    /** True when the module has settings worth resetting (any shown, non-button setting). */
    private boolean hasResettable() {
        for (Setting<?> setting : settings) {
            if (!setting.isAction() && !setting.isPermanentlyHidden()) return true;
        }
        return false;
    }

    /** True when there is no world to act on. */
    protected boolean notInGame() {
        return mc.player == null || mc.level == null || mc.gameMode == null;
    }

    public String getName() { return name; }
    /** The name shown in the menu / module list (a module can show another name than it is saved under). */
    public String displayName() { return name; }
    public String getDescription() { return description; }
    public ModuleCategory getCategory() { return category; }
    public boolean isEnabled() { return enabled; }
    public int getKey() { return key; }
    public void setKey(int key) { this.key = key; }
    public List<Setting<?>> getSettings() {
        // Added on first use (after the module's own settings exist), always last
        if (resetButton == null && hasResettable()) {
            resetButton = new com.relaxedqol.client.setting.ActionSetting("Reset to Default", this::resetSettings);
            resetButton.describe("Puts every setting of " + name + " back to how it was when you first got the client"
                + " (not its on/off state or keybind)");
            settings.add(resetButton);
        }
        return settings;
    }

    public Setting<?> getSetting(String settingName) {
        for (Setting<?> setting : settings) {
            if (setting.getName().replace(" ", "").equalsIgnoreCase(settingName.replace(" ", ""))) {
                return setting;
            }
        }
        return null;
    }
}
