package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class ScratchClipboardDraftTest {
    @Test void copyingNamedListKeepsItsEditableOperationAndExplicitTarget() {
        var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());draft.declareList("items",DataType.Scalar.INTEGER);
        var block=FlowNamedLists.prototype(ListOperation.INSERT,"insert","items",new DataType.ListType(DataType.Scalar.INTEGER),Set.of("items"));
        draft.place(List.of(block.step()),new FlowDraft.Socket(null,0),new ProgramDocument.Position(0,0),Map.of("insert",block.graph()));
        var before=draft.snapshot();var copy=draft.pasteStatements(draft.copyStatements("insert"),new ProgramDocument.Position(10,120));
        var step=draft.find(copy).orElseThrow();
        var target=FlowNamedLists.target(step,draft.snapshot().inline().get(copy));
        assertTrue(target.isPresent(),"Copying must not strip the named-list editor identity");
        assertEquals("items",target.orElseThrow().local());assertEquals(ListOperation.INSERT,target.orElseThrow().operation());
        assertNotEquals("insert",copy);assertEquals(before.steps(),draft.snapshot().steps());
        draft.undo();assertEquals(before,draft.snapshot());
    }
    @Test void copyingPublicListPreservesCommitResultAndShadowWithoutCopyingDefinitions() {
        var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());
        var type=new DataType.ListType(DataType.Scalar.INTEGER);
        var block=FlowNamedLists.scopedPrototype(ListOperation.APPEND,"append","items",type,true,Set.of());
        draft.place(List.of(block.step()),new FlowDraft.Socket(null,0),new ProgramDocument.Position(0,0),Map.of("append",block.graph()));
        var original=(FlowProgram.Shared)block.step();
        var copy=draft.pasteStatements(draft.copyStatements("append"),new ProgramDocument.Position(20,160));
        var shared=(FlowProgram.Shared)draft.find(copy).orElseThrow();
        assertEquals(original.cell(),shared.cell());assertEquals(original.listResult(),shared.listResult());assertEquals(original.value(),shared.value());
        assertTrue(FlowNamedLists.target(shared,draft.snapshot().inline().get(copy),type).isPresent());
        assertEquals(1,draft.snapshot().canvas().detached().size());
        assertThrows(java.util.NoSuchElementException.class,()->draft.copyStatements("missing"));
    }
    @Test void cutPreservesNestedShadowsAndUndoRestoresOneWholeEdit() {
        var body=FlowDocumentBody.emptyAssembly();var draft=new FlowDraft(body);
        var graph=new ComputationGraph(Map.of(),Map.of("a",new ComputationGraph.Constant(new DataValue.IntegerValue(7)),
            "b",new ComputationGraph.Constant(new DataValue.IntegerValue(2)),"sum",new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER,Map.of("left","a","right","b"))),Map.of("value","sum"));
        var statement=new FlowProgram.Evaluate("set","alias",new FlowCallBindings(Map.of(),Map.of("value","answer")));
        draft.place(List.of(statement),new FlowDraft.Socket(null,0),new ProgramDocument.Position(0,0),Map.of("set",graph));
        draft.editValues(new ScratchValueLayout(Map.of("set",Map.of("sum",new DataValue.IntegerValue(7))),List.of()));
        var before=draft.snapshot();var fragment=draft.cutStatements("set");assertTrue(draft.snapshot().steps().isEmpty());assertTrue(draft.snapshot().inline().isEmpty());
        var copy=draft.pasteStatements(fragment,new ProgramDocument.Position(30,140));
        assertEquals(Map.of("sum",new DataValue.IntegerValue(7)),draft.snapshot().canvas().values().forBlock(copy));
        assertEquals(graph,draft.snapshot().inline().get(copy));
        draft.undo();assertTrue(draft.snapshot().steps().isEmpty());draft.undo();assertEquals(before,draft.snapshot());
    }

}
