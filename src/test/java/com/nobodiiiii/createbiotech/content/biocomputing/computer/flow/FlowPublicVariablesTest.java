package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowPublicVariablesTest {
    static DataValue.RecordValue obj(Object... pairs) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) fields.put((String)pairs[i], pairs[i + 1] instanceof DataValue v ? v
            : pairs[i + 1] instanceof Boolean b ? new DataValue.BooleanValue(b) : new DataValue.TextValue((String)pairs[i + 1]));
        return new DataValue.RecordValue(fields);
    }
    static FlowProgram sharedProgram(List<DataValue> steps) {
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))), List.of()), Map.of());
        var encoded = (DataValue.RecordValue)FlowProgramCodec.encode(new FlowProgram(Map.of("counter", DataType.Scalar.INTEGER), List.of(), archive).withAssembly());
        var fields = new LinkedHashMap<>(encoded.fields()); fields.put("steps", new DataValue.ListValue(steps));
        return FlowProgramCodec.decode(new DataValue.RecordValue(fields));
    }
    static DataValue shared(String source, boolean add, long value) {
        return obj("kind", "shared", "source", source, "cell", "counter", "add", add,
            "value", obj("kind", "literal", "value", new DataValue.IntegerValue(value)));
    }
    @Test void publicChangePublishesOnceAtOwnerInstructionBoundariesWithoutTouchingSameNamedPrivateLocal() {
        var program = sharedProgram(List.of(shared("change", true, 1)));
        var limits = FlowAssemblyTasksTest.LIMITS;
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(program, obj("counter", new DataValue.IntegerValue(90)), limits, true);
        for (int i = 0; i < 8 && owner.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) {
            owner.step(limits, true); owner = FlowAssemblyTasksTest.restored(owner);
        }
        var saved = owner.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, saved.state().status());
        assertEquals(new DataValue.IntegerValue(1), saved.memory().cells().field("counter"));
        assertEquals(new DataValue.IntegerValue(90), saved.state().locals().field("counter"));
        assertTrue(saved.memory().owners().isEmpty());
    }

    static FlowProgram roundTrip(FlowProgram program) { return FlowProgramCodec.decode(FlowProgramCodec.encode(program)); }
    static FlowProgram withSteps(List<FlowProgram.Step> steps) {
        var base = sharedProgram(List.of());
        return roundTrip(new FlowProgram(base.inputs(), steps, base.expressions()).withAssembly());
    }
    static ComputerBlockHost install(FlowProgram program) {
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(program, obj("counter", new DataValue.IntegerValue(90)), FlowAssemblyTasksTest.LIMITS, true);
        return owner;
    }
    @Test void twoManagedTasksChangeOneCounterWithoutLostUpdatesOrPrivateParameterOverwrite() {
        var key = new ModuleDefinition.Key("increment", 1);
        var signature = Map.<String, DataType>of("counter", DataType.Scalar.INTEGER);
        var owner = install(withSteps(List.of(new FlowProgram.Sleep("main_wait", "counter"),
            new FlowProgram.Procedure("definition", key, signature, signature, Map.of("counter", "counter")),
            new FlowProgram.Shared("increment", "counter", new FlowInput.Literal(new DataValue.IntegerValue(1)), true),
            new FlowProgram.EndProcedure("return"))));
        var limits = FlowAssemblyTasksTest.LIMITS; owner.step(limits, true);
        var main = owner.flowSnapshot().orElseThrow().state();
        var a = owner.startFlowTask(key, obj("counter", new DataValue.IntegerValue(11)), limits, true);
        var b = owner.startFlowTask(key, obj("counter", new DataValue.IntegerValue(22)), limits, true);
        for (int i = 0; i < 16 && (owner.flowTask(a).orElseThrow().status() != FlowRuntime.Status.COMPLETE
                || owner.flowTask(b).orElseThrow().status() != FlowRuntime.Status.COMPLETE); i++) {
            owner.step(limits, true); owner = FlowAssemblyTasksTest.restored(owner);
        }
        assertEquals(main, owner.flowSnapshot().orElseThrow().state());
        assertEquals(new DataValue.IntegerValue(11), owner.flowTask(a).orElseThrow().locals().field("counter"));
        assertEquals(new DataValue.IntegerValue(22), owner.flowTask(b).orElseThrow().locals().field("counter"));
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowTask(a).orElseThrow().status());
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowTask(b).orElseThrow().status());
        assertEquals(new DataValue.IntegerValue(2), owner.flowSnapshot().orElseThrow().memory().cells().field("counter"));
        assertTrue(owner.flowSnapshot().orElseThrow().memory().owners().isEmpty());
    }
    static ProgramArchive reporterArchive() {
        var base = sharedProgram(List.of()).expressions();
        var graph = new ComputationGraph(Map.of(), Map.of("read", new ComputationGraph.ReadMemory("counter"),
            "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "sum", new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER, Map.of("left", "read", "right", "one"))), Map.of("value", "sum"));
        var p = base.program();
        return new ProgramArchive(new ProgramDefinition(p.graph(), p.cells(), List.of(), List.of(),
            Map.of("plus_one", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
    }
    @Test void arithmeticPublicReporterReadsCurrentBankWithoutTransactionOrSameNameBinding() {
        var program = roundTrip(new FlowProgram(Map.of("counter", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Shared("set", "counter", new FlowInput.Literal(new DataValue.IntegerValue(8)), false),
            new FlowProgram.Evaluate("read", "plus_one", new FlowCallBindings(Map.of(), Map.of("value", "result")))), reporterArchive()).withAssembly());
        var owner = install(program); var limits = FlowAssemblyTasksTest.LIMITS;
        for (int i = 0; i < 15 && owner.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) {
            owner.step(limits, true); owner = FlowAssemblyTasksTest.restored(owner);
        }
        var saved = owner.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, saved.state().status());
        assertEquals(new DataValue.IntegerValue(9), saved.state().locals().field("result"));
        assertEquals(new DataValue.IntegerValue(90), saved.state().locals().field("counter"));
        assertEquals(new DataValue.IntegerValue(8), saved.memory().cells().field("counter"));
        assertTrue(saved.memory().owners().isEmpty());
    }
    @Test void staticPublicTypeAndUnknownCellErrorsNeverFallBackToLegacyExecution() {
        var limits = FlowAssemblyTasksTest.LIMITS.hardware();
        var base = sharedProgram(List.of());
        for (var step : List.of(new FlowProgram.Shared("unknown", "missing", new FlowInput.Literal(new DataValue.IntegerValue(1)), true),
            new FlowProgram.Shared("wrong", "counter", new FlowInput.Literal(new DataValue.DecimalValue(1)), false))) {
            var error = assertThrows(ComputationException.class, () -> withSteps(List.of(step)).validate(limits));
            assertEquals(step.sourceId().equals("unknown") ? ComputationException.Code.MISSING_REFERENCE : ComputationException.Code.TYPE_MISMATCH, error.code());
        }
        var legacy = new FlowProgram(base.inputs(), List.of(new FlowProgram.Shared("legacy", "counter", new FlowInput.Local("counter"), false)), base.expressions());
        assertEquals(ComputationException.Code.INVALID_GRAPH, assertThrows(ComputationException.class, () -> legacy.validate(limits)).code());
        var reader = new FlowProgram(base.inputs(), List.of(new FlowProgram.Evaluate("read", "plus_one")), reporterArchive());
        assertEquals(ComputationException.Code.INVALID_GRAPH, assertThrows(ComputationException.class, () -> reader.validate(limits)).code());
    }
    @Test void atomicRejectsDirectAndIndirectPublicReadersAndWritesAtInstallation() {
        var key = new ModuleDefinition.Key("read_public", 1);
        var calls = List.<FlowProgram.Step>of(new FlowProgram.Shared("change", "counter", new FlowInput.Local("counter"), true),
            new FlowProgram.Evaluate("read", "plus_one", new FlowCallBindings(Map.of(), Map.of("value", "result"))),
            new FlowProgram.Repeat("repeat", new FlowInput.Expression("plus_one", "value", Map.of())));
        for (var call : calls) {
            var steps = new ArrayList<FlowProgram.Step>(List.of(new FlowProgram.Atomic("atomic", "result"), call));
            if (call instanceof FlowProgram.Repeat) steps.add(new FlowProgram.EndRepeat("end_repeat"));
            steps.add(new FlowProgram.EndAtomic("end_atomic"));
            var program = new FlowProgram(Map.of("counter", DataType.Scalar.INTEGER), steps, reporterArchive()).withAssembly();
            assertEquals(ComputationException.Code.INVALID_GRAPH, assertThrows(ComputationException.class,
                () -> program.validate(FlowAssemblyTasksTest.LIMITS.hardware())).code());
        }
        var program = new FlowProgram(Map.of("counter", DataType.Scalar.INTEGER), List.of(new FlowProgram.Atomic("atomic", "result"),
            new FlowProgram.Invoke("invoke", key, new FlowCallBindings(Map.of(), Map.of())), new FlowProgram.EndAtomic("end_atomic"),
            new FlowProgram.Procedure("definition", key, Map.of(), Map.of(), Map.of()),
            new FlowProgram.Evaluate("read", "plus_one", new FlowCallBindings(Map.of(), Map.of("value", "result"))),
            new FlowProgram.EndProcedure("return")), reporterArchive()).withAssembly();
        assertEquals(ComputationException.Code.INVALID_GRAPH, assertThrows(ComputationException.class,
            () -> program.validate(FlowAssemblyTasksTest.LIMITS.hardware())).code());
    }
    static DataValue.RecordValue replace(DataValue.RecordValue value, String key, DataValue replacement) {
        var fields = new LinkedHashMap<>(value.fields()); fields.put(key, replacement); return new DataValue.RecordValue(fields);
    }
    @Test void ownerAndSnapshotDowngradesPreserveOpaquePreInstructionSaveWithoutExecuting() {
        var owner = install(sharedProgram(List.of(shared("change", true, 1))));
        var limits = FlowAssemblyTasksTest.LIMITS;
        for (boolean snapshot : List.of(false, true)) {
            var raw = (net.minecraft.nbt.CompoundTag)owner.write();
            assertEquals(29, raw.getInt(FlowBlockNbtCodec.MARKER));
            if (snapshot) {
                var data = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), limits.archive());
                var flow = (DataValue.RecordValue)data.field("flow");
                assertEquals(new DataValue.IntegerValue(30), flow.field("version"));
                raw.put("Data", ValueNbtCodec.encode(replace(data, "flow", replace(flow, "version", new DataValue.IntegerValue(29))), limits.archive()));
            } else raw.putInt(FlowBlockNbtCodec.MARKER, 28);
            var rejected = new ComputerBlockHost(() -> {}); rejected.read(raw); rejected.tick(limits, true);
            assertFalse(rejected.loaded()); assertEquals(raw, rejected.write()); assertTrue(rejected.diagnostic().isPresent());
        }
    }

    static ComputerBlockHost typedStoreOwner(DataValue initial, DataValue payload, DataType type) {
        var graph = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var archive = new ProgramArchive(new ProgramDefinition(graph,
            Map.of("typed", new ProgramDefinition.Cell(type, initial)), List.of()), Map.of());
        var program = roundTrip(new FlowProgram(Map.of("payload", type), List.of(new FlowProgram.Shared("store", "typed", new FlowInput.Local("payload"), false)), archive).withAssembly());
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(program, obj("payload", payload), FlowAssemblyTasksTest.LIMITS, true); return owner;
    }
    @Test void publicStoreSupportsStructuredValuesThroughProductionArchiveWithoutTransaction() {
        var type = new DataType.RecordType(Map.of("items", new DataType.ListType(DataType.Scalar.INTEGER)));
        var empty = obj("items", new DataValue.ListValue(List.of()));
        var payload = obj("items", new DataValue.ListValue(List.of(new DataValue.IntegerValue(3), new DataValue.IntegerValue(5))));
        var owner = typedStoreOwner(empty, payload, type);
        owner.step(FlowAssemblyTasksTest.LIMITS, true); owner = FlowAssemblyTasksTest.restored(owner);
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowSnapshot().orElseThrow().state().status());
        assertEquals(payload, owner.flowSnapshot().orElseThrow().memory().cells().field("typed"));
        assertEquals(payload, owner.flowSnapshot().orElseThrow().state().locals().field("payload"));
        assertTrue(owner.flowSnapshot().orElseThrow().memory().owners().isEmpty());
        var p = owner.flowSnapshot().orElseThrow().program();
        var bad = new FlowProgram(p.inputs(), List.of(new FlowProgram.Shared("change", "typed", new FlowInput.Local("payload"), true)), p.expressions()).withAssembly();
        assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class,
            () -> bad.validate(FlowAssemblyTasksTest.LIMITS.hardware())).code());
    }
    @Test void typedStoreRamFailureCannotPublishBankOrAdvanceItsProgramCounter() {
        var owner = typedStoreOwner(new DataValue.TextValue(""), new DataValue.TextValue("x".repeat(512)), DataType.Scalar.TEXT);
        var limits = FlowAssemblyTasksTest.LIMITS;
        var before = owner.flowSnapshot().orElseThrow(); var raw = owner.write(); owner.step(limits, true);
        var after = owner.flowSnapshot().orElseThrow(); assertTrue(FlowAssemblyMemoryTest.ram(after) > FlowAssemblyMemoryTest.ram(before));
        var tight = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            new ResourceLimits(limits.hardware().programUnits(), FlowAssemblyMemoryTest.ram(after) - 1, 1),
            limits.archive(), limits.mailbox(), limits.pins(), limits.serial(), limits.taskSlots());
        var limited = new ComputerBlockHost(() -> {}); limited.read(raw); limited.tick(tight, true); assertTrue(limited.loaded());
        limited.step(tight, true);
        var rejected = limited.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.FAILED, rejected.state().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, rejected.state().diagnostic().orElseThrow().code());
        assertEquals(before.memory(), rejected.memory());
        assertEquals(before.state().assembly().orElseThrow().pc(), rejected.state().assembly().orElseThrow().pc());
    }
    @Test void typedStoreArchiveFailureRetainsWholeOldSaveAndResumesExactlyOnceWhenCapacityReturns() {
        var owner = typedStoreOwner(new DataValue.TextValue(""), new DataValue.TextValue("x".repeat(512)), DataType.Scalar.TEXT);
        var limits = FlowAssemblyTasksTest.LIMITS;
        var raw = (net.minecraft.nbt.CompoundTag)owner.write(); owner.step(limits, true);
        var completed = (net.minecraft.nbt.CompoundTag)owner.write();
        long capacity = ValueNbtCodec.decode(completed.getCompound("Data"), limits.archive()).storageUnits() - 1;
        assertTrue(ValueNbtCodec.decode(raw.getCompound("Data"), limits.archive()).storageUnits() <= capacity);
        var tight = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(limits.hardware(),
            new ArchiveLimits(100_000, 1_000_000, capacity), limits.mailbox(), limits.pins(), limits.serial(), limits.taskSlots());
        var limited = new ComputerBlockHost(() -> {}); limited.read(raw); limited.tick(tight, true); assertTrue(limited.loaded());
        assertThrows(ComputerBlockHost.HostException.class, () -> limited.step(tight, true));
        assertEquals(raw, limited.write()); assertFalse(limited.loaded()); assertTrue(limited.diagnostic().isPresent());
        var recovered = new ComputerBlockHost(() -> {}); recovered.read(limited.write()); recovered.tick(limits, true); recovered.step(limits, true);
        assertEquals(owner.flowSnapshot().orElseThrow(), recovered.flowSnapshot().orElseThrow());
    }

    @Test void publicStoreExpressionArgumentsMustBeExplicitInsteadOfSameNamedBinding() {
        var base = sharedProgram(List.of());
        var graph = new ComputationGraph(Map.of("counter", DataType.Scalar.INTEGER), Map.of("in", new ComputationGraph.Input("counter")), Map.of("value", "in"));
        var archive = new ProgramArchive(new ProgramDefinition(base.expressions().program().graph(), base.expressions().program().cells(),
            List.of(), List.of(), Map.of("identity", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(base.inputs(), List.of(new FlowProgram.Shared("store", "counter", new FlowInput.Expression("identity", "value"), false)), archive).withAssembly();
        assertEquals(ComputationException.Code.MISSING_REFERENCE, assertThrows(ComputationException.class,
            () -> program.validate(FlowAssemblyTasksTest.LIMITS.hardware())).code());
    }

    @Test void decimalChangeAndExplicitLocalValueUseDeclaredCellType() {
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("counter", new ProgramDefinition.Cell(DataType.Scalar.DECIMAL, new DataValue.DecimalValue(0.5))), List.of()), Map.of());
        var graph = new ComputationGraph(Map.of("parameter", DataType.Scalar.DECIMAL), Map.of("in", new ComputationGraph.Input("parameter")), Map.of("value", "in"));
        var p = archive.program();
        archive = new ProgramArchive(new ProgramDefinition(p.graph(), p.cells(), List.of(), List.of(),
            Map.of("identity", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = roundTrip(new FlowProgram(Map.of("counter", DataType.Scalar.DECIMAL), List.of(
            new FlowProgram.Shared("change", "counter", new FlowInput.Expression("identity", "value", Map.of("parameter", "counter")), true)), archive).withAssembly());
        var limits = FlowAssemblyTasksTest.LIMITS;
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(program, obj("counter", new DataValue.DecimalValue(1.25)), limits, true);
        for (int i = 0; i < 4 && owner.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) {
            owner.step(limits, true); owner = FlowAssemblyTasksTest.restored(owner);
        }
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowSnapshot().orElseThrow().state().status());
        assertEquals(new DataValue.DecimalValue(1.75), owner.flowSnapshot().orElseThrow().memory().cells().field("counter"));
        assertEquals(new DataValue.DecimalValue(1.25), owner.flowSnapshot().orElseThrow().state().locals().field("counter"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"any_integer", "optional_integer", "optional_null"})
    void safePublicSetWideningAcceptsLiteralLocalAndExplicitExpressionThroughOwnerCodec(String shape) {
        DataType cellType = shape.equals("any_integer") ? DataType.Scalar.ANY : new DataType.OptionalType(DataType.Scalar.INTEGER);
        DataValue payload = shape.equals("optional_null") ? DataValue.NullValue.INSTANCE : new DataValue.IntegerValue(7);
        DataType actual = DataType.typeOf(payload);
        var cells = Map.of("literal_cell", new ProgramDefinition.Cell(cellType, DataValue.NullValue.INSTANCE),
            "local_cell", new ProgramDefinition.Cell(cellType, DataValue.NullValue.INSTANCE),
            "expression_cell", new ProgramDefinition.Cell(cellType, DataValue.NullValue.INSTANCE));
        var expression = new ComputationGraph(Map.of("argument", actual), Map.of("in", new ComputationGraph.Input("argument")), Map.of("value", "in"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), cells,
            List.of(), List.of(), Map.of("identity", new ProgramDefinition.Entry(expression, List.of(), List.of()))), Map.of());
        var program = roundTrip(new FlowProgram(Map.of("payload", actual), List.of(
            new FlowProgram.Shared("literal", "literal_cell", new FlowInput.Literal(payload), false),
            new FlowProgram.Shared("local", "local_cell", new FlowInput.Local("payload"), false),
            new FlowProgram.Shared("expression", "expression_cell", new FlowInput.Expression("identity", "value", Map.of("argument", "payload")), false)), archive).withAssembly());
        var limits = shape.equals("any_integer")
            ? com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture.withMasterParser(FlowAssemblyTasksTest.LIMITS)
            : FlowAssemblyTasksTest.LIMITS;
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(program, obj("payload", payload), limits, true);
        for (int i = 0; i < 16 && owner.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) {
            owner.step(limits, true);
            var restored = new ComputerBlockHost(() -> {}); restored.read(owner.write()); restored.tick(limits, true);
            assertTrue(restored.loaded(), restored.diagnostic().toString());
            assertEquals(owner.flowSnapshot().orElseThrow(), restored.flowSnapshot().orElseThrow()); owner = restored;
        }
        var saved = owner.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, saved.state().status());
        for (var cell : cells.keySet()) assertEquals(payload, saved.memory().cells().field(cell));
        assertEquals(payload, saved.state().locals().field("payload"));
        assertTrue(saved.memory().owners().isEmpty());
    }

    @Test void publicSetDoesNotTurnDynamicOrOptionalSourcesIntoUncheckedNarrowingAndAddStaysExact() {
        for (DataType actual : List.of(DataType.Scalar.ANY, new DataType.OptionalType(DataType.Scalar.INTEGER), DataType.Scalar.DECIMAL)) {
            DataValue payload = actual == DataType.Scalar.DECIMAL ? new DataValue.DecimalValue(7) : new DataValue.IntegerValue(7);
            var base = sharedProgram(List.of());
            for (boolean add : List.of(false, true)) {
                var program = roundTrip(new FlowProgram(Map.of("payload", actual), List.of(
                    new FlowProgram.Shared("store", "counter", new FlowInput.Local("payload"), add)), base.expressions()).withAssembly());
                var owner = new ComputerBlockHost(() -> {});
                assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class,
                    () -> owner.installFlow(program, obj("payload", payload), FlowAssemblyTasksTest.LIMITS, true)).code());
                assertTrue(owner.flowSnapshot().isEmpty());
            }
        }
    }
}
