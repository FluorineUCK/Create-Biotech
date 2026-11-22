package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;

class ComputerFlowScreenTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void receivingFaultsRequiresAnExplicitEditableChoiceRatherThanChangingOrdinaryStatus(int width) throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, width); sensing(editor,"link"); click(editor, "add_serial_status");
        boxes(editor).get(0).setValue("s0"); boxes(editor).get(1).setValue("observed");
        click(editor, "serial_status_read"); bounds(editor); click(editor, "apply");
        var document = workspace.snapshot(); document.validate(menu.response().orElseThrow().hardware());
        assertEquals(new DataValue.IntegerValue(15), ProgramDocumentCodec.encode(document).field("version"));
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        click(editor, "edit"); assertNotNull(button(editor, "serial_status_claim"));
        click(editor, "apply"); assertEquals(document, workspace.snapshot());
        click(editor, "undo"); assertTrue(workspace.flow().orElseThrow().steps().isEmpty());
        click(editor, "redo"); assertEquals(document, workspace.snapshot());
        assertFalse((boolean)get(editor, "waiting")); bounds(editor);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void correlationIsEditableWithoutADevicePinAndNeverInstallsOrSends(int width) throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, width); sensing(editor,"link"); bounds(editor);
        var choice = button(editor, "add_correlation");
        int x = choice.getX() + choice.getWidth() / 2, y = choice.getY() + choice.getHeight() / 2;
        assertTrue(editor.mouseClicked(x, y, 0)); editor.mouseReleased(x, y, 0);
        assertEquals(1, boxes(editor).size());
        assertEquals("create_biotech.biocomputing.flow_editor.correlation_local",
            ((TranslatableContents)boxes(editor).getFirst().getMessage().getContents()).getKey());
        boxes(editor).getFirst().setValue("request"); bounds(editor); click(editor, "apply");
        var document = workspace.snapshot(); document.validate(menu.response().orElseThrow().hardware());
        var block = (com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Correlation)document.flow().orElseThrow().steps().getFirst();
        assertEquals("request", block.local());
        assertEquals(new DataValue.IntegerValue(12), ProgramDocumentCodec.encode(document).field("version"));
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        click(editor, "edit"); click(editor, "apply"); assertEquals(document, workspace.snapshot());
        click(editor, "undo"); assertTrue(workspace.flow().orElseThrow().steps().isEmpty());
        click(editor, "redo"); assertEquals(document, workspace.snapshot());
        assertFalse((boolean)get(editor, "waiting")); bounds(editor);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void serialChooserConsumesActualClicksInsteadOfSelectingUnderlyingBlocks(int width) throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.SerialStatus("state", "s0", "status")),
            new DataValue.RecordValue(Map.of()))));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, width); var before = workspace.snapshot(); sensing(editor,"link");
        var choice = button(editor, "add_serial_status");
        int x = choice.getX() + choice.getWidth() / 2, y = choice.getY() + choice.getHeight() / 2;
        assertTrue(editor.mouseClicked(x, y, 0)); editor.mouseReleased(x, y, 0);
        assertTrue((boolean)get(editor, "parameters")); assertEquals("SERIAL_STATUS", get(editor, "kind").toString());
        assertEquals("create_biotech.biocomputing.flow_editor.serial_port", ((TranslatableContents)boxes(editor).getFirst().getMessage().getContents()).getKey());
        assertEquals(before, workspace.snapshot()); bounds(editor);
    }
    @Test void droppingACroppedLoopRevealsItsFootAndKeepsTheOpeningAvailable() throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 426);
        var canvas = (FlowBlockCanvas)get(editor,"canvas");
        int x = (int)get(canvas,"x"), y = (int)get(canvas,"y"), w = (int)get(canvas,"width"), h = (int)get(canvas,"height");
        click(editor, "category_control");
        var repeat = button(editor,"add_repeat");
        // Release next to the right and bottom edges, where the ordinary C would extend beyond the view.
        double dropX = x + w - 8, dropY = y + h - 16;
        editor.mouseClicked(repeat.getX()+8,repeat.getY()+8,0);
        editor.mouseDragged(dropX,dropY,0,100,60);
        editor.mouseReleased(dropX,dropY,0);
        var placed = workspace.snapshot();
        var stack = placed.flow().orElseThrow().canvas().detached().getFirst();
        var head = canvas.blocks().getFirst();
        var foot = canvas.blocks().getLast();
        var view = (com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport)get(canvas,"viewport");
        assertTrue(canvas.contains(x + view.screenX(head.x()), y + view.screenY(head.y())));
        assertTrue(canvas.contains(x + view.screenX(foot.x()+foot.width()), y + view.screenY(foot.y()+foot.height()+4)),
            "C foot and connector must be visible after dropping a fitting loop");
        var mouth = canvas.joints().stream().filter(j -> stack.steps().getFirst().sourceId().equals(j.socket().stack())
            && j.socket().index()==1).findFirst().orElseThrow();
        assertTrue(canvas.contains(x+view.screenX(mouth.x()),y+view.screenY(mouth.y()+mouth.opening()/2.0)));
        canvas.scroll(x+12,y+12,-6,-6,false);
        click(editor,"reset_view");
        assertTrue(canvas.contains(x+view.screenX(foot.x()+foot.width()),y+view.screenY(foot.y()+foot.height()+4)));
        assertEquals(placed,workspace.snapshot(),"Framing must not rewrite a player's saved positions");
    }
    @Test void publicSerialBlocksEditPortableDraftsWithoutBorrowingXBusPinsOrInstalling() throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        var packet = new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12)));
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("packet", DataType.typeOf(packet)), List.of(), new DataValue.RecordValue(Map.of("packet", packet)))));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, 320); bounds(editor); sensing(editor,"link"); bounds(editor); click(editor, "add_serial_send");
        assertTrue(boxes(editor).getFirst().getValue().isEmpty()); boxes(editor).get(0).setValue("s0"); boxes(editor).get(1).setValue("packet");
        boxes(editor).get(2).setValue("sent"); bounds(editor); click(editor, "apply");
        for (String kind : List.of("receive", "status")) {
            sensing(editor,"link"); click(editor, "add_serial_" + kind); boxes(editor).get(0).setValue("s0"); boxes(editor).get(1).setValue(kind); click(editor, "apply");
        }
        var document = workspace.snapshot(); document.validate(menu.response().orElseThrow().hardware());
        assertEquals(new DataValue.IntegerValue(11), ProgramDocumentCodec.encode(document).field("version"));
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        click(editor, "edit"); click(editor, "apply"); assertEquals(document, workspace.snapshot());
        assertFalse((boolean)get(editor, "waiting")); bounds(editor);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void publicXBusResultChoicesAreExplicitPortableAndDoNotInstall(int width) throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, width); click(editor, "add_read");
        boxes(editor).get(1).setValue("received"); boxes(editor).get(2).setValue("{\"value\":12}");
        click(editor, "result_mode"); bounds(editor); click(editor, "apply");
        var read = (com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Read)workspace.flow().orElseThrow().steps().getFirst();
        assertTrue(read.result()); assertEquals("received", read.local());
        click(editor, "edit"); click(editor, "apply");
        assertEquals(read, workspace.flow().orElseThrow().steps().getFirst(), "Editing must not drop result mode");
        click(editor, "add_readable"); boxes(editor).get(1).setValue("readiness"); bounds(editor); click(editor, "apply");
        var wait = (com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.AwaitReadable)workspace.flow().orElseThrow().steps().getLast();
        assertEquals(Optional.of("readiness"), wait.result());
        var document = workspace.snapshot();
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        assertFalse((boolean)get(editor, "waiting"));
        click(editor, "undo"); assertEquals(1, workspace.flow().orElseThrow().steps().size());
        click(editor, "redo"); assertEquals(document, workspace.snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void publicRepeatGroupCapturesNamedCountAndSupportsUndoAndWholeGroupRemoval(int width) throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("n", DataType.Scalar.INTEGER), List.of(),
            new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(2))))));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, width); bounds(editor); click(editor, "add_repeat");
        assertEquals(1, boxes(editor).size()); boxes(editor).getFirst().setValue("n"); click(editor, "apply");
        var group = workspace.snapshot(); group.validate(new ResourceLimits(100, 100000, 100));
        assertEquals(2, workspace.flow().orElseThrow().steps().size());
        assertEquals(new DataValue.IntegerValue(8), ProgramDocumentCodec.encode(group).field("version"));
        click(editor, "add_sleep"); boxes(editor).getFirst().setValue("n"); click(editor, "apply");
        var body = workspace.snapshot(); assertEquals(body, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(body)));
        click(editor, "undo"); assertEquals(group, workspace.snapshot()); click(editor, "redo"); assertEquals(body, workspace.snapshot());
        int x = (int)get(get(editor, "canvas"), "x") + 24, y = (int)get(get(editor, "canvas"), "y") + 50;
        editor.mouseClicked(x, y, 0); editor.mouseReleased(x, y, 0); click(editor, "edit");
        assertEquals("n", boxes(editor).getFirst().getValue()); click(editor, "apply"); assertEquals(body, workspace.snapshot());
        click(editor, "remove"); assertTrue(workspace.flow().orElseThrow().steps().isEmpty());
        click(editor, "undo"); assertEquals(body, workspace.snapshot()); bounds(editor); assertFalse((boolean)get(editor, "waiting"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void publicForeverGroupCanBeCreatedNestedAndRemovedWithoutInstalling(int width) throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("ticks", DataType.Scalar.INTEGER), List.of(),
            new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(2))))));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, width); bounds(editor); click(editor, "add_forever");
        assertTrue(boxes(editor).isEmpty()); bounds(editor); click(editor, "apply");
        var group = workspace.snapshot(); assertEquals(2, workspace.flow().orElseThrow().steps().size());
        click(editor, "add_sleep"); boxes(editor).getFirst().setValue("ticks"); click(editor, "apply");
        var body = workspace.snapshot(); body.validate(new ResourceLimits(100, 100000, 100));
        assertEquals(new DataValue.IntegerValue(7), ProgramDocumentCodec.encode(body).field("version"));
        assertEquals(body, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(body)));
        click(editor, "undo"); assertEquals(group, workspace.snapshot()); click(editor, "redo"); assertEquals(body, workspace.snapshot());
        int x = (int)get(get(editor, "canvas"), "x") + 24, y = (int)get(get(editor, "canvas"), "y") + 50;
        editor.mouseClicked(x, y, 0); editor.mouseReleased(x, y, 0);
        click(editor, "remove"); assertTrue(workspace.flow().orElseThrow().steps().isEmpty());
        click(editor, "undo"); assertEquals(body, workspace.snapshot()); bounds(editor);
        assertFalse((boolean)get(editor, "waiting"));
    }
    @Test void newInitialRejectsDuplicateNamesButSelectingAnExistingEntryAllowsEditing() throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, 320); click(editor, "initials");
        boxes(editor).get(0).setValue("level"); boxes(editor).get(1).setValue("70"); click(editor, "save_initial");
        var saved = workspace.snapshot(); click(editor, "new_initial");
        boxes(editor).get(0).setValue("level"); boxes(editor).get(1).setValue("3"); click(editor, "save_initial");
        assertEquals(saved, workspace.snapshot(), "New must not silently replace an existing named value");
        editor.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getString().equals("level")).findFirst().orElseThrow().onClick(0, 0);
        boxes(editor).get(0).setValue("voltage"); boxes(editor).get(1).setValue("42"); click(editor, "save_initial");
        assertEquals(Map.of("voltage", new DataValue.IntegerValue(42)), workspace.flow().orElseThrow().initial().fields());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void explicitNewInitialKeepsPreviouslySavedEntriesWithoutReopeningTheEditor(int width) throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, width); click(editor, "initials");
        boxes(editor).get(0).setValue("level"); boxes(editor).get(1).setValue("70"); click(editor, "save_initial");
        var first = workspace.snapshot(); click(editor, "new_initial"); bounds(editor);
        assertEquals(first, workspace.snapshot(), "New clears only the editor form, not the draft");
        assertTrue(boxes(editor).stream().allMatch(box -> box.getValue().isEmpty()));
        boxes(editor).get(0).setValue("ticks"); boxes(editor).get(1).setValue("3"); click(editor, "save_initial");
        assertEquals(Map.of("level", new DataValue.IntegerValue(70), "ticks", new DataValue.IntegerValue(3)),
            workspace.flow().orElseThrow().initial().fields());
        var both = workspace.snapshot(); click(editor, "back"); click(editor, "undo"); assertEquals(first, workspace.snapshot());
        click(editor, "redo"); assertEquals(both, workspace.snapshot());
        assertFalse((boolean)get(editor, "waiting"), "Editing must not submit an installation");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void publicSignalControlsUseOnlyTheirHardwareListAndKeepEditingInTheDraft(int width) throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var response = menu.response().orElseThrow();
        var pins = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins(List.of("x0"), List.of("sense", "drive"));
        set(menu, "response", Optional.of(new ProgramEditorResponse(response.containerId(), response.nonce(), response.intent(),
            response.revision(), response.code(), response.detail(), response.hardware(), response.archiveLimits(), response.archive(), pins)));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("level", DataType.Scalar.INTEGER), List.of(),
            new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(300))))));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, width); bounds(editor); click(editor, "add_signal_write"); bounds(editor);
        assertEquals("sense", boxes(editor).getFirst().getValue()); click(editor, "next_pin");
        assertEquals("drive", boxes(editor).getFirst().getValue()); boxes(editor).get(1).setValue("level"); click(editor, "apply");
        click(editor, "add_signal_read"); assertEquals("sense", boxes(editor).getFirst().getValue());
        boxes(editor).get(1).setValue("sample"); click(editor, "apply");
        var document = workspace.snapshot(); document.validate(response.hardware(), pins);
        assertInstanceOf(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.SignalWrite.class, workspace.flow().orElseThrow().steps().getFirst());
        click(editor, "undo"); assertEquals(1, workspace.flow().orElseThrow().steps().size());
        click(editor, "redo"); assertEquals(document, workspace.snapshot()); assertFalse((boolean)get(editor, "waiting")); bounds(editor);
    }
    @Test void sleepBlockUsesNamedIntegerAndEditingDoesNotInstallOrRun() throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("ticks", DataType.Scalar.INTEGER), List.of(),
            new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(3))))));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, 320); bounds(editor); click(editor, "add_sleep");
        assertEquals(1, boxes(editor).size()); boxes(editor).getFirst().setValue("ticks"); bounds(editor); click(editor, "apply");
        var body = workspace.flow().orElseThrow();
        assertEquals("ticks", ((com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Sleep)body.steps().getFirst()).ticksLocal());
        workspace.snapshot().validate(new ResourceLimits(100, 100000, 100));
        assertFalse((boolean)get(editor, "waiting")); click(editor, "undo"); assertTrue(workspace.flow().orElseThrow().steps().isEmpty());
        click(editor, "redo"); assertEquals(body, workspace.flow().orElseThrow());
    }
    @Test void scratchSleepInstallsAsRegisterInstructionAndRetainsItsDocumentAcrossWake() throws Exception {
        var workspace = new ProgramWorkspace();
        var p = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of()).withAssembly();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(p, new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(2))))));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "add_sleep"); click(editor, "input_local"); boxes(editor).getFirst().setValue("ticks");
        click(editor, "wait_unit_ticks"); click(editor, "apply");
        var document = workspace.snapshot(); assertTrue(workspace.entryNames().isEmpty());
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var body = document.flow().orElseThrow();
        var host = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
        var limits = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            new ResourceLimits(100, 100000, 100), SubgraphWorkspaceTest.LIMITS);
        host.installFlow(body.program(document.archive()), body.initial(), limits, true); host.step(limits, true);
        var state = host.flowSnapshot().orElseThrow().state();
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.SLEEPING, state.status());
        assertEquals(0, state.assembly().orElseThrow().pc()); assertEquals(2, state.sleepTicks()); assertEquals(1, state.work());
        host.step(limits, true); host.step(limits, true);
        state = host.flowSnapshot().orElseThrow().state();
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.COMPLETE, state.status());
        assertEquals(1, state.assembly().orElseThrow().pc()); assertEquals(1, state.work());
        assertEquals(document, workspace.snapshot());
    }
    @Test void sharedCanvasDoesNotValidateAFlowWithNonexistentHardwarePins() throws Exception {
        var parent = screen(); readyMenu(parent);
        var program = new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(), List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.AwaitReadable("wait", "x9")));
        parent.importDocument(new ProgramDocument(program.expressions(), Map.of(), Optional.empty(), Optional.of(
            new FlowDocumentBody(program.inputs(), program.steps(), new DataValue.RecordValue(Map.of())))));
        assertNull(get(parent, "preview"));
        assertTrue(((net.minecraft.network.chat.Component)get(parent, "validation")).getString().contains("x9"));
    }
    @Test void hardwarePinChooserCyclesTheServerListAndUnknownDraftDoesNotInstall() throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var response = menu.response().orElseThrow();
        set(menu, "response", Optional.of(new ProgramEditorResponse(response.containerId(), response.nonce(), response.intent(),
            response.revision(), response.code(), response.detail(), response.hardware(), response.archiveLimits(), response.archive(),
            new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins(List.of("left", "right")))));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, 320); click(editor, "add_readable");
        click(editor, "next_pin"); assertEquals("left", boxes(editor).getFirst().getValue());
        click(editor, "next_pin"); assertEquals("right", boxes(editor).getFirst().getValue());
        click(editor, "next_pin"); assertEquals("left", boxes(editor).getFirst().getValue()); bounds(editor);
        boxes(editor).getFirst().setValue("invented"); click(editor, "apply");
        assertEquals("invented", ((com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.AwaitReadable)
            workspace.flow().orElseThrow().steps().getFirst()).port(), "Portable drafts retain names instead of silently remapping them");
        click(editor, "install"); assertFalse((boolean)get(editor, "waiting"));
        var notice = (TranslatableContents)((net.minecraft.network.chat.Component)get(editor, "notice")).getContents();
        assertEquals("create_biotech.biocomputing.flow_editor.invalid", notice.getKey());
        assertTrue(String.valueOf(notice.getArgs()[0]).contains("invented"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void publicConditionGroupControlsNestEditAndUndoWithoutInstalling(int width) throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("ok", DataType.Scalar.BOOLEAN), List.of(),
            new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(false))))));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, width); bounds(editor); click(editor, "add_if_else");
        assertEquals(1, boxes(editor).size()); boxes(editor).getFirst().setValue("ok"); bounds(editor); click(editor, "apply");
        var group = workspace.snapshot(); group.validate(new ResourceLimits(100, 100000, 100));
        assertEquals(3, workspace.flow().orElseThrow().steps().size());
        click(editor, "add_if"); boxes(editor).getFirst().setValue("ok"); click(editor, "apply");
        var nested = workspace.snapshot(); nested.validate(new ResourceLimits(100, 100000, 100));
        assertEquals(5, workspace.flow().orElseThrow().steps().size());
        click(editor, "add_readable"); boxes(editor).getFirst().setValue("x1"); click(editor, "apply");
        assertEquals("x1", ((com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.AwaitReadable)
            workspace.flow().orElseThrow().steps().get(2)).port());
        click(editor, "undo"); assertEquals(nested, workspace.snapshot());
        click(editor, "undo"); assertEquals(group, workspace.snapshot());
        int x = (int)get(get(editor, "canvas"), "x") + 24, y = (int)get(get(editor, "canvas"), "y") + 50;
        assertTrue(editor.mouseClicked(x, y, 0)); editor.mouseReleased(x, y, 0);
        click(editor, "edit"); assertEquals("ok", boxes(editor).getFirst().getValue()); click(editor, "apply");
        assertEquals(group, workspace.snapshot()); click(editor, "remove"); assertTrue(workspace.flow().orElseThrow().steps().isEmpty());
        click(editor, "undo"); assertEquals(group, workspace.snapshot()); bounds(editor);
        selectBlock(editor, 2);
        click(editor, "add_readable"); boxes(editor).getFirst().setValue("x2"); click(editor, "apply");
        assertInstanceOf(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.AwaitReadable.class,
            workspace.flow().orElseThrow().steps().getLast(), "Adding after End if leaves the branch");
        assertFalse((boolean)get(editor, "waiting"));
    }
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }
    @Test void workstationOpensFlowEditorWithoutInstallingOrReplacingDraft() throws Exception {
        var parent = screen(); var opened = new Screen[1];
        set(parent, "navigator", (Consumer<Screen>)(next -> opened[0] = next));
        var menu = (ComputerProgramMenu)get(parent, "menu");
        set(menu, "response", Optional.of(new ProgramEditorResponse(0, UUID.randomUUID(), 1, 7, "ready", "",
            new ResourceLimits(1000, 100000, 1000), SubgraphWorkspaceTest.LIMITS, new byte[0])));
        var workspace = (ProgramWorkspace)get(parent, "workspace");
        var value = workspace.root().add(new ComputationGraph.Constant(new DataValue.IntegerValue(1)), 0, 0);
        workspace.root().output("value", value); var before = workspace.snapshot();
        parent.init();
        var button = parent.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.editor.flow")).findFirst().orElseThrow();
        assertTrue(button.active); button.onClick(0, 0);
        assertEquals("ComputerFlowScreen", opened[0].getClass().getSimpleName());
        assertEquals(before, workspace.snapshot()); assertFalse((boolean)get(parent, "waiting"));
    }

    @Test void explicitNewFlowReplacesOnlyTheDraftAndAllowsAReadWriteOnlyDocument() throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        var replaced = new ProgramDocument[1]; var closed = new int[1];
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> replaced[0] = doc,
            () -> fail("New flow replaces, not mutates, the old draft"), () -> closed[0]++, () -> {}, () -> {});
        initialize(editor, 320); click(editor, "new_flow");
        assertNotNull(replaced[0], "Explicit creation must produce a portable empty flow document");
        assertEquals(1, closed[0]); assertTrue(workspace.flow().isEmpty());
        var next = new ProgramWorkspace(replaced[0]);
        assertEquals(replaced[0], next.snapshot());
        next.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.AwaitReadable("wait", "x0")),
            new DataValue.RecordValue(Map.of()))));
        next.snapshot().validate(new ResourceLimits(100, 100000, 100));
        assertFalse((boolean)get(editor, "waiting"));
    }
    static ComputerProgramMenu readyMenu(ComputerProgramScreen parent) throws Exception {
        var menu = (ComputerProgramMenu)get(parent, "menu");
        menu.rememberDebuggerInputs(new DataValue.RecordValue(Map.of()));
        set(menu, "response", Optional.of(new ProgramEditorResponse(0, UUID.randomUUID(), 1, 7, "ready", "",
            new ResourceLimits(1000, 100000, 1000), SubgraphWorkspaceTest.LIMITS, new byte[0]))); return menu;
    }
    @Test void canvasProgramCapacityIncludesControlBlocksNotOnlyPureExpressions() throws Exception {
        var parent = screen(); readyMenu(parent);
        var flow = com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingFlowFixture.calculator();
        parent.importDocument(new ProgramDocument(flow.expressions(), Map.of(), Optional.empty(), Optional.of(
            new FlowDocumentBody(flow.inputs(), flow.steps(), com.nobodiiiii.createbiotech.content.biocomputing.gametest.BiocomputingFlowFixture.inputs(12)))));
        var label = (TranslatableContents)parent.resourceStatus().getContents();
        long pure = flow.expressions().compile(new ResourceLimits(1000, 100000, 1000)).usage().programUnits();
        assertEquals(pure + flow.steps().size(), ((Number)label.getArgs()[0]).longValue());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void realParameterControlsDragAndUndoKeepTypedSourcesAndNeverInstall(int width) throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, width); bounds(editor); click(editor, "add_read");
        boxes(editor).get(0).setValue("x1"); boxes(editor).get(1).setValue("packet"); boxes(editor).get(2).setValue("{\"value\":12}");
        bounds(editor); click(editor, "apply");
        var read = workspace.flow().orElseThrow().steps().getFirst();
        click(editor, "add_write"); boxes(editor).get(0).setValue("x2"); boxes(editor).get(1).setValue("packet"); click(editor, "apply");
        var ordered = workspace.snapshot(); ordered.validate(new ResourceLimits(100, 100000, 100));
        int x = (int)get(get(editor, "canvas"), "x") + 24, y = (int)get(get(editor, "canvas"), "y") + 50;
        assertTrue(editor.mouseClicked(x, y + 28, 0)); assertTrue(editor.mouseDragged(x, y, 0, 0, -28)); assertTrue(editor.mouseReleased(x, y, 0));
        assertEquals(read, workspace.flow().orElseThrow().steps().get(1));
        assertThrows(ComputationException.class, () -> workspace.snapshot().validate(new ResourceLimits(100, 100000, 100)));
        click(editor, "undo"); assertEquals(ordered, workspace.snapshot());
        selectBlock(editor, 0); click(editor, "edit"); assertEquals("", boxes(editor).get(2).getValue()); click(editor, "apply");
        assertEquals(ordered, workspace.snapshot(), "Editing a read without a new sample keeps its declared type");
        // Minecraft suppresses accumulated motion while a window is inactive; click/release still arrive.
        click(editor, "reset_view");
        assertTrue(editor.mouseClicked(x, y + 28, 0)); assertTrue(editor.mouseReleased(x, y, 0));
        assertEquals(read, workspace.flow().orElseThrow().steps().get(1), "Release must decide drop even without intermediate callbacks");
        click(editor, "undo"); assertEquals(ordered, workspace.snapshot());
        assertTrue(editor.mouseClicked(x, y + 28, 0)); editor.mouseDragged(x, y, 0, 0, -28);
        editor.mouseReleased(0, 0, 0); assertEquals(ordered, workspace.snapshot(), "Releasing outside blocks cancels the reorder");
        assertFalse((boolean)get(editor, "waiting")); bounds(editor);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en_us", "zh_cn"})
    void allFlowControlsHaveLocalizedLabels(String locale) throws Exception {
        var old = net.minecraft.locale.Language.getInstance();
        try {
            var values = ComputerUiTranslationsTest.load(locale); net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(values));
            assertFalse(net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.title").getString().contains("XBus"),
                "Flow also contains local computation, time and Simple I/O");
            assertTrue(values.getOrDefault("create_biotech.biocomputing.cluster.left_signal_pin", "").contains("p0"));
            assertTrue(values.getOrDefault("create_biotech.biocomputing.cluster.right_signal_pin", "").contains("p1"));
            var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
            var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
                net.minecraft.network.chat.Component.empty(), workspace, doc -> {}, () -> {}, () -> {}, () -> {}, () -> {});
            initialize(editor, 426);
            for (var child : editor.children()) if (child instanceof CreateTextButton control)
                assertFalse(control.getMessage().getString().startsWith("create_biotech."), control.getMessage().getString());
            click(editor, "initials"); bounds(editor);
            boxes(editor).get(0).setValue("count"); boxes(editor).get(1).setValue("12"); click(editor, "save_initial");
            assertEquals(new DataValue.IntegerValue(12), workspace.flow().orElseThrow().initial().field("count"));
            for (var child : editor.children()) if (child instanceof CreateTextButton control)
                assertFalse(control.getMessage().getString().startsWith("create_biotech."), control.getMessage().getString());
        } finally { net.minecraft.locale.Language.inject(old); }
    }
    static List<net.minecraft.client.gui.components.EditBox> boxes(Screen screen) {
        return screen.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance)
            .map(net.minecraft.client.gui.components.EditBox.class::cast)
            .filter(box -> !(box.getMessage().getContents() instanceof TranslatableContents t && t.getKey().endsWith(".catalog_search"))).toList();
    }
    static void selectBlock(ComputerFlowScreen screen, int index) throws Exception {
        click(screen, "reset_view");
        var canvas = (FlowBlockCanvas)get(screen, "canvas"); var block = canvas.blocks().get(index);
        int left = (int)get(canvas, "x"), top = (int)get(canvas, "y");
        double dy = 30 - block.y() - block.height() / 2.0;
        int panX = left + (int)get(canvas, "width") - 4;
        screen.mouseClicked(panX, top + 4, 0);
        screen.mouseDragged(panX, top + 4 + dy, 0, 0, dy); screen.mouseReleased(panX, top + 4 + dy, 0);
        double x = left + block.x() + 8, y = top + 30;
        assertTrue(screen.mouseClicked(x, y, 0)); screen.mouseReleased(x, y, 0);
    }
    static void bounds(Screen screen) {
        var widgets = screen.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
            .map(net.minecraft.client.gui.components.AbstractWidget.class::cast).filter(w -> w.visible).toList();
        for (var w : widgets) {
            assertTrue(w.getX() >= 0 && w.getY() >= 0); assertTrue(w.getX() + w.getWidth() <= screen.width);
            assertTrue(w.getY() + w.getHeight() <= screen.height);
        }
        for (int i = 0; i < widgets.size(); i++) for (int j = i + 1; j < widgets.size(); j++) {
            var a = widgets.get(i); var b = widgets.get(j);
            assertFalse(a.getX() < b.getX() + b.getWidth() && a.getX() + a.getWidth() > b.getX()
                && a.getY() < b.getY() + b.getHeight() && a.getY() + a.getHeight() > b.getY(), "Widgets overlap");
        }
    }
    static void initialize(ComputerFlowScreen screen, int width) throws Exception {
        set(screen, "width", width); set(screen, "height", 260); set(screen, "minecraft", net.minecraft.client.Minecraft.getInstance());
        set(screen, "font", net.minecraft.client.Minecraft.getInstance().font); screen.init();
    }
    static CreateTextButton button(Screen screen, String key) {
        // Parameter tests use the real category/page controls, not the removed three-row toolbar.
        var found = visibleButton(screen, key);
        if (found.isEmpty() && key.startsWith("add_") && screen instanceof ComputerFlowScreen) {
            for (String category : List.of("events", "control", "operators", "variables", "storage", "data", "sensing_input", "sensing_link", "sensing_output", "custom")) {
                if(category.startsWith("sensing_")) sensing(screen,category.substring(8));
                else {
                    var tab = visibleButton(screen, "category_" + category); if (tab.isEmpty()) continue;
                    tab.orElseThrow().onClick(0, 0);
                }
                do {
                    found = visibleButton(screen, key); if (found.isPresent()) return found.orElseThrow();
                    var next = visibleButton(screen, "palette_next"); if (next.isEmpty() || !next.orElseThrow().active) break;
                    next.orElseThrow().onClick(0, 0);
                } while (true);
            }
        }
        if (found.isEmpty()) throw new AssertionError("Missing visible control: " + key);
        return found.orElseThrow();
    }
    private static Optional<CreateTextButton> visibleButton(Screen screen, String key) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.flow_editor." + key)).findFirst();
    }
    static void click(Screen screen, String key) { var control = button(screen, key); assertTrue(control.active); control.onClick(0, 0); }
    static void sensing(Screen screen,String section) {
        click(screen,"category_sensing");var option=button(screen,"sensing_"+section);
        if(option.active)option.onClick(0,0);
    }
}
