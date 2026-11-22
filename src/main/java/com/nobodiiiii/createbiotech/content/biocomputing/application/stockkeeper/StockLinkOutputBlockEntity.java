package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public final class StockLinkOutputBlockEntity extends SmartBlockEntity {
    public StockLinkOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) { behaviours.add(new StockApplicationBehaviour(this)); }
    void depart() { getBehaviour(StockApplicationBehaviour.TYPE).depart(); }
    void reorient() { getBehaviour(StockApplicationBehaviour.TYPE).reorient(); }
}
