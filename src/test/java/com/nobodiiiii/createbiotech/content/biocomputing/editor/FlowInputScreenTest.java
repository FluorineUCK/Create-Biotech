package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FlowInputScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }

    @ParameterizedTest @ValueSource(strings = {"library_service_b", "library_service_c", "library_service_d"})
    void publishedServiceRendersIgnoredExpressionResultsWithoutInventingAVariable(String id) throws Exception {
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> resources = location ->
            getClass().getClassLoader().getResourceAsStream("assets/" + location.getNamespace() + "/" + location.getPath());
        var example = ProgramExampleCatalog.load(resources).stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
        var document = ProgramExampleCatalog.preview(example, resources, SubgraphWorkspaceTest.LIMITS);
        var workspace = new ProgramWorkspace(document); var editor = editor(workspace, 320);
        set(net.minecraft.client.Minecraft.getInstance(), "guiSprites", allocate(SilentSprites.class));
        var graphics = allocate(SilentGraphics.class); var labels = new ArrayList<Component>(); set(graphics, "labels", labels);
        assertDoesNotThrow(() -> editor.renderBg(graphics, 0, -1, -1), "Actual published service must survive its first rendered frame");
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        var discarded = canvas.blocks().stream().map(FlowBlockCanvas.Block::step)
            .filter(FlowProgram.Evaluate.class::isInstance).map(FlowProgram.Evaluate.class::cast)
            .filter(call -> document.flow().orElseThrow().inline().containsKey(call.sourceId()) && call.bindings().orElseThrow().returns().isEmpty())
            .map(FlowProgram.Evaluate::sourceId).toList();
        assertFalse(discarded.isEmpty());
        assertTrue(labels.stream().anyMatch(label -> label.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
            && text.getKey().equals("create_biotech.biocomputing.flow_editor.inline_discard")));
        assertTrue(canvas.inlineSlots().stream().noneMatch(slot -> discarded.contains(slot.call()) && slot.target()),
            "Discarded results have no target variable editor");
        assertTrue(canvas.inlineSlots().stream().anyMatch(slot -> discarded.contains(slot.call()) && !slot.target()),
            "The actual expression still has ordinary editable/replacement slots");
        assertEquals(document, workspace.snapshot()); assertFalse((boolean)get(editor, "waiting"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void importedUnsupportedLiteralCanRenderCancelAndBeExplicitlyRepaired(boolean until) throws Exception {
        var bad = new FlowInput.Literal(new DataValue.ErrorValue("bad", "sample"));
        var program = new FlowProgram(Map.of(), until ? List.of(new FlowProgram.Until("loop", bad), new FlowProgram.EndUntil("end"))
            : List.of(new FlowProgram.Repeat("loop", bad), new FlowProgram.EndRepeat("end")));
        var document = new ProgramDocument(program.expressions(), Map.of(ProgramDocument.graphId(null), Map.of()), Optional.empty(), Optional.of(
            new FlowDocumentBody(program.inputs(), program.steps(), new DataValue.RecordValue(Map.of()))));
        var decoded = ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, SubgraphWorkspaceTest.LIMITS), SubgraphWorkspaceTest.LIMITS);
        var workspace = new ProgramWorkspace(decoded); var editor = editor(workspace, 320);
        set(net.minecraft.client.Minecraft.getInstance(), "guiSprites", allocate(SilentSprites.class));
        var graphics = allocate(SilentGraphics.class);
        assertDoesNotThrow(() -> editor.renderBg(graphics, 0, -1, -1), "Malformed imported values still need a repairable list view");
        selectBlock(editor, 0);
        assertDoesNotThrow(() -> click(editor, "edit")); assertEquals(document, workspace.snapshot());
        assertTrue(boxes(editor).getFirst().getValue().isEmpty(), "Unrepresentable values must not become 0/false");
        assertEquals("create_biotech.biocomputing.flow_editor.invalid_literal", ((net.minecraft.network.chat.contents.TranslatableContents)
            ((Component)get(editor, "notice")).getContents()).getKey());
        click(editor, "apply"); assertEquals(document, workspace.snapshot());
        click(editor, "cancel"); assertEquals(document, workspace.snapshot()); click(editor, "install"); assertFalse((boolean)get(editor, "waiting"));
        click(editor, "edit"); boxes(editor).getFirst().setValue(until ? "true" : "2"); click(editor, "apply");
        workspace.snapshot().validate(new ResourceLimits(1000, 100000, 100));
        click(editor, "undo"); assertEquals(document, workspace.snapshot());
    }

    /** Rendering callbacks execute normally; only texture lookup and final GPU/text submission are suppressed. */
    static final class SilentSprites extends net.minecraft.client.gui.GuiSpriteManager {
        private SilentSprites() { super(null); }
        @Override public net.minecraft.client.renderer.texture.TextureAtlasSprite getSprite(net.minecraft.resources.ResourceLocation id) { return null; }
        @Override public net.minecraft.client.resources.metadata.gui.GuiSpriteScaling getSpriteScaling(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) { return null; }
    }
    private static final class SilentGraphics extends net.minecraft.client.gui.GuiGraphics {
        private List<Component> labels;
        private static final com.mojang.blaze3d.vertex.PoseStack POSE = new com.mojang.blaze3d.vertex.PoseStack();
        private SilentGraphics() { super(null, null); }
        @Override public com.mojang.blaze3d.vertex.PoseStack pose() { return POSE; }
        @Override public void enableScissor(int x, int y, int right, int bottom) {}
        // Capture captions/layout without submitting the new nine-slice atlas to a GPU.
        @Override public void blit(net.minecraft.resources.ResourceLocation texture, int x, int y, int width, int height,
                float u, float v, int sourceWidth, int sourceHeight, int textureWidth, int textureHeight) {
            assertTrue(width > 0 && height > 0);
        }
        @Override public void disableScissor() {}
        @Override public int drawString(net.minecraft.client.gui.Font font, Component text, int x, int y, int color, boolean shadow) {
            text.getString(); if (labels != null) labels.add(text); return 0;
        }
        @Override public void blitSprite(net.minecraft.resources.ResourceLocation id, int x, int y, int width, int height) {}
        @Override public void fill(int x, int y, int right, int bottom, int color) {}
        @Override public int drawString(net.minecraft.client.gui.Font font, String text, int x, int y, int color, boolean shadow) { return 0; }
    }

    @Test void emptySelectorsAndMissingReferencesStayEditableButCannotInstall() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = editor(workspace, 320); click(editor, "add_until"); click(editor, "input_expression");
        assertFalse(button(editor, "next_expression").active); assertFalse(button(editor, "next_output").active);
        var empty = workspace.snapshot(); click(editor, "apply"); assertEquals(empty, workspace.snapshot());
        boxes(editor).get(0).setValue("absent"); boxes(editor).get(1).setValue("done"); click(editor, "apply");
        var missing = workspace.snapshot(); click(editor, "install"); assertFalse((boolean)get(editor, "waiting"));
        click(editor, "edit"); click(editor, "input_literal"); boxes(editor).getFirst().setValue("1"); click(editor, "apply");
        assertEquals(missing, workspace.snapshot(), "Until never coerces numeric text to boolean");
        click(editor, "input_true"); click(editor, "apply"); workspace.snapshot().validate(new ResourceLimits(1000, 100000, 100));
        click(editor, "undo"); assertEquals(missing, workspace.snapshot());
    }

    @ParameterizedTest @ValueSource(strings = {"en_us", "zh_cn"})
    void localizedSlotControlsFitSmallScreensAndHintsStayInsideTheirFields(String locale) throws Exception {
        var old = net.minecraft.locale.Language.getInstance();
        try {
            var translations = ComputerUiTranslationsTest.load(locale); net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(translations));
            var metrics = new Metrics() {
                @Override public int width(String value) { return value.length() * 6; }
                @Override public String plainSubstrByWidth(String value, int width) { return value.substring(0, Math.min(value.length(), Math.max(0, width / 6))); }
            };
            var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
            var editor = editor(workspace, 320); set(editor, "height", 240); set(editor, "font", metrics); editor.init(); bounds(editor);
            for (var key : List.of("install", "debugger", "add_until")) {
                var control = button(editor, key); assertTrue(metrics.width(control.getMessage().getString()) <= control.getWidth() - 8);
            }
            click(editor, "add_until"); bounds(editor);
            for (var key : List.of("input_local", "input_literal", "input_expression")) {
                var control = button(editor, key); assertFalse(control.getMessage().getString().startsWith("create_biotech."));
                assertTrue(metrics.width(control.getMessage().getString()) <= control.getWidth() - 8);
            }
            for (String source : List.of("local", "literal", "expression")) {
                if (!source.equals("local")) click(editor, "input_" + source);
                bounds(editor);
                for (var field : boxes(editor)) {
                    var hint = (Component)get(field, "hint");
                    assertTrue(metrics.width(hint.getString()) <= field.getWidth() - 8, hint.getString());
                    assertNotNull(field.getTooltip());
                }
            }
            for (String key : List.of("block_until", "block_end_until", "until_hint", "input_expression_hint", "install_hint", "debugger_hint"))
                assertTrue(translations.containsKey("create_biotech.biocomputing.flow_editor." + key), key);
        } finally { net.minecraft.locale.Language.inject(old); }
    }

    @ParameterizedTest @ValueSource(ints = {320, 426, 640})
    void expressionPickerUsesPublicEntriesAndOutputsIncludingNestedCallsAndParameters(int width) throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("done", DataType.Scalar.BOOLEAN, "n", DataType.Scalar.INTEGER), List.of(),
            new DataValue.RecordValue(Map.of("done", new DataValue.BooleanValue(true), "n", new DataValue.IntegerValue(3))))));
        var key = workspace.createModule("identity"); var module = workspace.graph(key); module.putInput("arg", DataType.Scalar.BOOLEAN);
        module.output("done", module.add(new ComputationGraph.Input("arg"), 0, 0));
        var predicate = workspace.createEntry("predicate"); predicate.putInput("done", DataType.Scalar.BOOLEAN);
        var input = predicate.add(new ComputationGraph.Input("done"), 0, 0);
        var call = predicate.add(new ComputationGraph.Call(key, Map.of("arg", input)), 60, 0);
        predicate.connect(call, "arg", input);
        var result = predicate.add(new ComputationGraph.Field(call, "done"), 120, 0); predicate.connect(result, "source", call);
        predicate.output("matched", result);
        predicate.output("wrong", predicate.add(new ComputationGraph.Constant(new DataValue.IntegerValue(7)), 120, 40));
        var count = workspace.createEntry("count"); count.putInput("n", DataType.Scalar.INTEGER);
        count.output("times", count.add(new ComputationGraph.Input("n"), 0, 0));
        var editor = editor(workspace, width); click(editor, "add_until"); click(editor, "input_expression"); bounds(editor);
        assertEquals(2, boxes(editor).size()); click(editor, "next_expression"); click(editor, "next_output");
        assertEquals(List.of("predicate", "matched"), boxes(editor).stream().map(box -> box.getValue()).toList());
        click(editor, "apply"); var until = workspace.snapshot(); until.validate(new ResourceLimits(1000, 100000, 100));
        assertEquals(new FlowInput.Expression("predicate", "matched"), ((FlowProgram.Until)workspace.flow().orElseThrow().steps().getFirst()).condition());
        var body = until.flow().orElseThrow();
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), new FlowProgram(body.inputs(), body.steps(), until.archive()),
            body.initial(), new ResourceLimits(1000, 100000, 100)); runtime.advance(100);
        assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status(), "Selected output runs the ordinary nested Call with the supplied boolean");
        click(editor, "edit"); click(editor, "next_output"); click(editor, "apply");
        var invalid = workspace.snapshot(); assertThrows(ComputationException.class, () -> invalid.validate(new ResourceLimits(1000, 100000, 100)));
        click(editor, "install"); assertFalse((boolean)get(editor, "waiting"), "An integer output cannot be installed as Until's boolean condition");
        click(editor, "undo"); assertEquals(until, workspace.snapshot());
        click(editor, "add_repeat"); click(editor, "input_expression"); boxes(editor).getFirst().setValue("count");
        click(editor, "next_output"); assertEquals("times", boxes(editor).get(1).getValue()); click(editor, "apply");
        var nested = workspace.snapshot(); nested.validate(new ResourceLimits(1000, 100000, 100));
        assertEquals(new FlowInput.Expression("count", "times"), ((FlowProgram.Repeat)workspace.flow().orElseThrow().steps().get(1)).count());
        assertEquals(nested, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(nested)));
        click(editor, "edit"); click(editor, "input_literal"); click(editor, "input_expression");
        assertEquals(List.of("count", "times"), boxes(editor).stream().map(box -> box.getValue()).toList());
        click(editor, "cancel"); assertEquals(nested, workspace.snapshot()); bounds(editor);
        assertFalse((boolean)get(editor, "waiting"));
    }

    @ParameterizedTest @ValueSource(ints = {320, 426, 640})
    void literalSlotsKeepTypesAndCancelOrInvalidTextCannotAlterTheDraft(int width) throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = editor(workspace, width); var empty = workspace.snapshot();
        click(editor, "add_repeat"); click(editor, "input_literal"); bounds(editor);
        assertEquals(empty, workspace.snapshot(), "Selecting a source must not publish a half-filled block");
        boxes(editor).getFirst().setValue("3"); click(editor, "apply");
        var counted = workspace.snapshot();
        assertEquals(new FlowInput.Literal(new DataValue.IntegerValue(3)), ((FlowProgram.Repeat)workspace.flow().orElseThrow().steps().getFirst()).count());
        click(editor, "edit"); assertEquals("3", boxes(editor).getFirst().getValue());
        boxes(editor).getFirst().setValue("3.5"); click(editor, "apply"); assertEquals(counted, workspace.snapshot());
        var notice = (net.minecraft.network.chat.contents.TranslatableContents)((Component)get(editor, "notice")).getContents();
        assertEquals("create_biotech.biocomputing.flow_editor.invalid", notice.getKey());
        boxes(editor).getFirst().setValue("4"); click(editor, "cancel"); assertEquals(counted, workspace.snapshot());
        click(editor, "add_until"); click(editor, "input_literal"); click(editor, "input_true"); click(editor, "apply");
        var nested = workspace.snapshot(); nested.validate(new ResourceLimits(100, 100000, 100));
        assertEquals(new FlowInput.Literal(new DataValue.BooleanValue(true)), ((FlowProgram.Until)workspace.flow().orElseThrow().steps().get(1)).condition());
        assertEquals(nested, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(nested)));
        click(editor, "edit"); click(editor, "input_local"); boxes(editor).getFirst().setValue("missing"); click(editor, "cancel");
        assertEquals(nested, workspace.snapshot()); click(editor, "remove"); assertEquals(counted, workspace.snapshot());
        click(editor, "undo"); assertEquals(nested, workspace.snapshot()); bounds(editor);
        assertFalse((boolean)get(editor, "waiting"));
    }

    @ParameterizedTest @ValueSource(ints = {320, 426, 640})
    void publicUntilCreatesEditableWholeGroupWithoutInstalling(int width) throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("done", DataType.Scalar.BOOLEAN), List.of(),
            new DataValue.RecordValue(Map.of("done", new DataValue.BooleanValue(false))))));
        var editor = editor(workspace, width); bounds(editor); click(editor, "add_until");
        assertEquals(1, boxes(editor).size()); boxes(editor).getFirst().setValue("done"); click(editor, "apply");
        var group = workspace.snapshot(); group.validate(new ResourceLimits(100, 100000, 100));
        assertEquals(2, workspace.flow().orElseThrow().steps().size());
        assertEquals(new FlowInput.Local("done"), ((FlowProgram.Until)workspace.flow().orElseThrow().steps().getFirst()).condition());
        assertEquals(new DataValue.IntegerValue(9), ProgramDocumentCodec.encode(group).field("version"));
        assertEquals(group, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(group)));
        click(editor, "edit"); assertEquals("done", boxes(editor).getFirst().getValue()); click(editor, "apply");
        assertEquals(group, workspace.snapshot()); click(editor, "remove"); assertTrue(workspace.flow().orElseThrow().steps().isEmpty());
        click(editor, "undo"); assertEquals(group, workspace.snapshot()); bounds(editor);
        assertFalse((boolean)get(editor, "waiting"));
    }

    private static ComputerFlowScreen editor(ProgramWorkspace workspace, int width) throws Exception {
        var parent = screen(); var menu = readyMenu(parent);
        var editor = new ComputerFlowScreen(menu, (Inventory)get(parent, "editorInventory"), Component.empty(), workspace,
            doc -> fail("Must not replace the workspace"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, width); return editor;
    }
}
