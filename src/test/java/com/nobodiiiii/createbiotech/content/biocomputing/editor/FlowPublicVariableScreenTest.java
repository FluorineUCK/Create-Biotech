package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.*;

class FlowPublicVariableScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void ordinaryVariablesDeclarePublicScopeWithoutTransactionControls(int width) throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var screen = FlowCallScreenTest.editor(workspace, width);
        click(screen, "category_variables"); click(screen, "declare_public");
        boxes(screen).get(0).setValue("counter"); boxes(screen).get(1).setValue("0");
        click(screen, "save_initial"); click(screen, "back");
        assertEquals(new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0)), workspace.root().cells().get("counter"));
        assertTrue(workspace.flow().orElseThrow().inputs().isEmpty());
        assertNotNull(button(screen, "add_public_set"));
        assertNotNull(button(screen, "add_public_add"));
        assertTrue(FlowBlockCatalog.search(Map.of(), FlowBlockPalette.Category.STORAGE, "").isEmpty(),
            "Legacy transaction controls are compatibility tools, not the ordinary variable path");
        bounds(screen);
    }

    @Test void createEditReadAndRestorePublicVariablesThroughScratchControlsAndTheInstalledOwner() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "initials"); boxes(screen).get(0).setValue("counter"); boxes(screen).get(1).setValue("9");
        click(screen, "save_initial"); click(screen, "back");
        click(screen, "category_variables"); click(screen, "declare_public");
        boxes(screen).get(0).setValue("counter"); boxes(screen).get(1).setValue("0"); click(screen, "save_initial"); click(screen, "back");
        click(screen, "add_public_set"); click(screen, "apply");
        FlowInlineScreenTest.setLiteral(screen, FlowInlineScreenTest.slot(screen, "0", 0), "40");
        // Header editing changes the public target, not the nested expression or a private local.
        click(screen, "edit"); assertEquals("counter", boxes(screen).getFirst().getValue()); click(screen, "apply");
        for (int i = 0; i < 2; i++) { click(screen, "add_public_add"); click(screen, "apply"); }
        click(screen, "add_assign");
        var assignment = workspace.flow().orElseThrow().steps().getLast();
        ((FlowBlockCanvas)get(screen, "canvas")).focus(assignment.sourceId());
        FlowPaletteScreenTest.search(screen).setValue("public:");
        var reporter = button(screen, "inline_public");
        var previousLanguage = net.minecraft.locale.Language.getInstance();
        try {
            net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load("en_us")));
            var reporterDetails = screen.typeTooltip(reporter.getX() + 8, reporter.getY() + 8, true);
            assertNotNull(reporterDetails);
            var declaredType = workspace.root().cells().get("counter").type();
            assertTrue(reporterDetails.getString().contains("Declared"), reporterDetails.getString());
            assertTrue(reporterDetails.getString().contains(ComputerGraphPorts.typeLabel(declaredType)), reporterDetails.getString());
        } finally { net.minecraft.locale.Language.inject(previousLanguage); }
        var slot = FlowInlineScreenTest.slot(screen, "0", 0);
        screen.mouseClicked(reporter.getX() + 8, reporter.getY() + 8, 0);
        screen.mouseDragged(slot.x() + 8, slot.y() + 8, 0, 40, 0);
        screen.mouseReleased(slot.x() + 8, slot.y() + 8, 0);
        var document = workspace.snapshot();
        var graph = document.flow().orElseThrow().inline().get(assignment.sourceId());
        assertTrue(graph.nodes().values().stream().anyMatch(ComputationGraph.ReadMemory.class::isInstance));
        assertTrue(graph.inputs().isEmpty(), "Public reads never capture a same-name private variable");
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        click(screen, "undo"); assertNotEquals(document, workspace.snapshot()); click(screen, "redo"); assertEquals(document, workspace.snapshot());
        assertFalse((boolean)get(screen, "waiting"), "Editing must not install or run");
        var limits = new ComputerHostLimits(new ResourceLimits(1000, 100_000, 1), new ArchiveLimits(100_000, 1_000_000, 1_000_000));
        var owner = new ComputerBlockHost(() -> {}); var body = document.flow().orElseThrow();
        owner.installFlow(body.program(document.archive()), body.initial(), limits, true);
        for (int i = 0; i < 100 && owner.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) {
            owner.step(limits, true); var checkpoint = owner.flowSnapshot().orElseThrow();
            assertTrue(checkpoint.state().expression().isEmpty(), "No graph interpreter continuation");
            var restored = new ComputerBlockHost(() -> {}); restored.read(owner.write()); restored.tick(limits, true);
            assertEquals(checkpoint, restored.flowSnapshot().orElseThrow()); owner = restored;
        }
        var saved = owner.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, saved.state().status());
        assertEquals(new DataValue.IntegerValue(42), saved.memory().cells().field("counter"));
        assertEquals(new DataValue.IntegerValue(9), saved.state().locals().field("counter"));
        String result = ((FlowProgram.Evaluate)assignment).bindings().orElseThrow().returns().get("value");
        assertEquals(new DataValue.IntegerValue(42), saved.state().locals().field(result));
    }

    @Test void publicRecordFieldReporterKeepsItsScopeAndHasNoImplicitCallArguments() {
        var integer = DataType.Scalar.INTEGER;
        var record = new DataType.RecordType(Map.of("reading", integer));
        var publicSources = InlineGraphEdits.palette(Map.of("sensor", record), Map.of("sensor", record));
        var source = publicSources.stream().filter(s -> s instanceof InlineGraphEdits.PublicVariable v && v.path().equals(List.of("reading"))).findFirst().orElseThrow();
        var graph = new ComputationGraph(Map.of(), Map.of("value", new ComputationGraph.Constant(new DataValue.IntegerValue(0))), Map.of("value", "value"));
        var edit = InlineGraphEdits.replace(graph, new FlowCallBindings(Map.of(), Map.of("value", "result")), "value", source);
        assertTrue(edit.graph().inputs().isEmpty()); assertTrue(edit.bindings().arguments().isEmpty());
        assertTrue(edit.graph().nodes().values().stream().anyMatch(n -> n.equals(new ComputationGraph.ReadMemory("sensor"))));
        var ids = FlowBlockCatalog.entries(Map.of("sensor", record), List.of(), Map.of("sensor", record)).stream().map(FlowBlockCatalog.Entry::id).toList();
        assertEquals(ids.size(), new HashSet<>(ids).size());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"1", "{byte:1b}"})
    void editingPublicNbtPreservesNativeTagTypes(String literal) throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var original = EditorValueFormat.parse(EditorValueFormat.Kind.NBT, literal);
        workspace.root().putCell("packet", DataType.Scalar.NBT, original);
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "add_public_set"); click(screen, "apply");
        String changed = literal.replace("1", "2");
        FlowInlineScreenTest.setLiteral(screen, FlowInlineScreenTest.slot(screen, EditorValueFormat.format(original), 0), changed);
        var body = workspace.flow().orElseThrow(); var graph = FlowInlineValues.graph(body, body.steps().getFirst());
        assertEquals(EditorValueFormat.parse(EditorValueFormat.Kind.NBT, changed),
            ((ComputationGraph.Constant)graph.nodes().get(graph.outputs().get("value"))).value());
        workspace.snapshot().validate(new ResourceLimits(1000, 100_000, 1));
        assertEquals(workspace.snapshot(), ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot())));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zh_cn", "en_us"})
    void scopeAndOperationLabelsHavePackagedTranslations(String locale) throws Exception {
        var translations = ComputerUiTranslationsTest.load(locale);
        for (String key : List.of("declare_private", "declare_public", "add_public_set", "add_public_add", "public_variable", "public_hint",
                "inline_private", "inline_public", "inline_public_set", "inline_public_add", "block_public_set", "block_public_add")) {
            var value = translations.get("create_biotech.biocomputing.flow_editor." + key);
            assertNotNull(value, key); assertFalse(value.isBlank(), key);
            assertEquals(key.startsWith("inline_") || key.startsWith("block_") ? 1 : 0,
                java.util.regex.Pattern.compile("%s").matcher(value).results().count(), key);
        }
    }
}
