package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochAccess.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionDomainProof.Failure;
import java.util.*;
import javax.annotation.Nullable;

/** Actual idle-close protocol for the production application; does not own Computer programs, RAM or hardware. */
public final class ProductionEpochClose {
    private ProductionEpochClose() {}
    private record Key(UUID structureId, UUID epochId) {}
    private static final Set<Key> ACTIVE = new HashSet<>();
    public enum Result {
        CLOSED, NO_EPOCH, EPOCH_MISMATCH, REQUIRES_REFORM, ROOTS_REMAIN, RUNTIME_NOT_QUIESCENT,
        PARTIAL_UNLOADED, SPACE_UNCERTAIN, STRUCTURE_INVALID, FROZEN_MEMBER_MISSING,
        IDENTITY_CONFLICT, PROFILE_NOT_READY, PERSISTENCE_INVALID
    }
    /** W is an immutable host/version witness. Ports are internal world proofs and checked host operations. */
    public interface Access<H,W,F> extends ProductionEpochAccess<H,W,F> {
        ProductionTopologyCommit.Replica prepare(H host, W before, ProductionStructure.Revision record, boolean ready, boolean withdraw);
        ProductionTopologyCommit.Publication publication(ProductionStructure structure, @Nullable H elected, boolean ready);
    }

    public static <H,W,F> Result close(H caller, UUID expectedEpochId, Set<F> reformFaults,
        ProductionQuiescence quiescence, Access<H,W,F> access) {
        Objects.requireNonNull(expectedEpochId); Objects.requireNonNull(quiescence); Objects.requireNonNull(access);
        reformFaults=Set.copyOf(reformFaults);
        var initialWitness=access.capture(caller); var initial=access.value(initialWitness); var epoch=initial.epoch();
        if (epoch==null) return Result.NO_EPOCH;
        if (!epoch.epochId().equals(expectedEpochId)) return Result.EPOCH_MISMATCH;
        if (!Collections.disjoint(initial.faults(),reformFaults)) return Result.REQUIRES_REFORM;
        var record=initial.record();
        if (!initial.persistenceAvailable() || record==null || initial.computerId()==null
            || !access.exactCaller(caller,initialWitness)) return Result.IDENTITY_CONFLICT;
        var key=new Key(record.structureId(),expectedEpochId);
        synchronized (ACTIVE) { if (!ACTIVE.add(key)) return Result.RUNTIME_NOT_QUIESCENT; }
        try {
            var failure=proofFailure(access.proveOld(caller,record,epoch));
            if (failure!=null) return failure;
            var scan=access.scan(caller); failure=scanFailure(scan.state());
            if (failure!=null) return failure;
            var current=scan.structure();
            if (current==null) return Result.STRUCTURE_INVALID;
            if (!record.structureId().equals(scan.observedStructureMemberId())) return Result.IDENTITY_CONFLICT;
            failure=proofFailure(access.proveCurrent(caller,current));
            if (failure!=null) return failure;
            var resolved=access.resolve(caller,current);
            if (resolved.failure()==ResolveFailure.PARTIAL) return Result.PARTIAL_UNLOADED;
            if (resolved.failure()==ResolveFailure.SPACE) return Result.SPACE_UNCERTAIN;
            if (resolved.failure()!=ResolveFailure.NONE || resolved.byId().get(initial.computerId())!=caller) return Result.IDENTITY_CONFLICT;
            var authority=authority(resolved,current,record.structureId(),expectedEpochId,access);
            if (authority==null) return Result.IDENTITY_CONFLICT;
            var authoritativeEpoch=authority.epoch(); var records=List.of(record,authority.record());
            var domain=access.captureDomain(caller,records,authoritativeEpoch,current);
            failure=proofFailure(domain.failure());
            if (failure!=null) return failure;
            var currentIds=new LinkedHashSet<UUID>();
            current.layout().members().forEach(member -> currentIds.add(member.computerId()));
            var states=domain.cells().stream().map(ProductionDomainProof.Cell::state).filter(Objects::nonNull)
                .map(access::value).filter(state -> ProductionTopologyReconciler.relevantReplica(state,record.structureId(),expectedEpochId,currentIds)).toList();
            if (!ProductionTopologyReconciler.transactionReplicasMatch(states,authoritativeEpoch,authority.record())) return Result.IDENTITY_CONFLICT;
            var frozen=new LinkedHashSet<UUID>();
            for (var member : authoritativeEpoch.layout().members()) {
                frozen.add(member.computerId());
                var now=current.layout().members().stream().filter(m -> m.computerId().equals(member.computerId())).findFirst().orElse(null);
                if (now==null || !now.address().equals(member.address())) return Result.FROZEN_MEMBER_MISSING;
                long count=domain.cells().stream().map(ProductionDomainProof.Cell::computer).filter(Objects::nonNull)
                    .filter(host -> member.computerId().equals(access.value(access.capture(host)).computerId())).count();
                if (count!=1) return Result.IDENTITY_CONFLICT;
                if (now.profile()==null || !now.profile().equals(member.profile())) return Result.PROFILE_NOT_READY;
            }
            if (scan.state()==ProductionStructureScanner.State.VALID_NOT_READY)
                for (var member : current.layout().members())
                    if (member.profile()==null && frozen.contains(member.computerId())) return Result.PROFILE_NOT_READY;
            try { authoritativeEpoch.relocate(current.layout()); }
            catch (IllegalArgumentException changedFrozenMembership) { return Result.FROZEN_MEMBER_MISSING; }
            var union=new LinkedHashSet<F>();
            for (var state : states) if (authoritativeEpoch.equals(state.epoch())) union.addAll(state.faults());
            if (!Collections.disjoint(union,reformFaults)) return Result.REQUIRES_REFORM;
            if (!quiescence.allRootsStopped()) return Result.ROOTS_REMAIN;
            var relevant=new TreeSet<UUID>(); relevant.addAll(frozen); relevant.addAll(currentIds);
            for (UUID id : relevant)
                if (!quiescence.nodeIdle(id) || !quiescence.nodeRootFree(id) || !quiescence.nodeMailboxEmpty(id)) return Result.RUNTIME_NOT_QUIESCENT;
            if (!access.revalidateDomain(domain,caller,records,authoritativeEpoch,current)
                || !access.revalidateResolved(caller,current,authoritativeEpoch,Set.copyOf(union))) return Result.IDENTITY_CONFLICT;
            UUID coordinator=current.layout().members().getFirst().computerId();
            var updated=authority.record();
            if (!updated.coordinatorId().equals(coordinator) || !updated.structure().equals(current)) {
                var revised=updated.revise(coordinator,current);
                if (revised.isEmpty()) return Result.PERSISTENCE_INVALID;
                updated=revised.orElseThrow();
            }
            boolean ready=scan.state()!=ProductionStructureScanner.State.VALID_NOT_READY;
            var elected=resolved.byId().get(coordinator); var changes=new ArrayList<ProductionTopologyCommit.Replica>();
            for (var host : resolved.computers()) changes.add(access.prepare(host,access.capture(host),updated,ready,host!=elected || !ready));
            return ProductionTopologyCommit.commit(changes,access.publication(current,elected,ready))==ProductionTopologyCommit.Outcome.COMMITTED
                ? Result.CLOSED : Result.IDENTITY_CONFLICT;
        } finally { synchronized (ACTIVE) { ACTIVE.remove(key); } }
    }

