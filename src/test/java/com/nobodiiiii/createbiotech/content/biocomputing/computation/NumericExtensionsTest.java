package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NumericExtensionsTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "ROUND_DECIMAL_TO_INTEGER,2,-1,0,0", "CEIL_DECIMAL_TO_INTEGER,2,-1,1,0", "FLOOR_DECIMAL_TO_INTEGER,1,-2,0,-1"
    })
    void roundingIsExplicitAndNeverSaturates(String name, long positive, long negative, long tinyPositive, long tinyNegative) {
        var operation = ScalarOperation.valueOf(name);
        assertEquals(new DataValue.IntegerValue(positive), operation.evaluate(unary(1.5), "round"));
        assertEquals(new DataValue.IntegerValue(negative), operation.evaluate(unary(-1.5), "round"));
        assertEquals(new DataValue.IntegerValue(tinyPositive), operation.evaluate(unary(Double.MIN_VALUE), "round"));
        assertEquals(new DataValue.IntegerValue(tinyNegative), operation.evaluate(unary(-Double.MIN_VALUE), "round"));
        assertEquals(new DataValue.IntegerValue(Long.MIN_VALUE), operation.evaluate(unary(-0x1.0p63), "round"));
        assertEquals(new DataValue.IntegerValue(0), operation.evaluate(unary(-0.0), "round"));
        for (double boundary : new double[] {Math.nextDown(0x1.0p63), Math.nextUp(-0x1.0p63), 0x1.0p52 + 1})
            assertEquals(new DataValue.IntegerValue((long)boundary), operation.evaluate(unary(boundary), "round"));
        for (double outside : new double[] {0x1.0p63, Math.nextDown(-0x1.0p63), Double.MAX_VALUE, -Double.MAX_VALUE})
            assertFailure(operation, unary(outside), ComputationException.Code.ARITHMETIC_OVERFLOW);
        assertFailure(operation, Map.of("value", new DataValue.IntegerValue(2)), ComputationException.Code.TYPE_MISMATCH);
        if (name.startsWith("ROUND")) {
            assertEquals(new DataValue.IntegerValue(0), operation.evaluate(unary(Math.nextDown(0.5)), "round"));
            assertEquals(new DataValue.IntegerValue(1), operation.evaluate(unary(0.5), "round"));
            assertEquals(new DataValue.IntegerValue(0), operation.evaluate(unary(-0.5), "round"));
            assertEquals(new DataValue.IntegerValue(-1), operation.evaluate(unary(Math.nextDown(-0.5)), "round"));
        }
    }

    private static Map<String, DataValue> unary(double value) { return Map.of("value", new DataValue.DecimalValue(value)); }

    @Test void decimalRemainderUsesDividendSignAndRejectsEitherZeroDivisor() {
        var operation = ScalarOperation.valueOf("REMAINDER_DECIMAL");
        assertEquals(new DataValue.DecimalValue(-1.5), operation.evaluate(binary(-7.5, 2), "remainder"));
        assertEquals(new DataValue.DecimalValue(1.5), operation.evaluate(binary(7.5, -2), "remainder"));
        assertEquals(new DataValue.DecimalValue(-0.0), operation.evaluate(binary(-4, 2), "remainder"));
        assertEquals(new DataValue.DecimalValue(0.0), operation.evaluate(binary(Double.MAX_VALUE, Double.MIN_VALUE), "remainder"));
        for (double zero : new double[] {0.0, -0.0})
            assertFailure(operation, binary(1, zero), ComputationException.Code.DIVISION_BY_ZERO);
    }

    @Test void powerHandlesFractionalAndNegativeExponentsWithoutHidingFailures() {
        var operation = ScalarOperation.valueOf("POWER_DECIMAL");
        assertEquals(new DataValue.DecimalValue(3), operation.evaluate(binary(9, 0.5), "power"));
        assertEquals(new DataValue.DecimalValue(0.125), operation.evaluate(binary(2, -3), "power"));
        assertEquals(new DataValue.DecimalValue(-8), operation.evaluate(binary(-2, 3), "power"));
        assertEquals(new DataValue.DecimalValue(1), operation.evaluate(binary(0, 0), "power"));
        assertFailure(operation, binary(-2, 0.5), ComputationException.Code.INVALID_ARGUMENT);
        assertFailure(operation, binary(0, -1), ComputationException.Code.DIVISION_BY_ZERO);
        assertFailure(operation, binary(Double.MAX_VALUE, 2), ComputationException.Code.ARITHMETIC_OVERFLOW);
    }

    private static Map<String, DataValue> binary(double left, double right) {
        return Map.of("left", new DataValue.DecimalValue(left), "right", new DataValue.DecimalValue(right));
    }
    private static void assertFailure(ScalarOperation operation, Map<String, DataValue> arguments, ComputationException.Code code) {
        var failure = assertThrows(ComputationException.class, () -> operation.evaluate(arguments, "numeric"));
        assertEquals(code, failure.code()); assertEquals("numeric", failure.location());
    }

    @Test void squareRootIsAPublicTypedDecimalOperation() {
        var operation = ScalarOperation.valueOf("SQRT_DECIMAL");
        assertEquals(DataType.Scalar.DECIMAL, operation.outputType(Map.of("value", DataType.Scalar.DECIMAL), "sqrt"));
        assertEquals(new DataValue.DecimalValue(3), operation.evaluate(Map.of("value", new DataValue.DecimalValue(9)), "sqrt"));
    }

    @Test void negativeSquareRootReportsAnInvalidArgumentNotAnOverflow() {
        var failure = assertThrows(ComputationException.class,
            () -> ScalarOperation.SQRT_DECIMAL.evaluate(Map.of("value", new DataValue.DecimalValue(-1)), "root"));
        assertEquals(ComputationException.Code.INVALID_ARGUMENT, failure.code()); assertEquals("root", failure.location());
        assertEquals(new DataValue.DecimalValue(-0.0), ScalarOperation.SQRT_DECIMAL.evaluate(Map.of("value", new DataValue.DecimalValue(-0.0)), "root"));
    }
}
