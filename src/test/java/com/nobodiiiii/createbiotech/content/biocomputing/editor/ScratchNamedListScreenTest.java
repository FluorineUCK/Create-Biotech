package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowInlineScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScratchNamedListScreenTest {
    private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { environment.client(); }
    @org.junit.jupiter.api.AfterEach void restoreClient() throws Exception { environment.restore(); }
    @ParameterizedTest @ValueSource(ints={320,426,640})
    void playerDeclaresPublicListAndUsesAllFiveOperationsWithoutTransactionForms(int width) throws Exception {
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var screen=FlowCallScreenTest.editor(workspace,width);
        click(screen,"category_data");click(screen,"declare_list");boxes(screen).getFirst().setValue("items");
        click(screen,"list_scope_private");click(screen,"apply");
        var declared=workspace.snapshot();assertEquals(new DataType.ListType(DataType.Scalar.INTEGER),workspace.root().cells().get("items").type());
        click(screen,"undo");assertFalse(workspace.root().cells().containsKey("items"));
        click(screen,"redo");assertEquals(declared,workspace.snapshot());
        var documents=new ArrayList<ProgramDocument>();
        String[] operations={"append_local","insert_local","replace_local","remove_local","clear"};
        long[] values={7,3,5};
        for(int index=0;index<operations.length;index++) {
            command(screen,"add_list_"+operations[index]);
            if(index<3)setLiteral(screen,visible(screen,"0"),Long.toString(values[index]));
            var document=workspace.snapshot();assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
            documents.add(document);
        }
        var expected=List.of(List.of(7L),List.of(3L,7L),List.of(5L,7L),List.of(7L),List.<Long>of());
        for(int i=0;i<documents.size();i++) {
            var limits=StockApplicationDriverDocuments.hardware();var host=new ComputerBlockHost(()->{});
            host.installProgram(PreparedProgram.prepare(documents.get(i),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
            for(int step=0;step<400 && host.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.READY;step++)host.step(limits,true);
            var result=host.flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.COMPLETE,result.state().status(),result.state().diagnostic().toString());
            assertEquals(expected.get(i),((DataValue.ListValue)result.memory().cells().field("items")).values().stream().map(v->((DataValue.IntegerValue)v).value()).toList());
            for(var command:documents.get(i).flow().orElseThrow().steps()) {
                var shared=(FlowProgram.Shared)command;
                assertEquals(new DataValue.TextValue("committed"),((DataValue.RecordValue)result.state().locals().field(shared.listResult().orElseThrow())).field("code"));
            }
        }
    }
    @org.junit.jupiter.api.Test
    void localListOperandShowsTheBoundNameInsteadOfCompilerInputName() throws Exception {
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var screen=FlowCallScreenTest.editor(workspace,426);
        click(screen,"category_data");click(screen,"declare_list");boxes(screen).getFirst().setValue("orders");click(screen,"apply");
        command(screen,"add_list_insert_local");
        var canvas=(FlowBlockCanvas)ComputerProgramScreenTest.get(screen,"canvas");
        var slots=canvas.inlineSlots();
        assertEquals("orders",slots.stream().filter(slot->"list".equals(slot.node())).findFirst().orElseThrow().label());
        assertEquals(net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor.add_list_insert_local").getString(),
            slots.stream().filter(slot->"value".equals(slot.node())).findFirst().orElseThrow().label());
    }
    @ParameterizedTest @ValueSource(ints={320,426,640})
    void selectedListCanSwitchScopeWithoutLosingTheItemOrUndoHistory(int width) throws Exception {
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var screen=FlowCallScreenTest.editor(workspace,width);
        click(screen,"category_data");click(screen,"declare_list");boxes(screen).getFirst().setValue("items");click(screen,"apply");
        click(screen,"declare_list");boxes(screen).getFirst().setValue("items");click(screen,"list_scope_private");click(screen,"apply");
        command(screen,"add_list_append_local");setLiteral(screen,visible(screen,"0"),"9");
        var before=workspace.snapshot();
        var target=visible(screen,"items");
        assertTrue(screen.mouseClicked(target.x()+8,target.y()+target.height()/2,0));
        assertTrue(screen.mouseReleased(target.x()+8,target.y()+target.height()/2,0));
        var choices=(List<?>)ComputerProgramScreenTest.get(screen,"variableWidgets");
        var publicChoice=choices.stream().filter(w->w instanceof com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton b
            && b.getMessage().equals(FlowBlockCatalog.label(new InlineGraphEdits.PublicVariable("items",List.of(),new DataType.ListType(DataType.Scalar.INTEGER)))))
            .map(w->(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton)w).findFirst().orElseThrow();
        assertTrue(screen.mouseClicked(publicChoice.getX()+8,publicChoice.getY()+8,0));
        screen.mouseReleased(publicChoice.getX()+8,publicChoice.getY()+8,0);
        var after=workspace.snapshot();
        assertInstanceOf(FlowProgram.Shared.class,after.flow().orElseThrow().steps().getFirst());
        assertEquals(new ComputationGraph.Constant(new DataValue.IntegerValue(9)),after.flow().orElseThrow().inline().values().iterator().next().nodes().get("item"));
        click(screen,"undo");assertEquals(before,workspace.snapshot());click(screen,"redo");assertEquals(after,workspace.snapshot());
        var encoded=(DataValue.RecordValue)ProgramDocumentCodec.encode(after);
        assertEquals(new DataValue.IntegerValue(39),encoded.field("version"));
        assertEquals(after,ProgramDocumentCodec.decode(encoded));
        for(int version:List.of(36,37,38)) {
            var downgraded=new LinkedHashMap<>(encoded.fields());downgraded.put("version",new DataValue.IntegerValue(version));
            assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,
                ()->ProgramDocumentCodec.decode(new DataValue.RecordValue(downgraded)));
        }
    }
    private static void command(ComputerFlowScreen screen,String key) throws Exception {
        click(screen,"category_data");
        for(int page=0;page<20;page++) {
            boolean found=screen.children().stream().anyMatch(child->child instanceof com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton b
                && b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.flow_editor."+key));
            if(found){click(screen,key);return;}
            assertTrue(button(screen,"palette_next").active);click(screen,"palette_next");
        }
        fail("Missing list command "+key);
    }
    private static FlowBlockCanvas.Slot visible(ComputerFlowScreen screen,String label) throws Exception {
        var canvas=(FlowBlockCanvas)ComputerProgramScreenTest.get(screen,"canvas");
        int x=(int)ComputerProgramScreenTest.get(canvas,"x"),y=(int)ComputerProgramScreenTest.get(canvas,"y");
        int width=(int)ComputerProgramScreenTest.get(canvas,"width"),height=(int)ComputerProgramScreenTest.get(canvas,"height");
        for(int guard=0;guard<120;guard++) {
            var target=canvas.inlineSlots().stream().filter(slot->label.equals(slot.label())).reduce((a,b)->b).orElseThrow();
            double tx=target.x()+8,ty=target.y()+target.height()/2;
            if(canvas.contains(tx,ty))return target;
            canvas.scroll(x+2,y+2,tx>=x+width?1:tx<x?-1:0,ty>=y+height?-1:ty<y?1:0,false);
        }
        throw new AssertionError("Cannot reach named list operand");
    }
}
