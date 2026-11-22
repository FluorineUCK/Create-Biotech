package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowAuthoredTypeCheckTest {
    static final ComputerHostLimits LIMITS = ParserHardwareFixture.withMasterParser(new ComputerHostLimits(new ResourceLimits(256, 65536, 1), new ArchiveLimits(10000, 100000, 1000000)));
    static DataType.RecordType outcome(DataType type) {
        return new DataType.RecordType(Map.of("ok", DataType.Scalar.BOOLEAN, "value", new DataType.OptionalType(type)));
    }
    static FlowProgram program(DataType type) {
        var key = new ModuleDefinition.Key("check", 1);
        return new FlowProgram(Map.of("reading", DataType.Scalar.ANY), List.of(
            new FlowProgram.Invoke("check_reading", key, new FlowCallBindings(Map.of("reading", "reading"), Map.of("checked", "checked"))),
            new FlowProgram.Procedure("definition", key, Map.of("reading", DataType.Scalar.ANY), Map.of("checked", outcome(type)),
                Map.of("checked", "result"), FlowBlockRules.NONE, Optional.of(new AssemblySource("""
                    REG result OUTPUT checked
                    VALUE.CHECK result reading
                    """))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
    }
    @Test void anOrdinaryCustomBlockChecksDynamicIntegerWithoutConvertingTextErrorsOrNullToZero() {
        for (DataValue input : List.of(new DataValue.IntegerValue(0), new DataValue.IntegerValue(15),
                new DataValue.TextValue("15"), new DataValue.DecimalValue(15),
                new DataValue.ErrorValue("access_denied", "no snapshot"), DataValue.NullValue.INSTANCE)) {
            var host = new ComputerBlockHost(() -> {});
            host.installFlow(program(DataType.Scalar.INTEGER), new DataValue.RecordValue(Map.of("reading", input)), LIMITS, true);
            int work = 0;
            while (host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE) {
                assertTrue(++work < 100); host.step(LIMITS, true);
                assertNotEquals(FlowRuntime.Status.FAILED, host.flowSnapshot().orElseThrow().state().status(),
                    host.flowSnapshot().orElseThrow().state().diagnostic().toString());
            }
            var locals = host.flowSnapshot().orElseThrow().state().locals();
            var result = (DataValue.RecordValue)locals.field("checked");
            boolean valid = input instanceof DataValue.IntegerValue;
            assertEquals(new DataValue.BooleanValue(valid), result.field("ok"));
            assertEquals(valid ? input : DataValue.NullValue.INSTANCE, result.field("value"));
            assertEquals(input, locals.field("reading"), "Checking never changes the received value");
        }
    }
    @Test void nestedCollectionsRestoreEveryPaidStepAndPublishNoPartialResult() {
        var element = new DataType.RecordType(Map.of("temperature", DataType.Scalar.INTEGER, "label", DataType.Scalar.TEXT));
        var type = new DataType.RecordType(Map.of("items", new DataType.ListType(element),
            "options", new DataType.MapType(new DataType.OptionalType(DataType.Scalar.BOOLEAN))));
        var child = new LinkedHashMap<String, DataValue>();
        child.put("label", new DataValue.TextValue("long text is a tag check, not a conversion"));
        child.put("temperature", new DataValue.IntegerValue(15));
        var source = new DataValue.RecordValue(Map.of(
            "options", new DataValue.MapValue(Map.of("a", new DataValue.BooleanValue(true), "b", DataValue.NullValue.INSTANCE)),
            "items", new DataValue.ListValue(Collections.nCopies(4, new DataValue.RecordValue(child)))));
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program(type), new DataValue.RecordValue(Map.of("reading", source)), LIMITS, true);
        int work = 0, restored = 0;
        while (host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE) {
            assertTrue(++work < 1000); host.step(LIMITS, true);
            var saved = host.flowSnapshot().orElseThrow();
            assertNotEquals(FlowRuntime.Status.FAILED, saved.state().status(), saved.state().diagnostic().toString());
            if (saved.state().assembly().flatMap(RegisterState::comparison).isPresent()) {
                assertFalse(saved.state().locals().fields().containsKey("checked"), "No partially checked result");
                var archive = host.write(); var next = new ComputerBlockHost(() -> {});
                next.read(archive); next.tick(LIMITS, true);
                assertTrue(next.loaded(), next.diagnostic().toString());
                assertEquals(saved, next.flowSnapshot().orElseThrow());
                assertEquals(archive, next.write()); host = next; restored++;
            }
        }
        assertTrue(restored > 20, "Actual data traversal, not one unmetered accepts call");
        var result = (DataValue.RecordValue)host.flowSnapshot().orElseThrow().state().locals().field("checked");
        assertEquals(new DataValue.BooleanValue(true), result.field("ok")); assertEquals(source, result.field("value"));
    }
    @Test void checkCannotHideInsideAProcedureThatForbidsEvaluation() {
        var ordinary = program(DataType.Scalar.INTEGER); var steps = new ArrayList<>(ordinary.steps());
        var p = (FlowProgram.Procedure)steps.get(1);
        steps.set(1, new FlowProgram.Procedure(p.sourceId(), p.key(), p.inputs(), p.outputs(), p.returns(),
            new FlowBlockRules(Set.of("evaluate"), Set.of()), p.assembly()));
        var host = new ComputerBlockHost(() -> {});
        assertThrows(ComputationException.class, () -> host.installFlow(new FlowProgram(ordinary.inputs(), steps).withAssembly(),
            new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(1))), LIMITS, true));
    }
    @Test void malformedVersionWorkAndCursorCannotBeRestoredAsAPassedCheck() {
        var program = program(new DataType.ListType(DataType.Scalar.INTEGER));
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program,
            new DataValue.RecordValue(Map.of("reading", new DataValue.ListValue(Collections.nCopies(8, new DataValue.IntegerValue(1))))), LIMITS.hardware());
        while (runtime.checkpoint().assembly().orElseThrow().comparison().isEmpty()) runtime.advance(1);
        var before = runtime.checkpoint();
        var root = (DataValue.RecordValue)ValueNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(program, before), LIMITS.archive()), LIMITS.archive());
        var state = (DataValue.RecordValue)root.field("state"); var assembly = (DataValue.RecordValue)state.field("assembly");
        var cursor = (DataValue.RecordValue)assembly.field("comparison"); var progress = (DataValue.RecordValue)cursor.field("progress");
        for (var changed : List.of(
                patch(cursor, "version", new DataValue.IntegerValue(1)),
                patch(cursor, "version", new DataValue.IntegerValue(2)),
                patch(cursor, "version", new DataValue.IntegerValue(4)),
                patch(cursor, "paid", new DataValue.IntegerValue(2)),
                patch(cursor, "progress", patch(progress, "mismatch", new DataValue.BooleanValue(true))),
                patch(cursor, "progress", patch(progress, "frames", new DataValue.ListValue(List.of()))),
                patch(cursor, "progress", patch(progress, "index", new DataValue.IntegerValue(0))))) {
            var corrupted = ValueNbtCodec.encode(patch(root, "state", patch(state, "assembly", patch(assembly, "comparison", changed))), LIMITS.archive());
            assertThrows(RuntimeException.class, () -> {
                var decoded = FlowNbtCodec.decode(corrupted, LIMITS.archive()); FlowRuntime.restore(decoded.program(), decoded.state(), LIMITS.hardware()).load();
            });
        }
        assertFalse(before.locals().fields().containsKey("checked"));
    }
    @Test void ownerRejectsTemporaryMemoryPressureWithoutPublishingAndCanResumeOriginalArchive() {
        var p = program(new DataType.ListType(DataType.Scalar.INTEGER));
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(p, new DataValue.RecordValue(Map.of("reading", new DataValue.ListValue(Collections.nCopies(8, new DataValue.IntegerValue(1))))), LIMITS, true);
        while (host.flowSnapshot().orElseThrow().state().assembly().orElseThrow().comparison().isEmpty()) host.step(LIMITS, true);
        var saved = host.flowSnapshot().orElseThrow(); var raw = host.write();
        long retained = saved.state().controlUnits() + p.usage(LIMITS.hardware()).constantUnits();
        var tight = new ResourceLimits(256, retained + 9, 1);
        var runtime = FlowRuntime.restore(p, saved.state(), tight); runtime.load(); runtime.setPaused(false); runtime.advance(1);
        assertEquals(FlowRuntime.Status.FAILED, runtime.checkpoint().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, runtime.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(saved.state().assembly(), runtime.checkpoint().assembly());
        assertEquals(saved.state().locals(), runtime.checkpoint().locals());
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(LIMITS, true);
        assertTrue(restored.loaded(), restored.diagnostic().toString()); assertEquals(raw, restored.write());
        while (restored.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE) restored.step(LIMITS, true);
        assertEquals(new DataValue.BooleanValue(true), ((DataValue.RecordValue)restored.flowSnapshot().orElseThrow().state().locals().field("checked")).field("ok"));
    }
    static DataValue.RecordValue patch(DataValue.RecordValue value, String field, DataValue replacement) {
        var fields = new LinkedHashMap<>(value.fields()); fields.put(field, replacement); return new DataValue.RecordValue(fields);
    }
}
