package com.nobodiiiii.createbiotech.content.biocomputing.library.service;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Strict private schema helpers; not a permissive conversion or object-reflection layer. */
final class LibraryServiceData {
    private LibraryServiceData() {}
    static DataValue.RecordValue obj(Object... pairs) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) {
            DataValue value = switch (pairs[i+1]) {
                case DataValue v -> v;
                case String v -> new DataValue.TextValue(v);
                case Long v -> new DataValue.IntegerValue(v);
                case Integer v -> new DataValue.IntegerValue(v);
                case Boolean v -> new DataValue.BooleanValue(v);
                default -> throw new IllegalArgumentException("Invalid schema scalar");
            };
            if (fields.putIfAbsent((String)pairs[i], value) != null) throw new IllegalArgumentException("Duplicate field");
        }
        return new DataValue.RecordValue(fields);
    }
    static DataValue.RecordValue fields(DataValue value, String... expected) {
        if (!(value instanceof DataValue.RecordValue record) || !record.fields().keySet().equals(Set.of(expected)))
            throw new IllegalArgumentException("Unexpected service fields");
        return record;
    }
    static String text(DataValue value) {
        if (value instanceof DataValue.TextValue text) return text.value();
        throw new IllegalArgumentException("Expected text");
    }
    static long number(DataValue value) {
        if (value instanceof DataValue.IntegerValue number && number.value() >= 0) return number.value();
        throw new IllegalArgumentException("Expected nonnegative integer");
    }
}

