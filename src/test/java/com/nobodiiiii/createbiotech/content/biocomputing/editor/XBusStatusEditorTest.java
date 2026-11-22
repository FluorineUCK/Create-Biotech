package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class XBusStatusEditorTest {
    @org.junit.jupiter.api.Test void scratchRendezvousBlocksCompileInstallAndWaitInTheRegisterVm() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "add_readable"); boxes(editor).get(0).setValue("x0"); click(editor, "apply");
        click(editor, "add_read"); boxes(editor).get(0).setValue("x0"); boxes(editor).get(1).setValue("packet");
        boxes(editor).get(2).setValue("{\"number\":42}"); click(editor, "apply");
        click(editor, "add_write"); boxes(editor).get(0).setValue("x1"); boxes(editor).get(1).setValue("packet"); click(editor, "apply");
        var document = workspace.snapshot(); assertTrue(workspace.entryNames().isEmpty());
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var body = document.flow().orElseThrow(); assertEquals(FlowProgram.Execution.REGISTER_V1, body.execution());
        assertEquals(3, body.steps().size());
        var limits = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(1000, 100000, 1), SubgraphWorkspaceTest.LIMITS);
        var host = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
        host.installFlow(body.program(document.archive()), body.initial(), limits, true); host.step(limits, true);
        var saved = host.flowSnapshot().orElseThrow();
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.WAITING_READABLE, saved.state().status());
        assertEquals(0, saved.state().assembly().orElseThrow().pc()); assertEquals(1, saved.state().work());
        assertTrue(saved.state().expression().isEmpty());
        var restored = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
        restored.read(host.write()); restored.tick(limits, true); assertEquals(saved, restored.flowSnapshot().orElseThrow());
    }
    @org.junit.jupiter.api.Test void defaultScratchDeviceBlocksInstallAndRunWithoutOpeningAGraph() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        click(editor, "add_xbus_status"); boxes(editor).get(0).setValue("x0"); boxes(editor).get(1).setValue("level"); click(editor, "apply");
        click(editor, "add_signal_write"); boxes(editor).get(0).setValue("p0"); boxes(editor).get(1).setValue("level"); click(editor, "apply");
        click(editor, "add_signal_read"); boxes(editor).get(0).setValue("p0"); boxes(editor).get(1).setValue("sample"); click(editor, "apply");
        var document = workspace.snapshot(); assertTrue(workspace.entryNames().isEmpty());
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var body = document.flow().orElseThrow(); assertEquals(FlowProgram.Execution.REGISTER_V1, body.execution());
        var host = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
        var limits = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(1000, 100000, 1), SubgraphWorkspaceTest.LIMITS);
        host.installFlow(body.program(document.archive()), body.initial(), limits, true);
        host.step(limits, true, Map.of("x0", 15)); host.step(limits, true);
        assertEquals(Map.of("p0", 15), host.flowSnapshot().orElseThrow().state().signals().outputs());
        host.step(limits, true, Map.of("p0", 7)); var done = host.flowSnapshot().orElseThrow().state();
        assertEquals(new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue(7), done.locals().field("sample"));
        assertEquals(3, done.work()); assertTrue(done.assembly().isPresent()); assertTrue(done.expression().isEmpty());
    }
    @org.junit.jupiter.api.BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { environment.client(); }
    @org.junit.jupiter.api.AfterEach void restoreClient() throws Exception { environment.restore(); }
    @ParameterizedTest @ValueSource(ints = {320, 426, 640})
    void publicPaletteEditsANonblockingStatusReadWithoutInstallingOrAddingAnOutput(int width) throws Exception {
        var entry = FlowBlockCatalog.entries(Map.of()).stream().filter(e -> e.id().equals("xbus_status")).findFirst().orElseThrow();
        assertEquals("xbus", entry.group()); assertEquals(FlowBlockPalette.Category.COMMUNICATION, entry.category());
        var parent = screen(); var menu = readyMenu(parent); var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var editor = new ComputerFlowScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), workspace, doc -> fail("not replacing"), () -> {}, () -> {}, () -> {}, () -> {});
        initialize(editor, width); click(editor, "add_xbus_status");
        assertFalse(((net.minecraft.network.chat.Component)get(editor, "notice")).getString().contains("\n"),
            "The single-line footer cannot render newline control glyphs; the tooltip handles wrapping");
        assertEquals(2, boxes(editor).size()); boxes(editor).get(0).setValue("x0"); boxes(editor).get(1).setValue("busy");
        bounds(editor); click(editor, "apply");
        var document = workspace.snapshot(); document.validate(menu.response().orElseThrow().hardware());
        var read = (FlowProgram.XBusStatus)document.flow().orElseThrow().steps().getFirst();
        assertEquals("x0", read.port()); assertEquals("busy", read.local());
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        click(editor, "edit"); click(editor, "apply"); assertEquals(document, workspace.snapshot());
        click(editor, "undo"); assertTrue(workspace.flow().orElseThrow().steps().isEmpty());
        click(editor, "redo"); assertEquals(document, workspace.snapshot());
        assertFalse((boolean)get(editor, "waiting")); bounds(editor);
    }
}
