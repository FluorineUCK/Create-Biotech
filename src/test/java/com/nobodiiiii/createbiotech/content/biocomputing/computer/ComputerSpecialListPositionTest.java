package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComputerSpecialListPositionTest {
    private static final DataType.ListType INTEGERS = new DataType.ListType(DataType.Scalar.INTEGER);

    @Test void authoredRandomBelowRejectsOldSourceAndMakesOnePaidAttemptAtATime() {
        var integer = Map.<String, DataType>of("bound", DataType.Scalar.INTEGER);
        String source = "REG draw INTEGER\nRAND.BELOW draw bound\nRET";
        var returns = Map.of("bound", "draw");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ProcedureAssembler.assemble(new AssemblySource(12, source),
            integer, integer, returns, 4096, 64)).getMessage().contains("Unsupported assembler version"));
        var image = ProcedureAssembler.assemble(new AssemblySource(source), integer, integer, returns, 4096, 64)
            .program().forTask("local", 1);
        long bound = (1L << 62) + 1;
        var first = image.begin(new DataValue.RecordValue(Map.of("bound", new DataValue.IntegerValue(bound))));
        assertTrue(image.drawsRandom(first));
        var id = new UUID(0, 0);
        assertSame(first, image.tryRandom(first, id, 0), "The zero sample is in the incomplete residue class");
        assertDoesNotThrow(() -> image.validate(first, 1, false), "A paid rejection may be saved inside an authored procedure");
        var next = first;
        for (long work = 1; next == first && work < 64; work++) {
            next = image.tryRandom(first, id, work);
            assertEquals(next, image.tryRandom(first, id, work));
        }
        assertFalse(image.drawsRandom(next));
        var instruction = (RegisterProgram.RandomBelow)image.code().get(first.pc()).instruction();
        long draw = ((DataValue.IntegerValue)next.registers().get(instruction.to())).value();
        assertTrue(draw >= 0 && draw < bound);
    }

    @Test void lastUsesTheSamePublicSnapshotForAppendingAndReading() {
        var source = program(ListOperation.INSERT, ComputationGraph.ListPosition.Choice.LAST);
        var host = install(source);
        run(host);
        assertEquals(list(4, 8, 12, 19), state(host).memory().cells().field("items"));
        assertEquals(new DataValue.IntegerValue(19), state(host).state().locals().field("last"));
    }

    @Test void randomRemovalMakesTheSameChoiceOnAnIndependentResumeFromBeforeTheDraw() {
        var host = install(program(ListOperation.REMOVE, ComputationGraph.ListPosition.Choice.RANDOM));
        var limits = ComputerListAppendTest.LIMITS;
        while (state(host).memory().owners().isEmpty()) {
            assertTrue(state(host).state().work() < 12);
            host.step(limits, true);
        }
        var beforeDraw = host.write();
        var restored = new ComputerBlockHost(() -> {});
        restored.read(beforeDraw); restored.tick(limits, true);
        assertTrue(restored.loaded(), restored.diagnostic().toString());
        assertEquals(state(host), state(restored));
        run(host); run(restored);
        assertEquals(state(host), state(restored));
        var items = ((DataValue.ListValue)state(host).memory().cells().field("items")).values();
        assertEquals(2, items.size());
        assertTrue(list(4, 8, 12).values().containsAll(items));
        assertEquals(new DataValue.TextValue("committed"),
            ((DataValue.RecordValue)state(host).state().locals().field("committed")).field("code"));
    }

    private static FlowProgram program(ListOperation op, ComputationGraph.ListPosition.Choice choice) {
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        nodes.put("list", new ComputationGraph.ReadMemory("items"));
        nodes.put("position", new ComputationGraph.ListPosition("list", choice, op == ListOperation.INSERT));
        nodes.put("one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)));
        nodes.put("index", new ComputationGraph.Scalar(ScalarOperation.SUBTRACT_INTEGER, Map.of("left", "position", "right", "one")));
        var readNodes = new LinkedHashMap<>(nodes);
        readNodes.put("position", new ComputationGraph.ListPosition("list", ComputationGraph.ListPosition.Choice.LAST, false));
        readNodes.put("last", new ComputationGraph.DynamicIndex("list", "index"));
        var args = new LinkedHashMap<>(Map.of("source", "list", "index", "index"));
        if (op == ListOperation.INSERT) {
            args.put("value", "item");
            nodes.put("item", new ComputationGraph.Constant(new DataValue.IntegerValue(19)));
        }
        nodes.put("result", new ComputationGraph.ListEdit(op, args));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("items", new ProgramDefinition.Cell(INTEGERS, list(4, 8, 12))), List.of(), List.of(),
            Map.of("edit", entry(nodes, "result"), "read", entry(readNodes, "last"))), Map.of());
        return new FlowProgram(Map.of(), List.of(
            new FlowProgram.Shared("edit", "items", new FlowInput.Expression("edit", "value", Map.of()), false, Optional.of("committed")),
            new FlowProgram.Evaluate("read", "read", new FlowCallBindings(Map.of(), Map.of("value", "last")))), archive).withAssembly();
    }

    private static ProgramDefinition.Entry entry(Map<String, ComputationGraph.Node> nodes, String output) {
        return new ProgramDefinition.Entry(new ComputationGraph(Map.of(), nodes, Map.of("value", output)), List.of(), List.of());
    }
    private static ComputerBlockHost install(FlowProgram source) {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(source, new DataValue.RecordValue(Map.of()), ComputerListAppendTest.LIMITS, true);
        return host;
    }
    private static void run(ComputerBlockHost host) {
        for (int i = 0; i < 45 && state(host).state().status() == FlowRuntime.Status.READY; i++)
            host.step(ComputerListAppendTest.LIMITS, true);
        assertEquals(FlowRuntime.Status.COMPLETE, state(host).state().status(), state(host).state().diagnostic().toString());
    }
    private static FlowBlockHost.Saved state(ComputerBlockHost host) { return host.flowSnapshot().orElseThrow(); }
    private static DataValue.ListValue list(long... values) {
        return new DataValue.ListValue(Arrays.stream(values).mapToObj(n -> (DataValue)new DataValue.IntegerValue(n)).toList());
    }
}
