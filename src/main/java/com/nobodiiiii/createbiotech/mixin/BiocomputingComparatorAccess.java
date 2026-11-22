package com.nobodiiiii.createbiotech.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ComparatorBlock.class)
public interface BiocomputingComparatorAccess {
    @Invoker("refreshOutputState")
    void createBiotech$refreshOutputState(Level level, BlockPos pos, BlockState state);
}
