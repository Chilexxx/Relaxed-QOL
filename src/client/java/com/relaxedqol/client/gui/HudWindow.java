package com.relaxedqol.client.gui;

import com.relaxedqol.client.setting.Setting;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A movable, resizable on-screen card (packet logger, held packets, macro card, radar).
 * Position and size are stored in (hidden) module settings, so they are saved with the config.
 * While the menu is open the card can be dragged anywhere and resized from its bottom-right corner.
 */
public final class HudWindow {
    private static final int GRIP = 9;

    private final Setting<Integer> x;
    private final Setting<Integer> y;
    private final Setting<Integer> scale;

    private int drawX;
    private int drawY;
    private int drawW;
    private int drawH;
    private float drawScale = 1;
    private long lastDrawn;
    /** Optional - the card stays against the right edge of the screen while this is on (scoreboard). */
    private Setting<Boolean> pinRight;
    private int lastVw = -1;

    /** Keeps the card on the right edge (like the vanilla scoreboard) until it is dragged away from it. */
    public HudWindow pinRight(Setting<Boolean> pin) {
        this.pinRight = pin;
        return this;
    }

    public boolean pinnedRight() {
        return pinRight != null && pinRight.getValue();
    }

    public HudWindow(Setting<Integer> x, Setting<Integer> y, Setting<Integer> scale) {
        this.x = x;
        this.y = y;
        this.scale = scale;
    }

    /** Settings to add to the owning module: X and Y (-1 = automatic position) and Scale %. */
    public static Setting<Integer> xSetting() {
        return new Setting<>("Window X", -1, -1, 8000, 1).hide();
    }

    public static Setting<Integer> ySetting() {
        return new Setting<>("Window Y", -1, -1, 8000, 1).hide();
    }

    public static Setting<Integer> scaleSetting() {
        return new Setting<>("Window Scale", 100, 50, 300, 1).hide();
    }

    /** The same for a module with several elements - the names get a prefix ("Watermark Window X"...). */
    public static Setting<Integer> xSetting(String prefix) {
        return new Setting<>(prefix + "Window X", -1, -1, 8000, 1).hide();
    }

    public static Setting<Integer> ySetting(String prefix) {
        return new Setting<>(prefix + "Window Y", -1, -1, 8000, 1).hide();
    }

    public static Setting<Integer> scaleSetting(String prefix) {
        return new Setting<>(prefix + "Window Scale", 100, 50, 300, 1).hide();
    }

    /** Puts the element back at its automatic spot and normal size. */
    public void resetPlacement() {
        x.setValue(-1);
        y.setValue(-1);
        scale.setValue(100);
        if (pinRight != null) pinRight.reset();
    }

    /** The size factor in percent (HUD editor). */
    public Setting<Integer> scaleSettingRef() {
        return scale;
    }

    /** Where the element was last drawn in the screen's HUD size (so the editor can keep it on screen). */
    public int lastScreenWidth() {
        return lastVw;
    }

    /**
     * Starts drawing a w x h card. After this, draw the content at local coordinates (0,0)-(w,h).
     * autoX/autoY are used until the player moves the card.
     */
    public void begin(GuiGraphicsExtractor g, int vw, int vh, int w, int h, int autoX, int autoY) {
        float s = scale.getValue() / 100f;
        int sw = Math.round(w * s);
        int sh = Math.round(h * s);
        int px = x.getValue() < 0 ? autoX : x.getValue();
        int py = y.getValue() < 0 ? autoY : y.getValue();
        if (pinnedRight()) px = vw - sw;
        lastVw = vw;
        px = Math.max(0, Math.min(px, vw - sw));
        py = Math.max(0, Math.min(py, vh - sh));
        drawX = px;
        drawY = py;
        drawW = sw;
        drawH = sh;
        drawScale = s;
        lastDrawn = System.currentTimeMillis();
        g.pose().pushMatrix();
        g.pose().translate(px, py);
        g.pose().scale(s, s);
    }

    public void end(GuiGraphicsExtractor g) {
        g.pose().popMatrix();
    }

    /**
     * Text inside the card that stays sharp at any card size: drawn at a whole number of screen pixels per
     * font pixel and on the screen's pixel grid (a freely scaled card used to make letters uneven and blurry).
     * (lx, ly) = local card position (between begin / end), size = wanted text size (1 = normal), centred = lx is
     * the middle of the text.
     */
    public void crispText(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String text, float lx, float ly,
                          int color, float size, boolean centred, boolean shadow) {
        crispText(g, font, net.minecraft.network.chat.Component.literal(text), lx, ly, color, size, centred, shadow);
    }

