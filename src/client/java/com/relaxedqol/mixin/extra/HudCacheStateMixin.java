package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.util.HudCache;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Smart HUD: records what the client's HUD hands to the game, so it can be repeated. Optional (require = 0). */
@Mixin(GuiRenderState.class)
public abstract class HudCacheStateMixin {
    @Inject(require = 0, method = "addGuiElement", at = @At("HEAD"))
    private void relaxed$element(GuiElementRenderState element, CallbackInfo ci) {
        HudCache.onElement(element);
    }

    @Inject(require = 0, method = "up", at = @At("HEAD"))
    private void relaxed$up(CallbackInfo ci) {
        HudCache.onMarker("up");
    }

    @Inject(require = 0, method = "nextStratum", at = @At("HEAD"))
    private void relaxed$stratum(CallbackInfo ci) {
        HudCache.onMarker("stratum");
    }

    @Inject(require = 0, method = {"addText", "submitText"}, at = @At("HEAD"))
    private void relaxed$text(CallbackInfo ci) {
        HudCache.onAddText();
    }

    /** Things that cannot simply be repeated: the layer draws normally for a while. */
    @Inject(require = 0, method = {"addItem", "submitItem", "addPicturesInPictureState", "addGlyphToCurrentLayer",
        "addBlitToCurrentLayer", "blurBeforeThisStratum"}, at = @At("HEAD"))
    private void relaxed$unsupported(CallbackInfo ci) {
        HudCache.onUnsupported();
    }
}
