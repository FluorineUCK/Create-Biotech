package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/** One Computer's production attachment. Never owns its program, RAM, hardware or live world objects. */
public final class ProductionComputerTopology<F> {
    private State<F> state = State.empty(true);

    /** F follows the reconciler's immutable production fault-token contract. */
    public record State<F>(@Nullable ProductionStructure.Revision structure,
        @Nullable ProductionBinding binding, boolean bindingValid,
        @Nullable ProductionEpoch epoch, Set<F> faults) {
        public State { faults = Set.copyOf(faults); }
        public static <F> State<F> empty(boolean bindingValid) {
            return new State<>(null,null,bindingValid,null,Set.of());
        }
    }

    public State<F> state() { return state; }

    /** Whole-value restore/stage; invalid historical combinations remain observable, not silently repaired. */
    public void replace(State<F> next) { state = Objects.requireNonNull(next); }

    public void replaceBinding(ProductionBinding binding) {
        replace(new State<>(state.structure(),Objects.requireNonNull(binding),true,state.epoch(),state.faults()));
    }

    /** Host archive, resident and listener checks remain separate from production topology consistency. */
    public boolean validFor(@Nullable UUID computerId) {
        if (computerId==null || !state.bindingValid()) return false;
        var record = state.structure(); var epoch = state.epoch(); var binding = state.binding();
        if (record!=null) {
            if (!contains(record,computerId)) return false;
            if (!contains(record,record.coordinatorId())
                && (epoch==null || !epoch.coordinatorId().equals(record.coordinatorId()))) return false;
        }
        if (epoch!=null) {
            if (record==null || !epoch.structureId().equals(record.structureId())
                || !epoch.coordinatorId().equals(record.coordinatorId())) return false;
            if (binding!=null && !epoch.groupId().equals(binding.groupId())) return false;
        } else if (!state.faults().isEmpty()) return false;
        return binding==null || binding.authority()==null || binding.authority().role()!=ProductionBinding.Role.COORDINATOR
            || record!=null && binding.authority().memberId().equals(record.structureId());
    }

    /** A pending member may install a resident; changing a frozen member's profile requires the production flow. */
    public boolean allowsResidentInstallation(@Nullable UUID computerId) {
        var epoch = state.epoch(); var record = state.structure();
        if (epoch==null) return true;
        if (record==null || computerId==null || !record.structureId().equals(epoch.structureId())
            || !record.coordinatorId().equals(epoch.coordinatorId()) || !contains(record,computerId)) return false;
        return epoch.layout().members().stream().noneMatch(member -> member.computerId().equals(computerId));
    }

    /** Pure preparation: the host withdraws live service before replacing this state when required. */
    public boolean invalidatesPublication(State<F> next, @Nullable UUID computerId) {
        Objects.requireNonNull(next);
        return next.structure()==null || state.structure()==null
            || !next.structure().structureId().equals(state.structure().structureId())
            || !next.structure().coordinatorId().equals(computerId)
            || !Objects.equals(state.binding(),next.binding());
    }

    private static boolean contains(ProductionStructure.Revision record, UUID id) {
        return record.structure().layout().members().stream().anyMatch(member -> member.computerId().equals(id));
    }
}
