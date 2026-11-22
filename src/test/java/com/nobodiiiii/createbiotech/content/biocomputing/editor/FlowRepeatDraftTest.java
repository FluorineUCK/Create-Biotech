package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowRepeatDraftTest {
    @Test void nestedGroupMovesAndDeletesAtomicallyAndTailCannotBeMovedAlone() {
        var draft = new FlowDraft(new FlowDocumentBody(Map.of("n", DataType.Scalar.INTEGER), List.of(),
            new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(2)))));
        String outer = draft.insertRepeat(null, "n"); String inner = draft.insertForever(outer);
        draft.insertAfter(inner, new FlowProgram.Sleep("pause", "n"));
        draft.append(new FlowProgram.Sleep("after", "n")); var before = draft.snapshot();
        draft.move(outer, before.steps().size() - 1); FlowBranches.compile(draft.snapshot().steps());
        assertEquals("after", draft.snapshot().steps().getFirst().sourceId());
        assertThrows(IllegalArgumentException.class, () -> draft.remove(outer + "_end"));
        assertThrows(IllegalArgumentException.class, () -> draft.move(outer + "_end", 0));
        draft.remove(outer); assertEquals(1, draft.snapshot().steps().size());
        draft.undo(); draft.undo(); assertEquals(before, draft.snapshot());
        draft.replace(outer, new FlowProgram.Repeat(outer, "renamed"));
        assertEquals(before.steps().size(), draft.snapshot().steps().size()); draft.undo(); assertEquals(before, draft.snapshot());
    }
}
