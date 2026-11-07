package com.relaxedqol.mixin;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Zoom: writes an option's value directly. The normal setter refuses values outside the slider range
 * (FOV below 30), and zoom needs a much smaller field of view for a moment.
 */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {
    @Accessor("value")
    void relaxed$setRawValue(Object value);
}
