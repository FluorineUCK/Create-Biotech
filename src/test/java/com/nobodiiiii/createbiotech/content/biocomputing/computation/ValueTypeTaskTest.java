package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValueTypeTaskTest {
    private long check(DataType type, DataValue value) {
        var schema = new ValueTypeTask.Schema(type);
        var task = ValueTypeTask.start(schema, value, units -> assertTrue(units > 0));
        long work = 1;
        while (true) {
            assertEquals(task.checkpointStorageUnits(), task.checkpoint().storageUnits());
            assertEquals(task.liveUnits(), ValueTypeTask.checkpointUnits(task.checkpoint()));
            assertTrue(work <= schema.workBound(value));
            if (task.complete()) break;
            assertTrue(work++ < 10000); task = task.advance(units -> assertTrue(units > 0));
        }
        assertEquals(type.accepts(value), task.matches(), type + " / " + value);
        return work;
    }
    @Test void allExistingKindsAndCompositeMismatchesMatchThePublicTypeContract() {
        var types = new ArrayList<DataType>(List.of(DataType.Scalar.values()));
        types.addAll(List.of(new DataType.ListType(DataType.Scalar.INTEGER), new DataType.MapType(DataType.Scalar.TEXT),
            new DataType.RecordType(Map.of("entry", DataType.Scalar.INTEGER)), new DataType.RecordType(Map.of()),
            new DataType.OptionalType(DataType.Scalar.INTEGER), new DataType.OptionalType(new DataType.OptionalType(DataType.Scalar.TEXT))));
        var values = List.<DataValue>of(new DataValue.IntegerValue(0), new DataValue.DecimalValue(0), new DataValue.TextValue("0"),
            new DataValue.BooleanValue(false), DataValue.NullValue.INSTANCE, new DataValue.ErrorValue("E", "kept as data"),
            new DataValue.NbtValue(3, new DataValue.IntegerValue(0)), new DataValue.NbtValue(1, new DataValue.IntegerValue(0)),
            new DataValue.ListValue(List.of()), new DataValue.ListValue(List.of(new DataValue.IntegerValue(1), new DataValue.TextValue("2"))),
            new DataValue.ListValue(List.of(new DataValue.IntegerValue(1), new DataValue.IntegerValue(2))),
            new DataValue.MapValue(Map.of("entry", new DataValue.TextValue("text"))),
            new DataValue.RecordValue(Map.of()), new DataValue.RecordValue(Map.of("entry", new DataValue.IntegerValue(1))),
            new DataValue.RecordValue(Map.of("extra", new DataValue.IntegerValue(1))), new DataValue.RecordValue(Map.of("entry", new DataValue.TextValue("1"))));
        for (var type : types) for (var value : values) check(type, value);
    }
    @Test void costTracksElementsAndRecordKeyCharactersButDoesNotScanTextOrNbtPayloadForATagCheck() {
        assertEquals(check(DataType.Scalar.TEXT, new DataValue.TextValue("a")), check(DataType.Scalar.TEXT, new DataValue.TextValue("a".repeat(4000))));
        assertEquals(check(DataType.Scalar.NBT, new DataValue.NbtValue(8, new DataValue.TextValue("a"))),
            check(DataType.Scalar.NBT, new DataValue.NbtValue(8, new DataValue.TextValue("a".repeat(4000)))));
        var list = new DataType.ListType(DataType.Scalar.INTEGER);
        assertEquals(18, check(list, new DataValue.ListValue(Collections.nCopies(10, new DataValue.IntegerValue(1))))
            - check(list, new DataValue.ListValue(List.of(new DataValue.IntegerValue(1)))));
        String longName = "a".repeat(60);
        assertEquals(59, check(new DataType.RecordType(Map.of(longName, DataType.Scalar.INTEGER)), new DataValue.RecordValue(Map.of(longName, new DataValue.IntegerValue(1))))
            - check(new DataType.RecordType(Map.of("a", DataType.Scalar.INTEGER)), new DataValue.RecordValue(Map.of("a", new DataValue.IntegerValue(1)))));
    }
    @Test void deepFailuresUnwindAndDoNotOverflowTheJavaStack() {
        DataType type = DataType.Scalar.INTEGER;
        DataValue value = new DataValue.TextValue("not an integer");
        for (int i = 0; i < 120; i++) { type = new DataType.ListType(type); value = new DataValue.ListValue(List.of(value)); }
        assertTrue(check(type, value) > 360);
    }
    @Test void equivalentTypeDeclarationsHaveTheSamePaidCursorRegardlessOfMapInsertionOrder() {
        var fields = new LinkedHashMap<String, DataType>();
        fields.put("long_field", DataType.Scalar.INTEGER); fields.put("a", DataType.Scalar.TEXT);
        var reversed = new LinkedHashMap<String, DataType>();
        reversed.put("a", DataType.Scalar.TEXT); reversed.put("long_field", DataType.Scalar.INTEGER);
        var value = new DataValue.RecordValue(Map.of("long_field", new DataValue.IntegerValue(1), "a", new DataValue.TextValue("text")));
        var left = ValueTypeTask.start(new ValueTypeTask.Schema(new DataType.RecordType(fields)), value, ignored -> {});
        var right = ValueTypeTask.start(new ValueTypeTask.Schema(new DataType.RecordType(reversed)), value, ignored -> {});
        while (!left.complete()) {
            assertEquals(left.checkpoint(), right.checkpoint(), "Same typed image must have the same continuation");
            left = left.advance(ignored -> {}); right = right.advance(ignored -> {});
        }
        assertEquals(left.checkpoint(), right.checkpoint());
    }
}
