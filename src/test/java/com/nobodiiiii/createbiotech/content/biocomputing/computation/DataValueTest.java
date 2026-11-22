package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DataValueTest {
	@Test
	void deepValueAndTypeEqualityHashingAndSummariesAreStackSafe() {
		DataValue a = new DataValue.IntegerValue(1), b = new DataValue.IntegerValue(1);
		DataType ta = DataType.Scalar.INTEGER, tb = DataType.Scalar.INTEGER;
		for (int depth = 0; depth < 10_000; depth++) {
			a = new DataValue.ListValue(List.of(a));
			b = new DataValue.ListValue(List.of(b));
			ta = new DataType.ListType(ta);
			tb = new DataType.ListType(tb);
		}
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertEquals(ta, tb);
		assertEquals(ta.hashCode(), tb.hashCode());
		assertTrue(a.toString().length() < 100);
		assertTrue(ta.toString().length() < 100);
	}

	@Test
	void deepValuesAndTypesDoNotUseTheJavaCallStack() {
		DataValue value = new DataValue.IntegerValue(1);
		DataType expected = DataType.Scalar.INTEGER;
		for (int depth = 0; depth < 10_000; depth++) {
			value = new DataValue.ListValue(List.of(value));
			expected = new DataType.ListType(expected);
		}
		assertEquals(10_001, value.storageUnits());
		assertTrue(expected.accepts(value));
		assertTrue(expected.compatibleWith(DataType.typeOf(value)));
		assertFalse(DataType.Scalar.TEXT.accepts(value));
	}

	@Test
	void sharedSubvaluesHaveLogicalCopyCostsWithoutExponentialTraversal() {
		DataValue value = new DataValue.IntegerValue(1);
		DataValue other = new DataValue.IntegerValue(1);
		for (int depth = 0; depth < 40; depth++) {
			value = new DataValue.ListValue(List.of(value, value));
			other = new DataValue.ListValue(List.of(other, other));
		}
		DataValue shared = value;
		DataValue copy = other;
		assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
			assertEquals((1L << 41) - 1, shared.storageUnits());
			assertTrue(DataType.typeOf(shared).accepts(shared));
			assertEquals(shared, copy);
			assertEquals(shared.hashCode(), copy.hashCode());
			assertEquals(DataType.typeOf(shared), DataType.typeOf(copy));
			assertEquals(DataType.typeOf(shared).hashCode(), DataType.typeOf(copy).hashCode());
		});
	}

	@Test
	void logicalSizeOverflowIsAnExplicitResourceFailure() {
		assertEquals(ComputationException.Code.MEMORY_LIMIT, assertThrows(ComputationException.class, () -> {
			DataValue value = new DataValue.IntegerValue(1);
			for (int depth = 0; depth < 64; depth++) value = new DataValue.ListValue(List.of(value, value));
		}).code());
	}


	@Test
	void snapshotsOwnCollectionsAndNeverExposeMutableSourceObjects() {
		var source = new ArrayList<DataValue>(List.of(new DataValue.IntegerValue(3)));
		var fields = new LinkedHashMap<String, DataValue>();
		fields.put("samples", new DataValue.ListValue(source));
		var snapshot = new DataValue.RecordValue(fields);
		source.clear();
		fields.clear();
		assertEquals(new DataValue.ListValue(List.of(new DataValue.IntegerValue(3))), snapshot.field("samples"));
		assertThrows(UnsupportedOperationException.class, () -> snapshot.fields().clear());
		assertThrows(UnsupportedOperationException.class,
			() -> ((DataValue.ListValue) snapshot.field("samples")).values().clear());
	}

	@Test
	void recordsAndMapsAreDistinctAndMissingIsNotNullOrZero() {
		var record = new DataValue.RecordValue(Map.of("optional", DataValue.NullValue.INSTANCE));
		var map = new DataValue.MapValue(Map.of("optional", DataValue.NullValue.INSTANCE));
		assertNotEquals(record, map);
		assertEquals(DataValue.NullValue.INSTANCE, record.field("optional"));
		assertEquals(ComputationException.Code.MISSING_FIELD,
			assertThrows(ComputationException.class, () -> record.field("absent")).code());
		assertNotEquals(DataValue.NullValue.INSTANCE,
			new DataValue.ErrorValue("TARGET_UNLOADED", "reader target is unloaded"));
	}

	@Test
	void typesCheckNestedStructureAndNullableValues() {
		var type = new DataType.RecordType(Map.of("samples", new DataType.ListType(DataType.Scalar.INTEGER)));
		assertTrue(type.accepts(new DataValue.RecordValue(Map.of("samples",
			new DataValue.ListValue(List.of(new DataValue.IntegerValue(4)))))));
		assertFalse(type.accepts(new DataValue.RecordValue(Map.of("samples",
			new DataValue.ListValue(List.of(new DataValue.TextValue("4")))))));
		assertTrue(new DataType.OptionalType(DataType.Scalar.INTEGER).accepts(DataValue.NullValue.INSTANCE));
		assertFalse(DataType.Scalar.INTEGER.accepts(DataValue.NullValue.INSTANCE));
	}

	@Test
	void logicalMemoryChargesContentAndKeysNotJustNumberOfCells() {
		assertEquals(1, new DataValue.IntegerValue(42).storageUnits());
		assertEquals(4, new DataValue.TextValue("abc").storageUnits());
		assertEquals(7, new DataValue.RecordValue(Map.of("a", new DataValue.TextValue("abc"))).storageUnits());
		assertEquals(3, new DataValue.ListValue(List.of(new DataValue.IntegerValue(1),
			new DataValue.IntegerValue(2))).storageUnits());
	}

	@Test
	void rejectsNonFiniteNumbersNullContainersAndInvalidHardwareLimits() {
		assertThrows(IllegalArgumentException.class, () -> new DataValue.DecimalValue(Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> new DataValue.DecimalValue(Double.POSITIVE_INFINITY));
		assertThrows(NullPointerException.class, () -> new DataValue.TextValue(null));
		assertThrows(IllegalArgumentException.class, () -> new ResourceLimits(-1, 10, 1));
		assertThrows(IllegalArgumentException.class, () -> new ResourceLimits(10, 10, 0));
	}
}
