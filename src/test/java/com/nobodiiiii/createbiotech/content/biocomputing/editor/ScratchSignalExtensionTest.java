package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ScratchSignalExtensionTest {
    private static final ComputerHostLimits LIMITS = new ComputerHostLimits(
        new ResourceLimits(1024, 65536, 1), new ArchiveLimits(100000, 1000000, 1000000));

    private static ProgramDocument document(String opcode, FlowProgram.Compilation policy) {
        var binding = ScratchExtensions.binding(new ModuleDefinition.Key("cb_extension:create_biotech:signals:" + opcode, 1)).orElseThrow();
        var result = binding.command().outputs().keySet().iterator().next();
        var graph = new ComputationGraph(Map.of(), Map.of("sample", new ComputationGraph.ProcedureValue(binding.key(), result, Map.of())),
            Map.of("result", "sample"));
        var call = new FlowProgram.Evaluate("sample", "inline", new FlowCallBindings(Map.of(), Map.of("result", "answer")));
        var canvas = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(), List.of(new FlowBlockLayout.Stack(
            new ProgramDocument.Position(400, 16), List.of(binding.template("fixed"), new FlowProgram.EndProcedure("fixed_end")))))
            .withExtensions(Map.of("create_biotech:signals", 1L));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(call), new DataValue.RecordValue(Map.of()),
            canvas, Map.of("sample", graph), FlowProgram.Execution.REGISTER_V1, policy)));
        return workspace.snapshot();
    }

    @ParameterizedTest @EnumSource(FlowProgram.Compilation.class)
    void reportersReadOnlySampledPinsAndResumeEveryInstructionOnBasicHardware(FlowProgram.Compilation policy) {
        assertFalse(LIMITS.isa1a()); assertFalse(LIMITS.isa1b());
        for (String opcode : List.of("simple_level", "xbus_busy")) for (int level : List.of(0, 12, 15)) {
            var document = document(opcode, policy);
            assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
            var prepared = PreparedProgram.prepare(document, LIMITS);
            var program = document.flow().orElseThrow().program(document.archive());
            var host = new ComputerBlockHost(() -> {});
            host.installProgram(prepared, ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
            var sample = Map.of("p0", level, "x0", level == 15 ? 15 : 0);
            boolean complete = false;
            for (int i = 0; i < 80; i++) {
                var before = host.flowSnapshot().orElseThrow();
                host.tick(LIMITS, true, sample);
                assertEquals(before, host.flowSnapshot().orElseThrow(), "Paused machines must not sample or advance");
                assertTrue(before.receipts().isEmpty()); assertTrue(before.pinClaims().entries().isEmpty());
                assertTrue(before.state().signals().outputs().isEmpty(), "Reading status must not overwrite SIO outputs");
                assertNotEquals(FlowRuntime.Status.FAILED, before.state().status(), before.state().diagnostic().toString());
                if (before.state().status() == FlowRuntime.Status.COMPLETE) {
                    DataValue expected = opcode.equals("simple_level") ? new DataValue.IntegerValue(level) : new DataValue.BooleanValue(level == 15);
                    assertEquals(expected, program.displayLocals(before.state().assembly().orElseThrow()).field("answer"));
                    assertTrue(before.state().work() > 1, "Call, sample and result copy remain paid ISA operations");
                    complete = true; break;
                }
                host.step(LIMITS, true, sample);
                var after = host.flowSnapshot().orElseThrow();
                var saved = host.write();
                host = new ComputerBlockHost(() -> {}); host.read(saved); host.tick(LIMITS, true, sample);
                assertTrue(host.loaded(), host.diagnostic().toString());
                assertEquals(after, host.flowSnapshot().orElseThrow());
            }
            assertTrue(complete);
        }
    }

    @ParameterizedTest @EnumSource(FlowProgram.Compilation.class)
    void unavailableSioSampleIsAFaultNotAZeroReading(FlowProgram.Compilation policy) {
        var document = document("simple_level", policy);
        var host = new ComputerBlockHost(() -> {});
        host.installProgram(PreparedProgram.prepare(document, LIMITS), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true);
        for (int i = 0; i < 80 && host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.FAILED; i++)
            host.step(LIMITS, true, Map.of());
        var failed = host.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.FAILED, failed.state().status());
        assertTrue(failed.state().diagnostic().isPresent());
        assertTrue(failed.receipts().isEmpty()); assertTrue(failed.pinClaims().entries().isEmpty());
    }
}
