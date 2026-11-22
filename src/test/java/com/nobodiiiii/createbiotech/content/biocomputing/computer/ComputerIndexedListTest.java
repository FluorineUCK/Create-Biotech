package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComputerIndexedListTest {
    private static final DataType.ListType INTEGERS = new DataType.ListType(DataType.Scalar.INTEGER);

    private static ComputerBlockHost install(ListOperation operation, long index) {
        var ports = new LinkedHashMap<String, String>();
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        ports.put("source", "source");
        nodes.put("source", new ComputationGraph.Input("items"));
        for (String port : operation.inputs().keySet()) {
            if (port.equals("source")) continue;
            ports.put(port, port);
            nodes.put(port, new ComputationGraph.Constant(new DataValue.IntegerValue(switch (port) {
                case "value" -> 7;
                case "end" -> 3;
                default -> index;
            })));
        }
        nodes.put("edited", new ComputationGraph.ListEdit(operation, ports));
        var graph = new ComputationGraph(Map.of("items", INTEGERS), nodes, Map.of("value", "edited"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(),
            List.of(), List.of(), Map.of("edit", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(Map.of("items", INTEGERS), List.of(new FlowProgram.Evaluate("edit", "edit",
            new FlowCallBindings(Map.of("items", "items"), Map.of("value", "items")))), archive).withAssembly();
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, new DataValue.RecordValue(Map.of("items", list(3, 5, 9))), ComputerListAppendTest.LIMITS, true);
        return host;
    }

    @Test void indexedFlowWritesBackOnlyOnceAfterResumingAMidCopyCheckpoint() {
        for (var edit : Map.of(ListOperation.INSERT, list(3, 7, 5, 9), ListOperation.REPLACE, list(3, 7, 9),
                ListOperation.REMOVE, list(3, 9), ListOperation.SLICE, list(5, 9)).entrySet()) {
            var host = install(edit.getKey(), 1);
            for (int step = 0; step < 20 && !midCopy(state(host)); step++)
                host.step(ComputerListAppendTest.LIMITS, true);
            var before = state(host);
            assertTrue(midCopy(before), before.diagnostic().toString());
            assertEquals(list(3, 5, 9), before.locals().field("items"));
            var restored = new ComputerBlockHost(() -> {});
            restored.read(host.write());
            restored.tick(ComputerListAppendTest.LIMITS, true);
            assertTrue(restored.loaded(), restored.diagnostic().toString());
            assertEquals(before, state(restored));
            for (int i = 0; i < 25; i++) restored.step(ComputerListAppendTest.LIMITS, true);
            assertEquals(FlowRuntime.Status.COMPLETE, state(restored).status());
            assertEquals(edit.getValue(), state(restored).locals().field("items"));
        }
    }

    @Test void authoredV12RunsTheSameOpcodeAndV11DoesNotAcceptIt() {
        var limits = ComputerListAppendTest.LIMITS;
        var inputs = new DataValue.RecordValue(Map.of("items", list(3, 5, 9), "index", new DataValue.IntegerValue(1)));
        var old = new ComputerBlockHost(() -> {});
        assertTrue(assertThrows(IllegalArgumentException.class, () -> old.installFlow(authored(11), inputs, limits, true))
            .getMessage().contains("Unsupported assembler version"));

        var host = new ComputerBlockHost(() -> {});
        host.installFlow(authored(AssemblySource.VERSION), inputs, limits, true);
        for (int i = 0; i < 30; i++) host.step(limits, true);
        assertEquals(FlowRuntime.Status.COMPLETE, state(host).status(), state(host).diagnostic().toString());
        assertEquals(list(3, 9), state(host).locals().field("edited"));
        assertEquals(list(3, 5, 9), state(host).locals().field("items"));
    }

    private static FlowProgram authored(int version) {
        var key = new ModuleDefinition.Key("remove", 1);
        var types = Map.<String, DataType>of("items", INTEGERS, "index", DataType.Scalar.INTEGER);
        return new FlowProgram(types, List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("items", "items", "index", "index"), Map.of("result", "edited"))),
            new FlowProgram.Procedure("procedure", key, types, Map.of("result", INTEGERS), Map.of("result", "items"),
                FlowBlockRules.NONE, Optional.of(new AssemblySource(version, "LIST.REMOVE items items index\nRET"))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
    }

    @Test void outOfRangeEditDoesNotChangeTheList() {
        var host = install(ListOperation.REMOVE, Long.MAX_VALUE);
        for (int i = 0; i < 20; i++) host.step(ComputerListAppendTest.LIMITS, true);
        assertEquals(ComputationException.Code.INDEX_OUT_OF_BOUNDS, state(host).diagnostic().orElseThrow().code());
        assertEquals(list(3, 5, 9), state(host).locals().field("items"));
    }

    @Test void concreteIndexedEditsRequireParserHardwareWithoutRequiringMasterAny() {
        var full = ComputerListAppendTest.LIMITS;
        var ordinary = new ComputerHostLimits(full.hardware(), full.archive());
        assertThrows(FlowPins.Unavailable.class, () -> FlowPins.validate(authored(AssemblySource.VERSION), ordinary));
        assertDoesNotThrow(() -> FlowPins.validate(authored(AssemblySource.VERSION),
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture.withIsa1a(ordinary)));
    }

    @Test void corruptCopyCursorAndInvalidRestoredIndexKeepTheOriginalRawSave() {
        for (boolean corruptCursor : List.of(false, true)) {
            var host = install(ListOperation.REMOVE, 1);
            for (int step=0;!midCopy(state(host));step++) {
                assertTrue(step<30);
                host.step(ComputerListAppendTest.LIMITS,true);
            }
            var raw = (net.minecraft.nbt.CompoundTag)host.write();
            var bounds = ComputerListAppendTest.LIMITS.archive();
            var owner = (DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(raw.getCompound("Data"),bounds);
            var flow = ComputerAssemblyConstructionTest.record(owner,"flow");
            var checkpoint = ComputerAssemblyConstructionTest.record(flow,"state");
            var assembly = ComputerAssemblyConstructionTest.record(checkpoint,"assembly");
            if (corruptCursor) {
                assembly = ComputerAssemblyConstructionTest.patch(assembly,"build",new DataValue.RecordValue(
                    Map.of("cursor",new DataValue.IntegerValue(999))));
            } else {
                var registers = ComputerAssemblyConstructionTest.record(assembly,"registers");
                String index = registers.fields().entrySet().stream().filter(e -> e.getValue().equals(new DataValue.IntegerValue(1)))
                    .findFirst().orElseThrow().getKey();
                assembly = ComputerAssemblyConstructionTest.patch(assembly,"registers",
                    ComputerAssemblyConstructionTest.patch(registers,index,new DataValue.IntegerValue(Long.MAX_VALUE)));
            }
            var corrupted = ComputerAssemblyConstructionTest.patch(owner,"flow",ComputerAssemblyConstructionTest.patch(flow,"state",
                ComputerAssemblyConstructionTest.patch(checkpoint,"assembly",assembly)));
            raw.put("Data",com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(corrupted,bounds));
            var restored = new ComputerBlockHost(()->{});
            restored.read(raw);restored.tick(ComputerListAppendTest.LIMITS,true);
            assertFalse(restored.loaded());
            assertEquals(raw,restored.write(),"Refusing a corrupt checkpoint must not erase it");
        }
    }

    private static boolean midCopy(FlowRuntime.Checkpoint state) {
        return state.assembly().orElseThrow().build().filter(build -> build.cursor() == 1).isPresent();
    }

    private static FlowRuntime.Checkpoint state(ComputerBlockHost host) {
        return host.flowSnapshot().orElseThrow().state();
    }
    private static DataValue.ListValue list(long... numbers) {
        return new DataValue.ListValue(Arrays.stream(numbers).mapToObj(n -> (DataValue)new DataValue.IntegerValue(n)).toList());
    }
}
