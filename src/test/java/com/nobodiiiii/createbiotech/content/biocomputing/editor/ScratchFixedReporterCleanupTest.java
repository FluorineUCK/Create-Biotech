package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowInput;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.glfw.GLFW;

class ScratchFixedReporterCleanupTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void start() throws Exception { client.client(); }
    @AfterEach void stop() throws Exception { client.restore(); }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void discardingLastEmbeddedExtensionReporterRestoresItsShadowAndReleasesItsDefinition(boolean toToolbox) throws Exception {
        var workspace = editorWorkspace();
        var screen = openAndSnap(workspace);
        var before = workspace.snapshot();
        var slot = embeddedReporter(screen, before);
        var fromX = slot.x() + 8;
        var fromY = slot.y() + slot.height() / 2;
        if (toToolbox) {
            var prototype = button(screen, "extension_text_label");
            var x = prototype.getX() + 8;
            var y = prototype.getY() + 8;
            assertTrue(screen.mouseClicked(fromX, fromY, 0));
            screen.mouseDragged(x, y, 0, -100, 0);
            assertTrue(screen.mouseReleased(x, y, 0));
        } else {
            assertTrue(screen.mouseClicked(fromX, fromY, 1));
            click(screen, "delete_reporter");
        }
        var changed = workspace.snapshot();
        var body = changed.flow().orElseThrow();
        assertTrue(body.canvas().values().parked().isEmpty());
        assertTrue(body.canvas().detached().isEmpty(), "No unused generated pink definition remains");
        assertEquals(Map.of("create_biotech:text", 1L), body.canvas().extensions());
        var graph = body.inline().get("store");
        assertEquals(new ComputationGraph.Constant(new DataValue.TextValue("shadow")), graph.nodes().get(graph.outputs().get("value")));
        assertEquals(changed, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(changed)));
        click(screen, "undo"); assertEquals(before, workspace.snapshot());
        click(screen, "redo"); assertEquals(changed, workspace.snapshot());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void parkingAnEmbeddedValueReleasesOnlyTheLastExecutableBinding(boolean anotherUse) throws Exception {
        var workspace = editorWorkspace();
        var baseline = workspace.snapshot();
        var screen = openAndSnap(workspace);
        if (anotherUse) {
            var draft = new FlowDraft(workspace.flow().orElseThrow());
            draft.append(new FlowProgram.Shared("other", "message", new FlowInput.Literal(new DataValue.TextValue("second")), false));
            draft.editInline("other", draft.snapshot().inline().get("store"),
                new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings(Map.of(), Map.of()));
            workspace.setFlow(Optional.of(draft.snapshot()));
            screen = FlowCallScreenTest.editor(workspace, 640);
        }
        var before = workspace.snapshot();
        com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.set(screen, "height", 460);
        screen.init();
        var slot = embeddedReporter(screen, before);
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        double x = (int)get(canvas, "x") + 22;
        double y = (int)get(canvas, "y") + (int)get(canvas, "height") - 34;
        assertNull(canvas.blockAt(x, y));
        assertTrue(screen.mouseClicked(slot.x() + 8, slot.y() + slot.height()/2.0, 0));
        screen.mouseDragged(x, y, 0, 80, 80);
        assertTrue(screen.mouseReleased(x, y, 0));

        var changed = workspace.snapshot();
        var body = changed.flow().orElseThrow();
        var graph = body.inline().get("store");
        assertEquals(new ComputationGraph.Constant(new DataValue.TextValue("shadow")), graph.nodes().get(graph.outputs().get("value")));
        var parked = body.canvas().values().parked().getFirst();
        assertInstanceOf(ComputationGraph.ProcedureValue.class, parked.graph().nodes().get(parked.graph().outputs().get("value")));
        assertEquals(anotherUse ? 1 : 0, body.canvas().detached().size(), "Keep a generated binding only while another use needs it");
        if (!anotherUse) {
            var pure = baseline.flow().orElseThrow().program(baseline.archive());
            var limits = new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(512, 65536, 16);
            assertEquals(pure.usage(limits), body.program(changed.archive()).usage(limits),
                "Detaching restores the code and constant budgets instead of leaving a hidden definition");
        }
        changed.validateExtensions();
        assertEquals(changed, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(changed)));
        click(screen, "undo");
        assertEquals(before, workspace.snapshot());
        click(screen, "redo");
        assertEquals(changed, workspace.snapshot());
    }

    @Test void cuttingAnExtensionReporterLeavesItsDefinitionAndPastingUsesItAgain() throws Exception {
        var workspace = editorWorkspace();
        var screen = openAndSnap(workspace);
        var before = workspace.snapshot();
        var slot = embeddedReporter(screen, before);
        assertTrue(screen.mouseClicked(slot.x() + 8, slot.y() + slot.height() / 2, 1));
        click(screen, "clipboard_cut");
        var after = workspace.flow().orElseThrow();
        assertEquals(before.flow().orElseThrow().canvas().detached(), after.canvas().detached());
        assertTrue(after.inline().values().stream().flatMap(g -> g.nodes().values().stream()).noneMatch(ComputationGraph.ProcedureValue.class::isInstance));
        assertTrue(screen.keyPressed(GLFW.GLFW_KEY_V, 0, GLFW.GLFW_MOD_CONTROL));
        var pasted = workspace.flow().orElseThrow();
        assertEquals(after.canvas().detached(), pasted.canvas().detached());
        assertEquals(1, pasted.canvas().values().parked().size());
        var parked = pasted.canvas().values().parked().getFirst().graph();
        assertInstanceOf(ComputationGraph.ProcedureValue.class, parked.nodes().get(parked.outputs().get("value")));
    }

    private static ProgramWorkspace editorWorkspace() {
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        var type = DataType.Scalar.TEXT;
        var shadow = new DataValue.TextValue("shadow");
        draft.setExtension("create_biotech:text", 1L);
        draft.append(new FlowProgram.Shared("store", "message", new FlowInput.Literal(shadow), false));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("message", new ProgramDefinition.Cell(type, shadow)), List.of(), List.of()), Map.of());
        return new ProgramWorkspace(new ProgramDocument(archive, Map.of(), Optional.empty(), Optional.of(draft.snapshot())));
    }

    private static ComputerFlowScreen openAndSnap(ProgramWorkspace workspace) throws Exception {
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "category_extensions");
        FlowPaletteScreenTest.search(screen).setValue("text_label");
        var prototype = button(screen, "extension_text_label");
        var slot = FlowInlineScreenTest.slot(screen, "\"shadow\"", 0);
        assertTrue(screen.mouseClicked(prototype.getX() + 8, prototype.getY() + 8, 0));
        screen.mouseDragged(slot.x() + 8, slot.y() + slot.height() / 2, 0, 40, 0);
        assertTrue(screen.mouseReleased(slot.x() + 8, slot.y() + slot.height() / 2, 0));
        assertEquals(1, workspace.flow().orElseThrow().canvas().detached().size());
        return screen;
    }

    private static FlowBlockCanvas.Slot embeddedReporter(ComputerFlowScreen screen, ProgramDocument document) throws Exception {
        var graph = document.flow().orElseThrow().inline().get("store");
        var node = graph.outputs().get("value");
        assertInstanceOf(ComputationGraph.ProcedureValue.class, graph.nodes().get(node));
        var canvas = (FlowBlockCanvas)get(screen, "canvas");
        return canvas.inlineSlots().stream().filter(s -> !s.target() && s.node().equals(node)).findFirst().orElseThrow();
    }

    private static CreateTextButton button(ComputerFlowScreen screen, String suffix) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents key && key.getKey().endsWith("." + suffix))
            .findFirst().orElseThrow();
    }

    private static void click(ComputerFlowScreen screen, String suffix) {
        var button = button(screen, suffix);
        assertTrue(button.active, suffix);
        screen.mouseClicked(button.getX() + 6, button.getY() + 6, 0);
        screen.mouseReleased(button.getX() + 6, button.getY() + 6, 0);
    }
}
