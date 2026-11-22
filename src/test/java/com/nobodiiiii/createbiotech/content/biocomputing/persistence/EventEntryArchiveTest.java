package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.INTEGER;
import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.obj;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventEntryArchiveTest {
	@Test
	void currentArchiveRoundTripsEntriesThroughValueNbtAndPreservesThemAfterSelection() {
		var root = graph(1);
		var message = graph(7);
		var definition = new ProgramDefinition(root, Map.of(), List.of(), List.of(), Map.of("message",
			new ProgramDefinition.Entry(message, List.of(), List.of())));
		var installed = ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(20, 100, 10));
		var archive = ProgramArchive.from(installed.select("message"));
		assertEquals(definition, archive.program());

		var encoded = ProgramArchiveCodec.encode(archive);
		assertEquals(new DataValue.IntegerValue(12), encoded.field("version"));
		var decodedValue = ValueNbtCodec.decode(ValueNbtCodec.encode(encoded, ValueNbtCodecTest.LIMITS), ValueNbtCodecTest.LIMITS);
		assertEquals(archive, ProgramArchiveCodec.decode(decodedValue));
	}

	@Test
	void archiveFourHasLiteralEntryShape() {
		var archive = new ProgramArchive(new ProgramDefinition(graph(1), Map.of(), List.of(), List.of(), Map.of("message",
			new ProgramDefinition.Entry(graph(7), List.of(), List.of()))), Map.of());
		var encoded = ProgramArchiveCodec.encode(archive);
		var expectedEntry = new DataValue.RecordValue(Map.of(
			"graph", graphArchive(7), "writes", new DataValue.ListValue(List.of()), "sends", new DataValue.ListValue(List.of())));
		assertEquals(new DataValue.RecordValue(Map.of("message", expectedEntry)), encoded.field("entries"));
	}

	@Test
	void literalLegacyRecordsRetainRootCellsEffectsAndModulesWithoutCurrentEncoder() {
		// Historical field names/types are pinned independently of every archive encoder.
		var literalGraph = obj("inputs", obj(), "nodes", obj(
			"value", obj("kind", "constant", "value", 1),
			"target", obj("kind", "constant", "value", "peer"),
			"payload", obj("kind", "record", "fields", obj("count", "value"))),
			"outputs", obj("out", "value", "target", "target", "payload", "payload"));
		var cells = obj("n", obj("type", obj("root", 0, "nodes", new DataValue.ListValue(List.of(
			obj("kind", "scalar", "name", "INTEGER")))), "initial", 0));
		var writes = new DataValue.ListValue(List.of(obj("cell", "n", "output", "out")));
		var sends = new DataValue.ListValue(List.of(obj("endpointOutput", "target", "payloadOutput", "payload")));
		var modules = new DataValue.ListValue(List.of(obj("key", obj("id", "saved", "revision", 2),
			"graph", graphArchive(7))));
		var versionOne = obj("version", 1, "graph", literalGraph, "cells", cells, "writes", writes, "modules", modules);
		var versionTwo = obj("version", 2, "graph", literalGraph, "cells", cells, "writes", writes,
			"sends", sends, "modules", modules);
		var expectedRoot = new ComputationGraph(Map.of(), Map.of(
			"value", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
			"target", new ComputationGraph.Constant(new DataValue.TextValue("peer")),
			"payload", new ComputationGraph.MakeRecord(Map.of("count", "value"))),
			Map.of("out", "value", "target", "target", "payload", "payload"));
		var key = new ModuleDefinition.Key("saved", 2);
		for (var literal : List.of(versionOne, versionTwo)) {
			var decoded = ProgramArchiveCodec.decode(literal);
			assertEquals(expectedRoot, decoded.program().graph());
			assertEquals(Map.of("n", new ProgramDefinition.Cell(INTEGER, new DataValue.IntegerValue(0))), decoded.program().cells());
			assertEquals(List.of(new ProgramDefinition.Write("n", "out")), decoded.program().writes());
			assertEquals(literal == versionOne ? List.of() : List.of(new ProgramDefinition.Send("target", "payload")),
				decoded.program().sends());
			assertEquals(Map.of(key, new ModuleDefinition(key, graph(7))), decoded.modules());
			assertEquals(Map.of(), decoded.program().entries());
		}
	}

	@Test
	void malformedEntryNamesAndGraphsAreRejected() {
		assertThrows(ComputationException.class, () -> new ProgramDefinition(graph(1), Map.of(), List.of(), List.of(),
			Map.of(" ", new ProgramDefinition.Entry(graph(2), List.of(), List.of()))));
		var encoded = ProgramArchiveCodec.encode(new ProgramArchive(new ProgramDefinition(graph(1), Map.of(), List.of()), Map.of()));
		var badEntry = new DataValue.RecordValue(Map.of("graph", new DataValue.RecordValue(Map.of()),
			"writes", new DataValue.ListValue(List.of()), "sends", new DataValue.ListValue(List.of())));
		var fields = new LinkedHashMap<>(encoded.fields());
		fields.put("entries", new DataValue.RecordValue(Map.of("message", badEntry)));
		assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(new DataValue.RecordValue(fields)));
	}

	private static ComputationGraph graph(long value) {
		return new ComputationGraph(Map.of(), Map.of("value", new ComputationGraph.Constant(new DataValue.IntegerValue(value))),
			Map.of("out", "value"));
	}
	private static DataValue.RecordValue graphArchive(long value) {
		return new DataValue.RecordValue(Map.of(
			"inputs", new DataValue.RecordValue(Map.of()),
			"nodes", new DataValue.RecordValue(Map.of("value", new DataValue.RecordValue(Map.of(
				"kind", new DataValue.TextValue("constant"), "value", new DataValue.IntegerValue(value))))),
			"outputs", new DataValue.RecordValue(Map.of("out", new DataValue.TextValue("value")))));
	}
}
