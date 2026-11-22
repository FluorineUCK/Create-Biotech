package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowInlineScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.*;
class ScratchClipboardScreenTest {
    private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }
    @Test void cuttingAnInlineValueRestoresItsShadowAndPastesTheOriginalExpression() throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var screen = FlowCallScreenTest.editor(workspace, 640);
        click(screen, "category_variables"); click(screen, "add_assign");
        setLiteral(screen, slot(screen, "0", 0), "7");
        dropReporter(screen, "inline_add_integer", slot(screen, "7", 0));
        var original = workspace.snapshot();
        var originalBody = original.flow().orElseThrow();
        var inline = originalBody.inline().get("block_1");
        var root = inline.outputs().get("value");
        var value = ((FlowBlockCanvas)get(screen, "canvas")).inlineSlots().stream()
            .filter(s -> !s.target() && s.node().equals(root)).findFirst().orElseThrow();
        assertTrue(screen.mouseClicked(value.x() + 5, value.y() + 7, 1));
        click(screen, "clipboard_copy");
        assertEquals(original, workspace.snapshot(), "Copy does not edit the draft");

        assertTrue(screen.mouseClicked(value.x() + 5, value.y() + 7, 1));
        click(screen, "clipboard_cut");
        var cut = workspace.snapshot();
        assertEquals(new DataValue.IntegerValue(7), run(cut).field("block_1_value"));
        assertTrue(cut.flow().orElseThrow().canvas().values().parked().isEmpty());

        assertTrue(screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_V, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));
        var pasted = workspace.snapshot();
        var reporter = pasted.flow().orElseThrow().canvas().values().parked().getFirst();
        assertEquals(inline.nodes().get(root), reporter.graph().nodes().get(reporter.graph().outputs().get("value")));
        assertEquals(new DataValue.IntegerValue(7), run(pasted).field("block_1_value"), "Pasted values cannot run until snapped");
        assertEquals(pasted, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(pasted)));
        click(screen, "undo"); assertEquals(cut, workspace.snapshot());
        click(screen, "undo"); assertEquals(original, workspace.snapshot());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void nestedStatementStackCopiesCutsAndPastesAsAnInertDraft(int width) throws Exception {
        var number=DataType.Scalar.INTEGER;
        var graph=new ComputationGraph(Map.of("argument",number),Map.of("in",new ComputationGraph.Input("argument"),
            "one",new ComputationGraph.Constant(new DataValue.IntegerValue(1)),"value",new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER,Map.of("left","in","right","one"))),Map.of("value","value"));
        var steps=List.<FlowProgram.Step>of(new FlowProgram.Repeat("loop",new FlowInput.Literal(new DataValue.IntegerValue(2))),
            new FlowProgram.Evaluate("set","calculation",new FlowCallBindings(Map.of("argument","n"),Map.of("value","out"))),
            new FlowProgram.EndRepeat("end"),new FlowProgram.Sleep("after",new FlowInput.Literal(new DataValue.IntegerValue(0))));
        var body=new FlowDocumentBody(Map.of("n",number),steps,new DataValue.RecordValue(Map.of("n",new DataValue.IntegerValue(3))),
            FlowBlockLayout.DEFAULT,Map.of("set",graph),FlowProgram.Execution.REGISTER_V1);
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(body));var editor=FlowCallScreenTest.editor(workspace,width);set(editor,"height",480);editor.init();
        var canvas=(FlowBlockCanvas)get(editor,"canvas");var block=canvas.blocks().getFirst();
        double x=(int)get(canvas,"x")+block.x()+4,y=(int)get(canvas,"y")+block.y()+4;
        var original=workspace.snapshot();assertTrue(editor.mouseClicked(x,y,1));click(editor,"clipboard_copy");assertEquals(original,workspace.snapshot());
        editor.mouseMoved((int)get(canvas,"x")+24,(int)get(canvas,"y")+180);
        assertTrue(editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_V,0,org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));
        var pasted=workspace.snapshot();var copy=pasted.flow().orElseThrow().canvas().detached().getFirst().steps();assertEquals(4,copy.size());
        assertEquals(original.flow().orElseThrow().program(original.archive()),pasted.flow().orElseThrow().program(pasted.archive()));
        assertEquals(new DataValue.IntegerValue(4),run(pasted).field("out"));
        assertEquals(Map.of("argument","n"),((FlowProgram.Evaluate)copy.get(1)).bindings().orElseThrow().arguments());
        var ids=new HashSet<String>();steps.forEach(step->assertTrue(ids.add(step.sourceId())));copy.forEach(step->assertTrue(ids.add(step.sourceId())));
        editor.mouseMoved(x,y);assertTrue(editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_X,0,org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));
        var cut=workspace.snapshot();assertTrue(cut.flow().orElseThrow().steps().isEmpty());assertFalse(run(cut).fields().containsKey("out"));
        editor.mouseMoved((int)get(canvas,"x")+60,(int)get(canvas,"y")+90);
        assertTrue(editor.mouseClicked((int)get(canvas,"x")+60,(int)get(canvas,"y")+90,1));click(editor,"clipboard_paste");
        assertEquals(2,workspace.flow().orElseThrow().canvas().detached().size());assertFalse(run(workspace.snapshot()).fields().containsKey("out"));
        assertEquals(workspace.snapshot(),ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot())));
        click(editor,"undo");assertEquals(cut,workspace.snapshot());click(editor,"undo");assertEquals(pasted,workspace.snapshot());click(editor,"undo");assertEquals(original,workspace.snapshot());
        assertNotNull(workspace.scratchClipboard());assertNull(new ProgramWorkspace(workspace.snapshot()).scratchClipboard(),"Clipboard is workspace-local, not part of a saved or installed program");
    }

}
