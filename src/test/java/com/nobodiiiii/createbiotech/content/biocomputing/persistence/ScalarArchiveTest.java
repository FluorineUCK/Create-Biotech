package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScalarArchiveTest {
	@Test void programFiveScalarRoundTripsThroughDocumentAndNbt() {
		var archive = ScalarProgramFixtures.intervalArchive();
		var encoded = ProgramArchiveCodec.encode(archive);
		assertEquals(new DataValue.IntegerValue(12), encoded.field("version"));
		assertEquals(archive, ProgramArchiveCodec.decode(encoded));
		var nbt = ValueNbtCodec.encode(encoded, ValueNbtCodecTest.LIMITS);
		assertEquals(archive, ProgramArchiveCodec.decode(ValueNbtCodec.decode(nbt, ValueNbtCodecTest.LIMITS)));
	}

	@Test void oldSchemasRejectScalarRatherThanUnlockingItByHeaderChange() {
		var current = ProgramArchiveCodec.encode(ScalarProgramFixtures.intervalArchive());
		for (long version = 1; version <= 4; version++) {
			var fields = new LinkedHashMap<>(current.fields());
			fields.put("version", new DataValue.IntegerValue(version));
			if (version == 1) fields.remove("sends");
			if (version < 3) fields.remove("entries");
			assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(new DataValue.RecordValue(fields)));
		}
	}

	@Test void unknownOperationAndArgumentFieldsAreRejected() {
		var encoded = ProgramArchiveCodec.encode(ScalarProgramFixtures.intervalArchive());
		assertInvalid(rewriteFirstScalar(encoded, node -> replace(node, "operation", new DataValue.TextValue("NOT_AN_OPERATION"))));
		assertInvalid(rewriteFirstScalar(encoded, node -> replace(node, "arguments", new DataValue.RecordValue(Map.of("wrong", new DataValue.TextValue("value"))))));
	}

	@Test void literalIndependentProgramFourBinaryAndRpcDocumentStillDecodes() {
		var graph = obj("inputs", obj(), "nodes", obj(
			"left", obj("kind", "constant", "value", 1), "right", obj("kind", "constant", "value", 2),
			"sum", obj("kind", "binary", "operation", "ADD_INTEGER", "left", "left", "right", "right"),
			"endpoint", obj("kind", "constant", "value", "factory:test"),
			"payload", obj("kind", "record", "fields", obj("amount", "sum"))),
			"outputs", obj("endpoint", "endpoint", "payload", "payload", "sum", "sum"));
		var send = obj("endpointOutput", "endpoint", "payloadOutput", "payload", "dispatch", "REQUEST", "requestSlot", "slot", "replyEntry", "reply");
		var fixture = obj("version", 4, "graph", graph, "cells", obj(), "writes", new DataValue.ListValue(List.of()),
			"sends", new DataValue.ListValue(List.of(send)), "entries", obj("reply", obj("graph", graph,
				"writes", new DataValue.ListValue(List.of()), "sends", new DataValue.ListValue(List.of()))),
			"modules", new DataValue.ListValue(List.of()));
		var decoded = ProgramArchiveCodec.decode(fixture);
		assertInstanceOf(ComputationGraph.Binary.class, decoded.program().graph().nodes().get("sum"));
		assertEquals(List.of(ProgramDefinition.Send.request("endpoint", "payload", "slot", "reply")), decoded.program().sends());
		assertEquals(graph, ProgramArchiveCodec.encode(new ProgramArchive(new ProgramDefinition(decoded.program().entries().get("reply").graph(), Map.of(), List.of()), Map.of())).field("graph"));
		assertEquals(List.of(), decoded.program().entries().get("reply").writes());
		assertEquals(List.of(), decoded.program().entries().get("reply").sends());
	}

	@Test void oldSchemasRejectScalarInRootEntriesAndModules() {
		var base = ScalarProgramFixtures.intervalArchive();
		var key = new ModuleDefinition.Key("scalar_module", 1);
		var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
		var rootOnly = ProgramArchiveCodec.encode(base);
		var entryOnly = ProgramArchiveCodec.encode(new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
			Map.of("scalar_entry", new ProgramDefinition.Entry(base.program().graph(), List.of(), List.of()))), Map.of()));
		var moduleOnly = ProgramArchiveCodec.encode(new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()),
			Map.of(key, new ModuleDefinition(key, base.program().graph()))));
		for (long version = 1; version <= 4; version++) assertOldRejected(rootOnly, version);
		for (long version = 3; version <= 4; version++) assertOldRejected(entryOnly, version);
		for (long version = 1; version <= 4; version++) assertOldRejected(moduleOnly, version);
	}

	private static DataValue.RecordValue rewriteFirstScalar(DataValue.RecordValue archive,
		java.util.function.UnaryOperator<DataValue.RecordValue> rewrite) {
		var root = (DataValue.RecordValue) archive.field("graph");
		var nodes = new LinkedHashMap<>(((DataValue.RecordValue) root.field("nodes")).fields());
		for (var entry : nodes.entrySet()) {
			var node = (DataValue.RecordValue) entry.getValue();
			if (node.field("kind").equals(new DataValue.TextValue("scalar"))) { nodes.put(entry.getKey(), rewrite.apply(node)); break; }
		}
		var graph = replace(root, "nodes", new DataValue.RecordValue(nodes));
		return replace(archive, "graph", graph);
	}
	private static DataValue.RecordValue replace(DataValue.RecordValue source, String key, DataValue value) {
		var fields = new LinkedHashMap<>(source.fields()); fields.put(key, value); return new DataValue.RecordValue(fields);
	}
	private static void assertOldRejected(DataValue.RecordValue current, long version) {
		var fields = new LinkedHashMap<>(current.fields()); fields.put("version", new DataValue.IntegerValue(version));
		if (version == 1) fields.remove("sends");
		if (version < 3) fields.remove("entries");
		assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(new DataValue.RecordValue(fields)));
	}
	private static void assertInvalid(DataValue value) { assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(value)); }
}
