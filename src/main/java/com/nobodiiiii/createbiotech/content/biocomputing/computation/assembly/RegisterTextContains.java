package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.TextOperation;
import java.util.Map;

/** Literal case-insensitive search: admission, one comparison per advance, atomic result. */
record RegisterTextContains(int start, int offset, int needle, long compared) implements RegisterConstruction.TextProgress {
    static final RegisterTextContains START = new RegisterTextContains(0, 0, 0, 0);
    RegisterTextContains {
        if (start < 0 || offset < start || needle < 0 || compared < 0 || compared == Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid text search cursor");
    }
    @Override public long paid() { return 1 + compared; }
    boolean found(String pattern) { return needle == pattern.length(); }
    boolean done(String source, String pattern) { return found(pattern) || offset == source.length(); }
    RegisterTextContains advance(String source, String pattern) {
        if (done(source, pattern)) throw new IllegalStateException("Text search already complete");
        int a = source.codePointAt(offset), b = pattern.codePointAt(needle);
        if (same(a, b)) return new RegisterTextContains(start, offset + Character.charCount(a), needle + Character.charCount(b), compared + 1);
        int next = start + Character.charCount(source.codePointAt(start));
        return new RegisterTextContains(next, next, 0, compared + 1);
    }
    /** Validate the retained match, not all previously rejected candidates. Cold work is linear in input size. */
    boolean matches(String source, String pattern) {
        if (!boundary(source, start) || !boundary(source, offset) || !boundary(pattern, needle)) return false;
        int matched = 0;
        int a = start, b = 0;
        while (a < offset && b < needle) {
            int first = source.codePointAt(a), second = pattern.codePointAt(b);
            if (!same(first, second)) return false;
            a += Character.charCount(first); b += Character.charCount(second); matched++;
        }
        if (a != offset || b != needle) return false;
        long skipped = source.codePointCount(0, start);
        long spent = compared - matched;
        return spent >= skipped && spent <= skipped * pattern.codePointCount(0, pattern.length());
    }
    private static boolean boundary(String value, int offset) {
        return offset <= value.length() && (offset == 0 || offset == value.length()
            || !Character.isSurrogatePair(value.charAt(offset - 1), value.charAt(offset)));
    }
    private static boolean same(int first, int second) {
        return TextOperation.foldCasePoint(first) == TextOperation.foldCasePoint(second);
    }
    @Override public DataValue.RecordValue value() {
        return new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("text_contains"),
            "version", new DataValue.IntegerValue(1), "start", new DataValue.IntegerValue(start),
            "offset", new DataValue.IntegerValue(offset), "needle", new DataValue.IntegerValue(needle),
            "compared", new DataValue.IntegerValue(compared)));
    }
}
