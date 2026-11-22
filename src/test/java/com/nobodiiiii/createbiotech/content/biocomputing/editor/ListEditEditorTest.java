package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.*;

class ListEditEditorTest {
    private final ComputerProgramScreenTest fixture = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { fixture.client(); }
    @AfterEach void restore() throws Exception { fixture.restore(); }

    @Test void bothLanguagesDescribeCopyBoundsAndNetworkGateProtectsOldClients() throws Exception {
        for (String language : List.of("en_us", "zh_cn")) {
            var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/assets/create_biotech/lang/" + language + ".json"))).getAsJsonObject();
            for (String operation : List.of("replace", "insert", "remove", "slice")) {
                assertTrue(json.has("create_biotech.biocomputing.editor.kind.list_" + operation));
                String hint = json.get("create_biotech.biocomputing.editor.list_" + operation + "_hint").getAsString();
                assertTrue(hint.contains("source")); assertTrue(hint.contains("0"));
            }
        }
        assertNetworkVersionAtLeast(75);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ListOperation.class)
    void publicPaletteCreatesTypedWiredEditAndDocumentRetainsLayout(ListOperation operation) throws Exception {
        var screen = screen(); click(screen, "add"); selectPaletteKind(screen, "list_" + operation.name().toLowerCase(Locale.ROOT)); click(screen, "configure");
        var workspace = (ProgramWorkspace)get(screen, "workspace"); var draft = workspace.root();
        var node = draft.nodes().getFirst();
        assertEquals(operation, ((ComputationGraph.ListEdit)node.definition()).operation());
        assertEquals(List.copyOf(operation.inputs().keySet()), List.copyOf(node.ports()));
        assertFalse(((EditBox)get(screen, "parameter")).visible);
        String source = draft.add(new ComputationGraph.Constant(new DataValue.ListValue(List.of(new DataValue.IntegerValue(3), new DataValue.IntegerValue(7)))), 10, 20);
        String one = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(1)), 20, 30);
        String value = draft.add(new ComputationGraph.Constant(new DataValue.TextValue("new")), 20, 50);
        for (String port : node.ports()) draft.connect(node.id(), port, port.equals("source") ? source : port.equals("value") ? value : one);
        draft.output("result", node.id());
        var hints = new NodeTypeHints(workspace, draft, false, null);
        for (var port : operation.inputs().entrySet()) assertEquals(port.getValue(), hints.input(node.id(), port.getKey()));
        assertThrows(ComputationException.class, () -> hints.check(node.id(), "source", one));
        if (operation != ListOperation.APPEND) assertThrows(ComputationException.class, () -> hints.check(node.id(), operation == ListOperation.SLICE ? "start" : "index", value));
        var expectedType = new DataType.ListType(operation == ListOperation.INSERT || operation == ListOperation.REPLACE || operation == ListOperation.APPEND ? DataType.Scalar.ANY : DataType.Scalar.INTEGER);
        assertEquals(expectedType, hints.output(node.id()));
        var document = workspace.snapshot(); var loaded = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document));
        assertEquals(document, loaded);
        var reloaded = new ProgramDraft(loaded.archive()); assertEquals(draft.node(node.id()).connections(), reloaded.node(node.id()).connections());
        var machine = new ComputationMachine(loaded.archive().compile(new ResourceLimits(100, 10_000, 1)));
        machine.begin(new DataValue.RecordValue(Map.of()));
        for (int i = 0; machine.report().status() == ComputationMachine.Status.RUNNING; i++) { assertTrue(i < 40); machine.tick(); }
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
        var expected = switch (operation) {
            case APPEND -> List.<DataValue>of(new DataValue.IntegerValue(3), new DataValue.IntegerValue(7), new DataValue.TextValue("new"));
            case INSERT -> List.<DataValue>of(new DataValue.IntegerValue(3), new DataValue.TextValue("new"), new DataValue.IntegerValue(7));
            case REPLACE -> List.<DataValue>of(new DataValue.IntegerValue(3), new DataValue.TextValue("new"));
            case REMOVE -> List.<DataValue>of(new DataValue.IntegerValue(3));
            case SLICE -> List.<DataValue>of();
        };
        assertEquals(new DataValue.ListValue(expected), machine.report().output().orElseThrow().field("result"));
        assertFalse((boolean)get(screen, "requested"), "Editing does not install or execute");
        assertNull(NodeParameter.kind(node.definition()));
    }
    private static void click(ComputerProgramScreen screen, String key) {
        screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance)
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast)
            .filter(button -> button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                && text.getKey().equals("create_biotech.biocomputing.editor." + key))
            .findFirst().orElseThrow().onClick(0, 0);
    }
}
