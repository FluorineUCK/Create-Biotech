package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class KeysEditorTest {
    private final ComputerProgramScreenTest fixture = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { fixture.client(); }
    @AfterEach void restore() throws Exception { fixture.restore(); }

    @Test void publicNodeHasOnlyASourcePortAndPersistsItsTypedListOutputAndLayout() throws Exception {
        assertNetworkVersionAtLeast(79);
        var screen = screen(); click(screen, "add"); selectPaletteKind(screen, "keys"); click(screen, "configure");
        var workspace = (ProgramWorkspace)get(screen, "workspace"); var draft = workspace.root(); var node = draft.nodes().getFirst();
        assertEquals(new ComputationGraph.Keys("source"), node.definition());
        assertEquals(List.of("source"), List.copyOf(node.ports()));
        assertFalse(((EditBox)get(screen, "parameter")).visible);
        String source = draft.add(new ComputationGraph.Constant(new DataValue.RecordValue(Map.of("route.east", DataValue.NullValue.INSTANCE))), 10, 20);
        String wrong = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(0)), 10, 180);
        draft.connect(node.id(), "source", source); draft.output("keys", node.id());
        var hints = new NodeTypeHints(workspace, draft, false, null);
        assertNull(hints.input(node.id(), "source"));
        assertThrows(ComputationException.class, () -> hints.check(node.id(), "source", wrong));
        assertEquals(new DataType.ListType(DataType.Scalar.TEXT), hints.output(node.id()));
        var doc = workspace.snapshot(); var decoded = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(doc)); assertEquals(doc, decoded);
        assertEquals(draft.node(node.id()).connections(), new ProgramDraft(decoded.archive()).node(node.id()).connections());
        var machine = new ComputationMachine(decoded.archive().compile(new ResourceLimits(100, 10000, 1)));
        machine.begin(new DataValue.RecordValue(Map.of()));
        for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) { assertTrue(guard < 40); machine.tick(); }
        assertEquals(new DataValue.ListValue(List.of(new DataValue.TextValue("route.east"))), machine.report().output().orElseThrow().field("keys"));
        assertFalse((boolean)get(screen, "requested")); assertNull(NodeParameter.kind(node.definition()));
    }

    private static void click(ComputerProgramScreen screen, String key) {
        screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(button -> button.getMessage().getContents() instanceof TranslatableContents text
                && text.getKey().equals("create_biotech.biocomputing.editor." + key)).findFirst().orElseThrow().onClick(0, 0);
    }
}
