package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.*;

class ComputerScratchAssemblyTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"en_us,320", "zh_cn,640"})
    void installTooltipShowsTheLoweredInstructionFamiliesNotJustTheBackendName(String locale, int width) throws Exception {
        var old = net.minecraft.locale.Language.getInstance();
        try {
            var translations = ComputerUiTranslationsTest.load(locale);
            net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(translations));
            for (boolean advanced : List.of(false, true)) {
                var parent = screen(); var menu = ComputerFlowScreenTest.readyMenu(parent);
                var workspace = advanced ? new ProgramWorkspace(ReaderOutputDocumentTest.load("d")) : new ProgramWorkspace();
                if (!advanced) workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(
                    new FlowProgram(Map.of(), List.of()).withAssembly(), new DataValue.RecordValue(Map.of()))));
                var editor = new ComputerFlowScreen(menu, (Inventory)get(parent, "editorInventory"), Component.empty(),
                    workspace, doc -> fail("Preview must not replace the program"), () -> {}, () -> {}, () -> {}, () -> {});
                ComputerFlowScreenTest.initialize(editor, width);
                String hint = tooltipText(ComputerFlowScreenTest.button(editor, "install"));
                String key = "create_biotech.biocomputing.flow_editor.instruction_families";
                assertNotNull(translations.get(key), "Packaged family label for " + locale);
                String families = advanced ? "ISA-1, ISA-1a, ISA-1b, XBus" : "ISA-1";
                assertTrue(hint.contains(String.format(translations.get(key), families)), hint);
                assertFalse(hint.contains(key));
                assertTrue(hint.length() <= 280 && hint.lines().count() <= 5,
                    "The normal install tooltip must not cover the program with a capability inventory: " + hint);
                assertEquals(hint, ComputerFlowScreenTest.button(editor, "install").getToolTip().stream()
                    .map(Component::getString).collect(java.util.stream.Collectors.joining("\n")));
                ComputerFlowScreenTest.bounds(editor);
            }
        } finally { net.minecraft.locale.Language.inject(old); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"repeat", "until", "forever"})
    void loopsDraggedFromTheDefaultScratchCatalogInstallWithoutNamedGraphsOrBackendFallback(String kind) throws Exception {
        var fixture = screen(); var menu = ComputerFlowScreenTest.readyMenu(fixture);
        var hub = new ComputerScratchScreen(menu, (Inventory)get(fixture, "editorInventory"), Component.empty());
        Screen[] shown = {hub}; set(hub, "navigator", (Consumer<Screen>)(next -> shown[0] = next));
        ComputerScratchNavigationTest.initialize(hub); set(hub, "requested", true); set(menu, "responseVersion", 1L);
        hub.containerTick(); var blocks = assertInstanceOf(ComputerFlowScreen.class, shown[0]);
        var workspace = (ProgramWorkspace)get(hub, "workspace"); ComputerFlowScreenTest.initialize(blocks, 640);
        var prototype = ComputerFlowScreenTest.button(blocks, "add_" + kind); var canvas = get(blocks, "canvas");
        int x = (int)get(canvas, "x") + 24, y = (int)get(canvas, "y") + 50;
        blocks.mouseClicked(prototype.getX() + 8, prototype.getY() + 8, 0);
        blocks.mouseDragged(x, y, 0, 100, 20); blocks.mouseReleased(x, y, 0);
        if (!kind.equals("forever")) {
            boolean until = kind.equals("until");
            FlowInlineScreenTest.dropReporter(blocks, until ? "inline_less_integer" : "inline_add_integer",
                FlowInlineScreenTest.slot(blocks, until ? "false" : "10", 0));
            FlowInlineScreenTest.setLiteral(blocks, FlowInlineScreenTest.slot(blocks, until ? "0" : "10", 0), "2");
            FlowInlineScreenTest.setLiteral(blocks, FlowInlineScreenTest.slot(blocks, "0", 0), "3");
        }
        assertTrue(workspace.entryNames().isEmpty()); assertFalse((boolean)get(blocks, "waiting"));
        var document = workspace.snapshot(); assertEquals(2, document.flow().orElseThrow().steps().size());
        var decoded = ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, SubgraphWorkspaceTest.LIMITS), SubgraphWorkspaceTest.LIMITS);
        assertEquals(document, decoded); assertEquals(FlowProgram.Execution.REGISTER_V1, decoded.flow().orElseThrow().execution());
        var host = new ComputerBlockHost(() -> {});
        var limits = new ComputerHostLimits(new ResourceLimits(1000, 100000, 1), SubgraphWorkspaceTest.LIMITS);
        var body = decoded.flow().orElseThrow(); host.installFlow(body.program(decoded.archive()), body.initial(), limits, true);
        for (int i = 0; i < 100 && host.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.READY; i++) host.step(limits, true);
        var state = host.flowSnapshot().orElseThrow().state();
        assertEquals(kind.equals("forever") ? FlowRuntime.Status.READY : FlowRuntime.Status.COMPLETE, state.status());
        if (kind.equals("forever")) assertEquals(100, state.work());
        assertTrue(state.assembly().isPresent()); assertTrue(state.expression().isEmpty());
    }

    @Test void defaultScratchPublicTypedEqualityReporterMatchesFreshAndRejectsStaleTags() throws Exception {
        var fixture = screen(); var menu = ComputerFlowScreenTest.readyMenu(fixture);
        var hub = new ComputerScratchScreen(menu, (Inventory)get(fixture, "editorInventory"), Component.empty());
        Screen[] shown = {hub}; set(hub, "navigator", (Consumer<Screen>)(next -> shown[0] = next));
        ComputerScratchNavigationTest.initialize(hub); set(hub, "requested", true); set(menu, "responseVersion", 1L);
        hub.containerTick(); var blocks = assertInstanceOf(ComputerFlowScreen.class, shown[0]);
        var workspace = (ProgramWorkspace)get(hub, "workspace"); ComputerFlowScreenTest.initialize(blocks, 640);
        ((net.minecraft.client.gui.components.EditBox)get(blocks,"catalogSearch")).setValue("correlation");
        for (String name : List.of("first", "second")) {
            ComputerFlowScreenTest.click(blocks, "add_correlation"); ComputerFlowScreenTest.boxes(blocks).getFirst().setValue(name);
            ComputerFlowScreenTest.click(blocks, "apply");
        }
        ((net.minecraft.client.gui.components.EditBox)get(blocks,"catalogSearch")).setValue("");
        var generated = workspace.flow().orElseThrow().steps().stream().map(FlowProgram.Correlation.class::cast).toList();
        for (boolean matching : List.of(true, false)) {
            ComputerFlowScreenTest.click(blocks, "category_variables"); ComputerFlowScreenTest.click(blocks, "add_assign");
            ((FlowBlockCanvas)get(blocks,"canvas")).focus(workspace.flow().orElseThrow().steps().getLast().sourceId());
            FlowInlineScreenTest.dropReporter(blocks, "inline_value_equals", FlowInlineScreenTest.slot(blocks, "0", 0));
            FlowInlineScreenTest.dropPrivateVariable(blocks, generated.getFirst().local(), FlowInlineScreenTest.slot(blocks, "0", 0));
            FlowInlineScreenTest.dropPrivateVariable(blocks, generated.get(matching ? 0 : 1).local(), FlowInlineScreenTest.slot(blocks, "null", 0));
        }
        var document = workspace.snapshot();
        assertTrue(workspace.entryNames().isEmpty());
        var decoded = ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, SubgraphWorkspaceTest.LIMITS), SubgraphWorkspaceTest.LIMITS);
        assertEquals(document, decoded);
        var body = decoded.flow().orElseThrow();
        var limits = new ComputerHostLimits(new ResourceLimits(1000,100000,1), SubgraphWorkspaceTest.LIMITS,
            com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.DEFAULT,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware.NONE, 1, true);
        var host = new ComputerBlockHost(() -> {}); host.installFlow(body.program(decoded.archive()),body.initial(),limits,true);
        for (int i=0; host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++) {
            assertTrue(i<250); host.step(limits,true);
        }
        var state=host.flowSnapshot().orElseThrow().state();
        var calls=body.steps().stream().filter(FlowProgram.Evaluate.class::isInstance).map(FlowProgram.Evaluate.class::cast).toList();
        String yes=calls.getFirst().bindings().orElseThrow().returns().get("value"), no=calls.getLast().bindings().orElseThrow().returns().get("value");
        assertEquals(new DataValue.BooleanValue(true),state.locals().field(yes)); assertEquals(new DataValue.BooleanValue(false),state.locals().field(no));
        assertNotEquals(state.locals().field(generated.getFirst().local()),state.locals().field(generated.getLast().local()));
        assertTrue(state.assembly().isPresent()); assertTrue(state.expression().isEmpty());
        assertEquals(30,body.program(decoded.archive()).usage(limits.hardware()).programUnits());
        assertEquals(91,state.work());
    }

    @Test void documentTargetIsVersionedAndOlderDocumentsKeepTheirOriginalExecutionMeaning() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var document = workspace.snapshot(); var encoded = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(23), encoded.field("version"));
        assertEquals(document, ProgramDocumentCodec.decode(encoded));
        var downgraded = new LinkedHashMap<>(encoded.fields()); downgraded.put("version", new DataValue.IntegerValue(22));
        assertThrows(RuntimeException.class, () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(downgraded)));
        var body = new LinkedHashMap<>(((DataValue.RecordValue)encoded.field("flow")).fields()); body.put("execution", new DataValue.TextValue("future_isa"));
        var future = new LinkedHashMap<>(encoded.fields()); future.put("flow", new DataValue.RecordValue(body));
        assertThrows(RuntimeException.class, () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(future)));
        workspace.setFlow(Optional.of(FlowDocumentBody.empty())); var old = workspace.snapshot();
        assertEquals(new DataValue.IntegerValue(3), ProgramDocumentCodec.encode(old).field("version"));
        assertEquals(FlowProgram.Execution.LEGACY_GRAPH, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(old)).flow().orElseThrow().execution());
        assertNetworkVersionAtLeast(89);
    }
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }

    @Test void aNewScratchAssignmentSurvivesDocumentExchangeAndInstallsAsRegistersWithoutAnOptInApi() throws Exception {
        var fixture = screen(); var menu = ComputerFlowScreenTest.readyMenu(fixture);
        var hub = new ComputerScratchScreen(menu, (Inventory)get(fixture, "editorInventory"), Component.literal("Computer"));
        Screen[] shown = {hub}; set(hub, "navigator", (Consumer<Screen>)(next -> shown[0] = next));
        ComputerScratchNavigationTest.initialize(hub); set(hub, "requested", true); set(menu, "responseVersion", 1L);
        hub.containerTick(); var blocks = assertInstanceOf(ComputerFlowScreen.class, shown[0]);
        var workspace = (ProgramWorkspace)get(hub, "workspace");
        assertEquals(FlowProgram.Execution.REGISTER_V1, workspace.flow().orElseThrow().program(workspace.snapshot().archive()).execution());
        ComputerFlowScreenTest.initialize(blocks, 426);
        ComputerFlowScreenTest.click(blocks, "add_assign");
        var document = workspace.snapshot(); assertEquals(1, document.flow().orElseThrow().steps().size());
        var language = net.minecraft.locale.Language.getInstance();
        try {
            net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load("en_us")));
            String hint = tooltipText(ComputerFlowScreenTest.button(blocks, "install"));
            assertTrue(hint.contains("6 / 1000"), "The install control must disclose generated code words rather than block count");
            assertTrue(hint.contains("ISA-1"));
            String renderedHint = ComputerFlowScreenTest.button(blocks, "install").getToolTip().stream()
                .map(Component::getString).collect(java.util.stream.Collectors.joining("\n"));
            assertEquals(hint, renderedHint, "Create's screen renders its own tooltip list, not the vanilla narration tooltip");
        } finally { net.minecraft.locale.Language.inject(language); }
        var call = (FlowProgram.Evaluate)document.flow().orElseThrow().steps().getFirst();
        var draft = (FlowDraft)get(blocks, "draft"); draft.undo(); draft.redo();
        assertEquals(document.flow().orElseThrow(), draft.snapshot(), "Undo/redo must retain the assembly target");
        var decoded = ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, SubgraphWorkspaceTest.LIMITS), SubgraphWorkspaceTest.LIMITS);
        assertEquals(document, decoded);
        var host = new ComputerBlockHost(() -> {});
        var limits = new ComputerHostLimits(new ResourceLimits(1000, 100000, 1), SubgraphWorkspaceTest.LIMITS);
        var body = decoded.flow().orElseThrow(); host.installFlow(body.program(decoded.archive()), body.initial(), limits, true);
        assertTrue(host.flowSnapshot().orElseThrow().state().paused());
        assertTrue(host.flowSnapshot().orElseThrow().state().assembly().isPresent());
        host.step(limits, true);
        var restored = new ComputerBlockHost(() -> {}); restored.read(host.write()); restored.tick(limits, true);
        assertEquals(host.flowSnapshot(), restored.flowSnapshot());
        restored.step(limits, true);
        var done = restored.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE, done.status()); assertEquals(2, done.work());
        assertEquals(new DataValue.IntegerValue(0), done.locals().field(call.bindings().orElseThrow().returns().get("value")));
        assertTrue(done.expression().isEmpty());
        var status = ComputerStatusView.flow(restored.flowSnapshot(), true, Optional.empty());
        assertEquals(new DataValue.TextValue("register_v1"), status.field("execution"));
        assertEquals(new DataValue.IntegerValue(2), status.field("cycles"));
        assertEquals(new DataValue.IntegerValue(done.assembly().orElseThrow().pc()), status.field("pc"));
    }

    private static String tooltipText(net.minecraft.client.gui.components.AbstractWidget widget) {
        var result = new StringBuilder(); assertNotNull(widget.getTooltip());
        widget.getTooltip().updateNarration(new net.minecraft.client.gui.narration.NarrationElementOutput() {
            @Override public void add(net.minecraft.client.gui.narration.NarratedElementType type, net.minecraft.client.gui.narration.NarrationThunk<?> thunk) { thunk.getText(result::append); }
            @Override public net.minecraft.client.gui.narration.NarrationElementOutput nest() { return this; }
        });
        return result.toString();
    }
}
