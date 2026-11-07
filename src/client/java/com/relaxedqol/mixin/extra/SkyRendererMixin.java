package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.modules.render.CustomSky;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * CustomSky: after vanilla works out the sky for this frame, swap in our colour and / or the End skybox.
 * The fields are found by name at runtime (the colour may be an int or a vector depending on the game version),
 * so a different layout just means no custom sky - never a crash.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
    @Unique
    private static Field relaxed$color;
    @Unique
    private static Field relaxed$skybox;
    @Unique
    private static Object relaxed$endSkybox;
    @Unique
    private static boolean relaxed$looked;

    @Inject(require = 0, method = "extractRenderState", at = @At("TAIL"))
    private void relaxed$customSky(CallbackInfo ci, @Local(argsOnly = true) SkyRenderState state) {
        CustomSky sky = RelaxedQolClient.enabled(CustomSky.class);
        if (sky == null || state == null) return;
        relaxed$find(state);
        try {
            if (sky.colour() && relaxed$color != null) {
                Object current = relaxed$color.get(state);
                Class<?> type = relaxed$color.getType();
                if (type == int.class) {
                    int v = (Integer) current;
                    float bright = Math.max((v >> 16) & 0xFF, Math.max((v >> 8) & 0xFF, v & 0xFF)) / 255f;
                    relaxed$color.setInt(state, (v & 0xFF000000) | sky.skyRgb(bright));
                } else if (current instanceof org.joml.Vector3f vec) {
                    float bright = Math.max(vec.x, Math.max(vec.y, vec.z));
                    int rgb = sky.skyRgb(bright);
                    vec.set(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f);
                } else if (current instanceof net.minecraft.world.phys.Vec3 vec) {
                    float bright = (float) Math.max(vec.x, Math.max(vec.y, vec.z));
                    int rgb = sky.skyRgb(bright);
                    relaxed$color.set(state, new net.minecraft.world.phys.Vec3(((rgb >> 16) & 0xFF) / 255.0, ((rgb >> 8) & 0xFF) / 255.0, (rgb & 0xFF) / 255.0));
                }
            }
            if (sky.endSky() && relaxed$skybox != null && relaxed$endSkybox != null) relaxed$skybox.set(state, relaxed$endSkybox);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            relaxed$color = null; // this layout does not work: stop trying
        }
    }

    @Unique
    private static void relaxed$find(SkyRenderState state) {
        if (relaxed$looked) return;
        relaxed$looked = true;
        for (Field f : state.getClass().getFields()) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.equals("skycolor")) relaxed$color = f;
            if (n.equals("skybox") && f.getType().isEnum()) {
                relaxed$skybox = f;
                for (Object c : f.getType().getEnumConstants()) {
                    if (c.toString().equalsIgnoreCase("end") || ((Enum<?>) c).name().equals("END")) relaxed$endSkybox = c;
                }
            }
        }
        if (relaxed$color != null) relaxed$color.setAccessible(true);
    }
}
