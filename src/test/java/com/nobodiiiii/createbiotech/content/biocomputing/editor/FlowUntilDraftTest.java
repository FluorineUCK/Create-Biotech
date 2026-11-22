package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowUntilDraftTest {
    @Test void importedUntilIsAnAtomicGroupForMoveRemoveAndUndo() {
        var steps = List.<FlowProgram.Step>of(new FlowProgram.Until("until", new FlowInput.Literal(new DataValue.BooleanValue(true))),
            new FlowProgram.Repeat("inner", "n"), new FlowProgram.Sleep("body", "n"), new FlowProgram.EndRepeat("inner_end"),
            new FlowProgram.EndUntil("until_end"), new FlowProgram.Sleep("after", "n"));
        var draft = new FlowDraft(new FlowDocumentBody(Map.of("n", DataType.Scalar.INTEGER), steps,
            new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(2)))));
        var original = draft.snapshot(); draft.move("until", 5); FlowBranches.compile(draft.snapshot().steps());
        assertEquals("after", draft.snapshot().steps().getFirst().sourceId());
        assertThrows(IllegalArgumentException.class, () -> draft.move("until_end", 0));
        assertThrows(IllegalArgumentException.class, () -> draft.remove("until_end"));
        draft.remove("until"); assertEquals(List.of(new FlowProgram.Sleep("after", "n")), draft.snapshot().steps());
        draft.undo(); draft.undo(); assertEquals(original, draft.snapshot());
    }
}