    private static <H,W,F> @Nullable Authority authority(Resolved<H> resolved, ProductionStructure current,
        UUID structureId, UUID expectedEpochId, Access<H,W,F> access) {
        var minimum=resolved.byId().get(current.layout().members().getFirst().computerId());
        if (minimum==null) return null;
        var epoch=access.value(access.capture(minimum)).epoch();
        if (epoch==null || !epoch.epochId().equals(expectedEpochId)) return null;
        var coordinator=resolved.byId().get(epoch.coordinatorId());
        if (coordinator==null) return null;
        var state=access.value(access.capture(coordinator)); var record=state.record();
        if (!state.persistenceAvailable() || !state.bindingValid() || state.binding()==null || record==null
            || !epoch.equals(state.epoch()) || !record.structureId().equals(structureId) || !record.coordinatorId().equals(epoch.coordinatorId())
            || !epoch.coordinatorId().equals(access.value(access.capture(coordinator)).computerId())) return null;
        return new Authority(record,epoch);
    }
    private record Authority(ProductionStructure.Revision record, ProductionEpoch epoch) {}
    private static @Nullable Result proofFailure(Failure failure) {
        return switch (failure) { case NONE -> null; case PARTIAL -> Result.PARTIAL_UNLOADED; case SPACE -> Result.SPACE_UNCERTAIN; };
    }
    private static @Nullable Result scanFailure(ProductionStructureScanner.State state) {
        return switch (state) {
            case VALID, VALID_NOT_READY -> null;
            case PARTIAL_UNLOADED -> Result.PARTIAL_UNLOADED;
            case SPACE -> Result.SPACE_UNCERTAIN;
            case IDENTITY_INVALID, STRUCTURE_IDENTITY_CONFLICT, AMBIGUOUS -> Result.IDENTITY_CONFLICT;
            default -> Result.STRUCTURE_INVALID;
        };
    }
}
