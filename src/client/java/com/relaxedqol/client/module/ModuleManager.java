package com.relaxedqol.client.module;

import com.relaxedqol.RelaxedQolMod;
import com.relaxedqol.client.module.modules.client.ClickGUI;
import com.relaxedqol.client.module.modules.misc.CoordsCopy;
import com.relaxedqol.client.module.modules.misc.PlayerAlert;
import com.relaxedqol.client.module.modules.misc.QuickPanel;
import com.relaxedqol.client.module.modules.misc.TradeShot;
import com.relaxedqol.client.module.modules.player.DurabilityAlert;
import com.relaxedqol.client.module.modules.player.InventoryTweaks;
import com.relaxedqol.client.module.modules.player.Sprint;
import com.relaxedqol.client.module.modules.render.Freelook;
import com.relaxedqol.client.module.modules.render.Fullbright;
import com.relaxedqol.client.module.modules.render.HUD;
import com.relaxedqol.client.module.modules.render.Radar;
import com.relaxedqol.client.module.modules.render.Waypoints;
import com.relaxedqol.client.module.modules.render.Zoom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModuleManager {
    /** Copy-on-write: safe to iterate while a keybind toggles something. */
    private final List<Module> modules = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<Class<? extends Module>, Module> byClass = new ConcurrentHashMap<>();

    public ModuleManager() {
        // Render
        add(new HUD());
        add(new Radar());
        add(new Waypoints());
        add(new Zoom());
        add(new Freelook());
        add(new Fullbright());
        // Lunar-style visuals
        add(new com.relaxedqol.client.module.modules.render.Chat());
        add(new com.relaxedqol.client.module.modules.render.ArmorStatus());
        add(new com.relaxedqol.client.module.modules.render.Keystrokes());
        add(new com.relaxedqol.client.module.modules.render.BossBar());
        add(new com.relaxedqol.client.module.modules.render.ActionBar());
        add(new com.relaxedqol.client.module.modules.render.Scoreboard());
        add(new com.relaxedqol.client.module.modules.render.BlockOutline());
        add(new com.relaxedqol.client.module.modules.render.CustomSky());
        add(new com.relaxedqol.client.module.modules.render.LocatorBar());
        add(new com.relaxedqol.client.module.modules.render.ScrollableTooltips());
        add(new com.relaxedqol.client.module.modules.render.ChunkBorders());

        // Player
        add(new InventoryTweaks());
        add(new Sprint());
        add(new DurabilityAlert());

        // Misc
        add(new TradeShot());
        add(new com.relaxedqol.client.module.modules.lovercraft.KeepServerPacks());
        add(new PlayerAlert());
        add(new QuickPanel());
        add(new CoordsCopy());

        add(new com.relaxedqol.client.module.modules.misc.Notes());
        add(new com.relaxedqol.client.module.modules.render.ClearView());
        add(new com.relaxedqol.client.module.modules.render.Crosshair());
        add(new com.relaxedqol.client.module.modules.lovercraft.LoverFellaMode());
        add(new com.relaxedqol.client.module.modules.lovercraft.LiveMap());


        // Client
        add(new ClickGUI());
    }

    private void add(Module module) {
        modules.add(module);
        byClass.put(module.getClass(), module);
    }

    public void onTick() {
        for (Module module : modules) {
            if (!module.isEnabled() || !module.getCategory().active()) continue;
            try {
                module.onTick();
            } catch (Exception e) {
                RelaxedQolMod.LOGGER.error("Error ticking module {}", module.getName(), e);
            }
        }
    }

    public void onRender3D(float partialTicks) {
        for (Module module : modules) {
            if (!module.isEnabled() || !module.getCategory().active() || com.relaxedqol.client.util.Privacy.hides(module)) continue;
            try {
                module.onRender3D(partialTicks);
            } catch (Exception e) {
                RelaxedQolMod.LOGGER.error("Error rendering module {}", module.getName(), e);
            }
        }
    }

    public Module getModule(String name) {
        for (Module module : modules) {
            if (module.getName().equalsIgnoreCase(name)) return module;
        }
        return null;
    }

    public <T extends Module> T getModule(Class<T> moduleClass) {
        return moduleClass.cast(byClass.get(moduleClass));
    }

    /** True when the module of this class exists and is enabled. Safe to call from mixins. */
    public boolean isEnabled(Class<? extends Module> moduleClass) {
        Module module = byClass.get(moduleClass);
        return module != null && module.isEnabled() && module.getCategory().active() && !com.relaxedqol.client.util.Privacy.hides(module);
    }

    public List<Module> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public List<Module> getModulesByCategory(ModuleCategory category) {
        List<Module> result = new ArrayList<>();
        for (Module module : modules) {
            if (module.getCategory() == category) result.add(module);
        }
        return result;
    }

    public List<Module> getEnabledModules() {
        List<Module> result = new ArrayList<>();
        for (Module module : modules) {
            if (module.isEnabled() && module.getCategory().active()) result.add(module);
        }
        return result;
    }

    /** Every module's {@link Module#alwaysTick}, on or off. */
    public void keyTicks() {
        for (Module module : modules) {
            if (!module.getCategory().active()) continue;
            try {
                module.alwaysTick();
            } catch (Exception e) {
                RelaxedQolMod.LOGGER.error("Error in always-tick of {}", module.getName(), e);
            }
        }
    }
}
