package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramCompilerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.NodePorts;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ProgramArchiveCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexNodeTest {
	private static final ArchiveLimits ARCHIVE_LIMITS = new ArchiveLimits(1_000, 100_000, 100_000);

	@Test void selectsListItemWithoutChangingSourceAndAcceptsErrorValuesAsData() {
		var error = new DataValue.ErrorValue("sample", "kept as data");
		var source = new DataValue.ListValue(List.of(integer(4), error));
		var machine = run(new ComputationMachine(compile(graph(ANY, 1))), record(Map.of("items", source)));
		assertEquals(error, machine.report().output().orElseThrow().field("item"));
		assertEquals(List.of(integer(4), error), source.values());
		assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
	}

	@Test void validatesIndexAndReportsRuntimeBoundsAndDynamicSourceType() {
		assertCode(INVALID_GRAPH, () -> new ComputationGraph.Index("items", -1));
		assertCode(INVALID_GRAPH, () -> new ComputationGraph.Index(" ", 0));
		var outOfBounds = run(new ComputationMachine(compile(graph(new DataType.ListType(INTEGER), 2))),
			record(Map.of("items", numbers(8))));
		assertEquals(INDEX_OUT_OF_BOUNDS, outOfBounds.report().diagnostic().orElseThrow().code());
		var wrongDynamicType = run(new ComputationMachine(compile(graph(ANY, 0))),
			record(Map.of("items", new DataValue.TextValue("not a list"))));
		assertEquals(TYPE_MISMATCH, wrongDynamicType.report().diagnostic().orElseThrow().code());
	}

	@Test void compilerInfersListElementOrAnyAndRejectsKnownNonList() {
		assertEquals(TEXT, compile(graph(new DataType.ListType(TEXT), 0)).root().nodeTypes().get("pick"));
		assertEquals(ANY, compile(graph(ANY, 0)).root().nodeTypes().get("pick"));
		assertCode(TYPE_MISMATCH, () -> compile(graph(INTEGER, 0)));
	}

	@Test void archiveAndEditorWireRoundTripAndRejectNarrowingOverflow() {
		var archive = new ProgramArchive(program(graph(new DataType.ListType(INTEGER), 1)), Map.of());
		assertEquals(archive, ProgramArchiveCodec.decode(ProgramArchiveCodec.encode(archive)));
		assertEquals(archive, ProgramEditorWire.decode(ProgramEditorWire.encode(archive, ARCHIVE_LIMITS), ARCHIVE_LIMITS));

		var encoded = ProgramArchiveCodec.encode(archive);
		assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(withArchivedIndex(encoded, -1)));
		assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(withArchivedIndex(encoded, (long) Integer.MAX_VALUE + 1)));
	}

	private static DataValue.RecordValue withArchivedIndex(DataValue.RecordValue encoded, long index) {
		var graph = (DataValue.RecordValue) encoded.field("graph");
		var nodes = (DataValue.RecordValue) graph.field("nodes");
		var node = new LinkedHashMap<>(((DataValue.RecordValue) nodes.field("pick")).fields());
		node.put("index", integer(index));
		var changedNodes = new LinkedHashMap<>(nodes.fields());
		changedNodes.put("pick", new DataValue.RecordValue(node));
		var changedGraph = new LinkedHashMap<>(graph.fields());
		changedGraph.put("nodes", new DataValue.RecordValue(changedNodes));
		var changedArchive = new LinkedHashMap<>(encoded.fields());
		changedArchive.put("graph", new DataValue.RecordValue(changedGraph));
		return new DataValue.RecordValue(changedArchive);
	}

	@Test void checkpointBeforeIndexResumesThroughTheSameEvaluatorAndPreservesBudgetAccounting() {
		var installation = ProgramCompiler.compile(program(graph(new DataType.ListType(INTEGER), 1)), Map.of(),
			new ResourceLimits(10, 1_000, 1));
		var machine = new ComputationMachine(installation);
		machine.begin(record(Map.of("items", numbers(3, 7))));
		machine.step(); // input is complete; index remains pending
		var saved = machine.checkpoint();
		var restored = ComputationMachine.restore(installation, saved);
		assertEquals(saved, restored.checkpoint());
		restored.step();
		assertEquals(saved.workUnits() + 1, restored.report().workUnits());
		assertTrue(restored.report().peakMemoryUnits() > saved.arenaUnits());
		while (restored.report().status() == ComputationMachine.Status.RUNNING) restored.step();
		assertEquals(integer(7), restored.report().output().orElseThrow().field("item"));
	}

	@Test void nodePortsExposeAndRebindSourceOnly() {
		var node = new ComputationGraph.Index("before", 3);
		assertEquals(Map.of("source", "before"), NodePorts.bindings(node));
		assertEquals(new ComputationGraph.Index("after", 3), NodePorts.bind(node, Map.of("source", "after")));
	}

	private static ComputationGraph graph(DataType inputType, int index) {
		return new ComputationGraph(Map.of("items", inputType), Map.of(
			"input", new ComputationGraph.Input("items"),
			"pick", new ComputationGraph.Index("input", index)), Map.of("item", "pick"));
	}

	private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
	private static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
	private static DataValue.ListValue numbers(long... values) {
		return new DataValue.ListValue(java.util.Arrays.stream(values).mapToObj(IndexNodeTest::integer).map(DataValue.class::cast).toList());
	}
	private static ComputationMachine run(ComputationMachine machine, DataValue.RecordValue input) {
		machine.begin(input);
		while (machine.report().status() == ComputationMachine.Status.RUNNING) machine.tick();
		return machine;
	}
}
