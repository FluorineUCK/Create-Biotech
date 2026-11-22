package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import java.util.*;
import javax.annotation.Nullable;

/** Production-only decisions from captured values; never a world writer or a shared Computer resource pool. */
public final class ProductionTopologyReconciler {
    private ProductionTopologyReconciler() {}

    public enum Status {
        NONE, NOT_READY, IDENTITY_CONFLICT, PERSISTENCE_INVALID, COORDINATOR_MISSING,
        PARTIAL_UNLOADED, STRUCTURE_INVALID, FROZEN_MEMBER_MISSING
    }
    public enum Proof { READY, PARTIAL_UNLOADED, IDENTITY_CONFLICT }
    public interface Evidence {
        UUID newStructureId();
        Proof proveReelection(ProductionStructure.Revision previous, ProductionStructure current, UUID coordinator);
    }
    /** F is an immutable production fault token; reconciliation unions tokens without interpreting their category. */
    public record Replica<F>(@Nullable UUID computerId, boolean persistenceAvailable, boolean bindingValid,
        @Nullable ProductionStructure.Revision record, @Nullable ProductionBinding binding,
        @Nullable ProductionEpoch epoch, Set<F> faults) {
        public Replica { faults = Set.copyOf(faults); }
    }
    public record Decision<F>(boolean accepted, Status status, @Nullable ProductionStructure.Revision record,
        @Nullable ProductionBinding binding, @Nullable ProductionEpoch epoch, Set<F> faults) {
        public Decision { Objects.requireNonNull(status); faults = Set.copyOf(faults); }
        private static <F> Decision<F> failure(Status status) {
            return new Decision<>(false,status,null,null,null,Set.of());
        }
    }

    public static <F> Decision<F> reconcile(ProductionStructure structure, boolean ready,
        @Nullable UUID observedStructureId, List<Replica<F>> replicas, Evidence evidence) {
        Objects.requireNonNull(structure); Objects.requireNonNull(evidence);
        replicas = List.copyOf(replicas);
        var byId = new LinkedHashMap<UUID,Replica<F>>();
        for (var state : replicas)
            if (!state.persistenceAvailable() || state.computerId()==null || !state.bindingValid()
                || byId.put(state.computerId(),state)!=null) return Decision.failure(Status.IDENTITY_CONFLICT);
        var members = structure.layout().members();
        if (byId.size()!=members.size() || members.stream().anyMatch(member -> !byId.containsKey(member.computerId())))
            return Decision.failure(Status.IDENTITY_CONFLICT);

        var structureIds = new LinkedHashSet<UUID>();
        for (var state : replicas) {
            if (state.record()!=null) structureIds.add(state.record().structureId());
            if (state.epoch()!=null) structureIds.add(state.epoch().structureId());
        }
        if (structureIds.size()>1) return Decision.failure(Status.IDENTITY_CONFLICT);
        UUID structureId = structureIds.isEmpty() ? Objects.requireNonNull(evidence.newStructureId()) : structureIds.iterator().next();
        if (observedStructureId!=null && !observedStructureId.equals(structureId))
            return Decision.failure(Status.IDENTITY_CONFLICT);

        ProductionEpoch active = null;
        for (var state : replicas) {
            if (state.epoch()==null) continue;
            if (active==null) active=state.epoch();
            else if (!active.equals(state.epoch())) return Decision.failure(Status.PERSISTENCE_INVALID);
        }
        var selection = authoritativeRecord(replicas,byId,active);
        if (!selection.valid()) return Decision.failure(Status.IDENTITY_CONFLICT);
        var authority = selection.record();
        var records = replicas.stream().map(Replica::record).filter(Objects::nonNull).toList();
        if (!recordsCompatible(records,authority,structureId)) return Decision.failure(Status.IDENTITY_CONFLICT);
        if (active!=null && !byId.containsKey(active.coordinatorId())) return Decision.failure(Status.COORDINATOR_MISSING);

        UUID bindingCoordinator = active!=null ? active.coordinatorId()
            : authority!=null ? authority.coordinatorId() : members.getFirst().computerId();
        var bindingOwner = byId.get(bindingCoordinator);
        boolean electingAfterDestruction = active==null && authority!=null && bindingOwner==null;
        ProductionBinding binding = bindingOwner!=null ? bindingOwner.binding()
            : electingAfterDestruction ? replicas.getFirst().binding() : null;
        for (var state : replicas) {
            var replica = state.binding();
            if (electingAfterDestruction && !Objects.equals(binding,replica)) return Decision.failure(Status.IDENTITY_CONFLICT);
            if (state.record()==null && state.epoch()==null && replica==null) continue;
            if (binding==null ? replica!=null : replica==null || !binding.groupId().equals(replica.groupId())
                || replica.revision()>binding.revision() || replica.revision()==binding.revision() && !replica.equals(binding))
                return Decision.failure(Status.IDENTITY_CONFLICT);
        }
        if (active!=null && (binding==null || !binding.groupId().equals(active.groupId())))
            return Decision.failure(Status.PERSISTENCE_INVALID);

        var union = new LinkedHashSet<F>();
        for (var state : replicas) {
            if (active!=null) {
                if (state.epoch()!=null && state.epoch().epochId().equals(active.epochId())) union.addAll(state.faults());
            } else if (!state.faults().isEmpty()) return Decision.failure(Status.PERSISTENCE_INVALID);
        }

        UUID coordinator;
        ProductionEpoch desiredEpoch = active;
        Status status;
        if (active==null) {
            coordinator=members.getFirst().computerId();
            if (authority!=null && !authority.coordinatorId().equals(coordinator)) {
                Proof proof = Objects.requireNonNull(evidence.proveReelection(authority,structure,coordinator));
                if (proof!=Proof.READY) return Decision.failure(proof==Proof.PARTIAL_UNLOADED
                    ? Status.PARTIAL_UNLOADED : Status.IDENTITY_CONFLICT);
            }
            status=ready ? Status.NONE : Status.NOT_READY;
        } else {
            coordinator=active.coordinatorId();
            var current = new HashMap<UUID,ProductionLayout.Member>();
            members.forEach(member -> current.put(member.computerId(),member));
            boolean missing=false;
            for (var frozen : active.layout().members()) {
                var observed = current.get(frozen.computerId());
                if (observed==null) missing=true;
                else if (!frozen.profile().equals(observed.profile())) return Decision.failure(Status.PERSISTENCE_INVALID);
            }
            if (missing) status=Status.FROZEN_MEMBER_MISSING;
            else {
                try { desiredEpoch=active.relocate(structure.layout()); }
                catch (IllegalArgumentException invalid) { return Decision.failure(Status.STRUCTURE_INVALID); }
                status=Status.NONE;
            }
        }

        ProductionStructure.Revision desired;
        if (records.isEmpty()) desired=new ProductionStructure.Revision(structureId,0,coordinator,structure);
        else {
            var base = authority!=null ? authority : records.getFirst();
            if (base.structureId().equals(structureId) && base.coordinatorId().equals(coordinator)
                && base.structure().equals(structure)) desired=base;
            else {
                var revised=base.revise(coordinator,structure);
                if (revised.isEmpty()) return Decision.failure(Status.PERSISTENCE_INVALID);
                desired=revised.orElseThrow();
            }
        }
        return new Decision<>(true,status,desired,binding,desiredEpoch,union);
    }

