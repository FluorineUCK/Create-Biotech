package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochAccess.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionDomainProof.Failure;
import java.util.*;
import javax.annotation.Nullable;

/** Checked production stop/reform protocol. Computer programs, RAM and hardware are not production reservations. */
public final class ProductionEpochReform {
    private ProductionEpochReform() {}
    private record Key(UUID structureId, UUID epochId) {}
    private static final Set<Key> ACTIVE = new HashSet<>();
    public enum Result {
        REFORMED, NO_EPOCH, EPOCH_MISMATCH, STOP_FAILED, ROOTS_REMAIN, RUNTIME_NOT_QUIESCENT,
        PARTIAL_UNLOADED, SPACE_UNCERTAIN, STRUCTURE_INVALID, IDENTITY_CONFLICT, PROFILE_NOT_READY, PERSISTENCE_INVALID
    }
    public interface Access<H,W,F> extends ProductionEpochAccess<H,W,F> {
        boolean panelPublished(ProductionLayout.Panel panel, ProductionEpoch epoch);
        ProductionTopologyCommit.Replica prepare(H host, W before, ProductionStructure.Revision record,
            ProductionBinding binding, ProductionEpoch replacement, boolean withdraw);
        ProductionTopologyCommit.Publication publication(ProductionStructure current, H stagingOwner, ProductionBinding binding,
            ProductionEpoch previous, ProductionEpoch replacement, List<ProductionLayout.Panel> previouslyPublished);
    }

