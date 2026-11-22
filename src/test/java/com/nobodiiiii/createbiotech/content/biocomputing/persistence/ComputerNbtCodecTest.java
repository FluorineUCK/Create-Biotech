package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerNbtCodecTest {
	private static final ResourceLimits HARDWARE = new ResourceLimits(1000, 1_000_000, 1);
	private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());

	@Test void nestedCallsMapFilterReturnsAndPartialCommitSurviveRealNbtIoAtEveryStep() throws IOException {
		var computer = new ComputerRuntime(UUID.randomUUID(), iterations(), HARDWARE);
		computer.begin(EMPTY);
		var seen = new HashSet<String>();
		for (int i = 0; computer.report().status() == ComputationMachine.Status.RUNNING; i++) {
			assertTrue(i < 1000);
			for (var frame : computer.snapshot().machine().frames()) {
				seen.add(frame.getClass().getSimpleName());
				if (frame instanceof MachineCheckpoint.LoopState loop && loop.returned().isPresent()) seen.add("returned");
				if (frame instanceof MachineCheckpoint.CommitState commit && commit.cursor() > 0) seen.add("preparedWrite");
				if (frame instanceof MachineCheckpoint.GraphState graph && graph.module().isPresent()) seen.add(graph.module().get().id());
			}
			var restored = ComputerRuntime.restore(roundTrip(computer.snapshot()), HARDWARE);
			assertEquals(computer.snapshot(), restored.snapshot());
			restored.load(); computer.step(); restored.step(); assertEquals(computer.snapshot(), restored.snapshot());
			computer = restored;
		}
		assertEquals(ComputationMachine.Status.SUCCEEDED, computer.report().status());
		assertEquals(Set.of("GraphState", "LoopState", "CommitState", "returned", "preparedWrite", "double", "below"), seen);
		assertEquals(new DataValue.ListValue(List.of(integer(2), integer(4))), computer.memory().field("values"));
		assertEquals(computer.snapshot(), roundTrip(computer.snapshot()));
	}

	@Test void failedEventAndInstallationDiagnosticsSurviveSaveLoad() throws IOException {
		var graph = new ComputationGraph(Map.of("input", ANY), Map.of("input", new ComputationGraph.Input("input"),
			"field", new ComputationGraph.Field("input", "missing")), Map.of("out", "field"));
		var program = new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
		var computer = new ComputerRuntime(UUID.randomUUID(), program, HARDWARE);
		computer.begin(new DataValue.RecordValue(Map.of("input", EMPTY)));
		for (int i = 0; i < 10 && computer.report().status() == ComputationMachine.Status.RUNNING; i++) computer.step();
		assertEquals(ComputationMachine.Status.FAILED, computer.report().status());
		assertEquals(ComputationException.Code.MISSING_FIELD, computer.report().diagnostic().orElseThrow().code());
		var invalid = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of("out", "missing")), Map.of(), List.of()), Map.of());
		assertThrows(ComputationException.class, () -> computer.requestInstall(invalid, ComputerRuntime.MemoryPolicy.RESET));
		var restored = ComputerRuntime.restore(roundTrip(computer.snapshot()), HARDWARE);
		assertEquals(computer.snapshot(), restored.snapshot());
	}

	@Test void duplicatePinnedModulesAndUnknownSchemaVersionsAreRejected() {
		var archive = ProgramArchiveCodec.encode(iterations());
		var fields = new LinkedHashMap<>(archive.fields());
		var modules = new ArrayList<>(((DataValue.ListValue) fields.get("modules")).values());
		modules.add(modules.getFirst()); fields.put("modules", new DataValue.ListValue(modules));
		assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(new DataValue.RecordValue(fields)));
        fields.put("version", integer(99));
		assertEquals(ArchiveException.Code.UNSUPPORTED_VERSION,
			assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(new DataValue.RecordValue(fields))).code());
	}

	private static ComputerRuntime.Snapshot roundTrip(ComputerRuntime.Snapshot snapshot) throws IOException {
		var bytes = new ByteArrayOutputStream();
		net.minecraft.nbt.NbtIo.write(ComputerNbtCodec.encode(snapshot, ValueNbtCodecTest.LIMITS), new DataOutputStream(bytes));
		return ComputerNbtCodec.decode(net.minecraft.nbt.NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))), ValueNbtCodecTest.LIMITS);
	}
	private static DataValue.IntegerValue integer(long n) { return new DataValue.IntegerValue(n); }
	private static ProgramArchive iterations() {
		var doubleKey = new ModuleDefinition.Key("double", 7); var belowKey = new ModuleDefinition.Key("below", 3);
		var doubleGraph = new ComputationGraph(Map.of("item", INTEGER), Map.of("x", new ComputationGraph.Input("item"),
			"sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "x", "x")), Map.of("result", "sum"));
		var belowGraph = new ComputationGraph(Map.of("item", INTEGER), Map.of("x", new ComputationGraph.Input("item"),
			"call", new ComputationGraph.Call(doubleKey, Map.of("item", "x")), "double", new ComputationGraph.Field("call", "result"),
			"limit", new ComputationGraph.Constant(integer(5)), "keep", new ComputationGraph.Binary(ComputationGraph.BinaryOp.LESS_INTEGER, "double", "limit")), Map.of("keep", "keep"));
		var graph = new ComputationGraph(Map.of(), Map.of("input", new ComputationGraph.Constant(new DataValue.ListValue(List.of(integer(1), integer(2), integer(3)))),
			"filter", new ComputationGraph.Iterate(ComputationGraph.Iteration.FILTER, "input", belowKey, "item", Map.of(), "keep"),
			"map", new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP, "filter", doubleKey, "item", Map.of(), "result")), Map.of("values", "map"));
		return new ProgramArchive(new ProgramDefinition(graph, Map.of("values", new ProgramDefinition.Cell(new DataType.ListType(INTEGER), new DataValue.ListValue(List.of()))),
			List.of(new ProgramDefinition.Write("values", "values"))), Map.of(doubleKey, new ModuleDefinition(doubleKey, doubleGraph), belowKey, new ModuleDefinition(belowKey, belowGraph)));
	}
}
