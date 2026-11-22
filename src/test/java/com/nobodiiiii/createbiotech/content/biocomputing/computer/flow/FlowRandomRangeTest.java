package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowNbtCodec;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowRandomRangeTest {
    private static final UUID TASK = new UUID(0, 0);
    private static final ResourceLimits LIMITS = new ResourceLimits(1000, 100000, 1);

    @Test void rejectedIntegerSampleKeepsItsOperandsAndPaidCycleAcrossAColdCheckpoint() {
        var types = Map.<String, DataType>of("first", DataType.Scalar.INTEGER, "second", DataType.Scalar.INTEGER);
        var graph = new ComputationGraph(types, Map.of(
            "first", new ComputationGraph.Input("first"), "second", new ComputationGraph.Input("second"),
            "draw", new ComputationGraph.RandomRange("first", "second", false)), Map.of("value", "draw"));
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var archive = new ProgramArchive(new ProgramDefinition(empty, Map.of(), List.of(), List.of(),
            Map.of("draw", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(types, List.of(new FlowProgram.Evaluate("random", "draw",
            new FlowCallBindings(Map.of("first", "first", "second", "second"), Map.of("value", "result")))), archive).withAssembly();
        long high = 1L << 62;
        var image = FlowXBus.image(program);
        var computer = new UUID(2, 5);
        var probe = new FlowRuntime(computer, TASK, program, ints(0, high), LIMITS);
        while (!image.drawsRandom(probe.checkpoint().assembly().orElseThrow())) {
            assertTrue(probe.checkpoint().work() < 8);
            assertEquals(1, probe.advance(1));
        }
        var before = probe.checkpoint();
        var operands = before.assembly().orElseThrow();
        UUID rejecting = java.util.stream.LongStream.range(0, 64).mapToObj(seed -> new UUID(0, seed))
            .filter(id -> image.tryRandom(operands, id, before.work()) == operands).findFirst().orElseThrow();
        var runtime = new FlowRuntime(computer, rejecting, program, ints(0, high), LIMITS);
        while (runtime.checkpoint().work() < before.work()) assertEquals(1, runtime.advance(1));
        var start = runtime.checkpoint().assembly().orElseThrow();
        assertEquals(1, runtime.advance(1));
        var waiting = runtime.checkpoint();
        assertEquals(FlowRuntime.Status.READY, waiting.status());
        assertEquals(start, waiting.assembly().orElseThrow(), "A rejected draw does not advance or publish");
        assertEquals(before.work() + 1, waiting.work());
        var snapshot = new FlowNbtCodec.Snapshot(program, waiting);
        var saved = FlowNbtCodec.decode(FlowNbtCodec.encode(snapshot, FlowArchiveTest.ARCHIVE), FlowArchiveTest.ARCHIVE);
        assertEquals(snapshot, saved);
        var restored = FlowRuntime.restore(saved.program(), saved.state(), LIMITS); restored.load();
        for (int i=0; runtime.checkpoint().status()==FlowRuntime.Status.READY; i++) {
            assertTrue(i<64); assertEquals(1, runtime.advance(1)); assertEquals(1, restored.advance(1));
            assertEquals(runtime.checkpoint(), restored.checkpoint());
        }
        assertEquals(FlowRuntime.Status.COMPLETE, restored.checkpoint().status());
        long result = ((DataValue.IntegerValue)restored.checkpoint().locals().field("result")).value();
        assertTrue(result >= 0 && result <= high);
    }

    @Test void authoredDrawsCoverClosedIntegerExtremesAndFiniteHalfOpenDecimals() {
        var integers = image(false);
        var instruction = (RegisterProgram.RandomRange)integers.code().getFirst().instruction();
        var full = integers.begin(ints(Long.MAX_VALUE, Long.MIN_VALUE));
        assertEquals(new DataValue.IntegerValue(Long.MIN_VALUE), integers.tryRandom(full, TASK, 0).registers().get(instruction.to()));
        var pair = integers.begin(ints(0, -1));
        var seen = new HashSet<DataValue>();
        for (int i=0; i<32; i++) seen.add(integers.tryRandom(pair, TASK, i).registers().get(instruction.to()));
        assertEquals(Set.of(new DataValue.IntegerValue(-1), new DataValue.IntegerValue(0)), seen);

        var decimals = image(true);
        var operation = (RegisterProgram.RandomRange)decimals.code().getFirst().instruction();
        var wide = decimals.begin(decimals(Double.MAX_VALUE, -Double.MAX_VALUE));
        var adjacent = decimals.begin(decimals(1.0, Math.nextUp(1.0)));
        for (int i=0; i<32; i++) {
            var value = (DataValue.DecimalValue)decimals.tryRandom(wide, TASK, i).registers().get(operation.to());
            assertTrue(value.value() >= -Double.MAX_VALUE && value.value() < Double.MAX_VALUE);
            assertEquals(new DataValue.DecimalValue(1), decimals.tryRandom(adjacent, TASK, i).registers().get(operation.to()));
        }
    }

    private static RegisterProgram image(boolean decimal) {
        var type = decimal ? DataType.Scalar.DECIMAL : DataType.Scalar.INTEGER;
        var token = decimal ? "DECIMAL" : "INTEGER";
        var source = "REG result " + token + "\nRAND." + token + " result first second\nRET";
        var types = Map.<String, DataType>of("first", type, "second", type);
        var output = Map.<String, DataType>of("result", type);
        var returns = Map.of("result", "result");
        assertThrows(IllegalArgumentException.class, () -> ProcedureAssembler.assemble(new AssemblySource(15, source), types, output, returns, 4096, 64));
        return ProcedureAssembler.assemble(new AssemblySource(source), types, output, returns, 4096, 64).program().forTask("local", 1);
    }

    private static DataValue.RecordValue ints(long first, long second) {
        return new DataValue.RecordValue(Map.of("first", new DataValue.IntegerValue(first), "second", new DataValue.IntegerValue(second)));
    }
    private static DataValue.RecordValue decimals(double first, double second) {
        return new DataValue.RecordValue(Map.of("first", new DataValue.DecimalValue(first), "second", new DataValue.DecimalValue(second)));
    }
}
