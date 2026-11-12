package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.gui.MenuStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Settings > Custom Menu Buttons. The client's rounded buttons / text fields live in the client's own namespace
 * (assets/relaxed-qol/textures/gui/sprites/widget) instead of replacing Minecraft's textures. While the switch is on,
 * every "minecraft:widget/button..." / "minecraft:widget/text_field..." sprite is drawn with the client's copy;
 * switched off, Minecraft's normal buttons show again - no resource reload needed.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class ButtonSpritesMixin {
    @ModifyVariable(require = 0, at = @At("HEAD"), argsOnly = true, method = {
        "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
        "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
        "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIF)V"})
    private Identifier relaxed$clientButtons(Identifier sprite) {
        return MenuStyle.buttonSprite(sprite);
    }
}
