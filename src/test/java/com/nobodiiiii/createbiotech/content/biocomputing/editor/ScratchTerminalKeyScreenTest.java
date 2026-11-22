package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
class ScratchTerminalKeyScreenTest {
    private final ComputerProgramScreenTest fixture=new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { fixture.client(); }
    @org.junit.jupiter.api.AfterEach void restore() throws Exception { fixture.restore(); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void paletteDragCreatesIndependentKeyRootAndUndoRestoresDraft(int width) throws Exception {
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor=FlowCallScreenTest.editor(workspace,width);click(editor,"category_events");
        var prototype=button(editor,"add_terminal_key");var canvas=get(editor,"canvas");
        double x=(int)get(canvas,"x")+28,y=(int)get(canvas,"y")+49;
        double fromX=prototype.getX()+10,fromY=prototype.getY()+10;var before=workspace.snapshot();
        assertTrue(editor.mouseClicked(fromX,fromY,0));editor.mouseDragged(x,y,0,x-fromX,y-fromY);
        assertEquals(before,workspace.snapshot());assertTrue(editor.mouseReleased(x,y,0));
        var drafted=workspace.snapshot();var body=drafted.flow().orElseThrow();assertTrue(body.steps().isEmpty());
        var hat=assertInstanceOf(FlowProgram.Procedure.class,body.canvas().detached().getFirst().steps().getFirst());
        assertEquals(Optional.of("space"),hat.terminalKey());assertFalse(hat.startup());
        assertTrue(body.program(drafted.archive()).usesTerminalKeys());
        assertEquals(drafted,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(drafted)));
        click(editor,"undo");assertEquals(before,workspace.snapshot());click(editor,"redo");assertEquals(drafted,workspace.snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void keyDropdownChangesOnlyTheSelectorAndCanBeUndone(int width) throws Exception {
        var key=new ModuleDefinition.Key("terminal_key_test",1);var hat=FlowProgram.Procedure.keyHat("hat",key,"space");
        var stack=new FlowBlockLayout.Stack(new ProgramDocument.Position(24,70),List.of(hat,
            new FlowProgram.Sleep("wait",new FlowInput.Literal(new DataValue.IntegerValue(1))),new FlowProgram.EndProcedure("end")));
        var layout=new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(),List.of(stack));
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(),List.of(),new DataValue.RecordValue(Map.of()),layout,Map.of(),FlowProgram.Execution.REGISTER_V1)));
        var editor=FlowCallScreenTest.editor(workspace,width);var original=workspace.snapshot();
        var canvas=(FlowBlockCanvas)get(editor,"canvas");canvas.focus("hat");
        var block=canvas.blocks().getFirst();var viewport=(com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport)get(canvas,"viewport");
        var caption=net.minecraft.network.chat.Component.translatableWithFallback("create_biotech.biocomputing.flow_editor.terminal_key_caption","When key");
        int chipX=19+net.minecraft.client.Minecraft.getInstance().font.width(caption);
        assertTrue(editor.mouseClicked((int)get(canvas,"x")+viewport.screenX(block.x()+chipX),(int)get(canvas,"y")+viewport.screenY(block.y()+14),0));
        var any=editor.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(control->control.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.terminal.key_any")).findFirst().orElseThrow();
        assertEquals(original,workspace.snapshot());assertTrue(editor.mouseClicked(any.getX()+2,any.getY()+2,0));
        var changed=workspace.snapshot().flow().orElseThrow().canvas().detached().getFirst();
        assertEquals(FlowProgram.Procedure.keyHat("hat",key,"any"),changed.steps().getFirst());
        assertEquals(stack.steps().subList(1,3),changed.steps().subList(1,3));assertEquals(stack.position(),changed.position());
        assertEquals(workspace.snapshot(),ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot())));
        click(editor,"undo");assertEquals(original,workspace.snapshot());
    }
}
