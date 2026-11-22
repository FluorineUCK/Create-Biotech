package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowInlineScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ScratchReporterDragScreenTest {
    private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }

    @Test void nestedValueCanBeParkedSavedAndSnappedBackWithoutExecutingWhileParked() throws Exception {
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor=FlowCallScreenTest.editor(workspace,640);set(editor,"height",480);editor.init();
        click(editor,"category_variables");click(editor,"add_assign");setLiteral(editor,slot(editor,"0",0),"7");
        dropReporter(editor,"inline_add_integer",slot(editor,"7",0));setLiteral(editor,slot(editor,"0",0),"5");
        var before=workspace.snapshot();var canvas=(FlowBlockCanvas)get(editor,"canvas");
        var sum=slot(editor,"+",0);var tile=InlineGraphView.layout(before.flow().orElseThrow().inline().get(sum.call()),s->s.length()*6);
        double x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+140;
        drag(editor,sum.x()+tile.labelX()+2,sum.y()+sum.height()/2,x,y);
        var parked=workspace.snapshot();
        assertEquals(1,parked.flow().orElseThrow().canvas().values().parked().size());
        assertEquals(new DataValue.IntegerValue(7),run(parked).field("block_1_value"),"Detaching restores the prior literal; parked values do not execute");
        click(editor,"undo");assertEquals(before,workspace.snapshot());click(editor,"redo");assertEquals(parked,workspace.snapshot());
        var loaded=ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(parked));assertEquals(parked,loaded);
        var reopened=new ProgramWorkspace(loaded);editor=FlowCallScreenTest.editor(reopened,640);set(editor,"height",480);editor.init();
        canvas=(FlowBlockCanvas)get(editor,"canvas");var value=loaded.flow().orElseThrow().canvas().values().parked().getFirst();
        var view=InlineGraphView.layout(value.graph(),s->s.length()*6);var target=slot(editor,"7",0);
        drag(editor,(int)get(canvas,"x")+value.position().x()+view.labelX()+2,(int)get(canvas,"y")+value.position().y()+view.height()/2,
            target.x()+8,target.y()+target.height()/2);
        assertTrue(reopened.flow().orElseThrow().canvas().values().parked().isEmpty());
        assertEquals(new DataValue.IntegerValue(12),run(reopened.snapshot()).field("block_1_value"));
        click(editor,"undo");assertEquals(loaded,reopened.snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void parkedLiteralCanBeEditedMovedDuplicatedDeletedAndUsedAtEachWidth(int width) throws Exception {
        var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());
        var number=new ScratchValueLayout.Reporter(new ProgramDocument.Position(18,140),
            new ComputationGraph(Map.of(),Map.of("value",new ComputationGraph.Constant(new DataValue.IntegerValue(13))),Map.of("value","value")),Map.of(),Map.of());
        draft.editValues(new ScratchValueLayout(Map.of(),List.of(number)));
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(draft.snapshot()));
        var editor=FlowCallScreenTest.editor(workspace,width);set(editor,"height",480);editor.init();
        click(editor,"category_variables");click(editor,"add_assign");
        var canvas=(FlowBlockCanvas)get(editor,"canvas");
        double x=(int)get(canvas,"x")+26,y=(int)get(canvas,"y")+148;
        var parked=canvas.parkedSlotHit(x,y);assertNotNull(parked);
        assertNotNull(editor.typeTooltip((int)x,(int)y,true));
        setLiteral(editor,parked.slot(),"19");
        var edited=workspace.snapshot();assertEquals(new DataValue.IntegerValue(19),((ComputationGraph.Constant)edited.flow().orElseThrow().canvas().values().parked().getFirst().graph().nodes().get("value")).value());
        drag(editor,x,y,x+24,y+12);var moved=workspace.snapshot();assertNotEquals(edited,moved);
        click(editor,"undo");assertEquals(edited,workspace.snapshot());click(editor,"redo");assertEquals(moved,workspace.snapshot());
        x+=24;y+=12;
        assertTrue(editor.mouseClicked(x+5,y+5,1));click(editor,"duplicate_reporter");
        assertEquals(2,workspace.flow().orElseThrow().canvas().values().parked().size());
        assertTrue(editor.mouseClicked(x+5,y+5,1));click(editor,"delete_reporter");
        assertEquals(1,workspace.flow().orElseThrow().canvas().values().parked().size());
        click(editor,"undo");assertEquals(2,workspace.flow().orElseThrow().canvas().values().parked().size());
        click(editor,"undo");assertEquals(moved,workspace.snapshot());
        var target=slot(editor,"0",0);
        drag(editor,x+5,y+5,target.x()+8,target.y()+8);
        assertTrue(workspace.flow().orElseThrow().canvas().values().parked().isEmpty());
        assertEquals(new DataValue.IntegerValue(19),run(workspace.snapshot()).field("block_1_value"));
    }
    @Test void invalidTypeDropAndCancelledDragDoNotLoseTheSource() throws Exception {
        var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.insertRepeat(null,new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowInput.Literal(new DataValue.IntegerValue(2)));
        draft.editValues(new ScratchValueLayout(Map.of(),List.of(new ScratchValueLayout.Reporter(new ProgramDocument.Position(20,140),
            new ComputationGraph(Map.of(),Map.of("value",new ComputationGraph.Constant(new DataValue.BooleanValue(true))),Map.of("value","value")),Map.of(),Map.of()))));
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(draft.snapshot()));
        var editor=FlowCallScreenTest.editor(workspace,640);set(editor,"height",480);editor.init();
        var before=workspace.snapshot();var canvas=(FlowBlockCanvas)get(editor,"canvas");
        double x=(int)get(canvas,"x")+25,y=(int)get(canvas,"y")+145;
        var target=slot(editor,"2",0);drag(editor,x,y,target.x()+5,target.y()+5);assertEquals(before,workspace.snapshot());
        assertTrue(editor.mouseClicked(x,y,0));assertTrue(editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0));
        editor.mouseReleased(x+20,y+20,0);assertEquals(before,workspace.snapshot());
        assertTrue(editor.mouseClicked(x,y,0));editor.onClose();editor.mouseReleased(x+20,y+20,0);assertEquals(before,workspace.snapshot());
    }
    @Test void savedProcedureArgumentCannotCaptureASameNamedMainVariable() throws Exception {
        var number=DataType.Scalar.INTEGER;var key=new ModuleDefinition.Key("transform",1);
        var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());draft.initial(new DataValue.RecordValue(Map.of("n",new DataValue.IntegerValue(9))));
        var zero=new ComputationGraph(Map.of(),Map.of("value",new ComputationGraph.Constant(new DataValue.IntegerValue(0))),Map.of("value","value"));
        var main=new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Evaluate("main","main",
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings(Map.of(),Map.of("value","out")));
        var local=new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Evaluate("local","local",
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings(Map.of(),Map.of("value","out")));
        var definition=new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure("def",key,Map.of("n",number),Map.of("out",number),Map.of("out","out"));
        draft.place(List.of(main),new FlowDraft.Socket(null,0),new ProgramDocument.Position(0,0),Map.of("main",zero));
        draft.place(List.of(definition,local,new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.EndProcedure("end")),null,
            new ProgramDocument.Position(0,80),Map.of("local",zero));
        var reporter=new ScratchValueLayout.Reporter(new ProgramDocument.Position(180,170),
            new ComputationGraph(Map.of("n",number),Map.of("value",new ComputationGraph.Input("n")),Map.of("value","value")),Map.of("n","n"),Map.of())
            .scoped(new ScratchValueLayout.ProcedureScope("def",key));
        draft.editValues(new ScratchValueLayout(Map.of(),List.of(reporter)));
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(draft.snapshot()));
        var document=ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot()));workspace=new ProgramWorkspace(document);
        var editor=FlowCallScreenTest.editor(workspace,640);set(editor,"height",480);editor.init();
        var canvas=(FlowBlockCanvas)get(editor,"canvas");double x=(int)get(canvas,"x")+184,y=(int)get(canvas,"y")+175;
        var mainSlot=slot(editor,"0",0);drag(editor,x,y,mainSlot.x()+5,mainSlot.y()+5);assertEquals(document,workspace.snapshot());
        var localSlot=slot(editor,"0",1);drag(editor,x,y,localSlot.x()+5,localSlot.y()+5);
        assertTrue(workspace.flow().orElseThrow().canvas().values().parked().isEmpty());
        assertEquals(Map.of("n","n"),FlowInlineValues.bindings(workspace.flow().orElseThrow().canvas().detached().getFirst().steps().get(1)).arguments());
        click(editor,"undo");assertEquals(document,workspace.snapshot());
    }
    @Test void paletteReporterCanBeParkedWithoutAddingExecutableInstructions() throws Exception {
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor=FlowCallScreenTest.editor(workspace,640);set(editor,"height",480);editor.init();
        click(editor,"category_operators");var canvas=(FlowBlockCanvas)get(editor,"canvas");
        var program=workspace.flow().orElseThrow().program(workspace.snapshot().archive());
        var add=button(editor,"inline_add_integer");
        drag(editor,add.getX()+8,add.getY()+8,(int)get(canvas,"x")+24,(int)get(canvas,"y")+140);
        var saved=workspace.snapshot();assertEquals(1,saved.flow().orElseThrow().canvas().values().parked().size());
        assertEquals(program,saved.flow().orElseThrow().program(saved.archive()),"An unattached expression is editing metadata, not running code or RAM");
        assertEquals(saved,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(saved)));
        click(editor,"undo");assertTrue(workspace.flow().orElseThrow().canvas().values().parked().isEmpty());
        click(editor,"redo");assertEquals(saved,workspace.snapshot());
    }
    @Test void changingNamedListTypeLeavesAnEditableValueWhichCanBeReusedElsewhere() throws Exception {
        var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.declareList("numbers",DataType.Scalar.INTEGER);draft.declareList("words",DataType.Scalar.TEXT);
        var block=FlowNamedLists.append("append","numbers",new DataType.ListType(DataType.Scalar.INTEGER),draft.snapshot().inputs().keySet());
        var edited=InlineGraphEdits.replace(block.graph(),FlowInlineValues.bindings(block.step()),"item",new InlineGraphEdits.Literal(new DataValue.IntegerValue(27)));
        draft.place(List.of(block.step()),new FlowDraft.Socket(null,0),new ProgramDocument.Position(0,0),Map.of("append",edited.graph()));
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(draft.snapshot()));
        var editor=FlowCallScreenTest.editor(workspace,640);set(editor,"height",480);editor.init();
        click(editor,"category_variables");click(editor,"add_assign");
        var before=workspace.snapshot();var target=slot(editor,"numbers",0);
        editor.mouseClicked(target.x()+5,target.y()+5,0);editor.mouseReleased(target.x()+5,target.y()+5,0);
        var label=FlowBlockCatalog.label(new InlineGraphEdits.Variable("words",List.of(),new DataType.ListType(DataType.Scalar.TEXT)));
        var widgets=(List<?>)get(editor,"variableWidgets");
        var choice=widgets.stream().filter(w->w instanceof com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton b && b.getMessage().equals(label))
            .map(w->(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton)w).findFirst().orElseThrow();
        editor.mouseClicked(choice.getX()+5,choice.getY()+5,0);editor.mouseReleased(choice.getX()+5,choice.getY()+5,0);
        var switched=workspace.snapshot();assertEquals(1,switched.flow().orElseThrow().canvas().values().parked().size());
        click(editor,"undo");assertEquals(before,workspace.snapshot());click(editor,"redo");assertEquals(switched,workspace.snapshot());
        var canvas=(FlowBlockCanvas)get(editor,"canvas");var parked=switched.flow().orElseThrow().canvas().values().parked().getFirst();
        double x=(int)get(canvas,"x")+parked.position().x()+5,y=(int)get(canvas,"y")+parked.position().y()+5;
        double blankX=(int)get(canvas,"x")+15,blankY=(int)get(canvas,"y")+190;
        drag(editor,x,y,blankX,blankY);
        var destination=slot(editor,"0",0);drag(editor,blankX+5,blankY+5,destination.x()+5,destination.y()+5);
        assertTrue(workspace.flow().orElseThrow().canvas().values().parked().isEmpty());
        var values=run(workspace.snapshot());assertEquals(new DataValue.IntegerValue(27),values.field("block_1_value"));
        assertEquals(new DataValue.ListValue(List.of()),values.field("numbers"));
        assertEquals(new DataValue.ListValue(List.of(new DataValue.TextValue(""))),values.field("words"));
    }
    static void drag(ComputerFlowScreen editor,double x,double y,double tx,double ty) {
        assertTrue(editor.mouseClicked(x,y,0));editor.mouseDragged(tx,ty,0,tx-x,ty-y);assertTrue(editor.mouseReleased(tx,ty,0));
    }
}
