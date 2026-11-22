package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValueEqualityArchiveTest {
    static final ArchiveLimits BOUNDS = new ArchiveLimits(100000, 1000000, 10000000);
    static ComputationGraph graph() {
        return new ComputationGraph(Map.of("left", DataType.Scalar.ANY, "right", DataType.Scalar.ANY),
            Map.of("left", new ComputationGraph.Input("left"), "right", new ComputationGraph.Input("right"), "same", new ComputationGraph.ValueEquals("left", "right")),
            Map.of("result", "same", "left", "left", "right", "right"));
    }
    static DataValue.RecordValue replace(DataValue.RecordValue record, String name, DataValue value) {
        var fields = new LinkedHashMap<>(record.fields()); fields.put(name, value); return new DataValue.RecordValue(fields);
    }
    @Test void capabilitySchemasCoverRootEventFixedModuleAndSubgraphWithoutAdvancingOldGraphs() {
        var graph = graph(); var empty = new ComputationGraph(Map.of(), Map.of(), Map.of()); var key = new ModuleDefinition.Key("unique", 4);
        for (var archive : List.of(new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of()),
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(), Map.of("unique", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of()),
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of(key, new ModuleDefinition(key, graph))))) {
            var encoded = ProgramArchiveCodec.encode(archive); assertEquals(new DataValue.IntegerValue(17), encoded.field("version"));
            assertEquals(archive, ProgramArchiveCodec.decode(encoded));
            for (int v = 3; v <= 16; v++) {
                var older = replace(encoded, "version", new DataValue.IntegerValue(v));
                assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(older));
            }
            var document = new ProgramDocument(archive, Map.of()); assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        }
        for (int v = 1; v <= 14; v++) { int version = v; assertThrows(ArchiveException.class, () -> GraphArchiveCodec.decode(GraphArchiveCodec.encode(graph), version)); }
        var doc = new SubgraphDocument(key, Map.of(key, new ModuleDefinition(key, graph)), Map.of());
        var encoded = SubgraphDocumentCodec.encode(doc); assertEquals(new DataValue.IntegerValue(15), encoded.field("graph_version"));
        assertEquals(doc, SubgraphDocumentCodec.decode(encoded));
        assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(replace(encoded, "graph_version", new DataValue.IntegerValue(14))));
        assertEquals(new DataValue.IntegerValue(12), ProgramArchiveCodec.encode(new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of())).field("version"));
    }
    @Test void computerAndFlowPersistEveryValueEqualityStepAndRejectOlderEmbeddedFrameSchemas() throws Exception {
        var graph = graph(); var archive = new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
        var hardware = new ResourceLimits(100, 100000, 1); var source = new DataValue.ListValue(List.of(new DataValue.IntegerValue(3), new DataValue.IntegerValue(1), new DataValue.IntegerValue(3)));
        var inputs = new DataValue.RecordValue(Map.of("left", source, "right", new DataValue.ListValue(source.values())));
        var computer = new ComputerRuntime(UUID.randomUUID(), archive, hardware); computer.begin(inputs); computer.setPaused(true);
        boolean frameSeen = false;
        for (int i = 0; computer.report().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); var before = computer.snapshot(); var encoded = (DataValue.RecordValue)ComputerNbtCodec.value(before);
            assertEquals(new DataValue.IntegerValue(12), encoded.field("version"));
            assertThrows(ArchiveException.class, () -> ComputerNbtCodec.readValue(replace(encoded, "version", new DataValue.IntegerValue(11))));
            var bytes = new java.io.ByteArrayOutputStream(); net.minecraft.nbt.NbtIo.write(ComputerNbtCodec.encode(before, BOUNDS), new java.io.DataOutputStream(bytes));
            var decoded = ComputerNbtCodec.decode(net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))), BOUNDS);
            assertEquals(before, decoded); computer = ComputerRuntime.restore(decoded, hardware); computer.load();
            assertEquals(before, computer.snapshot());
            if (before.machine().frames().stream().anyMatch(MachineCheckpoint.ValueEqualityState.class::isInstance)) {
                frameSeen = true;
                var embedded = ComputerNbtCodec.pureMachine(before.machine());
                assertThrows(ArchiveException.class, () -> ComputerNbtCodec.readPureMachine(embedded, false));
                assertThrows(ArchiveException.class, () -> ComputerNbtCodec.readPureMachine(embedded, true));
                assertEquals(before.machine(), ComputerNbtCodec.readPureMachine(embedded, 10));
            }
            computer.step();
        }
        assertTrue(frameSeen); assertEquals(ComputationMachine.Status.SUCCEEDED, computer.report().status());
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var expressions = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(), Map.of("unique", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(graph.inputs(), List.of(new FlowProgram.Evaluate("uniqueList", "unique")), expressions);
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, inputs, hardware); frameSeen = false;
        for (int i = 0; runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) {
            assertTrue(i < 100); runtime.advance(1);
            var before = new FlowNbtCodec.Snapshot(program, runtime.checkpoint()); var encoded = (DataValue.RecordValue)FlowNbtCodec.value(before);
            assertEquals(new DataValue.IntegerValue(10), encoded.field("version"));
            assertThrows(ArchiveException.class, () -> FlowNbtCodec.readValue(replace(encoded, "version", new DataValue.IntegerValue(9))));
            var decoded = FlowNbtCodec.decode(FlowNbtCodec.encode(before, BOUNDS), BOUNDS); assertEquals(before, decoded);
            frameSeen |= before.state().expression().map(e -> e.frames().stream().anyMatch(MachineCheckpoint.ValueEqualityState.class::isInstance)).orElse(false);
            runtime = FlowRuntime.restore(decoded.program(), decoded.state(), hardware); assertEquals(0, runtime.advance(1)); runtime.load();
        }
        assertTrue(frameSeen); assertEquals(source, runtime.checkpoint().locals().field("left"));
        assertEquals(new DataValue.BooleanValue(true), runtime.checkpoint().locals().field("result"));
        var oldArchive = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of());
        assertEquals(new DataValue.IntegerValue(10), ((DataValue.RecordValue)ComputerNbtCodec.value(new ComputerRuntime(UUID.randomUUID(), oldArchive, hardware).snapshot())).field("version"));
        var oldProgram = new FlowProgram(Map.of(), List.of(), oldArchive);
        assertEquals(new DataValue.IntegerValue(1), ((DataValue.RecordValue)FlowNbtCodec.value(new FlowNbtCodec.Snapshot(oldProgram, new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), oldProgram, new DataValue.RecordValue(Map.of()), hardware).checkpoint()))).field("version"));
    }    @Test void pendingProgramsAndHiddenFutureFramesRequireExactComputerAndFlowCapabilities() {
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var old = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of());
        var equality = new ProgramArchive(new ProgramDefinition(graph(), Map.of(), List.of()), Map.of());
        var limits = new ResourceLimits(100, 100000, 1); var machine = new ComputationMachine(equality.compile(limits));
        var text = new DataValue.TextValue("long text"); machine.begin(new DataValue.RecordValue(Map.of("left", text, "right", new DataValue.TextValue(text.value()))));
        while (machine.checkpoint().frames().stream().noneMatch(MachineCheckpoint.ValueEqualityState.class::isInstance)) machine.tick();
        var onlyFrame = new ComputerRuntime.Snapshot(UUID.randomUUID(), old, machine.checkpoint(), Optional.empty(), Optional.empty());
        var frameData = (DataValue.RecordValue)ComputerNbtCodec.value(onlyFrame);
        assertEquals(new DataValue.IntegerValue(12), frameData.field("version"));
        assertThrows(ArchiveException.class, () -> ComputerNbtCodec.readValue(replace(frameData, "version", new DataValue.IntegerValue(11))));
        var pending = new ComputerRuntime.Snapshot(UUID.randomUUID(), old, new ComputationMachine(old.compile(limits)).checkpoint(),
            Optional.of(new ComputerRuntime.PendingInstallation(equality, ComputerRuntime.MemoryPolicy.RESET)), Optional.empty());
        var pendingData = (DataValue.RecordValue)ComputerNbtCodec.value(pending);
        assertEquals(new DataValue.IntegerValue(12), pendingData.field("version"));
        assertEquals(pending, ComputerNbtCodec.decode(ComputerNbtCodec.encode(pending, BOUNDS), BOUNDS));
        assertThrows(ArchiveException.class, () -> ComputerNbtCodec.readValue(replace(pendingData, "version", new DataValue.IntegerValue(11))));
        var oldFlow = new FlowProgram(Map.of(), List.of());
        var control = new FlowRuntime.Checkpoint(UUID.randomUUID(), UUID.randomUUID(), 0, FlowRuntime.Status.COMPUTING,
            new DataValue.RecordValue(Map.of()), machine.report().workUnits(), true, Optional.of(machine.checkpoint()), Optional.empty());
        var flow = (DataValue.RecordValue)FlowNbtCodec.value(new FlowNbtCodec.Snapshot(oldFlow, control));
        assertEquals(new DataValue.IntegerValue(10), flow.field("version"));
        assertThrows(ArchiveException.class, () -> FlowNbtCodec.readValue(replace(flow, "version", new DataValue.IntegerValue(9))));
    }

}
