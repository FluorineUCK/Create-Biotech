package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowCompilationReuseTest {
    private static final ResourceLimits ROOMY = new ResourceLimits(1000, 100_000, 32);
    private static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());

    @Test void varyingRuntimeMemoryAndCpuAllowancesReuseOnlyImmutableCode() {
        var program = new FlowProgram(Map.of("delay", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Sleep("wait", "delay"))).withAssembly();
        var first = program.compile(new ResourceLimits(100, 100_000, 32));
        for (int i = 1; i <= 32; i++) {
            var next = program.compile(new ResourceLimits(100, 100_000 - i, i));
            assertSame(first.assembly().orElseThrow(), next.assembly().orElseThrow(),
                "Changing a continuation's allowance must not rebuild immutable code and control-flow analysis");
            assertEquals(100_000 - i, next.expressions().limits().memoryUnits(),
                "Reuse code, not the previous caller's resource permission");
        }
    }

    @Test void warmAdmissionMatchesColdChecksForLiteralsCellsAndCode() {
        var empty = new ComputationGraph(Map.of(), Map.of(), Map.of());
        var archive = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(
            new ProgramDefinition(empty, Map.of("counter", new ProgramDefinition.Cell(
                DataType.Scalar.INTEGER, new DataValue.IntegerValue(7))), List.of()), Map.of());
        var program = new FlowProgram(Map.of(), List.of(
            new FlowProgram.Repeat("repeat", new FlowInput.Literal(new DataValue.IntegerValue(3))),
            new FlowProgram.EndRepeat("end")), archive).withAssembly();
        var compiled = program.compile(ROOMY);
        long floor = FlowProgram.inputConstantUnits(program.steps()) + compiled.expressions().usage().constantUnits()
            + 2 * compiled.expressions().usage().initialMemoryUnits();
        for (long memory : new long[] {0, floor - 1, floor, floor + 1}) {
            var limits = new ResourceLimits(ROOMY.programUnits(), memory, 1);
            if (memory < floor) {
                var cold = assertThrows(ComputationException.class, () -> program.compileRegister(limits, Optional.empty()));
                var warm = assertThrows(ComputationException.class, () -> program.compile(limits));
                assertEquals(cold.code(), warm.code()); assertEquals(cold.location(), warm.location());
                assertEquals(cold.getMessage(), warm.getMessage());
            } else {
                var cold = program.compileRegister(limits, Optional.empty());
                var warm = program.compile(limits);
                assertEquals(cold.expressions().limits(), warm.expressions().limits());
                assertEquals(cold.assembly().orElseThrow().begin(EMPTY), warm.assembly().orElseThrow().begin(EMPTY));
            }
        }
        var tooSmall = new ResourceLimits(compiled.assembly().orElseThrow().codeWords() - 1, 100_000, 1);
        assertEquals(ComputationException.Code.PROGRAM_LIMIT,
            assertThrows(ComputationException.class, () -> program.compile(tooSmall)).code());
    }

    @Test void taskRootsAndChangedPinnedBodiesNeverReuseAnotherExecutionImage() {
        var first = FlowAuthoredDevicesTest.program("REG value INTEGER\nCONST value 7\nRET");
        var main = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), first, EMPTY, ROOMY);
        var child = FlowRuntime.startProcedure(UUID.randomUUID(), UUID.randomUUID(), first, FlowAuthoredDevicesTest.KEY, EMPTY, ROOMY);
        assertNotEquals(main.checkpoint().assembly().orElseThrow().image(), child.checkpoint().assembly().orElseThrow().image());
        var changed = FlowAuthoredDevicesTest.program("REG value INTEGER\nCONST value 8\nRET");
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,
            assertThrows(ComputationException.class, () -> FlowRuntime.restore(changed, main.checkpoint(), ROOMY)).code());
        main.advance(32); child.advance(32);
        assertEquals(new DataValue.IntegerValue(7), main.checkpoint().locals().field("answer"));
        assertEquals(new DataValue.IntegerValue(7), child.checkpoint().locals().field("result"));
        var other = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), changed, EMPTY, ROOMY);
        other.advance(32); assertEquals(new DataValue.IntegerValue(8), other.checkpoint().locals().field("answer"));
    }

    @Test void cachedCodeDoesNotExemptRestoredPrivateStateFromCurrentRamLimit() {
        var program = new FlowProgram(Map.of("delay", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.Sleep("wait", "delay"))).withAssembly();
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program,
            new DataValue.RecordValue(Map.of("delay", new DataValue.IntegerValue(20))), ROOMY);
        runtime.advance(1);
        long exact = runtime.retainedUnits();
        assertEquals(runtime.checkpoint(), FlowRuntime.restore(program, runtime.checkpoint(),
            new ResourceLimits(ROOMY.programUnits(), exact, 1)).checkpoint());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, assertThrows(ComputationException.class,
            () -> FlowRuntime.restore(program, runtime.checkpoint(), new ResourceLimits(ROOMY.programUnits(), exact - 1, 1))).code());
    }

    @Test void evictingCompilerMetadataDoesNotChangeSavedIdentityOrExecutionCost() {
        var program = FlowAuthoredDevicesTest.program("REG value INTEGER\nCONST value 7\nRET");
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, EMPTY, ROOMY);
        runtime.advance(1); var saved = runtime.checkpoint();
        var retained = new ArrayList<FlowProgram>();
        for (int i = 0; i < 80; i++) {
            var next = FlowAuthoredDevicesTest.program("REG value INTEGER\nCONST value " + (100 + i) + "\nRET");
            retained.add(next); next.validate(ROOMY);
        }
        var restored = FlowRuntime.restore(program, saved, ROOMY);
        assertEquals(saved, restored.checkpoint());
        restored.load(); restored.advance(32); runtime.advance(32);
        assertEquals(runtime.checkpoint(), restored.checkpoint());
        assertEquals(80, retained.size());
    }
}
