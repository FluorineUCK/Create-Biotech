package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.Optional;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerPhysicalHost;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Old topology requires an explicit production host. A registered Computer is NOT one.
 * The only concrete implementation is in the historical tests. */
public abstract class HistoricalProductionHost extends SmartBlockEntity implements ComputerPhysicalHost {
    protected HistoricalProductionHost(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
    public abstract Optional<UUID> computerId();
    public abstract boolean isAuthoritativeLoadedInstance();
    public abstract ProductionComputer production();
}
