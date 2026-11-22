package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionComputerRuntime.Scope;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.*;

class ProductionComputerRuntimeTest {
    private static final HolderLookup.Provider REGISTRIES = RegistryAccess.EMPTY;
    private static final Scope SCOPE = new Scope(id(1), id(2));

    @Test void missingRuntimeInitializesWithoutDirtyingAndExplicitInstallReplacesExactlyOnce() {
        var f = new Fixture();
        f.runtime.load(null, REGISTRIES, SCOPE);
        assertTrue(f.runtime.snapshot().persistenceValid()); assertEquals(SCOPE, f.runtime.snapshot().state().scope());
        assertEquals(0, f.dirty);
        var installed = new State(SCOPE, 17, true, List.of(), false);
        f.runtime.install(installed);
        assertSame(installed, f.runtime.snapshot().state()); assertEquals(1, f.dirty);
        var encoded = f.runtime.save(REGISTRIES); var reloaded = new Fixture();
        reloaded.runtime.load(encoded, REGISTRIES, SCOPE);
        assertEquals(installed, reloaded.runtime.snapshot().state()); assertTrue(reloaded.runtime.snapshot().persistenceValid());
        assertEquals(0, reloaded.dirty);
    }

    @Test void rejectedArchivesRemainInvalidAndAreCopiedUntilExplicitReplacement() {
        for (String mode : List.of("wrong-type", "malformed", "wrong-group", "wrong-epoch")) {
            var f = new Fixture();
            var compound = SCHEMA.save(new State(SCOPE, 17, true, List.of(), false), REGISTRIES);
            Tag raw = compound;
            switch (mode) {
                case "wrong-type" -> raw = StringTag.valueOf("unrecognized");
                case "malformed" -> compound.putString("Unknown", "evidence");
                case "wrong-group" -> compound.putUUID("Group", id(99));
                case "wrong-epoch" -> compound.putUUID("Epoch", id(99));
                default -> throw new AssertionError(mode);
            }
            var expected = raw.copy();
            f.runtime.load(raw, REGISTRIES, SCOPE);
            if (raw instanceof CompoundTag tag) tag.putString("AfterLoad", "must not alias");
            assertFalse(f.runtime.snapshot().persistenceValid(), mode);
            assertTrue(f.runtime.hasRejectedArchive(), mode);
            assertEquals(SCHEMA.empty(SCOPE), f.runtime.snapshot().state(), mode);
            assertEquals(0, f.dirty); assertEquals(expected, f.runtime.save(REGISTRIES), mode);
            var output = f.runtime.save(REGISTRIES);
            if (output instanceof CompoundTag tag) tag.remove("Group");
            assertEquals(expected, f.runtime.save(REGISTRIES), "Returned data is not the retained archive");
            f.runtime.reconcile(new Scope(id(100), id(101)));
            assertEquals(expected, f.runtime.save(REGISTRIES), "Topology alignment cannot erase a rejected input");
            var reloaded = new Fixture(); reloaded.runtime.load(f.runtime.save(REGISTRIES), REGISTRIES, SCOPE);
            assertFalse(reloaded.runtime.snapshot().persistenceValid(), mode);
            f.runtime.install(SCHEMA.empty(SCOPE));
            assertTrue(f.runtime.snapshot().persistenceValid(), mode);
            assertFalse(f.runtime.hasRejectedArchive(), mode);
            assertEquals(SCHEMA.save(SCHEMA.empty(SCOPE), REGISTRIES), f.runtime.save(REGISTRIES), mode);
        }
    }

    @Test void checkedScopeTransitionsKeepTheOriginalInitializationAndDirtyingRules() {
        var f = new Fixture(); var initial = f.runtime.snapshot();
        f.runtime.reconcile(null); assertEquals(initial, f.runtime.snapshot()); assertEquals(0, f.dirty);
        f.runtime.reconcile(SCOPE);
        assertEquals(SCHEMA.empty(SCOPE), f.runtime.snapshot().state());
        assertTrue(f.runtime.snapshot().persistenceValid()); assertEquals(0, f.dirty);
        f.runtime.install(new State(SCOPE, 17, true, List.of(), false));
        var before = f.runtime.snapshot(); f.runtime.reconcile(SCOPE);
        assertEquals(before, f.runtime.snapshot()); assertEquals(1, f.dirty);
        var next = new Scope(id(4), id(5)); f.runtime.reconcile(next);
        assertEquals(SCHEMA.empty(next), f.runtime.snapshot().state());
        assertFalse(f.runtime.snapshot().persistenceValid()); assertEquals(2, f.dirty);
        f.runtime.reconcile(next); assertEquals(2, f.dirty);
        // A validated epoch transition saves the new empty scope, unlike a rejected input archive.
        var restored = new Fixture(); restored.runtime.load(f.runtime.save(REGISTRIES), REGISTRIES, next);
        assertTrue(restored.runtime.snapshot().persistenceValid()); assertEquals(SCHEMA.empty(next), restored.runtime.snapshot().state());
        f.runtime.load(StringTag.valueOf("bad"), REGISTRIES, null); var bad = f.runtime.save(REGISTRIES);
        f.runtime.reconcile(next);
        assertFalse(f.runtime.snapshot().persistenceValid()); assertEquals(bad, f.runtime.save(REGISTRIES));
        assertEquals(2, f.dirty, "Initializing an unbound placeholder cannot mark a bad archive recovered");
    }

