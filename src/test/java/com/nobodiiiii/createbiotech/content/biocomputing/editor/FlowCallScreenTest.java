package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FlowCallScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { environment.client(); }
    @org.junit.jupiter.api.AfterEach void restoreClient() throws Exception { environment.restore(); }
    @ParameterizedTest @ValueSource(ints = {320, 426, 640})
    void playerCanBindParametersAndReturnsWithoutRenamingLocalsOrInstalling(int width) throws Exception {
        var workspace = workspace(false); var before = workspace.snapshot(); var editor = editor(workspace, width);
        click(editor, "add_evaluate"); boxes(editor).getFirst().setValue("increment"); click(editor, "call_bindings"); bounds(editor);
        assertEquals(3, boxes(editor).size());
        boxes(editor).get(0).setValue("counter"); boxes(editor).get(1).setValue(""); boxes(editor).get(2).setValue("counter");
        assertEquals(before, workspace.snapshot()); click(editor, "call_back"); bounds(editor); click(editor, "apply");
        var document = workspace.snapshot(); document.validate(new ResourceLimits(100, 100000, 1));
        var call = (FlowProgram.Evaluate) document.flow().orElseThrow().steps().getFirst();
        assertEquals(new FlowCallBindings(Map.of("a", "counter"), Map.of("sum", "counter")), call.bindings().orElseThrow());
        assertEquals(new DataValue.IntegerValue(16), ProgramDocumentCodec.encode(document).field("version"));
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), document.flow().orElseThrow().program(document.archive()),
            document.flow().orElseThrow().initial(), new ResourceLimits(100, 100000, 1));
        for (int i = 0; i < 40 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) runtime.advance(1);
        assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(42), runtime.checkpoint().locals().field("counter"));
        assertEquals(new DataValue.IntegerValue(2000), runtime.checkpoint().locals().field("sum"));
        click(editor, "edit"); click(editor, "call_bindings"); boxes(editor).getFirst().setValue("a"); click(editor, "call_back"); click(editor, "cancel");
        assertEquals(document, workspace.snapshot());
        click(editor, "undo"); assertEquals(before, workspace.snapshot()); click(editor, "redo"); assertEquals(document, workspace.snapshot());
        assertFalse((boolean) get(editor, "waiting")); bounds(editor);
    }

    @Test void legacyMappingIsOnlyMaterializedAfterReviewAndApplyingTheDraft() throws Exception {
        var workspace = workspace(true); var original = workspace.snapshot(); var editor = editor(workspace, 320);
        selectBlock(editor, 0); click(editor, "edit"); click(editor, "apply");
        assertEquals(original, workspace.snapshot(), "Opening/applying an untouched legacy call is not a migration");
        click(editor, "edit"); click(editor, "call_bindings");
        assertEquals(List.of("a", "echo", "sum"), boxes(editor).stream().map(net.minecraft.client.gui.components.EditBox::getValue).toList());
        click(editor, "call_back"); click(editor, "cancel"); assertEquals(original, workspace.snapshot());
        click(editor, "edit"); click(editor, "call_bindings"); click(editor, "call_back"); click(editor, "apply");
        var call = (FlowProgram.Evaluate)workspace.flow().orElseThrow().steps().getFirst();
        assertEquals(new FlowCallBindings(Map.of("a", "a"), Map.of("sum", "sum", "echo", "echo")), call.bindings().orElseThrow());
        assertEquals(original.archive(), workspace.snapshot().archive());
        click(editor, "undo"); assertEquals(original, workspace.snapshot());
    }

    static ComputerFlowScreen editor(ProgramWorkspace workspace, int width) throws Exception {
        var parent = screen(); var menu = readyMenu(parent);
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, ignored -> fail("Unexpected document replacement"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, width); return editor;
    }
    @Test void newCallsRequireVisibleReviewAndDuplicateReturnTargetsDoNotPartiallyChangeTheDraft() throws Exception {
        var workspace = workspace(false); var before = workspace.snapshot(); var editor = editor(workspace, 320);
        click(editor, "add_evaluate"); boxes(editor).getFirst().setValue("increment"); click(editor, "apply");
        assertEquals(before, workspace.snapshot()); assertNotNull(button(editor, "call_back"));
        boxes(editor).get(1).setValue("counter"); boxes(editor).get(2).setValue("counter");
        click(editor, "call_back"); click(editor, "apply"); assertEquals(before, workspace.snapshot());
        assertTrue((boolean)get(editor, "parameters"));
    }

    @ParameterizedTest @ValueSource(strings = {"en_us", "zh_cn"})
    void everyBindingIsReachableOnSmallScreensAndNewControlsAreLocalized(String locale) throws Exception {
        var old = net.minecraft.locale.Language.getInstance();
        try {
            var values = ComputerUiTranslationsTest.load(locale); net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(values));
            var workspace = workspace(false);
            for (int i = 0; i < 12; i++) workspace.entry("increment").output("extra_" + i, "in");
            var editor = editor(workspace, 320); click(editor, "add_evaluate"); boxes(editor).getFirst().setValue("increment");
            click(editor, "call_bindings"); var seen = new HashSet<String>(); int page = 0;
            while (true) {
                assertTrue(page++ < 10); bounds(editor);
                for (var child : editor.children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget)
                    assertFalse(widget.getMessage().getString().startsWith("create_biotech."));
                for (var box : boxes(editor)) { assertTrue(seen.add(box.getValue())); if (!box.getValue().equals("a")) box.setValue(""); }
                if (!button(editor, "next").active) break;
                click(editor, "next");
            }
            assertEquals(15, seen.size()); click(editor, "call_back"); click(editor, "apply");
            var call = (FlowProgram.Evaluate) workspace.flow().orElseThrow().steps().getFirst();
            assertEquals(Map.of("a", "a"), call.bindings().orElseThrow().arguments());
            assertTrue(call.bindings().orElseThrow().returns().isEmpty());
            workspace.snapshot().validate(new ResourceLimits(100, 100000, 1));
        } finally { net.minecraft.locale.Language.inject(old); }
    }
    static ProgramWorkspace workspace(boolean legacy) {
        var graph = new ComputationGraph(Map.of("a", DataType.Scalar.INTEGER), Map.of(
            "in", new ComputationGraph.Input("a"), "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "add", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "in", "one")), Map.of("sum", "add", "echo", "in"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
            Map.of("increment", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var initial = new DataValue.RecordValue(Map.of("counter", new DataValue.IntegerValue(41), "a", new DataValue.IntegerValue(1000), "sum", new DataValue.IntegerValue(2000)));
        return new ProgramWorkspace(new ProgramDocument(archive, Map.of(), Optional.empty(), Optional.of(new FlowDocumentBody(
            ((DataType.RecordType)DataType.typeOf(initial)).fields(), legacy ? List.of(new FlowProgram.Evaluate("call", "increment")) : List.of(), initial))));
    }
}
