package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowAtomicMemoryTest {
    static FlowProgram decode(List<DataValue> steps) {
        var encoded = (DataValue.RecordValue)FlowProgramCodec.encode(FlowAssemblyMemoryTest.program());
        var fields = new LinkedHashMap<>(encoded.fields()); fields.put("steps", new DataValue.ListValue(steps));
        return FlowProgramCodec.decode(new DataValue.RecordValue(fields));
    }
    static DataValue start() { return FlowAssemblyMemoryTest.obj("kind", "atomic", "source", "update", "result", "result"); }
    static DataValue end() { return FlowAssemblyMemoryTest.obj("kind", "end_atomic", "source", "update_end"); }
    @Test void installationRejectsNestedManualAndWaitingOperationsIncludingThroughCalls() {
        var key = new ModuleDefinition.Key("helper", 1);
        var relay = new ModuleDefinition.Key("relay", 1);
        var illegal = List.<FlowProgram.Step>of(new FlowProgram.Atomic("nested", "inner"),
            new FlowProgram.Memory("begin", FlowProgram.MemoryKind.BEGIN, "", ""),
            new FlowProgram.Memory("commit", FlowProgram.MemoryKind.COMMIT, "", "inner"),
            new FlowProgram.Memory("rollback", FlowProgram.MemoryKind.ROLLBACK, "", ""),
            new FlowProgram.Sleep("sleep", "value"), new FlowProgram.Read("read", "x0", "received", new DataType.RecordType(Map.of())),
            new FlowProgram.Write("write", "x0", "value"), new FlowProgram.AwaitReadable("probe", "x0"),
            new FlowProgram.SerialSend("send", "s0", "value", "sent"), new FlowProgram.SerialReceive("receive", "s0", "received"));
        for (var forbidden : illegal) for (boolean called : List.of(false, true)) {
            var body = new ArrayList<FlowProgram.Step>(); body.add(forbidden);
            if (forbidden instanceof FlowProgram.Atomic) body.add(new FlowProgram.EndAtomic("nested_end"));
            var steps = new ArrayList<FlowProgram.Step>(); steps.add(new FlowProgram.Atomic("update", "result"));
            if (called) steps.add(new FlowProgram.Invoke("call_relay", relay, new FlowCallBindings(Map.of("value", "value"), Map.of())));
            else steps.addAll(body);
            steps.add(new FlowProgram.EndAtomic("end"));
            if (called) {
                steps.add(new FlowProgram.Procedure("relay_def", relay, Map.of("value", DataType.Scalar.INTEGER), Map.of(), Map.of()));
                steps.add(new FlowProgram.Invoke("call_helper", key, new FlowCallBindings(Map.of("value", "value"), Map.of())));
                steps.add(new FlowProgram.EndProcedure("relay_end"));
                steps.add(new FlowProgram.Procedure("helper_def", key, Map.of("value", DataType.Scalar.INTEGER), Map.of(), Map.of()));
                steps.addAll(body); steps.add(new FlowProgram.EndProcedure("helper_end"));
            }
            var error = assertThrows(ComputationException.class, () -> FlowAssemblyMemoryTest.withSteps(steps).validate(FlowAssemblyTasksTest.LIMITS.hardware()), forbidden + " called=" + called);
            assertEquals(ComputationException.Code.INVALID_GRAPH, error.code());
            assertTrue(error.getMessage().contains("atomic"), error.getMessage());
        }
    }
    @Test void helperMayReadAndWriteInsideTheCallersAtomicRegion() {
        var key = new ModuleDefinition.Key("helper", 1);
        var owner = FlowAssemblyMemoryTest.install(FlowAssemblyMemoryTest.withSteps(List.of(
            new FlowProgram.Atomic("update", "result"),
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("value", "value"), Map.of())), new FlowProgram.EndAtomic("end"),
            new FlowProgram.Procedure("definition", key, Map.of("value", DataType.Scalar.INTEGER), Map.of(), Map.of()),
            new FlowProgram.Memory("read", FlowProgram.MemoryKind.READ, "counter", "old"),
            new FlowProgram.Memory("write", FlowProgram.MemoryKind.WRITE, "counter", "value"), new FlowProgram.EndProcedure("return"))));
        for (int i = 0; i < 30 && owner.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) {
            owner.step(FlowAssemblyTasksTest.LIMITS, true); owner = FlowAssemblyTasksTest.restored(owner);
        }
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowSnapshot().orElseThrow().state().status());
        assertEquals(new DataValue.IntegerValue(42), owner.flowSnapshot().orElseThrow().memory().cells().field("counter"));
    }
    @Test void structuredUpdateUsesTheSameInstructionsAndSurvivesEveryOwnerCheckpoint() {
        var program = decode(List.of(start(),
            FlowAssemblyMemoryTest.op("write", "stage", "counter", "value"),
            FlowAssemblyMemoryTest.op("read", "old", "counter", "old"), end()));
        assertEquals(program, FlowProgramCodec.decode(FlowProgramCodec.encode(program)));
        var manual = decode(List.of(FlowAssemblyMemoryTest.op("begin", "update", "", ""),
            FlowAssemblyMemoryTest.op("write", "stage", "counter", "value"), FlowAssemblyMemoryTest.op("read", "old", "counter", "old"),
            FlowAssemblyMemoryTest.op("commit", "update_end", "", "result")));
        var image = program.compile(FlowAssemblyTasksTest.LIMITS.hardware()).assembly().orElseThrow();
        var expanded = manual.compile(FlowAssemblyTasksTest.LIMITS.hardware()).assembly().orElseThrow();
        assertEquals(expanded.code(), image.code(), "The wrapper must not introduce instructions or change source locations");
        assertEquals(expanded.codeWords(), image.codeWords());
        var owner = FlowAssemblyMemoryTest.install(program);
        for (int i = 0; i < 6; i++) {
            owner.step(FlowAssemblyTasksTest.LIMITS, true); owner = FlowAssemblyTasksTest.restored(owner);
            assertNotEquals(FlowRuntime.Status.FAILED, owner.flowSnapshot().orElseThrow().state().status());
            assertEquals(new DataValue.IntegerValue(i < 5 ? 0 : 42), owner.flowSnapshot().orElseThrow().memory().cells().field("counter"));
        }
        var state = owner.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE, state.status()); assertEquals(6, state.work());
        assertEquals(new DataValue.IntegerValue(0), state.locals().field("old"));
        assertEquals(FlowAssemblyMemoryTest.obj("ok", true, "code", "committed"), state.locals().field("result"));
        assertTrue(owner.flowSnapshot().orElseThrow().memory().owners().isEmpty());
    }
}
