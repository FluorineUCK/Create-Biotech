package com.nobodiiiii.createbiotech.content.biocomputing.block;

import com.simibubi.create.content.decoration.palettes.ConnectedGlassBlock;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class TransparentComputerCasingBlock extends ConnectedGlassBlock implements ComputerShellBlock, IWrenchable {
    public TransparentComputerCasingBlock(Properties properties) { super(properties); }
    @Override public ComputerShellMaterial shellMaterial(BlockState state) { return ComputerShellMaterial.TRANSPARENT; }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        ComputerShellInteractions.beforeRemoval(state, level, pos, replacement);
        super.onRemove(state, level, pos, replacement, moving);
    }
    @Override public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        var result = ComputerShellInteractions.sneakWrench(state, context);
        return result == InteractionResult.PASS ? IWrenchable.super.onSneakWrenched(state, context) : result;
    }
    @Override public InteractionResult onWrenched(BlockState state, UseOnContext context) { return InteractionResult.FAIL; }
}
