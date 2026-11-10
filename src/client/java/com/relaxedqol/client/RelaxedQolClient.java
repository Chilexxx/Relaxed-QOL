package com.relaxedqol.client;

import com.relaxedqol.RelaxedQolMod;
import com.relaxedqol.client.command.CommandManager;
import com.relaxedqol.client.gui.ClickGuiScreen;
import com.relaxedqol.client.gui.OverlayRenderer;
import com.relaxedqol.client.gui.ScreenPanelOverlay;
import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleManager;
import com.relaxedqol.client.module.modules.misc.QuickPanel;
import com.relaxedqol.client.module.modules.render.HUD;
import com.relaxedqol.mixin.AbstractContainerScreenAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;

import java.util.HashSet;
import java.util.Set;

public class RelaxedQolClient implements ClientModInitializer {
    public static ModuleManager moduleManager;
    public static CommandManager commandManager;

    /** Edge tracking for the Menu Key (read from the Settings panel, not from Controls). */
    private static boolean menuKeyWasDown;
    private static final Set<Integer> heldModuleKeys = new HashSet<>();

    @Override
    public void onInitializeClient() {
        RelaxedQolMod.LOGGER.info("Initializing Relaxed QOL (client side)");

        moduleManager = new ModuleManager();
        commandManager = new CommandManager();
        // LoverFella Mode's figures - their skins load while the game is still starting (from disk if saved)
        com.relaxedqol.client.util.SkinFaces.prefetch(java.util.List.of("LoverFella", "Isaacbc76", "RainingSoap"));

        // Load saved modules/settings once the game is fully started, save on exit
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            com.relaxedqol.client.waypoint.WaypointManager.load();
            ConfigManager.loadFriends();
            ConfigManager.load(ConfigManager.DEFAULT);
            // And the names you picked yourself
            com.relaxedqol.client.module.modules.lovercraft.LoverFellaMode lover =
                moduleManager.getModule(com.relaxedqol.client.module.modules.lovercraft.LoverFellaMode.class);
            if (lover != null) {
                java.util.List<String> names = new java.util.ArrayList<>(lover.sitterNames());
                names.addAll(lover.headNames());
                com.relaxedqol.client.util.SkinFaces.prefetch(names);
            }
            // Zoom: mouse wheel zooms in / out while zoomed
            com.relaxedqol.client.util.ScrollHook.install();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            ConfigManager.save(ConfigManager.DEFAULT);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            com.relaxedqol.client.module.modules.client.ClickGUI look =
                moduleManager.getModule(com.relaxedqol.client.module.modules.client.ClickGUI.class);
            if (look != null) {
                boolean menuDown = InputConstants.isKeyDown(client.getWindow(), look.menuKey());
                if (menuDown && !menuKeyWasDown && client.gui.screen() == null && client.player != null) {
                    client.gui.setScreen(ClickGuiScreen.create(null));
                }
                menuKeyWasDown = menuDown;
            }
            handleModuleKeybinds(client);
            long tt = com.relaxedqol.client.util.Perf.start();
            moduleManager.onTick();
            com.relaxedqol.client.util.Perf.end(com.relaxedqol.client.util.Perf.TICKS, tt);
            com.relaxedqol.client.module.modules.render.Zoom zoom =
                moduleManager.getModule(com.relaxedqol.client.module.modules.render.Zoom.class);
            if (zoom != null) zoom.frame();
            // Keys that work while their module is off too (Radar window, Quick Panel buttons)
            com.relaxedqol.client.module.modules.render.Radar radar =
                moduleManager.getModule(com.relaxedqol.client.module.modules.render.Radar.class);
            if (radar != null) radar.keyTick();
            QuickPanel quick = moduleManager.getModule(QuickPanel.class);
            if (quick != null) quick.keyTick();
            // Keys that work while their module is off (waypoint menu...), queued windows
            com.relaxedqol.client.module.modules.render.Waypoints waypoints =
                moduleManager.getModule(com.relaxedqol.client.module.modules.render.Waypoints.class);
            if (waypoints != null) waypoints.keyTick();
            moduleManager.keyTicks();
            dotOpensChatTick(client);
            com.relaxedqol.client.util.Disclaimer.tick(client);
            com.relaxedqol.client.util.Later.tick();
            // The Minecraft session is renewed before it runs out (accounts added in the Accounts window)
            com.relaxedqol.client.util.Accounts.tick();
        });

        // HUD overlay (module list, coordinates, FPS, ping)
        // Boss bar / action bar movers, block outline hook, locator heads
        HudMovers.install();
        // Crosshair: our own crosshair in place of the game's while the module is on
        HudElementRegistry.replaceElement(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CROSSHAIR,
            vanilla -> (graphics, deltaTracker) -> {
                if (com.relaxedqol.client.util.DebugHud.open() || com.relaxedqol.client.util.Privacy.hidden()
                    || !com.relaxedqol.client.module.modules.render.Crosshair.draw(graphics)) vanilla.extractRenderState(graphics, deltaTracker);
            });
        // Screenshots: the /qolshot command behind the Copy / Delete buttons in chat
        com.relaxedqol.client.module.modules.misc.Screenshots.registerCommand();

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(RelaxedQolMod.MOD_ID, "hud"), (graphics, deltaTracker) -> {
            // Zoom animates every frame (smooth in/out)
            com.relaxedqol.client.module.modules.render.Zoom zoom =
                moduleManager.getModule(com.relaxedqol.client.module.modules.render.Zoom.class);
            if (zoom != null) zoom.frame();
            if (com.relaxedqol.client.util.DebugHud.open() || com.relaxedqol.client.util.Privacy.hidden()) return;
            HUD hud = moduleManager.getModule(HUD.class);
            // Out of the way of the tab list while Tab is held
            if (hud != null && hud.isEnabled() && !com.relaxedqol.client.util.TabHud.open()) {
                // Smart HUD: worked out at most 60 times a second, repeated in between
                long t0 = com.relaxedqol.client.util.Perf.start();
                com.relaxedqol.client.util.HudCache.layer("hud", graphics, false, () -> hud.render(graphics));
                com.relaxedqol.client.util.Perf.end(com.relaxedqol.client.util.Perf.HUD, t0);
            }
            // .perf
            com.relaxedqol.client.util.Perf.frame();
            com.relaxedqol.client.util.Perf.draw(graphics);
        });

        // Radar card, PlayerAlert card and pop-ups (independent of the HUD module).
        // While the menu is open it draws the cards itself, on top, so they are not blurred.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(RelaxedQolMod.MOD_ID, "overlays"), (graphics, deltaTracker) -> {
            var open = Minecraft.getInstance().gui.screen();
            if (com.relaxedqol.client.util.Privacy.hidden()) return;
            if (com.relaxedqol.client.util.DebugHud.open() && open == null) {
                com.relaxedqol.client.gui.Toasts.render(graphics);
                return;
            }
            if (open == null) {
                // Smart HUD: the in-game cards / alerts through the cache (worked out at most 60 times a second)
                long t0 = com.relaxedqol.client.util.Perf.start();
                com.relaxedqol.client.util.HudCache.layer("overlays", graphics, true,
                    () -> drawOverlays(graphics, null, deltaTracker.getGameTimeDeltaPartialTick(true)));
                com.relaxedqol.client.util.Perf.end(com.relaxedqol.client.util.Perf.CARDS, t0);
                return;
            }
            drawOverlays(graphics, open, deltaTracker.getGameTimeDeltaPartialTick(true));
        });
        installRest();
    }

    /** The cards, alerts and pop-ups layer (radar, PlayerAlert, durability, toasts, REC dot, HUD cards). */
    private static void drawOverlays(net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.client.gui.screens.Screen open, float partialTicks) {
        {
            com.relaxedqol.client.module.modules.misc.PlayerAlert playerAlert =
                enabled(com.relaxedqol.client.module.modules.misc.PlayerAlert.class);
            boolean chatOpen = open instanceof net.minecraft.client.gui.screens.ChatScreen;
            // Each part has its own LoverFella Mode switch (whether it lights up red / cyan)
            String prevScope = com.relaxedqol.client.gui.LoverFx.beginScope("PlayerAlert");
            try {
                if (playerAlert != null && !com.relaxedqol.client.util.TabHud.open()
                    && (open == null || open instanceof com.relaxedqol.client.gui.HudEditorScreen
                    || (chatOpen && playerAlert.showWhileChatting()))) playerAlert.renderHud(graphics, -1);
                com.relaxedqol.client.gui.LoverFx.beginScope("Pop-ups");
                com.relaxedqol.client.module.modules.player.DurabilityAlert durability =
                    enabled(com.relaxedqol.client.module.modules.player.DurabilityAlert.class);
                if (durability != null && open == null) durability.renderHud(graphics);
                com.relaxedqol.client.gui.Toasts.render(graphics);
            } finally {
                com.relaxedqol.client.gui.LoverFx.endScope(prevScope);
            }
            if (open instanceof ClickGuiScreen || (open != null && ScreenPanelOverlay.isAttachedTo(open))) return;
            OverlayRenderer.renderAll(graphics, partialTicks);
        }
    }

    private void installRest() {

        // 3D rendering (waypoints) through vanilla's gizmo system
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            if (com.relaxedqol.client.util.Privacy.hidden()) return;
            float partialTicks = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            long t3 = com.relaxedqol.client.util.Perf.start();
            moduleManager.onRender3D(partialTicks);
            com.relaxedqol.client.util.Perf.end(com.relaxedqol.client.util.Perf.WORLD, t3);
            // "Relaxed" titles above other client users (not a module: always on unless switched off in Settings)
            try {
                ClientTag.render(partialTicks);
            } catch (RuntimeException e) {
                RelaxedQolMod.LOGGER.error("Client tag render failed", e);
            }
        });

        // Chat commands with the "." prefix never reach the server
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            return !commandManager.handle(message);
        });
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            // TradeShot: "trade completed" / "trade cancelled" lines decide whether the screenshot is kept
            com.relaxedqol.client.module.modules.misc.TradeShot tradeShot =
                enabled(com.relaxedqol.client.module.modules.misc.TradeShot.class);
            if (tradeShot != null) tradeShot.onChat(message.getString());
        });
        // Setting profiles: a server linked to a profile switches to it when you join
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> client.execute(com.relaxedqol.client.util.Profiles::onJoin));
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            // Nothing from this server may show on the next one
            com.relaxedqol.client.module.modules.misc.PlayerAlert alert =
                moduleManager.getModule(com.relaxedqol.client.module.modules.misc.PlayerAlert.class);
            if (alert != null) alert.clearSession();
        });

        // Any open chest/inventory (or any screen, if chosen): Quick Panel overlay
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            // Not in a world yet: offer the menu on the title screen and server list (button + Right Shift)
            if (client.level == null && (screen instanceof TitleScreen || screen instanceof JoinMultiplayerScreen)) {
                // A plain round gear button (no client name anywhere), and the client look for the menu buttons
                Screens.getWidgets(screen).add(com.relaxedqol.client.gui.MenuStyle.gearButton(screen));
                // Switch Minecraft accounts right here (no restart, no second launcher instance)
                Screens.getWidgets(screen).add(com.relaxedqol.client.gui.MenuStyle.accountButton(screen));
                // After an update: "What's new" once
                if (screen instanceof TitleScreen) com.relaxedqol.client.gui.ChangelogScreen.maybeShow(screen);
                ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickProgress) ->
                    com.relaxedqol.client.gui.MenuStyle.afterExtract(s, graphics, mouseX, mouseY));
                ScreenEvents.afterBackground(screen).register((s, graphics, mouseX, mouseY, tickProgress) ->
                    com.relaxedqol.client.gui.LoverFx.menuBackground(s, graphics));
                ScreenKeyboardEvents.afterKeyPress(screen).register((s, event) -> {
                    com.relaxedqol.client.module.modules.client.ClickGUI look =
                        moduleManager.getModule(com.relaxedqol.client.module.modules.client.ClickGUI.class);
                    if (look != null && event.key() == look.menuKey()) client.gui.setScreen(ClickGuiScreen.create(s));
                });
                return;
            }
            // Pause menu: same client look as the title screen
            if (screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
                ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickProgress) ->
                    com.relaxedqol.client.gui.MenuStyle.afterExtract(s, graphics, mouseX, mouseY));
                ScreenEvents.afterBackground(screen).register((s, graphics, mouseX, mouseY, tickProgress) ->
                    com.relaxedqol.client.gui.LoverFx.menuBackground(s, graphics));
            }
            if (screen instanceof DisconnectedScreen) {
                // Kicked with "Invalid session"? The session is checked and renewed, so you can join again
                com.relaxedqol.client.util.Accounts.onDisconnected();
                return;
            }
            // No Quick Panel on loading screens (switching zones / servers, joining, terrain loading)
            if (isLoadingScreen(screen) || client.level == null) return;
            // Esc: the pause menu (and the Options screens you reach from it) never get the Quick Panel
            if (isPauseOrOptions(screen)) return;
            if (client.player == null || screen instanceof ClickGuiScreen || screen instanceof com.relaxedqol.client.gui.HudEditorScreen
                || screen instanceof com.relaxedqol.client.gui.WindowScreen) return;
            // Never put the Quick Panel over the chat box - it is in the way while typing
            if (screen instanceof net.minecraft.client.gui.screens.ChatScreen) return;
            boolean container = screen instanceof AbstractContainerScreen<?>;
            if (container) {
                ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickProgress) -> {
                    if (!(s instanceof AbstractContainerScreen<?> containerScreen) || com.relaxedqol.client.util.Privacy.hidden()) return;
                    // TradeShot: the money both sides offer + watermark, drawn on the trade window (so they are in the shot)
                    com.relaxedqol.client.module.modules.misc.TradeShot tradeShot =
                        enabled(com.relaxedqol.client.module.modules.misc.TradeShot.class);
                    if (tradeShot != null) tradeShot.renderTradeOverlay(containerScreen, graphics);
                    // InvTweaks: shulker fill bars on the slots and the contents preview when hovering a shulker
                    com.relaxedqol.client.module.modules.player.InventoryTweaks tweaks =
                        enabled(com.relaxedqol.client.module.modules.player.InventoryTweaks.class);
                    if (tweaks != null) tweaks.renderScreen(containerScreen, graphics, mouseX, mouseY);
                });
            }
            QuickPanel panel = enabled(QuickPanel.class);
            if (panel == null || (!container && !panel.onAllScreens())) return;

            // The Quick Panel only shows on your own inventory (survival and creative)
            boolean showPanel = isPlayerInventory(screen);
            if (showPanel) ScreenPanelOverlay.attach(screen);
            // Cards (radar) are drawn on top of the GUI and can be moved here too
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tickProgress) -> {
                if (com.relaxedqol.client.util.Privacy.hidden()) return;
                OverlayRenderer.renderAll(graphics, tickProgress);
                OverlayRenderer.drawEditFrames(graphics, mouseX, mouseY);
                if (showPanel) ScreenPanelOverlay.render(graphics, mouseX, mouseY);
            });
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                !((showPanel && ScreenPanelOverlay.mouseClicked(event)) || OverlayRenderer.mouseClicked(event.x(), event.y(), event.button())));
            ScreenMouseEvents.allowMouseDrag(screen).register((s, event, dx, dy) ->
                !((showPanel && ScreenPanelOverlay.mouseDragged(event)) || OverlayRenderer.mouseDragged(event.x(), event.y())));
            ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> {
                boolean card = OverlayRenderer.mouseReleased();
                return !((showPanel && ScreenPanelOverlay.mouseReleased()) || card);
            });
            if (showPanel) ScreenEvents.remove(screen).register(s -> ScreenPanelOverlay.onScreenClosed());
        });

        RelaxedQolMod.LOGGER.info("Relaxed QOL initialization complete");
    }

    /** The screens shown while you change world / server (they get no panel and no cards). */
    private static boolean isLoadingScreen(net.minecraft.client.gui.screens.Screen screen) {
        String name = screen.getClass().getSimpleName();
        return name.contains("Loading") || name.contains("ReceivingLevel") || name.contains("Progress")
            || name.contains("Connect") || name.contains("DownloadingTerrain") || name.contains("Generating")
            || name.contains("Message");
    }

    /** Your own inventory: survival / adventure, or the creative inventory. */
    private static boolean isPlayerInventory(net.minecraft.client.gui.screens.Screen screen) {
        return screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
            || screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
    }

    /** The Esc menu, or one of the Options screens (video, controls, key binds, sounds...) opened from it. */
    private static boolean isPauseOrOptions(net.minecraft.client.gui.screens.Screen screen) {
        if (screen instanceof net.minecraft.client.gui.screens.PauseScreen) return true;
        String name = screen.getClass().getName();
        return name.startsWith("net.minecraft.client.gui.screens.options.")
            || name.equals("net.minecraft.client.gui.screens.ShareToLanScreen")
            || name.startsWith("net.minecraft.client.gui.screens.achievement.")
            || name.startsWith("net.minecraft.client.gui.screens.advancements.")
            || name.startsWith("net.minecraft.client.gui.screens.social.")
            || name.startsWith("net.minecraft.client.gui.screens.reporting.");
    }

    /** Returns the module if it exists and is enabled, otherwise null. Safe to call from mixins at any time. */
    public static <T extends Module> T enabled(Class<T> moduleClass) {
        if (moduleManager == null) return null;
        T module = moduleManager.getModule(moduleClass);
        return module != null && module.isEnabled() && module.getCategory().active()
            && !com.relaxedqol.client.util.Privacy.hides(module) ? module : null;
    }

    private static void handleModuleKeybinds(Minecraft client) {
        if (client.player == null) {
            heldModuleKeys.clear();
            return;
        }
        // Only toggle while actually in the world (no screen). We still track which bound keys are held
        // while a screen is open, so a key you are holding does not fire when the screen closes - it only fires
        // on a fresh press afterwards.
        boolean inGame = client.gui.screen() == null;
        Set<Integer> checkedKeys = new HashSet<>();
        for (Module module : moduleManager.getModules()) {
            int key = module.getKey();
            if (key < 0 || !module.getCategory().active()) continue;
            checkedKeys.add(key);
            boolean down = InputConstants.isKeyDown(client.getWindow(), key);
            if (inGame && down && !heldModuleKeys.contains(key)) {
                module.toggle();
            }
        }
        for (int key : checkedKeys) {
            if (InputConstants.isKeyDown(client.getWindow(), key)) heldModuleKeys.add(key);
            else heldModuleKeys.remove(key);
        }
        // Keys no longer bound to anything should not linger in the held set
        heldModuleKeys.retainAll(checkedKeys);
    }

    private static boolean dotWasDown;

    /** "." (no screen open) opens the chat with "." typed - like "/" for server commands. */
    private static void dotOpensChatTick(Minecraft client) {
        if (client.getWindow() == null) return;
        boolean down = InputConstants.isKeyDown(client.getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_PERIOD);
        if (down && !dotWasDown && client.gui.screen() == null && client.player != null && client.level != null) {
            com.relaxedqol.client.module.modules.client.ClickGUI gui = moduleManager.getModule(com.relaxedqol.client.module.modules.client.ClickGUI.class);
            if (gui == null || gui.dotOpensChat()) {
                client.gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen(com.relaxedqol.client.command.CommandManager.PREFIX, false));
            }
        }
        dotWasDown = down;
    }
}
