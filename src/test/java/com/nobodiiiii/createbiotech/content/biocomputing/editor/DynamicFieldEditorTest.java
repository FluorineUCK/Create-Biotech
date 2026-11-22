package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class DynamicFieldEditorTest {
    private final ComputerProgramScreenTest fixture = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { fixture.client(); }
    @AfterEach void restore() throws Exception { fixture.restore(); }

    @Test void publicPaletteWiresAComputedNameWithoutFixedParameterAndPreservesDocumentLayout() throws Exception {
        var screen = screen(); click(screen, "add"); selectPaletteKind(screen, "dynamic_field"); click(screen, "configure");
        var workspace = (ProgramWorkspace)get(screen, "workspace"); var draft = workspace.root(); var node = draft.nodes().getFirst();
        assertEquals(new ComputationGraph.DynamicField("source", "name"), node.definition());
        assertEquals(List.of("source", "name"), List.copyOf(node.ports()));
        assertFalse(((EditBox)get(screen, "parameter")).visible);
        var value = new DataValue.RecordValue(Map.of("route.east", new DataValue.IntegerValue(7)));
        String source = draft.add(new ComputationGraph.Constant(value), 10, 20);
        String text = draft.add(new ComputationGraph.Constant(new DataValue.TextValue("route.east")), 10, 100);
        String wrong = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(0)), 10, 180);
        draft.connect(node.id(), "source", source); draft.connect(node.id(), "name", text); draft.output("chosen", node.id());
        var hints = new NodeTypeHints(workspace, draft, false, null);
        assertNull(hints.input(node.id(), "source"), "Do not fabricate a Record-or-Map union type");
        assertEquals(DataType.Scalar.TEXT, hints.input(node.id(), "name"));
        assertThrows(ComputationException.class, () -> hints.check(node.id(), "name", wrong));
        assertThrows(ComputationException.class, () -> hints.check(node.id(), "source", wrong));
        assertEquals(DataType.Scalar.ANY, hints.output(node.id()));
        var doc = workspace.snapshot(); var decoded = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(doc));
        assertEquals(doc, decoded);
        assertEquals(draft.node(node.id()).connections(), new ProgramDraft(decoded.archive()).node(node.id()).connections());
        var machine = new ComputationMachine(decoded.archive().compile(new ResourceLimits(100, 10000, 1)));
        machine.begin(new DataValue.RecordValue(Map.of()));
        for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) { assertTrue(guard < 40); machine.tick(); }
        assertEquals(new DataValue.IntegerValue(7), machine.report().output().orElseThrow().field("chosen"));
        assertFalse((boolean)get(screen, "requested")); assertNull(NodeParameter.kind(node.definition()));
    }

    private static void click(ComputerProgramScreen screen, String key) {
        screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(button -> button.getMessage().getContents() instanceof TranslatableContents text
                && text.getKey().equals("create_biotech.biocomputing.editor." + key)).findFirst().orElseThrow().onClick(0, 0);
    }
}
