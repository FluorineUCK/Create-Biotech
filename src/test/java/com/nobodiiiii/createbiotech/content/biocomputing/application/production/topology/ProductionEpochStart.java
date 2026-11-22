package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionTopologyReconciler.Replica;

/** Server-internal production start protocol. No persistent world, hardware, program or RAM ownership. */
public final class ProductionEpochStart {
    private ProductionEpochStart() {}
    public enum Result { STARTED, NOT_COORDINATOR, NOT_READY, BINDING_UNAVAILABLE, ALREADY_ACTIVE, REQUIRES_REFORM }

    /** W is the host's immutable exact-instance/version witness, not a programmable value. */
    public record Capture<W, F>(W witness, Replica<F> state) {
        public Capture { Objects.requireNonNull(witness); Objects.requireNonNull(state); }
    }
    public record Resolved<H>(List<H> computers, Map<UUID, H> byId, boolean valid) {
        public Resolved { computers = List.copyOf(computers); byId = Map.copyOf(byId); }
    }
    public interface Access<H, W, F> {
        Capture<W, F> capture(H host);
        boolean notReady(H host);
        boolean coordinatorOnline(H host);
        boolean chunksLoaded(ProductionStructure structure);
        ProductionStructureScanner.ScanResult scan(H caller);
        /** Proves every observed member's loaded instance, address, profile and persistence, plus panels. */
        Resolved<H> resolve(H caller, ProductionStructure structure);
        boolean enforcePublication();
        boolean published(H host);
        /** Existing binding authority checks/reconciliation; caller and replicas are recaptured afterward. */
        boolean refreshBindingAuthority(H caller);
        ProductionTopologyCommit.Replica prepare(H host, Capture<W, F> before,
            ProductionStructure.Revision record, ProductionBinding binding, ProductionEpoch epoch);
        ProductionTopologyCommit.Publication publication(ProductionStructure structure,
            ProductionBinding binding, ProductionEpoch epoch);
    }

    public static <H, W, F> Result start(H caller, UUID groupId, Access<H, W, F> access) {
        Objects.requireNonNull(groupId); Objects.requireNonNull(access);
        var initial = access.capture(caller).state();
        if (initial.epoch() != null) return Result.ALREADY_ACTIVE;
        if (!initial.faults().isEmpty()) return Result.REQUIRES_REFORM;
        if (access.notReady(caller)) return Result.NOT_READY;
        var record = initial.record();
        if (!initial.persistenceAvailable() || initial.computerId() == null || record == null) return Result.NOT_READY;
        if (!record.coordinatorId().equals(initial.computerId()) || !access.coordinatorOnline(caller)) return Result.NOT_COORDINATOR;
        if (!initial.bindingValid() || initial.binding() == null || !initial.binding().groupId().equals(groupId))
            return Result.BINDING_UNAVAILABLE;
        if (!access.chunksLoaded(record.structure())) return Result.NOT_READY;
        var scan = access.scan(caller);
        if (scan.state() != ProductionStructureScanner.State.VALID || scan.structure() == null
            || !record.structureId().equals(scan.observedStructureMemberId()) || !record.structure().equals(scan.structure()))
            return Result.NOT_READY;
        var resolved = access.resolve(caller, scan.structure());
        if (!resolved.valid() || resolved.byId().get(initial.computerId()) != caller
            || scan.structure().layout().members().stream().anyMatch(member -> member.profile() == null)) return Result.NOT_READY;
        for (var host : resolved.computers())
            if (!admittedReplica(access.capture(host).state(), record.structureId(), initial.binding(), false)) return Result.NOT_READY;
        if (access.enforcePublication() && (!access.published(caller) || !access.refreshBindingAuthority(caller)))
            return Result.BINDING_UNAVAILABLE;

        var authority = access.capture(caller).state();
        var binding = authority.binding();
        if (!authority.persistenceAvailable() || authority.computerId() == null
            || !authority.computerId().equals(record.coordinatorId()) || !Objects.equals(authority.record(), record)
            || !authority.bindingValid() || binding == null || !binding.groupId().equals(groupId)
            || authority.epoch() != null || !authority.faults().isEmpty()) return Result.NOT_READY;
        var finalStates = resolved.computers().stream().map(access::capture).toList();
        for (var state : finalStates)
            if (!admittedReplica(state.state(), record.structureId(), binding, true)) return Result.NOT_READY;
        ProductionEpoch epoch;
        try { epoch = ProductionEpoch.freeze(groupId, record.structureId(), scan.structure().layout()); }
        catch (IllegalArgumentException invalid) { return Result.NOT_READY; }
        var changes = new ArrayList<ProductionTopologyCommit.Replica>();
        for (int i = 0; i < resolved.computers().size(); i++)
            changes.add(access.prepare(resolved.computers().get(i), finalStates.get(i), record, binding, epoch));
        return ProductionTopologyCommit.commit(changes, access.publication(scan.structure(), binding, epoch))
            == ProductionTopologyCommit.Outcome.COMMITTED ? Result.STARTED : Result.NOT_READY;
    }

    private static <F> boolean admittedReplica(Replica<F> state, UUID structureId, ProductionBinding binding, boolean strict) {
        return state.persistenceAvailable() && state.record() != null && state.record().structureId().equals(structureId)
            && (!strict || state.bindingValid()) && state.binding() != null && state.binding().equals(binding)
            && state.epoch() == null && state.faults().isEmpty();
    }
}
