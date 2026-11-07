package com.relaxedqol.client;

import com.relaxedqol.RelaxedQolMod;
import com.relaxedqol.client.module.modules.client.ClickGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;

/**
 * "Relaxed" title above other players who use this client.
 *
 * How clients find each other without any server of our own: every client tells the server which skin layers it
 * shows (hat, jacket, sleeves...) as one byte, and the server copies that byte to every player who can see you.
 * Only 7 bits are skin layers; the 8th (0x80) is unused by the game and ignored by every renderer. With
 * "Show Client Tag" on, this client sets that bit; other Relaxed users see it and draw the title above your head.
 * Switch it off (Settings > Show Client Tag) and the bit is cleared at once - nobody can tell you use the client.
 *
 * Works on vanilla, Paper and Spigot servers (they pass the byte along unchanged). A server or proxy that rewrites
 * skin settings would hide the title, but nothing else breaks.
 */
public final class ClientTag {
    /** The spare bit in the skin-layer byte (bits 0-6 are cape, jacket, sleeves, pants legs, hat). */
    public static final int MARK = 0x80;
    public static final String TITLE = "Relaxed";

    private static EntityDataAccessor<Byte> skinAccessor;
    private static boolean searched;

    private ClientTag() {}

    private static ClickGUI settings() {
        if (RelaxedQolClient.moduleManager == null) return null;
        return RelaxedQolClient.moduleManager.getModule(ClickGUI.class);
    }

    /** Whether we mark ourselves (others with the client see our title). Defaults to on. */
    public static boolean sharing() {
        ClickGUI gui = settings();
        return gui == null || gui.showClientTag();
    }

    private static boolean seeingTags() {
        ClickGUI gui = settings();
        return gui == null || gui.seeClientTags();
    }

    // ------------------------------------------------------------------ sending our mark (OptionsMixin)

    /**
     * Called with the client information the game is about to send (a record). Returns a copy with the mark bit
     * set or cleared, or the same object if anything about it is unexpected. Reflection keeps this independent of
     * the record's exact field list (it changes between versions).
     */
    public static Object withMark(Object info) {
        if (info == null || !info.getClass().isRecord()) return info;
        try {
            RecordComponent[] parts = info.getClass().getRecordComponents();
            Object[] values = new Object[parts.length];
            Class<?>[] types = new Class<?>[parts.length];
            boolean found = false;
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType();
                Method accessor = parts[i].getAccessor();
                accessor.setAccessible(true);
                values[i] = accessor.invoke(info);
                if (types[i] == int.class && parts[i].getName().toLowerCase(java.util.Locale.ROOT).contains("modelcustomi")) {
                    int mask = (Integer) values[i];
                    values[i] = sharing() ? (mask | MARK) : (mask & ~MARK);
                    found = true;
                }
            }
            if (!found) return info;
            var constructor = info.getClass().getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        } catch (ReflectiveOperationException | RuntimeException e) {
            RelaxedQolMod.LOGGER.warn("Client tag: could not mark the client information", e);
            return info;
        }
    }

    /** Sends the client information again right away (after "Show Client Tag" is switched), so it applies at once. */
    public static void rebroadcast() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null || mc.getConnection() == null) return;
        try {
            Method send = mc.options.getClass().getMethod("broadcastOptions");
            send.invoke(mc.options);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Older/newer name: it is applied the next time you join or change a skin layer instead
            RelaxedQolMod.LOGGER.debug("Client tag: could not resend options", e);
        }
    }

    // ------------------------------------------------------------------ reading other players' mark

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Byte> accessor() {
        if (searched) return skinAccessor;
        searched = true;
        for (Class<?> c = Player.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || !EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                if (!f.getName().contains("CUSTOMISATION") && !f.getName().contains("CUSTOMIZATION")) continue;
                try {
                    f.setAccessible(true);
                    skinAccessor = (EntityDataAccessor<Byte>) f.get(null);
                    return skinAccessor;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    RelaxedQolMod.LOGGER.warn("Client tag: skin data field not readable", e);
                }
            }
        }
        RelaxedQolMod.LOGGER.warn("Client tag: skin data field not found - titles of other users can't be shown");
        return null;
    }

    /** Whether this (other) player runs this client and shares it. */
    public static boolean usesClient(Player player) {
        EntityDataAccessor<Byte> acc = accessor();
        if (acc == null) return false;
        try {
            Byte value = player.getEntityData().get(acc);
            return value != null && (value & MARK) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ drawing the title

    /** Draws the title above every other client user in sight (world render pass). */
    public static void render(float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !seeingTags()) return;
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.isInvisibleTo(mc.player) || !usesClient(player)) continue;
            Vec3 feet = player.getPosition(partialTicks);
            if (feet.distanceToSqr(cam) > 64 * 64) continue;
            // Above the name tag (which sits about half a block over the head)
            Vec3 at = feet.add(0, player.getBbHeight() + 0.98, 0);
            int alpha = player.isDiscrete() ? 0x70000000 : 0xFF000000;
            int color = alpha | (com.relaxedqol.client.gui.Theme.accent() & 0xFFFFFF);
            Gizmos.billboardText("✦ " + TITLE + " ✦", at, TextGizmo.Style.forColorAndCentered(color).withScale(0.42f));
        }
    }
}
