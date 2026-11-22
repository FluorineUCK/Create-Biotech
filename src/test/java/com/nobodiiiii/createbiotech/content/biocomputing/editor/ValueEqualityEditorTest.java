package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.*;

class ValueEqualityEditorTest {
    private final ComputerProgramScreenTest fixture = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { fixture.client(); }
    @AfterEach void restore() throws Exception { fixture.restore(); }
    @Test void publicDataPaletteHasTwoAnyPortsNoUnusedParameterAndPreservesTypedDraft() throws Exception {
        assertEquals(ComputerNodePalette.Category.DATA, ComputerNodePalette.Kind.VALUE_EQUALS.category());
        var screen = screen(); click(screen, "add"); selectPaletteKind(screen, "value_equals"); click(screen, "configure");
        var workspace = (ProgramWorkspace)get(screen, "workspace"); var draft = workspace.root(); var node = draft.nodes().getFirst();
        assertInstanceOf(ComputationGraph.ValueEquals.class, node.definition()); assertEquals(Set.of("left", "right"), node.ports());
        assertFalse(((EditBox)get(screen, "parameter")).visible);
        String source = draft.add(new ComputationGraph.Constant(new DataValue.ListValue(List.of(new DataValue.IntegerValue(3), new DataValue.IntegerValue(1), new DataValue.IntegerValue(3)))), 10, 20);
        String wrong = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(1)), 20, 30);
        draft.connect(node.id(), "left", source); draft.connect(node.id(), "right", source); draft.output("result", node.id());
        var hints = new NodeTypeHints(workspace, draft, false, null);
        assertEquals(DataType.Scalar.ANY, hints.input(node.id(), "left"));
        assertEquals(DataType.Scalar.BOOLEAN, hints.output(node.id()));
        assertDoesNotThrow(() -> hints.check(node.id(), "left", wrong));
        assertEquals(DataType.Scalar.ANY, hints.input(node.id(), "right"));
        var document = workspace.snapshot(); var decoded = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document));
        assertEquals(document, decoded); assertEquals(draft.node(node.id()).connections(), new ProgramDraft(decoded.archive()).node(node.id()).connections());
        var machine = new ComputationMachine(decoded.archive().compile(new ResourceLimits(100, 10000, 1))); machine.begin(new DataValue.RecordValue(Map.of()));
        for (int i = 0; machine.report().status() == ComputationMachine.Status.RUNNING; i++) { assertTrue(i < 80); machine.tick(); }
        assertEquals(new DataValue.BooleanValue(true), machine.report().output().orElseThrow().field("result"));
        assertFalse((boolean)get(screen, "requested")); assertNull(NodeParameter.kind(node.definition()));
    }
    @Test void bothLanguagesExplainCompleteTypedEqualityAndNetworkSchemaAdvances() throws Exception {
        for (String language : List.of("en_us", "zh_cn")) {
            var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/create_biotech/lang/" + language + ".json"))).getAsJsonObject();
            assertTrue(json.has("create_biotech.biocomputing.editor.kind.value_equals"));
            String hint = json.get("create_biotech.biocomputing.editor.value_equals_hint").getAsString(); assertTrue(hint.contains("left") && hint.contains("right"));
        }
        assertNetworkVersionAtLeast(75);
    }
    @Test void childSubgraphEqualityCompilesAndImportsWithoutInstallation() throws Exception {
        var screen = screen(); var workspace = (ProgramWorkspace)get(screen, "workspace");
        var key = workspace.createModule("same_values"); var child = workspace.graph(key);
        child.putInput("left", DataType.Scalar.ANY); child.putInput("right", DataType.Scalar.ANY);
        String left = child.add(new ComputationGraph.Input("left"), 0, 0), right = child.add(new ComputationGraph.Input("right"), 0, 40);
        String same = child.add(new ComputationGraph.ValueEquals("left", "right"), 50, 0);
        child.connect(same, "left", left); child.connect(same, "right", right); child.output("same", same);
        var root = workspace.root(); String one = root.add(new ComputationGraph.Constant(new DataValue.IntegerValue(1)), 0, 0);
        String text = root.add(new ComputationGraph.Constant(new DataValue.TextValue("1")), 0, 40);
        String call = root.add(new ComputationGraph.Call(key, Map.of("left", "left", "right", "right")), 50, 0);
        root.connect(call, "left", one); root.connect(call, "right", text); String field = root.add(new ComputationGraph.Field(call, "same"), 100, 0);
        root.connect(field, "source", call); root.output("result", field);
        var bounds = new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000, 1000000, 10000000);
        var exported = workspace.exportSubgraph(key, bounds); var imported = SubgraphDocumentCodec.decode(SubgraphDocumentCodec.encode(exported));
        assertEquals(exported, imported);
        var fresh = new ProgramWorkspace(); fresh.importSubgraph(imported, bounds); assertEquals(child.moduleGraph(false), fresh.graph(key).moduleGraph(false));
        var document = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        var runtime = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime(UUID.randomUUID(), document.archive(), new ResourceLimits(100, 10000, 1));
        assertEquals(ComputationMachine.Status.IDLE, runtime.report().status()); assertFalse((boolean)get(screen, "requested"));
        runtime.begin(new DataValue.RecordValue(Map.of()));
        for (int i = 0; runtime.report().status() == ComputationMachine.Status.RUNNING; i++) { assertTrue(i < 100); runtime.tick(); }
        assertEquals(new DataValue.BooleanValue(false), runtime.report().output().orElseThrow().field("result"));
    }
    private static void click(ComputerProgramScreen screen, String key) {
        screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance)
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast)
            .filter(button -> button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text && text.getKey().equals("create_biotech.biocomputing.editor." + key))
            .findFirst().orElseThrow().onClick(0, 0);
    }
}
