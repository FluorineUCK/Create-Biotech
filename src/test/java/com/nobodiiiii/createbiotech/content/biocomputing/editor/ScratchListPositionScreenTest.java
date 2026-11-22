package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.click;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowInlineScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScratchListPositionScreenTest {
    private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { environment.client(); }
    @org.junit.jupiter.api.AfterEach void restoreClient() throws Exception { environment.restore(); }
    @ParameterizedTest @ValueSource(ints={320,426,640})
    void indexMenuPreservesNumericShadowUndoAndProgramExecution(int width) throws Exception {
        var items=new DataValue.ListValue(List.of(new DataValue.IntegerValue(10),new DataValue.IntegerValue(20)));
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),
            Map.of("result",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(0))),List.of()),Map.of());
        var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.append(new FlowProgram.Evaluate("write","write",new FlowCallBindings(Map.of(),Map.of("value","answer"))));
        var initialGraph=new ComputationGraph(Map.of(),Map.of("value",new ComputationGraph.Constant(new DataValue.IntegerValue(0))),Map.of("value","value"));
        var seed=draft.snapshot();
        var body=new FlowDocumentBody(Map.of("items",new DataType.ListType(DataType.Scalar.INTEGER)),seed.steps(),
            new DataValue.RecordValue(Map.of("items",items)),seed.canvas(),Map.of("write",initialGraph),seed.execution(),seed.compilation());
        var workspace=new ProgramWorkspace(new ProgramDocument(archive,Map.of(),Optional.empty(),Optional.of(body)));
        var screen=FlowCallScreenTest.editor(workspace,width);
        dropReporter(screen,"inline_list_get",visibleSlot(screen,"0"));
        dropReporter(screen,"inline_private",visibleSlot(screen,"[]"));
        setLiteral(screen,visibleSlot(screen,"1"),"2");
        var numeric=workspace.snapshot();
        var index=visibleMenuSlot(screen,"2");
        assertTrue(screen.mouseClicked(index.x()+index.width()-3,index.y()+index.height()/2,0));
        click(screen,"list_position_last");
        var last=workspace.snapshot();assertNotEquals(numeric,last);
        click(screen,"undo");assertEquals(numeric,workspace.snapshot());
        click(screen,"redo");assertEquals(last,workspace.snapshot());
        assertEquals(last,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(last)));
        var target=visibleMenuSlot(screen,net.minecraft.network.chat.Component.translatable(
            "create_biotech.biocomputing.flow_editor.list_position_last").getString());
        screen.mouseClicked(target.x()+target.width()-3,target.y()+target.height()/2,0);
        click(screen,"list_position_number");assertEquals(numeric,workspace.snapshot());
        index=visibleMenuSlot(screen,"2");screen.mouseClicked(index.x()+index.width()-3,index.y()+index.height()/2,0);
        click(screen,"list_position_random");
        var random=workspace.snapshot();
        for(var document:List.of(last,random)) {
            var limits=StockApplicationDriverDocuments.hardware();var host=new ComputerBlockHost(()->{});
            host.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
            for(int step=0;step<100 && host.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.READY;step++)host.step(limits,true);
            var done=host.flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.COMPLETE,done.state().status(),done.state().diagnostic().toString());
            long value=((DataValue.IntegerValue)done.state().locals().field("answer")).value();
            if(document==last)assertEquals(20,value);else assertTrue(value==10||value==20);
        }
    }
    private static FlowBlockCanvas.Slot visibleMenuSlot(ComputerFlowScreen screen,String label) throws Exception {
        return visibleSlot(screen,label,true);
    }
    private static FlowBlockCanvas.Slot visibleSlot(ComputerFlowScreen screen,String label) throws Exception {
        return visibleSlot(screen,label,false);
    }
    private static FlowBlockCanvas.Slot visibleSlot(ComputerFlowScreen screen,String label,boolean menu) throws Exception {
        var canvas=(FlowBlockCanvas)ComputerProgramScreenTest.get(screen,"canvas");
        int x=(int)ComputerProgramScreenTest.get(canvas,"x"),y=(int)ComputerProgramScreenTest.get(canvas,"y");
        int width=(int)ComputerProgramScreenTest.get(canvas,"width");
        for(int guard=0;guard<80;guard++) {
            var target=slot(screen,label,0);double tx=target.x()+(menu?target.width()-3:8),ty=target.y()+target.height()/2;
            if(canvas.contains(tx,ty)) return target;
            assertTrue(canvas.scroll(x+2,y+2,tx>=x+width?1:-1,0,false));
        }
        throw new AssertionError("Cannot pan to list search operand");
    }
}
