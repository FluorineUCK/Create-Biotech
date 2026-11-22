package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;

/**
 * TEXT.CONCAT v1: admission, one step per source code point, then one atomic publication.
 * Immutable operand registers already own the text. The cursor retains no duplicate prefix.
 */
record RegisterTextConcat(int left, int right, long points) implements RegisterConstruction.TextProgress {
    static final RegisterTextConcat START = new RegisterTextConcat(0, 0, 0);
    RegisterTextConcat {
        long units = (long)left + right;
        if (left < 0 || right < 0 || points < (units + 1) / 2 || points > units)
            throw new IllegalArgumentException("Invalid text concatenation cursor");
    }
    @Override public long paid() { return 1 + points; }
    boolean done(String first, String second) { return left == first.length() && right == second.length(); }
    RegisterTextConcat advance(String first, String second) {
        if (left < first.length()) return new RegisterTextConcat(left + Character.charCount(first.codePointAt(left)), right, points + 1);
        if (right < second.length()) return new RegisterTextConcat(left, right + Character.charCount(second.codePointAt(right)), points + 1);
        throw new IllegalStateException("Text concatenation already scanned");
    }
    boolean matches(String first, String second) {
        return left <= first.length() && right <= second.length() && (right == 0 || left == first.length())
            && boundary(first, left) && boundary(second, right)
            && points == (long)first.codePointCount(0, left) + second.codePointCount(0, right);
    }
    private static boolean boundary(String source, int index) {
        return index == 0 || index == source.length()
            || !Character.isSurrogatePair(source.charAt(index - 1), source.charAt(index));
    }
    static String operand(RegisterState state, int register, String location) {
        if (state.registers().get(register) instanceof DataValue.TextValue text) return text.value();
        throw new ComputationException(ComputationException.Code.TYPE_MISMATCH, location, "TEXT.CONCAT requires two text operands");
    }
    @Override public DataValue.RecordValue value() {
        return new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("text_concat"),
            "version", new DataValue.IntegerValue(1), "left", new DataValue.IntegerValue(left),
            "right", new DataValue.IntegerValue(right), "points", new DataValue.IntegerValue(points)));
    }
}
