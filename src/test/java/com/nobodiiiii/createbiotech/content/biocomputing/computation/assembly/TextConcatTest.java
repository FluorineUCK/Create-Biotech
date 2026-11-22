package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TextConcatTest {
    @Test void workIsPerCodePointAndAliasedOutputIsPublishedOnlyAfterReservation() {
        for (String first : List.of("", "a", "😀文", "x".repeat(128))) {
            String second = "🧬z";
            var image = new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.TextConcat(0, 0, 1), 0, "concat")),
                List.of(DataType.Scalar.TEXT, DataType.Scalar.TEXT), List.of(), Map.of("left", 0, "right", 1), Map.of("answer", 0, "right", 1), 1);
            var left = new DataValue.TextValue(first); var right = new DataValue.TextValue(second);
            var initial = image.begin(new DataValue.RecordValue(Map.of("left", left, "right", right)));
            var state = initial;
            int points = first.codePointCount(0, first.length()) + second.codePointCount(0, second.length());
            for (int paid = 1; paid <= points + 1; paid++) {
                state = image.step(state);
                image.validate(state, paid, false);
                assertEquals(left, state.registers().get(0), "An alias cannot overwrite its source during scanning");
                assertEquals(right, state.registers().get(1));
                assertEquals(paid, state.build().orElseThrow().paidWork());
                assertEquals(0, state.build().orElseThrow().ownedUnits(), "No duplicate text prefix is retained");
            }
            var ready = state;
            var failed = assertThrows(ComputationException.class, () -> image.step(ready, Map.of(), Map.of(), points + 2, units -> {
                assertEquals(1L + first.length() + second.length(), units);
                throw new ComputationException(ComputationException.Code.MEMORY_LIMIT, "concat", "Full RAM");
            }));
            assertEquals(ComputationException.Code.MEMORY_LIMIT, failed.code());
            assertEquals(left, ready.registers().get(0));
            var done = image.step(ready); image.validate(done, points + 2, false);
            assertTrue(image.complete(done)); assertTrue(done.build().isEmpty());
            assertEquals(new DataValue.TextValue(first + second), done.registers().get(0));
            assertEquals(left, initial.registers().get(0));
        }
    }

    @Test void authoredConcatRejectsACheckpointSplittingAUnicodePair() {
        var text = DataType.Scalar.TEXT;
        var code = new AssemblySource("REG result TEXT\nTEXT.CONCAT result first second\nRET");
        var image = ProcedureAssembler.assemble(code, Map.of("first", text, "second", text), Map.of("result", text),
            Map.of("result", "result"), 4096, 64).program().forTask("local", 1);
        var op = assertInstanceOf(RegisterProgram.TextConcat.class, image.code().getFirst().instruction());
        assertEquals(4, op.words());
        assertTrue(RegisterProgram.variableWork(op));
        assertEquals(InstructionFamily.ISA_1A, op.family());
        var start = image.begin(new DataValue.RecordValue(Map.of(
            "first", new DataValue.TextValue("😀"), "second", new DataValue.TextValue(""))));
        var forged = new RegisterState(start.image(), start.pc(), start.registers(), true, Optional.empty(), start.calls(),
            start.transaction(), true, Optional.of(RegisterConstruction.savedTextConcat(1, 0, 1)));
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,
            assertThrows(ComputationException.class, () -> image.validate(forged, 2, false)).code());
    }
}
