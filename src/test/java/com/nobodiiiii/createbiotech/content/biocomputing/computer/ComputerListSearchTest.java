package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComputerListSearchTest {
    @Test void firstExactMatchAndContainsResumeDuringARealComparison() {
        var items = new DataValue.ListValue(List.of(text("another"), text("job-12345678"), text("job-12345678")));
        var types = Map.<String, DataType>of("items", new DataType.ListType(DataType.Scalar.TEXT));
        var graph = new ComputationGraph(types, Map.of(
            "items", new ComputationGraph.Input("items"),
            "present", new ComputationGraph.Constant(text("job-12345678")),
            "absent", new ComputationGraph.Constant(text("Job-12345678")),
            "first", new ComputationGraph.ListSearch("items", "present", false),
            "contains", new ComputationGraph.ListSearch("items", "present", true),
            "missing", new ComputationGraph.ListSearch("items", "absent", false)),
            Map.of("first", "first", "contains", "contains", "missing", "missing"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(),
            List.of(), List.of(), Map.of("find", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(types, List.of(new FlowProgram.Evaluate("find", "find",
            new FlowCallBindings(Map.of("items", "items"), Map.of("first", "first", "contains", "contains", "missing", "missing")))),
            archive, FlowProgram.Execution.REGISTER_V1, FlowProgram.Compilation.SOFTWARE_V1);
        var limits = ComputerListAppendTest.LIMITS;
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, new DataValue.RecordValue(Map.of("items", items)), limits, true);
        for (int i = 0; i < 35 && state(host).assembly().orElseThrow().comparison().isEmpty(); i++) host.step(limits, true);
        var checkpoint = state(host);
        assertTrue(checkpoint.assembly().orElseThrow().comparison().isPresent(), checkpoint.diagnostic().toString());
        assertFalse(checkpoint.locals().fields().containsKey("first"));
        var restored = new ComputerBlockHost(() -> {});
        restored.read(host.write()); restored.tick(limits, true);
        assertTrue(restored.loaded(), restored.diagnostic().toString());
        assertEquals(checkpoint, state(restored));
        for (int i = 0; i < 220 && state(restored).status() == FlowRuntime.Status.READY; i++) restored.step(limits, true);
        var complete = state(restored);
        assertEquals(FlowRuntime.Status.COMPLETE, complete.status(), complete.diagnostic().toString());
        assertEquals(new DataValue.IntegerValue(2), complete.locals().field("first"));
        assertEquals(new DataValue.BooleanValue(true), complete.locals().field("contains"));
        assertEquals(new DataValue.IntegerValue(0), complete.locals().field("missing"));
        assertEquals(items, complete.locals().field("items"));
    }

    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static FlowRuntime.Checkpoint state(ComputerBlockHost host) { return host.flowSnapshot().orElseThrow().state(); }
}
