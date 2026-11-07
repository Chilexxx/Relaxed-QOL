package com.relaxedqol.client.module.modules.player;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;

/** Toggle sprint: sprints for you whenever you walk forward (not while sneaking, hungry or walking into a wall). */
public class Sprint extends Module {
    public Sprint() {
        super("Sprint", "Toggle sprint: sprints for you whenever you walk forward", ModuleCategory.PLAYER);
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        if (mc.player.isShiftKeyDown() || mc.player.getFoodData().getFoodLevel() <= 6 || mc.player.horizontalCollision) return;

        if (mc.player.input.hasForwardImpulse() && !mc.player.isSprinting()) {
            mc.player.setSprinting(true);
        }
    }
}
