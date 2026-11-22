package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import java.util.Collection;
import java.util.Optional;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.RuntimeNbt;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/** Current production-engine value/codec adapter; no host, epoch object or runtime ownership. */
public final class ProductionEngineSchema implements ProductionComputerRuntime.Schema<ClusterEngineState> {
    public static final ProductionEngineSchema INSTANCE = new ProductionEngineSchema();

    private ProductionEngineSchema() {}

    @Override public ProductionComputerRuntime.Scope scope(ClusterEngineState state) {
        return new ProductionComputerRuntime.Scope(state.clusterId(), state.epochId());
    }
    @Override public ClusterEngineState empty(ProductionComputerRuntime.Scope scope) {
        return ClusterEngineState.empty(scope.groupId(), scope.epochId());
    }
    @Override public Optional<ClusterEngineState> load(CompoundTag raw, HolderLookup.Provider registries) {
        return RuntimeNbt.load(raw, registries);
    }
    @Override public CompoundTag save(ClusterEngineState state, HolderLookup.Provider registries) {
        return RuntimeNbt.save(state, registries);
    }
    @Override public boolean allRootsStopped(ClusterEngineState state) {
        return state.goals().values().stream().allMatch(goal -> goal.state() == GoalState.TERMINAL);
    }
    @Override public Collection<ProcessRecord> processes(ClusterEngineState state) {
        return state.processes().values();
    }
    @Override public boolean hasPendingIntents(ClusterEngineState state) {
        return !state.pendingIntents().isEmpty();
    }
}
