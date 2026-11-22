package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class BranchArchiveTest {
	@Test void programSixBranchRoundTripsAndProgramFiveScalarRemainsReadable() {
		var archive = BranchProgramFixtures.archive();
		var encoded = ProgramArchiveCodec.encode(archive);
		assertEquals(new DataValue.IntegerValue(12), encoded.field("version"));
		assertEquals(archive, ProgramArchiveCodec.decode(encoded));
		var scalar = ProgramArchiveCodec.encode(ScalarProgramFixtures.intervalArchive());
		var fields = new LinkedHashMap<>(((DataValue.RecordValue) scalar).fields());
		fields.put("version", new DataValue.IntegerValue(5));
		assertEquals(ScalarProgramFixtures.intervalArchive(), ProgramArchiveCodec.decode(new DataValue.RecordValue(fields)));
	}

	@Test void schemasOneThroughFiveRejectBranchOnlyInRoot() {
		for (long version = 1; version <= 5; version++)
			assertBranchSchemaRejection(fixture(version, branchGraph(), obj(), leafModules()));
	}

	@Test void schemasOneThroughFiveRejectBranchOnlyInModule() {
		var modules = new DataValue.ListValue(List.of(leafModule(),
			obj("key", obj("id", "container", "revision", 1), "graph", branchGraph())));
		assertInstanceOf(ComputationGraph.Branch.class,
			ProgramArchiveCodec.decode(fixture(6, plainGraph(), obj(), modules)).modules()
				.get(new ModuleDefinition.Key("container", 1)).graph().nodes().get("choice"));
		for (long version = 1; version <= 5; version++) {
			var control = fixture(version, plainGraph(), obj(), leafModules());
			assertDoesNotThrow(() -> ProgramArchiveCodec.decode(control));
			assertBranchSchemaRejection(fixture(version, plainGraph(), obj(), modules));
		}
	}

	@Test void schemasThreeThroughFiveRejectBranchOnlyInEntry() {
		var entries = obj("nested", obj("graph", branchGraph(), "writes", emptyList(), "sends", emptyList()));
		assertInstanceOf(ComputationGraph.Branch.class,
			ProgramArchiveCodec.decode(fixture(6, plainGraph(), entries, leafModules())).program()
				.entries().get("nested").graph().nodes().get("choice"));
		for (long version = 3; version <= 5; version++) {
			var control = fixture(version, plainGraph(),
				obj("nested", obj("graph", plainGraph(), "writes", emptyList(), "sends", emptyList())), leafModules());
			assertDoesNotThrow(() -> ProgramArchiveCodec.decode(control));
			assertBranchSchemaRejection(fixture(version, plainGraph(), entries, leafModules()));
		}
	}

	private static void assertBranchSchemaRejection(DataValue fixture) {
		var error = assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(fixture));
		assertEquals(ArchiveException.Code.INVALID_FORMAT, error.code());
		assertEquals("Branch nodes are unavailable in this archive schema", error.getMessage());
	}

	private static DataValue.RecordValue fixture(long version, DataValue graph, DataValue entries, DataValue modules) {
		var fields = new LinkedHashMap<>(obj("version", version, "graph", graph, "cells", obj(),
			"writes", emptyList(), "modules", modules).fields());
		if (version >= 2) fields.put("sends", emptyList());
		if (version >= 3) fields.put("entries", entries);
		return new DataValue.RecordValue(fields);
	}

	private static DataValue.RecordValue plainGraph() {
		return obj("inputs", obj(), "nodes", obj("value", obj("kind", "constant", "value", 0)),
			"outputs", obj("value", "value"));
	}

	private static DataValue.RecordValue branchGraph() {
		var key = obj("id", "leaf", "revision", 1);
		return obj("inputs", obj(), "nodes", obj(
			"condition", obj("kind", "constant", "value", true),
			"choice", obj("kind", "branch", "condition", "condition", "whenTrue", key, "whenFalse", key, "arguments", obj())),
			"outputs", obj("result", "choice"));
	}

	private static DataValue.RecordValue leafModule() {
		return obj("key", obj("id", "leaf", "revision", 1), "graph", plainGraph());
	}

	private static DataValue.ListValue leafModules() {
		return new DataValue.ListValue(List.of(leafModule()));
	}

	private static DataValue.ListValue emptyList() {
		return new DataValue.ListValue(List.of());
	}

	@Test void literalIndependentProgramFiveScalarStillDecodes() {
		var graph=obj("inputs",obj(),"nodes",obj(
			"left",obj("kind","constant","value",2),"right",obj("kind","constant","value",3),
			"sum",obj("kind","scalar","operation","ADD_INTEGER","arguments",obj("left","left","right","right"))),
			"outputs",obj("sum","sum"));
		var fixture=obj("version",5,"graph",graph,"cells",obj(),"writes",new DataValue.ListValue(java.util.List.of()),
			"sends",new DataValue.ListValue(java.util.List.of()),"entries",obj(),"modules",new DataValue.ListValue(java.util.List.of()));
		var decoded=ProgramArchiveCodec.decode(fixture);
		assertInstanceOf(ComputationGraph.Scalar.class,decoded.program().graph().nodes().get("sum"));
	}
}
