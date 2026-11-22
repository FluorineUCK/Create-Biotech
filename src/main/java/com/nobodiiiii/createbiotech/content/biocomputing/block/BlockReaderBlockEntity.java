package com.nobodiiiii.createbiotech.content.biocomputing.block;

import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderSerialBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Retains the existing reader block and hand-capture path; optional independent serial authorization. */
public final class BlockReaderBlockEntity extends SmartBlockEntity {
    public BlockReaderBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) { behaviours.add(new ReaderSerialBehaviour(this)); }
}
