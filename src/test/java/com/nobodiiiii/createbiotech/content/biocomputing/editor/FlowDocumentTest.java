package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowDocumentTest {
    @Test void blockEditsKeepSourceIdentityAndUndoOnlyTheDraft() {
        var initial = FlowDocumentBody.empty(); var draft = new FlowDraft(initial);
        var read = new FlowProgram.Read("read-id", "x0", "packet", new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER)));
        draft.append(read); draft.append(new FlowProgram.Write("write-id", "x1", "packet"));
        var ordered = draft.snapshot(); draft.move("write-id", 0);
        assertEquals("write-id", draft.snapshot().steps().getFirst().sourceId());
        draft.undo(); assertEquals(ordered, draft.snapshot()); draft.redo();
        assertEquals(read, draft.snapshot().steps().get(1));
        var before = draft.snapshot();
        assertThrows(IllegalArgumentException.class, () -> draft.replace("read-id", new FlowProgram.AwaitReadable("different-id", "x0")));
        assertEquals(before, draft.snapshot());
        draft.remove("write-id"); assertEquals(List.of(read), draft.snapshot().steps());
        assertTrue(initial.steps().isEmpty());
    }
    static final ArchiveLimits LIMITS = new ArchiveLimits(10000, 100000, 1000000);
    @Test void flowDocumentRoundTripKeepsInitialValuesControlAndPureCanvasWithoutExecuting() {
        var workspace = new ProgramWorkspace(); var pure = workspace.createEntry("calculate");
        String node = pure.add(new ComputationGraph.Constant(new DataValue.IntegerValue(12)), 33, 44); pure.output("value", node);
        var body = new FlowDocumentBody(Map.of(), List.of(new FlowProgram.Evaluate("calc", "calculate")), new DataValue.RecordValue(Map.of()));
        var expression = workspace.snapshot();
        var document = new ProgramDocument(expression.archive(), expression.positions(), Optional.empty(), Optional.of(body));
        var restored = ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, LIMITS), LIMITS);
        assertEquals(document, restored);
        assertEquals(document, new ProgramWorkspace(restored).snapshot());
        restored.flow().orElseThrow().program(restored.archive()).validate(new ResourceLimits(64, 32768, 4));
        assertEquals(new ProgramDocument.Position(33, 44), restored.positions().get(ProgramDocument.entryGraphId("calculate")).get(node));
    }
}
