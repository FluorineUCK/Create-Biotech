package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathNodeBuilderTest {
    @Test void generatesLiveNamedExtractionThroughRecordMapAndListInsteadOfBakingTheSample() {
        var workspace = new ProgramWorkspace(); var draft = workspace.root();
        var sample = input(42); var path = new ValuePath("in", List.of()).key("a.b").index(0).key("");
        var inserted = PathNodeBuilder.insert(draft, sample, path, 20, 20);
        assertEquals("read_value", inserted.output());
        assertEquals(DataType.Scalar.ANY, draft.inputs().get("in"));
        assertTrue(draft.nodes().stream().noneMatch(n -> n.definition() instanceof ComputationGraph.Constant));
        var limits = new ArchiveLimits(100000, 100000, 1000000);
        var restored = ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(), limits), limits);
        var machine = new ComputationMachine(restored.archive().compile(new ResourceLimits(100, 100000, 100)));
        machine.begin(input(7)); machine.tick();
        assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
        assertEquals(new DataValue.IntegerValue(7), machine.report().output().orElseThrow().field("read_value"));
        assertEquals(new DataValue.IntegerValue(42), path.resolve(sample));
        var again = PathNodeBuilder.insert(draft, sample, path, 180, 20);
        assertEquals("read_value_2", again.output());
        assertEquals(inserted.node(), draft.outputs().get("read_value"));
    }
    @Test void invalidSelectionDeclarationOrLayoutCannotPartiallyModifyTheDraft() {
        var draft = new ProgramDraft(); var snapshot = input(42);
        assertThrows(RuntimeException.class, () -> PathNodeBuilder.insert(draft, snapshot,
            new ValuePath("in", List.of()).key("missing"), 0, 0));
        assertTrue(draft.nodes().isEmpty()); assertTrue(draft.inputs().isEmpty());
        assertThrows(RuntimeException.class, () -> PathNodeBuilder.insert(draft, snapshot,
            new ValuePath("in", List.of()), Double.NaN, 0));
        assertTrue(draft.nodes().isEmpty()); assertTrue(draft.inputs().isEmpty());
        draft.putInput("in", DataType.Scalar.INTEGER);
        assertThrows(RuntimeException.class, () -> PathNodeBuilder.insert(draft, snapshot,
            new ValuePath("in", List.of()), 0, 0));
        assertEquals(DataType.Scalar.INTEGER, draft.inputs().get("in"));
        assertTrue(draft.nodes().isEmpty()); assertTrue(draft.outputs().isEmpty());
    }
    private static DataValue.RecordValue input(long number) {
        return new DataValue.RecordValue(Map.of("in", new DataValue.RecordValue(Map.of("a.b",
            new DataValue.ListValue(List.of(new DataValue.MapValue(Map.of("", new DataValue.IntegerValue(number)))))))));
    }
}
