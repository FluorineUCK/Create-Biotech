package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.*;

class DynamicIndexEditorTest {
    private final ComputerProgramScreenTest fixture = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { fixture.client(); }
    @AfterEach void restore() throws Exception { fixture.restore(); }

    @Test void actualLanguageResourcesProvideNameAndZeroBasedWiringHint() throws Exception {
        for (String locale : List.of("en_us", "zh_cn")) {
            var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/assets/create_biotech/lang/" + locale + ".json"))).getAsJsonObject();
            for (String suffix : List.of("kind.dynamic_index", "dynamic_index_hint")) {
                var text = json.get("create_biotech.biocomputing.editor." + suffix).getAsString();
                assertFalse(text.isBlank());
                if (suffix.endsWith("hint")) { assertTrue(text.contains("0")); assertTrue(text.contains("source")); assertTrue(text.contains("index")); }
            }
        }
    }

    @Test void paletteCreatesTwoTypedPortsAndComputedIndexSurvivesDocumentLayoutRoundTrip() throws Exception {
        var screen = screen(); click(screen, "add"); selectPaletteKind(screen, "dynamic_index"); click(screen, "configure");
        var workspace = (ProgramWorkspace) get(screen, "workspace"); var draft = workspace.root();
        var pick = draft.nodes().getFirst();
        assertEquals(new ComputationGraph.DynamicIndex("source", "index"), pick.definition());
        assertEquals(List.of("source", "index"), List.copyOf(pick.ports()));
        assertFalse(((EditBox) get(screen, "parameter")).visible, "Index is a wired integer, not a literal text parameter");
        var alpha = new DataValue.RecordValue(Map.of("name", new DataValue.TextValue("alpha")));
        var beta = new DataValue.RecordValue(Map.of("name", new DataValue.TextValue("beta")));
        String source = draft.add(new ComputationGraph.Constant(new DataValue.ListValue(List.of(alpha, beta))), 10, 20);
        String zero = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(0)), 20, 20);
        String one = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(1)), 20, 50);
        String add = draft.add(new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "left", "right"), 80, 20);
        draft.connect(add, "left", zero); draft.connect(add, "right", one);
        draft.connect(pick.id(), "source", source); draft.connect(pick.id(), "index", add);
        String name = draft.add(new ComputationGraph.Field("source", "name"), 200, 20);
        draft.connect(name, "source", pick.id()); draft.output("name", name);
        var hints = new NodeTypeHints(workspace, draft, false, null);
        assertEquals(new DataType.ListType(DataType.Scalar.ANY), hints.input(pick.id(), "source"));
        assertEquals(DataType.Scalar.INTEGER, hints.input(pick.id(), "index"));
        assertEquals(DataType.typeOf(alpha), hints.output(pick.id()));
        assertEquals(DataType.Scalar.TEXT, hints.output(name));
        assertThrows(ComputationException.class, () -> hints.check(pick.id(), "source", one));
        assertThrows(ComputationException.class, () -> hints.check(pick.id(), "index", source));
        var document = workspace.snapshot(); var loaded = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document));
        assertEquals(document, loaded);
        var reloaded = new ProgramDraft(loaded.archive());
        assertEquals(draft.node(pick.id()).connections(), reloaded.node(pick.id()).connections());
        var machine = new ComputationMachine(loaded.archive().compile(new ResourceLimits(100, 10_000, 1)));
        machine.begin(new DataValue.RecordValue(Map.of()));
        for (int i = 0; machine.report().status() == ComputationMachine.Status.RUNNING; i++) { assertTrue(i < 40); machine.tick(); }
        assertEquals(new DataValue.TextValue("beta"), machine.report().output().orElseThrow().field("name"));
        assertFalse((boolean) get(screen, "requested"), "Configuring nodes never installs or runs a Computer");
        assertNull(NodeParameter.kind(pick.definition()), "No fixed index parameter is fabricated");
    }

    private static void click(ComputerProgramScreen screen, String key) {
        screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance)
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast)
            .filter(button -> button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                && text.getKey().equals("create_biotech.biocomputing.editor." + key))
            .findFirst().orElseThrow().onClick(0, 0);
    }
}