    @Test void quiescenceUsesCurrentHostIdentityAndNeverTreatsAnotherMembersProcessAsItsOwn() {
        for (var processState : ProcessState.values()) for (boolean pending : List.of(false, true)) {
            var f = new Fixture();
            var librarian = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
            var fletcher = ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER, 2, 0).orElseThrow();
            var local = new ProcessRecord(id(11), id(21), id(3), 0, librarian, ProcessKind.GOAL,
                processState, processState == ProcessState.WAITING ? WaitReason.MATERIAL : null, 0, Map.of());
            var other = new ProcessRecord(id(12), id(22), id(4), 0, fletcher, ProcessKind.GOAL,
                ProcessState.TERMINAL, null, 0, Map.of());
            var state = new State(SCOPE, 17, false, List.of(local, other), pending); f.runtime.install(state);
            assertFalse(f.runtime.allRootsStopped());
            assertEquals(processState == ProcessState.TERMINAL, f.runtime.nodeIdle(id(3)));
            assertEquals(f.runtime.nodeIdle(id(3)), f.runtime.nodeRootFree(id(3)));
            assertEquals(!pending, f.runtime.nodeMailboxEmpty(id(3)));
            assertFalse(f.runtime.nodeIdle(id(4))); assertFalse(f.runtime.nodeRootFree(id(4))); assertFalse(f.runtime.nodeMailboxEmpty(id(4)));
            f.owner = id(4);
            assertTrue(f.runtime.nodeIdle(id(4)), "A different Computer's busy process does not become this host's work");
            assertFalse(f.runtime.nodeIdle(id(3)), "Identity is not captured permanently at construction");
            f.owner = null;
            assertFalse(f.runtime.nodeIdle(id(4))); assertFalse(f.runtime.nodeMailboxEmpty(id(4)));
            assertSame(state, f.runtime.snapshot().state()); assertEquals(1, f.dirty, "Observation never changes the snapshot");
        }
    }

    @Test void aFreshLoadReplacesPriorRecoveryWithoutRequiringAnEpochOrMarkingDirty() {
        var f = new Fixture(); f.runtime.load(StringTag.valueOf("old rejected data"), REGISTRIES, SCOPE);
        assertTrue(f.runtime.hasRejectedArchive());
        var installed = new State(new Scope(id(7), id(8)), 29, true, List.of(), false);
        f.runtime.load(SCHEMA.save(installed, REGISTRIES), REGISTRIES, null);
        assertEquals(installed, f.runtime.snapshot().state()); assertTrue(f.runtime.snapshot().persistenceValid());
        assertFalse(f.runtime.hasRejectedArchive()); assertEquals(0, f.dirty);
        var loaded = f.runtime.snapshot().state();
        f.runtime.reconcile(null); assertSame(loaded, f.runtime.snapshot().state());
        f.runtime.load(null, REGISTRIES, null);
        assertEquals(new Scope(id(0), id(0)), f.runtime.snapshot().state().scope());
        assertTrue(f.runtime.snapshot().persistenceValid()); assertFalse(f.runtime.hasRejectedArchive()); assertEquals(0, f.dirty);
    }

    private static UUID id(long value) { return new UUID(0, value); }
    private record State(Scope scope, int value, boolean rootsStopped, List<ProcessRecord> processes, boolean pending) {
        State { processes = List.copyOf(processes); }
    }
    private static final ProductionComputerRuntime.Schema<State> SCHEMA = new ProductionComputerRuntime.Schema<>() {
        public Scope scope(State state) { return state.scope(); }
        public State empty(Scope scope) { return new State(scope, 0, true, List.of(), false); }
        public Optional<State> load(CompoundTag raw, HolderLookup.Provider registries) {
            if (!raw.getAllKeys().equals(Set.of("Group", "Epoch", "Value", "Roots", "Pending", "Processes"))
                || !raw.hasUUID("Group") || !raw.hasUUID("Epoch") || !raw.contains("Value", Tag.TAG_INT)) return Optional.empty();
            var processes = new ArrayList<ProcessRecord>();
            for (var tag : raw.getList("Processes", Tag.TAG_COMPOUND)) {
                var decoded = ProcessRecordCodecs.load((CompoundTag) tag);
                if (decoded.isEmpty()) return Optional.empty(); processes.add(decoded.orElseThrow());
            }
            return Optional.of(new State(new Scope(raw.getUUID("Group"), raw.getUUID("Epoch")), raw.getInt("Value"),
                raw.getBoolean("Roots"), processes, raw.getBoolean("Pending")));
        }
        public CompoundTag save(State state, HolderLookup.Provider registries) {
            var tag = new CompoundTag(); tag.putUUID("Group", state.scope().groupId()); tag.putUUID("Epoch", state.scope().epochId());
            tag.putInt("Value", state.value()); tag.putBoolean("Roots", state.rootsStopped()); tag.putBoolean("Pending", state.pending());
            var processes = new ListTag(); state.processes().forEach(process -> processes.add(ProcessRecordCodecs.save(process)));
            tag.put("Processes", processes); return tag;
        }
        public boolean allRootsStopped(State state) { return state.rootsStopped(); }
        public Collection<ProcessRecord> processes(State state) { return state.processes(); }
        public boolean hasPendingIntents(State state) { return state.pending(); }
    };
    private static final class Fixture {
        UUID owner = id(3);
        int dirty;
        final ProductionComputerRuntime<State> runtime = new ProductionComputerRuntime<>(SCHEMA, () -> owner, () -> dirty++);
    }
}
