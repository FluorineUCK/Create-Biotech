package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ScratchTextExtensionIntegrationTest {
    @ParameterizedTest @EnumSource(FlowProgram.Compilation.class)
    void directConcatCallArgumentUsesBoundedRestoreWithoutChangingLegacyMarkers(FlowProgram.Compilation policy) {
        var key = new ModuleDefinition.Key("identity", 1);
        var graph = new ComputationGraph(Map.of(), Map.of(
            "first", new ComputationGraph.Constant(new DataValue.TextValue("😀")),
            "second", new ComputationGraph.Constant(new DataValue.TextValue("完成")),
            "join", new ComputationGraph.Text(TextOperation.CONCAT, Map.of("left", "first", "right", "second"))), Map.of("value", "join"));
        var call = FlowInlineValues.operands(new FlowProgram.Invoke("copy", key, new FlowCallBindings(Map.of(), Map.of("result", "answer"))), "copy", Map.of());
        var procedure = new FlowProgram.Procedure("identity", key, Map.of("value", DataType.Scalar.TEXT),
            Map.of("result", DataType.Scalar.TEXT), Map.of("result", "value"));
        var canvas = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(), List.of(new FlowBlockLayout.Stack(
            new ProgramDocument.Position(400, 16), List.of(procedure, new FlowProgram.EndProcedure("identity_end")))));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(call), new DataValue.RecordValue(Map.of()), canvas,
            Map.of("copy", graph), FlowProgram.Execution.REGISTER_V1, policy)));
        var document = workspace.snapshot(); var program = document.flow().orElseThrow().program(document.archive());
        assertTrue(program.usesConstructionAssembly());
        assertTrue(program.usesBoundedAssemblyRestore(), "A newly supported graph concat must not use unbounded legacy restoration");
        var limits = StockApplicationDriverDocuments.hardware();
        var host = new ComputerBlockHost(() -> {});
        host.installProgram(PreparedProgram.prepare(document, limits), ComputerRuntime.MemoryPolicy.RESET, limits, true);
        for (int i = 0; i < 100; i++) {
            var before = host.flowSnapshot().orElseThrow();
            assertNotEquals(FlowRuntime.Status.FAILED, before.state().status(), before.state().diagnostic().toString());
            if (before.state().status() == FlowRuntime.Status.COMPLETE) {
                assertEquals(new DataValue.TextValue("😀完成"), program.displayLocals(before.state().assembly().orElseThrow()).field("answer"));
                return;
            }
            host.step(limits, true); var expected = host.flowSnapshot().orElseThrow(); var saved = host.write();
            host = new ComputerBlockHost(() -> {}); host.read(saved); host.tick(limits, true);
            assertTrue(host.loaded(), host.diagnostic().toString()); assertEquals(expected, host.flowSnapshot().orElseThrow());
        }
        fail("Direct concat argument did not return");
    }

    @ParameterizedTest @EnumSource(FlowProgram.Compilation.class)
    void nestedConcatAndExtensionReturnAfterEveryInstructionSaveWithoutPartialPublication(FlowProgram.Compilation policy) {
        var binding = ScratchExtensions.binding(new ModuleDefinition.Key("cb_extension:create_biotech:text:text_label", 1)).orElseThrow();
        var graph = new ComputationGraph(Map.of(), Map.of(
            "prefix", new ComputationGraph.Constant(new DataValue.TextValue("状态")),
            "first", new ComputationGraph.Constant(new DataValue.TextValue("😀")),
            "second", new ComputationGraph.Constant(new DataValue.TextValue("已连接")),
            "joined", new ComputationGraph.Text(TextOperation.CONCAT, Map.of("left", "first", "right", "second")),
            "label", new ComputationGraph.ProcedureValue(binding.key(), "text", Map.of("label", "prefix", "value", "joined"))),
            Map.of("value", "label"));
        var placeholder = new DataValue.TextValue("unset");
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("message", new ProgramDefinition.Cell(DataType.Scalar.TEXT, placeholder)), List.of()), Map.of());
        var step = new FlowProgram.Shared("write", "message", new FlowInput.Expression("write", "value", Map.of()), false);
        var canvas = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(), List.of(new FlowBlockLayout.Stack(
            new ProgramDocument.Position(400, 16), List.of(binding.template("format"), new FlowProgram.EndProcedure("format_end")))))
            .withExtensions(Map.of("create_biotech:text", 1L));
        var body = new FlowDocumentBody(Map.of(), List.of(step), new DataValue.RecordValue(Map.of()), canvas,
            Map.of("write", graph), FlowProgram.Execution.REGISTER_V1, policy);
        var document = new ProgramDocument(archive, Map.of(), Optional.empty(), Optional.of(body));
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var limits = StockApplicationDriverDocuments.hardware();
        var uninterrupted = new ComputerBlockHost(() -> {});
        uninterrupted.installProgram(PreparedProgram.prepare(document, limits), ComputerRuntime.MemoryPolicy.RESET, limits, true);
        var resumed = new ComputerBlockHost(() -> {}); resumed.read(uninterrupted.write()); resumed.tick(limits, true);
        int constructions = 0;
        for (int i = 0; i < 500; i++) {
            var expected = uninterrupted.flowSnapshot().orElseThrow();
            assertEquals(expected, resumed.flowSnapshot().orElseThrow());
            assertNotEquals(FlowRuntime.Status.FAILED, expected.state().status(), expected.state().diagnostic().toString());
            if (expected.state().assembly().orElseThrow().build().isPresent()) {
                constructions++;
                assertEquals(placeholder, expected.memory().cells().field("message"));
            }
            if (expected.state().status() == FlowRuntime.Status.COMPLETE) {
                assertTrue(constructions >= 10, "Inner join and both formatting joins perform paid work");
                assertEquals(new DataValue.TextValue("状态: 😀已连接"), expected.memory().cells().field("message"));
                return;
            }
            uninterrupted.step(limits, true); resumed.step(limits, true);
            var saved = resumed.write(); resumed = new ComputerBlockHost(() -> {}); resumed.read(saved); resumed.tick(limits, true);
            assertTrue(resumed.loaded(), resumed.diagnostic().toString());
        }
        fail("Text did not complete within its finite instruction budget");
    }
}
