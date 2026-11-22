package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class ScratchCallOrderScreenTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void start() throws Exception { client.client(); }
    @AfterEach void stop() throws Exception { client.restore(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void declarationOrderControlsBothCallsWithoutRebindingTheirTypedValues(boolean labelled) throws Exception {
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        var params = new LinkedHashMap<String, DataType>();
        params.put("z_guard", DataType.Scalar.BOOLEAN);
        params.put("a_guard", DataType.Scalar.BOOLEAN);
        params.put("amount", DataType.Scalar.INTEGER);
        var key = new ModuleDefinition.Key("quantity", 1);
        var definition = new FlowProgram.Procedure("define", key, params,
            Map.of("result", DataType.Scalar.BOOLEAN), Map.of("result", "z_guard"));
        FlowProcedureTools.define(draft, definition, false);
        if (labelled) draft.setProcedureLabels("define", List.of(new ScratchProcedureLabel(1, "only when")));
        var prototype = FlowProcedureTools.prototype(draft, definition);
        draft.place(List.of(prototype.call()), new FlowDraft.Socket(null, 0), FlowBlockLayout.DEFAULT.main(), prototype.graphs());
        var evaluate = new FlowProgram.Evaluate("check", "check", new FlowCallBindings(Map.of(), Map.of("value", "done")));
        var graph = new ComputationGraph(Map.of(), Map.of(
            "a", new ComputationGraph.Constant(new DataValue.BooleanValue(false)),
            "z", new ComputationGraph.Constant(new DataValue.BooleanValue(true)),
            "n", new ComputationGraph.Constant(new DataValue.IntegerValue(19)),
            "value", new ComputationGraph.ProcedureValue(key, "result", Map.of("a_guard", "a", "z_guard", "z", "amount", "n"))),
            Map.of("value", "value"));
        draft.place(List.of(evaluate), new FlowDraft.Socket(null, 1), FlowBlockLayout.DEFAULT.main(), Map.of("check", graph));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(draft.snapshot()));
        var document = workspace.snapshot();

        for (var opened : List.of(workspace, new ProgramWorkspace(ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document))))) {
            var screen = FlowCallScreenTest.editor(opened, 640);
            var canvas = (FlowBlockCanvas)get(screen, "canvas");
            var slots = canvas.inlineSlots();
            var argumentNodes = prototype.graphs().get(prototype.call().sourceId()).outputs();
            assertAll(
                () -> assertEquals(List.of(argumentNodes.get("z_guard"), argumentNodes.get("a_guard"), argumentNodes.get("amount")),
                    slots.stream().filter(s -> prototype.call().sourceId().equals(s.call())).map(FlowBlockCanvas.Slot::node).toList()),
                () -> assertEquals(List.of("z", "a", "n"),
                    slots.stream().filter(s -> "check".equals(s.call()) && s.node() != null && Set.of("z", "a", "n").contains(s.node()))
                        .map(FlowBlockCanvas.Slot::node).toList()),
                () -> assertEquals(document, opened.snapshot(), "Rendering must not rewrite the named bindings"));
        }
        var caption = (TranslatableContents)FlowBlockCatalog.label(new InlineGraphEdits.ProcedureResult(definition, "result")).getContents();
        assertEquals(" (z_guard, a_guard, amount)", caption.getArgs()[3]);
    }
}
