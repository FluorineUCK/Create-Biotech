package com.nobodiiiii.createbiotech.content.biocomputing.computation;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.FoldProgramFixtures.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import org.junit.jupiter.api.Test;

class FoldGraphTest {
    static final ResourceLimits ROOMY = new ResourceLimits(10_000, 10_000_000, 1);
    @Test void sumEmptyAndLargeListsUseExactlySixPlusSixPerItemWorkAndResumeEveryStep() {
        for (int count : new int[]{0, 3, 300}) {
            long[] values = java.util.stream.LongStream.rangeClosed(1, count).toArray();
            var archive = archive(numbers(values), integer(10));
            var installed = archive.compile(ROOMY);
            var machine = run(installed, new DataValue.RecordValue(Map.of()), true);
            assertEquals(integer(10L + (long) count * (count + 1) / 2), machine.report().output().orElseThrow().field("result"));
            assertEquals(6L + 6L * count, machine.report().workUnits());
            assertEquals(run(installed, new DataValue.RecordValue(Map.of()), false).report(), machine.report());
            assertEquals(numbers(values), ((ComputationGraph.Constant) archive.program().graph().nodes().get("items")).value());
        }
    }
    @Test void orderedDecimalDigitsUseOrdinaryScalarModule() {
        var nodes = new LinkedHashMap<>(sum().nodes());
        nodes.put("ten", new ComputationGraph.Constant(integer(10)));
        nodes.put("times", new ComputationGraph.Scalar(ScalarOperation.MULTIPLY_INTEGER, Map.of("left", "total", "right", "ten")));
        nodes.put("next", new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER, Map.of("left", "times", "right", "item")));
        var module = new ComputationGraph(sum().inputs(), nodes, sum().outputs());
        assertEquals(integer(123), run(archive(numbers(1,2,3), integer(0), module).compile(ROOMY), new DataValue.RecordValue(Map.of()), true).report().output().orElseThrow().field("result"));
    }
    @Test void recordAccumulatorKeepsSumAndCount() {
        var type = new DataType.RecordType(Map.of("sum", INTEGER, "count", INTEGER));
        var module = new ComputationGraph(Map.of("item", INTEGER, "total", type), Map.of(
            "item", new ComputationGraph.Input("item"), "total", new ComputationGraph.Input("total"),
            "sum", new ComputationGraph.Field("total", "sum"), "count", new ComputationGraph.Field("total", "count"),
            "one", new ComputationGraph.Constant(integer(1)),
            "s", new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER, Map.of("left", "sum", "right", "item")),
            "c", new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER, Map.of("left", "count", "right", "one")),
            "next", new ComputationGraph.MakeRecord(Map.of("sum", "s", "count", "c"))), Map.of("next", "next"));
        var seed = new DataValue.RecordValue(Map.of("sum", integer(0), "count", integer(0)));
        assertEquals(new DataValue.RecordValue(Map.of("sum", integer(6), "count", integer(3))),
            run(archive(numbers(1,2,3), seed, module).compile(ROOMY), new DataValue.RecordValue(Map.of()), true).report().output().orElseThrow().field("result"));
    }
    @Test void constructorDefendsBindingsAndRejectsShadowedOrDuplicatePorts() {
        var captures = new LinkedHashMap<String,String>(); captures.put("capture", "value");
        var fold = new ComputationGraph.Fold("items", "seed", SUM, "item", "total", captures, "next");
        captures.clear();
        assertEquals(List.of("items", "seed", "value"), fold.dependencies());
        assertThrows(UnsupportedOperationException.class, () -> fold.captures().clear());
        assertThrows(ComputationException.class, () -> new ComputationGraph.Fold("items", "seed", SUM, "item", "item", Map.of(), "next"));
        for (String shadow : List.of("item", "total"))
            assertThrows(ComputationException.class, () -> new ComputationGraph.Fold("items", "seed", SUM, "item", "total", Map.of(shadow,"value"), "next"));
    }
    @Test void installationRejectsInvalidSourceSeedResultMissingModulesPurityAndRecursion() {
        assertCode(TYPE_MISMATCH, () -> archive(integer(1), integer(0)).compile(ROOMY));
        assertCode(TYPE_MISMATCH, () -> archive(numbers(), new DataValue.TextValue("bad")).compile(ROOMY));
        var bad = new ComputationGraph(sum().inputs(), Map.of("next", new ComputationGraph.Constant(new DataValue.TextValue("bad"))), sum().outputs());
        assertCode(TYPE_MISMATCH, () -> archive(numbers(), integer(0), bad).compile(ROOMY));
        var missing = new ComputationGraph(sum().inputs(), Map.of(), Map.of());
        assertCode(MISSING_REFERENCE, () -> archive(numbers(), integer(0), missing).compile(ROOMY));
        var impure = new ComputationGraph(sum().inputs(), Map.of("next", new ComputationGraph.ReadMemory("ram")), sum().outputs());
        assertCode(INVALID_GRAPH, () -> archive(numbers(), integer(0), impure).compile(ROOMY));
        var recursive = new ComputationGraph(sum().inputs(), Map.of("item", new ComputationGraph.Input("item"), "total", new ComputationGraph.Input("total"),
            "next", new ComputationGraph.Call(SUM, Map.of("item", "item", "total", "total"))), sum().outputs());
        assertCode(RECURSIVE_MODULE, () -> archive(numbers(), integer(0), recursive).compile(ROOMY));
        assertCode(PROGRAM_LIMIT, () -> archive(numbers(1), integer(0)).compile(new ResourceLimits(5, 100_000, 1)));
        assertCode(MEMORY_LIMIT, () -> archive(numbers(1,2,3), integer(0)).compile(new ResourceLimits(100, 2, 1)));
        var base = archive(numbers(1), integer(0));
        assertCode(UNKNOWN_MODULE, () -> new ProgramArchive(base.program(), Map.of()).compile(ROOMY));
    }
    @Test void anySourceAndInitialAreValidatedEvenForEmptyListAndFailuresAreAtomic() {
        var root = new ComputationGraph(Map.of("items", ANY, "seed", ANY), Map.of(
            "items", new ComputationGraph.Input("items"), "seed", new ComputationGraph.Input("seed"), "fold", fold(),
            "endpoint", new ComputationGraph.Constant(new DataValue.TextValue("factory:test")),
            "payload", new ComputationGraph.MakeRecord(Map.of("result", "fold"))), Map.of("result", "fold", "endpoint", "endpoint", "payload", "payload"));
        var program = new ProgramDefinition(root, Map.of("ram", new ProgramDefinition.Cell(INTEGER, integer(7))),
            List.of(new ProgramDefinition.Write("ram", "result")), List.of(new ProgramDefinition.Send("endpoint", "payload")), Map.of());
        var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(ROOMY, new ArchiveLimits(100_000, 1_000_000, 10_000_000)));
        for (var input : List.of(new DataValue.RecordValue(Map.of("items", numbers(), "seed", new DataValue.TextValue("bad"))),
            new DataValue.RecordValue(Map.of("items", integer(1), "seed", integer(0))))) {
            var host = new ComputerBlockHost(() -> {});
            host.install(new ProgramArchive(program, Map.of(SUM, new ModuleDefinition(SUM, sum()))), ComputerRuntime.MemoryPolicy.RESET, limits, true);
            host.begin(input, limits, true);
            for (int i = 0; host.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) { assertTrue(i < 100); host.step(limits, true); }
            assertEquals(TYPE_MISMATCH, host.snapshot().orElseThrow().machine().diagnostic().orElseThrow().code());
            assertEquals(integer(7), host.snapshot().orElseThrow().machine().memory().field("ram"));
            assertTrue(host.outbound(limits, true).isEmpty());
        }
    }
    @Test void dynamicReturnedValueIsValidatedAtFoldConsumer() {
        var module = new ComputationGraph(Map.of("item", ANY, "total", INTEGER), Map.of("next", new ComputationGraph.Input("item")), Map.of("next", "next"));
        var result = run(archive(new DataValue.ListValue(List.of(new DataValue.TextValue("bad"))), integer(0), module).compile(ROOMY), new DataValue.RecordValue(Map.of()), true).report();
        assertEquals(ComputationMachine.Status.FAILED, result.status());
        assertEquals(TYPE_MISMATCH, result.diagnostic().orElseThrow().code());
    }
    @Test void realHostSaveLoadAtEveryStepAndPausedStepRetainContinuation() {
        var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(ROOMY, new ArchiveLimits(100_000, 1_000_000, 10_000_000)));
        var host = new ComputerBlockHost(() -> {});
        host.install(archive(numbers(1,2,3), integer(0)), ComputerRuntime.MemoryPolicy.RESET, limits, true);
        host.begin(new DataValue.RecordValue(Map.of()), limits, true);
        boolean foldSeen = false, childSeen = false, returnedSeen = false;
        for (int i = 0; host.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100);
            for (var frame : host.snapshot().orElseThrow().machine().frames()) {
                if (frame instanceof MachineCheckpoint.FoldState fold) { foldSeen = true; returnedSeen |= fold.returned().isPresent(); }
                if (frame instanceof MachineCheckpoint.GraphState graph) childSeen |= graph.module().isPresent();
            }
            var restored = new ComputerBlockHost(() -> {}); restored.read(host.write());
            restored.step(limits, true); host.step(limits, true); assertEquals(host.snapshot(), restored.snapshot()); host = restored;
        }
        assertTrue(foldSeen && childSeen && returnedSeen);
        assertEquals(integer(6), host.snapshot().orElseThrow().machine().output().orElseThrow().field("result"));
        var installed = archive(numbers(1,2,3), integer(0)).compile(ROOMY);
        var machine = new ComputationMachine(installed); machine.begin(new DataValue.RecordValue(Map.of())); machine.setPaused(true);
        assertEquals(0, machine.tick().workUnits()); assertEquals(1, machine.step().workUnits());
        machine.reset(); assertEquals(ComputationMachine.Status.IDLE, machine.report().status());
    }
    @Test void nestedFoldBranchCallMapAndFilterResumeTogether() {
        var outer = new ModuleDefinition.Key("outer", 1);
        var wrapper = new ModuleDefinition.Key("wrapper", 1);
        var pipeline = new ModuleDefinition.Key("pipeline", 1);
        var identity = new ModuleDefinition.Key("identity", 1);
        var predicate = new ModuleDefinition.Key("predicate", 1);
        var inputs = Map.<String, DataType>of("item", new DataType.ListType(INTEGER), "total", INTEGER);
        var pipe = new ComputationGraph(inputs, Map.of(
            "item", new ComputationGraph.Input("item"), "total", new ComputationGraph.Input("total"),
            "filtered", new ComputationGraph.Iterate(ComputationGraph.Iteration.FILTER, "item", predicate, "item", Map.of(), "keep"),
            "mapped", new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP, "filtered", identity, "item", Map.of(), "value"),
            "next", new ComputationGraph.Fold("mapped", "total", SUM, "item", "total", Map.of(), "next")), Map.of("next", "next"));
        var call = new ComputationGraph(inputs, Map.of(
            "item", new ComputationGraph.Input("item"), "total", new ComputationGraph.Input("total"),
            "call", new ComputationGraph.Call(pipeline, Map.of("item", "item", "total", "total")),
            "next", new ComputationGraph.Field("call", "next")), Map.of("next", "next"));
        var branch = new ComputationGraph(inputs, Map.of(
            "item", new ComputationGraph.Input("item"), "total", new ComputationGraph.Input("total"),
            "yes", new ComputationGraph.Constant(new DataValue.BooleanValue(true)),
            "branch", new ComputationGraph.Branch("yes", wrapper, wrapper, Map.of("item", "item", "total", "total")),
            "next", new ComputationGraph.Field("branch", "next")), Map.of("next", "next"));
        var modules = Map.of(SUM, new ModuleDefinition(SUM, sum()), pipeline, new ModuleDefinition(pipeline, pipe),
            wrapper, new ModuleDefinition(wrapper, call), outer, new ModuleDefinition(outer, branch),
            identity, new ModuleDefinition(identity, new ComputationGraph(Map.of("item", INTEGER),
                Map.of("v", new ComputationGraph.Input("item")), Map.of("value", "v"))),
            predicate, new ModuleDefinition(predicate, new ComputationGraph(Map.of("item", INTEGER),
                Map.of("yes", new ComputationGraph.Constant(new DataValue.BooleanValue(true))), Map.of("keep", "yes"))));
        var root = new ComputationGraph(Map.of(), Map.of(
            "items", new ComputationGraph.Constant(new DataValue.ListValue(List.of(numbers(1,2), numbers(3)))),
            "seed", new ComputationGraph.Constant(integer(0)),
            "fold", new ComputationGraph.Fold("items", "seed", outer, "item", "total", Map.of(), "next")), Map.of("result", "fold"));
        var installed = new ProgramArchive(new ProgramDefinition(root, Map.of(), List.of()), modules).compile(ROOMY);
        var resumed = run(installed, new DataValue.RecordValue(Map.of()), true);
        assertEquals(integer(6), resumed.report().output().orElseThrow().field("result"));
        assertEquals(run(installed, new DataValue.RecordValue(Map.of()), false).report(), resumed.report());
    }
    static ComputationMachine run(ProgramCompiler.InstalledProgram installed, DataValue.RecordValue input, boolean restore) {
        var machine = new ComputationMachine(installed); machine.begin(input);
        for (int i = 0; machine.report().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 20_000);
            if (restore) machine = ComputationMachine.restore(installed, machine.checkpoint());
            long before = machine.report().workUnits(); machine.step(); assertEquals(before + 1, machine.report().workUnits());
        }
        return machine;
    }
    static void assertCode(ComputationException.Code code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ComputationException.class, action).code());
    }
}
