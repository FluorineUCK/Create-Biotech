package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Objects;

/** Immutable public advertisement. Names are literal values, never patterns or formatting. */
public record EndpointConfiguration(String name, boolean enabled) {
	public static final int MAX_NAME_LENGTH = 64;
	public EndpointConfiguration {
		Objects.requireNonNull(name);
		if (name.isBlank() || name.length() > MAX_NAME_LENGTH)
			throw new IllegalArgumentException("Endpoint name must be nonblank and at most 64 UTF-16 units");
		// Generic TextValue deliberately preserves arbitrary UTF-16. Endpoint names have a stricter boundary.
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isHighSurrogate(c)) {
				if (++i >= name.length() || !Character.isLowSurrogate(name.charAt(i)))
					throw new IllegalArgumentException("Endpoint name contains an unpaired surrogate");
			} else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Endpoint name contains an unpaired surrogate");
		}
	}
	public DataValue.RecordValue value() {
		return new DataValue.RecordValue(Map.of("name", new DataValue.TextValue(name),
			"enabled", new DataValue.BooleanValue(enabled)));
	}
	public static EndpointConfiguration decode(DataValue.RecordValue value) {
		Objects.requireNonNull(value);
		if (!value.fields().keySet().equals(java.util.Set.of("name", "enabled")))
			throw new IllegalArgumentException("Endpoint configuration requires exact fields");
		if (!(value.field("name") instanceof DataValue.TextValue name)
			|| !(value.field("enabled") instanceof DataValue.BooleanValue enabled))
			throw new IllegalArgumentException("Endpoint configuration has invalid field types");
		return new EndpointConfiguration(name.value(), enabled.value());
	}
}
