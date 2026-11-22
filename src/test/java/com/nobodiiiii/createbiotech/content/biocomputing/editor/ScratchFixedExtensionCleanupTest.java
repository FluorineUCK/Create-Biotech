package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class ScratchFixedExtensionCleanupTest {
    private static final String READER = "create_biotech:reader";
    private static final ModuleDefinition.Key KEY = new ModuleDefinition.Key("cb_extension:"+READER+":status",1);
    private static final ProgramDocument.Position PLACE = new ProgramDocument.Position(25,100);
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void start() throws Exception { client.client(); }
    @AfterEach void stop() throws Exception { client.restore(); }

    @Test void removingLastCommandFromCanvasRemovesItsFixedTemplateInTheSameUndoStep() throws Exception {
        var fixture=fixture(false);
        var workspace=fixture.workspace();
        var before=workspace.snapshot();var beforeBody=before.flow().orElseThrow();
        var screen=FlowCallScreenTest.editor(workspace,640);
        remove(screen,fixture.call());
        var after=workspace.snapshot();var body=after.flow().orElseThrow();
        assertTrue(body.steps().isEmpty());
        assertTrue(body.canvas().detached().isEmpty(),"The last call's generated definition should not appear as a pink hat");
        assertEquals(Map.of(READER,1L),body.canvas().extensions(),"Extension stays explicitly enabled");
        assertEquals(FlowDocumentBody.emptySoftwareAssembly().program(after.archive()),body.program(after.archive()));
        assertEquals(after,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(after)));
        assertDoesNotThrow(() -> PreparedProgram.prepare(after,StockApplicationDriverDocuments.hardware()));
        click(screen,"undo");assertEquals(before,workspace.snapshot());
        assertEquals(beforeBody.canvas().detached(),workspace.flow().orElseThrow().canvas().detached());
        click(screen,"redo");assertEquals(after,workspace.snapshot());
    }

    @Test void neverPrunesAnArchivedReference() throws Exception {
        var fixture=fixture(false);
        var ws=fixture.workspace();
        var before=ws.snapshot();
        var actual=before.archive().program();
        var root=new ComputationGraph(Map.of(),Map.of("orphaned",new ComputationGraph.ProcedureValue(KEY,"request",Map.of()), "zero", new ComputationGraph.Constant(new DataValue.IntegerValue(0))),
            Map.of("out","zero"));
        // A disconnected archive node may itself be editable; cleanup is conservative.
        var program=new ProgramDefinition(root,actual.cells(),actual.writes(),actual.sends(),actual.entries());
        var candidate = new ProgramDocument(new ProgramArchive(program,before.archive().modules()),before.positions(),before.terminal(),before.flow());
        ws=new ProgramWorkspace(candidate);
        var normalized=ws.snapshot();
        var screen=FlowCallScreenTest.editor(ws,640);
        remove(screen,fixture.call());
        assertEquals(1, ws.flow().orElseThrow().canvas().detached().size());
        assertEquals(root, ws.snapshot().archive().program().graph());
        click(screen,"undo");assertEquals(normalized,ws.snapshot());
    }

    @Test void anotherParkedCallAndAlteredTemplatesRemainAvailable() throws Exception {
        var parked=fixture(true);
        var ws=parked.workspace();var screen=FlowCallScreenTest.editor(ws,640);
        remove(screen,parked.call());
        assertTrue(ws.flow().orElseThrow().canvas().detached().stream()
            .anyMatch(s->s.steps().getFirst() instanceof FlowProgram.Procedure));
        var altered=fixture(false);
        var draft=new FlowDraft(altered.workspace().flow().orElseThrow());
        var fixed=draft.snapshot().canvas().detached().getFirst();
        var old=(FlowProgram.Procedure)fixed.steps().getFirst();
        var edited=new FlowProgram.Procedure(old.sourceId(),old.key(),old.inputs(),old.outputs(),old.returns(),old.rules(),
            Optional.of(new AssemblySource("RET")));
        draft.replace(old.sourceId(),edited);
        altered.workspace().setFlow(Optional.of(draft.snapshot()));
        screen=FlowCallScreenTest.editor(altered.workspace(),640);
        remove(screen,altered.call());
        assertEquals(edited,altered.workspace().flow().orElseThrow().canvas().detached().getFirst().steps().getFirst(),
            "Do not delete the invalid or modified definition that a player may need to repair");
    }

    @Test void cuttingKeepsTheTemplateForPaste() throws Exception {
        var fixture=fixture(false);
        var ws=fixture.workspace();
        var screen=FlowCallScreenTest.editor(ws,640);
        select(screen,fixture.call());
        assertTrue(screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_X,0,org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));
        assertTrue(ws.flow().orElseThrow().steps().isEmpty());
        assertEquals(1,ws.flow().orElseThrow().canvas().detached().size());
        assertTrue(screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_V,0,org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));
        var stacks=ws.flow().orElseThrow().canvas().detached();
        assertEquals(1,stacks.stream().filter(s->s.steps().getFirst() instanceof FlowProgram.Procedure).count());
        assertTrue(stacks.stream().anyMatch(s->s.steps().getFirst() instanceof FlowProgram.Invoke));
    }

    private record Fixture(ProgramWorkspace workspace,String call) {}
    private static Fixture fixture(boolean park) {
        var draft=new FlowDraft(FlowDocumentBody.emptySoftwareAssembly());
        draft.setExtension(READER,1L);
        var template=ScratchExtensions.binding(KEY).orElseThrow().template("reader_template");
        var call=draft.placeTemplate(template,new FlowDraft.Socket(null,0),PLACE);
        if(park) draft.placeTemplate(template,null,PLACE);
        var workspace=new ProgramWorkspace();
        workspace.setFlow(Optional.of(draft.snapshot()));
        return new Fixture(workspace,call);
    }
    private static void remove(ComputerFlowScreen screen,String id) throws Exception {
        select(screen,id);
        click(screen,"remove");
    }
    private static void select(ComputerFlowScreen screen,String id) throws Exception {
        var canvas=(FlowBlockCanvas)get(screen,"canvas");
        canvas.focus(id);
        var block=canvas.blocks().stream().filter(b->b.step().sourceId().equals(id)).findFirst().orElseThrow();
        var vp=(CanvasViewport)get(canvas,"viewport");
        var x=(int)get(canvas,"x")+vp.screenX(block.x()+8);
        var y=(int)get(canvas,"y")+vp.screenY(block.y()+8);
        assertTrue(screen.mouseClicked(x,y,0));screen.mouseReleased(x,y,0);
        assertEquals(id,canvas.selected());
    }
    private static void click(ComputerFlowScreen screen,String suffix) {
        var btn=screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b->b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().endsWith("."+suffix))
            .findFirst().orElseThrow();
        assertTrue(btn.active,suffix);
        screen.mouseClicked(btn.getX()+6,btn.getY()+6,0);
        screen.mouseReleased(btn.getX()+6,btn.getY()+6,0);
    }
}
