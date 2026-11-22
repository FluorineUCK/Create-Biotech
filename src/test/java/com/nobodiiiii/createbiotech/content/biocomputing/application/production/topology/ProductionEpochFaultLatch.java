package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionTopologyReconciler.Replica;
import java.util.*;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;

/** Server-internal, monotonic production fault withdrawal; never a general Computer stop or RAM write. */
public final class ProductionEpochFaultLatch {
    private ProductionEpochFaultLatch() {}
    public enum Result { LATCHED, ALREADY_LATCHED, NO_EPOCH, EPOCH_MISMATCH, IDENTITY_INVALID }

    /** The immutable host/version witness is an internal capability, not a programmable value. */
    public record Capture<W, F>(W witness, Replica<F> state) {
        public Capture { Objects.requireNonNull(witness); Objects.requireNonNull(state); }
    }
    public interface Access<H, W, F> {
        Capture<W, F> capture(H host);
        BlockPos position(H host);
        boolean loaded(BlockPos position);
        boolean sameSpace(H caller, BlockPos position);
        @Nullable H computerAt(BlockPos position);
        ProductionLayout.Address address(BlockPos position);
        boolean matches(H host, W witness);
        /** Retains the host's current record, binding and epoch; only replaces its production fault set. */
        void stageFaults(H host, Set<F> faults);
        boolean published(H host);
        void withdraw(H host);
        void withdrawPanel(ProductionLayout.Panel panel);
        void changed(H host);
    }

    public static <H, W, F> Result latch(H caller, UUID expectedEpochId, F fault, Access<H, W, F> access) {
        Objects.requireNonNull(expectedEpochId); Objects.requireNonNull(fault); Objects.requireNonNull(access);
        var initial = access.capture(caller).state();
        if (!initial.persistenceAvailable() || initial.computerId() == null || initial.record() == null)
            return Result.IDENTITY_INVALID;
        var epoch = initial.epoch();
        if (epoch == null) return Result.NO_EPOCH;
        if (!epoch.epochId().equals(expectedEpochId)) return Result.EPOCH_MISMATCH;
        var callerNode = epoch.layout().members().stream()
            .filter(member -> member.computerId().equals(initial.computerId())).findFirst().orElse(null);
        if (callerNode == null) return Result.IDENTITY_INVALID;
        var position = access.position(caller);
        if (!access.loaded(position) || !access.sameSpace(caller, position)
            || access.computerAt(position) != caller || !access.address(position).equals(callerNode.address()))
            return Result.IDENTITY_INVALID;

        var replicas = loadedReplicas(caller, epoch, initial.record(), access);
        var union = new LinkedHashSet<F>();
        for (var host : replicas) union.addAll(access.capture(host).state().faults());
        boolean already = union.contains(fault);
        union.add(fault);
        var faults = Set.copyOf(union);
        var before = replicas.stream().map(access::capture).toList();
        for (int i = 0; i < replicas.size(); i++)
            if (!access.matches(replicas.get(i), before.get(i).witness())) return Result.IDENTITY_INVALID;
        // Withdrawal is monotonic, not a normal publication transaction that could restore healthy service.
        for (var host : replicas) access.stageFaults(host, faults);
        for (var host : replicas) if (access.published(host)) access.withdraw(host);
        initial.record().structure().layout().panels().forEach(access::withdrawPanel);
        for (var host : replicas) access.changed(host);
        return already ? Result.ALREADY_LATCHED : Result.LATCHED;
    }

    private static <H, W, F> List<H> loadedReplicas(H caller, ProductionEpoch epoch,
        ProductionStructure.Revision record, Access<H, W, F> access) {
        var positions = new LinkedHashMap<UUID, BlockPos>();
        for (var member : record.structure().layout().members()) positions.put(member.computerId(), member.address().localPos());
        for (var member : epoch.layout().members()) positions.putIfAbsent(member.computerId(), member.address().localPos());
        var result = new ArrayList<H>();
        for (var entry : positions.entrySet()) {
            var position = entry.getValue();
            if (!access.loaded(position) || !access.sameSpace(caller, position)) continue;
            var host = access.computerAt(position);
            if (host == null) continue;
            var state = access.capture(host).state();
            // Keep the historical absent-record sentinel; malformed archives remain the host's responsibility.
            UUID structureId = state.record() == null ? new UUID(0, 0) : state.record().structureId();
            if (!entry.getKey().equals(state.computerId()) || state.epoch() == null
                || !state.epoch().epochId().equals(epoch.epochId()) || !structureId.equals(epoch.structureId())) continue;
            result.add(host);
        }
        if (result.stream().noneMatch(host -> host == caller)) result.add(caller);
        return List.copyOf(result);
    }
}
