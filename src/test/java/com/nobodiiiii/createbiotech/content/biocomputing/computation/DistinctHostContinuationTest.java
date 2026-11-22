package com.nobodiiiii.createbiotech.content.biocomputing.computation;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DistinctHostContinuationTest {
    static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, 1000000, 1), new ArchiveLimits(1000000, 10000000, 10000000)));
    static ProgramArchive archive() {
        var graph = new ComputationGraph(Map.of("source", new DataType.ListType(DataType.Scalar.ANY)),
            Map.of("source", new ComputationGraph.Input("source"), "unique", new ComputationGraph.Distinct("source")), Map.of("result", "unique"));
        return new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
    }
    static FlowProgram flow() {
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var expressions = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(), Map.of("unique", new ProgramDefinition.Entry(archive().program().graph(), List.of(), List.of()))), Map.of());
        return new FlowProgram(archive().program().graph().inputs(), List.of(new FlowProgram.Evaluate("uniqueList", "unique")), expressions);
    }
    static DataValue.RecordValue inputs(DataValue... items) { return new DataValue.RecordValue(Map.of("source", new DataValue.ListValue(List.of(items)))); }
    static boolean atOffset(MachineCheckpoint machine, String mode, int offset) {
        for (var state : machine.frames()) if (state instanceof MachineCheckpoint.DistinctState distinct
            && distinct.progress().field("comparison") instanceof DataValue.RecordValue comparison) {
            var frames = ((DataValue.ListValue)comparison.field("frames")).values();
            if (!frames.isEmpty()) {
                var frame = (DataValue.RecordValue)frames.getLast();
                if (((DataValue.TextValue)frame.field("mode")).value().equals(mode) && ((DataValue.IntegerValue)frame.field("offset")).value() == offset) return true;
            }
        }
        return false;
    }
    static FlowBlockHost hostAtOffset(DataValue.RecordValue input, String mode) { return hostAtOffset(input, mode, 16); }
    static FlowBlockHost hostAtOffset(DataValue.RecordValue input, String mode, int offset) {
        var host = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), flow(), input, LIMITS);
        for (int i = 0; ; i++) {
            assertTrue(i < 300);
            if (host.snapshot().orElseThrow().state().expression().map(machine -> atOffset(machine, mode, offset)).orElse(false)) return host;
            host.step();
        }
    }
    @Test void actualFlowTransactionalStepsAndPausedActivityDoNotReplayDecoderTraversal() {
        String prefix = "x".repeat(128);
        var input = inputs(new DataValue.TextValue(prefix), new DataValue.TextValue(new String(prefix)));
        var host = hostAtOffset(input, "TEXT"); var visits = new AtomicLong();
        DistinctTask.validationObserver.set(visits::addAndGet);
        try {
            var before = host.snapshot().orElseThrow();
            assertTrue(host.beginActivityTick()); assertEquals(before, host.snapshot().orElseThrow());
            assertEquals(0, visits.get(), "Paused real activity must not scan DISTINCT/text prefixes");
            host.step(); assertEquals(0, visits.get(), "Speculative STEP must clone owned controls, not revalidate consumed characters");
            host.setPaused(false); assertEquals(0, visits.get()); assertEquals(1, host.advanceRound(Map.of())); assertEquals(0, visits.get());
            for (int i = 0; host.snapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) { assertTrue(i < 300); host.advanceRound(Map.of()); }
            assertEquals(0, visits.get());
            var direct = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), flow(), input, LIMITS.hardware());
            for (int i = 0; direct.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) { assertTrue(i < 300); direct.advance(1); }
            assertEquals(direct.checkpoint().work(), host.snapshot().orElseThrow().state().work());
            assertEquals(direct.checkpoint().locals(), host.snapshot().orElseThrow().state().locals());
        } finally { DistinctTask.validationObserver.remove(); }
    }
    @Test void actualFlowLongKeyCursorDoesNotSeekEarlierNamedEntriesOnEachStep() {
        String key = "z".repeat(128); var left = new LinkedHashMap<String, DataValue>(); left.put("first", ListEditTest.number(1)); left.put(key, ListEditTest.number(2));
        var right = new LinkedHashMap<String, DataValue>(); right.put(key, ListEditTest.number(2)); right.put("first", ListEditTest.number(1));
        var host = hostAtOffset(inputs(new DataValue.RecordValue(left), new DataValue.RecordValue(right)), "KEY");
        var visits = new AtomicLong(); DistinctTask.validationObserver.set(visits::addAndGet);
        try { for (int i = 0; i < 10; i++) host.step(); assertEquals(0, visits.get(), "Key-prefix validation/iterator seek are not live work"); }
        finally { DistinctTask.validationObserver.remove(); }
    }
    @Test void loadedPausedActivityDoesNotRepeatTheDecoderValidationJustCompleted() {
        var host = hostAtOffset(inputs(new DataValue.TextValue("x".repeat(128)), new DataValue.TextValue("x".repeat(128))), "TEXT");
        var loaded = new FlowBlockHost(() -> {}, host.write()); var visits = new AtomicLong();
        DistinctTask.validationObserver.set(visits::addAndGet);
        try {
            assertTrue(loaded.synchronize(LIMITS, true)); assertTrue(visits.get() >= 16, "Disk decode still checks the text prefix");
            visits.set(0); var before = loaded.snapshot().orElseThrow();
            assertTrue(loaded.beginActivityTick()); assertEquals(before, loaded.snapshot().orElseThrow());
            assertEquals(0, visits.get(), "Validated decoded publication must retain its opaque proof even without work/publication");
        } finally { DistinctTask.validationObserver.remove(); }
    }
    @Test void sourceAndProgressIdentityBindProofAndDecodedOrPublicStatesStillValidate() {
        var input = inputs(new DataValue.TextValue("x".repeat(64)), new DataValue.TextValue("x".repeat(64)));
        var host = hostAtOffset(input, "TEXT"); var saved = host.snapshot().orElseThrow().state().expression().orElseThrow();
        var state = (MachineCheckpoint.DistinctState)saved.frames().getLast(); assertNotNull(state.continuation());
        var installed = archive().compile(LIMITS.hardware()); var visits = new AtomicLong(); DistinctTask.validationObserver.set(visits::addAndGet);
        try {
            var first = ComputationMachine.restore(installed, saved); var second = ComputationMachine.restore(installed, saved);
            assertEquals(0, visits.get()); first.step(); assertEquals(saved, second.checkpoint()); second.step(); assertEquals(first.checkpoint(), second.checkpoint());
            var publicState = new MachineCheckpoint.DistinctState(state.progress()); assertNull(publicState.continuation());
            var publicCheckpoint = withState(saved, publicState); assertEquals(saved, publicCheckpoint); assertEquals(saved.hashCode(), publicCheckpoint.hashCode());
            ComputationMachine.restore(installed, publicCheckpoint); assertTrue(visits.get() >= 16); visits.set(0);
            ComputationMachine.restore(installed, publicCheckpoint); assertEquals(0, visits.get(), "Successfully decoded controls remember only their own bound proof");
            var snapshot = new ComputerRuntime.Snapshot(UUID.randomUUID(), archive(), saved, Optional.empty(), Optional.empty());
            var decoded = ComputerNbtCodec.decode(ComputerNbtCodec.encode(snapshot, LIMITS.archive()), LIMITS.archive());
            assertNull(((MachineCheckpoint.DistinctState)decoded.machine().frames().getLast()).continuation());
            ComputationMachine.restore(installed, decoded.machine()); assertTrue(visits.get() >= 16); visits.set(0);
            var frames = new ArrayList<>(saved.frames()); var root = (MachineCheckpoint.GraphState)frames.getFirst();
            var source = new DataValue.ListValue(((DataValue.ListValue)root.inputs().field("source")).values());
            frames.set(0, new MachineCheckpoint.GraphState(root.module(), root.cursor(), new DataValue.RecordValue(Map.of("source", source)), new DataValue.RecordValue(Map.of("source", source))));
            var differentSource = new MachineCheckpoint(saved.memory(), saved.status(), saved.paused(), saved.workUnits(), saved.arenaUnits(), saved.output(), saved.diagnostic(), frames);
            ComputationMachine.restore(installed, differentSource); assertTrue(visits.get() >= 16, "Equal new source identity cannot inherit a proof"); visits.set(0);
            ComputationMachine.restore(installed, saved); visits.set(0); // Rebind the genuine proof to the original source first.
            var changed = new DataValue.RecordValue(state.progress().fields());
            ComputationMachine.restore(installed, withState(saved, new MachineCheckpoint.DistinctState(changed, state.continuation())));
            assertTrue(visits.get() >= 16, "Even an internal proof reference cannot authenticate a different progress identity");
            assertEquals(1, MachineCheckpoint.DistinctState.class.getConstructors().length);
        } finally { DistinctTask.validationObserver.remove(); }
    }
    @Test void actualBusyAndBufferedAdmissionsDoNotReplayLongDistinctComparison() {
        var input = inputs(new DataValue.TextValue("x".repeat(128)), new DataValue.TextValue("x".repeat(128)));
        var host = new ComputerBlockHost(() -> {}); host.install(archive(), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true); host.begin(input, LIMITS, true);
        for (int i = 0; !atOffset(host.snapshot().orElseThrow().machine(), "TEXT", 16); i++) { assertTrue(i < 100); host.tick(LIMITS, true); }
        var before = host.snapshot().orElseThrow(); var raw = host.write();
        var visits = new AtomicLong(); DistinctTask.validationObserver.set(visits::addAndGet);
        try {
            var message = new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, input);
            for (int i = 0; i < 5; i++) assertEquals(ReceptionStatus.WAITING_FOR_RECEIVER, host.receiveReady(before.id(), message, LIMITS, true));
            assertEquals(before, host.snapshot().orElseThrow()); assertEquals(raw, host.write()); assertEquals(0, visits.get());
            assertEquals(AdmissionStatus.ACCEPTED, host.receive(message, LIMITS, true)); assertEquals(0, visits.get());
            assertEquals(before.machine(), host.snapshot().orElseThrow().machine()); assertEquals(1, host.snapshot().orElseThrow().mailbox().queued().size());
            assertEquals(AdmissionStatus.DUPLICATE, host.receive(message, LIMITS, true)); assertEquals(0, visits.get());
            var queued = host.snapshot().orElseThrow(); host.tick(LIMITS, true); assertEquals(queued.machine().workUnits() + 1, host.snapshot().orElseThrow().machine().workUnits());
            assertEquals(0, visits.get()); assertEquals(before.machine().memory(), host.snapshot().orElseThrow().machine().memory());
        } finally { DistinctTask.validationObserver.remove(); }
    }
    @Test void failedActualFlowPublicationKeepsOldFrozenControlsAndIndependentRetry() {
        String key = "z".repeat(128); var named = new DataValue.RecordValue(Map.of(key, ListEditTest.number(1)));
        var host = hostAtOffset(inputs(named, new DataValue.RecordValue(named.fields())), "KEY", key.length());
        var before = host.snapshot().orElseThrow(); var raw = host.write();
        long units = ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive()).storageUnits();
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(LIMITS.archive().maxNodes(), LIMITS.archive().maxStringChars(), units)));
        assertTrue(host.synchronize(tight, true));
        assertThrows(ArchiveException.class, host::step); assertEquals(raw, host.write()); assertEquals(before, host.snapshot().orElseThrow());
        assertFalse(host.loaded()); assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
        assertTrue(host.synchronize(LIMITS, true)); assertEquals(before, host.snapshot().orElseThrow());
        var visits = new AtomicLong(); DistinctTask.validationObserver.set(visits::addAndGet);
        try { host.step(); assertEquals(0, visits.get()); assertEquals(before.state().work() + 1, host.snapshot().orElseThrow().state().work()); }
        finally { DistinctTask.validationObserver.remove(); }
        assertTrue(atOffset(before.state().expression().orElseThrow(), "KEY", key.length()), "Failed candidate cannot mutate old key frame");
        var expression = before.state().expression().orElseThrow(); var a = ComputationMachine.restore(archive().compile(LIMITS.hardware()), expression);
        var b = ComputationMachine.restore(archive().compile(LIMITS.hardware()), expression); a.step(); assertEquals(expression, b.checkpoint()); b.step(); assertEquals(a.checkpoint(), b.checkpoint());
    }
    @Test void actualFlowOwnerCapabilitySchemaRoundTripsAndRejectsOlderMarkers() {
        var idle = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), flow(), inputs(new DataValue.IntegerValue(1)), LIMITS);
        assertTrue(idle.snapshot().orElseThrow().state().expression().isEmpty());
        assertEquals(8, idle.write().getInt(FlowBlockNbtCodec.MARKER));
        assertEquals(idle.snapshot().orElseThrow(), FlowBlockNbtCodec.decode(idle.write(), LIMITS.archive()));
        var host = hostAtOffset(inputs(new DataValue.TextValue("x".repeat(128)), new DataValue.TextValue("x".repeat(128))), "TEXT");
        var raw = host.write(); assertEquals(8, raw.getInt(FlowBlockNbtCodec.MARKER));
        assertEquals(host.snapshot().orElseThrow(), FlowBlockNbtCodec.decode(raw, LIMITS.archive()));
        for (int version = 1; version <= 7; version++) { var old = raw.copy(); old.putInt(FlowBlockNbtCodec.MARKER, version); assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(old, LIMITS.archive())); }
        var normal = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()), LIMITS);
        assertEquals(3, normal.write().getInt(FlowBlockNbtCodec.MARKER));
        var owner = new ComputerBlockHost(() -> {}); owner.read(raw); owner.tick(LIMITS, true); assertTrue(owner.loaded()); assertTrue(owner.sequentialMode()); assertEquals(host.snapshot().orElseThrow(), owner.flowSnapshot().orElseThrow());
    }
    static MachineCheckpoint withState(MachineCheckpoint saved, MachineCheckpoint.DistinctState state) {
        var frames = new ArrayList<>(saved.frames()); frames.set(frames.size() - 1, state);
        return new MachineCheckpoint(saved.memory(), saved.status(), saved.paused(), saved.workUnits(), saved.arenaUnits(), saved.output(), saved.diagnostic(), frames);
    }
}
