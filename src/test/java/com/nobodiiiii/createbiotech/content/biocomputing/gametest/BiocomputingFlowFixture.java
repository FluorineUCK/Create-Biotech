package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;

/** Plain public-IR fixture shared by JVM and native lifecycle tests, not a hidden application node. */
public final class BiocomputingFlowFixture {
    private BiocomputingFlowFixture() {}
    public static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
    public static DataValue.RecordValue inputs(long value) {
        return new DataValue.RecordValue(Map.of("packet", new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(value)))));
    }
    public static FlowProgram calculator() {
        var graph = new ComputationGraph(Map.of("packet", PACKET), Map.of(
            "packet", new ComputationGraph.Input("packet"), "value", new ComputationGraph.Field("packet", "value"),
            "seven", new ComputationGraph.Constant(new DataValue.IntegerValue(7)),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "value", "seven"),
            "result", new ComputationGraph.MakeRecord(Map.of("value", "sum"))), Map.of("packet", "result"));
        var expressions = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(),
            List.of(), List.of(), Map.of("sum", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        return new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Evaluate("calculate", "sum"),
            new FlowProgram.Write("send", "x0", "packet")), expressions);
    }
}
