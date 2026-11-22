package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowProgramCodec;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowCanvasDraftTest {
    @Test void draggingAnInnerStackKeepsItsFollowingSiblingsButNotTheEnclosingDelimiter() {
        var body = new FlowDocumentBody(Map.of(), List.of(new FlowProgram.Repeat("repeat", new FlowInput.Literal(new DataValue.IntegerValue(2))),
            new FlowProgram.Correlation("a", "a"), new FlowProgram.Correlation("b", "b"), new FlowProgram.EndRepeat("end"),
            new FlowProgram.Correlation("after", "after")), new DataValue.RecordValue(Map.of()));
        var draft = new FlowDraft(body);
        draft.drop("a", null, new ProgramDocument.Position(280, 100));
        assertEquals(List.of("repeat", "end", "after"), draft.snapshot().steps().stream().map(FlowProgram.Step::sourceId).toList());
        assertEquals(List.of("a", "b"), draft.snapshot().canvas().detached().getFirst().steps().stream().map(FlowProgram.Step::sourceId).toList());
        var detached = draft.snapshot();
        draft.drop("a", new FlowDraft.Socket(null, 1), new ProgramDocument.Position(0, 0));
        assertEquals(body, draft.snapshot());
        draft.undo(); assertEquals(detached, draft.snapshot()); draft.undo(); assertEquals(body, draft.snapshot());
        draft.drop("repeat", new FlowDraft.Socket(null, 2), new ProgramDocument.Position(0, 0));
        assertEquals(body, draft.snapshot(), "Dropping a stack into its own body must not create a cycle or duplicate it");
        assertThrows(IllegalArgumentException.class, () -> draft.drop("end", null, new ProgramDocument.Position(0, 0)));
        assertEquals(body, draft.snapshot());
    }
    @Test void editingInitialValuesAndBlocksMustPreserveParkedStacksAndTheirIdentity() {
        var layout = new FlowBlockLayout(new ProgramDocument.Position(60, 20), List.of(new FlowBlockLayout.Stack(
            new ProgramDocument.Position(300, 60), List.of(new FlowProgram.Correlation("block_1", "parked")))));
        var original = new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()), layout);
        var draft = new FlowDraft(original);
        draft.initial(new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(2))));
        assertEquals(layout, draft.snapshot().canvas());
        assertEquals("block_2", draft.nextId());
        draft.replace("block_1", new FlowProgram.Correlation("block_1", "edited"));
        assertEquals("edited", ((FlowProgram.Correlation)draft.snapshot().canvas().detached().getFirst().steps().getFirst()).local());
        assertTrue(draft.snapshot().steps().isEmpty());
        draft.undo(); draft.undo(); assertEquals(original, draft.snapshot());
    }
    @Test void detachedBlocksSurviveDocumentRoundTripButNeverRunWithTheMainScript() {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(new FlowProgram.Correlation("main", "live")),
            new DataValue.RecordValue(Map.of()))));
        var encoded = ProgramDocumentCodec.encode(workspace.snapshot());
        var fields = new LinkedHashMap<>(encoded.fields());
        fields.put("version", new DataValue.IntegerValue(17));
        var flow = new LinkedHashMap<>(((DataValue.RecordValue)encoded.field("flow")).fields());
        flow.put("canvas", new DataValue.RecordValue(Map.of("main", position(20, 16), "detached", new DataValue.ListValue(List.of(
            new DataValue.RecordValue(Map.of("position", position(280, 90), "control", FlowProgramCodec.control(Map.of(),
                List.of(new FlowProgram.Correlation("parked", "must_not_run"))))))))));
        fields.put("flow", new DataValue.RecordValue(flow));
        var document = ProgramDocumentCodec.decode(new DataValue.RecordValue(fields));
        assertEquals(new DataValue.RecordValue(fields), ProgramDocumentCodec.encode(document));
        assertEquals(document, new ProgramWorkspace(document).snapshot());
        var body = document.flow().orElseThrow();
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), body.program(document.archive()), body.initial(),
            new ResourceLimits(100, 100000, 10));
        runtime.advance(10);
        assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        assertTrue(runtime.checkpoint().locals().fields().containsKey("live"));
        assertFalse(runtime.checkpoint().locals().fields().containsKey("must_not_run"));
    }
    private static DataValue.RecordValue position(double x, double y) {
        return new DataValue.RecordValue(Map.of("x", new DataValue.DecimalValue(x), "y", new DataValue.DecimalValue(y)));
    }
    @Test void invalidSocketsAndDuplicateIdentitiesCannotPartiallyChangeOrHideBlocks() {
        var initial = new DataValue.RecordValue(Map.of());
        var body = new FlowDocumentBody(Map.of(), List.of(new FlowProgram.Correlation("a", "a"),
            new FlowProgram.Correlation("b", "b")), initial);
        var draft = new FlowDraft(body);
        assertThrows(IllegalArgumentException.class, () -> draft.drop("b", new FlowDraft.Socket(null, 7), new ProgramDocument.Position(1, 1)));
        assertEquals(body, draft.snapshot()); assertFalse(draft.canUndo());
        var duplicate = new FlowBlockLayout.Stack(new ProgramDocument.Position(10, 10), List.of(new FlowProgram.Correlation("a", "shadow")));
        assertThrows(IllegalArgumentException.class, () -> new FlowDocumentBody(body.inputs(), body.steps(), initial,
            new FlowBlockLayout(body.canvas().main(), List.of(duplicate))));
        draft.drop("b", null, new ProgramDocument.Position(100, 20));
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(draft.snapshot()));
        var fields = new LinkedHashMap<>(ProgramDocumentCodec.encode(workspace.snapshot()).fields());
        fields.put("version", new DataValue.IntegerValue(16));
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException.class,
            () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(fields)), "An older decoder must not discard parked stacks");
    }
}
