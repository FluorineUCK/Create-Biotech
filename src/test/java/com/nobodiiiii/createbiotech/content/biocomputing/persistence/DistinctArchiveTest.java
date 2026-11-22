package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DistinctArchiveTest {
    static final ArchiveLimits BOUNDS = new ArchiveLimits(100000, 1000000, 10000000);
    static ComputationGraph graph() {
        return new ComputationGraph(Map.of("source", new DataType.ListType(DataType.Scalar.INTEGER)),
            Map.of("source", new ComputationGraph.Input("source"), "unique", new ComputationGraph.Distinct("source")), Map.of("result", "unique", "original", "source"));
    }
    static DataValue.RecordValue replace(DataValue.RecordValue record, String name, DataValue value) {
        var fields = new LinkedHashMap<>(record.fields()); fields.put(name, value); return new DataValue.RecordValue(fields);
    }
    @Test void capabilitySchemasCoverRootEventFixedModuleAndSubgraphWithoutAdvancingOldGraphs() {
        var graph = graph(); var empty = new ComputationGraph(Map.of(), Map.of(), Map.of()); var key = new ModuleDefinition.Key("unique", 4);
        for (var archive : List.of(new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of()),
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(), Map.of("unique", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of()),
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of(key, new ModuleDefinition(key, graph))))) {
            var encoded = ProgramArchiveCodec.encode(archive); assertEquals(new DataValue.IntegerValue(16), encoded.field("version"));
            assertEquals(archive, ProgramArchiveCodec.decode(encoded));
            for (int v = 3; v <= 15; v++) {
                var older = replace(encoded, "version", new DataValue.IntegerValue(v));
                assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(older));
            }
            var document = new ProgramDocument(archive, Map.of()); assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        }
        for (int v = 1; v <= 13; v++) { int version = v; assertThrows(ArchiveException.class, () -> GraphArchiveCodec.decode(GraphArchiveCodec.encode(graph), version)); }
        var doc = new SubgraphDocument(key, Map.of(key, new ModuleDefinition(key, graph)), Map.of());
        var encoded = SubgraphDocumentCodec.encode(doc); assertEquals(new DataValue.IntegerValue(14), encoded.field("graph_version"));
        assertEquals(doc, SubgraphDocumentCodec.decode(encoded));
        assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(replace(encoded, "graph_version", new DataValue.IntegerValue(13))));
        assertEquals(new DataValue.IntegerValue(12), ProgramArchiveCodec.encode(new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of())).field("version"));
    }
    @Test void computerAndFlowPersistEveryDistinctStepAndRejectOlderEmbeddedFrameSchemas() throws Exception {
        var graph = graph(); var archive = new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
        var hardware = new ResourceLimits(100, 100000, 1); var source = new DataValue.ListValue(List.of(new DataValue.IntegerValue(3), new DataValue.IntegerValue(1), new DataValue.IntegerValue(3)));
        var inputs = new DataValue.RecordValue(Map.of("source", source));
        var computer = new ComputerRuntime(UUID.randomUUID(), archive, hardware); computer.begin(inputs); computer.setPaused(true);
        boolean frameSeen = false;
        for (int i = 0; computer.report().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); var before = computer.snapshot(); var encoded = (DataValue.RecordValue)ComputerNbtCodec.value(before);
            assertEquals(new DataValue.IntegerValue(11), encoded.field("version"));
            assertThrows(ArchiveException.class, () -> ComputerNbtCodec.readValue(replace(encoded, "version", new DataValue.IntegerValue(10))));
            var bytes = new java.io.ByteArrayOutputStream(); net.minecraft.nbt.NbtIo.write(ComputerNbtCodec.encode(before, BOUNDS), new java.io.DataOutputStream(bytes));
            var decoded = ComputerNbtCodec.decode(net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))), BOUNDS);
            assertEquals(before, decoded); computer = ComputerRuntime.restore(decoded, hardware); computer.load();
            assertEquals(before, computer.snapshot());
            if (before.machine().frames().stream().anyMatch(MachineCheckpoint.DistinctState.class::isInstance)) {
                frameSeen = true;
                var embedded = ComputerNbtCodec.pureMachine(before.machine());
                assertThrows(ArchiveException.class, () -> ComputerNbtCodec.readPureMachine(embedded, false));
                assertEquals(before.machine(), ComputerNbtCodec.readPureMachine(embedded, true));
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
            assertEquals(new DataValue.IntegerValue(9), encoded.field("version"));
            assertThrows(ArchiveException.class, () -> FlowNbtCodec.readValue(replace(encoded, "version", new DataValue.IntegerValue(8))));
            var decoded = FlowNbtCodec.decode(FlowNbtCodec.encode(before, BOUNDS), BOUNDS); assertEquals(before, decoded);
            frameSeen |= before.state().expression().map(e -> e.frames().stream().anyMatch(MachineCheckpoint.DistinctState.class::isInstance)).orElse(false);
            runtime = FlowRuntime.restore(decoded.program(), decoded.state(), hardware); assertEquals(0, runtime.advance(1)); runtime.load();
        }
        assertTrue(frameSeen); assertEquals(source, runtime.checkpoint().locals().field("source"));
        assertEquals(new DataValue.ListValue(List.of(new DataValue.IntegerValue(3), new DataValue.IntegerValue(1))), runtime.checkpoint().locals().field("result"));
        var oldArchive = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of());
        assertEquals(new DataValue.IntegerValue(10), ((DataValue.RecordValue)ComputerNbtCodec.value(new ComputerRuntime(UUID.randomUUID(), oldArchive, hardware).snapshot())).field("version"));
        var oldProgram = new FlowProgram(Map.of(), List.of(), oldArchive);
        assertEquals(new DataValue.IntegerValue(1), ((DataValue.RecordValue)FlowNbtCodec.value(new FlowNbtCodec.Snapshot(oldProgram, new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), oldProgram, new DataValue.RecordValue(Map.of()), hardware).checkpoint()))).field("version"));
    }
}
