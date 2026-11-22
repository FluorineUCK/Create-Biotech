package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;

/** Real multi-entry programs used by runtime and persistence regression tests. */
public final class EventEntryFixtures {
    private EventEntryFixtures() {}
    public static final ResourceLimits HARDWARE = new ResourceLimits(1000, 100_000, 1);
    public static final ArchiveLimits ARCHIVE = new ArchiveLimits(1000, 1_000_000, 10_000_000);
    public static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(HARDWARE, ARCHIVE));
    public static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());

    public static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    public static DataValue.RecordValue input(String name, long value) {
        return new DataValue.RecordValue(Map.of(name, integer(value)));
    }
    public static MessageEnvelope message(long amount) {
        return new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, input("amount", amount));
    }
    public static ProgramArchive program(boolean sends) {
        var root = graph("manual", sends ? List.of("root_peer") : List.of());
        var message = graph("amount", sends ? List.of("message_peer_a", "message_peer_b") : List.of());
        var moduleKey = new ModuleDefinition.Key("message_identity", 1);
        var module = new ModuleDefinition(moduleKey, new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER),
            Map.of("value", new ComputationGraph.Input("value")), Map.of("value", "value")));
        var messageNodes = new LinkedHashMap<>(message.nodes());
        messageNodes.put("call", new ComputationGraph.Call(moduleKey, Map.of("value", "input")));
        messageNodes.put("resolved", new ComputationGraph.Field("call", "value"));
        var messageOutputs = new LinkedHashMap<>(message.outputs()); messageOutputs.put("out", "resolved");
        message = new ComputationGraph(message.inputs(), messageNodes, messageOutputs);
        var timer = new ComputationGraph(Map.of(), Map.of(
            "old", new ComputationGraph.ReadMemory("n"),
            "one", new ComputationGraph.Constant(integer(1)),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "old", "one")), Map.of("out", "sum"));
        return new ProgramArchive(new ProgramDefinition(root, Map.of(
            "n", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(0)),
            "untouched", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, integer(42))),
            List.of(new ProgramDefinition.Write("n", "out")), sends ? bindings(1) : List.of(),
            Map.of("message", new ProgramDefinition.Entry(message, List.of(new ProgramDefinition.Write("n", "out")), sends ? bindings(2) : List.of()),
                "timer", new ProgramDefinition.Entry(timer, List.of(new ProgramDefinition.Write("n", "out")), List.of()))), Map.of(moduleKey, module));
    }
    private static List<ProgramDefinition.Send> bindings(int count) {
        var result = new ArrayList<ProgramDefinition.Send>();
        for (int i = 0; i < count; i++) result.add(new ProgramDefinition.Send("target" + i, "payload"));
        return result;
    }
    private static ComputationGraph graph(String input, List<String> peers) {
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        var outputs = new LinkedHashMap<String, String>();
        nodes.put("input", new ComputationGraph.Input(input)); outputs.put("out", "input");
        if (!peers.isEmpty()) {
            nodes.put("payload", new ComputationGraph.MakeRecord(Map.of("amount", "input"))); outputs.put("payload", "payload");
            for (int i = 0; i < peers.size(); i++) {
                nodes.put("target" + i, new ComputationGraph.Constant(new DataValue.TextValue(peers.get(i))));
                outputs.put("target" + i, "target" + i);
            }
        }
        return new ComputationGraph(Map.of(input, DataType.Scalar.INTEGER), nodes, outputs);
    }
    public static void finish(ComputerRuntime runtime) {
        for (int i = 0; i < 100 && runtime.report().status() == ComputationMachine.Status.RUNNING; i++) runtime.step();
        org.junit.jupiter.api.Assertions.assertEquals(ComputationMachine.Status.SUCCEEDED, runtime.report().status());
    }
}
