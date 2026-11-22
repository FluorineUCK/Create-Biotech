package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProgramArchiveCodecTest {
	@Test void currentVersionRoundTripsSendsAndRejectsUnknownVersion() {
		var graph = sendGraph();
		var archive = new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of(),
			List.of(new ProgramDefinition.Send("endpoint", "payload"))), Map.of());
		var encoded = ProgramArchiveCodec.encode(archive);
		assertEquals(new DataValue.IntegerValue(12), encoded.field("version"));
		assertEquals(archive, ProgramArchiveCodec.decode(encoded));
		var unknown = new LinkedHashMap<>(encoded.fields());
		unknown.put("version", new DataValue.IntegerValue(Integer.MAX_VALUE));
		assertEquals(ArchiveException.Code.UNSUPPORTED_VERSION,
			assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(new DataValue.RecordValue(unknown))).code());
	}

	@Test void versionOneWithoutSendFieldRemainsReadable() {
		var v2 = ProgramArchiveCodec.encode(new ProgramArchive(new ProgramDefinition(sendGraph(), Map.of(), List.of()), Map.of()));
		var fields = new LinkedHashMap<>(v2.fields());
		fields.put("version", new DataValue.IntegerValue(1));
		fields.remove("sends");
		assertTrue(ProgramArchiveCodec.decode(new DataValue.RecordValue(fields)).program().sends().isEmpty());
	}

	@Test void installedArchivePreservesSendsWhileDiscardingDisconnectedNodes() {
		var base = sendGraph();
		var nodes = new LinkedHashMap<>(base.nodes());
		nodes.put("unused", new ComputationGraph.Constant(new DataValue.IntegerValue(99)));
		var definition = new ProgramDefinition(new ComputationGraph(base.inputs(), nodes, base.outputs()), Map.of(), List.of(),
			List.of(new ProgramDefinition.Send("endpoint", "payload")));
		var installed = ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(100, 10_000, 1));
		var archived = ProgramArchive.from(installed);
		assertEquals(List.of(new ProgramDefinition.Send("endpoint", "payload")), archived.program().sends());
		assertFalse(archived.program().graph().nodes().containsKey("unused"));
	}

	private static ComputationGraph sendGraph() {
		return new ComputationGraph(Map.of(), Map.of(
			"endpoint", new ComputationGraph.Constant(new DataValue.TextValue("factory:input")),
			"payload", new ComputationGraph.Constant(new DataValue.RecordValue(Map.of("amount", new DataValue.IntegerValue(1))))),
			Map.of("endpoint", "endpoint", "payload", "payload"));
	}
	@Test void everyExistingNodeAndTypeRoundTrips() {
		var key = new ModuleDefinition.Key("user:temperature", 7);
		var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
		nodes.put("constant", new ComputationGraph.Constant(new DataValue.IntegerValue(1)));
		nodes.put("input", new ComputationGraph.Input("samples"));
		nodes.put("memory", new ComputationGraph.ReadMemory("stored"));
		for (var op : ComputationGraph.BinaryOp.values()) nodes.put(op.name(), new ComputationGraph.Binary(op, "constant", "memory"));
		nodes.put("field", new ComputationGraph.Field("memory", ""));
		nodes.put("record", new ComputationGraph.MakeRecord(Map.of("", "input")));
		nodes.put("call", new ComputationGraph.Call(key, Map.of("sample", "constant")));
		for (var mode : ComputationGraph.Iteration.values()) nodes.put(mode.name(), new ComputationGraph.Iterate(mode, "input", key, "sample", Map.of(), "value"));
		var types = new LinkedHashMap<String, DataType>();
		for (var scalar : DataType.Scalar.values()) types.put(scalar.name(), scalar);
		types.put("samples", new DataType.ListType(new DataType.OptionalType(new DataType.MapType(new DataType.RecordType(Map.of("", TEXT))))));
		var graph = new ComputationGraph(types, nodes, Map.of("result", "MAP"));
		var program = new ProgramDefinition(graph, Map.of("stored", new ProgramDefinition.Cell(ANY, DataValue.NullValue.INSTANCE)), List.of(new ProgramDefinition.Write("stored", "result")));
		var module = new ModuleDefinition(key, new ComputationGraph(Map.of("sample", INTEGER), Map.of("x", new ComputationGraph.Input("sample")), Map.of("value", "x")));
		var archive = new ProgramArchive(program, Map.of(key, module));
		assertEquals(archive, ProgramArchiveCodec.decode(ValueNbtCodec.decode(ValueNbtCodec.encode(
			ProgramArchiveCodec.encode(archive), ValueNbtCodecTest.LIMITS), ValueNbtCodecTest.LIMITS)));
	}

	@Test void installedArchiveContainsOnlyPinnedReachableModuleClosure() {
		var old = new ModuleDefinition.Key("identity", 1);
		var newer = new ModuleDefinition.Key("identity", 2);
		var body = new ComputationGraph(Map.of(), Map.of("c", new ComputationGraph.Constant(new DataValue.IntegerValue(3))), Map.of("value", "c"));
		var root = new ComputationGraph(Map.of(), Map.of("use", new ComputationGraph.Call(old, Map.of()), "unused", new ComputationGraph.Call(newer, Map.of())), Map.of("value", "use"));
		var installed = ProgramCompiler.compile(new ProgramDefinition(root, Map.of(), List.of()), Map.of(old, new ModuleDefinition(old, body), newer, new ModuleDefinition(newer, body)), new ResourceLimits(100, 10000, 1));
		var archive = ProgramArchive.from(installed);
		assertEquals(Set.of(old), archive.modules().keySet());
		assertEquals(Set.of("use"), archive.program().graph().nodes().keySet());
		var loaded = ProgramArchiveCodec.decode(ProgramArchiveCodec.encode(archive));
		assertEquals(installed.usage(), loaded.compile(new ResourceLimits(100, 10000, 1)).usage());
	}

	@Test void deeplyNestedTypesDoNotUseTheJavaCallStack() {
		DataType type = INTEGER;
		for (int i = 0; i < 10_000; i++) type = new DataType.ListType(type);
		var graph = new ComputationGraph(Map.of("deep", type), Map.of("input", new ComputationGraph.Input("deep")), Map.of("out", "input"));
		var archive = new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
		assertEquals(archive, ProgramArchiveCodec.decode(ProgramArchiveCodec.encode(archive)));
	}

	@Test void malformedArchiveIsExplicitlyRejected() {
		assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(new DataValue.RecordValue(Map.of())));
	}
}
