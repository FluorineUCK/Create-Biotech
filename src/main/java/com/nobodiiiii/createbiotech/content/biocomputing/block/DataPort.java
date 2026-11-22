package com.nobodiiiii.createbiotech.content.biocomputing.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** Physical connector only; adjacency does not grant shared memory or world write access. */
public interface DataPort {
    boolean acceptsCable(BlockState state, Direction side);
}