    /** Shared with close/reform validation; an older replica is allowed, an equal divergent one is not. */
    public static boolean recordsCompatible(List<ProductionStructure.Revision> records,
        @Nullable ProductionStructure.Revision authority, UUID structureId) {
        for (var record : records) {
            if (!record.structureId().equals(structureId)) return false;
            if (authority==null) continue;
            if (record.revision()>authority.revision() || record.revision()==authority.revision()
                && (!record.coordinatorId().equals(authority.coordinatorId()) || !record.structure().equals(authority.structure()))) return false;
        }
        return true;
    }

    /** Includes old-domain replicas as well as current members, so stale/conflicting copies cannot be ignored. */
    public static <F> boolean relevantReplica(Replica<F> state, UUID structureId, UUID epochId, Set<UUID> currentIds) {
        return state.record()!=null && state.record().structureId().equals(structureId)
            || state.epoch()!=null && (state.epoch().epochId().equals(epochId) || state.epoch().structureId().equals(structureId))
            || state.computerId()!=null && currentIds.contains(state.computerId());
    }

    /** Shared close/reform admission: complete equal epochs and bindings, with compatible record revisions. */
    public static <F> boolean transactionReplicasMatch(List<Replica<F>> states, ProductionEpoch epoch,
        ProductionStructure.Revision authority) {
        if (states.isEmpty() || !recordsCompatible(states.stream().map(Replica::record).filter(Objects::nonNull).toList(),
            authority, authority.structureId())) return false;
        ProductionBinding binding = null;
        for (var state : states) {
            if (!state.persistenceAvailable() || !state.bindingValid() || state.binding()==null
                || !state.binding().groupId().equals(epoch.groupId()) || !epoch.equals(state.epoch())
                || state.record()==null || !state.record().structureId().equals(authority.structureId())) return false;
            if (binding==null) binding=state.binding();
            else if (!binding.equals(state.binding())) return false;
        }
        return binding!=null;
    }

    private static <F> Selection authoritativeRecord(List<Replica<F>> replicas,
        Map<UUID,Replica<F>> byId, @Nullable ProductionEpoch active) {
        if (active!=null) {
            var coordinator=byId.get(active.coordinatorId());
            if (coordinator==null) return new Selection(null,true);
            return new Selection(coordinator.record(),coordinator.record()!=null);
        }
        var coordinators=new LinkedHashSet<UUID>();
        replicas.stream().map(Replica::record).filter(Objects::nonNull).forEach(record -> coordinators.add(record.coordinatorId()));
        if (coordinators.size()>1) return new Selection(null,false);
        if (coordinators.isEmpty()) return new Selection(null,true);
        var coordinator=byId.get(coordinators.iterator().next());
        if (coordinator!=null) return new Selection(coordinator.record(),coordinator.record()!=null);
        var records=replicas.stream().map(Replica::record).filter(Objects::nonNull).toList();
        var first=records.getFirst();
        return new Selection(first,records.stream().allMatch(first::equals));
    }
    private record Selection(@Nullable ProductionStructure.Revision record, boolean valid) {}
}
