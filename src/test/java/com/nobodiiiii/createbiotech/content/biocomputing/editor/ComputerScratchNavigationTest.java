package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.*;

class ComputerScratchNavigationTest {
    @Test void savedDraftReopensWithoutInstallingAndSaveAcknowledgesOnlyTheSentVersion() throws Exception {
        var fixture = screen(); var menu = ComputerFlowScreenTest.readyMenu(fixture);
        var authority = menu.response().orElseThrow(); set(menu, "nonce", authority.nonce()); set(menu, "responseVersion", 1L);
        var requests = new ArrayList<ProgramDraftRequest>(); menu.requestSavedDraft(requests::add);
        var source = new ProgramWorkspace(); source.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var document = source.snapshot();
        var hub = new ComputerScratchScreen(menu, (Inventory)get(fixture, "editorInventory"), Component.empty());
        Screen[] shown = {hub}; set(hub, "navigator", (Consumer<Screen>)(s -> shown[0] = s));
        initialize(hub); set(hub, "requested", true); hub.containerTick();
        assertNull(get(hub, "workspace"), "Wait for the independent saved draft instead of flashing the installed source");
        menu.accept(new ProgramDraftResponse(menu.containerId, authority.nonce(), requests.getFirst().intent(), 7,
            "ready", "", ProgramDocumentWire.encode(document, authority.archiveLimits())));
        hub.containerTick();
        var workspace = (ProgramWorkspace)get(hub, "workspace");
        assertEquals(document, workspace.snapshot()); assertInstanceOf(ComputerFlowScreen.class, shown[0]);
        set(hub, "draftSender", (Consumer<ProgramDraftRequest>)requests::add);
        var blocks = (ComputerFlowScreen)shown[0]; ComputerFlowScreenTest.initialize(blocks, 320);
        ComputerFlowScreenTest.click(blocks, "save_draft");
        assertEquals(2, requests.size()); assertTrue(requests.getLast().save()); assertEquals(7, requests.getLast().revision());
        assertSame(authority, menu.response().orElseThrow(), "Saving must not change installed-source authority");
        workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
        menu.accept(new ProgramDraftResponse(menu.containerId, authority.nonce(), requests.getLast().intent(), 8, "saved", "", new byte[0]));
        blocks.containerTick();
        assertEquals(document, get(hub, "savedBaseline"), "A late save ACK cannot mark subsequent edits saved");
        assertNotEquals(document, workspace.snapshot());
    }
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }

    @Test void returningFromRecoveryInitializesOnlyTheMissingDraftFromAcknowledgedInstalledSource() throws Exception {
        var fixture = screen(); var menu = ComputerFlowScreenTest.readyMenu(fixture);
        set(menu, "controlResponse", Optional.empty());
        var authority = menu.response().orElseThrow(); var nonce = authority.nonce();
        set(menu, "nonce", nonce); set(menu, "latestIntent", 1L);
        var failed = new ProgramEditorResponse(menu.containerId, nonce, 1, 5, "unavailable", "No installed program snapshot",
            authority.hardware(), authority.archiveLimits(), new byte[0]);
        menu.accept(failed);
        var hub = new ComputerScratchScreen(menu, (Inventory)get(fixture, "editorInventory"), Component.empty());
        Screen[] shown = {hub}; set(hub, "navigator", (Consumer<Screen>)(s -> shown[0] = s));
        initialize(hub); set(hub, "requested", true); hub.containerTick();
        assertNull(get(hub, "workspace"));
        var inputs = new DataValue.RecordValue(Map.of("draft_input", new DataValue.TextValue("keep")));
        menu.rememberDebuggerInputs(inputs);
        set(menu, "latestControlIntent", 2L); set(menu, "latestControlAction", ComputerControlSession.Action.RESET);
        menu.accept(new ProgramStatusResponse(menu.containerId, nonce, 2, 6, "ok", "", new byte[0]));
        var requests = new ArrayList<ProgramEditorRequest>(); menu.requestInstalledSource(6, requests::add);
        var request = requests.getFirst(); assertFalse(request.install());
        var installed = new ProgramWorkspace(); installed.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var document = installed.snapshot();
        var source = new ProgramEditorResponse(menu.containerId, nonce, request.intent(), 6, "ready", "",
            authority.hardware(), authority.archiveLimits(), ProgramDocumentWire.encode(document, authority.archiveLimits()));
        long resultVersion = menu.responseVersion(); menu.accept(source); hub.containerTick();
        var workspace = (ProgramWorkspace)get(hub, "workspace"); assertNotNull(workspace, "Return must not require closing the authorized menu");
        assertEquals(document, workspace.snapshot()); assertInstanceOf(ComputerFlowScreen.class, shown[0]);
        assertSame(failed, menu.response().orElseThrow(), "Read-only recovery must not rewrite editor/installation feedback");
        assertEquals(resultVersion, menu.responseVersion()); assertEquals(inputs, menu.debuggerInputs());
        assertEquals(1, requests.size()); assertEquals(request.intent(), get(menu, "latestIntent"));
        click(hub, "program_package"); assertInstanceOf(ComputerProgramDocumentScreen.class, shown[0]);
        shown[0] = hub; hub.containerTick(); assertSame(hub, shown[0], "Do not reopen Scratch every tick");
        assertSame(workspace, get(hub, "workspace"));
    }

    @Test void blocksOfferDocumentsInsteadOfOpeningTheExpressionGraph() throws Exception {
        var fixture = screen(); var menu = ComputerFlowScreenTest.readyMenu(fixture);
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        int[] documents = {0};
        var blocks = new ComputerFlowScreen(menu, (Inventory)get(fixture, "editorInventory"), Component.empty(), workspace,
            ignored -> fail("Navigation must not replace the draft"), () -> {}, () -> {}, () -> documents[0]++, () -> {});
        ComputerFlowScreenTest.initialize(blocks, 320);
        assertFalse(keys(blocks).contains("create_biotech.biocomputing.flow_editor.expressions"));
        ComputerFlowScreenTest.click(blocks, "documents");
        assertEquals(1, documents[0]);
        assertFalse((boolean)get(blocks, "waiting"), "Navigation must not install a program");
        ComputerFlowScreenTest.bounds(blocks);
    }

    @Test void recoveredSourceNeverReplacesAnExistingEditedDraft() throws Exception {
        var fixture = screen(); var menu = ComputerFlowScreenTest.readyMenu(fixture);
        var authority = menu.response().orElseThrow(); var nonce = authority.nonce();
        set(menu, "nonce", nonce); set(menu, "latestIntent", 1L);
        var hub = new ComputerScratchScreen(menu, (Inventory)get(fixture, "editorInventory"), Component.empty());
        Screen[] shown = {hub}; set(hub, "navigator", (Consumer<Screen>)(s -> shown[0] = s));
        initialize(hub); set(hub, "requested", true); set(menu, "responseVersion", 1L); hub.containerTick();
        var workspace = (ProgramWorkspace)get(hub, "workspace");
        var node = workspace.root().add(new ComputationGraph.Constant(new DataValue.TextValue("unsaved edit")), 12, 34);
        workspace.root().output("value", node);
        var edited = workspace.snapshot(); shown[0] = hub;
        var failure = new ProgramEditorResponse(menu.containerId, nonce, 1, 5, "unavailable", "", authority.hardware(), authority.archiveLimits(), new byte[0]);
        menu.accept(failure);
        set(menu, "latestControlIntent", 2L);
        menu.accept(new ProgramStatusResponse(menu.containerId, nonce, 2, 6, "ok", "", new byte[0]));
        var requests = new ArrayList<ProgramEditorRequest>(); menu.requestInstalledSource(6, requests::add);
        menu.accept(new ProgramEditorResponse(menu.containerId, nonce, requests.getFirst().intent(), 6, "ready", "",
            authority.hardware(), authority.archiveLimits(), new byte[0]));
        hub.containerTick();
        assertSame(workspace, get(hub, "workspace")); assertEquals(edited, workspace.snapshot());
        assertSame(hub, shown[0]); assertSame(failure, menu.response().orElseThrow());
    }

    @Test void recoveryRequiresSuccessfulMatchingRevisionEvenWhenSourceArrivesFirst() throws Exception {
        var fixture = screen(); var menu = ComputerFlowScreenTest.readyMenu(fixture);
        set(menu, "controlResponse", Optional.empty());
        var authority = menu.response().orElseThrow(); var nonce = authority.nonce();
        set(menu, "nonce", nonce); set(menu, "latestIntent", 1L);
        menu.accept(new ProgramEditorResponse(menu.containerId, nonce, 1, 5, "unavailable", "", authority.hardware(), authority.archiveLimits(), new byte[0]));
        var hub = new ComputerScratchScreen(menu, (Inventory)get(fixture, "editorInventory"), Component.empty());
        Screen[] shown = {hub}; set(hub, "navigator", (Consumer<Screen>)(s -> shown[0] = s));
        initialize(hub); set(hub, "requested", true); hub.containerTick();
        var requests = new ArrayList<ProgramEditorRequest>(); menu.requestInstalledSource(6, requests::add);
        menu.accept(new ProgramEditorResponse(menu.containerId, nonce, requests.getFirst().intent(), 6, "ready", "",
            authority.hardware(), authority.archiveLimits(), new byte[0]));
        hub.containerTick(); assertNull(get(hub, "workspace"));
        set(menu, "latestControlIntent", 2L);
        menu.accept(new ProgramStatusResponse(menu.containerId, nonce, 2, 5, "ok", "", new byte[0]));
        hub.containerTick(); assertNull(get(hub, "workspace"), "Old revision cannot authorize a newer source");
        set(menu, "latestControlIntent", 3L);
        menu.accept(new ProgramStatusResponse(menu.containerId, nonce, 3, 6, "unavailable", "", new byte[0]));
        hub.containerTick(); assertNull(get(hub, "workspace"), "Failed recovery cannot initialize the draft");
        set(menu, "latestControlIntent", 4L);
        menu.accept(new ProgramStatusResponse(menu.containerId, nonce, 4, 6, "ok", "", new byte[0]));
        hub.containerTick(); assertNotNull(get(hub, "workspace")); assertInstanceOf(ComputerFlowScreen.class, shown[0]);
    }

    @Test void resetReplyAfterReturningToHubRequestsSourceOnceAndNeverInstalls() throws Exception {
        var fixture = screen(); var menu = ComputerFlowScreenTest.readyMenu(fixture);
        var authority = menu.response().orElseThrow(); var nonce = authority.nonce();
        set(menu, "nonce", nonce); set(menu, "latestIntent", 1L); set(menu, "controlResponse", Optional.empty());
        menu.accept(new ProgramEditorResponse(menu.containerId, nonce, 1, 5, "unavailable", "", authority.hardware(), authority.archiveLimits(), new byte[0]));
        var requests = new ArrayList<ProgramEditorRequest>();
        var hub = new ComputerScratchScreen(menu, (Inventory)get(fixture, "editorInventory"), Component.empty(), requests::add);
        Screen[] shown = {hub}; set(hub, "navigator", (Consumer<Screen>)(s -> shown[0] = s));
        initialize(hub); set(hub, "requested", true); hub.containerTick();
        // REFRESH is successful even while the runtime is unavailable. Do not poison the revision's source read.
        set(menu, "latestControlIntent", 1L);
        menu.accept(new ProgramStatusResponse(menu.containerId, nonce, 1, 5, "ok", "", ProgramEditorWire.encodeValue(
            new DataValue.RecordValue(Map.of("available", new DataValue.BooleanValue(false))), authority.archiveLimits())));
        hub.containerTick(); assertTrue(requests.isEmpty());
        // The RESET response reaches the same menu only after the child debugger was closed.
        set(menu, "latestControlIntent", 2L); set(menu, "latestControlAction", ComputerControlSession.Action.RESET);
        menu.accept(new ProgramStatusResponse(menu.containerId, nonce, 2, 5, "ok", "", ProgramEditorWire.encodeValue(
            new DataValue.RecordValue(Map.of("available", new DataValue.BooleanValue(true))), authority.archiveLimits())));
        hub.containerTick(); hub.containerTick();
        assertEquals(1, requests.size(), "The parent must initiate the missing source-only read");
        var request = requests.getFirst(); assertFalse(request.install()); assertEquals(5, request.revision());
        menu.accept(new ProgramEditorResponse(menu.containerId, nonce, request.intent(), 5, "ready", "",
            authority.hardware(), authority.archiveLimits(), new byte[0]));
        hub.containerTick(); assertNotNull(get(hub, "workspace")); assertInstanceOf(ComputerFlowScreen.class, shown[0]);
        hub.containerTick(); assertEquals(1, requests.size());
    }

    @Test void loadingOpeningReturningAndImportingNeverFallBackToAGraphCanvas() throws Exception {
        var fixture = screen(); var menu = ComputerFlowScreenTest.readyMenu(fixture);
        var hub = new ComputerScratchScreen(menu, (Inventory)get(fixture, "editorInventory"), Component.literal("Computer"));
        Screen[] shown = {hub}; set(hub, "navigator", (Consumer<Screen>)(next -> shown[0] = next));
        initialize(hub); assertFalse(keys(hub).contains("create_biotech.biocomputing.editor.add"));
        set(hub, "requested", true); set(menu, "responseVersion", 1L);
        hub.containerTick();
        var blocks = assertInstanceOf(ComputerFlowScreen.class, shown[0]);
        var workspace = (ProgramWorkspace)get(hub, "workspace"); var original = workspace.snapshot();
        blocks.onClose(); assertSame(hub, shown[0]);
        click(hub, "program_package");
        var documents = assertInstanceOf(ComputerProgramDocumentScreen.class, shown[0]);
        documents.onClose(); assertSame(hub, shown[0]);
        click(hub, "debugger");
        var debugger = assertInstanceOf(ComputerExecutionScreen.class, shown[0]);
        assertSame(hub, get(debugger, "parent"));
        assertEquals(original, workspace.snapshot());

        // Opening an old document must neither delete it nor resurrect the retired graph UI.
        var legacyWorkspace = new ProgramWorkspace();
        var constant = legacyWorkspace.root().add(new com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph.Constant(
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue(7)), 0, 0);
        legacyWorkspace.root().output("value", constant);
        var legacy = legacyWorkspace.snapshot();
        hub.importDocument(legacy); shown[0] = hub;
        click(hub, "flow");
        var legacyBlocks = assertInstanceOf(ComputerFlowScreen.class, shown[0]);
        ComputerFlowScreenTest.initialize(legacyBlocks, 320);
        assertTrue(ComputerFlowScreenTest.button(legacyBlocks, "new_flow").active);
        legacyBlocks.onClose(); assertSame(hub, shown[0]);
        assertEquals(legacy, ((ProgramWorkspace)get(hub, "workspace")).snapshot());
        assertEquals(1L, menu.responseVersion(), "Local navigation/import must not submit or install");
        ComputerFlowScreenTest.bounds(hub);
    }

    @Test void terminalPreviewCancelAndApplyReturnToScratchWithoutReplacingOrInstallingTheProgram() throws Exception {
        var fixture = screen(); var menu = ComputerFlowScreenTest.readyMenu(fixture);
        var hub = new ComputerScratchScreen(menu, (Inventory)get(fixture, "editorInventory"), Component.empty());
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        set(hub, "workspace", workspace);
        Screen[] shown = {hub}; set(hub, "navigator", (Consumer<Screen>)(next -> shown[0] = next));
        initialize(hub);
        var original = workspace.snapshot(); long responseVersion = menu.responseVersion();

        clickKey(hub, "create_biotech.biocomputing.terminal.edit");
        var editor = assertInstanceOf(ComputerTerminalEditorScreen.class, shown[0]);
        assertSame(hub, get(editor, "parent"));
        set(editor, "navigator", (Consumer<Screen>)(next -> shown[0] = next));
        set(editor, "width", 320); set(editor, "height", 420);
        set(editor, "minecraft", net.minecraft.client.Minecraft.getInstance());
        set(editor, "font", net.minecraft.client.Minecraft.getInstance().font); editor.init();
        clickKey(editor, "create_biotech.biocomputing.terminal.installed");
        var preview = assertInstanceOf(ComputerTerminalScreen.class, shown[0]);
        set(preview, "navigator", (Consumer<Screen>)(next -> shown[0] = next));
        preview.onClose(); assertSame(editor, shown[0]);
        clickKey(editor, "create_biotech.biocomputing.terminal.add.text");
        clickKey(editor, "create_biotech.biocomputing.terminal.cancel");
        assertSame(hub, shown[0]); assertEquals(original, workspace.snapshot());

        clickKey(hub, "create_biotech.biocomputing.terminal.edit");
        editor = assertInstanceOf(ComputerTerminalEditorScreen.class, shown[0]);
        set(editor, "width", 320); set(editor, "height", 420);
        set(editor, "minecraft", net.minecraft.client.Minecraft.getInstance());
        set(editor, "font", net.minecraft.client.Minecraft.getInstance().font); editor.init();
        clickKey(editor, "create_biotech.biocomputing.terminal.add.text");
        clickKey(editor, "create_biotech.biocomputing.terminal.apply");
        assertSame(hub, shown[0]);
        assertEquals(original.flow(), workspace.snapshot().flow());
        assertEquals(original.archive(), workspace.snapshot().archive());
        assertTrue(workspace.terminal().orElseThrow().nodes().values().stream()
            .anyMatch(com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition.Text.class::isInstance));
        click(hub, "flow"); assertInstanceOf(ComputerFlowScreen.class, shown[0]);
        assertEquals(responseVersion, menu.responseVersion(), "Terminal navigation only edits the local layout draft");
    }

    static void initialize(ComputerScratchScreen screen) throws Exception {
        set(screen, "width", 320); set(screen, "height", 260);
        set(screen, "minecraft", net.minecraft.client.Minecraft.getInstance());
        set(screen, "font", net.minecraft.client.Minecraft.getInstance().font); screen.init();
    }
    @Test void fieldSelectionCreatesAParkedScratchBlockNotAnInvisibleNamedGraph() throws Exception {
        var fixture = screen(); var menu = ComputerFlowScreenTest.readyMenu(fixture);
        var hub = new ComputerScratchScreen(menu, (Inventory)get(fixture, "editorInventory"), Component.empty());
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        set(hub, "workspace", workspace); initialize(hub);
        var frozen = new DataValue.RecordValue(Map.of("sensor", new DataValue.RecordValue(Map.of(
            "levels", new DataValue.ListValue(List.of(new DataValue.IntegerValue(6)))))));
        hub.importValuePath(frozen, new ValuePath("sensor", List.of()).key("levels").index(0));
        assertTrue(workspace.entryNames().isEmpty(), "Field selection must not require a hidden expression graph");
        var body = workspace.flow().orElseThrow();
        assertTrue(body.steps().isEmpty(), "Generated block stays parked until the player connects it");
        assertTrue(body.initial().fields().isEmpty(), "Frozen sample is not installed as a live input");
        assertEquals(1, body.canvas().detached().size());
        var step = body.canvas().detached().getFirst().steps().getFirst();
        var graph = body.inline().get(step.sourceId()); assertNotNull(graph);
        assertTrue(graph.nodes().values().stream().noneMatch(ComputationGraph.Constant.class::isInstance));
        assertTrue(graph.nodes().values().stream().anyMatch(ComputationGraph.Index.class::isInstance));
        var document = workspace.snapshot();
        assertEquals(document, ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, SubgraphWorkspaceTest.LIMITS), SubgraphWorkspaceTest.LIMITS));
        assertThrows(RuntimeException.class, () -> hub.importValuePath(frozen, new ValuePath("sensor", List.of()).key("absent")));
        assertEquals(document, workspace.snapshot(), "Invalid selection leaves no half-created block");
    }
    private static void click(Screen screen, String name) {
        clickKey(screen, "create_biotech.biocomputing.editor." + name);
    }
    private static void clickKey(Screen screen, String name) {
        var button = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents key
                && key.getKey().equals(name)).findFirst().orElseThrow(() -> new AssertionError("Missing control: " + name));
        assertTrue(button.active); button.onClick(0, 0);
    }

    private static Set<String> keys(Screen screen) {
        var result = new HashSet<String>();
        screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .forEach(button -> { if (button.getMessage().getContents() instanceof TranslatableContents key) result.add(key.getKey()); });
        return result;
    }
}
