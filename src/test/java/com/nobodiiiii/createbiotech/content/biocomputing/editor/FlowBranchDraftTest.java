package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowBranchDraftTest {
    @Test void selectingTheEndAddsTheNextSiblingOutsideTheCondition() {
        var draft = new FlowDraft(FlowDocumentBody.empty());
        draft.insertBranch(null, "ok"); var end = draft.snapshot().steps().getLast().sourceId();
        draft.insertAfter(end, new FlowProgram.AwaitReadable("after", "x2"));
        assertEquals("after", draft.snapshot().steps().getLast().sourceId());
        String sibling = draft.insertBranch(end, "ok");
        var structure = FlowBranches.compile(draft.snapshot().steps());
        int at = java.util.stream.IntStream.range(0, draft.snapshot().steps().size())
            .filter(i -> draft.snapshot().steps().get(i).sourceId().equals(sibling)).findFirst().orElseThrow();
        assertEquals(0, structure.depth(at), "A selected end means after the group, not inside its else arm");
    }
    @Test void conditionGroupIsInsertedMovedDeletedAndUndoneAsOneEdit() {
        var draft = new FlowDraft(new FlowDocumentBody(Map.of("ok", DataType.Scalar.BOOLEAN), List.of(),
            new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(false)))));
        String group = draft.insertBranch(null, "ok");
        assertEquals(3, draft.snapshot().steps().size());
        draft.insertAfter(group, new FlowProgram.AwaitReadable("yes", "x0"));
        var otherwise = draft.snapshot().steps().stream().filter(FlowProgram.Otherwise.class::isInstance).findFirst().orElseThrow();
        draft.insertAfter(otherwise.sourceId(), new FlowProgram.AwaitReadable("no", "x1"));
        var branched = draft.snapshot(); draft.undo(); assertEquals(4, draft.snapshot().steps().size()); draft.redo(); assertEquals(branched, draft.snapshot());
        String nested = draft.insertBranch(group, "ok");
        assertEquals(1, FlowBranches.compile(draft.snapshot().steps()).depth(1));
        draft.remove(nested); assertEquals(branched, draft.snapshot());
        draft.append(new FlowProgram.AwaitReadable("tail", "x2"));
        var before = draft.snapshot(); draft.move(group, before.steps().size() - 1);
        assertEquals("tail", draft.snapshot().steps().getFirst().sourceId());
        FlowBranches.compile(draft.snapshot().steps());
        draft.undo(); assertEquals(before, draft.snapshot());
        draft.remove(group); assertEquals(List.of(new FlowProgram.AwaitReadable("tail", "x2")), draft.snapshot().steps());
        draft.undo(); assertEquals(before, draft.snapshot());
        assertThrows(IllegalArgumentException.class, () -> draft.remove(otherwise.sourceId()));
        assertThrows(IllegalArgumentException.class, () -> draft.move(otherwise.sourceId(), 0));
        assertEquals(before, draft.snapshot());
    }
}
