package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;

class ScratchBroadcastScreenTest {
    private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { environment.client(); }
    @org.junit.jupiter.api.AfterEach void restoreClient() throws Exception { environment.restore(); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void dragReceiverAndAddBroadcastProducesAnEditableSavedProgram(int width) throws Exception {
        var workspace=new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var screen=FlowCallScreenTest.editor(workspace,width); var before=workspace.snapshot();
        click(screen,"add_broadcast_wait"); click(screen,"apply");
        var withPost=workspace.snapshot();
        click(screen,"category_events"); var block=button(screen,"add_receive_broadcast");
        var canvas=get(screen,"canvas");
        double fromX=block.getX()+4,fromY=block.getY()+4;
        double x=(int)get(canvas,"x")+40,y=(int)get(canvas,"y")+100;
        screen.mouseClicked(fromX,fromY,0);screen.mouseDragged(x,y,0,x-fromX,y-fromY);screen.mouseReleased(x,y,0);
        var body=workspace.flow().orElseThrow();
        assertEquals(1,body.canvas().detached().size());
        var receiver=assertInstanceOf(FlowProgram.Procedure.class,body.canvas().detached().getFirst().steps().getFirst());
        assertEquals(Optional.of("message1"),receiver.event());
        assertDoesNotThrow(()->ComputerFlowScreen.blockLabel(receiver));
        var post=assertInstanceOf(FlowProgram.Broadcast.class,workspace.flow().orElseThrow().steps().getFirst());
        assertEquals("message1",post.channel()); assertTrue(post.await());
        assertDoesNotThrow(()->ComputerFlowScreen.blockLabel(post));
        var document=workspace.snapshot(); assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        assertFalse((boolean)get(screen,"waiting"));
        var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            new ResourceLimits(4096,64000,1),new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000,100000,1000000),
            com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.DEFAULT,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware.NONE,2,true);
        var owner=new ComputerBlockHost(()->{});
        owner.installFlow(document.flow().orElseThrow().program(document.archive()),document.flow().orElseThrow().initial(),limits,true);
        owner.step(limits,true); assertEquals(FlowRuntime.Status.WAITING_TASK,owner.flowSnapshot().orElseThrow().state().status());
        for(int i=0;i<8 && owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++)owner.step(limits,true);
        assertEquals(FlowRuntime.Status.COMPLETE,owner.flowSnapshot().orElseThrow().state().status());
        assertTrue(owner.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
        click(screen,"undo"); assertEquals(withPost,workspace.snapshot());
        click(screen,"undo"); assertEquals(before,workspace.snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("channelCases")
    void broadcastDropdownSelectsExistingAndCreatesNewChannelsWithoutRebindingOtherBlocks(int width, String mode) throws Exception {
        var hat = new FlowProgram.Procedure("receiver", new ModuleDefinition.Key("receiver", 1), Map.of(), Map.of(), Map.of(),
            FlowBlockRules.NONE, Optional.empty(), Optional.of("alpha"));
        var layout = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(), List.of(new FlowBlockLayout.Stack(
            new ProgramDocument.Position(24, 106), List.of(hat, new FlowProgram.EndProcedure("end")))));
        var send = new FlowProgram.Broadcast("send", "alpha", false);
        var wait = new FlowProgram.Broadcast("wait", "beta", true);
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(new FlowDocumentBody(
            Map.of(), List.of(send, wait), new DataValue.RecordValue(Map.of()), layout, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        var editor = FlowCallScreenTest.editor(workspace, width);
        var original = workspace.snapshot();
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        String source = mode.equals("receive") ? "receiver" : mode;
        canvas.focus(source);
        var block = canvas.blocks().stream().filter(b -> b.step().sourceId().equals(source)).findFirst().orElseThrow();
        String captionKey = "broadcast_caption_" + mode;
        var viewport = (com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport)get(canvas,"viewport");
        int x = (int)get(canvas, "x") + (int)viewport.screenX(block.x() + 16 + net.minecraft.client.Minecraft.getInstance().font.width(
            net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor." + captionKey)));
        int y = (int)get(canvas, "y") + (int)viewport.screenY(block.y() + 11);
        assertTrue(canvas.contains(x,y));
        assertTrue(editor.mouseClicked(x,y,0));
        assertEquals(original, workspace.snapshot());
        String channel = mode.equals("wait") ? "alpha" : "beta";
        var existing = editor.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().equals(net.minecraft.network.chat.Component.literal(channel))).findFirst().orElseThrow();
        existing.onClick(0,0);
        var changed = workspace.snapshot();
        var body = changed.flow().orElseThrow();
        assertEquals(mode.equals("send") ? new FlowProgram.Broadcast("send",channel,false) : send, body.steps().getFirst());
        assertEquals(mode.equals("wait") ? new FlowProgram.Broadcast("wait",channel,true) : wait, body.steps().getLast());
        var listener = (FlowProgram.Procedure)body.canvas().detached().getFirst().steps().getFirst();
        assertEquals(Optional.of(mode.equals("receive") ? channel : "alpha"), listener.event());
        assertEquals(hat.key(),listener.key());
        assertEquals(changed,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(changed)));
        click(editor,"undo"); assertEquals(original,workspace.snapshot());
        assertTrue(editor.mouseClicked(x,y,0)); click(editor,"broadcast_new_channel");
        assertEquals(original,workspace.snapshot(),"Opening the name form does not create a channel");
        var name = boxes(editor).getFirst();
        name.setValue("alpha"); click(editor,"apply");
        assertEquals(original,workspace.snapshot(),"Duplicate new names must not silently rebind the block");
        click(editor,"cancel"); assertEquals(original,workspace.snapshot());
        assertTrue(editor.mouseClicked(x,y,0)); click(editor,"broadcast_new_channel");
        name = boxes(editor).getFirst();
        name.setValue("new_channel"); click(editor,"apply");
        var edited = new FlowDraft(workspace.flow().orElseThrow());
        assertTrue(FlowBlockPalette.eventChannels(edited).contains("new_channel"));
        click(editor,"undo"); assertEquals(original,workspace.snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void channelPagesStayInsidePanelAndBrowsingDoesNotEdit(int width) throws Exception {
        var steps=new ArrayList<FlowProgram.Step>();
        for(int i=0;i<24;i++)steps.add(new FlowProgram.Broadcast("post"+i,String.format("channel_%02d",i),false));
        var workspace=new ProgramWorkspace(); workspace.setFlow(Optional.of(new FlowDocumentBody(
            Map.of(),steps,new DataValue.RecordValue(Map.of()),FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1)));
        var editor=FlowCallScreenTest.editor(workspace,width);var before=workspace.snapshot();
        var canvas=(FlowBlockCanvas)get(editor,"canvas");canvas.focus("post0");
        var block=canvas.blocks().getFirst();
        var viewport=(com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport)get(canvas,"viewport");
        double x=(int)get(canvas,"x")+viewport.screenX(block.x()+16+net.minecraft.client.Minecraft.getInstance().font.width(
            net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.broadcast_caption_send")));
        double y=(int)get(canvas,"y")+viewport.screenY(block.y()+11);
        assertTrue(editor.mouseClicked(x,y,0));
        var visited=new HashSet<String>();
        int pages=0;
        while(true){
            @SuppressWarnings("unchecked") var menu=(List<CreateTextButton>)get(editor,"reporterMenu");
            assertFalse(menu.isEmpty());
            int left=(int)get(editor,"leftPos"),top=(int)get(editor,"topPos");
            int panelWidth=(int)get(editor,"imageWidth"),panelHeight=(int)get(editor,"imageHeight");
            for(var item:menu){
                assertTrue(item.getX()>=left && item.getX()+item.getWidth()<=left+panelWidth);
                assertTrue(item.getY()>=top && item.getY()+item.getHeight()<=top+panelHeight);
                if(item.getMessage().getString().startsWith("channel_"))visited.add(item.getMessage().getString());
            }
            assertEquals(before,workspace.snapshot());
            var next=menu.stream().filter(b->b.getMessage().equals(net.minecraft.network.chat.Component.literal(">"))).findFirst().orElseThrow();
            if(!next.active)break;
            assertTrue(++pages<24);next.onClick(0,0);
        }
        assertEquals(24,visited.size());
        editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);
        assertEquals(before,workspace.snapshot());
        assertTrue(((List<?>)get(editor,"reporterMenu")).isEmpty());
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> channelCases() {
        return java.util.stream.Stream.of(320,426,640).flatMap(width ->
            java.util.stream.Stream.of("send","wait","receive").map(mode -> org.junit.jupiter.params.provider.Arguments.of(width,mode)));
    }
    @org.junit.jupiter.api.Test void copyingAReceiverHatKeepsTheChannelAndNestedScriptButMakesANewListener() throws Exception {
        var originalKey = new ModuleDefinition.Key("original_receiver", 1);
        var hat = new FlowProgram.Procedure("receiver", originalKey, Map.of(), Map.of(), Map.of(),
            FlowBlockRules.NONE, Optional.empty(), Optional.of("alpha"));
        var add = new FlowProgram.Shared("add", "score", new FlowInput.Expression("add", "value", Map.of()), true);
        var graph = new ComputationGraph(Map.of(), Map.of(
            "left", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "right", new ComputationGraph.Constant(new DataValue.IntegerValue(2)),
            "inner", new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER, Map.of("left", "left", "right", "right"))),
            Map.of("value", "inner"));
        var backup = new DataValue.IntegerValue(8);
        var layout = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(),
            List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(24, 92),
                List.of(hat, add, new FlowProgram.EndProcedure("end")))),
            new ScratchValueLayout(Map.of("add", Map.of("inner", backup)), List.of()));
        var workspace = new ProgramWorkspace();
        workspace.root().putCell("score", DataType.Scalar.INTEGER, new DataValue.IntegerValue(0));
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(new FlowProgram.Broadcast("send", "alpha", true)),
            new DataValue.RecordValue(Map.of()), layout, Map.of("add", graph), FlowProgram.Execution.REGISTER_V1)));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        var original = workspace.snapshot();
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        var block = canvas.blockPosition("receiver");
        double x = (int)get(canvas, "x") + block.x() + 25, y = (int)get(canvas, "y") + block.y() + 12;
        assertTrue(editor.mouseClicked(x, y, 1));
        click(editor, "duplicate_reporter");
        assertReceiverCopy(original, workspace.snapshot(), graph, backup);
        click(editor, "undo"); assertEquals(original, workspace.snapshot());

        assertTrue(editor.mouseClicked(x, y, 1)); click(editor, "clipboard_copy");
        assertEquals(original, workspace.snapshot(), "Copy alone cannot edit the draft");
        double blank = (int)get(canvas, "x") + (int)get(canvas, "width") - 12;
        assertTrue(canvas.contains(blank, y)); assertNull(canvas.blockAt(blank, y));
        assertTrue(editor.mouseClicked(blank, y, 1)); click(editor, "clipboard_paste");
        assertReceiverCopy(original, workspace.snapshot(), graph, backup);
        assertFalse((boolean)get(editor, "waiting"), "Copying a script must not send an installation request");
        var body = workspace.flow().orElseThrow();
        var host = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
        var limits = com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture.withIsa1a(
            new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
                new ResourceLimits(256, 64000, 8), SubgraphWorkspaceTest.LIMITS,
                com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,
                com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.DEFAULT,
                com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware.NONE, 3)); // Sender + two receivers.
        host.installFlow(body.program(workspace.snapshot().archive()), body.initial(), limits, true);
        for (int step = 0; step < 32 && host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; step++)
            host.step(limits, true);
        var installed = host.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, installed.state().status(), () -> installed.state().diagnostic().toString());
        assertEquals(new DataValue.IntegerValue(6), installed.memory().cells().field("score"),
            "Broadcast runs both independent listeners exactly once");
    }

    private static void assertReceiverCopy(ProgramDocument original, ProgramDocument document, ComputationGraph graph,
                                           DataValue.IntegerValue backup) {
        var body = document.flow().orElseThrow();
        assertEquals(original.flow().orElseThrow().steps(), body.steps());
        assertEquals(2, body.canvas().detached().size());
        var before = body.canvas().detached().getFirst();
        var duplicate = body.canvas().detached().getLast();
        assertEquals(original.flow().orElseThrow().canvas().detached().getFirst(), before);
        var source = (FlowProgram.Procedure)before.steps().getFirst();
        var target = (FlowProgram.Procedure)duplicate.steps().getFirst();
        assertEquals(source.event(), target.event());
        assertNotEquals(source.key(), target.key());
        assertFalse(duplicate.steps().stream().map(FlowProgram.Step::sourceId)
            .anyMatch(id -> before.steps().stream().anyMatch(step -> step.sourceId().equals(id))));
        var copiedAdd = duplicate.steps().get(1);
        assertEquals(graph, body.inline().get(copiedAdd.sourceId()));
        assertEquals(Map.of("inner", backup), body.canvas().values().forBlock(copiedAdd.sourceId()));
        var reopened = ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document));
        assertEquals(document, reopened);
        long handlers = reopened.flow().orElseThrow().program(reopened.archive()).steps().stream()
            .filter(s -> s instanceof FlowProgram.Procedure p && p.event().equals(Optional.of("alpha"))).count();
        assertEquals(2, handlers, "Both hats remain active program definitions, rather than an inert copy");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void deletingAStandaloneBroadcastHatRemovesOnlyItsScriptAndUndoRestoresIt(boolean drag) throws Exception {
        var main = List.<FlowProgram.Step>of(new FlowProgram.Broadcast("send", "alpha", true));
        var hat = new FlowProgram.Procedure("receiver", new ModuleDefinition.Key("receiver", 1), Map.of(), Map.of(),
            Map.of(), FlowBlockRules.NONE, Optional.empty(), Optional.of("alpha"));
        var graph = new ComputationGraph(Map.of(),
            Map.of("input", new ComputationGraph.Constant(new DataValue.IntegerValue(2))), Map.of("value", "input"));
        var script = new FlowBlockLayout.Stack(new ProgramDocument.Position(24, 92), List.of(hat,
            new FlowProgram.Shared("event_add", "score", new FlowInput.Expression("event_add", "value", Map.of()), true),
            new FlowProgram.EndProcedure("end")));
        var layout = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(), List.of(script));
        var workspace = new ProgramWorkspace();
        workspace.root().putCell("score", DataType.Scalar.INTEGER, new DataValue.IntegerValue(0));
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), main, new DataValue.RecordValue(Map.of()),
            layout, Map.of("event_add", graph), FlowProgram.Execution.REGISTER_V1)));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        var original = workspace.snapshot();
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        var block = canvas.blockPosition("receiver");
        int canvasLeft = (int)get(canvas, "x"), canvasTop = (int)get(canvas, "y");
        double x = canvasLeft + block.x() + 25, y = canvasTop + block.y() + 12;

        // Keep a known, independent clipboard selection: delete is not cut.
        assertTrue(editor.mouseClicked(x, y, 1));
        click(editor, "clipboard_copy");
        var clipboard = workspace.scratchClipboard();
        assertNotNull(clipboard);
        if (drag) {
            assertTrue(editor.mouseClicked(x, y, 0));
            assertTrue(editor.mouseReleased(canvasLeft - 13, canvasTop + 13, 0),
                "A coalesced pointer gesture may have no intermediate move");
        } else {
            assertTrue(editor.mouseClicked(x, y, 1));
            click(editor, "delete_event_script");
        }

        var removed = workspace.snapshot();
        var body = removed.flow().orElseThrow();
        assertEquals(main, body.steps());
        assertTrue(body.canvas().detached().isEmpty(), "The attached script must not remain as a detached stack");
        assertTrue(body.inline().isEmpty());
        assertEquals(clipboard, workspace.scratchClipboard());
        assertEquals(removed, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(removed)));
        assertFalse((boolean)get(editor, "waiting"));
        click(editor, "undo"); assertEquals(original, workspace.snapshot());
        click(editor, "redo"); assertEquals(removed, workspace.snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"broadcast", "startup", "key"})
    void visualEventCutMovesTheEntireScriptAndKeepsItsIdentityUnlessUndoAlreadyRestoredIt(String kind) throws Exception {
        var key = new ModuleDefinition.Key("visual_handler", 1);
        var hat = switch (kind) {
            case "broadcast" -> new FlowProgram.Procedure("hat", key, Map.of(), Map.of(), Map.of(),
                FlowBlockRules.NONE, Optional.empty(), Optional.of("alpha"));
            case "startup" -> FlowProgram.Procedure.startupHat("hat", key);
            case "key" -> FlowProgram.Procedure.keyHat("hat", key, "any");
            default -> throw new AssertionError("Unknown event test");
        };
        var main = List.<FlowProgram.Step>of(new FlowProgram.Sleep("main_wait", new FlowInput.Literal(new DataValue.IntegerValue(1))));
        var script = List.<FlowProgram.Step>of(hat, new FlowProgram.Sleep("event_wait", new FlowInput.Literal(new DataValue.IntegerValue(2))), new FlowProgram.EndProcedure("hat_end"));
        var layout = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(),
            List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(24, 92), script)));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), main, new DataValue.RecordValue(Map.of()),
            layout, Map.of(), FlowProgram.Execution.REGISTER_V1)));
        var editor = FlowCallScreenTest.editor(workspace, 640);
        var original = workspace.snapshot();
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        var block = canvas.blockPosition("hat");
        double x = (int)get(canvas, "x") + block.x() + 25, y = (int)get(canvas, "y") + block.y() + 11;
        double blank = (int)get(canvas, "x") + (int)get(canvas, "width") - 12;
        assertTrue(canvas.contains(blank, y)); assertNull(canvas.blockAt(blank, y));
        assertTrue(editor.mouseClicked(x, y, 1));
        click(editor, "clipboard_cut");
        var cut = workspace.snapshot();
        assertEquals(main, cut.flow().orElseThrow().steps());
        assertTrue(cut.flow().orElseThrow().canvas().detached().isEmpty());
        assertTrue(editor.mouseClicked(blank, y, 1));
        click(editor, "clipboard_paste");
        var moved = workspace.flow().orElseThrow().canvas().detached().getFirst();
        assertEquals(script, moved.steps());
        assertNotEquals(layout.detached().getFirst().position(), moved.position());
        click(editor, "undo"); assertEquals(cut, workspace.snapshot());
        click(editor, "undo"); assertEquals(original, workspace.snapshot());

        assertTrue(editor.mouseClicked(blank, y, 1));
        click(editor, "clipboard_paste");
        var both = workspace.flow().orElseThrow().canvas().detached();
        assertEquals(2, both.size());
        assertEquals(script, both.getFirst().steps());
        var copy = (FlowProgram.Procedure)both.getLast().steps().getFirst();
        assertNotEquals(key, copy.key());
        assertEquals(hat.event(), copy.event());
        assertEquals(hat.terminalKey(), copy.terminalKey());
        assertEquals(hat.startup(), copy.startup());
        assertEquals(workspace.snapshot(), ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot())));
        assertFalse((boolean)get(editor, "waiting"));
    }

}
