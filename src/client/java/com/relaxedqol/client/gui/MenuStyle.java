package com.relaxedqol.client.gui;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.modules.client.ClickGUI;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The buttons themselves are restyled by the client's own textures (assets/relaxed-qol/textures/gui/sprites/widget:
 * rounded dark buttons, accent on hover, rounded text fields) - Minecraft draws them, nothing is painted over. They
 * are used instead of Minecraft's while Settings > Custom Menu Buttons is on ({@link #buttonSprite}).
 * The client button there reads "Relaxed QOL" with a gear icon.
 */
public final class MenuStyle {
    private static Button menuButton;

    private MenuStyle() {}

    private static final java.util.Set<String> OWN_SPRITES = java.util.Set.of("widget/button", "widget/button_disabled",
        "widget/button_highlighted", "widget/text_field", "widget/text_field_highlighted");

    /**
     * Minecraft is about to draw a sprite: its buttons / text fields become the client's rounded ones while
     * Settings > Custom Menu Buttons is on; anything else (and everything when it is off) stays as it is.
     */
    /** True while Vanilla Style draws its own buttons: those always stay Minecraft's, whatever Menu Button Look says. */
    private static boolean keepMinecraft;

    /** Draws one of Minecraft's own button / text field sprites exactly as it is (Vanilla Style's windows). */
    public static void minecraftSprite(GuiGraphicsExtractor g, net.minecraft.resources.Identifier sprite, int x, int y, int w, int h) {
        keepMinecraft = true;
        try {
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h, 0xFFFFFFFF);
        } finally {
            keepMinecraft = false;
        }
    }

    public static net.minecraft.resources.Identifier buttonSprite(net.minecraft.resources.Identifier sprite) {
        if (keepMinecraft) return sprite;
        if (sprite == null || !"minecraft".equals(sprite.getNamespace()) || !OWN_SPRITES.contains(sprite.getPath())) return sprite;
        String look = ClickGUI.buttonLook();
        if ("Minecraft".equals(look)) return sprite;
        // Glass (Clean Style's, or picked in Menu Button Look): see-through buttons and text boxes with a light edge
        if ("Glass".equals(look)) {
            return net.minecraft.resources.Identifier.fromNamespaceAndPath("relaxed-qol",
                "widget/clean_" + sprite.getPath().substring("widget/".length()));
        }
        return net.minecraft.resources.Identifier.fromNamespaceAndPath("relaxed-qol", sprite.getPath());
    }

    /** The round gear button (top-left) that opens the client menu. */
    public static Button gearButton(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        menuButton = Button.builder(Component.literal("  Relaxed QOL"), b -> mc.gui.setScreen(ClickGuiScreen.create(screen)))
            .bounds(6, 6, 108, 20).build();
        return menuButton;
    }

    /** "Account: Name" next to the gear - opens the Accounts window (switch account, renew the session). */
    public static Button accountButton(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        String name = mc.getUser() == null ? "?" : mc.getUser().getName();
        // Always wide enough for the whole label (a too-narrow button makes Minecraft scroll the text, which looks
        // like it overlaps); only a really long name is shortened with "…"
        String label = Theme.ellipsize("Account: " + name, 200, mc.font::width);
        int w = Math.max(90, mc.font.width(label) + 16);
        return Button.builder(Component.literal(label), b -> AccountsScreen.open(screen)).bounds(118, 6, w, 20).build();
    }

    public static void afterExtract(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.nextStratum();
        // LoverFella Mode - sitters on the buttons, the heart badge, a red / cyan underline on hover
        if (LoverFx.on()) LoverFx.menuForeground(screen, g, mouseX, mouseY);
        // Gear icon on the "Relaxed QOL" button (the button itself is drawn by Minecraft with the client texture)
        if (menuButton != null && Screens.getWidgets(screen).contains(menuButton)) {
            int icon = 11;
            int x = menuButton.getX() + 7;
            int y = menuButton.getY() + (menuButton.getHeight() - icon) / 2;
            Render2D.icon(g, "gear", x, y, icon, menuButton.isHovered() ? 0xFFFFFFFF : Theme.TEXT_DIM);
        }
    }
}
