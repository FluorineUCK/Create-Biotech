package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.*;

class ListCompositionEditorTest {
    private final ComputerProgramScreenTest fixture = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { fixture.client(); }
    @AfterEach void restore() throws Exception { fixture.restore(); }
    private static void click(ComputerProgramScreen screen, String key) {
        screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance)
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast)
            .filter(button -> button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                && text.getKey().equals("create_biotech.biocomputing.editor." + key))
            .findFirst().orElseThrow().onClick(0, 0);
    }

    @Test void paletteCreatesNumberedListPortsAndResizePreservesOnlyExistingSlots() throws Exception {
        var screen = screen();
        click(screen, "add"); selectPaletteKind(screen, "list");
        ((EditBox) get(screen, "parameter")).setValue("3"); click(screen, "configure");
        var draft = (ProgramDraft) get(screen, "draft");
        var list = draft.nodes().getFirst();
        assertInstanceOf(ComputationGraph.MakeList.class, list.definition());
        assertEquals(List.of("0", "1", "2"), List.copyOf(list.ports()));
        String source = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(7)), 0, 0);
        for (String port : list.ports()) draft.connect(list.id(), port, source);
        ((EditBox) get(screen, "parameter")).setValue("2"); click(screen, "configure");
        assertEquals(Map.of("0", source, "1", source), draft.node(list.id()).connections());
        ((EditBox) get(screen, "parameter")).setValue("3"); click(screen, "configure");
        assertEquals(Map.of("0", source, "1", source), draft.node(list.id()).connections());
        assertFalse((boolean) get(screen, "requested"), "configuration does not install");
        assertEquals("3", NodeParameter.text(draft.node(list.id()).definition()));
        assertEquals(ComputerNodePalette.Kind.LIST, NodeParameter.kind(draft.node(list.id()).definition()));
    }

    @Test void publicConcatConfigurationCompilesAndRetainsOrderedPortsAcrossDocumentLoad() throws Exception {
        var screen = screen(); click(screen, "add"); selectPaletteKind(screen, "concat_lists"); click(screen, "configure");
        var workspace = (ProgramWorkspace) get(screen, "workspace"); var draft = workspace.root();
        var concat = draft.nodes().getFirst();
        assertEquals(List.of("left", "right"), List.copyOf(concat.ports()));
        assertFalse(((EditBox) get(screen, "parameter")).visible);
        String value = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(5)), 20, 20);
        String list = draft.add(NodeParameter.parse(ComputerNodePalette.Kind.LIST, "1"), 170, 20);
        draft.connect(list, "0", value); draft.connect(concat.id(), "left", list); draft.connect(concat.id(), "right", list);
        draft.output("result", concat.id());
        var hints = new NodeTypeHints(workspace, draft, false, null);
        assertEquals(new DataType.ListType(DataType.Scalar.INTEGER), hints.output(concat.id()));
        assertThrows(ComputationException.class, () -> hints.check(concat.id(), "left", value));
        var document = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));
        var loaded = new ProgramDraft(document.archive());
        assertEquals(draft.node(list).connections(), loaded.node(list).connections());
        var machine = new ComputationMachine(document.archive().compile(new ResourceLimits(100, 10_000, 1)));
        machine.begin(new DataValue.RecordValue(Map.of()));
        for (int guard = 0; machine.report().status() == ComputationMachine.Status.RUNNING; guard++) { assertTrue(guard < 100); machine.step(); }
        assertEquals(new DataValue.ListValue(Collections.nCopies(2, new DataValue.IntegerValue(5))), machine.report().output().orElseThrow().field("result"));
        assertFalse((boolean) get(screen, "requested"));
    }

    @Test void quantityRejectsNegativeOverflowAndUnrepresentableAllocation() {
        for (String value : List.of("-1", "2147483647", "2147483648", "1.5", ""))
            assertThrows(IllegalArgumentException.class, () -> NodeParameter.parse(ComputerNodePalette.Kind.LIST, value));
        assertEquals(new ComputationGraph.MakeList(List.of()), NodeParameter.parse(ComputerNodePalette.Kind.LIST, "0"));
    }

    @Test void bothActualLanguageResourcesNameTheNodesAndExplainQuantity() throws Exception {
        for (String locale : List.of("en_us", "zh_cn")) {
            var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/assets/create_biotech/lang/" + locale + ".json"))).getAsJsonObject();
            for (String suffix : List.of("kind.list", "kind.concat_lists", "list_count")) {
                String key = "create_biotech.biocomputing.editor." + suffix;
                assertTrue(json.has(key), locale + ": " + key); assertFalse(json.get(key).getAsString().isBlank());
            }
        }
    }
}
