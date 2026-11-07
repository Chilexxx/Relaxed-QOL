package com.relaxedqol.mixin.extra;

import com.relaxedqol.client.util.HudInfo;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Counts the boss bars on screen (cards that sit under them move down). Optional mixin (its own config,
 * require 0): if the game ever changes this method, the count just stays 0 - nothing breaks.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
    @ModifyExpressionValue(method = "extractRenderState", require = 0,
        at = @At(value = "INVOKE", target = "Ljava/util/Collection;iterator()Ljava/util/Iterator;"))
    private Iterator<LerpingBossEvent> relaxed$countBosses(Iterator<LerpingBossEvent> original) {
        List<LerpingBossEvent> list = new ArrayList<>();
        original.forEachRemaining(list::add);
        HudInfo.bossBarsDrawn(list.size());
        return list.iterator();
    }
}
