package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class RegisterConstructionTest {
    static RegisterProgram list(int n) {
        return new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.MakeList(1, Collections.nCopies(n, 0)), 0, "build")), List.of(DataType.Scalar.ANY, new DataType.ListType(DataType.Scalar.ANY)), List.of(), Map.of("x", 0), Map.of("x", 0, "out", 1), 1);
    }
    static RegisterState copy(RegisterState s, int pc, Map<Integer, DataValue> values, RegisterConstruction build) {
        return new RegisterState(s.image(), pc, values, true, Optional.empty(), s.calls(), s.transaction(), true, Optional.of(build));
    }
    @Test void emptyOneManyNestedRepeatedOccurrencesHaveExactWorkAndRetainEveryPriorSnapshot() {
        DataValue child = new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("payload", new DataValue.TextValue("large".repeat(100))))));
        for (int n:List.of(0, 1, 3, 128)) {
            var image = list(n);
            var state = image.begin(new DataValue.RecordValue(Map.of("x", child)));
            var history = new ArrayList<RegisterState>();
            history.add(state);
            for (int cycle = 1;cycle <= n + 2;cycle++) {
                var before = state;
                state = image.step(state);
                image.validate(state, cycle, false);
                history.add(state);
                if (cycle <= n + 1) {
                    assertFalse(state.registers().containsKey(1));
                    assertSame(before.registers(), state.registers());
                    assertEquals(1L + (cycle-1) * child.storageUnits(), state.constructionUnits());
                }
            }
            assertTrue(image.complete(state));
            var value = (DataValue.ListValue)state.registers().get(1);
            assertEquals(Collections.nCopies(n, child), value.values());
            assertEquals(1L + n * child.storageUnits(), value.storageUnits());
            for (int i = 1;i <= n + 1;i++) {
                assertEquals(1L + (i-1) * child.storageUnits(), history.get(i).constructionUnits());
                assertFalse(history.get(i).registers().containsKey(1));
            }
            assertThrows(UnsupportedOperationException.class, ()->value.values().add(child));
            assertThrows(UnsupportedOperationException.class, ()->value.values().subList(0, value.values().size()).clear());
        }
    }
    @Test void prefixWitnessIsBoundToFrozenSourceAndPcAndRejectsUnpaidCursor() {
        var image = list(3);
        var s = image.step(image.begin(new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(7)))));
        var progress = s.build().orElseThrow();
        image.validate(s, 1, false);
        var same = new RegisterState(s.image(), s.pc(), s.registers(), true, Optional.empty(), s.calls(), s.transaction(), true, s.build());
        image.validate(same, 1, false);
        assertSame(s.registers(), same.registers());
        assertThrows(ComputationException.class, ()->image.validate(copy(s, 0, new TreeMap<>(s.registers()), progress), 1, false));
        assertThrows(ComputationException.class, ()->image.validate(copy(s, 1, s.registers(), progress), 1, false));
        assertThrows(ComputationException.class, ()->image.validate(copy(s, 0, s.registers(), new RegisterConstruction(4)), 5, false));
        assertThrows(ComputationException.class, ()->image.validate(copy(s, 0, s.registers(), new RegisterConstruction(2)), 2, false));
        assertEquals(1, s.constructionUnits());
        var done = s;
        for (int i = 2;i <= 5;i++)done = image.step(done);
        var end = done;
        assertThrows(ComputationException.class, ()->image.validate(end, 4, false));
    }
    @Test void emptyRecordAdmitsThenPublishesAndStaticTypesCannotNarrowAnyInput() {
        var image = new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.MakeRecord(0, List.of()), 0, "empty")), List.of(new DataType.RecordType(Map.of())), List.of(), Map.of(), Map.of("out", 0), 1);
        var start = image.begin(new DataValue.RecordValue(Map.of()));var pending = image.step(start);image.validate(pending, 1, false);assertFalse(pending.registers().containsKey(0));assertEquals(1, pending.constructionUnits());
        var done = image.step(pending);image.validate(done, 2, false);assertEquals(new DataValue.RecordValue(Map.of()), done.registers().get(0));
        assertThrows(IllegalArgumentException.class, () -> new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.MakeList(1, List.of(0)), 0, "narrow")), List.of(DataType.Scalar.ANY, new DataType.ListType(DataType.Scalar.INTEGER)), List.of(), Map.of("x", 0), Map.of("x", 0, "out", 1), 1));
    }
    @Test void recordOrderUtf16EmptyKeysTypesAndImmutableEntriesArePreserved() {
        var f = List.of(new RegisterProgram.NamedOperand("z", 0), new RegisterProgram.NamedOperand("", 0), new RegisterProgram.NamedOperand("\ud800", 0));
        var type = new DataType.RecordType(new LinkedHashMap<>(Map.of("z", DataType.Scalar.INTEGER, "", DataType.Scalar.INTEGER, "\ud800", DataType.Scalar.INTEGER)));
        var p = new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.MakeRecord(1, f), 0, "record")), List.of(DataType.Scalar.INTEGER, type), List.of(), Map.of("x", 0), Map.of("out", 1, "x", 0), 1);
        var s = p.begin(new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(7))));
        for (int i = 1;i <= 5;i++) {
            s = p.step(s);
            p.validate(s, i, false);
        }
        var r = (DataValue.RecordValue)s.registers().get(1);
        assertEquals(List.of("z", "", "\ud800"), new ArrayList<>(r.fields().keySet()));
        assertEquals(9, r.storageUnits());
        assertEquals(new DataValue.RecordValue(new LinkedHashMap<>(r.fields())), r);
        assertThrows(UnsupportedOperationException.class, ()->r.indexedFields().getFirst().setValue(new DataValue.IntegerValue(8)));
        assertThrows(UnsupportedOperationException.class, ()->r.fields().values().clear());
        assertThrows(IllegalArgumentException.class, ()->new RegisterProgram.MakeRecord(1, List.of(new RegisterProgram.NamedOperand("same", 0), new RegisterProgram.NamedOperand("same", 0))));
    }
    @Test void optionalRegisterProofWeightsWriterAndReverseReturnPath() {
        for (boolean weightedWriter:List.of(true, false)) {
            List<RegisterProgram.Code> code = weightedWriter?List.of(
            new RegisterProgram.Code(new RegisterProgram.JumpFalse(0, 3), 0, "branch"), new RegisterProgram.Code(new RegisterProgram.MakeList(3, List.of(1, 1, 1)), 0, "build"), new RegisterProgram.Code(new RegisterProgram.Jump(4), 0, "join"), new RegisterProgram.Code(new RegisterProgram.Jump(4), 0, "skip"), new RegisterProgram.Code(new RegisterProgram.Jump(0), 0, "loop")):
            List.of(new RegisterProgram.Code(new RegisterProgram.JumpFalse(0, 4), 0, "branch"), new RegisterProgram.Code(new RegisterProgram.Constant(2, 0), 0, "writer"), new RegisterProgram.Code(new RegisterProgram.MakeList(3, List.of(1, 1, 1)), 0, "build"), new RegisterProgram.Code(new RegisterProgram.Jump(0), 0, "loop"), new RegisterProgram.Code(new RegisterProgram.Jump(0), 0, "skip"));
            var p = new RegisterProgram(code, List.of(DataType.Scalar.BOOLEAN, DataType.Scalar.INTEGER, DataType.Scalar.INTEGER, new DataType.ListType(DataType.Scalar.INTEGER)), List.of(new DataValue.IntegerValue(1)), Map.of("condition", 0, "x", 1), Map.of("condition", 0, "x", 1, "writer", 2, "list", 3), 1);
            var cold = p.begin(new DataValue.RecordValue(Map.of("condition", new DataValue.BooleanValue(true), "x", new DataValue.IntegerValue(7))));
            var registers = new TreeMap<>(cold.registers());
            registers.put(weightedWriter?3:2, weightedWriter?new DataValue.ListValue(Collections.nCopies(3, new DataValue.IntegerValue(7))):new DataValue.IntegerValue(1));
            var warm = new RegisterState(cold.image(), 0, registers, true, Optional.empty(), Optional.empty(), Optional.empty(), true, Optional.empty());
            assertThrows(ComputationException.class, ()->p.validate(warm, 7, false));
            assertDoesNotThrow(()->p.validate(warm, 8, false));
        }
    }
    @Test void sourceDestinationAliasReadsOldFrozenValueUntilFinalPublication() {
        var p = new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.MakeList(0, List.of(0, 0)), 0, "alias")), List.of(new DataType.ListType(DataType.Scalar.ANY)), List.of(), Map.of("x", 0), Map.of("x", 0), 1);
        var old = new DataValue.ListValue(List.of(new DataValue.IntegerValue(7)));
        var s = p.begin(new DataValue.RecordValue(Map.of("x", old)));
        for (int i = 1;i <= 3;i++) {
            s = p.step(s);
            p.validate(s, i, false);
            assertSame(old, s.registers().get(0));
        }
        s = p.step(s);
        p.validate(s, 4, false);
        assertEquals(new DataValue.ListValue(List.of(old, old)), s.registers().get(0));
        assertEquals(1, old.values().size());
    }
}
