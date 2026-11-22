package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.*;

class ScratchProcedureNavigationTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void openClient() throws Exception { client.client(); }
    @AfterEach void closeClient() throws Exception { client.restore(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void rightClickNavigatesBetweenTheCallAndItsExactDefinitionWithoutEditing(int width) throws Exception {
        var first = new ModuleDefinition.Key("check", 1);
        var second = new ModuleDefinition.Key("check", 2);
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        var def = new FlowProgram.Procedure("def1", first, Map.of(), Map.of(), Map.of());
        FlowProcedureTools.define(draft, def, false);
        FlowProcedureTools.define(draft, new FlowProgram.Procedure("def2", second, Map.of(), Map.of(), Map.of()), false);
        draft.append(new FlowProgram.Invoke("call1", first, new FlowCallBindings(Map.of(), Map.of())));
        draft.append(new FlowProgram.Invoke("call2", second, new FlowCallBindings(Map.of(), Map.of())));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        var editor = FlowCallScreenTest.editor(workspace, width);
        var nav = new AtomicReference<Screen>();
        set(editor,"navigator",(Consumer<Screen>)nav::set);
        var original = workspace.snapshot();
        var canvas = (FlowBlockCanvas)get(editor,"canvas");
        var call = canvas.blocks().stream().filter(b -> b.step().sourceId().equals("call1")).findFirst().orElseThrow();
        assertTrue(editor.mouseClicked((int)get(canvas,"x")+call.x()+10,(int)get(canvas,"y")+call.y()+10,1));
        click(editor,"procedure_go_definition");
        assertEquals("def1",canvas.selected());
        rightClick(editor, "def1");
        click(editor,"procedure_find_references");
        var page = nav.get(); assertNotSame(editor,page);
        FlowProcedureScreenTest.initializeForm(page, 426);
        var rows = page.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance)
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast)
            .filter(b->b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().endsWith(".procedure_reference_call")).toList();
        assertEquals(1, rows.size(), "Calls of another revision are excluded");
        rows.getFirst().onClick(0,0);
        assertSame(editor, nav.get());
        assertEquals("call1",canvas.selected());
        FlowProcedureScreenTest.initializeForm(editor, width);
        assertEquals("call1",canvas.selected());
        assertEquals(original,workspace.snapshot());
        assertFalse(button(editor,"undo").active);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 2})
    void missingOrAmbiguousDefinitionDoesNotGuessOrEdit(int definitions) throws Exception {
        var key = new ModuleDefinition.Key("check", 1);
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        // Imported invalid drafts must remain navigable; the definition dialog correctly rejects duplicates.
        for(int i=0;i<definitions;i++) draft.place(List.of(
            new FlowProgram.Procedure("def"+i,key,Map.of(),Map.of(),Map.of()),
            new FlowProgram.EndProcedure("end"+i)),null,new ProgramDocument.Position(280,16+i*80));
        draft.append(new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of(),Map.of())));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        var editor = FlowCallScreenTest.editor(workspace,426);
        var before = workspace.snapshot();
        rightClick(editor,"call"); click(editor,"procedure_go_definition");
        assertTrue(((net.minecraft.network.chat.Component)get(editor,"notice")).getString()
            .contains(definitions==0 ? "procedure_missing" : "procedure_ambiguous"));
        assertEquals(before,workspace.snapshot()); assertFalse(button(editor,"undo").active);
    }

    @Test void parkedValueCallGoesToItsDefinitionWithoutInstallingOrEditing() throws Exception {
        var key = new ModuleDefinition.Key("identity",1);
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        FlowProcedureTools.define(draft,new FlowProgram.Procedure("def",key,Map.of("n",DataType.Scalar.INTEGER),
            Map.of("result",DataType.Scalar.INTEGER),Map.of("result","n")),false);
        var graph = new ComputationGraph(Map.of(),Map.of(
            "n",new ComputationGraph.Constant(new DataValue.IntegerValue(7)),
            "call",new ComputationGraph.ProcedureValue(key,"result",Map.of("n","n"))),Map.of("value","call"));
        draft.editValues(ScratchValueLayout.EMPTY.withParked(List.of(new ScratchValueLayout.Reporter(
            new ProgramDocument.Position(16,40),graph,Map.of(),Map.of()))));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        var editor=FlowCallScreenTest.editor(workspace,640);var before=workspace.snapshot();
        var canvas=(FlowBlockCanvas)get(editor,"canvas");
        assertTrue(editor.mouseClicked((int)get(canvas,"x")+20,(int)get(canvas,"y")+45,1));
        click(editor,"procedure_go_definition");assertEquals("def",canvas.selected());
        assertEquals(before,workspace.snapshot());assertFalse(button(editor,"undo").active);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"zh_cn","en_us"})
    void navigationCaptionsArePackaged(String locale) throws Exception {
        var translations=ComputerUiTranslationsTest.load(locale);
        for(String key:List.of("procedure_go_definition","procedure_find_references","procedure_context_edit","procedure_ambiguous"))
            assertFalse(translations.get("create_biotech.biocomputing.flow_editor."+key).isBlank());
    }

    private static void rightClick(ComputerFlowScreen editor,String source) throws Exception {
        var canvas=(FlowBlockCanvas)get(editor,"canvas");
        var block=canvas.blocks().stream().filter(b->b.step().sourceId().equals(source)).findFirst().orElseThrow();
        var view=(CanvasViewport)get(canvas,"viewport");
        assertTrue(editor.mouseClicked((int)get(canvas,"x")+view.screenX(block.x()+10),
            (int)get(canvas,"y")+view.screenY(block.y()+10),1));
    }
}
