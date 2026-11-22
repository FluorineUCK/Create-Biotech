package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.OptionalType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.BooleanValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.DecimalValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue.IntegerValue;

/** Pure scalar operations shared by static checking and execution. */
public enum ScalarOperation {
	ADD_INTEGER(Category.INTEGER, binary(Scalar.INTEGER)),
	SUBTRACT_INTEGER(Category.INTEGER, binary(Scalar.INTEGER)),
	MULTIPLY_INTEGER(Category.INTEGER, binary(Scalar.INTEGER)),
	DIVIDE_INTEGER(Category.INTEGER, binary(Scalar.INTEGER)),
	REMAINDER_INTEGER(Category.INTEGER, binary(Scalar.INTEGER)),
	MIN_INTEGER(Category.INTEGER, binary(Scalar.INTEGER)),
	MAX_INTEGER(Category.INTEGER, binary(Scalar.INTEGER)),
	LESS_INTEGER(Category.INTEGER, binary(Scalar.INTEGER)),
	LESS_EQUAL_INTEGER(Category.INTEGER, binary(Scalar.INTEGER)),
	GREATER_INTEGER(Category.INTEGER, binary(Scalar.INTEGER)),
	GREATER_EQUAL_INTEGER(Category.INTEGER, binary(Scalar.INTEGER)),
	EQUAL_INTEGER(Category.INTEGER, binary(Scalar.INTEGER)),

