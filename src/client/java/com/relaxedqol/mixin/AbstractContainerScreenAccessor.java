package com.relaxedqol.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the slot under the mouse (shown in the Quick Panel status bar). */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("hoveredSlot")
    Slot relaxed$getHoveredSlot();

    @Accessor("leftPos")
    int relaxed$getLeftPos();

    @Accessor("topPos")
    int relaxed$getTopPos();

    @Accessor("imageWidth")
    int relaxed$getImageWidth();
}
