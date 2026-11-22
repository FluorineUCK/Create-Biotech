package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerAssemblyHostTest {
    @Test void preLoopIsaPlayerSaveKeepsItsImageAndContinuesWithoutReinstalling() throws Exception {
        // Exact owner NBT from the A1 player's normally saved PC-1 checkpoint, before loop support.
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/biocomputing/assembly-a1-paused-owner.snbt"))) {
            var raw = net.minecraft.nbt.TagParser.parseTag(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var host = new ComputerBlockHost(() -> {}); host.read(raw); host.tick(LIMITS, true);
            assertTrue(host.loaded()); assertEquals(raw, host.write(), "Do not rewrite an existing image or cursor during load");
            var before = host.flowSnapshot().orElseThrow().state();
            assertEquals(1, before.work()); assertEquals(1, before.assembly().orElseThrow().pc()); assertTrue(before.paused());
            for (int i = 0; i < 20 && host.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.READY; i++) host.step(LIMITS, true);
            var done = host.flowSnapshot().orElseThrow().state();
            assertEquals(FlowRuntime.Status.COMPLETE, done.status()); assertEquals(9, done.work());
            assertEquals(new DataValue.IntegerValue(20), done.locals().field("answer"));
            assertEquals(before.assembly().orElseThrow().image(), done.assembly().orElseThrow().image());
            assertEquals(before.flow(), done.flow()); assertEquals(before.computer(), done.computer());
        }
    }

    @Test void failingArithmeticRetainsThePreInstructionRegistersAndSurvivesReloadWithoutRepeating() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(calculator().withAssembly(), new DataValue.RecordValue(Map.of(
            "x", new DataValue.IntegerValue(3), "take", new DataValue.BooleanValue(false))), LIMITS, true);
        host.setPaused(false, LIMITS, true);
        FlowRuntime.Checkpoint before = host.flowSnapshot().orElseThrow().state();
        for (int i = 0; i < 20 && host.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.READY; i++) {
            before = host.flowSnapshot().orElseThrow().state(); host.tick(LIMITS, true);
        }
        var failed = host.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.FAILED, failed.state().status());
        assertEquals(ComputationException.Code.DIVISION_BY_ZERO, failed.state().diagnostic().orElseThrow().code());
        assertEquals("no", failed.state().diagnostic().orElseThrow().location());
        assertEquals(before.assembly(), failed.state().assembly());
        assertEquals(before.work() + 1, failed.state().work());
        assertFalse(failed.state().locals().fields().containsKey("answer"));
        var restored = new ComputerBlockHost(() -> {}); restored.read(host.write()); restored.tick(LIMITS, true);
        assertTrue(restored.loaded()); assertEquals(failed, restored.flowSnapshot().orElseThrow());
        restored.tick(LIMITS, true); assertEquals(failed, restored.flowSnapshot().orElseThrow());
    }

    @Test void codeCapacityIsInstructionWordsAndRejectedInstallsKeepTheOldProgram() {
        var host = new ComputerBlockHost(() -> {});
        var inputs = new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(3), "take", new DataValue.BooleanValue(true)));
        host.installFlow(calculator(), inputs, LIMITS, true); var old = host.write();
        var small = new ComputerHostLimits(new ResourceLimits(32, 100000, 1), LIMITS.archive());
        assertEquals(ComputationException.Code.PROGRAM_LIMIT, assertThrows(ComputationException.class,
            () -> host.installFlow(calculator().withAssembly(), inputs, small, true)).code());
        assertEquals(old, host.write());
        var exact = new ComputerHostLimits(new ResourceLimits(33, 100000, 1), LIMITS.archive());
        host.installFlow(calculator().withAssembly(), inputs, exact, true);
        assertTrue(host.flowSnapshot().orElseThrow().state().assembly().isPresent());
        var installed = host.write();
        // Correlation is now native; retain the rejection guard using genuinely unsupported text work.
        var unsupportedGraph = new ComputationGraph(Map.of(), Map.of(
            "left", new ComputationGraph.Constant(new DataValue.TextValue("left")),
            "right", new ComputationGraph.Constant(new DataValue.TextValue("right")),
            "concat", new ComputationGraph.Text(TextOperation.CONCAT, Map.of("left", "left", "right", "right"))), Map.of("value", "concat"));
        var unsupportedArchive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of(), List.of(), List.of(), Map.of("concat", new ProgramDefinition.Entry(unsupportedGraph, List.of(), List.of()))), Map.of());
        var unsupportedFlow = new FlowProgram(Map.of(), List.of(new FlowProgram.Evaluate("concat", "concat",
            new FlowCallBindings(Map.of(), Map.of("value", "value")))), unsupportedArchive).withAssembly();
        assertEquals(ComputationException.Code.INVALID_GRAPH, assertThrows(ComputationException.class, () -> host.installFlow(
            unsupportedFlow, new DataValue.RecordValue(Map.of()), LIMITS, true)).code());
        assertEquals(installed, host.write());
    }

    @Test void reducedMemorySuspendsWithoutHalfPublishingRegistersThenResumesWhenHardwareReturns() {
        var host = new ComputerBlockHost(() -> {});
        var program = calculator().withAssembly();
        host.installFlow(program, new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(3), "take", new DataValue.BooleanValue(true))), LIMITS, true);
        host.step(LIMITS, true); // The branch itself allocates no register; the next Move does.
        var before = host.flowSnapshot().orElseThrow(); var raw = host.write();
        long memory = before.state().controlUnits() + before.initialInputs().storageUnits() + before.control().storageUnits()
            + program.usage(LIMITS.hardware()).constantUnits();
        var tight = new ComputerHostLimits(new ResourceLimits(1000, memory, 1), LIMITS.archive());
        assertThrows(RuntimeException.class, () -> host.step(tight, true));
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
        assertEquals(raw, host.write()); assertEquals(before, host.flowSnapshot().orElseThrow());
        host.tick(LIMITS, true); assertTrue(host.loaded()); assertEquals(before, host.flowSnapshot().orElseThrow());
        host.step(LIMITS, true); assertEquals(before.state().work() + 1, host.flowSnapshot().orElseThrow().state().work());
        var other = new ComputerBlockHost(() -> {}); other.read(raw); other.tick(LIMITS, true);
        assertEquals(before, other.flowSnapshot().orElseThrow(), "Another independently owned checkpoint gains no work or RAM");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"image", "pc", "type", "owner_version", "flow_version"})
    void malformedRegisterImagesAndVersionDowngradesRetainTheRawSave(String mutation) {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(calculator().withAssembly(), new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(3), "take", new DataValue.BooleanValue(true))), LIMITS, true);
        var raw = (CompoundTag)host.write();
        var owner = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
        var flow = (DataValue.RecordValue)owner.field("flow"); var state = (DataValue.RecordValue)flow.field("state");
        var assembly = (DataValue.RecordValue)state.field("assembly");
        switch (mutation) {
            case "image" -> assembly = patch(assembly, "image", new DataValue.TextValue("0".repeat(64)));
            case "pc" -> assembly = patch(assembly, "pc", new DataValue.IntegerValue(999));
            case "type" -> {
                var registers = (DataValue.RecordValue)assembly.field("registers");
                assembly = patch(assembly, "registers", patch(registers, "0", new DataValue.TextValue("not a register boolean")));
            }
            case "owner_version" -> raw.putInt(FlowBlockNbtCodec.MARKER, 19);
            case "flow_version" -> flow = patch(flow, "version", new DataValue.IntegerValue(21));
        }
        raw.put("Data", ValueNbtCodec.encode(patch(owner, "flow", patch(flow, "state", patch(state, "assembly", assembly))), LIMITS.archive()));
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(LIMITS, true);
        assertFalse(restored.loaded()); assertEquals(ComputerBlockHost.Code.INVALID_SAVE, restored.diagnostic().orElseThrow().code());
        assertEquals(raw, restored.write()); assertTrue(restored.snapshot().isEmpty());
    }

    private static DataValue.RecordValue patch(DataValue.RecordValue value, String key, DataValue replacement) {
        var fields = new LinkedHashMap<>(value.fields()); fields.put(key, replacement); return new DataValue.RecordValue(fields);
    }
    @Test void aFailedInstructionMustHaveConsumedACycleAndCorruptSavesNeverFallBackToGraphs() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(calculator().withAssembly(), new DataValue.RecordValue(Map.of(
            "x", new DataValue.IntegerValue(3), "take", new DataValue.BooleanValue(false))), LIMITS, true);
        var raw = (CompoundTag)host.write();
        var owner = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
        var flow = (DataValue.RecordValue)owner.field("flow"); var state = (DataValue.RecordValue)flow.field("state");
        var changed = new LinkedHashMap<>(state.fields());
        changed.put("status", new DataValue.TextValue("FAILED"));
        changed.put("diagnostic", new DataValue.RecordValue(Map.of("code", new DataValue.TextValue("DIVISION_BY_ZERO"),
            "source", new DataValue.TextValue("choose"), "message", new DataValue.TextValue("not executed"))));
        var flowFields = new LinkedHashMap<>(flow.fields()); flowFields.put("state", new DataValue.RecordValue(changed));
        var ownerFields = new LinkedHashMap<>(owner.fields()); ownerFields.put("flow", new DataValue.RecordValue(flowFields));
        raw.put("Data", ValueNbtCodec.encode(new DataValue.RecordValue(ownerFields), LIMITS.archive()));
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(LIMITS, true);
        assertFalse(restored.loaded(), "Failure before the first instruction cannot have spent zero cycles");
        assertTrue(restored.diagnostic().isPresent());
        assertEquals(raw, restored.write(), "Invalid state must remain recoverable, not get reinstalled or reset");
        assertTrue(restored.snapshot().isEmpty(), "Invalid assembly is never handed to the graph runtime");
    }
    @Test void actualOwnerExecutesRegisterInstructionsAndRestoresEveryInstructionWithoutEnteringTheGraphMachine() {
        var host = new ComputerBlockHost(() -> {});
        var source = calculator().withAssembly();
        var inputs = new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(3), "take", new DataValue.BooleanValue(true)));
        host.installFlow(source, inputs, LIMITS, true);
        assertTrue(host.flowSnapshot().orElseThrow().state().assembly().isPresent());
        assertTrue(host.flowSnapshot().orElseThrow().state().paused());
        for (int i = 0; i < 40; i++) {
            var before = host.flowSnapshot().orElseThrow();
            if (before.state().status() == FlowRuntime.Status.COMPLETE) break;
            assertEquals(FlowRuntime.Status.READY, before.state().status());
            host.setPaused(true, LIMITS, true);
            var paused = host.flowSnapshot().orElseThrow();
            host.tick(LIMITS, true); assertEquals(paused, host.flowSnapshot().orElseThrow());
            var replacement = new ComputerBlockHost(() -> {}); replacement.read(host.write()); replacement.tick(LIMITS, true);
            assertEquals(paused, replacement.flowSnapshot().orElseThrow()); host = replacement;
            host.setPaused(false, LIMITS, true); host.tick(LIMITS, true);
            var next = host.flowSnapshot().orElseThrow().state();
            assertEquals(before.state().work() + 1, next.work(), "One granted cycle executes one bounded primitive instruction");
            assertTrue(next.expression().isEmpty(), "A register instruction must not delegate to a hidden graph execution");
        }
        var done = host.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, done.state().status());
        assertEquals(new DataValue.IntegerValue(10), done.state().locals().field("answer"));
        assertEquals(inputs, done.initialInputs());
        assertTrue(done.receipts().isEmpty());
    }

    static FlowProgram calculator() {
        var add = new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), Map.of(
            "input", new ComputationGraph.Input("value"), "seven", new ComputationGraph.Constant(new DataValue.IntegerValue(7)),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "input", "seven")), Map.of("result", "sum"));
        var divide = new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), Map.of(
            "input", new ComputationGraph.Input("value"), "zero", new ComputationGraph.Constant(new DataValue.IntegerValue(0)),
            "divide", new ComputationGraph.Scalar(ScalarOperation.DIVIDE_INTEGER, Map.of("left", "input", "right", "zero"))), Map.of("result", "divide"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
            Map.of("add", new ProgramDefinition.Entry(add, List.of(), List.of()), "divide", new ProgramDefinition.Entry(divide, List.of(), List.of()))), Map.of());
        var binding = new FlowCallBindings(Map.of("value", "x"), Map.of("result", "answer"));
        return new FlowProgram(Map.of("x", DataType.Scalar.INTEGER, "take", DataType.Scalar.BOOLEAN), List.of(
            new FlowProgram.If("choose", "take"), new FlowProgram.Evaluate("yes", "add", binding),
            new FlowProgram.Otherwise("else"), new FlowProgram.Evaluate("no", "divide", binding), new FlowProgram.EndIf("end")), archive);
    }
}
