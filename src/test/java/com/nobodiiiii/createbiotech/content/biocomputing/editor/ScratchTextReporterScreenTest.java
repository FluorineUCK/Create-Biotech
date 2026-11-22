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

class ScratchTextReporterScreenTest {
    private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { environment.client(); }
    @org.junit.jupiter.api.AfterEach void restoreClient() throws Exception { environment.restore(); }

    @ParameterizedTest @ValueSource(ints={320,426,640})
    void dragEditUndoSaveAndRunOrdinaryTextReporters(int width) throws Exception {
        for(String name:List.of("concat","letter_of","contains_ignore_case")) {
            boolean bool=name.equals("contains_ignore_case");
            DataValue initial=bool?new DataValue.BooleanValue(false):new DataValue.TextValue("seed");
            DataType type=bool?DataType.Scalar.BOOLEAN:DataType.Scalar.TEXT;
            var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),
                Map.of("result",new ProgramDefinition.Cell(type,initial)),List.of()),Map.of());
            var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());
            draft.append(new FlowProgram.Shared("write","result",new FlowInput.Literal(initial),false));
            var workspace=new ProgramWorkspace(new ProgramDocument(archive,Map.of(),Optional.empty(),Optional.of(draft.snapshot())));
            var screen=FlowCallScreenTest.editor(workspace,width);
            dropReporter(screen,"inline_text_"+name,slot(screen,bool?"false":"\"seed\"",0));
            DataValue expected;
            if(name.equals("concat")) {
                setVisibleLiteral(screen,"\"seed\"","前");
                setVisibleLiteral(screen,"\"\"","后");
                expected=new DataValue.TextValue("前后");
            } else if(name.equals("letter_of")) {
                setVisibleLiteral(screen,"\"seed\"","A😀中");
                setVisibleLiteral(screen,"1","2");
                expected=new DataValue.TextValue("😀");
            } else {
                setVisibleLiteral(screen,"\"\"","A😀B");
                setVisibleLiteral(screen,"\"\"","😀b");
                expected=new DataValue.BooleanValue(true);
            }
            var edited=workspace.snapshot();
            click(screen,"undo");assertNotEquals(edited,workspace.snapshot());
            click(screen,"redo");assertEquals(edited,workspace.snapshot());
            var document=ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(edited));
            assertEquals(edited,document);
            var limits=StockApplicationDriverDocuments.hardware();
            var host=new ComputerBlockHost(()->{});
            host.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
            for(int step=0;step<200 && host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;step++)
                host.step(limits,true);
            var done=host.flowSnapshot().orElseThrow();
            assertEquals(FlowRuntime.Status.COMPLETE,done.state().status(),done.state().diagnostic().toString());
            assertEquals(expected,done.memory().cells().field("result"));
        }
    }
    private static void setVisibleLiteral(ComputerFlowScreen screen,String label,String value) throws Exception {
        var canvas=(FlowBlockCanvas)ComputerProgramScreenTest.get(screen,"canvas");
        int x=(int)ComputerProgramScreenTest.get(canvas,"x"),y=(int)ComputerProgramScreenTest.get(canvas,"y");
        int width=(int)ComputerProgramScreenTest.get(canvas,"width");
        // Narrow canvases intentionally pan horizontally; never click a clipped/outside value slot.
        for(int guard=0;guard<80;guard++) {
            var target=slot(screen,label,0);
            double tx=target.x()+8,ty=target.y()+target.height()/2;
            if(canvas.contains(tx,ty)) {setLiteral(screen,target,value);return;}
            assertTrue(canvas.scroll(x+2,y+2,tx>=x+width?1:-1,0,false));
        }
        fail("Cannot pan to text operand");
    }
}
