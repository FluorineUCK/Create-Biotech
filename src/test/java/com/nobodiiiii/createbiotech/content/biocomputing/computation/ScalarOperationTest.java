package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ScalarOperationTest {
	private static final String LOCATION = "node.scalar";

	@Test
	void catalogHasExactlyTheSpecifiedOperationsAndCategories() {
		assertArrayEquals(new String[] {
			"ADD_INTEGER", "SUBTRACT_INTEGER", "MULTIPLY_INTEGER", "DIVIDE_INTEGER", "REMAINDER_INTEGER",
			"MIN_INTEGER", "MAX_INTEGER", "LESS_INTEGER", "LESS_EQUAL_INTEGER", "GREATER_INTEGER",
			"GREATER_EQUAL_INTEGER", "EQUAL_INTEGER",
			"ADD_DECIMAL", "SUBTRACT_DECIMAL", "MULTIPLY_DECIMAL", "DIVIDE_DECIMAL", "MIN_DECIMAL",
			"MAX_DECIMAL", "LESS_DECIMAL", "LESS_EQUAL_DECIMAL", "GREATER_DECIMAL", "GREATER_EQUAL_DECIMAL",
			"EQUAL_DECIMAL", "AND_BOOLEAN", "OR_BOOLEAN", "XOR_BOOLEAN", "NOT_BOOLEAN", "NEGATE_INTEGER",
			"ABS_INTEGER", "NEGATE_DECIMAL", "ABS_DECIMAL", "INTEGER_TO_DECIMAL", "DECIMAL_TO_INTEGER",
			"IS_NULL", "SELECT", "SQRT_DECIMAL", "POWER_DECIMAL", "REMAINDER_DECIMAL", "ROUND_DECIMAL_TO_INTEGER", "CEIL_DECIMAL_TO_INTEGER", "FLOOR_DECIMAL_TO_INTEGER", "IS_ERROR", "ERROR_CODE", "ERROR_MESSAGE", "LIST_LENGTH", "REQUIRE_VALUE",
			"EDGE_RISING_INTEGER", "EDGE_FALLING_INTEGER", "EDGE_RISING_DECIMAL", "EDGE_FALLING_DECIMAL", "SAMPLE_RISING_INTEGER", "SAMPLE_FALLING_INTEGER", "SAMPLE_RISING_DECIMAL", "SAMPLE_FALLING_DECIMAL",
			"MOD_INTEGER", "MOD_DECIMAL", "FLOOR_DECIMAL", "CEIL_DECIMAL", "SIN_DEGREES", "COS_DEGREES", "TAN_DEGREES", "ASIN_DEGREES", "ACOS_DEGREES", "ATAN_DEGREES", "LOG_NATURAL", "LOG_BASE_TEN", "EXP_NATURAL", "EXP_BASE_TEN"
		}, java.util.Arrays.stream(ScalarOperation.values()).map(Enum::name).toArray(String[]::new));
		for (ScalarOperation operation : ScalarOperation.values()) {
			ScalarOperation.Category expected = switch (operation) {
				case ADD_INTEGER, SUBTRACT_INTEGER, MULTIPLY_INTEGER, DIVIDE_INTEGER, REMAINDER_INTEGER, MOD_INTEGER,
					MIN_INTEGER, MAX_INTEGER, LESS_INTEGER, LESS_EQUAL_INTEGER, GREATER_INTEGER,
					GREATER_EQUAL_INTEGER, EQUAL_INTEGER, NEGATE_INTEGER, ABS_INTEGER -> ScalarOperation.Category.INTEGER;
				case ADD_DECIMAL, SUBTRACT_DECIMAL, MULTIPLY_DECIMAL, DIVIDE_DECIMAL, MIN_DECIMAL,
					MAX_DECIMAL, LESS_DECIMAL, LESS_EQUAL_DECIMAL, GREATER_DECIMAL, GREATER_EQUAL_DECIMAL,
					EQUAL_DECIMAL, NEGATE_DECIMAL, ABS_DECIMAL, SQRT_DECIMAL, POWER_DECIMAL, REMAINDER_DECIMAL, MOD_DECIMAL,
					FLOOR_DECIMAL, CEIL_DECIMAL, SIN_DEGREES, COS_DEGREES, TAN_DEGREES, ASIN_DEGREES, ACOS_DEGREES, ATAN_DEGREES, LOG_NATURAL, LOG_BASE_TEN, EXP_NATURAL, EXP_BASE_TEN -> ScalarOperation.Category.DECIMAL;
				case AND_BOOLEAN, OR_BOOLEAN, XOR_BOOLEAN, NOT_BOOLEAN, EDGE_RISING_INTEGER, EDGE_FALLING_INTEGER, EDGE_RISING_DECIMAL, EDGE_FALLING_DECIMAL, SAMPLE_RISING_INTEGER, SAMPLE_FALLING_INTEGER, SAMPLE_RISING_DECIMAL, SAMPLE_FALLING_DECIMAL -> ScalarOperation.Category.LOGIC;
				case INTEGER_TO_DECIMAL, DECIMAL_TO_INTEGER, ROUND_DECIMAL_TO_INTEGER, CEIL_DECIMAL_TO_INTEGER, FLOOR_DECIMAL_TO_INTEGER -> ScalarOperation.Category.CONVERSION;
				case REQUIRE_VALUE, LIST_LENGTH, IS_NULL, IS_ERROR, ERROR_CODE, ERROR_MESSAGE, SELECT -> ScalarOperation.Category.VALUE;
			};
			assertEquals(expected, operation.category(), operation.name());
		}
	}

	@Test
	void signaturesAreOrderedImmutableAndOwnAllStaticValidation() {
		Map<String, DataType> integerBinary = orderedTypes("left", DataType.Scalar.INTEGER, "right", DataType.Scalar.INTEGER);
		Map<String, DataType> decimalBinary = orderedTypes("left", DataType.Scalar.DECIMAL, "right", DataType.Scalar.DECIMAL);
		Map<String, DataType> booleanBinary = orderedTypes("left", DataType.Scalar.BOOLEAN, "right", DataType.Scalar.BOOLEAN);
		for (ScalarOperation operation : ScalarOperation.values()) {
			Map<String, DataType> expected = switch (operation) {
				case SAMPLE_RISING_INTEGER, SAMPLE_FALLING_INTEGER -> orderedTypes("current", DataType.Scalar.INTEGER, "threshold", DataType.Scalar.INTEGER);
                case EDGE_RISING_INTEGER, EDGE_FALLING_INTEGER -> orderedTypes("previous", DataType.Scalar.INTEGER, "current", DataType.Scalar.INTEGER, "threshold", DataType.Scalar.INTEGER);
				case SAMPLE_RISING_DECIMAL, SAMPLE_FALLING_DECIMAL -> orderedTypes("current", DataType.Scalar.DECIMAL, "threshold", DataType.Scalar.DECIMAL);
                case EDGE_RISING_DECIMAL, EDGE_FALLING_DECIMAL -> orderedTypes("previous", DataType.Scalar.DECIMAL, "current", DataType.Scalar.DECIMAL, "threshold", DataType.Scalar.DECIMAL);
				case ADD_INTEGER, SUBTRACT_INTEGER, MULTIPLY_INTEGER, DIVIDE_INTEGER, REMAINDER_INTEGER, MOD_INTEGER,
					MIN_INTEGER, MAX_INTEGER, LESS_INTEGER, LESS_EQUAL_INTEGER, GREATER_INTEGER,
					GREATER_EQUAL_INTEGER, EQUAL_INTEGER -> integerBinary;
				case ADD_DECIMAL, SUBTRACT_DECIMAL, MULTIPLY_DECIMAL, DIVIDE_DECIMAL, MIN_DECIMAL,
					MAX_DECIMAL, LESS_DECIMAL, LESS_EQUAL_DECIMAL, GREATER_DECIMAL, GREATER_EQUAL_DECIMAL,
					EQUAL_DECIMAL, POWER_DECIMAL, REMAINDER_DECIMAL, MOD_DECIMAL -> decimalBinary;
				case AND_BOOLEAN, OR_BOOLEAN, XOR_BOOLEAN -> booleanBinary;
				case NOT_BOOLEAN -> Map.of("value", DataType.Scalar.BOOLEAN);
				case NEGATE_INTEGER, ABS_INTEGER, INTEGER_TO_DECIMAL -> Map.of("value", DataType.Scalar.INTEGER);
				case NEGATE_DECIMAL, ABS_DECIMAL, DECIMAL_TO_INTEGER, SQRT_DECIMAL, ROUND_DECIMAL_TO_INTEGER, CEIL_DECIMAL_TO_INTEGER, FLOOR_DECIMAL_TO_INTEGER,
					FLOOR_DECIMAL, CEIL_DECIMAL, SIN_DEGREES, COS_DEGREES, TAN_DEGREES, ASIN_DEGREES, ACOS_DEGREES, ATAN_DEGREES, LOG_NATURAL, LOG_BASE_TEN, EXP_NATURAL, EXP_BASE_TEN -> Map.of("value", DataType.Scalar.DECIMAL);
				case LIST_LENGTH -> Map.of("value", new DataType.ListType(DataType.Scalar.ANY));
				case REQUIRE_VALUE, IS_NULL, IS_ERROR -> Map.of("value", DataType.Scalar.ANY);
				case ERROR_CODE, ERROR_MESSAGE -> Map.of("value", DataType.Scalar.ERROR);
				case SELECT -> orderedTypes("condition", DataType.Scalar.BOOLEAN, "when_true", DataType.Scalar.ANY,
					"when_false", DataType.Scalar.ANY);
			};
			assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(operation.inputs().keySet()), operation.name());
			assertEquals(expected, operation.inputs(), operation.name());
			assertThrows(UnsupportedOperationException.class, () -> operation.inputs().clear(), operation.name());
		}

		assertEquals(DataType.Scalar.INTEGER, ScalarOperation.ADD_INTEGER.outputType(integerBinary, LOCATION));
		assertEquals(DataType.Scalar.BOOLEAN, ScalarOperation.LESS_INTEGER.outputType(integerBinary, LOCATION));
		assertEquals(DataType.Scalar.DECIMAL, ScalarOperation.ADD_DECIMAL.outputType(decimalBinary, LOCATION));
		assertEquals(DataType.Scalar.BOOLEAN, ScalarOperation.EQUAL_DECIMAL.outputType(decimalBinary, LOCATION));
		assertEquals(DataType.Scalar.BOOLEAN, ScalarOperation.AND_BOOLEAN.outputType(booleanBinary, LOCATION));
		assertEquals(DataType.Scalar.DECIMAL, ScalarOperation.INTEGER_TO_DECIMAL.outputType(Map.of("value", DataType.Scalar.INTEGER), LOCATION));
		assertEquals(DataType.Scalar.INTEGER, ScalarOperation.DECIMAL_TO_INTEGER.outputType(Map.of("value", DataType.Scalar.DECIMAL), LOCATION));
		assertEquals(DataType.Scalar.BOOLEAN, ScalarOperation.IS_NULL.outputType(Map.of("value", DataType.Scalar.ERROR), LOCATION));
		assertEquals(DataType.Scalar.INTEGER,
			ScalarOperation.ADD_INTEGER.outputType(orderedTypes("left", DataType.Scalar.ANY, "right", DataType.Scalar.INTEGER), LOCATION));
	}

	@Test
	void everyIntegerBinaryOperationHasItsLiteralSemantics() {
		Map<ScalarOperation, DataValue> expected = new LinkedHashMap<>();
		expected.put(ScalarOperation.ADD_INTEGER, integer(10));
		expected.put(ScalarOperation.SUBTRACT_INTEGER, integer(4));
		expected.put(ScalarOperation.MULTIPLY_INTEGER, integer(21));
		expected.put(ScalarOperation.DIVIDE_INTEGER, integer(2));
		expected.put(ScalarOperation.REMAINDER_INTEGER, integer(1));
		expected.put(ScalarOperation.MIN_INTEGER, integer(3));
		expected.put(ScalarOperation.MAX_INTEGER, integer(7));
		expected.put(ScalarOperation.LESS_INTEGER, bool(false));
		expected.put(ScalarOperation.LESS_EQUAL_INTEGER, bool(false));
		expected.put(ScalarOperation.GREATER_INTEGER, bool(true));
		expected.put(ScalarOperation.GREATER_EQUAL_INTEGER, bool(true));
		expected.put(ScalarOperation.EQUAL_INTEGER, bool(false));
		expected.forEach((operation, result) -> assertEquals(result,
			operation.evaluate(orderedValues("left", integer(7), "right", integer(3)), LOCATION), operation.name()));

		assertEquals(integer(-2), ScalarOperation.DIVIDE_INTEGER.evaluate(intArgs(-7, 3), LOCATION));
		assertEquals(integer(-1), ScalarOperation.REMAINDER_INTEGER.evaluate(intArgs(-7, 3), LOCATION));
		assertFailure(ComputationException.Code.ARITHMETIC_OVERFLOW, ScalarOperation.DIVIDE_INTEGER,
			intArgs(Long.MIN_VALUE, -1));
		assertEquals(integer(0), ScalarOperation.REMAINDER_INTEGER.evaluate(intArgs(Long.MIN_VALUE, -1), LOCATION));
	}

	@Test
	void everyDecimalBinaryOperationHasItsLiteralSemantics() {
		Map<ScalarOperation, DataValue> expected = new LinkedHashMap<>();
		expected.put(ScalarOperation.ADD_DECIMAL, decimal(4.0));
		expected.put(ScalarOperation.SUBTRACT_DECIMAL, decimal(3.0));
		expected.put(ScalarOperation.MULTIPLY_DECIMAL, decimal(1.75));
		expected.put(ScalarOperation.DIVIDE_DECIMAL, decimal(7.0));
		expected.put(ScalarOperation.MIN_DECIMAL, decimal(0.5));
		expected.put(ScalarOperation.MAX_DECIMAL, decimal(3.5));
		expected.put(ScalarOperation.LESS_DECIMAL, bool(false));
		expected.put(ScalarOperation.LESS_EQUAL_DECIMAL, bool(false));
		expected.put(ScalarOperation.GREATER_DECIMAL, bool(true));
		expected.put(ScalarOperation.GREATER_EQUAL_DECIMAL, bool(true));
		expected.put(ScalarOperation.EQUAL_DECIMAL, bool(false));
		expected.forEach((operation, result) -> assertEquals(result,
			operation.evaluate(decimalArgs(3.5, 0.5), LOCATION), operation.name()));
		assertEquals(bool(true), ScalarOperation.EQUAL_DECIMAL.evaluate(decimalArgs(-0.0, 0.0), LOCATION));
		assertEquals(bool(false), ScalarOperation.LESS_DECIMAL.evaluate(decimalArgs(-0.0, 0.0), LOCATION));
	}

	@Test
	void logicUnaryAndConversionsHaveLiteralSemanticsAndExplicitCasts() {
		assertEquals(bool(false), ScalarOperation.AND_BOOLEAN.evaluate(boolArgs(true, false), LOCATION));
		assertEquals(bool(true), ScalarOperation.OR_BOOLEAN.evaluate(boolArgs(true, false), LOCATION));
		assertEquals(bool(true), ScalarOperation.XOR_BOOLEAN.evaluate(boolArgs(true, false), LOCATION));
		assertEquals(bool(false), ScalarOperation.NOT_BOOLEAN.evaluate(Map.of("value", bool(true)), LOCATION));
		assertEquals(integer(-9), ScalarOperation.NEGATE_INTEGER.evaluate(Map.of("value", integer(9)), LOCATION));
		assertEquals(integer(9), ScalarOperation.ABS_INTEGER.evaluate(Map.of("value", integer(-9)), LOCATION));
		assertEquals(decimal(-2.5), ScalarOperation.NEGATE_DECIMAL.evaluate(Map.of("value", decimal(2.5)), LOCATION));
		assertEquals(decimal(2.5), ScalarOperation.ABS_DECIMAL.evaluate(Map.of("value", decimal(-2.5)), LOCATION));
		assertEquals(decimal((double) Long.MAX_VALUE),
			ScalarOperation.INTEGER_TO_DECIMAL.evaluate(Map.of("value", integer(Long.MAX_VALUE)), LOCATION));
		assertEquals(integer(-3), ScalarOperation.DECIMAL_TO_INTEGER.evaluate(Map.of("value", decimal(-3.9)), LOCATION));
		assertEquals(integer(3), ScalarOperation.DECIMAL_TO_INTEGER.evaluate(Map.of("value", decimal(3.9)), LOCATION));
		assertEquals(integer(Long.MIN_VALUE),
			ScalarOperation.DECIMAL_TO_INTEGER.evaluate(Map.of("value", decimal(-0x1.0p63)), LOCATION));
	}

	@Test
	void arithmeticFailuresUseDedicatedCodesAndPreserveLocation() {
		for (ScalarOperation operation : List.of(ScalarOperation.DIVIDE_INTEGER, ScalarOperation.REMAINDER_INTEGER))
			assertFailure(ComputationException.Code.DIVISION_BY_ZERO, operation, intArgs(1, 0));
		for (double zero : new double[] { 0.0, -0.0 })
			assertFailure(ComputationException.Code.DIVISION_BY_ZERO, ScalarOperation.DIVIDE_DECIMAL, decimalArgs(1.0, zero));

		assertFailure(ComputationException.Code.ARITHMETIC_OVERFLOW, ScalarOperation.ADD_INTEGER, intArgs(Long.MAX_VALUE, 1));
		assertFailure(ComputationException.Code.ARITHMETIC_OVERFLOW, ScalarOperation.SUBTRACT_INTEGER, intArgs(Long.MIN_VALUE, 1));
		assertFailure(ComputationException.Code.ARITHMETIC_OVERFLOW, ScalarOperation.MULTIPLY_INTEGER, intArgs(Long.MAX_VALUE, 2));
		assertFailure(ComputationException.Code.ARITHMETIC_OVERFLOW, ScalarOperation.NEGATE_INTEGER, Map.of("value", integer(Long.MIN_VALUE)));
		assertFailure(ComputationException.Code.ARITHMETIC_OVERFLOW, ScalarOperation.ABS_INTEGER, Map.of("value", integer(Long.MIN_VALUE)));
		assertFailure(ComputationException.Code.ARITHMETIC_OVERFLOW, ScalarOperation.MULTIPLY_DECIMAL,
			decimalArgs(Double.MAX_VALUE, 2.0));
		assertFailure(ComputationException.Code.ARITHMETIC_OVERFLOW, ScalarOperation.ADD_DECIMAL,
			decimalArgs(Double.MAX_VALUE, Double.MAX_VALUE));
		assertFailure(ComputationException.Code.ARITHMETIC_OVERFLOW, ScalarOperation.DECIMAL_TO_INTEGER,
			Map.of("value", decimal(0x1.0p63)));
		assertFailure(ComputationException.Code.ARITHMETIC_OVERFLOW, ScalarOperation.DECIMAL_TO_INTEGER,
			Map.of("value", decimal(-0x1.0000000000001p63)));
	}

	@Test
	void wrongArgumentNamesAndTypesAreReportedAtTheSuppliedLocation() {
		assertFailure(ComputationException.Code.INVALID_GRAPH, ScalarOperation.ADD_INTEGER,
			Map.of("left", integer(1), "other", integer(2)));
		assertFailure(ComputationException.Code.INVALID_GRAPH, ScalarOperation.ADD_INTEGER,
			Map.of("left", integer(1)));
		assertFailure(ComputationException.Code.TYPE_MISMATCH, ScalarOperation.ADD_INTEGER,
			orderedValues("left", decimal(1), "right", integer(2)));

		assertTypeFailure(ComputationException.Code.INVALID_GRAPH, ScalarOperation.ADD_INTEGER,
			Map.of("left", DataType.Scalar.INTEGER));
		assertTypeFailure(ComputationException.Code.INVALID_GRAPH, ScalarOperation.ADD_INTEGER,
			orderedTypes("left", DataType.Scalar.INTEGER, "oops", DataType.Scalar.INTEGER));
		assertTypeFailure(ComputationException.Code.TYPE_MISMATCH, ScalarOperation.ADD_INTEGER,
			orderedTypes("left", DataType.Scalar.DECIMAL, "right", DataType.Scalar.INTEGER));
	}

	@Test
	void nullEntriesWithCorrectArgumentNamesAreDomainTypeFailures() {
		var runtime = new LinkedHashMap<String, DataValue>();
		runtime.put("left", integer(1));
		runtime.put("right", null);
		assertFailure(ComputationException.Code.TYPE_MISMATCH, ScalarOperation.ADD_INTEGER, runtime);

		var types = new LinkedHashMap<String, DataType>();
		types.put("left", DataType.Scalar.INTEGER);
		types.put("right", null);
		assertTypeFailure(ComputationException.Code.TYPE_MISMATCH, ScalarOperation.ADD_INTEGER, types);
	}

	@Test
	void selectJoinsTypesStructurallyAndAcceptsDynamicInputs() {
		DataType record = new DataType.RecordType(orderedTypes("name", DataType.Scalar.TEXT, "count", DataType.Scalar.INTEGER));
		DataType list = new DataType.ListType(DataType.Scalar.INTEGER);
		assertSelectType(DataType.Scalar.INTEGER, DataType.Scalar.INTEGER, DataType.Scalar.INTEGER);
		assertSelectType(DataType.Scalar.ANY, DataType.Scalar.ANY, DataType.Scalar.INTEGER);
		assertSelectType(DataType.Scalar.ANY, DataType.Scalar.INTEGER, DataType.Scalar.ANY);
		assertSelectType(DataType.Scalar.NULL, DataType.Scalar.NULL, DataType.Scalar.NULL);
		assertSelectType(new DataType.OptionalType(DataType.Scalar.INTEGER), DataType.Scalar.NULL, DataType.Scalar.INTEGER);
		assertSelectType(new DataType.OptionalType(DataType.Scalar.INTEGER), DataType.Scalar.INTEGER, DataType.Scalar.NULL);
		assertSelectType(new DataType.OptionalType(DataType.Scalar.INTEGER),
			new DataType.OptionalType(DataType.Scalar.INTEGER), DataType.Scalar.NULL);
		assertSelectType(new DataType.OptionalType(DataType.Scalar.INTEGER),
			new DataType.OptionalType(DataType.Scalar.INTEGER), DataType.Scalar.INTEGER);
		assertSelectType(DataType.Scalar.ANY, record, list);
		assertSelectType(record, record, record);
	}

	@Test
	void selectReturnsTheIdenticalImmutableChosenValueAcrossAllValueKinds() {
		var source = new ArrayList<DataValue>(List.of(integer(4)));
		DataValue list = new DataValue.ListValue(source);
		DataValue record = new DataValue.RecordValue(Map.of("items", list));
		DataValue error = new DataValue.ErrorValue("E", "ordinary data");
		for (DataValue chosen : List.of(record, list, DataValue.NullValue.INSTANCE, error)) {
			DataValue result = ScalarOperation.SELECT.evaluate(
				orderedValues("condition", bool(true), "when_true", chosen, "when_false", integer(0)), LOCATION);
			assertSame(chosen, result);
		}
		source.clear();
		assertEquals(new DataValue.ListValue(List.of(integer(4))), list);
		assertThrows(UnsupportedOperationException.class, () -> ((DataValue.ListValue) list).values().clear());
		DataValue falseChoice = new DataValue.TextValue("false branch");
		assertSame(falseChoice, ScalarOperation.SELECT.evaluate(
			orderedValues("condition", bool(false), "when_true", error, "when_false", falseChoice), LOCATION));
	}

	@Test
	void isNullTestsOnlyTheNullSingleton() {
		assertEquals(bool(true), ScalarOperation.IS_NULL.evaluate(Map.of("value", DataValue.NullValue.INSTANCE), LOCATION));
		for (DataValue value : List.of(integer(0), new DataValue.TextValue(""),
			new DataValue.ErrorValue("NULL", "not null"), new DataValue.ListValue(List.of())))
			assertEquals(bool(false), ScalarOperation.IS_NULL.evaluate(Map.of("value", value), LOCATION));
	}

	@Test
	void everyRuntimeResultFitsItsInferredOutputSignature() {
		for (ScalarOperation operation : ScalarOperation.values()) {
			if (operation.retainedEdge()) continue; // Requires a private ISA frame; see FlowAuthoredAssemblyTest.
			Map<String, DataValue> values = sampleArguments(operation);
			var types = new LinkedHashMap<String, DataType>();
			values.forEach((name, value) -> types.put(name, DataType.typeOf(value)));
			assertTrue(operation.outputType(types, LOCATION).accepts(operation.evaluate(values, LOCATION)), operation.name());
		}
	}

	@Test
	void legacyOperationsMapExplicitlyToTheCatalog() {
		assertSame(ScalarOperation.ADD_INTEGER, ScalarOperation.forLegacy(ComputationGraph.BinaryOp.ADD_INTEGER));
		assertSame(ScalarOperation.LESS_INTEGER, ScalarOperation.forLegacy(ComputationGraph.BinaryOp.LESS_INTEGER));
		assertSame(ScalarOperation.EQUAL_INTEGER, ScalarOperation.forLegacy(ComputationGraph.BinaryOp.EQUAL_INTEGER));
		assertThrows(NullPointerException.class, () -> ScalarOperation.forLegacy(null));
	}

	private static Map<String, DataValue> sampleArguments(ScalarOperation operation) {
		return switch (operation) {
			case SAMPLE_RISING_INTEGER, SAMPLE_FALLING_INTEGER, SAMPLE_RISING_DECIMAL, SAMPLE_FALLING_DECIMAL -> throw new IllegalArgumentException("Requires ISA frame");
            case EDGE_RISING_INTEGER, EDGE_FALLING_INTEGER -> Map.of("previous", integer(0), "current", integer(2), "threshold", integer(1));
			case EDGE_RISING_DECIMAL, EDGE_FALLING_DECIMAL -> Map.of("previous", decimal(0), "current", decimal(2), "threshold", decimal(1));
			case ADD_INTEGER, SUBTRACT_INTEGER, MULTIPLY_INTEGER, DIVIDE_INTEGER, REMAINDER_INTEGER, MOD_INTEGER,
				MIN_INTEGER, MAX_INTEGER, LESS_INTEGER, LESS_EQUAL_INTEGER, GREATER_INTEGER,
				GREATER_EQUAL_INTEGER, EQUAL_INTEGER -> intArgs(8, 2);
			case ADD_DECIMAL, SUBTRACT_DECIMAL, MULTIPLY_DECIMAL, DIVIDE_DECIMAL, MIN_DECIMAL,
				MAX_DECIMAL, LESS_DECIMAL, LESS_EQUAL_DECIMAL, GREATER_DECIMAL, GREATER_EQUAL_DECIMAL,
				EQUAL_DECIMAL, POWER_DECIMAL, REMAINDER_DECIMAL, MOD_DECIMAL -> decimalArgs(8, 2);
			case AND_BOOLEAN, OR_BOOLEAN, XOR_BOOLEAN -> boolArgs(true, false);
			case NOT_BOOLEAN -> Map.of("value", bool(true));
			case NEGATE_INTEGER, ABS_INTEGER, INTEGER_TO_DECIMAL -> Map.of("value", integer(4));
			case NEGATE_DECIMAL, ABS_DECIMAL, DECIMAL_TO_INTEGER, SQRT_DECIMAL, ROUND_DECIMAL_TO_INTEGER, CEIL_DECIMAL_TO_INTEGER, FLOOR_DECIMAL_TO_INTEGER -> Map.of("value", decimal(4));
			case FLOOR_DECIMAL, CEIL_DECIMAL, SIN_DEGREES, COS_DEGREES, TAN_DEGREES, ASIN_DEGREES, ACOS_DEGREES, ATAN_DEGREES, LOG_NATURAL, LOG_BASE_TEN, EXP_NATURAL, EXP_BASE_TEN -> Map.of("value", decimal(0.5));
			case LIST_LENGTH -> Map.of("value", new DataValue.ListValue(List.of(integer(1))));
			case REQUIRE_VALUE, IS_NULL, IS_ERROR, ERROR_CODE, ERROR_MESSAGE -> Map.of("value", new DataValue.ErrorValue("E", "data"));
			case SELECT -> orderedValues("condition", bool(true), "when_true", integer(1), "when_false", integer(2));
		};
	}

	private static void assertSelectType(DataType expected, DataType whenTrue, DataType whenFalse) {
		assertEquals(expected, ScalarOperation.SELECT.outputType(
			orderedTypes("condition", DataType.Scalar.BOOLEAN, "when_true", whenTrue, "when_false", whenFalse), LOCATION));
	}

	private static void assertFailure(ComputationException.Code code, ScalarOperation operation,
		Map<String, DataValue> arguments) {
		ComputationException failure = assertThrows(ComputationException.class,
			() -> operation.evaluate(arguments, LOCATION), operation.name());
		assertEquals(code, failure.code(), operation.name());
		assertEquals(LOCATION, failure.location(), operation.name());
	}

	private static void assertTypeFailure(ComputationException.Code code, ScalarOperation operation,
		Map<String, DataType> arguments) {
		ComputationException failure = assertThrows(ComputationException.class,
			() -> operation.outputType(arguments, LOCATION), operation.name());
		assertEquals(code, failure.code(), operation.name());
		assertEquals(LOCATION, failure.location(), operation.name());
	}

	private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
	private static DataValue.DecimalValue decimal(double value) { return new DataValue.DecimalValue(value); }
	private static DataValue.BooleanValue bool(boolean value) { return new DataValue.BooleanValue(value); }
	private static Map<String, DataValue> intArgs(long left, long right) {
		return orderedValues("left", integer(left), "right", integer(right));
	}
	private static Map<String, DataValue> decimalArgs(double left, double right) {
		return orderedValues("left", decimal(left), "right", decimal(right));
	}
	private static Map<String, DataValue> boolArgs(boolean left, boolean right) {
		return orderedValues("left", bool(left), "right", bool(right));
	}

	private static <T> Map<String, T> orderedTypes(String firstName, T first, String secondName, T second) {
		var result = new LinkedHashMap<String, T>();
		result.put(firstName, first);
		result.put(secondName, second);
		return result;
	}
	private static <T> Map<String, T> orderedTypes(String firstName, T first, String secondName, T second,
		String thirdName, T third) {
		var result = new LinkedHashMap<String, T>();
		result.put(firstName, first);
		result.put(secondName, second);
		result.put(thirdName, third);
		return result;
	}
	private static Map<String, DataValue> orderedValues(String firstName, DataValue first,
		String secondName, DataValue second) {
		return orderedTypes(firstName, first, secondName, second);
	}
	private static Map<String, DataValue> orderedValues(String firstName, DataValue first,
		String secondName, DataValue second, String thirdName, DataValue third) {
		return orderedTypes(firstName, first, secondName, second, thirdName, third);
	}
	@Test
	void decimalMathKeepsFiniteValuesAndRejectsUndefinedResults() {
		assertEquals(decimal(-2), ScalarOperation.FLOOR_DECIMAL.evaluate(Map.of("value", decimal(-1.5)), LOCATION));
		assertEquals(decimal(-1), ScalarOperation.CEIL_DECIMAL.evaluate(Map.of("value", decimal(-1.5)), LOCATION));
		assertEquals(decimal(1), ScalarOperation.SIN_DEGREES.evaluate(Map.of("value", decimal(450)), LOCATION));
		assertEquals(decimal(0.5), ScalarOperation.SIN_DEGREES.evaluate(Map.of("value", decimal(30)), LOCATION));
		assertEquals(decimal(1), ScalarOperation.TAN_DEGREES.evaluate(Map.of("value", decimal(45)), LOCATION));
		assertEquals(decimal(0), ScalarOperation.COS_DEGREES.evaluate(Map.of("value", decimal(90)), LOCATION));
		assertEquals(decimal(90), ScalarOperation.ASIN_DEGREES.evaluate(Map.of("value", decimal(1)), LOCATION));
		assertEquals(decimal(180), ScalarOperation.ACOS_DEGREES.evaluate(Map.of("value", decimal(-1)), LOCATION));
		assertEquals(decimal(45), ScalarOperation.ATAN_DEGREES.evaluate(Map.of("value", decimal(1)), LOCATION));
		assertEquals(decimal(0), ScalarOperation.LOG_NATURAL.evaluate(Map.of("value", decimal(1)), LOCATION));
		assertEquals(decimal(2), ScalarOperation.LOG_BASE_TEN.evaluate(Map.of("value", decimal(100)), LOCATION));
		assertEquals(decimal(1), ScalarOperation.EXP_NATURAL.evaluate(Map.of("value", decimal(0)), LOCATION));
		assertEquals(decimal(1000), ScalarOperation.EXP_BASE_TEN.evaluate(Map.of("value", decimal(3)), LOCATION));
		assertFailure(ComputationException.Code.INVALID_ARGUMENT, ScalarOperation.LOG_NATURAL, Map.of("value", decimal(0)));
		assertFailure(ComputationException.Code.INVALID_ARGUMENT, ScalarOperation.TAN_DEGREES, Map.of("value", decimal(-270)));
		assertFailure(ComputationException.Code.INVALID_ARGUMENT, ScalarOperation.ACOS_DEGREES, Map.of("value", decimal(2)));
		assertFailure(ComputationException.Code.ARITHMETIC_OVERFLOW, ScalarOperation.EXP_BASE_TEN, Map.of("value", decimal(309)));
	}

	@Test void floorModuloKeepsDivisorSignWithoutChangingRemainderOrCoercingZero() {
		assertEquals(integer(-1), ScalarOperation.REMAINDER_INTEGER.evaluate(intArgs(-7, 3), LOCATION));
		assertEquals(integer(2), ScalarOperation.MOD_INTEGER.evaluate(intArgs(-7, 3), LOCATION));
		assertEquals(integer(-2), ScalarOperation.MOD_INTEGER.evaluate(intArgs(7, -3), LOCATION));
		assertEquals(integer(0), ScalarOperation.MOD_INTEGER.evaluate(intArgs(Long.MIN_VALUE, -1), LOCATION));
		assertEquals(integer(-1), ScalarOperation.MOD_INTEGER.evaluate(intArgs(Long.MAX_VALUE, Long.MIN_VALUE), LOCATION));
		assertEquals(decimal(-1.5), ScalarOperation.REMAINDER_DECIMAL.evaluate(decimalArgs(-7.5, 3), LOCATION));
		assertEquals(decimal(1.5), ScalarOperation.MOD_DECIMAL.evaluate(decimalArgs(-7.5, 3), LOCATION));
		assertEquals(decimal(-1.5), ScalarOperation.MOD_DECIMAL.evaluate(decimalArgs(7.5, -3), LOCATION));
		assertEquals(decimal(-0.0), ScalarOperation.MOD_DECIMAL.evaluate(decimalArgs(-6.0, 3), LOCATION));
		assertFailure(ComputationException.Code.DIVISION_BY_ZERO, ScalarOperation.MOD_INTEGER, intArgs(8, 0));
		assertFailure(ComputationException.Code.DIVISION_BY_ZERO, ScalarOperation.MOD_DECIMAL, decimalArgs(8, -0.0));
	}


}
