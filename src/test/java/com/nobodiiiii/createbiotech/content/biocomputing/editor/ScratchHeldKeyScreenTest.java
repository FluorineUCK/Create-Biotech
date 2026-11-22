package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
class ScratchHeldKeyScreenTest {
    private final ComputerProgramScreenTest fixture=new ComputerProgramScreenTest();
    @org.junit.jupiter.api.BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @org.junit.jupiter.api.BeforeEach void client() throws Exception { fixture.client(); }
    @org.junit.jupiter.api.AfterEach void restore() throws Exception { fixture.restore(); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void sensingReporterDropsIntoValueSlotAndSelectorPreservesUndoAndDocument(int width) throws Exception {
        var body=FlowDocumentBody.emptyAssembly();var graph=new ComputationGraph(Map.of(),Map.of("value",new ComputationGraph.Constant(new DataValue.BooleanValue(false))),Map.of("value","value"));
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(),
            List.of(new FlowProgram.Evaluate("read","read",new FlowCallBindings(Map.of(),Map.of("value","held")))),body.initial(),body.canvas(),Map.of("read",graph),body.execution(),body.compilation())));
        var editor=FlowCallScreenTest.editor(workspace,width);var canvas=(FlowBlockCanvas)get(editor,"canvas");canvas.focus("read");
        click(editor,"category_sensing");
        int pages=0;
        while(editor.children().stream().noneMatch(c->c instanceof com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton b && b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().endsWith("inline_key_down"))) {assertTrue(pages++<12);click(editor,"palette_next");}
        var prototype=button(editor,"inline_key_down");var target=FlowInlineScreenTest.slot(editor,"false",0);var before=workspace.snapshot();
        editor.mouseClicked(prototype.getX()+8,prototype.getY()+8,0);editor.mouseDragged(target.x()+8,target.y()+target.height()/2,0,20,0);editor.mouseReleased(target.x()+8,target.y()+target.height()/2,0);
        var inserted=workspace.snapshot();assertNotEquals(before,inserted);
        var keyGraph=inserted.flow().orElseThrow().inline().get("read");assertTrue(keyGraph.nodes().values().stream().anyMatch(ComputationGraph.KeyDown.class::isInstance));
        var keySlot=((FlowBlockCanvas)get(editor,"canvas")).inlineSlots().stream().filter(slot->keyGraph.nodes().get(slot.node()) instanceof ComputationGraph.KeyDown).findFirst().orElseThrow();
        canvas=(FlowBlockCanvas)get(editor,"canvas");
        for(int pan=0;!canvas.contains(keySlot.x()+keySlot.width()-3,keySlot.y()+keySlot.height()/2);pan++) {
            assertTrue(pan<80);int left=(int)get(canvas,"x"),top=(int)get(canvas,"y"),cw=(int)get(canvas,"width");
            assertTrue(canvas.scroll(left+2,top+2,keySlot.x()+keySlot.width()>=left+cw?1:-1,0,false));
            keySlot=canvas.inlineSlots().stream().filter(slot->keyGraph.nodes().get(slot.node()) instanceof ComputationGraph.KeyDown).findFirst().orElseThrow();
        }
        editor.mouseClicked(keySlot.x()+keySlot.width()-3,keySlot.y()+keySlot.height()/2,0);
        var any=editor.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance).map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast)
            .filter(b->b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.terminal.key_any")).findFirst().orElseThrow();
        editor.mouseClicked(any.getX()+2,any.getY()+2,0);
        var changed=workspace.snapshot();assertTrue(changed.flow().orElseThrow().inline().get("read").nodes().values().contains(new ComputationGraph.KeyDown("any")));
        assertEquals(changed,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(changed)));
        click(editor,"undo");assertEquals(inserted,workspace.snapshot());click(editor,"undo");assertEquals(before,workspace.snapshot());
    }
}
