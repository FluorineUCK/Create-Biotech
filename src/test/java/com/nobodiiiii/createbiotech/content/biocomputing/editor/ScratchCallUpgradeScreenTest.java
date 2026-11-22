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

class ScratchCallUpgradeScreenTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void openClient() throws Exception { client.client(); }
    @AfterEach void closeClient() throws Exception { client.restore(); }

    @Test void upgradingANamedCallKeepsExplicitLocalsAndAddsEditableDefaults() throws Exception {
        var key = new ModuleDefinition.Key("scale", 1);
        var next = new ModuleDefinition.Key("scale", 2);
        var integer = DataType.Scalar.INTEGER;
        var outputs = Map.<String, DataType>of("result", integer);
        var returns = Map.of("result", "value");
        var originalDef = new FlowProgram.Procedure("def1", key, Map.of("value", integer), outputs, returns);
        var nextDef = new FlowProgram.Procedure("def2", next, Map.of("value", integer, "extra", integer), outputs, returns);
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.initial(new DataValue.RecordValue(Map.of("source", new DataValue.IntegerValue(7), "value", new DataValue.IntegerValue(33))));
        FlowProcedureTools.define(draft, originalDef, false);
        FlowProcedureTools.define(draft, nextDef, false);
        var call = new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("value", "source"), Map.of("result", "answer")));
        var other = new FlowProgram.Invoke("other", key, new FlowCallBindings(Map.of("value", "source"), Map.of("result", "otherAnswer")));
        draft.append(call);
        draft.append(other);
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        var before = workspace.snapshot();
        var editor = FlowCallScreenTest.editor(workspace, 640);
        var nav = new AtomicReference<Screen>(); set(editor, "navigator", (Consumer<Screen>) nav::set);
        var canvas = (FlowBlockCanvas) get(editor, "canvas"); var p = canvas.blocks().stream().filter(b -> b.step().sourceId().equals("call")).findFirst().orElseThrow();
        assertTrue(editor.mouseClicked((int) get(canvas, "x") + p.x() + 8, (int) get(canvas, "y") + p.y() + 9, 1));
        click(editor, "procedure_upgrade_call");
        var picker = nav.get(); FlowProcedureScreenTest.initializeForm(picker, 426);
        assertEquals(before, workspace.snapshot());
        click(picker, "procedure_upgrade_revision");
        var after = workspace.snapshot(); var body = after.flow().orElseThrow();
        var upgraded = assertInstanceOf(FlowProgram.Invoke.class, body.steps().getFirst());
        assertEquals(next, upgraded.procedure());
        assertEquals(call.bindings().returns(), upgraded.bindings().returns());
        assertEquals(other, body.steps().getLast());
        var graph = body.inline().get("call");
        var oldArgument = assertInstanceOf(ComputationGraph.Input.class, graph.nodes().get(graph.outputs().get("value")));
        assertEquals("source", upgraded.operands().orElseThrow().inputs().get(oldArgument.name()));
        assertEquals(new ComputationGraph.Constant(new DataValue.IntegerValue(0)), graph.nodes().get(graph.outputs().get("extra")));
        assertEquals(new DataValue.IntegerValue(7), FlowInlineScreenTest.run(after).field("answer"));
        var edited = InlineGraphEdits.replace(graph, FlowInlineValues.bindings(upgraded), graph.outputs().get("extra"),
            new InlineGraphEdits.Variable("value", List.of(), integer));
        assertEquals("source", edited.bindings().arguments().get(oldArgument.name()),
            "Dragging a local named like an old port must not change that port's source");
        var newArgument = assertInstanceOf(ComputationGraph.Input.class, edited.graph().nodes().get(graph.outputs().get("extra")));
        assertEquals("value", edited.bindings().arguments().get(newArgument.name()));
        var modified = new FlowDraft(body);
        modified.editInline(upgraded.sourceId(), edited.graph(), edited.bindings());
        var modifiedWorkspace = new ProgramWorkspace(after);
        modifiedWorkspace.setFlow(Optional.of(modified.snapshot()));
        assertEquals(new DataValue.IntegerValue(7), FlowInlineScreenTest.run(modifiedWorkspace.snapshot()).field("answer"));
        assertEquals(after, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(after)));
        initialize(editor, 640); click(editor, "undo"); assertEquals(before, workspace.snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aValueCallCanUpgradeInPlaceWithoutLosingItsArguments(boolean parked) throws Exception {
        var integer = DataType.Scalar.INTEGER;
        var old = new ModuleDefinition.Key("offset", 1);
        var next = new ModuleDefinition.Key("offset", 2);
        var out = Map.<String, DataType>of("result", integer);
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        FlowProcedureTools.define(draft, new FlowProgram.Procedure("old", old, Map.of("n", integer), out, Map.of("result", "n")), false);
        FlowProcedureTools.define(draft, new FlowProgram.Procedure("new", next, Map.of("n", integer, "extra", integer), out, Map.of("result", "n")), false);
        var graph = new ComputationGraph(Map.of(), Map.of("call", new ComputationGraph.ProcedureValue(old, "result", Map.of("n", "arg")),
            "arg", new ComputationGraph.Constant(new DataValue.IntegerValue(7))), Map.of("value", "call"));
        var position = new ProgramDocument.Position(24, 42);
        if (parked) draft.editValues(new ScratchValueLayout(Map.of(), List.of(new ScratchValueLayout.Reporter(position, graph, Map.of(), Map.of()))));
        else {
            var step = new FlowProgram.Evaluate("assignment", "assignment", new FlowCallBindings(Map.of(), Map.of("value", "answer")));
            draft.place(List.of(step), new FlowDraft.Socket(null, 0), position, Map.of("assignment", graph));
            draft.editValues(draft.snapshot().canvas().values().with("assignment", Map.of("call", new DataValue.IntegerValue(9)), List.of()));
        }
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        var before = workspace.snapshot();
        var editor = FlowCallScreenTest.editor(workspace, 640);
        var nav = new java.util.concurrent.atomic.AtomicReference<net.minecraft.client.gui.screens.Screen>();
        set(editor, "navigator", (java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)nav::set);
        var canvas = (FlowBlockCanvas)get(editor, "canvas");
        var field = parked ? canvas.parkedSlotHit((int)get(canvas,"x")+32,(int)get(canvas,"y")+50).slot()
            : canvas.inlineSlots().stream().filter(s -> "call".equals(s.node())).findFirst().orElseThrow();
        assertTrue(editor.mouseClicked(field.x()+6,field.y()+8,1));
        click(editor, "procedure_upgrade_call");
        var picker = nav.get(); FlowProcedureScreenTest.initializeForm(picker, 426);
        assertEquals(before, workspace.snapshot());
        click(picker, "procedure_upgrade_revision");
        var after = workspace.snapshot();
        var body = after.flow().orElseThrow();
        var changed = parked ? body.canvas().values().parked().getFirst().graph() : body.inline().get("assignment");
        var node = assertInstanceOf(ComputationGraph.ProcedureValue.class, changed.nodes().get("call"));
        assertEquals(next, node.procedure());
        assertEquals("result", node.result());
        assertEquals("arg", node.arguments().get("n"));
        assertEquals(graph.nodes().get("arg"), changed.nodes().get("arg"));
        assertEquals(new ComputationGraph.Constant(new DataValue.IntegerValue(0)), changed.nodes().get(node.arguments().get("extra")));
        if (parked) {
            assertEquals(position, body.canvas().values().parked().getFirst().position());
            assertTrue(body.steps().isEmpty());
        } else {
            assertEquals(Map.of("call", new DataValue.IntegerValue(9)), body.canvas().values().forBlock("assignment"));
            assertEquals(new DataValue.IntegerValue(7), FlowInlineScreenTest.run(after).field("answer"));
        }
        assertEquals(after, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(after)));
        initialize(editor, 640); click(editor, "undo"); assertEquals(before, workspace.snapshot());
    }
    @Test void upgradingOneCallRetainsItsExpressionShadowsAndResultBinding() throws Exception {
        var key = new ModuleDefinition.Key("scale",1);
        var next = new ModuleDefinition.Key("scale",2);
        var integer = DataType.Scalar.INTEGER;
        var outputs = Map.<String,DataType>of("result",integer);
        var returns = Map.of("result","value");
        var originalDef = new FlowProgram.Procedure("def1",key,Map.of("value",integer),outputs,returns);
        var nextDef = new FlowProgram.Procedure("def2",next,Map.of("value",integer,"extra",integer),outputs,returns);
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        FlowProcedureTools.define(draft, originalDef, false); FlowProcedureTools.define(draft, nextDef, false);
        var original = new ComputationGraph(Map.of(),Map.of(
            "left", new ComputationGraph.Constant(new DataValue.IntegerValue(2)),
            "right", new ComputationGraph.Constant(new DataValue.IntegerValue(3)),
            "root", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER,"left","right")), Map.of("value","root"));
        var call = new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of(),Map.of("result","answer")),
            Optional.of(new FlowProgram.InvokeOperands("call",Map.of())));
        draft.place(List.of(call), new FlowDraft.Socket(null,0), FlowBlockLayout.DEFAULT.main(),Map.of("call",original));
        draft.editValues(draft.snapshot().canvas().values().with("call",Map.of("root",new DataValue.IntegerValue(7)),List.of()));
        var other = new FlowProgram.Invoke("other",key,new FlowCallBindings(Map.of(),Map.of("result","otherAnswer")),
            Optional.of(new FlowProgram.InvokeOperands("other",Map.of())));
        draft.place(List.of(other),new FlowDraft.Socket(null,1),FlowBlockLayout.DEFAULT.main(),Map.of("other",original));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        var before = workspace.snapshot();
        var editor = FlowCallScreenTest.editor(workspace,640);
        var nav = new AtomicReference<Screen>(); set(editor,"navigator",(Consumer<Screen>)nav::set);
        var canvas = (FlowBlockCanvas)get(editor,"canvas"); var p = canvas.blocks().stream().filter(b -> b.step().sourceId().equals("call")).findFirst().orElseThrow();
        assertTrue(editor.mouseClicked((int)get(canvas,"x")+p.x()+8,(int)get(canvas,"y")+p.y()+9,1));
        click(editor,"procedure_upgrade_call");
        var page = nav.get(); FlowProcedureScreenTest.initializeForm(page,426);
        assertEquals(before,workspace.snapshot(),"Opening the version picker is read-only");
        click(page,"procedure_upgrade_revision");
        var after = workspace.snapshot(); var body = after.flow().orElseThrow();
        var upgraded = assertInstanceOf(FlowProgram.Invoke.class,body.steps().getFirst());
        assertEquals(next,upgraded.procedure()); assertEquals(call.bindings(),upgraded.bindings());
        assertEquals(other,body.steps().getLast()); assertEquals(List.of(originalDef,nextDef),FlowProcedureTools.definitions(body));
        var graph = body.inline().get("call");
        assertEquals("root",graph.outputs().get("value"));
        original.nodes().forEach((id,node) -> assertEquals(node,graph.nodes().get(id)));
        assertEquals(new ComputationGraph.Constant(new DataValue.IntegerValue(0)),graph.nodes().get(graph.outputs().get("extra")));
        assertEquals(Map.of("root",new DataValue.IntegerValue(7)),body.canvas().values().forBlock("call"));
        assertEquals(new DataValue.IntegerValue(5),FlowInlineScreenTest.run(after).field("answer"));
        assertEquals(after,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(after)));
        initialize(editor,640); click(editor,"undo"); assertEquals(before,workspace.snapshot());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
    void incompatibleRevisionIsDisabledAndCancelDoesNotEdit(int width) throws Exception {
        var old=new ModuleDefinition.Key("identity",1);var next=new ModuleDefinition.Key("identity",2);
        var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());
        FlowProcedureTools.define(draft,new FlowProgram.Procedure("old",old,Map.of("n",DataType.Scalar.INTEGER),
            Map.of("result",DataType.Scalar.INTEGER),Map.of("result","n")),false);
        FlowProcedureTools.define(draft,new FlowProgram.Procedure("next",next,Map.of("n",DataType.Scalar.TEXT),
            Map.of("result",DataType.Scalar.TEXT),Map.of("result","n")),false);
        draft.append(new FlowProgram.Invoke("call",old,new FlowCallBindings(Map.of("n","seed"),Map.of("result","answer"))));
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(draft.snapshot()));
        var before=workspace.snapshot();var editor=FlowCallScreenTest.editor(workspace,width);
        var nav=new AtomicReference<Screen>();set(editor,"navigator",(Consumer<Screen>)nav::set);
        var canvas=(FlowBlockCanvas)get(editor,"canvas");var block=canvas.blocks().getFirst();
        assertTrue(editor.mouseClicked((int)get(canvas,"x")+block.x()+8,(int)get(canvas,"y")+block.y()+9,1));
        click(editor,"procedure_upgrade_call");var picker=nav.get();FlowProcedureScreenTest.initializeForm(picker,width);
        assertFalse(button(picker,"procedure_upgrade_revision").active);
        click(picker,"cancel");assertSame(editor,nav.get());
        assertEquals(before,workspace.snapshot());assertFalse(button(editor,"undo").active);
    }

    @Test void unknownDefaultsAndNonIncreasingVersionsAreNotUpgrades() {
        var old=new FlowProgram.Procedure("old",new ModuleDefinition.Key("id",1),Map.of(),Map.of(),Map.of());
        var any=new FlowProgram.Procedure("next",new ModuleDefinition.Key("id",2),Map.of("arg",DataType.Scalar.ANY),Map.of(),Map.of());
        assertTrue(ScratchCallUpgrade.addedDefaults(old,any).isEmpty());
        assertTrue(ScratchCallUpgrade.addedDefaults(old,old).isEmpty());
        assertTrue(ScratchCallUpgrade.addedDefaults(any,old).isEmpty());
    }
}
