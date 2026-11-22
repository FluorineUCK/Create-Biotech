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

class ValueEqualityHostContinuationTest {
    static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, 1000000, 1), new ArchiveLimits(1000000, 10000000, 10000000)));
    static ProgramArchive archive() {
        var graph = new ComputationGraph(Map.of("left", DataType.Scalar.ANY, "right", DataType.Scalar.ANY),
            Map.of("left", new ComputationGraph.Input("left"), "right", new ComputationGraph.Input("right"), "same", new ComputationGraph.ValueEquals("left", "right")), Map.of("result", "same"));
        return new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
    }
    static FlowProgram flow() {
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var expressions = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(), Map.of("compare", new ProgramDefinition.Entry(archive().program().graph(), List.of(), List.of()))), Map.of());
        return new FlowProgram(archive().program().graph().inputs(), List.of(new FlowProgram.Evaluate("comparison", "compare")), expressions);
    }
    static DataValue.RecordValue inputs(DataValue... items) { return new DataValue.RecordValue(Map.of("left", items[0], "right", items.length > 1 ? items[1] : items[0])); }
    static boolean atOffset(MachineCheckpoint machine, String mode, int offset) {
        for (var state : machine.frames()) if (state instanceof MachineCheckpoint.ValueEqualityState equality) {
            var comparison = equality.progress();
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
            assertEquals(0, visits.get(), "Paused real activity must not scan VALUE_EQUALS/text prefixes");
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
        var state = (MachineCheckpoint.ValueEqualityState)saved.frames().getLast(); assertNotNull(state.continuation());
        var installed = archive().compile(LIMITS.hardware()); var visits = new AtomicLong(); DistinctTask.validationObserver.set(visits::addAndGet);
        try {
            var first = ComputationMachine.restore(installed, saved); var second = ComputationMachine.restore(installed, saved);
            assertEquals(0, visits.get()); first.step(); assertEquals(saved, second.checkpoint()); second.step(); assertEquals(first.checkpoint(), second.checkpoint());
            var publicState = new MachineCheckpoint.ValueEqualityState(state.progress()); assertNull(publicState.continuation());
            var publicCheckpoint = withState(saved, publicState); assertEquals(saved, publicCheckpoint); assertEquals(saved.hashCode(), publicCheckpoint.hashCode());
            ComputationMachine.restore(installed, publicCheckpoint); assertTrue(visits.get() >= 16); visits.set(0);
            ComputationMachine.restore(installed, publicCheckpoint); assertEquals(0, visits.get(), "Successfully decoded controls remember only their own bound proof");
            var snapshot = new ComputerRuntime.Snapshot(UUID.randomUUID(), archive(), saved, Optional.empty(), Optional.empty());
            var decoded = ComputerNbtCodec.decode(ComputerNbtCodec.encode(snapshot, LIMITS.archive()), LIMITS.archive());
            assertNull(((MachineCheckpoint.ValueEqualityState)decoded.machine().frames().getLast()).continuation());
            ComputationMachine.restore(installed, decoded.machine()); assertTrue(visits.get() >= 16); visits.set(0);
            var frames = new ArrayList<>(saved.frames()); var root = (MachineCheckpoint.GraphState)frames.getFirst();
            for (String port : List.of("left", "right")) {
                var original = (DataValue.TextValue) root.inputs().field(port);
                var source = new DataValue.TextValue(new String(original.value()));
                var values = new LinkedHashMap<>(root.inputs().fields()); values.put(port, source);
                frames.set(0, new MachineCheckpoint.GraphState(root.module(), root.cursor(), new DataValue.RecordValue(values), new DataValue.RecordValue(values)));
                var differentSource = new MachineCheckpoint(saved.memory(), saved.status(), saved.paused(), saved.workUnits(), saved.arenaUnits(), saved.output(), saved.diagnostic(), frames);
                ComputationMachine.restore(installed, differentSource); assertTrue(visits.get() >= 16, "Equal new source identity cannot inherit a proof"); visits.set(0);
            }
            ComputationMachine.restore(installed, saved); visits.set(0); // Rebind the genuine proof to the original source first.
            var changed = new DataValue.RecordValue(state.progress().fields());
            ComputationMachine.restore(installed, withState(saved, new MachineCheckpoint.ValueEqualityState(changed, state.continuation())));
            assertTrue(visits.get() >= 16, "Even an internal proof reference cannot authenticate a different progress identity");
            assertEquals(1, MachineCheckpoint.ValueEqualityState.class.getConstructors().length);
        } finally { DistinctTask.validationObserver.remove(); }
    }
    @Test void actualBusyAndBufferedAdmissionsDoNotReplayLongValueEqualityComparison() {
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
        assertEquals(9, idle.write().getInt(FlowBlockNbtCodec.MARKER));
        assertEquals(idle.snapshot().orElseThrow(), FlowBlockNbtCodec.decode(idle.write(), LIMITS.archive()));
        var host = hostAtOffset(inputs(new DataValue.TextValue("x".repeat(128)), new DataValue.TextValue("x".repeat(128))), "TEXT");
        var raw = host.write(); assertEquals(9, raw.getInt(FlowBlockNbtCodec.MARKER));
        assertEquals(host.snapshot().orElseThrow(), FlowBlockNbtCodec.decode(raw, LIMITS.archive()));
        for (int version = 1; version <= 8; version++) { var old = raw.copy(); old.putInt(FlowBlockNbtCodec.MARKER, version); assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(old, LIMITS.archive())); }
        var normal = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), new FlowProgram(Map.of(), List.of()), new DataValue.RecordValue(Map.of()), LIMITS);
        assertEquals(3, normal.write().getInt(FlowBlockNbtCodec.MARKER));
        var owner = new ComputerBlockHost(() -> {}); owner.read(raw); owner.tick(LIMITS, true); assertTrue(owner.loaded()); assertTrue(owner.sequentialMode()); assertEquals(host.snapshot().orElseThrow(), owner.flowSnapshot().orElseThrow());
    }
    @Test void pausedComputerColdLoadValidatesOnceThenTicksAndStepsDoNotReplay() {
        var input = inputs(new DataValue.TextValue("x".repeat(128)), new DataValue.TextValue("x".repeat(128)));
        var host = new ComputerBlockHost(() -> {}); host.install(archive(), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true); host.begin(input, LIMITS, true);
        while (!atOffset(host.snapshot().orElseThrow().machine(), "TEXT", 16)) host.tick(LIMITS, true);
        host.setPaused(true, LIMITS, true); var loaded = new ComputerBlockHost(() -> {}); loaded.read(host.write());
        var visits = new AtomicLong(); DistinctTask.validationObserver.set(visits::addAndGet);
        try {
            loaded.tick(LIMITS, true); assertTrue(visits.get() >= 16); visits.set(0);
            var before = loaded.snapshot().orElseThrow();
            for (int i = 0; i < 4; i++) loaded.tick(LIMITS, true);
            assertEquals(before, loaded.snapshot().orElseThrow()); assertEquals(0, visits.get());
            loaded.step(LIMITS, true); assertEquals(before.machine().workUnits() + 1, loaded.snapshot().orElseThrow().machine().workUnits());
            assertEquals(0, visits.get());
        } finally { DistinctTask.validationObserver.remove(); }
    }
    @Test void computerArchivePublicationFailureRollsBackPartialComparisonAndRam() {
        String key = "z".repeat(128); var named = new DataValue.RecordValue(Map.of(key, ListEditTest.number(1)));
        var host = new ComputerBlockHost(() -> {}); host.install(archive(), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        host.begin(inputs(named, new DataValue.RecordValue(named.fields())), LIMITS, true);
        while (!atOffset(host.snapshot().orElseThrow().machine(), "KEY", key.length())) host.tick(LIMITS, true);
        var before = host.snapshot().orElseThrow(); var raw = host.write();
        long units = ValueNbtCodec.decode((net.minecraft.nbt.CompoundTag)raw, LIMITS.archive()).storageUnits();
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(LIMITS.archive().maxNodes(), LIMITS.archive().maxStringChars(), units)));
        assertThrows(ComputerBlockHost.HostException.class, () -> host.step(tight, true));
        assertEquals(raw, host.write()); assertEquals(before, host.snapshot().orElseThrow());
        assertFalse(host.loaded()); assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
        host.setPaused(true, LIMITS, true); var paused = host.snapshot().orElseThrow();
        var visits = new AtomicLong(); DistinctTask.validationObserver.set(visits::addAndGet);
        try { host.step(LIMITS, true); assertEquals(0, visits.get()); assertEquals(paused.machine().workUnits() + 1, host.snapshot().orElseThrow().machine().workUnits()); }
        finally { DistinctTask.validationObserver.remove(); }
        assertTrue(atOffset(before.machine(), "KEY", key.length())); assertEquals(before.machine().memory(), host.snapshot().orElseThrow().machine().memory());
    }
    @Test void realComputerAndFlowMemoryFailuresRetainPreviousCommittedData() {
        var nested = new DataValue.ListValue(List.of(ListEditTest.number(1)));
        var input = inputs(nested, new DataValue.ListValue(nested.values()));
        var definition = new ProgramDefinition(archive().program().graph(),
            Map.of("log", new ProgramDefinition.Cell(DataType.Scalar.BOOLEAN, new DataValue.BooleanValue(true))), List.of(new ProgramDefinition.Write("log", "result")));
        var program = new ProgramArchive(definition, Map.of());
        var direct = new ComputationMachine(program.compile(LIMITS.hardware())); direct.begin(input);
        while (direct.checkpoint().frames().stream().noneMatch(MachineCheckpoint.ValueEqualityState.class::isInstance)) direct.tick();
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, direct.checkpoint().arenaUnits(), 1), LIMITS.archive()));
        var computer = new ComputerBlockHost(() -> {}); computer.install(program, ComputerRuntime.MemoryPolicy.RESET, tight, true); computer.begin(input, tight, true);
        for (int i = 0; computer.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) { assertTrue(i < 100); computer.tick(tight, true); }
        var failed = computer.snapshot().orElseThrow().machine(); assertEquals(ComputationMachine.Status.FAILED, failed.status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, failed.diagnostic().orElseThrow().code());
        assertEquals(new DataValue.BooleanValue(true), failed.memory().field("log")); assertTrue(failed.output().isEmpty());
        var flow = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), flow(), input, LIMITS);
        while (flow.snapshot().orElseThrow().state().expression().map(machine -> machine.frames().stream().anyMatch(MachineCheckpoint.ValueEqualityState.class::isInstance)).orElse(false) == false) flow.step();
        var active = flow.snapshot().orElseThrow().state();
        var flowTight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, input.storageUnits() + flow.snapshot().orElseThrow().control().storageUnits() + active.controlUnits() + active.expression().orElseThrow().arenaUnits(), 1), LIMITS.archive()));
        assertTrue(flow.synchronize(flowTight, true));
        for (int i = 0; flow.snapshot().orElseThrow().state().status() != FlowRuntime.Status.FAILED; i++) { assertTrue(i < 100); flow.step(); }
        var flowFailed = flow.snapshot().orElseThrow().state(); assertEquals(ComputationException.Code.MEMORY_LIMIT, flowFailed.diagnostic().orElseThrow().code());
        assertEquals(input, flowFailed.locals()); assertTrue(flowFailed.expression().isEmpty());
    }
    static MachineCheckpoint withState(MachineCheckpoint saved, MachineCheckpoint.ValueEqualityState state) {
        var frames = new ArrayList<>(saved.frames()); frames.set(frames.size() - 1, state);
        return new MachineCheckpoint(saved.memory(), saved.status(), saved.paused(), saved.workUnits(), saved.arenaUnits(), saved.output(), saved.diagnostic(), frames);
    }
}
