package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowForeverDraftTest {
    @Test void removingOrMovingAHeaderAlwaysIncludesItsNestedGroup() {
        var steps = List.<FlowProgram.Step>of(new FlowProgram.Forever("loop"), new FlowProgram.If("if", "ok"),
            new FlowProgram.EndIf("endif"), new FlowProgram.EndForever("end"), new FlowProgram.AwaitReadable("tail", "x0"));
        var draft = new FlowDraft(new FlowDocumentBody(Map.of("ok", DataType.Scalar.BOOLEAN), steps,
            new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(false)))));
        draft.remove("loop"); assertEquals(List.of(steps.getLast()), draft.snapshot().steps());
        draft.undo(); assertEquals(steps, draft.snapshot().steps()); draft.move("loop", 4);
        assertEquals("tail", draft.snapshot().steps().getFirst().sourceId()); FlowBranches.compile(draft.snapshot().steps());
        var moved = draft.snapshot(); assertThrows(IllegalArgumentException.class, () -> draft.remove("end"));
        assertThrows(IllegalArgumentException.class, () -> draft.move("end", 0)); assertEquals(moved, draft.snapshot());
    }
}
