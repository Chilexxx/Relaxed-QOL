package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.module.modules.render.ScrollableTooltips;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ScrollableTooltips: tooltips are drawn shifted by the scroll amount (mouse wheel while one is shown), so a
 * tooltip taller or wider than the screen (big shulkers, books, long lore) can be read to the end.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {
    @Unique
    private int relaxed$depth;
    @Unique
    private boolean relaxed$pushed;

    @Inject(require = 0, method = "tooltip", at = @At("HEAD"))
    private void relaxed$tooltipStart(CallbackInfo ci) {
        if (relaxed$depth++ > 0) return;
        relaxed$pushed = false;
        com.relaxedqol.client.gui.ScreenPanelOverlay.tooltipAtNs = System.nanoTime();
        int[] off = ScrollableTooltips.offsetForFrame();
        if (off == null) return;
        GuiGraphicsExtractor self = (GuiGraphicsExtractor) (Object) this;
        self.pose().pushMatrix();
        self.pose().translate(off[0], off[1]);
        relaxed$pushed = true;
    }

    @Inject(require = 0, method = "tooltip", at = @At("RETURN"))
    private void relaxed$tooltipEnd(CallbackInfo ci) {
        if (--relaxed$depth > 0) return;
        relaxed$depth = 0;
        if (relaxed$pushed) {
            ((GuiGraphicsExtractor) (Object) this).pose().popMatrix();
            relaxed$pushed = false;
        }
    }

    /** Measures the scoreboard while it draws (LoverFella sits on its corner). */
    @Inject(require = 0, method = "fill(IIIII)V", at = @At("HEAD"))
    private void relaxed$measureFill(int x0, int y0, int x1, int y1, int color, CallbackInfo ci) {
        com.relaxedqol.client.util.ScoreboardBounds.onFill(x0, y0, x1, y1);
    }
}
