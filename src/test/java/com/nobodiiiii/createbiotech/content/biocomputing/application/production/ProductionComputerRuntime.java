package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import java.util.*;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionQuiescence;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;

/** Durable production application state of one host. Never a Computer program, RAM or pooled hardware. */
public final class ProductionComputerRuntime<S> implements ProductionQuiescence {
    private static final UUID NONE = new UUID(0, 0);
    private static final Scope UNBOUND = new Scope(NONE, NONE);

    public record Scope(UUID groupId, UUID epochId) {
        public Scope { Objects.requireNonNull(groupId); Objects.requireNonNull(epochId); }
    }

    /** Immutable application snapshots and their authoritative codec, not world objects or executable callbacks. */
    public interface Schema<S> {
        Scope scope(S state);
        S empty(Scope scope);
        Optional<S> load(CompoundTag raw, HolderLookup.Provider registries);
        CompoundTag save(S state, HolderLookup.Provider registries);
        boolean allRootsStopped(S state);
        Collection<ProcessRecord> processes(S state);
        boolean hasPendingIntents(S state);
    }

    private final Schema<S> schema;
    private final Supplier<UUID> ownerId;
    private final Runnable dirty;
    private S state;
    private boolean persistenceValid = true;
    private @Nullable Tag rejectedRaw;

    public ProductionComputerRuntime(Schema<S> schema, Supplier<UUID> ownerId, Runnable dirty) {
        this.schema = Objects.requireNonNull(schema); this.ownerId = Objects.requireNonNull(ownerId);
        this.dirty = Objects.requireNonNull(dirty); state = schema.empty(UNBOUND);
    }

    public record Snapshot<S>(S state, boolean persistenceValid) {
        public Snapshot { Objects.requireNonNull(state); }
    }
    public Snapshot<S> snapshot() { return new Snapshot<>(state, persistenceValid); }
    public boolean hasRejectedArchive() { return rejectedRaw != null; }

    /** Checked application replacement; ordinary saving or scope alignment cannot clear rejected evidence. */
    public void install(S replacement) {
        state = Objects.requireNonNull(replacement); persistenceValid = true;
        rejectedRaw = null; dirty.run();
    }

    public void load(@Nullable Tag raw, HolderLookup.Provider registries, @Nullable Scope expected) {
        Objects.requireNonNull(registries);
        var decoded = raw instanceof CompoundTag compound ? schema.load(compound.copy(), registries) : Optional.<S>empty();
        rejectedRaw = null;
        if (decoded.isPresent() && (expected == null || expected.equals(schema.scope(decoded.orElseThrow())))) {
            state = decoded.orElseThrow(); persistenceValid = true;
        } else {
            state = schema.empty(expected == null ? UNBOUND : expected); persistenceValid = raw == null;
            if (raw != null) rejectedRaw = raw.copy();
        }
    }

    public Tag save(HolderLookup.Provider registries) {
        Objects.requireNonNull(registries);
        return rejectedRaw != null ? rejectedRaw.copy() : schema.save(state, registries).copy();
    }

    public void reconcile(@Nullable Scope authoritative) {
        if (authoritative == null) return;
        var current = schema.scope(state);
        if ((!current.groupId().equals(NONE) && !current.groupId().equals(authoritative.groupId()))
            || (!current.epochId().equals(NONE) && !current.epochId().equals(authoritative.epochId()))) {
            // A checked topology transition retires the old executable state, not a rejected archive.
            state = schema.empty(authoritative); persistenceValid = false; dirty.run();
        } else if (current.groupId().equals(NONE)) state = schema.empty(authoritative);
    }

    // Quiescence is work evidence, not authorization: callers must separately check persistence/epoch validity.
    @Override public boolean allRootsStopped() { return schema.allRootsStopped(state); }
    @Override public boolean nodeIdle(UUID computerId) {
        return owns(computerId) && schema.processes(state).stream().noneMatch(process ->
            process.owningComputerId().equals(computerId) && process.state() != ProcessState.TERMINAL);
    }
    @Override public boolean nodeRootFree(UUID computerId) { return nodeIdle(computerId); }
    @Override public boolean nodeMailboxEmpty(UUID computerId) { return owns(computerId) && !schema.hasPendingIntents(state); }
    private boolean owns(UUID computerId) { return Objects.requireNonNull(computerId).equals(ownerId.get()); }
}