    public static <H,W,F> Result reform(H caller, UUID expectedEpochId, ProductionReformControl control, Access<H,W,F> access) {
        Objects.requireNonNull(expectedEpochId); Objects.requireNonNull(control); Objects.requireNonNull(access);
        var callerWitness=access.capture(caller); var callerState=access.value(callerWitness); var oldEpoch=callerState.epoch();
        if (oldEpoch==null) return Result.NO_EPOCH;
        if (!oldEpoch.epochId().equals(expectedEpochId)) return Result.EPOCH_MISMATCH;
        var oldRecord=callerState.record();
        if (!callerState.persistenceAvailable() || oldRecord==null || callerState.computerId()==null
            || !access.exactCaller(caller,callerWitness)) return Result.IDENTITY_CONFLICT;
        var key=new Key(oldRecord.structureId(),expectedEpochId);
        synchronized (ACTIVE) { if (!ACTIVE.add(key)) return Result.RUNTIME_NOT_QUIESCENT; }
        try {
            var failure=proofFailure(access.proveOld(caller,oldRecord,oldEpoch));
            if (failure!=null) return failure;
            var scan=access.scan(caller); failure=scanFailure(scan.state());
            if (failure!=null) return failure;
            var current=scan.structure();
            if (current==null) return Result.STRUCTURE_INVALID;
            if (!oldRecord.structureId().equals(scan.observedStructureMemberId())) return Result.IDENTITY_CONFLICT;
            failure=proofFailure(access.proveCurrent(caller,current));
            if (failure!=null) return failure;
            var resolved=access.resolve(caller,current);
            if (resolved.failure()==ResolveFailure.PARTIAL) return Result.PARTIAL_UNLOADED;
            if (resolved.failure()==ResolveFailure.SPACE) return Result.SPACE_UNCERTAIN;
            if (resolved.failure()!=ResolveFailure.NONE || resolved.byId().get(callerState.computerId())!=caller) return Result.IDENTITY_CONFLICT;
            if (current.layout().members().stream().anyMatch(member -> member.profile()==null)) return Result.PROFILE_NOT_READY;
            var stagingOwner=resolved.byId().get(current.layout().members().getFirst().computerId());
            if (stagingOwner==null) return Result.IDENTITY_CONFLICT;
            var authorityState=access.value(access.capture(stagingOwner));
            var authorityRecord=authorityState.record(); var authoritativeEpoch=authorityState.epoch();
            if (!authorityState.persistenceAvailable() || !authorityState.bindingValid() || authorityState.binding()==null
                || authorityRecord==null || authoritativeEpoch==null || !expectedEpochId.equals(authoritativeEpoch.epochId())
                || !oldRecord.structureId().equals(authorityRecord.structureId())) return Result.IDENTITY_CONFLICT;
            if (authorityRecord.revision()==Long.MAX_VALUE) return Result.PERSISTENCE_INVALID;
            // A successful stop is not undone if a subsequent proof fails; only topology publication can be restored.
            if (!control.stopAllAndClear()) return Result.STOP_FAILED;
            if (!control.allRootsStopped()) return Result.ROOTS_REMAIN;
            var records=List.of(oldRecord,authorityRecord);
            var domain=access.captureDomain(caller,records,authoritativeEpoch,current);
            failure=proofFailure(domain.failure());
            if (failure!=null) return failure;
            var currentIds=new LinkedHashSet<UUID>();
            current.layout().members().forEach(member -> currentIds.add(member.computerId()));
            var states=domain.cells().stream().map(ProductionDomainProof.Cell::state).filter(Objects::nonNull).map(access::value)
                .filter(state -> ProductionTopologyReconciler.relevantReplica(state,oldRecord.structureId(),expectedEpochId,currentIds)).toList();
            if (!ProductionTopologyReconciler.transactionReplicasMatch(states,authoritativeEpoch,authorityRecord)) return Result.IDENTITY_CONFLICT;
            var relevant=new LinkedHashSet<>(currentIds);
            for (var frozen : authoritativeEpoch.layout().members()) {
                var present=current.layout().members().stream().filter(member -> member.computerId().equals(frozen.computerId())).findFirst().orElse(null);
                long count=domain.cells().stream().map(ProductionDomainProof.Cell::computer).filter(Objects::nonNull)
                    .filter(host -> frozen.computerId().equals(access.value(access.capture(host)).computerId())).count();
                if (present!=null) {
                    if (count!=1) return Result.IDENTITY_CONFLICT;
                    if (!present.address().equals(frozen.address()) || present.profile()==null || !present.profile().equals(frozen.profile()))
                        return Result.PROFILE_NOT_READY;
                    relevant.add(frozen.computerId());
                } else if (count!=0) return Result.IDENTITY_CONFLICT;
            }
            for (UUID id : relevant)
                if (!control.nodeIdle(id) || !control.nodeRootFree(id) || !control.nodeMailboxEmpty(id)) return Result.RUNTIME_NOT_QUIESCENT;
            var faults=new LinkedHashSet<F>();
            for (var state : states) if (authoritativeEpoch.equals(state.epoch())) faults.addAll(state.faults());
            if (!access.revalidateDomain(domain,caller,records,authoritativeEpoch,current)
                || !access.revalidateResolved(caller,current,authoritativeEpoch,Set.copyOf(faults))) return Result.IDENTITY_CONFLICT;
            ProductionEpoch replacement;
            try { replacement=ProductionEpoch.freeze(authoritativeEpoch.groupId(),authorityRecord.structureId(),current.layout()); }
            catch (IllegalArgumentException invalid) { return Result.STRUCTURE_INVALID; }
            var revised=authorityRecord.revise(replacement.coordinatorId(),current);
            if (revised.isEmpty()) return Result.PERSISTENCE_INVALID;
            var updated=revised.orElseThrow();
            var before=resolved.computers().stream().map(access::capture).toList();
            var published=authoritativeEpoch.layout().panels().stream().filter(panel -> access.panelPublished(panel,authoritativeEpoch)).toList();
            var previous=resolved.byId().get(authorityRecord.coordinatorId());
            var changes=new ArrayList<ProductionTopologyCommit.Replica>();
            for (int i=0;i<resolved.computers().size();i++) {
                var host=resolved.computers().get(i);
                changes.add(access.prepare(host,before.get(i),updated,authorityState.binding(),replacement,host==previous && host!=stagingOwner));
            }
            return ProductionTopologyCommit.commit(changes,access.publication(current,stagingOwner,authorityState.binding(),authoritativeEpoch,replacement,published))
                ==ProductionTopologyCommit.Outcome.COMMITTED ? Result.REFORMED : Result.IDENTITY_CONFLICT;
        } finally { synchronized (ACTIVE) { ACTIVE.remove(key); } }
    }

    private static @Nullable Result proofFailure(Failure failure) {
        return switch (failure) { case NONE -> null; case PARTIAL -> Result.PARTIAL_UNLOADED; case SPACE -> Result.SPACE_UNCERTAIN; };
    }
    private static @Nullable Result scanFailure(ProductionStructureScanner.State state) {
        return switch (state) {
            case VALID -> null;
            case VALID_NOT_READY -> Result.PROFILE_NOT_READY;
            case PARTIAL_UNLOADED -> Result.PARTIAL_UNLOADED;
            case SPACE -> Result.SPACE_UNCERTAIN;
            case IDENTITY_INVALID, STRUCTURE_IDENTITY_CONFLICT, AMBIGUOUS -> Result.IDENTITY_CONFLICT;
            default -> Result.STRUCTURE_INVALID;
        };
    }
}
