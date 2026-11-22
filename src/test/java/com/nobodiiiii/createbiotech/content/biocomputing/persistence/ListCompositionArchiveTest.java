package com.nobodiiiii.createbiotech.content.biocomputing.persistence;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ListCompositionArchiveTest {
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
    private static final ResourceLimits HARDWARE = new ResourceLimits(100, 100_000, 1);
    private static ComputationGraph graph() {
        return new ComputationGraph(Map.of(), Map.of(
            "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "list", new ComputationGraph.MakeList(List.of("one", "one")),
            "join", new ComputationGraph.ConcatLists("list", "list")), Map.of("result", "join"));
    }
    private static ProgramArchive archive() { return new ProgramArchive(new ProgramDefinition(graph(), Map.of(), List.of()), Map.of()); }

    @Test void programAndStandaloneModuleKeepNodeOrderAndLayoutsWithoutInstalling() {
        var archive = archive();
        assertEquals(archive, ProgramEditorWire.decode(ProgramEditorWire.encode(archive, ARCHIVE), ARCHIVE));
        var doc = new ProgramDocument(archive, Map.of("root", Map.of("join", new ProgramDocument.Position(31, -12))));
        assertEquals(doc, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(doc)));
        var key = new ModuleDefinition.Key("combine", 3);
        var module = new SubgraphDocument(key, Map.of(key, new ModuleDefinition(key, graph())),
            Map.of(ProgramDocument.graphId(key), Map.of("list", new ProgramDocument.Position(120, 85))));
        assertEquals(module, SubgraphDocumentCodec.decode(SubgraphDocumentCodec.encode(module)));
        var wire = ProgramArchiveCodec.encode(archive);
        assertEquals(new DataValue.IntegerValue(12), wire.field("version"));
        for (int version = 1; version <= 11; version++) {
            int v = version;
            assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(with(wire, "version", new DataValue.IntegerValue(v))));
            var plain = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of()), Map.of());
            assertDoesNotThrow(() -> ProgramArchiveCodec.decode(with(ProgramArchiveCodec.encode(plain), "version", new DataValue.IntegerValue(v))));
        }
        assertThrows(ArchiveException.class, () -> SubgraphDocumentCodec.decode(with(SubgraphDocumentCodec.encode(module), "graph_version", new DataValue.IntegerValue(9))));
    }

    @Test void oldProgramTenAndElevenCannotSmuggleListNodesThroughModulesOrEventEntries() {
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var key = new ModuleDefinition.Key("list", 1);
        for (int version : List.of(10, 11)) {
            for (var candidate : List.of(
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of(key, new ModuleDefinition(key, graph()))),
                new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
                    Map.of("message", new ProgramDefinition.Entry(graph(), List.of(), List.of()))), Map.of()))) {
                var encoded = ProgramArchiveCodec.encode(candidate);
                assertEquals(candidate, ProgramArchiveCodec.decode(encoded));
                assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(with(encoded, "version", new DataValue.IntegerValue(version))));
            }
        }
    }

    @Test void pausedRealHostRoundTripsNonzeroListProgressAndKeepsWorkUntilExplicitStep() {
        var hostLimits = ParserHardwareFixture.withIsa1a(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(HARDWARE, ARCHIVE));
        var host = new ComputerBlockHost(() -> {});
        host.install(archive(), ComputerRuntime.MemoryPolicy.RESET, hostLimits, true);
        host.begin(new DataValue.RecordValue(Map.of()), hostLimits, true);
        for (int guard = 0; ; guard++) {
            assertTrue(guard < 50);
            var snapshot = host.snapshot().orElseThrow();
            if (snapshot.machine().frames().stream().anyMatch(frame -> frame instanceof MachineCheckpoint.ListState list && list.index() == 1)) break;
            host.tick(hostLimits, true);
        }
        var before = host.snapshot().orElseThrow();
        var restored = new ComputerBlockHost(() -> {}); restored.read(host.write());
        assertTrue(restored.snapshot().isEmpty());
        assertEquals(before, restored.observe(hostLimits, true));
        assertEquals(before, restored.observe(hostLimits, true));
        var paused = ComputerRuntime.restore(before, HARDWARE); paused.setPaused(true);
        var nbt = ComputerNbtCodec.encode(paused.snapshot(), ARCHIVE);
        var decoded = ComputerNbtCodec.decode(nbt, ARCHIVE);
        var runtime = ComputerRuntime.restore(decoded, HARDWARE);
        assertEquals(paused.snapshot(), runtime.snapshot());
        runtime.load();
        runtime.tick(); assertEquals(decoded, runtime.snapshot());
        for (int guard = 0; runtime.snapshot().machine().status() == ComputationMachine.Status.RUNNING; guard++) {
            assertTrue(guard < 100); runtime.step();
        }
        assertEquals(new DataValue.ListValue(Collections.nCopies(4, new DataValue.IntegerValue(1))),
            runtime.snapshot().machine().output().orElseThrow().field("result"));
        var data = (DataValue.RecordValue) ValueNbtCodec.decode(nbt, ARCHIVE);
        assertEquals(new DataValue.IntegerValue(10), data.field("version"));
        assertThrows(ArchiveException.class, () -> ComputerNbtCodec.decode(ValueNbtCodec.encode(with(data, "version", new DataValue.IntegerValue(9)), ARCHIVE), ARCHIVE));
    }

    private static DataValue.RecordValue with(DataValue.RecordValue value, String key, DataValue replacement) {
        var fields = new LinkedHashMap<>(value.fields()); fields.put(key, replacement); return new DataValue.RecordValue(fields);
    }
}
