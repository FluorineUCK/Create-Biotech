package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import java.util.*;

public final class RequestReplyFixtures {
    private RequestReplyFixtures() {}
    public static final ResourceLimits HARDWARE = EventEntryFixtures.HARDWARE;
    public static DataValue.RecordValue input(String field, long value) { return EventEntryFixtures.input(field, value); }
    public static void finish(ComputerRuntime runtime) { EventEntryFixtures.finish(runtime); }
    public static ProgramArchive client() {
        return new ProgramArchive(new ProgramDefinition(graph("query", "number", true), Map.of("result", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))), List.of(),
            List.of(ProgramDefinition.Send.request("target", "payload", "lookup", "result")),
            Map.of("result", new ProgramDefinition.Entry(graph("answer", "unused", false), List.of(new ProgramDefinition.Write("result", "value")), List.of()))), Map.of());
    }
    public static ProgramArchive server() {
        return new ProgramArchive(new ProgramDefinition(graph("manual", "unused", false), Map.of("result", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(100))), List.of(), List.of(),
            Map.of("request", new ProgramDefinition.Entry(graph("number", "answer", false), List.of(new ProgramDefinition.Write("result", "value")), List.of(ProgramDefinition.Send.reply("payload"))))), Map.of());
    }
    private static ComputationGraph graph(String input, String payloadField, boolean target) {
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        nodes.put("input", new ComputationGraph.Input(input));
        nodes.put("payload", new ComputationGraph.MakeRecord(Map.of(payloadField, "input")));
        var outputs = new LinkedHashMap<String, String>(); outputs.put("value", "input"); outputs.put("payload", "payload");
        if (target) { nodes.put("target", new ComputationGraph.Constant(new DataValue.TextValue("server"))); outputs.put("target", "target"); }
        return new ComputationGraph(Map.of(input, DataType.Scalar.INTEGER), nodes, outputs);
    }
    public static MessageEnvelope request(UUID source, OutboundMessage output, String returnEndpoint) {
        var correlation = ((OutboundProtocol.Request) output.protocol()).correlation();
        return new MessageEnvelope(source, output.sequence(), output.eventId(), output.ordinal(), output.payload(), new MessageProtocol.Request(new RequestContext(correlation, returnEndpoint)));
    }
    public static MessageEnvelope reply(UUID source, OutboundMessage output) {
        return new MessageEnvelope(source, output.sequence(), output.eventId(), output.ordinal(), output.payload(), new MessageProtocol.Reply(((OutboundProtocol.Reply) output.protocol()).correlation()));
    }
    public static void acknowledge(ComputerRuntime runtime) { var output = runtime.outbound().orElseThrow(); runtime.acknowledge(output.eventId(), output.ordinal()); }
}
