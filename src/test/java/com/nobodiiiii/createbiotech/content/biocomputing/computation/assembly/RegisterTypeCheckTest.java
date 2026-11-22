package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RegisterTypeCheckTest {
    static RegisterProgram image(DataType target) {
        var outcome = new DataType.RecordType(Map.of("ok", DataType.Scalar.BOOLEAN, "value", new DataType.OptionalType(target)));
        return new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.ValueCheck(1, 0), 0, "check_block")),
            List.of(DataType.Scalar.ANY, outcome), List.of(), Map.of("value", 0), Map.of("value", 0, "checked", 1), 1);
    }
    static RegisterState forged(RegisterState state, long paid, DataValue.RecordValue progress) {
        return new RegisterState(state.image(), state.pc(), state.registers(), state.structured(), state.scan(), state.calls(), state.transaction(),
            state.constructionCapable(), state.build(), state.serialCapable(), state.serial(), state.equalityCapable(), Optional.of(new RegisterComparison(paid, progress)));
    }
    @Test void mismatchesRemainResultsAndSuccessRetainsImmutableIdentityIncludingNull() {
        for (var type : DataType.Scalar.values()) {
            DataValue value = type == DataType.Scalar.NULL ? DataValue.NullValue.INSTANCE : new DataValue.TextValue("large".repeat(2000));
            var p = image(type); var s = p.begin(new DataValue.RecordValue(Map.of("value", value))); long cycles = 0;
            while (!p.complete(s)) { s = p.step(s); p.validate(s, ++cycles, false); }
            assertEquals(3, cycles); var result = (DataValue.RecordValue)s.registers().get(1);
            assertEquals(new DataValue.BooleanValue(type.accepts(value)), result.field("ok"));
            assertSame(type.accepts(value) ? value : DataValue.NullValue.INSTANCE, result.field("value"));
        }
    }
    @Test void alteredWorkOrCompletedCursorCannotSkipStructuralChecking() {
        var p = image(new DataType.ListType(DataType.Scalar.INTEGER));
        var s = p.begin(new DataValue.RecordValue(Map.of("value", new DataValue.ListValue(Collections.nCopies(4, new DataValue.IntegerValue(1))))));
        s = p.step(s); var current = s; var progress = s.comparison().orElseThrow().value();
        var snapshot = (DataValue.RecordValue)progress.field("progress");
        var complete = new DataValue.RecordValue(Map.of("typing", new DataValue.BooleanValue(true), "mismatch", new DataValue.BooleanValue(false), "frames", new DataValue.ListValue(List.of())));
        for (var corrupted : List.of(forged(current, 2, snapshot), forged(current, 1, complete), forged(current, Long.MAX_VALUE - 1, snapshot)))
            assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> p.validate(corrupted, corrupted.progressWork(), false)).code());
        var restored = forged(current, 1, snapshot);
        p.validate(restored, 1, false); assertEquals(current.value(), restored.value());
    }
    @Test void cursorAndSnapshotReservationFailureLeavesOriginalRegistersAndPcUntouched() {
        var p = image(new DataType.ListType(DataType.Scalar.INTEGER));
        var old = p.begin(new DataValue.RecordValue(Map.of("value", new DataValue.ListValue(List.of(new DataValue.IntegerValue(1))))));
        var before = old.value();
        assertThrows(ComputationException.class, () -> p.step(old, Map.of(), Map.of(), 1, units -> {
            if (units > 9) throw new ComputationException(ComputationException.Code.MEMORY_LIMIT, "check", "No snapshot headroom");
        }));
        assertEquals(before, old.value()); assertFalse(old.registers().containsKey(1));
        var started = p.step(old); var saved = started.value();
        assertThrows(ComputationException.class, () -> p.step(started, Map.of(), Map.of(), 2, units -> {
            if (units > 9) throw new ComputationException(ComputationException.Code.MEMORY_LIMIT, "check", "No candidate headroom");
        }));
        assertEquals(saved, started.value());
    }
    @Test void destinationShapeAndOldDialectsAreCheckedWithoutWeakeningMov() {
        String source = "TYPE maybe OPTIONAL INTEGER\nTYPE outcome RECORD ok BOOLEAN value maybe\nREG checked outcome\nVALUE.CHECK checked value";
        for (int v : List.of(23, 2, 3)) assertThrows(IllegalArgumentException.class, () -> ProcedureAssembler.assemble(new AssemblySource(v, source),
            Map.of("value", DataType.Scalar.ANY), Map.of(), Map.of(), 10000, 1000));
        assertDoesNotThrow(() -> ProcedureAssembler.assemble(new AssemblySource(source), Map.of("value", DataType.Scalar.ANY), Map.of(), Map.of(), 10000, 1000));
        for (String invalid : List.of("REG checked INTEGER\nVALUE.CHECK checked value", "REG checked INTEGER\nMOV checked value"))
            assertThrows(ComputationException.class, () -> ProcedureAssembler.assemble(new AssemblySource(invalid),
                Map.of("value", DataType.Scalar.ANY), Map.of(), Map.of(), 10000, 1000));
    }
}
