package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.foundation.gui.*;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;

/** Real widget callbacks and mouse gestures; replaces only Minecraft/font bootstrap, not editor behavior. */
class ComputerProgramScreenTest {
    @Test void paletteSearchTabsToResultAndEnterOnlyOpensNodeConfiguration() throws Exception {
        var screen = screen(); click(screen, "add");
        var search = (EditBox)get(screen, "paletteSearch");
        assertSame(search, screen.getFocused());
        for (char c : "integer".toCharArray()) assertTrue(screen.charTyped(c, 0));
        assertTrue(screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_TAB, 0, 0));
        assertSame(button(screen, "create_biotech.biocomputing.editor.kind.integer"), screen.getFocused(),
            () -> "Actual focus: " + ((net.minecraft.client.gui.components.AbstractWidget)screen.getFocused()).getMessage().getString());
        assertTrue(((ProgramDraft)get(screen, "draft")).nodes().isEmpty());
        assertTrue(screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0));
        var draft = (ProgramDraft)get(screen, "draft");
        assertEquals(1, draft.nodes().size());
        assertNull(draft.nodes().getFirst().definition());
        assertTrue(screen.getFocused() == null || screen.children().contains(screen.getFocused()));
        assertFalse((boolean)get(screen, "requested"));
    }

    @Test void paletteClickWithSmallPointerMovementStillSelectsThePressedRow() throws Exception {
        var screen = screen(); click(screen, "add");
        var row = button(screen, "create_biotech.biocomputing.editor.kind.integer");
        double x = row.getX() + 5, y = row.getY() + 5;
        assertTrue(screen.mouseClicked(x, y, 0));
        assertTrue(screen.mouseDragged(x + .5, y + .5, 0, .5, .5));
        var draft = (ProgramDraft)get(screen, "draft");
        assertTrue(draft.nodes().isEmpty(), "No insertion before release");
        assertTrue(screen.mouseReleased(x + .5, y + .5, 0));
        assertEquals(1, draft.nodes().size());
        assertNull(draft.nodes().getFirst().definition(), "Selection must not silently configure");
        assertFalse((boolean)get(screen, "requested"));
    }

    @Test void paletteDropUsesReleaseCoordinatesEvenWhenIntermediateMoveCallbacksAreCoalesced() throws Exception {
        var screen = screen(); click(screen, "add"); var row = button(screen, "create_biotech.biocomputing.editor.kind.integer");
        screen.mouseClicked(row.getX() + 2, row.getY() + 2, 0);
        screen.mouseReleased(coordinate(screen, "canvasX") + 45, coordinate(screen, "canvasY") + 30, 0);
        var draft = (ProgramDraft)get(screen, "draft"); assertEquals(1, draft.nodes().size());
        assertEquals(45, draft.nodes().getFirst().x()); assertEquals(30, draft.nodes().getFirst().y());
    }
    @Test void searchHintIsClippedToItsOwnFieldEvenForUntranslatedOrLongLabels() throws Exception {
        var screen = screen(); var metrics = new Metrics() {
            @Override public int width(String value) { return value.length() * 6; }
            @Override public String plainSubstrByWidth(String value, int width) { return value.substring(0, Math.min(value.length(), Math.max(0, width / 6))); }
        };
        set(screen, "font", metrics); set(screen, "width", 320); screen.init(); click(screen, "add");
        var search = (EditBox)get(screen, "paletteSearch");
        assertTrue(metrics.width(((Component)get(search, "hint")).getString()) <= search.getWidth() - 8);
    }
    @Test void realAddButtonDispatchLeavesTypingFocusInSearchRatherThanRemovedToolbarButton() throws Exception {
        var screen = screen(); var add = button(screen, "create_biotech.biocomputing.editor.add");
        screen.mouseClicked(add.getX() + 2, add.getY() + 2, 0);
        assertSame(get(screen, "paletteSearch"), screen.getFocused());
        for (char c : "DECIMAL".toCharArray()) assertTrue(screen.charTyped(c, 0));
        assertEquals("DECIMAL", ((EditBox)get(screen, "paletteSearch")).getValue());
        assertNotNull(button(screen, "create_biotech.biocomputing.editor.kind.decimal"));
    }
    @Test void localizedNameAndStableIdBothSearchTheActualClickableNode() throws Exception {
        var oldLanguage = net.minecraft.locale.Language.getInstance();
        var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/resources/assets/create_biotech/lang/zh_cn.json"))).getAsJsonObject();
        try {
            net.minecraft.locale.Language.inject(new net.minecraft.locale.Language() {
                @Override public String getOrDefault(String key, String fallback) { return json.has(key) ? json.get(key).getAsString() : fallback; }
                @Override public boolean has(String key) { return json.has(key); }
                @Override public boolean isDefaultRightToLeft() { return false; }
                @Override public net.minecraft.util.FormattedCharSequence getVisualOrder(FormattedText text) {
                    return net.minecraft.util.FormattedCharSequence.forward(text.getString(), Style.EMPTY);
                }
            });
            var screen = screen(); click(screen, "add"); var search = (EditBox)get(screen, "paletteSearch");
            search.setValue("整数"); assertNotNull(button(screen, "create_biotech.biocomputing.editor.kind.integer"));
            search.setValue("DECIMAL"); var row = button(screen, "create_biotech.biocomputing.editor.kind.decimal");
            screen.mouseClicked(row.getX() + 2, row.getY() + 2, 0);
            screen.mouseReleased(row.getX() + 2, row.getY() + 2, 0);
            assertEquals(1, ((ProgramDraft)get(screen, "draft")).nodes().size());
            ((EditBox)get(screen, "parameter")).setValue("2.5"); click(screen, "configure");
            assertEquals(new ComputationGraph.Constant(new DataValue.DecimalValue(2.5)), ((ProgramDraft)get(screen, "draft")).nodes().getFirst().definition());
        } finally { net.minecraft.locale.Language.inject(oldLanguage); }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"outside", "escape", "resize"})
    void cancelledPaletteGesturesNeverLeaveUnconfiguredNodes(String cancellation) throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft"); click(screen, "add");
        var row = button(screen, "create_biotech.biocomputing.editor.kind.integer");
        screen.mouseClicked(row.getX() + 2, row.getY() + 2, 0);
        double x = coordinate(screen, "canvasX") + 30, y = coordinate(screen, "canvasY") + 30;
        screen.mouseDragged(x, y, 0, 20, 10);
        if (cancellation.equals("escape")) screen.keyPressed(256, 0, 0);
        if (cancellation.equals("resize")) { set(screen, "width", 426); screen.init(); }
        screen.mouseReleased(cancellation.equals("outside") ? -1 : x, y, 0);
        assertTrue(draft.nodes().isEmpty()); assertFalse((boolean)get(screen, "requested"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void searchablePalettePaginatesAllKindsAndKeepsWidgetsInBounds(int width) throws Exception {
        var screen = screen(); set(screen, "width", width); set(screen, "height", 240); screen.init(); click(screen, "add");
        Set<String> seen = new HashSet<>();
        while (true) {
            assertWidgetBounds(screen);
            for (var child : screen.children()) if (child instanceof CreateTextButton button
                && button.getMessage().getContents() instanceof TranslatableContents label && label.getKey().contains(".editor.kind.")) seen.add(label.getKey());
            var next = literalButton(screen, ">"); if (!next.active) break; next.onClick(0, 0);
        }
        assertEquals(34, seen.size());
        assertTrue(seen.contains("create_biotech.biocomputing.editor.kind.nbt"));
        assertTrue(seen.contains("create_biotech.biocomputing.editor.kind.dynamic_field"));
        assertTrue(seen.contains("create_biotech.biocomputing.editor.kind.distinct"));
        var search = (EditBox)get(screen, "paletteSearch"); screen.setFocused(search); search.setValue("  SORT  ");
        assertSame(search, screen.getFocused()); assertEquals(0, get(screen, "palettePage"));
        assertNotNull(button(screen, "create_biotech.biocomputing.editor.kind.sort"));
        button(screen, "create_biotech.biocomputing.palette.category.data").onClick(0, 0);
        set(screen, "width", 426); screen.init(); assertWidgetBounds(screen);
        assertEquals("  SORT  ", ((EditBox)get(screen, "paletteSearch")).getValue());
        assertNotNull(button(screen, "create_biotech.biocomputing.editor.kind.sort"));
        button(screen, "create_biotech.biocomputing.palette.category.state").onClick(0, 0);
        assertFalse(literalButton(screen, ">").active); assertFalse(literalButton(screen, "<").active);
        assertTrue(((ProgramDraft)get(screen, "draft")).nodes().isEmpty());
        click(screen, "back"); assertNull(get(screen, "editing"));
    }

    @Test void replacingNodeTypeCanBeCancelledWithoutLosingConnectionsOrUnappliedText() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        String source = draft.add(ProgramDraftTest.number(4), 0, 0);
        String id = draft.add(new ComputationGraph.Field("source", "old"), 150, 0); draft.connect(id, "source", source);
        openNode(screen, id); ((EditBox)get(screen, "parameter")).setValue("not-yet-applied");
        var before = draft.node(id);
        click(screen, "kind"); ((EditBox)get(screen, "paletteSearch")).setValue("integer");
        screen.keyPressed(256, 0, 0);
        assertEquals(before, draft.node(id));
        assertEquals("not-yet-applied", ((EditBox)get(screen, "parameter")).getValue());
        click(screen, "kind"); selectPaletteKind(screen, "index");
        assertEquals(before, draft.node(id), "Type selection remains unapplied until Configure");
        ((EditBox)get(screen, "parameter")).setValue("2"); click(screen, "configure");
        assertEquals(new ComputationGraph.Index("source", 2), draft.node(id).definition());
        assertEquals(Map.of("source", source), draft.node(id).connections());
    }
    @Test void paletteDragUsesCanvasTransformAndCommitsOnlyAtValidRelease() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        click(screen, "add");
        var viewport = (CanvasViewport)get(screen, "viewport"); viewport.zoomAt(0, 0, 8); viewport.pan(20, -10);
        var search = screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
        search.setValue("integer");
        var row = button(screen, "create_biotech.biocomputing.editor.kind.integer");
        double startX = row.getX() + 5, startY = row.getY() + 5;
        double x = coordinate(screen, "canvasX") + 80, y = coordinate(screen, "canvasY") + 50;
        assertTrue(screen.mouseClicked(startX, startY, 0));
        assertTrue(draft.nodes().isEmpty(), "Pressing a palette row is not yet insertion");
        assertTrue(screen.mouseDragged(x, y, 0, x - startX, y - startY));
        assertTrue(draft.nodes().isEmpty());
        assertTrue(screen.mouseReleased(x, y, 0));
        assertEquals(1, draft.nodes().size()); var node = draft.nodes().getFirst();
        assertEquals(30, node.x()); assertEquals(30, node.y());
        assertEquals(0, get(screen, "kind")); assertFalse((boolean)get(screen, "requested"));
    }

    private static int coordinate(ComputerProgramScreen screen, String method) throws Exception {
        var m = ComputerProgramScreen.class.getDeclaredMethod(method); m.setAccessible(true); return (int)m.invoke(screen);
    }
    @Test void addOpensSearchWithoutCreatingAPlaceholderAndSelectionUsesOrdinaryParameters() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        click(screen, "add");
        assertTrue(draft.nodes().isEmpty(), "Opening the catalog must not edit the program");
        var search = screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
        search.setValue("INTEGER");
        button(screen, "create_biotech.biocomputing.editor.kind.integer").onClick(0, 0);
        assertEquals(1, draft.nodes().size());
        ((EditBox)get(screen, "parameter")).setValue("42"); click(screen, "configure");
        ((EditBox)get(screen, "outputName")).setValue("answer"); click(screen, "bind_output");
        var machine = new ComputationMachine(draft.snapshot().compile(ProgramDraftTest.ROOM));
        machine.begin(new DataValue.RecordValue(Map.of())); machine.tick();
        assertEquals(new DataValue.IntegerValue(42), machine.report().output().orElseThrow().field("answer"));
        assertFalse((boolean)get(screen, "requested"));
    }
    @Test void actualEntryButtonsCreateSwitchAndReturnWithoutInstalling() throws Exception {
        var screen = screen(); var workspace = (ProgramWorkspace)get(screen, "workspace");
        click(screen, "entries");
        ((EditBox)get(screen, "entryName")).setValue("message"); click(screen, "create_entry");
        assertEquals("message", get(screen, "activeEntry")); assertNull(get(screen, "activeGraph"));
        assertSame(workspace.entry("message"), get(screen, "draft"));
        String eventNode = workspace.entry("message").add(ProgramDraftTest.number(4), 17, 18);
        workspace.entry("message").output("event_out", eventNode);
        click(screen, "entries"); click(screen, "root_graph");
        assertNull(get(screen, "activeEntry")); assertSame(workspace.root(), get(screen, "draft"));
        assertFalse((boolean)get(screen, "requested"));
        click(screen, "entries");
        literalButton(screen, "message").onClick(0, 0);
        assertEquals(Map.of("event_out", eventNode), ((ProgramDraft)get(screen, "draft")).outputs());
    }

    @Test void openingPureModuleReturnsToOriginatingEntryAndSendScreenEditsThatEntry() throws Exception {
        var screen = screen(); var workspace = (ProgramWorkspace)get(screen, "workspace");
        var event = workspace.createEntry("timer");
        var key = workspace.createModule("pure");
        String value = workspace.graph(key).add(ProgramDraftTest.number(1), 0, 0);
        workspace.graph(key).output("value", value);
        click(screen, "entries"); literalButton(screen, "timer").onClick(0, 0);
        click(screen, "modules"); literalButton(screen, "pure @ 1").onClick(0, 0);
        assertEquals(key, get(screen, "activeGraph")); assertEquals("timer", get(screen, "activeEntry"));
        click(screen, "modules"); click(screen, "return_entry");
        assertSame(event, get(screen, "draft")); assertEquals("timer", get(screen, "activeEntry"));

        var opened = new net.minecraft.client.gui.screens.Screen[1];
        set(screen,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(next->opened[0]=next));
        button(screen,"create_biotech.biocomputing.editor.sends").onClick(0,0);
        assertSame(event, get(opened[0], "draft"));
    }

    @Test void explicitRootButtonFromModuleClearsOriginEntryWithoutLosingItsDraft() throws Exception {
        var screen = screen(); var workspace = (ProgramWorkspace)get(screen, "workspace");
        var event = workspace.createEntry("message");
        String saved = event.add(ProgramDraftTest.number(7), 12, 13); event.output("saved", saved);
        var key = workspace.createModule("pure");
        String value = workspace.graph(key).add(ProgramDraftTest.number(1), 0, 0);
        workspace.graph(key).output("value", value);
        click(screen, "entries"); literalButton(screen, "message").onClick(0, 0);
        click(screen, "modules"); literalButton(screen, "pure @ 1").onClick(0, 0);

        click(screen, "modules"); click(screen, "root_graph");

        assertNull(get(screen, "activeEntry")); assertNull(get(screen, "activeGraph"));
        assertSame(workspace.root(), get(screen, "draft"));
        assertEquals(Map.of("saved", saved), event.outputs());
        assertFalse((boolean)get(screen, "requested"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426})
    void compactModulePickerKeepsDistinctLongOriginReturnAndManualRootControlsInBounds(int width) throws Exception {
        var screen=screen();var workspace=(ProgramWorkspace)get(screen,"workspace");
        String origin="event-with-a-very-long-origin-name-0123456789";
        workspace.createEntry(origin);
        var key=workspace.createModule("pure");
        String value=workspace.graph(key).add(ProgramDraftTest.number(1),0,0);workspace.graph(key).output("value",value);
        click(screen,"entries");literalButton(screen,origin).onClick(0,0);
        click(screen,"modules");literalButton(screen,"pure @ 1").onClick(0,0);
        click(screen,"modules");set(screen,"width",width);set(screen,"height",240);screen.init();

        assertWidgetBounds(screen);
        assertNotSame(button(screen,"create_biotech.biocomputing.editor.return_entry"),
            button(screen,"create_biotech.biocomputing.editor.root_graph"));
        assertEquals(origin,get(screen,"activeEntry"));
    }

    @Test void explicitPathImportClearsEntryContextAndPreservesEventDraft() throws Exception {
        var screen = screen(); var workspace = (ProgramWorkspace)get(screen, "workspace");
        var event = workspace.createEntry("event-with-a-name-long-enough-to-require-clipping");
        String node = event.add(ProgramDraftTest.number(9), 70, 80); event.output("saved", node);
        click(screen, "entries"); literalButton(screen, "event-with-a-name-long-enough-to-require-clipping").onClick(0, 0);
        screen.importValuePath(new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(1))), new ValuePath("x", List.of()));
        assertNull(get(screen, "activeEntry")); assertNull(get(screen, "activeGraph"));
        assertSame(workspace.root(), get(screen, "draft")); assertEquals(Map.of("saved", node), event.outputs());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426})
    void compactEntryPickerKeepsLongLiteralNamesLosslessAcrossResize(int width) throws Exception {
        var screen=screen(); var workspace=(ProgramWorkspace)get(screen,"workspace");
        String name="event-with-a-very-long-literal-name-0123456789";
        workspace.createEntry(name);
        click(screen,"entries"); set(screen,"width",width); set(screen,"height",240); screen.init();
        assertWidgetBounds(screen); literalButton(screen,name).onClick(0,0);
        assertEquals(name,get(screen,"activeEntry"));
        set(screen,"width",640);set(screen,"height",480);screen.init();
        assertEquals(name,get(screen,"activeEntry")); assertSame(workspace.entry(name),get(screen,"draft"));
    }
    @Test void sendsToolbarIsRootOnlyAndReturnsToTheSameLocalWorkspace() throws Exception {
        var screen=screen(); var workspace=(ProgramWorkspace)get(screen,"workspace"); var root=workspace.root();
        var opened=new net.minecraft.client.gui.screens.Screen[1];set(screen,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(next->opened[0]=next));
        var sends=button(screen,"create_biotech.biocomputing.editor.sends"); assertTrue(sends.active);
        sends.onClick(0,0); assertInstanceOf(ComputerSendScreen.class,opened[0]);
        var sendScreen=(ComputerSendScreen)opened[0];assertSame(root,get(sendScreen,"draft"));
        var returned=new boolean[1];set(sendScreen,"close",(Runnable)()->returned[0]=true);sendScreen.onClose();assertTrue(returned[0]);
        click(screen,"modules"); ((EditBox)get(screen,"moduleName")).setValue("pure"); click(screen,"create_module");
        assertFalse(button(screen,"create_biotech.biocomputing.editor.sends").active);
    }
    @Test void generatedSnapshotPathGoesToRootEvenWhenEditorWasInsideAModule() throws Exception {
        var screen = screen(); var root = (ProgramDraft)get(screen, "draft");
        click(screen, "modules"); ((EditBox)get(screen, "moduleName")).setValue("inside"); click(screen, "create_module");
        var module = (ProgramDraft)get(screen, "draft");
        var snapshot = new DataValue.RecordValue(Map.of("sample", new DataValue.ListValue(List.of(new DataValue.IntegerValue(5)))));
        screen.importValuePath(snapshot, new ValuePath("sample", List.of()).index(0));
        assertSame(root, get(screen, "draft")); assertNull(get(screen, "activeGraph"));
        assertTrue(module.nodes().isEmpty()); assertEquals(2, root.nodes().size());
        var machine = new ComputationMachine(((ProgramWorkspace)get(screen, "workspace")).snapshot().archive().compile(ProgramDraftTest.ROOM));
        machine.begin(snapshot); machine.tick();
        assertEquals(new DataValue.IntegerValue(5), machine.report().output().orElseThrow().field("read_value"));
    }
    @Test void indexNodeRemainsEditableThroughTheOrdinaryNodePalette() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        addNode(screen, "index");
        ((EditBox)get(screen, "parameter")).setValue("2"); click(screen, "configure");
        assertEquals(new ComputationGraph.Index("source", 2), draft.nodes().getFirst().definition());
        ((EditBox)get(screen, "parameter")).setValue("-1"); click(screen, "configure");
        assertEquals(new ComputationGraph.Index("source", 2), draft.nodes().getFirst().definition());
    }
    private Object oldClient;
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        field(net.minecraft.server.Bootstrap.class, "isBootstrapped").setBoolean(null, true);
    }
    @BeforeEach void client() throws Exception {
        oldClient = field(Minecraft.class, "instance").get(null);
        var client = allocate(Minecraft.class); set(client, "font", new Metrics());
        set(client, "soundManager", allocate(SilentSounds.class));
        field(Minecraft.class, "instance").set(null, client);
    }
    @AfterEach void restore() throws Exception { field(Minecraft.class, "instance").set(null, oldClient); }

    @Test void addCreatesAnUnconfiguredCanvasNodeThenConfigurationAndNamedOutputBecomeAProgram() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        addNode(screen, "integer"); assertEquals(1, draft.nodes().size());
        assertNull(draft.nodes().getFirst().definition());
        ((EditBox)get(screen, "parameter")).setValue("42"); click(screen, "configure");
        ((EditBox)get(screen, "outputName")).setValue("answer"); click(screen, "bind_output");
        var compiled = draft.snapshot().compile(ProgramDraftTest.ROOM);
        var machine = new ComputationMachine(compiled);
        machine.begin(new DataValue.RecordValue(Map.of())); machine.tick();
        assertEquals(new DataValue.IntegerValue(42), machine.report().output().orElseThrow().field("answer"));
    }

    @Test void draggingUsesSharedZoomTransformAndDoesNotOpenTheNodeModal() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        String id = draft.add(ProgramDraftTest.number(3), 20, 20);
        var viewport = (CanvasViewport)get(screen, "viewport"); viewport.zoomAt(0, 0, 8);
        var canvasTop=ComputerProgramScreen.class.getDeclaredMethod("canvasTop");canvasTop.setAccessible(true);
        int left = (int)get(screen, "leftPos") + 8, top = (int)get(screen, "topPos") + (int)canvasTop.invoke(screen);
        assertTrue(screen.mouseClicked(left + 45, top + 45, 0));
        assertTrue(screen.mouseDragged(left + 65, top + 55, 0, 20, 10));
        assertTrue(screen.mouseReleased(left + 65, top + 55, 0));
        assertEquals(30, draft.node(id).x()); assertEquals(25, draft.node(id).y());
        assertNull(get(screen, "editing"));
    }

    @Test void choosingRecordThenTypingAKeyDoesNotInsertAnUnwantedEmptyField() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        addNode(screen, "record");
        ((EditBox)get(screen, "parameter")).setValue("total"); click(screen, "configure");
        assertEquals(Set.of("total"), draft.nodes().getFirst().ports());
    }

    @Test void invalidParameterRetainsNodeAndExposesTheModalDiagnostic() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        addNode(screen, "integer"); ((EditBox)get(screen, "parameter")).setValue("4"); click(screen, "configure");
        var before = draft.nodes().getFirst().definition();
        ((EditBox)get(screen, "parameter")).setValue("not-an-integer"); click(screen, "configure");
        assertEquals(before, draft.nodes().getFirst().definition());
        assertSame(get(screen, "notice"), get(screen, "validation"));
    }

    @Test void rebuildingAndReturningFromModalDoNotLeaveOldControlsOrFocusTargets() throws Exception {
        var screen = screen(); int canvasControls = screen.children().size();
        screen.init(); assertEquals(canvasControls, screen.children().size());
        addNode(screen, "integer"); int modalControls = screen.children().size();
        click(screen, "configure"); assertEquals(modalControls, screen.children().size());
        var input = (EditBox)get(screen, "parameter");
        // Real coordinate hit testing without invoking GLFW modifier-key polling in vanilla EditBox.onClick.
        assertSame(input, screen.getChildAt(input.getX() + 3, input.getY() + 3).orElseThrow());
        screen.setFocused(input);
        click(screen, "back"); assertEquals(canvasControls, screen.children().size());
        assertFalse(screen.children().contains(input));
        assertNull(screen.getFocused());
    }

    @Test void untouchedImportedLongTextConstantConfiguresWithoutChangingItsValue() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        String original = "head-" + "文".repeat(1500) + "-tail";
        String id = draft.add(new ComputationGraph.Constant(new DataValue.TextValue(original)), 20, 20);
        openNode(screen, id);

        click(screen, "configure");

        assertEquals(new ComputationGraph.Constant(new DataValue.TextValue(original)), draft.node(id).definition());
    }

    @Test void cursorMovementAndResizePreserveAnImportedLongTextConstantOnConfigure() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        String original = "head-" + "文".repeat(1500) + "-tail";
        String id = draft.add(new ComputationGraph.Constant(new DataValue.TextValue(original)), 20, 20);
        openNode(screen, id);
        assertEquals(original, get(screen, "parameterText"));

        ((EditBox)get(screen, "parameter")).moveCursorTo(0, false);
        set(screen, "width", 426); set(screen, "height", 300); screen.init();
        click(screen, "configure");

        assertEquals(new ComputationGraph.Constant(new DataValue.TextValue(original)), draft.node(id).definition());
    }

    @Test void cursorMovementAndResizePreserveAnImportedLongStructuredConstantOnConfigure() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        var fields = new LinkedHashMap<String, DataValue>();
        fields.put("message", new DataValue.TextValue("结构".repeat(800) + "-tail"));
        fields.put("flags", new DataValue.ListValue(List.of(new DataValue.BooleanValue(true), DataValue.NullValue.INSTANCE)));
        DataValue original = new DataValue.RecordValue(fields);
        String id = draft.add(new ComputationGraph.Constant(original), 20, 20);
        openNode(screen, id);
        assertEquals(EditorValueFormat.format(original), get(screen, "parameterText"));

        ((EditBox)get(screen, "parameter")).moveCursorTo(0, false);
        set(screen, "width", 320); set(screen, "height", 240); screen.init();
        click(screen, "configure");

        assertTrue(((Component)get(screen, "validation")).getString().isEmpty());
        assertEquals(new ComputationGraph.Constant(original), draft.node(id).definition());
    }

    @Test void wireEncodableStructuredValueKeepsEscapedEditorTextLongerThanTheInputBaseline() throws Exception {
        var screen = screen(); var workspace = (ProgramWorkspace)get(screen, "workspace"); var draft = workspace.root();
        DataValue original = new DataValue.RecordValue(Map.of("message", new DataValue.TextValue("\0".repeat(4200))));
        String formatted = EditorValueFormat.format(original);
        String id = draft.add(new ComputationGraph.Constant(original), 20, 20);
        draft.output("result", id);
        byte[] encoded = ProgramDocumentWire.encode(workspace.snapshot(),
            new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10_000, 1_000_000, 10_000_000));
        assertTrue(formatted.length() > ProgramEditorWire.MAX_BYTES);
        assertTrue(encoded.length < ProgramEditorWire.MAX_BYTES);
        openNode(screen, id);

        ((EditBox)get(screen, "parameter")).moveCursorTo(0, false);
        assertEquals(formatted, get(screen, "parameterText"));
        set(screen, "width", 426); set(screen, "height", 300); screen.init();

        assertEquals(formatted, ((EditBox)get(screen, "parameter")).getValue());
        assertEquals(new ComputationGraph.Constant(original), draft.node(id).definition());
    }

    @Test void cursorMovementAndResizePreserveAnImportedLongTextCellInitialValueOnSave() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        String original = "initial-" + "值".repeat(1500) + "-tail";
        draft.putCell("long_text", DataType.Scalar.TEXT, new DataValue.TextValue(original));
        click(screen, "declarations"); click(screen, "declaration_mode");
        literalButton(screen, "long_text").onClick(0, 0);
        assertEquals(original, get(screen, "initialText"));

        ((EditBox)get(screen, "declarationValue")).moveCursorTo(0, false);
        set(screen, "width", 426); set(screen, "height", 300); screen.init();
        click(screen, "save_declaration");

        assertEquals(new ProgramDefinition.Cell(DataType.Scalar.TEXT, new DataValue.TextValue(original)),
            draft.cells().get("long_text"));
    }

    @Test void replacingTheLastCharacterOfAnImportedLongTextConstantKeepsTheRestExact() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        String original = "head-" + "文".repeat(1500) + "-tail";
        String expected = original.substring(0, original.length() - 1) + "!";
        String id = draft.add(new ComputationGraph.Constant(new DataValue.TextValue(original)), 20, 20);
        openNode(screen, id);
        var parameter = (EditBox)get(screen, "parameter");

        parameter.moveCursorToEnd(false);
        parameter.deleteChars(-1);
        parameter.insertText("!");
        set(screen, "width", 426); set(screen, "height", 300); screen.init();
        click(screen, "configure");

        assertEquals(new ComputationGraph.Constant(new DataValue.TextValue(expected)), draft.node(id).definition());
    }

    @Test void namedRecordCanBeBuiltThroughModalPortSelectionAndCanvasSourceClicks() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        addNode(screen, "integer"); ((EditBox)get(screen, "parameter")).setValue("2"); click(screen, "configure");
        String a = draft.nodes().getLast().id(); place(draft, a, 20, 20); click(screen, "back");
        addNode(screen, "integer"); ((EditBox)get(screen, "parameter")).setValue("3"); click(screen, "configure");
        String b = draft.nodes().getLast().id(); place(draft, b, 20, 110); click(screen, "back");
        addNode(screen, "add"); click(screen, "configure");
        String sum = draft.nodes().getLast().id(); place(draft, sum, 180, 20);
        connect(screen, draft, "left", a); connect(screen, draft, "right", b); click(screen, "back");
        addNode(screen, "record");
        ((EditBox)get(screen, "parameter")).setValue("total"); click(screen, "configure");
        String record = draft.nodes().getLast().id(); place(draft, record, 340, 20);
        connect(screen, draft, "total", sum);
        ((EditBox)get(screen, "outputName")).setValue("result"); click(screen, "bind_output"); click(screen, "back");
        var machine = new ComputationMachine(draft.snapshot().compile(ProgramDraftTest.ROOM));
        machine.begin(new DataValue.RecordValue(Map.of())); machine.tick();
        assertEquals(new DataValue.RecordValue(Map.of("total", new DataValue.IntegerValue(5))), machine.report().output().orElseThrow().field("result"));
    }

    @Test void moduleCanBeCreatedOpenedAndReturnedWithoutReplacingRootDraft() throws Exception {
        var screen = screen(); var root = (ProgramDraft)get(screen, "draft");
        click(screen, "modules"); ((EditBox)get(screen,"moduleName")).setValue("double"); click(screen,"create_module");
        assertNotSame(root, get(screen,"draft"));
        var workspace = (ProgramWorkspace)get(screen,"workspace");
        assertEquals("double", workspace.keys().getFirst().id());
        click(screen,"modules"); click(screen,"root_graph");
        assertSame(root,get(screen,"draft"));
    }

    @Test void memoryDeclarationsAndWriteBindingsUseTheCanonicalStateModel() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        click(screen,"declarations"); click(screen,"declaration_mode");
        ((EditBox)get(screen,"declarationName")).setValue("count");
        ((EditBox)get(screen,"declarationValue")).setValue("0");
        ((EditBox)get(screen,"declarationWrite")).setValue("next"); click(screen,"save_declaration");
        assertEquals(new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(0)),draft.cells().get("count"));
        assertEquals(List.of(new ProgramDefinition.Write("count","next")),draft.writes());
        click(screen,"back"); addNode(screen, "memory"); // MEMORY after INPUT
        ((EditBox)get(screen,"parameter")).setValue("count"); click(screen,"configure");
        assertEquals(new ComputationGraph.ReadMemory("count"),draft.nodes().getLast().definition());
    }

    @Test void callNodeUsesModuleNamedInputsAndOpensItsOwnCanvas() throws Exception {
        var screen = screen(); var workspace = (ProgramWorkspace)get(screen,"workspace"); var root=workspace.root();
        var key=workspace.createModule("identity"); var child=workspace.graph(key);
        child.putInput("value",DataType.Scalar.INTEGER);
        var input=child.add(new ComputationGraph.Input("value"),20,20); child.output("result",input);
        addNode(screen, "call"); click(screen,"configure");
        assertEquals(Set.of("value"),root.nodes().getLast().ports());
        click(screen,"open_subgraph"); assertSame(child,get(screen,"draft"));
        click(screen,"modules");click(screen,"root_graph");assertSame(root,get(screen,"draft"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={12,13})
    void perItemCanvasProducesMappedOrFilteredValuesWithNamedCaptures(int operation) throws Exception {
        var screen=screen();var workspace=(ProgramWorkspace)get(screen,"workspace");var root=workspace.root();
        var key=workspace.createModule("below_limit");var child=workspace.graph(key);
        child.putInput("item",DataType.Scalar.INTEGER);child.putInput("limit",DataType.Scalar.INTEGER);
        var item=child.add(new ComputationGraph.Input("item"),20,20);var limit=child.add(new ComputationGraph.Input("limit"),20,90);
        var compare=child.add(new ComputationGraph.Binary(ComputationGraph.BinaryOp.LESS_INTEGER,"left","right"),180,20);
        child.connect(compare,"left",item);child.connect(compare,"right",limit);child.output("keep",compare);
        addNode(screen, "json");
        ((EditBox)get(screen,"parameter")).setValue("[1,4,2]");click(screen,"configure");
        String list=root.nodes().getLast().id();place(root,list,20,20);click(screen,"back");
        addNode(screen, "integer");((EditBox)get(screen,"parameter")).setValue("3");click(screen,"configure");
        String capture=root.nodes().getLast().id();place(root,capture,20,110);click(screen,"back");
        addNode(screen, operation == 12 ? "map" : "filter");click(screen,"configure");
        String loop=root.nodes().getLast().id();place(root,loop,180,20);
        assertEquals(Set.of("source","capture:limit"),root.node(loop).ports());
        connect(screen,root,"source",list);connect(screen,root,"capture:limit",capture);
        ((EditBox)get(screen,"outputName")).setValue("result");click(screen,"bind_output");click(screen,"back");
        var machine=new ComputationMachine(workspace.snapshot().archive().compile(ProgramDraftTest.ROOM));
        machine.begin(new DataValue.RecordValue(Map.of()));machine.tick();
        var expected=EditorValueFormat.parse(EditorValueFormat.Kind.JSON,operation==12?"[true,false,true]":"[1,2]");
        assertEquals(expected,machine.report().output().orElseThrow().field("result"));
    }

    @Test void memoryCounterBuiltWithWidgetsCommitsOnlyAtEventEnd() throws Exception {
        var screen=screen();var root=(ProgramDraft)get(screen,"draft");
        click(screen,"declarations");click(screen,"declaration_mode");
        ((EditBox)get(screen,"declarationName")).setValue("count");((EditBox)get(screen,"declarationValue")).setValue("0");
        ((EditBox)get(screen,"declarationWrite")).setValue("next");click(screen,"save_declaration");click(screen,"back");
        addNode(screen, "memory");
        ((EditBox)get(screen,"parameter")).setValue("count");click(screen,"configure");
        String read=root.nodes().getLast().id();place(root,read,20,20);click(screen,"back");
        addNode(screen, "integer");((EditBox)get(screen,"parameter")).setValue("1");click(screen,"configure");
        String one=root.nodes().getLast().id();place(root,one,20,110);click(screen,"back");
        addNode(screen, "add"); click(screen, "configure");
        String sum=root.nodes().getLast().id();place(root,sum,180,20);connect(screen,root,"left",read);connect(screen,root,"right",one);
        ((EditBox)get(screen,"outputName")).setValue("next");click(screen,"bind_output");click(screen,"back");
        var machine=new ComputationMachine(((ProgramWorkspace)get(screen,"workspace")).snapshot().archive().compile(new ResourceLimits(1000,10000,1)));
        var empty=new DataValue.RecordValue(Map.of());machine.begin(empty);machine.tick();
        assertEquals(new DataValue.IntegerValue(0),machine.checkpoint().memory().field("count"));
        for(int i=0;i<30;i++)machine.tick();assertEquals(new DataValue.IntegerValue(1),machine.checkpoint().memory().field("count"));
        machine.begin(empty);for(int i=0;i<30;i++)machine.tick();assertEquals(new DataValue.IntegerValue(2),machine.checkpoint().memory().field("count"));
    }

    private static void place(ProgramDraft draft, String id, double x, double y) { var node = draft.node(id); draft.moveBy(id, x - node.x(), y - node.y()); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426})
    void compactWindowKeepsToolbarControlsVisible(int width) throws Exception {
        var screen=screen();set(screen,"width",width);set(screen,"height",240);screen.init();assertWidgetBounds(screen);
    }
    @Test void compactNodeModalDoesNotOverlapPortAndBackControls() throws Exception {
        var screen=screen();addNode(screen, "record");
        ((EditBox)get(screen,"parameter")).setValue("field");click(screen,"configure");
        set(screen,"width",426);set(screen,"height",240);screen.init();assertWidgetBounds(screen);
        click(screen,"node_bindings");assertWidgetBounds(screen);
    }
    @Test void resizingWhileModalOpenKeepsUnderlyingCanvasBelowTheCurrentWidthToolbar() throws Exception {
        var modal=screen();addNode(modal, "integer");set(modal,"width",320);set(modal,"height",240);modal.init();
        var normal=screen();set(normal,"width",320);set(normal,"height",240);normal.init();
        var canvasTop=ComputerProgramScreen.class.getDeclaredMethod("canvasTop");canvasTop.setAccessible(true);
        assertEquals(canvasTop.invoke(normal),canvasTop.invoke(modal));
    }
    private static void assertWidgetBounds(ComputerProgramScreen screen) throws Exception {
        var widgets=screen.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
            .map(net.minecraft.client.gui.components.AbstractWidget.class::cast).filter(w->w.visible).toList();
        for(var widget:widgets) {
            assertTrue(widget.getWidth()>0 && widget.getHeight()>0);
            assertTrue(widget.getX()>=0 && widget.getY()>=0 && widget.getX()+widget.getWidth()<=(int)get(screen,"width")
                && widget.getY()+widget.getHeight()<=(int)get(screen,"height"),widget.getMessage().getString());
        }
        for(int i=0;i<widgets.size();i++)for(int j=i+1;j<widgets.size();j++) {
            var a=widgets.get(i);var b=widgets.get(j);
            assertFalse(a.getX()<b.getX()+b.getWidth() && a.getX()+a.getWidth()>b.getX() && a.getY()<b.getY()+b.getHeight() && a.getY()+a.getHeight()>b.getY(),a.getMessage()+" overlaps "+b.getMessage());
        }
    }
    private static void connect(ComputerProgramScreen screen, ProgramDraft draft, String port, String source) throws Exception {
        screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getString().startsWith(port + " ← ")).findFirst().orElseThrow().onClick(0, 0);
        var node = draft.node(source);
        var canvasTop=ComputerProgramScreen.class.getDeclaredMethod("canvasTop");canvasTop.setAccessible(true);
        assertTrue(screen.mouseClicked((int)get(screen, "leftPos") + 8 + node.x() + 5, (int)get(screen, "topPos") + (int)canvasTop.invoke(screen) + node.y() + 5, 0));
        assertNull(get(screen, "connectingTarget"));
    }

    private static void openNode(ComputerProgramScreen screen, String id) throws Exception {
        var method = ComputerProgramScreen.class.getDeclaredMethod("openNode", String.class);
        method.setAccessible(true); method.invoke(screen, id);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "   "})
    void invalidSubgraphNameStaysInDialogAndCanBeCorrectedWithoutLosingTheRoot(String name) throws Exception {
        var screen = screen(); var workspace = (ProgramWorkspace)get(screen, "workspace");
        String rootNode = workspace.root().add(ProgramDraftTest.number(7), 10, 20);
        workspace.root().output("original", rootNode);
        click(screen, "modules");
        assertEquals("modules", get(screen, "panel")); assertTrue(workspace.keys().isEmpty());
        var input = (EditBox)get(screen, "moduleName"); input.setValue(name);
        assertDoesNotThrow(() -> click(screen, "create_module"));
        assertEquals("modules", get(screen, "panel")); assertNull(get(screen, "activeGraph"));
        assertTrue(workspace.keys().isEmpty()); assertEquals(name, input.getValue());
        var error = assertInstanceOf(TranslatableContents.class, ((Component)get(screen, "validation")).getContents());
        assertEquals("create_biotech.biocomputing.editor.invalid_parameter", error.getKey());
        input.setValue("valid_subgraph"); click(screen, "create_module");
        assertEquals("valid_subgraph", ((ModuleDefinition.Key)get(screen, "activeGraph")).id());
        assertEquals(Map.of("original", rootNode), workspace.root().outputs());
    }

    @Test void duplicateSubgraphNameIsReportedAndExistingGraphStillOpens() throws Exception {
        var screen = screen(); var workspace = (ProgramWorkspace)get(screen, "workspace");
        var key = workspace.createModule("existing");
        String node = workspace.graph(key).add(ProgramDraftTest.number(9), 30, 40);
        workspace.graph(key).output("saved", node);
        click(screen, "modules"); ((EditBox)get(screen, "moduleName")).setValue("existing");
        assertDoesNotThrow(() -> click(screen, "create_module")); assertEquals(List.of(key), workspace.keys());
        assertEquals("modules", get(screen, "panel"));
        var error = assertInstanceOf(TranslatableContents.class, ((Component)get(screen, "validation")).getContents());
        assertEquals("create_biotech.biocomputing.editor.invalid_parameter", error.getKey());
        assertArrayEquals(new Object[]{"Duplicate module id: existing"}, error.getArgs());
        screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(button -> button.getMessage().getString().equals("existing @ " + key.revision())).findFirst().orElseThrow().onClick(0, 0);
        assertEquals(key, get(screen, "activeGraph"));
        assertEquals(Map.of("saved", node), ((ProgramDraft)get(screen, "draft")).outputs());
    }

    static void addNode(ComputerProgramScreen screen, String kind) {
        click(screen, "add"); selectPaletteKind(screen, kind);
    }
    static void selectPaletteKind(ComputerProgramScreen screen, String kind) {
        screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
            .findFirst().orElseThrow().setValue(kind);
        button(screen, "create_biotech.biocomputing.editor.kind." + kind).onClick(0, 0);
    }

    private static void click(ComputerProgramScreen screen, String key) {
        screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.editor." + key))
            .findFirst().orElseThrow().onClick(0, 0);
    }
    static ComputerProgramScreen screen() throws Exception {
        var screen = allocate(ComputerProgramScreen.class);
        var menu = allocate(ComputerProgramMenu.class); set(menu, "response", Optional.empty());
        set(menu, "slots", net.minecraft.core.NonNullList.create());
        set(menu, "carried", net.minecraft.world.item.ItemStack.EMPTY);
        set(screen, "draggingItem", net.minecraft.world.item.ItemStack.EMPTY);
        set(screen, "quickCraftSlots", new HashSet<>());
        var workspace = new ProgramWorkspace();
        set(screen, "menu", menu); set(screen, "workspace",workspace); set(screen, "draft", workspace.root()); set(screen,"editorInventory",allocate(net.minecraft.world.entity.player.Inventory.class));
        set(screen,"scalarOperation",ScalarOperation.ADD_INTEGER);
        set(screen,"textOperation",TextOperation.LENGTH);
        var viewport = new CanvasViewport(); set(screen, "viewport", viewport); set(screen, "interaction", new CanvasInteraction<String>(viewport));
        set(screen, "title", Component.literal("Computer")); set(screen, "playerInventoryTitle", Component.empty());
        set(screen, "children", new ArrayList<>()); set(screen, "renderables", new ArrayList<>()); set(screen, "narratables", new ArrayList<>());
        set(screen, "width", 640); set(screen, "height", 480); set(screen, "minecraft", Minecraft.getInstance()); set(screen, "font", Minecraft.getInstance().font);
        screen.init(); return screen;
    }
    static void assertNetworkVersionAtLeast(int minimum) throws Exception {
        String version = (String)field(com.nobodiiiii.createbiotech.network.CBPackets.class, "NETWORK_VERSION").get(null);
        assertTrue(Integer.parseInt(version) >= minimum, "Network gate must reject clients predating this feature");
    }
    static Object get(Object target, String name) throws Exception { return field(target.getClass(), name).get(target); }
    static void set(Object target, String name, Object value) throws Exception { field(target.getClass(), name).set(target, value); }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try { var f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    static <T> T allocate(Class<T> type) throws Exception { return type.cast(((sun.misc.Unsafe)field(sun.misc.Unsafe.class, "theUnsafe").get(null)).allocateInstance(type)); }
    static class Metrics extends Font implements com.simibubi.create.foundation.mixin.accessor.FontAccessor {
        Metrics() { super(ignored -> null, false); }
        @Override public java.util.function.Function<ResourceLocation, net.minecraft.client.gui.font.FontSet> create$getFonts() { return ignored -> null; }
        @Override public int width(String value) { return value.length(); }
        @Override public int width(FormattedText value) { return value.getString().length(); }
        @Override public String plainSubstrByWidth(String value, int width) { return value.substring(0, Math.clamp(width, 0, value.length())); }
        @Override public String plainSubstrByWidth(String value, int width, boolean reverse) { return reverse ? value.substring(Math.max(0, value.length()-width)) : plainSubstrByWidth(value, width); }
    }

    private static final class SilentSounds extends net.minecraft.client.sounds.SoundManager {
        private SilentSounds() { super(null); }
        @Override public void play(net.minecraft.client.resources.sounds.SoundInstance sound) { }
    }

    private static CreateTextButton button(ComputerProgramScreen screen,String translationKey){return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).filter(b->b.getMessage().getContents()instanceof TranslatableContents t&&t.getKey().equals(translationKey)).findFirst().orElseThrow();}
    private static CreateTextButton literalButton(ComputerProgramScreen screen,String label){return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).filter(b->b.getMessage().getString().equals(label)).findFirst().orElseThrow();}
}
