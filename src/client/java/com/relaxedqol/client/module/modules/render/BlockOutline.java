package com.relaxedqol.client.module.modules.render;

import com.relaxedqol.client.module.Module;
import com.relaxedqol.client.module.ModuleCategory;
import com.relaxedqol.client.setting.Setting;
import com.relaxedqol.client.util.Colors;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * (Lunar-style "Block Outline"): the outline around the block you look at, in your colour and thickness,
 * optionally filled (fill off by default, no "through walls"). Replaces the thin black vanilla outline while on.
 */
public class BlockOutline extends Module {
    private final Setting<String> color = addSetting(Colors.setting("Colour", "Accent"), "Click the swatch to pick a colour");
    private final Setting<Double> width = addSetting(new Setting<>("Line Width", 2.5, 1.0, 8.0, 0.5));
    private final Setting<Integer> lineAlpha = addSetting(new Setting<>("Line Opacity %", 90, 10, 100, 5));
    /** Off by default (just the outline). */
    private final Setting<Boolean> fill = addSetting(new Setting<>("Fill", false), "A see-through tint over the block's sides");
    private final Setting<Integer> fillAlpha = addSetting(new Setting<>("Fill Opacity %", 14, 2, 80, 2).showIf(() -> this.fill.getValue()));
    private final Setting<Boolean> exact = addSetting(new Setting<>("Exact Shape", true),
        "Follow the block's real shape (stairs, slabs, fences) instead of one box around it");

    public BlockOutline() {
        super("BlockOutline", "Customise the outline of the block you look at", ModuleCategory.RENDER);
    }

    @Override
    public void onRender3D(float partialTicks) {
        if (mc.level == null || mc.player == null) return;
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos pos = bhr.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return;
        VoxelShape shape = state.getShape(mc.level, pos);
        if (shape.isEmpty()) return;
        int base = Colors.of(color.getValue());
        int line = Colors.withAlpha(base, Math.round(lineAlpha.getValue() * 2.55f));
        int fillColor = Colors.withAlpha(base, Math.round(fillAlpha.getValue() * 2.55f));
        float w = width.getValue().floatValue();
        List<AABB> boxes = exact.getValue() ? shape.toAabbs() : List.of(shape.bounds());
        if (boxes.size() > 24) boxes = List.of(shape.bounds()); // very detailed shapes: one clean box
        for (AABB box : boxes) {
            AABB b = box.move(pos.getX(), pos.getY(), pos.getZ()).inflate(0.002);
            Gizmos.cuboid(b, fill.getValue() ? GizmoStyle.strokeAndFill(line, w, fillColor) : GizmoStyle.stroke(line, w));
        }
    }

    /** Vanilla's outline is hidden while this is on. */
    public boolean replacesVanilla() {
        return true;
    }
}
