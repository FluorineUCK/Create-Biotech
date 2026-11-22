package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlowPathExpressionTest {
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(100000, 100000, 1000000);
    private static final ResourceLimits LIMITS = new ResourceLimits(100, 100000, 1);
    private static final ValuePath PATH = new ValuePath("sample", List.of()).key("a.b").index(0).key("");

    @Test void generatedExpressionUsesLiveLocalsAfterDocumentAndEveryRuntimeBoundaryRestore() {
        for (boolean missing : List.of(false, true)) {
            var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
            var sample = input(42);
            var insertion = workspace.createPathExpression(sample, PATH, 20, 20);
            var original = missing ? new DataValue.RecordValue(Map.of("sample", new DataValue.RecordValue(Map.of()))) : input(7);
            var locals = Map.of("sample", (DataType)DataType.Scalar.ANY);
            workspace.setFlow(Optional.of(new FlowDocumentBody(locals,
                List.of(new FlowProgram.Evaluate("read", insertion.entry())), original)));
            var document = workspace.snapshot();
            var restored = ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, ARCHIVE), ARCHIVE);
            assertEquals(document, restored); restored.validate(LIMITS);
            var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), restored.flow().orElseThrow().program(restored.archive()), original, LIMITS);
            for (int i = 0; runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE && runtime.checkpoint().status() != FlowRuntime.Status.FAILED; i++) {
                assertTrue(i < 40); runtime.advance(1);
                runtime.setPaused(true); var paused = runtime.checkpoint();
                assertEquals(0, runtime.advance(1)); assertEquals(paused, runtime.checkpoint());
                var saved = new FlowNbtCodec.Snapshot(restored.flow().orElseThrow().program(restored.archive()), paused);
                var decoded = FlowNbtCodec.decode(FlowNbtCodec.encode(saved, ARCHIVE), ARCHIVE);
                assertEquals(saved, decoded);
                runtime = FlowRuntime.restore(decoded.program(), decoded.state(), LIMITS);
                assertEquals(0, runtime.advance(1)); runtime.load(); runtime.setPaused(false);
            }
            if (missing) {
                assertEquals(ComputationException.Code.MISSING_FIELD, runtime.checkpoint().diagnostic().orElseThrow().code());
                assertEquals(original, runtime.checkpoint().locals());
            } else {
                assertEquals(new DataValue.IntegerValue(7), runtime.checkpoint().locals().field(insertion.output()));
                assertEquals(original.field("sample"), runtime.checkpoint().locals().field("sample"));
            }
            assertEquals(new DataValue.IntegerValue(42), PATH.resolve(sample));
            var limited = assertThrows(ComputationException.class, () -> restored.validate(new ResourceLimits(1, 100000, 1)));
            assertEquals(ComputationException.Code.PROGRAM_LIMIT, limited.code());
        }
    }

    @Test void repeatedGenerationAndFailureLeaveExistingDraftsAndFlowBodyUntouched() {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var incomplete = workspace.createEntry("unfinished"); incomplete.add(null, 3, 5);
        var module = workspace.createModule("helper"); workspace.graph(module).add(null, 4, 6);
        var first = workspace.createPathExpression(input(42), PATH, 20, 20);
        var nodes = workspace.entry(first.entry()).nodes();
        var second = workspace.createPathExpression(input(99), PATH, 180, 20);
        assertEquals("read_path", first.entry()); assertEquals("read_path_2", second.entry());
        assertEquals(nodes, workspace.entry(first.entry()).nodes());
        assertEquals(1, incomplete.nodes().size()); assertNull(incomplete.nodes().getFirst().definition());
        assertEquals(1, workspace.graph(module).nodes().size());
        var names = workspace.entryNames(); var body = workspace.flow();
        for (var path : List.of(new ValuePath("absent", List.of()), PATH.key("absent")))
            assertThrows(RuntimeException.class, () -> workspace.createPathExpression(input(42), path, 20, 20));
        assertThrows(IllegalArgumentException.class, () -> workspace.createPathExpression(input(42), PATH, Double.NaN, 20));
        assertEquals(names, workspace.entryNames()); assertEquals(body, workspace.flow());
        assertTrue(workspace.root().nodes().isEmpty());
        assertTrue(workspace.entry(first.entry()).writes().isEmpty()); assertTrue(workspace.entry(first.entry()).sends().isEmpty());
    }

    private static DataValue.RecordValue input(long n) {
        return new DataValue.RecordValue(Map.of("sample", new DataValue.RecordValue(Map.of("a.b",
            new DataValue.ListValue(List.of(new DataValue.MapValue(Map.of("", new DataValue.IntegerValue(n)))))))));
    }
}
