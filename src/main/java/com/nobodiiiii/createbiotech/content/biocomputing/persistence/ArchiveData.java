package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import java.util.function.Function;

/** Strict schema helpers shared by the program and computer archive adapters. */
final class ArchiveData {
	private ArchiveData() {}
	static DataValue.RecordValue obj(Object... pairs) {
		if (pairs.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs");
		var fields = new LinkedHashMap<String, DataValue>();
		for (int i = 0; i < pairs.length; i += 2) {
			String key = (String) pairs[i];
			DataValue value = switch (pairs[i + 1]) {
				case DataValue data -> data;
				case String text -> new DataValue.TextValue(text);
				case Integer integer -> new DataValue.IntegerValue(integer);
				case Long integer -> new DataValue.IntegerValue(integer);
				case Boolean bool -> new DataValue.BooleanValue(bool);
				default -> throw new IllegalArgumentException("Not an archive scalar");
			};
			if (fields.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate schema key");
		}
		return new DataValue.RecordValue(fields);
	}
	static Map<String, DataValue> fields(DataValue value) {
		if (value instanceof DataValue.RecordValue record) return record.fields();
		throw ArchiveException.invalid("Expected record");
	}
	static DataValue at(DataValue record, String key) {
		var value = fields(record).get(key);
		if (value == null) throw ArchiveException.invalid("Missing field: " + key);
		return value;
	}
	static DataValue.RecordValue record(DataValue value) { fields(value); return (DataValue.RecordValue) value; }
	static String string(DataValue value) {
		if (value instanceof DataValue.TextValue text) return text.value();
		throw ArchiveException.invalid("Expected text");
	}
	static long number(DataValue value) {
		if (value instanceof DataValue.IntegerValue integer) return integer.value();
		throw ArchiveException.invalid("Expected integer");
	}
	static int index(DataValue value) {
		long n = number(value);
		if (n < 0 || n > Integer.MAX_VALUE) throw ArchiveException.invalid("Invalid index");
		return (int) n;
	}
	static boolean bool(DataValue value) {
		if (value instanceof DataValue.BooleanValue bool) return bool.value();
		throw ArchiveException.invalid("Expected boolean");
	}
	static List<DataValue> list(DataValue value) {
		if (value instanceof DataValue.ListValue list) return list.values();
		throw ArchiveException.invalid("Expected list");
	}
	static <T> DataValue.RecordValue named(Map<String, T> values, Function<T, DataValue> encode) {
		var result = new LinkedHashMap<String, DataValue>();
		values.forEach((key, value) -> result.put(key, encode.apply(value)));
		return new DataValue.RecordValue(result);
	}
	static <T> Map<String, T> readNamed(DataValue value, Function<DataValue, T> decode) {
		var result = new LinkedHashMap<String, T>();
		fields(value).forEach((key, child) -> result.put(key, decode.apply(child)));
		return result;
	}
	static <T> DataValue.ListValue sequence(Collection<T> values, Function<T, DataValue> encode) {
		return new DataValue.ListValue(values.stream().map(encode).toList());
	}
	static <E extends Enum<E>> E enumeration(Class<E> type, DataValue value) {
		try { return Enum.valueOf(type, string(value)); }
		catch (IllegalArgumentException invalid) { throw ArchiveException.invalid("Unknown " + type.getSimpleName()); }
	}
	static void version(DataValue value) {
		if (number(at(value, "version")) != 1)
			throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported archive schema version");
	}
}
