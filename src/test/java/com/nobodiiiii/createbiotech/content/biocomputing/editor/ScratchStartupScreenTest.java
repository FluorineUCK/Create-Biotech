package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class ScratchStartupScreenTest {
    private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { environment.client(); }
    @org.junit.jupiter.api.AfterEach void restoreClient() throws Exception { environment.restore(); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void programStartHatIsAnIndependentRootRatherThanAnInstructionInMain(int width) throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var screen = FlowCallScreenTest.editor(workspace, width);
        click(screen, "category_events");
        var prototype = button(screen, "add_startup");
        var canvas = get(screen, "canvas");
        double x = (int)get(canvas, "x") + 28, y = (int)get(canvas, "y") + 49;
        double fromX = prototype.getX() + 10, fromY = prototype.getY() + 10;
        var before = workspace.snapshot();
        assertTrue(screen.mouseClicked(fromX, fromY, 0));
        screen.mouseDragged(x, y, 0, x - fromX, y - fromY);
        assertEquals(before, workspace.snapshot());
        assertTrue(screen.mouseReleased(x, y, 0));
        var drafted = workspace.snapshot();
        var body = drafted.flow().orElseThrow();
        assertTrue(body.steps().isEmpty(), "The main connector cannot absorb another event hat");
        var root = assertInstanceOf(FlowProgram.Procedure.class, body.canvas().detached().getFirst().steps().getFirst());
        assertTrue(root.startup());
        assertEquals(List.of(root.key()), body.program(drafted.archive()).startupEntries());
        assertEquals(drafted, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(drafted)));
        assertFalse((boolean)get(screen, "waiting"));
        var navigated=new java.util.concurrent.atomic.AtomicBoolean();set(screen,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(next->navigated.set(true)));
        var view=(FlowBlockCanvas)get(screen,"canvas");view.focus(root.sourceId());
        var block=view.blocks().stream().filter(b->b.step().sourceId().equals(root.sourceId())).findFirst().orElseThrow();
        var viewport=(com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport)get(view,"viewport");
        screen.mouseClicked((int)get(view,"x")+viewport.screenX(block.x()+8),(int)get(view,"y")+viewport.screenY(block.y()+10),1);
        click(screen,"procedure_context_edit");assertFalse(navigated.get());assertEquals(drafted,workspace.snapshot());
        screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);
        var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits(4096,64000,1),
            new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000,100000,1000000),
            com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.DEFAULT,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware.NONE,2);
        var host=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});
        host.installFlow(body.program(drafted.archive()),body.initial(),limits,true);
        assertEquals(List.of(root.key()),host.flowSnapshot().orElseThrow().tasks().contexts().stream().map(t->t.entry().orElseThrow()).toList());
        for(int i=0;i<6;i++)host.step(limits,true);
        assertTrue(host.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());

        click(screen, "undo"); assertEquals(before, workspace.snapshot());
        click(screen, "redo"); assertEquals(drafted, workspace.snapshot());
    }
}
