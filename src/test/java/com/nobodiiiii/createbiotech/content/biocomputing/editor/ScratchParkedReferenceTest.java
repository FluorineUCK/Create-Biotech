package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.*;

class ScratchParkedReferenceTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void openClient() throws Exception { client.client(); }
    @AfterEach void closeClient() throws Exception { client.restore(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void locatingAUseExposesTheExactNestedReporterOnANarrowCanvas(int width) throws Exception {
        var key = new ModuleDefinition.Key("cb_extension:addon:meter:description", 8);
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        nodes.put("target", new ComputationGraph.ProcedureValue(key, "text", Map.of()));
        String last = "target";
        for (int i = 0; i < 7; i++) {
            String literal = "literal_" + i, next = "concat_" + i;
            nodes.put(literal, new ComputationGraph.Constant(new DataValue.TextValue("sample label " + i)));
            nodes.put(next, new ComputationGraph.Text(TextOperation.CONCAT, Map.of("left", literal, "right", last)));
            last = next;
        }
        var graph = new ComputationGraph(Map.of(), nodes, Map.of("value", last));
        var position = new ProgramDocument.Position(700, 400);
        var parked = new ScratchValueLayout.Reporter(position, graph, Map.of(), Map.of());
        var body = FlowDocumentBody.emptyAssembly();
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(body.edited(body.inputs(), body.steps(), body.initial(),
            body.canvas().withValues(ScratchValueLayout.EMPTY.withParked(List.of(parked))), body.inline())));
        var before = workspace.snapshot();
        var editor = FlowCallScreenTest.editor(workspace, width);
        var opened = new AtomicReference<Screen>();
        set(editor, "navigator", (Consumer<Screen>) opened::set);
        click(editor, "category_extensions");
        click(editor, "extension_library");
        var library = opened.get();
        FlowProcedureScreenTest.initializeForm(library, width);
        while (button(library, "palette_next").active) click(library, "palette_next");
        click(library, "extension_find_uses");
        var refs = opened.get();
        FlowProcedureScreenTest.initializeForm(refs, width);
        click(refs, "procedure_reference_parked");
        assertSame(editor, opened.get());
        assertEquals(before, workspace.snapshot());
        assertFalse(button(editor, "undo").active, "Locating does not add an undo entry");
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        var viewport = (CanvasViewport)get(canvas, "viewport");
        var font = (Font)get(canvas, "font");
        var tile = InlineGraphView.layout(graph, font::width);
        double x = position.x(), y = position.y();
        while (!"target".equals(tile.node())) {
            var right = tile.children().getLast();
            x += right.x();
            y += right.y();
            tile = right.tile();
        }
        double mouseX = (int)get(canvas, "x") + viewport.screenX(x + tile.width()/2d);
        double mouseY = (int)get(canvas, "y") + viewport.screenY(y + tile.height()/2d);
        var hit = canvas.parkedSlotHit(mouseX, mouseY);
        assertNotNull(hit, "The cited nested reporter must be inside the canvas and selectable");
        assertEquals("target", hit.slot().node(), "Focus must not stop at the parked root");
    }
}