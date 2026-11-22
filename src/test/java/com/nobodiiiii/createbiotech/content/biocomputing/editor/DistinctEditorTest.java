package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.*;

class DistinctEditorTest {
    private final ComputerProgramScreenTest fixture = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { fixture.client(); }
    @AfterEach void restore() throws Exception { fixture.restore(); }
    @Test void publicDataPaletteHasOneSourceNoUnusedParameterAndPreservesTypedDraft() throws Exception {
        assertEquals(ComputerNodePalette.Category.DATA, ComputerNodePalette.Kind.DISTINCT.category());
        var screen = screen(); click(screen, "add"); selectPaletteKind(screen, "distinct"); click(screen, "configure");
        var workspace = (ProgramWorkspace)get(screen, "workspace"); var draft = workspace.root(); var node = draft.nodes().getFirst();
        assertInstanceOf(ComputationGraph.Distinct.class, node.definition()); assertEquals(Set.of("source"), node.ports());
        assertFalse(((EditBox)get(screen, "parameter")).visible);
        String source = draft.add(new ComputationGraph.Constant(new DataValue.ListValue(List.of(new DataValue.IntegerValue(3), new DataValue.IntegerValue(1), new DataValue.IntegerValue(3)))), 10, 20);
        String wrong = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(1)), 20, 30);
        draft.connect(node.id(), "source", source); draft.output("result", node.id());
        var hints = new NodeTypeHints(workspace, draft, false, null);
        assertEquals(new DataType.ListType(DataType.Scalar.ANY), hints.input(node.id(), "source"));
        assertEquals(new DataType.ListType(DataType.Scalar.INTEGER), hints.output(node.id()));
        assertThrows(ComputationException.class, () -> hints.check(node.id(), "source", wrong));
        var document = workspace.snapshot(); var decoded = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document));
        assertEquals(document, decoded); assertEquals(draft.node(node.id()).connections(), new ProgramDraft(decoded.archive()).node(node.id()).connections());
        var machine = new ComputationMachine(decoded.archive().compile(new ResourceLimits(100, 10000, 1))); machine.begin(new DataValue.RecordValue(Map.of()));
        for (int i = 0; machine.report().status() == ComputationMachine.Status.RUNNING; i++) { assertTrue(i < 80); machine.tick(); }
        assertEquals(new DataValue.ListValue(List.of(new DataValue.IntegerValue(3), new DataValue.IntegerValue(1))), machine.report().output().orElseThrow().field("result"));
        assertFalse((boolean)get(screen, "requested")); assertNull(NodeParameter.kind(node.definition()));
    }
    @Test void bothLanguagesExplainStableTypedEqualityAndNetworkSchemaAdvances() throws Exception {
        for (String language : List.of("en_us", "zh_cn")) {
            var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/create_biotech/lang/" + language + ".json"))).getAsJsonObject();
            assertTrue(json.has("create_biotech.biocomputing.editor.kind.distinct"));
            String hint = json.get("create_biotech.biocomputing.editor.distinct_hint").getAsString(); assertTrue(hint.contains("source"));
        }
        assertNetworkVersionAtLeast(75);
    }
    private static void click(ComputerProgramScreen screen, String key) {
        screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance)
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast)
            .filter(button -> button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text && text.getKey().equals("create_biotech.biocomputing.editor." + key))
            .findFirst().orElseThrow().onClick(0, 0);
    }
}
