package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ListEditTest {
    @Test void completeProgramArchiveCarriesListEditsInRootsEntriesAndFixedModules() {
        var graph = graph("REPLACE", Map.of("source", new DataType.ListType(DataType.Scalar.INTEGER),
            "index", DataType.Scalar.INTEGER, "value", DataType.Scalar.INTEGER));
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var key = new ModuleDefinition.Key("edit", 3);
        for (var archive : List.of(
                new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of()),
                new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
                    Map.of("edit", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of()),
                new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of()), Map.of(key, new ModuleDefinition(key, graph))))) {
            var encoded = ProgramArchiveCodec.encode(archive);
            assertEquals(archive, ProgramArchiveCodec.decode(encoded));
            assertEquals(number(15), encoded.field("version"));
            for (int version = 1; version <= 14; version++) {
                var fields = new LinkedHashMap<>(encoded.fields()); fields.put("version", number(version));
                if (version < 3 && !archive.program().entries().isEmpty())
                    assertTrue(ProgramArchiveCodec.decode(record(fields)).program().entries().isEmpty());
                else assertThrows(ArchiveException.class, () -> ProgramArchiveCodec.decode(record(fields)));
            }
        }
    }
    @Test void insertionRemovalAndSlicesHandleBothEndsWithoutChangingOriginal() {
        for (String operation : List.of("INSERT", "REMOVE", "SLICE")) {
            var types = new LinkedHashMap<String, DataType>();
            types.put("source", new DataType.ListType(DataType.Scalar.INTEGER));
            types.put(operation.equals("SLICE") ? "start" : "index", DataType.Scalar.INTEGER);
            if (operation.equals("SLICE")) types.put("end", DataType.Scalar.INTEGER);
            if (operation.equals("INSERT")) types.put("value", DataType.Scalar.INTEGER);
            var installed = compile(graph(operation, types), 10_000);
            for (int index = 0; index <= 3; index++) {
                if (operation.equals("REMOVE") && index == 3) continue;
                var args = new LinkedHashMap<String, DataValue>(); args.put("source", list(3, 7, 9));
                args.put(operation.equals("SLICE") ? "start" : "index", number(index));
                if (operation.equals("SLICE")) args.put("end", number(3));
                if (operation.equals("INSERT")) args.put("value", number(19));
                var expected = new ArrayList<>(list(3, 7, 9).values());
                if (operation.equals("INSERT")) expected.add(index, number(19));
                if (operation.equals("REMOVE")) expected.remove(index);
                if (operation.equals("SLICE")) expected = new ArrayList<>(expected.subList(index, 3));
                var machine = new ComputationMachine(installed); machine.begin(record(args));
                for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) {
                    assertTrue(guard < 40); machine = ComputationMachine.restore(installed, machine.checkpoint()); machine.tick();
                }
                assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
                assertEquals(new DataValue.ListValue(expected), machine.report().output().orElseThrow().field("result"));
                assertEquals(list(3, 7, 9), args.get("source"));
            }
        }
    }
    static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
    static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    static DataValue.ListValue list(long... values) {
        return new DataValue.ListValue(Arrays.stream(values).mapToObj(v -> (DataValue)number(v)).toList());
    }
    static ComputationGraph graph(String operation, Map<String, DataType> types) {
        var encodedNodes = new LinkedHashMap<String, DataValue>();
        types.forEach((name, type) -> encodedNodes.put(name, record(Map.of("kind", new DataValue.TextValue("input"), "name", new DataValue.TextValue(name)))));
        var bindings = new LinkedHashMap<String, DataValue>();
        types.keySet().forEach(name -> bindings.put(name, new DataValue.TextValue(name)));
        encodedNodes.put("edited", record(Map.of("kind", new DataValue.TextValue("list_edit"),
            "operation", new DataValue.TextValue(operation), "arguments", record(bindings))));
        var encodedTypes = new LinkedHashMap<String, DataValue>();
        types.forEach((name, type) -> encodedTypes.put(name, TypeArchiveCodec.encode(type)));
        return GraphArchiveCodec.decode(record(Map.of("inputs", record(encodedTypes), "nodes", record(encodedNodes),
            "outputs", record(Map.of("result", new DataValue.TextValue("edited"), "original", new DataValue.TextValue("source"))))), operation.equals("APPEND") ? 23 : 13);
    }
    static ProgramCompiler.InstalledProgram compile(ComputationGraph graph, long memory) {
        return ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of()), Map.of(), new ResourceLimits(100, memory, 1));
    }
    static void finish(ComputationMachine machine) {
        for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) {
            assertTrue(guard < 500); machine.tick();
        }
    }

    @Test void replacementCreatesNewListAndRestoresEverySingleWorkStep() {
        var graph = graph("REPLACE", Map.of("source", new DataType.ListType(DataType.Scalar.INTEGER),
            "index", DataType.Scalar.INTEGER, "value", DataType.Scalar.INTEGER));
        var installed = compile(graph, 10_000);
        var source = list(3, 7, 9);
        var machine = new ComputationMachine(installed);
        machine.begin(record(Map.of("source", source, "index", number(1), "value", number(19))));
        for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) {
            assertTrue(guard < 40);
            var before = machine.checkpoint(); machine = ComputationMachine.restore(installed, before);
            assertEquals(before, machine.checkpoint()); machine.tick();
            assertEquals(before.workUnits() + 1, machine.report().workUnits());
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
        assertEquals(list(3, 19, 9), machine.report().output().orElseThrow().field("result"));
        assertEquals(source, machine.report().output().orElseThrow().field("original"));
        assertEquals(list(3, 7, 9), source);
        assertEquals(new DataType.ListType(DataType.Scalar.INTEGER), installed.root().nodeTypes().get("edited"));
    }
}
