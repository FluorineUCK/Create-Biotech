package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;

/** Data-only program interchange. Loading does not install or execute it; the compiler remains authoritative. */
public final class ProgramArchiveCodec {
	private ProgramArchiveCodec() {}
	private static int archiveVersion(ProgramArchive archive) {
		var graphs = new ArrayList<ComputationGraph>();
		graphs.add(archive.program().graph());
		archive.program().entries().values().forEach(entry -> graphs.add(entry.graph()));
		archive.modules().values().forEach(module -> graphs.add(module.graph()));
		int graphVersion = GraphArchiveCodec.encodingVersion(graphs);
		if (graphVersion >= 12) return graphVersion + 2;
		return graphs.stream().flatMap(graph -> graph.nodes().values().stream())
			.anyMatch(ComputationGraph.DynamicIndex.class::isInstance) ? 13 : 12;
	}
	/** Capability check includes fixed module revisions and event entries, not just the active root. */
    public static boolean hasValueEquality(ProgramArchive archive) {
        if (archive.program().graph().nodes().values().stream().anyMatch(ComputationGraph.ValueEquals.class::isInstance)) return true;
        if (archive.program().entries().values().stream().flatMap(entry -> entry.graph().nodes().values().stream()).anyMatch(ComputationGraph.ValueEquals.class::isInstance)) return true;
        return archive.modules().values().stream().flatMap(module -> module.graph().nodes().values().stream()).anyMatch(ComputationGraph.ValueEquals.class::isInstance);
    }
    public static boolean hasDistinct(ProgramArchive archive) {
        if (archive.program().graph().nodes().values().stream().anyMatch(ComputationGraph.Distinct.class::isInstance)) return true;
        if (archive.program().entries().values().stream().flatMap(entry -> entry.graph().nodes().values().stream()).anyMatch(ComputationGraph.Distinct.class::isInstance)) return true;
        return archive.modules().values().stream().flatMap(module -> module.graph().nodes().values().stream()).anyMatch(ComputationGraph.Distinct.class::isInstance);
    }
	public static DataValue.RecordValue encode(ProgramArchive archive) {
		var program = archive.program();
		return obj("version", archiveVersion(archive), "graph", GraphArchiveCodec.encode(program.graph()),
			"cells", named(program.cells(), cell -> obj("type", TypeArchiveCodec.encode(cell.type()), "initial", cell.initial())),
			"writes", encodeWrites(program.writes()),
			"sends", encodeSends(program.sends()),
			"entries", named(program.entries(), entry -> obj("graph", GraphArchiveCodec.encode(entry.graph()),
				"writes", encodeWrites(entry.writes()), "sends", encodeSends(entry.sends()))),
			"modules", sequence(archive.modules().entrySet(), entry -> {
				if (!entry.getKey().equals(entry.getValue().key())) throw ArchiveException.invalid("Module key mismatch");
				return obj("key", GraphArchiveCodec.key(entry.getKey()), "graph", GraphArchiveCodec.encode(entry.getValue().graph()));
			}));
	}
	public static ProgramArchive decode(DataValue value) {
		try {
			long version = number(at(value, "version"));
			if (version < 1 || version > 36)
				throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported archive schema version");
			long graphVersion = version >= 29 ? version - 2 : version >= 28 ? 26 : version >= 27 ? 25 : version >= 26 ? 24 : version >= 25 ? 23 : version >= 24 ? 22 : version >= 23 ? 21 : version >= 22 ? 20 : version >= 21 ? 19 : version >= 20 ? 18 : version >= 19 ? 17 : version >= 18 ? 16 : version >= 17 ? 15 : version >= 16 ? 14 : version >= 15 ? 13 : version >= 14 ? 12 : version >= 13 ? 11 : version >= 12 ? 10 : Math.min(version, 9);
			var program = new ProgramDefinition(GraphArchiveCodec.decode(at(value, "graph"), graphVersion),
				readNamed(at(value, "cells"), cell -> new ProgramDefinition.Cell(TypeArchiveCodec.decode(at(cell, "type")), at(cell, "initial"))),
				readWrites(at(value, "writes")),
				version == 1 ? List.of() : readSends(at(value, "sends"), version),
				version < 3 ? Map.of() : readNamed(at(value, "entries"), entry -> new ProgramDefinition.Entry(
					GraphArchiveCodec.decode(at(entry, "graph"), graphVersion), readWrites(at(entry, "writes")), readSends(at(entry, "sends"), version))));
			var modules = new LinkedHashMap<ModuleDefinition.Key, ModuleDefinition>();
			for (var module : list(at(value, "modules"))) {
				var key = GraphArchiveCodec.readKey(at(module, "key"));
				if (modules.putIfAbsent(key, new ModuleDefinition(key, GraphArchiveCodec.decode(at(module, "graph"), graphVersion))) != null)
					throw ArchiveException.invalid("Duplicate module version");
			}
			return new ProgramArchive(program, modules);
		} catch (ComputationException | IllegalArgumentException invalid) {
			if (invalid instanceof ArchiveException archive) throw archive;
			throw ArchiveException.invalid("Invalid program: " + invalid.getMessage());
		}
	}
	private static DataValue encodeWrites(List<ProgramDefinition.Write> writes) {
		return sequence(writes, write -> obj("cell", write.cell(), "output", write.output()));
	}
	private static List<ProgramDefinition.Write> readWrites(DataValue value) {
		return list(value).stream().map(write -> new ProgramDefinition.Write(string(at(write, "cell")), string(at(write, "output")))).toList();
	}
	private static DataValue encodeSends(List<ProgramDefinition.Send> sends) {
		return sequence(sends, send -> obj("endpointOutput", send.endpointOutput(), "payloadOutput", send.payloadOutput(),
			"dispatch", send.dispatch().name(), "requestSlot", send.requestSlot(), "replyEntry", send.replyEntry(),
			"continuation", send.continuation().<DataValue>map(binding -> obj("captureOutput", binding.captureOutput(),
				"captureInput", binding.captureInput())).orElse(DataValue.NullValue.INSTANCE),
			"conditionOutput", send.conditionOutput().<DataValue>map(DataValue.TextValue::new).orElse(DataValue.NullValue.INSTANCE)));
	}
	private static List<ProgramDefinition.Send> readSends(DataValue value, long version) {
		return list(value).stream().map(send -> version < 4
			? new ProgramDefinition.Send(string(at(send, "endpointOutput")), string(at(send, "payloadOutput")))
			: new ProgramDefinition.Send(string(at(send, "endpointOutput")), string(at(send, "payloadOutput")),
				enumeration(ProgramDefinition.Dispatch.class, at(send, "dispatch")),
				string(at(send, "requestSlot")), string(at(send, "replyEntry")),
				version < 10 ? Optional.empty() : readContinuation(at(send, "continuation")),
				version < 11 || at(send, "conditionOutput") == DataValue.NullValue.INSTANCE ? Optional.empty()
					: Optional.of(string(at(send, "conditionOutput"))))).toList();
	}
	private static Optional<ProgramDefinition.Continuation> readContinuation(DataValue value) {
		if (value == DataValue.NullValue.INSTANCE) return Optional.empty();
		if (!fields(value).keySet().equals(Set.of("captureOutput", "captureInput")))
			throw ArchiveException.invalid("Unexpected continuation binding fields");
		return Optional.of(new ProgramDefinition.Continuation(string(at(value, "captureOutput")), string(at(value, "captureInput"))));
	}
}
