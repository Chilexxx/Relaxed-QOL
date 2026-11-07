package com.relaxedqol.mixin;

import com.relaxedqol.client.RelaxedQolClient;
import com.relaxedqol.client.module.modules.render.Freelook;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    protected abstract void setPosition(Vec3 position);

    /**
     * Freelook: after vanilla placed the camera on the player, turn it to the free-look angle. Third person: put it
     * behind your head along that angle (pulled in when a wall is in the way) and show your own body.
     */
    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void relaxed$freelook(float partialTicks, CallbackInfo ci) {
        Freelook freelook = RelaxedQolClient.enabled(Freelook.class);
        if (freelook == null || !freelook.isLooking()) return;
        setRotation(freelook.getYaw(), freelook.getPitch());
        Vec3 position = freelook.cameraPosition(partialTicks);
        if (position != null) {
            setPosition(position);
            // Detached makes the game render your own player model
            detached = true;
        }
    }
}