    /** The same for styled text (bold titles...). */
    public void crispText(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, net.minecraft.network.chat.Component text,
                          float lx, float ly, int color, float size, boolean centred, boolean shadow) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        int gs = Math.max(1, mc.getWindow().getGuiScale());
        float hud = OverlayRenderer.hudScale();
        float perLocal = gs * hud * drawScale;              // screen pixels per local unit
        int perFont = Math.max(1, Math.round(perLocal * size)); // screen pixels per font pixel (whole)
        float k = perFont / perLocal;                       // local units per font pixel
        float w = font.width(text) * k;
        // Centred on the letters themselves - a font's width includes the 1 px gap after the last letter, which
        // put every centred label (the Keystrokes W A S D...) half a pixel too far left
        float x = centred ? lx - Math.max(0f, w - k) / 2f : lx;
        // keep the line's vertical middle where it was asked for
        float y = ly + 4f * size - 4f * k;
        float sx = Math.round(gs * hud * (drawX + x * drawScale));
        float sy = Math.round(gs * hud * (drawY + y * drawScale));
        float nx = (sx / (gs * hud) - drawX) / drawScale;
        float ny = (sy / (gs * hud) - drawY) / drawScale;
        g.pose().pushMatrix();
        g.pose().translate(nx, ny);
        g.pose().scale(k, k);
        g.text(font, text, 0, 0, color, shadow);
        g.pose().popMatrix();
    }

    /** How wide {@link #crispText} really draws a text (local units), so labels can be sized to fit it. */
    public float crispWidth(net.minecraft.client.gui.Font font, String text, float size) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        int gs = Math.max(1, mc.getWindow().getGuiScale());
        float perLocal = gs * OverlayRenderer.hudScale() * drawScale;
        int perFont = Math.max(1, Math.round(perLocal * size));
        return font.width(text) * (perFont / perLocal);
    }

    /** The card's own size factor (Window Scale %). */
    public float scale() {
        return drawScale;
    }

    /** Whether the card was drawn in the last moment (only visible cards can be edited). */
    public boolean isVisible() {
        return System.currentTimeMillis() - lastDrawn < 250;
    }

    public boolean contains(double mx, double my) {
        return isVisible() && mx >= drawX && mx < drawX + drawW && my >= drawY && my < drawY + drawH;
    }

    public boolean onGrip(double mx, double my) {
        return isVisible() && mx >= drawX + drawW - GRIP && mx < drawX + drawW + 2 && my >= drawY + drawH - GRIP && my < drawY + drawH + 2;
    }

    public int getX() {
        return drawX;
    }

    public int getY() {
        return drawY;
    }

    /** Drawn size on screen (HUD pixels, scale included). */
    public int getW() {
        return drawW;
    }

    public int getH() {
        return drawH;
    }

    /** True while the card sits at its automatic spot (the player has not moved it). */
    public boolean isAuto() {
        return x.getValue() < 0;
    }

    public void moveTo(int newX, int newY) {
        x.setValue(Math.max(0, newX));
        y.setValue(Math.max(0, newY));
        // Dropped against the right edge = stays there (also when it is resized later); dragged away = free
        if (pinRight != null && lastVw > 0) pinRight.setValue(newX + drawW >= lastVw - 4);
    }

    /** Resizes so the bottom-right corner follows the mouse (keeps the shape). */
    public void resizeTo(double mx, double my) {
        float baseW = drawW / drawScale;
        float baseH = drawH / drawScale;
        float sx = (float) (mx - drawX) / baseW;
        float sy = (float) (my - drawY) / baseH;
        // Pinned to the right edge: the size follows how far down you pull (it grows to the left, off the edge)
        int percent = Math.round((pinnedRight() ? sy : Math.max(sx, sy)) * 100);
        if (isAuto()) {
            boolean pinned = pinnedRight();
            moveTo(drawX, drawY);
            if (pinRight != null) pinRight.setValue(pinned);
        }
        scale.setValue(Math.max(50, Math.min(300, percent)));
    }

    /** Edit-mode frame: outline and a corner grip. Drawn in the same coordinates as the card position. */
    public void drawEditFrame(GuiGraphicsExtractor g, boolean hover, int accent) {
        if (!isVisible()) return;
        int color = hover ? accent : 0x60FFFFFF;
        Render2D.roundOutline(g, drawX - 2, drawY - 2, drawW + 4, drawH + 4, color);
        Render2D.resizeGrip(g, drawX + drawW - 4, drawY + drawH - 4, color);
    }
}