	ADD_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),
	SUBTRACT_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),
	MULTIPLY_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),
	DIVIDE_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),
	MIN_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),
	MAX_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),
	LESS_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),
	LESS_EQUAL_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),
	GREATER_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),
	GREATER_EQUAL_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),
	EQUAL_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),

	AND_BOOLEAN(Category.LOGIC, binary(Scalar.BOOLEAN)),
	OR_BOOLEAN(Category.LOGIC, binary(Scalar.BOOLEAN)),
	XOR_BOOLEAN(Category.LOGIC, binary(Scalar.BOOLEAN)),
	NOT_BOOLEAN(Category.LOGIC, unary(Scalar.BOOLEAN)),
	NEGATE_INTEGER(Category.INTEGER, unary(Scalar.INTEGER)),
	ABS_INTEGER(Category.INTEGER, unary(Scalar.INTEGER)),
	NEGATE_DECIMAL(Category.DECIMAL, unary(Scalar.DECIMAL)),
	ABS_DECIMAL(Category.DECIMAL, unary(Scalar.DECIMAL)),
	INTEGER_TO_DECIMAL(Category.CONVERSION, unary(Scalar.INTEGER)),
	DECIMAL_TO_INTEGER(Category.CONVERSION, unary(Scalar.DECIMAL)),
	IS_NULL(Category.VALUE, unary(Scalar.ANY)),
	SELECT(Category.VALUE, select()),
	SQRT_DECIMAL(Category.DECIMAL, unary(Scalar.DECIMAL)),
	POWER_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),
	REMAINDER_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),
	ROUND_DECIMAL_TO_INTEGER(Category.CONVERSION, unary(Scalar.DECIMAL)),
	CEIL_DECIMAL_TO_INTEGER(Category.CONVERSION, unary(Scalar.DECIMAL)),
	FLOOR_DECIMAL_TO_INTEGER(Category.CONVERSION, unary(Scalar.DECIMAL)),
	IS_ERROR(Category.VALUE, unary(Scalar.ANY)),
	ERROR_CODE(Category.VALUE, unary(Scalar.ERROR)),
	ERROR_MESSAGE(Category.VALUE, unary(Scalar.ERROR)),
	LIST_LENGTH(Category.VALUE, unary(new DataType.ListType(Scalar.ANY))),
	REQUIRE_VALUE(Category.VALUE, unary(Scalar.ANY)),
	EDGE_RISING_INTEGER(Category.LOGIC, edge(Scalar.INTEGER)),
	EDGE_FALLING_INTEGER(Category.LOGIC, edge(Scalar.INTEGER)),
	EDGE_RISING_DECIMAL(Category.LOGIC, edge(Scalar.DECIMAL)),
	EDGE_FALLING_DECIMAL(Category.LOGIC, edge(Scalar.DECIMAL)),
	SAMPLE_RISING_INTEGER(Category.LOGIC, sample(Scalar.INTEGER)),
	SAMPLE_FALLING_INTEGER(Category.LOGIC, sample(Scalar.INTEGER)),
	SAMPLE_RISING_DECIMAL(Category.LOGIC, sample(Scalar.DECIMAL)),
	SAMPLE_FALLING_DECIMAL(Category.LOGIC, sample(Scalar.DECIMAL)),

	MOD_INTEGER(Category.INTEGER, binary(Scalar.INTEGER)),
	MOD_DECIMAL(Category.DECIMAL, binary(Scalar.DECIMAL)),

	FLOOR_DECIMAL(Category.DECIMAL, unary(Scalar.DECIMAL)),
	CEIL_DECIMAL(Category.DECIMAL, unary(Scalar.DECIMAL)),
	SIN_DEGREES(Category.DECIMAL, unary(Scalar.DECIMAL)),
	COS_DEGREES(Category.DECIMAL, unary(Scalar.DECIMAL)),
	TAN_DEGREES(Category.DECIMAL, unary(Scalar.DECIMAL)),
	ASIN_DEGREES(Category.DECIMAL, unary(Scalar.DECIMAL)),
	ACOS_DEGREES(Category.DECIMAL, unary(Scalar.DECIMAL)),
	ATAN_DEGREES(Category.DECIMAL, unary(Scalar.DECIMAL)),
	LOG_NATURAL(Category.DECIMAL, unary(Scalar.DECIMAL)),
	LOG_BASE_TEN(Category.DECIMAL, unary(Scalar.DECIMAL)),
	EXP_NATURAL(Category.DECIMAL, unary(Scalar.DECIMAL)),
	EXP_BASE_TEN(Category.DECIMAL, unary(Scalar.DECIMAL));

	public boolean numericEdge() {
		return switch (this) {
			case EDGE_RISING_INTEGER, EDGE_FALLING_INTEGER, EDGE_RISING_DECIMAL, EDGE_FALLING_DECIMAL,
				SAMPLE_RISING_INTEGER, SAMPLE_FALLING_INTEGER, SAMPLE_RISING_DECIMAL, SAMPLE_FALLING_DECIMAL -> true;
			default -> false;
		};
	}

	public boolean retainedEdge() {
		return switch (this) {
			case SAMPLE_RISING_INTEGER, SAMPLE_FALLING_INTEGER, SAMPLE_RISING_DECIMAL, SAMPLE_FALLING_DECIMAL -> true;
			default -> false;
		};
	}

	public boolean fallingEdge() {
		return switch (this) {
			case EDGE_FALLING_INTEGER, EDGE_FALLING_DECIMAL, SAMPLE_FALLING_INTEGER, SAMPLE_FALLING_DECIMAL -> true;
			default -> false;
		};
	}

	public ScalarOperation oppositeEdge() {
		return switch (this) {
			case EDGE_RISING_INTEGER -> EDGE_FALLING_INTEGER;
			case EDGE_FALLING_INTEGER -> EDGE_RISING_INTEGER;
			case EDGE_RISING_DECIMAL -> EDGE_FALLING_DECIMAL;
			case EDGE_FALLING_DECIMAL -> EDGE_RISING_DECIMAL;
			case SAMPLE_RISING_INTEGER -> SAMPLE_FALLING_INTEGER;
			case SAMPLE_FALLING_INTEGER -> SAMPLE_RISING_INTEGER;
			case SAMPLE_RISING_DECIMAL -> SAMPLE_FALLING_DECIMAL;
			case SAMPLE_FALLING_DECIMAL -> SAMPLE_RISING_DECIMAL;
			default -> throw new IllegalStateException("Not a numeric edge");
		};
	}

	public static boolean crosses(boolean wasAbove, boolean isAbove, boolean falling) {
		return falling ? wasAbove && !isAbove : !wasAbove && isAbove;
	}

	public enum Category { INTEGER, DECIMAL, LOGIC, CONVERSION, VALUE }

	private final Category category;
	private final Map<String, DataType> inputs;

	ScalarOperation(Category category, Map<String, DataType> inputs) {
		this.category = category;
		this.inputs = DataValue.immutableMap(inputs);
	}

	public Category category() { return category; }
	public Map<String, DataType> inputs() { return inputs; }

	public boolean extendedDecimalMath() {
		return switch (this) {
			case FLOOR_DECIMAL, CEIL_DECIMAL, SIN_DEGREES, COS_DEGREES, TAN_DEGREES,
				ASIN_DEGREES, ACOS_DEGREES, ATAN_DEGREES, LOG_NATURAL, LOG_BASE_TEN, EXP_NATURAL, EXP_BASE_TEN -> true;
			default -> false;
		};
	}

	public DataType outputType(Map<String, DataType> arguments, String location) {
		validateTypes(arguments, location);
		return switch (this) {
			case LESS_INTEGER, LESS_EQUAL_INTEGER, GREATER_INTEGER, GREATER_EQUAL_INTEGER, EQUAL_INTEGER,
				LESS_DECIMAL, LESS_EQUAL_DECIMAL, GREATER_DECIMAL, GREATER_EQUAL_DECIMAL, EQUAL_DECIMAL,
				AND_BOOLEAN, OR_BOOLEAN, XOR_BOOLEAN, NOT_BOOLEAN, IS_NULL, IS_ERROR -> Scalar.BOOLEAN;
			case EDGE_RISING_INTEGER, EDGE_FALLING_INTEGER, EDGE_RISING_DECIMAL, EDGE_FALLING_DECIMAL, SAMPLE_RISING_INTEGER, SAMPLE_FALLING_INTEGER, SAMPLE_RISING_DECIMAL, SAMPLE_FALLING_DECIMAL -> Scalar.BOOLEAN;
			case LIST_LENGTH, ADD_INTEGER, SUBTRACT_INTEGER, MULTIPLY_INTEGER, DIVIDE_INTEGER, REMAINDER_INTEGER, MOD_INTEGER,
				MIN_INTEGER, MAX_INTEGER, NEGATE_INTEGER, ABS_INTEGER, DECIMAL_TO_INTEGER,
				ROUND_DECIMAL_TO_INTEGER, CEIL_DECIMAL_TO_INTEGER, FLOOR_DECIMAL_TO_INTEGER -> Scalar.INTEGER;
			case ADD_DECIMAL, SUBTRACT_DECIMAL, MULTIPLY_DECIMAL, DIVIDE_DECIMAL, MIN_DECIMAL,
				MAX_DECIMAL, NEGATE_DECIMAL, ABS_DECIMAL, INTEGER_TO_DECIMAL, SQRT_DECIMAL, POWER_DECIMAL, REMAINDER_DECIMAL, MOD_DECIMAL,
				FLOOR_DECIMAL, CEIL_DECIMAL, SIN_DEGREES, COS_DEGREES, TAN_DEGREES, ASIN_DEGREES, ACOS_DEGREES, ATAN_DEGREES,
				LOG_NATURAL, LOG_BASE_TEN, EXP_NATURAL, EXP_BASE_TEN -> Scalar.DECIMAL;
			case SELECT -> join(arguments.get("when_true"), arguments.get("when_false"));
			case REQUIRE_VALUE -> requiredType(arguments.get("value"));
			case ERROR_CODE, ERROR_MESSAGE -> Scalar.TEXT;
		};
	}

	public DataValue evaluate(Map<String, DataValue> arguments, String location) {
		validateValues(arguments, location);
		return switch (this) {
			case ADD_INTEGER -> integerExact(() -> Math.addExact(leftInteger(arguments), rightInteger(arguments)), location);
			case SUBTRACT_INTEGER -> integerExact(() -> Math.subtractExact(leftInteger(arguments), rightInteger(arguments)), location);
			case MULTIPLY_INTEGER -> integerExact(() -> Math.multiplyExact(leftInteger(arguments), rightInteger(arguments)), location);
			case DIVIDE_INTEGER -> divideInteger(leftInteger(arguments), rightInteger(arguments), location);
			case REMAINDER_INTEGER -> remainderInteger(leftInteger(arguments), rightInteger(arguments), location);
			case MOD_INTEGER -> moduloInteger(leftInteger(arguments), rightInteger(arguments), location);
			case MIN_INTEGER -> new IntegerValue(Math.min(leftInteger(arguments), rightInteger(arguments)));
			case MAX_INTEGER -> new IntegerValue(Math.max(leftInteger(arguments), rightInteger(arguments)));
			case LESS_INTEGER -> new BooleanValue(leftInteger(arguments) < rightInteger(arguments));
			case LESS_EQUAL_INTEGER -> new BooleanValue(leftInteger(arguments) <= rightInteger(arguments));
			case GREATER_INTEGER -> new BooleanValue(leftInteger(arguments) > rightInteger(arguments));
			case GREATER_EQUAL_INTEGER -> new BooleanValue(leftInteger(arguments) >= rightInteger(arguments));
			case EQUAL_INTEGER -> new BooleanValue(leftInteger(arguments) == rightInteger(arguments));
			case ADD_DECIMAL -> decimal(leftDecimal(arguments) + rightDecimal(arguments), location);
			case SUBTRACT_DECIMAL -> decimal(leftDecimal(arguments) - rightDecimal(arguments), location);
			case MULTIPLY_DECIMAL -> decimal(leftDecimal(arguments) * rightDecimal(arguments), location);
			case DIVIDE_DECIMAL -> divideDecimal(leftDecimal(arguments), rightDecimal(arguments), location);
			case MIN_DECIMAL -> decimal(Math.min(leftDecimal(arguments), rightDecimal(arguments)), location);
			case MAX_DECIMAL -> decimal(Math.max(leftDecimal(arguments), rightDecimal(arguments)), location);
			case LESS_DECIMAL -> new BooleanValue(leftDecimal(arguments) < rightDecimal(arguments));
			case LESS_EQUAL_DECIMAL -> new BooleanValue(leftDecimal(arguments) <= rightDecimal(arguments));
			case GREATER_DECIMAL -> new BooleanValue(leftDecimal(arguments) > rightDecimal(arguments));
			case GREATER_EQUAL_DECIMAL -> new BooleanValue(leftDecimal(arguments) >= rightDecimal(arguments));
			case EQUAL_DECIMAL -> new BooleanValue(leftDecimal(arguments) == rightDecimal(arguments));
			case AND_BOOLEAN -> new BooleanValue(leftBoolean(arguments) && rightBoolean(arguments));
			case OR_BOOLEAN -> new BooleanValue(leftBoolean(arguments) || rightBoolean(arguments));
			case XOR_BOOLEAN -> new BooleanValue(leftBoolean(arguments) ^ rightBoolean(arguments));
			case NOT_BOOLEAN -> new BooleanValue(!booleanValue(arguments));
			case NEGATE_INTEGER -> integerExact(() -> Math.negateExact(integerValue(arguments)), location);
			case ABS_INTEGER -> integerExact(() -> Math.absExact(integerValue(arguments)), location);
			case NEGATE_DECIMAL -> decimal(-decimalValue(arguments), location);
			case ABS_DECIMAL -> decimal(Math.abs(decimalValue(arguments)), location);
			case SQRT_DECIMAL -> realDecimal(StrictMath.sqrt(decimalValue(arguments)), location);
			case FLOOR_DECIMAL -> decimal(StrictMath.floor(decimalValue(arguments)), location);
			case CEIL_DECIMAL -> decimal(StrictMath.ceil(decimalValue(arguments)), location);
			case SIN_DEGREES, COS_DEGREES, TAN_DEGREES -> trig(decimalValue(arguments), location);
			case ASIN_DEGREES -> realDecimal(StrictMath.toDegrees(StrictMath.asin(decimalValue(arguments))), location);
			case ACOS_DEGREES -> realDecimal(StrictMath.toDegrees(StrictMath.acos(decimalValue(arguments))), location);
			case ATAN_DEGREES -> realDecimal(StrictMath.toDegrees(StrictMath.atan(decimalValue(arguments))), location);
			case LOG_NATURAL -> realDecimal(decimalValue(arguments) <= 0 ? Double.NaN : StrictMath.log(decimalValue(arguments)), location);
			case LOG_BASE_TEN -> realDecimal(decimalValue(arguments) <= 0 ? Double.NaN : StrictMath.log10(decimalValue(arguments)), location);
			case EXP_NATURAL -> decimal(StrictMath.exp(decimalValue(arguments)), location);
			case EXP_BASE_TEN -> decimal(StrictMath.pow(10, decimalValue(arguments)), location);
			case POWER_DECIMAL -> powerDecimal(leftDecimal(arguments), rightDecimal(arguments), location);
			case REMAINDER_DECIMAL -> remainderDecimal(leftDecimal(arguments), rightDecimal(arguments), location);
			case MOD_DECIMAL -> moduloDecimal(leftDecimal(arguments), rightDecimal(arguments), location);
			case INTEGER_TO_DECIMAL -> new DecimalValue((double) integerValue(arguments));
			case DECIMAL_TO_INTEGER -> decimalToInteger(decimalValue(arguments), location);
			case ROUND_DECIMAL_TO_INTEGER -> roundDecimal(decimalValue(arguments), location);
			case CEIL_DECIMAL_TO_INTEGER -> decimalToInteger(StrictMath.ceil(decimalValue(arguments)), location);
			case FLOOR_DECIMAL_TO_INTEGER -> decimalToInteger(StrictMath.floor(decimalValue(arguments)), location);
			case LIST_LENGTH -> new IntegerValue(((DataValue.ListValue)arguments.get("value")).values().size());
			case EDGE_RISING_INTEGER, EDGE_FALLING_INTEGER -> {
				long limit = ((IntegerValue) arguments.get("threshold")).value();
				yield new BooleanValue(crosses(((IntegerValue) arguments.get("previous")).value() > limit,
					((IntegerValue) arguments.get("current")).value() > limit, fallingEdge()));
			}
			case EDGE_RISING_DECIMAL, EDGE_FALLING_DECIMAL -> {
				double limit = ((DecimalValue) arguments.get("threshold")).value();
				yield new BooleanValue(crosses(((DecimalValue) arguments.get("previous")).value() > limit,
					((DecimalValue) arguments.get("current")).value() > limit, fallingEdge()));
			}
			case SAMPLE_RISING_INTEGER, SAMPLE_FALLING_INTEGER, SAMPLE_RISING_DECIMAL, SAMPLE_FALLING_DECIMAL ->
				throw new IllegalStateException("Retained edge samples must lower to an ISA frame instruction");
			case IS_NULL -> new BooleanValue(arguments.get("value") == DataValue.NullValue.INSTANCE);
			case REQUIRE_VALUE -> requireValue(arguments.get("value"), location);
			case IS_ERROR -> new BooleanValue(arguments.get("value") instanceof DataValue.ErrorValue);
			case ERROR_CODE -> new DataValue.TextValue(((DataValue.ErrorValue) arguments.get("value")).code());
			case ERROR_MESSAGE -> new DataValue.TextValue(((DataValue.ErrorValue) arguments.get("value")).message());
			case SELECT -> ((BooleanValue) arguments.get("condition")).value()
				? arguments.get("when_true") : arguments.get("when_false");
		};
	}

	public static ScalarOperation forLegacy(ComputationGraph.BinaryOp operation) {
		return switch (Objects.requireNonNull(operation)) {
			case ADD_INTEGER -> ADD_INTEGER;
			case LESS_INTEGER -> LESS_INTEGER;
			case EQUAL_INTEGER -> EQUAL_INTEGER;
		};
	}

	private void validateTypes(Map<String, DataType> arguments, String location) {
		requireNames(arguments, location);
		for (var input : inputs.entrySet()) {
			DataType actual = arguments.get(input.getKey());
			if (actual == null || !input.getValue().compatibleWith(actual))
				throw failure(ComputationException.Code.TYPE_MISMATCH, location,
					"Input '" + input.getKey() + "' has incompatible type");
		}
	}

	private void validateValues(Map<String, DataValue> arguments, String location) {
		requireNames(arguments, location);
		for (var input : inputs.entrySet()) {
			DataValue actual = arguments.get(input.getKey());
			// Immutable list size needs only the container tag, never ListType.accepts element traversal.
			if (actual == null || (this == LIST_LENGTH ? !(actual instanceof DataValue.ListValue) : !input.getValue().accepts(actual)))
				throw failure(ComputationException.Code.TYPE_MISMATCH, location,
					"Input '" + input.getKey() + "' has incompatible value");
		}
	}

	private void requireNames(Map<?, ?> arguments, String location) {
		Objects.requireNonNull(arguments);
		if (!arguments.keySet().equals(inputs.keySet()))
			throw failure(ComputationException.Code.INVALID_GRAPH, location, "Scalar operation arguments do not match its signature");
	}

	private static DataType requiredType(DataType type) {
		while (type instanceof OptionalType optional) type = optional.value();
		// Known null carries no payload evidence; it must never strengthen into a concrete type.
		return type == Scalar.NULL ? Scalar.ANY : type;
	}

	private static DataValue requireValue(DataValue value, String location) {
		if (value == DataValue.NullValue.INSTANCE)
			throw failure(ComputationException.Code.INVALID_ARGUMENT, location, "Required value is null");
		return value;
	}

	private static DataType join(DataType left, DataType right) {
		if (left.equals(right)) return left;
		if (left == Scalar.ANY || right == Scalar.ANY) return Scalar.ANY;
		if (left == Scalar.NULL) return optional(right);
		if (right == Scalar.NULL) return optional(left);
		if (left instanceof OptionalType optional && optional.value().equals(right)) return left;
		if (right instanceof OptionalType optional && optional.value().equals(left)) return right;
		return Scalar.ANY;
	}

	private static DataType optional(DataType value) {
		return value instanceof OptionalType ? value : new OptionalType(value);
	}

	private static IntegerValue divideInteger(long left, long right, String location) {
		if (right == 0) throw failure(ComputationException.Code.DIVISION_BY_ZERO, location, "Integer division by zero");
		if (left == Long.MIN_VALUE && right == -1)
			throw failure(ComputationException.Code.ARITHMETIC_OVERFLOW, location, "Integer division overflow");
		return new IntegerValue(left / right);
	}

	private static IntegerValue remainderInteger(long left, long right, String location) {
		if (right == 0) throw failure(ComputationException.Code.DIVISION_BY_ZERO, location, "Integer remainder by zero");
		return new IntegerValue(left % right);
	}

	private static IntegerValue moduloInteger(long left, long right, String location) {
		if (right == 0) throw failure(ComputationException.Code.DIVISION_BY_ZERO, location, "Integer modulo by zero");
		return new IntegerValue(Math.floorMod(left, right));
	}

	private static DecimalValue divideDecimal(double left, double right, String location) {
		if (right == 0.0) throw failure(ComputationException.Code.DIVISION_BY_ZERO, location, "Decimal division by zero");
		return decimal(left / right, location);
	}

	private static DecimalValue remainderDecimal(double left, double right, String location) {
		if (right == 0.0) throw failure(ComputationException.Code.DIVISION_BY_ZERO, location, "Decimal remainder by zero");
		return decimal(left % right, location);
	}

	private static DecimalValue moduloDecimal(double left, double right, String location) {
		if (right == 0.0) throw failure(ComputationException.Code.DIVISION_BY_ZERO, location, "Decimal modulo by zero");
		// Compute the remainder before adjusting its sign: left/right can overflow or
		// underflow even though the modulo is finite. Keep IEEE rounding and signed zero.
		double remainder = left % right;
		return decimal(remainder != 0.0 && ((remainder < 0.0) != (right < 0.0)) ? remainder + right : remainder, location);
	}

	private static DecimalValue powerDecimal(double base, double exponent, String location) {
		if (base == 0.0 && exponent < 0.0)
			throw failure(ComputationException.Code.DIVISION_BY_ZERO, location, "Zero raised to a negative power");
		return realDecimal(StrictMath.pow(base, exponent), location);
	}

	private DecimalValue trig(double degrees, String location) {
		// Reduce before converting to radians, retaining precision even at large angles.
		double angle = StrictMath.IEEEremainder(degrees, 360.0);
		double magnitude = StrictMath.abs(angle);
		double radians = StrictMath.toRadians(angle);
		return switch (this) {
			case SIN_DEGREES -> decimal(magnitude == 90 ? StrictMath.copySign(1, angle)
				: magnitude == 180 ? 0 : roundTrig(StrictMath.sin(radians)), location);
			case COS_DEGREES -> decimal(magnitude == 90 ? 0 : magnitude == 180 ? -1 : roundTrig(StrictMath.cos(radians)), location);
			case TAN_DEGREES -> realDecimal(magnitude == 90 ? Double.NaN
				: magnitude == 180 ? 0 : roundTrig(StrictMath.tan(radians)), location);
			default -> throw new IllegalStateException("Not a forward trigonometric operation");
		};
	}

	private static double roundTrig(double result) {
		// Scratch rounds forward trig to ten decimal places (sin(30) should equal 0.5).
		double magnitude = StrictMath.abs(result);
		if (magnitude >= 0x1p19) return result; // One double ULP already exceeds 1e-10.
		double scale = 1e10;
		double lower = StrictMath.floor(magnitude * scale);
		// Use the unrounded product's remainder at the half-way boundary.
		double nearest = lower + (Math.fma(magnitude, scale, -lower) >= 0.5 ? 1 : 0);
		return StrictMath.copySign(nearest / scale, result);
	}

	private static IntegerValue decimalToInteger(double value, String location) {
		requireIntegerRange(value, location);
		return new IntegerValue((long) value);
	}

	private static IntegerValue roundDecimal(double value, String location) {
		// Math.round saturates outside long range; reject those values instead.
		requireIntegerRange(value, location);
		return new IntegerValue(Math.round(value));
	}

	private static void requireIntegerRange(double value, String location) {
		if (value < -0x1.0p63 || value >= 0x1.0p63)
			throw failure(ComputationException.Code.ARITHMETIC_OVERFLOW, location, "Decimal to integer conversion overflow");
	}

	private static IntegerValue integerExact(LongCalculation calculation, String location) {
		try {
			return new IntegerValue(calculation.get());
		} catch (ArithmeticException overflow) {
			throw failure(ComputationException.Code.ARITHMETIC_OVERFLOW, location, "Integer arithmetic overflow");
		}
	}

	private static DecimalValue realDecimal(double value, String location) {
		if (Double.isNaN(value))
			throw failure(ComputationException.Code.INVALID_ARGUMENT, location, "Operation has no real-valued result");
		return decimal(value, location);
	}

	private static DecimalValue decimal(double value, String location) {
		if (!Double.isFinite(value))
			throw failure(ComputationException.Code.ARITHMETIC_OVERFLOW, location, "Decimal arithmetic overflow");
		return new DecimalValue(value);
	}

	private static long leftInteger(Map<String, DataValue> values) { return ((IntegerValue) values.get("left")).value(); }
	private static long rightInteger(Map<String, DataValue> values) { return ((IntegerValue) values.get("right")).value(); }
	private static long integerValue(Map<String, DataValue> values) { return ((IntegerValue) values.get("value")).value(); }
	private static double leftDecimal(Map<String, DataValue> values) { return ((DecimalValue) values.get("left")).value(); }
	private static double rightDecimal(Map<String, DataValue> values) { return ((DecimalValue) values.get("right")).value(); }
	private static double decimalValue(Map<String, DataValue> values) { return ((DecimalValue) values.get("value")).value(); }
	private static boolean leftBoolean(Map<String, DataValue> values) { return ((BooleanValue) values.get("left")).value(); }
	private static boolean rightBoolean(Map<String, DataValue> values) { return ((BooleanValue) values.get("right")).value(); }
	private static boolean booleanValue(Map<String, DataValue> values) { return ((BooleanValue) values.get("value")).value(); }

	private static Map<String, DataType> edge(DataType type) {
		var inputs = new LinkedHashMap<String, DataType>();
		inputs.put("previous", type); inputs.put("current", type); inputs.put("threshold", type);
		return inputs;
	}
	private static Map<String, DataType> sample(DataType type) {
		var inputs = new LinkedHashMap<String, DataType>();
		inputs.put("current", type); inputs.put("threshold", type);
		return inputs;
	}
	private static Map<String, DataType> binary(DataType type) {
		var inputs = new LinkedHashMap<String, DataType>();
		inputs.put("left", type);
		inputs.put("right", type);
		return inputs;
	}

	private static Map<String, DataType> unary(DataType type) { return Map.of("value", type); }

	private static Map<String, DataType> select() {
		var inputs = new LinkedHashMap<String, DataType>();
		inputs.put("condition", Scalar.BOOLEAN);
		inputs.put("when_true", Scalar.ANY);
		inputs.put("when_false", Scalar.ANY);
		return inputs;
	}

	private static ComputationException failure(ComputationException.Code code, String location, String message) {
		return new ComputationException(code, Objects.requireNonNull(location), message);
	}

	@FunctionalInterface
	private interface LongCalculation { long get(); }
}
