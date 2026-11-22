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

class ScratchListSearchScreenTest {
    private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { environment.client(); }
    @org.junit.jupiter.api.AfterEach void restoreClient() throws Exception { environment.restore(); }
    @ParameterizedTest @ValueSource(ints={320,426,640})
    void searchReporterBindsAListAndSurvivesUndoSaveAndIsaExecution(int width) throws Exception {
        for(boolean contains:List.of(false,true)) {
            DataValue initial=contains?new DataValue.BooleanValue(false):new DataValue.IntegerValue(0);
            DataType type=contains?DataType.Scalar.BOOLEAN:DataType.Scalar.INTEGER;
            DataValue item=contains?new DataValue.BooleanValue(true):new DataValue.IntegerValue(7);
            var items=new DataValue.ListValue(List.of(item,item));
            var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),
                Map.of("result",new ProgramDefinition.Cell(type,initial)),List.of()),Map.of());
            var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());
            draft.append(new FlowProgram.Shared("write","result",new FlowInput.Literal(initial),false));
            var seed=draft.snapshot();
            var body=new FlowDocumentBody(Map.of("items",new DataType.ListType(type)),seed.steps(),
                new DataValue.RecordValue(Map.of("items",items)),seed.canvas(),seed.inline(),seed.execution(),seed.compilation());
            var workspace=new ProgramWorkspace(new ProgramDocument(archive,Map.of(),Optional.empty(),Optional.of(body)));
            var screen=FlowCallScreenTest.editor(workspace,width);
            dropReporter(screen,contains?"inline_list_contains":"inline_list_find",visibleSlot(screen,contains?"false":"0"));
            dropReporter(screen,"inline_private",visibleSlot(screen,"[]"));
            setLiteral(screen,visibleSlot(screen,contains?"false":"0"),contains?"true":"7");
            var edited=workspace.snapshot();
            click(screen,"undo");assertNotEquals(edited,workspace.snapshot());
            click(screen,"redo");assertEquals(edited,workspace.snapshot());
            assertEquals(edited,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(edited)));
            var limits=StockApplicationDriverDocuments.hardware();
            var host=new ComputerBlockHost(()->{});
            host.installProgram(PreparedProgram.prepare(edited,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
            for(int step=0;step<300 && host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;step++)
                host.step(limits,true);
            var done=host.flowSnapshot().orElseThrow();
            assertEquals(FlowRuntime.Status.COMPLETE,done.state().status(),done.state().diagnostic().toString());
            assertEquals(contains?new DataValue.BooleanValue(true):new DataValue.IntegerValue(1),done.memory().cells().field("result"));
        }
    }
    private static FlowBlockCanvas.Slot visibleSlot(ComputerFlowScreen screen,String label) throws Exception {
        var canvas=(FlowBlockCanvas)ComputerProgramScreenTest.get(screen,"canvas");
        int x=(int)ComputerProgramScreenTest.get(canvas,"x"),y=(int)ComputerProgramScreenTest.get(canvas,"y");
        int width=(int)ComputerProgramScreenTest.get(canvas,"width");
        for(int guard=0;guard<80;guard++) {
            var target=slot(screen,label,0);double tx=target.x()+8,ty=target.y()+target.height()/2;
            if(canvas.contains(tx,ty)) return target;
            assertTrue(canvas.scroll(x+2,y+2,tx>=x+width?1:-1,0,false));
        }
        throw new AssertionError("Cannot pan to list search operand");
    }
}
