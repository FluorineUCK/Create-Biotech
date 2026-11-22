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

class ScratchListAppendScreenTest {
    private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { environment.client(); }
    @org.junit.jupiter.api.AfterEach void restoreClient() throws Exception { environment.restore(); }

    @ParameterizedTest @ValueSource(ints={320,426,640})
    void appendFromPaletteEditsUndoAndDocumentRoundTripExecuteOnIsa(int width) throws Exception {
        var initial=new DataValue.ListValue(List.of(new DataValue.IntegerValue(3)));
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),
            Map.of("result",new ProgramDefinition.Cell(new DataType.ListType(DataType.Scalar.INTEGER),initial)),List.of()),Map.of());
        var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.append(new FlowProgram.Shared("write","result",new FlowInput.Literal(initial),false));
        var workspace=new ProgramWorkspace(new ProgramDocument(archive,Map.of(),Optional.empty(),Optional.of(draft.snapshot())));
        var screen=FlowCallScreenTest.editor(workspace,width);
        dropReporter(screen,"inline_list_append",slot(screen,EditorValueFormat.format(initial),0));
        var canvas=(FlowBlockCanvas)ComputerProgramScreenTest.get(screen,"canvas");
        int x=(int)ComputerProgramScreenTest.get(canvas,"x"), y=(int)ComputerProgramScreenTest.get(canvas,"y");
        int canvasWidth=(int)ComputerProgramScreenTest.get(canvas,"width");
        boolean edited=false;
        for(int guard=0;guard<80;guard++) {
            var target=slot(screen,"0",0);
            double tx=target.x()+8,ty=target.y()+target.height()/2;
            if(canvas.contains(tx,ty)) {setLiteral(screen,target,"7");edited=true;break;}
            assertTrue(canvas.scroll(x+2,y+2,tx>=x+canvasWidth?1:-1,0,false));
        }
        assertTrue(edited,"Appended value must be accessible in a narrow window");
        var document=workspace.snapshot();
        click(screen,"undo");assertNotEquals(document,workspace.snapshot());
        click(screen,"redo");assertEquals(document,workspace.snapshot());
        assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var limits=StockApplicationDriverDocuments.hardware();
        var host=new ComputerBlockHost(()->{});
        host.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
        for(int step=0;step<200 && host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;step++)
            host.step(limits,true);
        var done=host.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE,done.state().status(),done.state().diagnostic().toString());
        assertEquals(new DataValue.ListValue(List.of(new DataValue.IntegerValue(3),new DataValue.IntegerValue(7))),done.memory().cells().field("result"));
        assertEquals(List.of(new DataValue.IntegerValue(3)),initial.values());
    }
}
