package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** System-owned return route and an immutable, player-named value copy. Empty only while staged. */
public record RequestContinuation(RequestContext origin, String input, Optional<DataValue.RecordValue> captured) {
	public RequestContinuation {
		Objects.requireNonNull(origin); RpcState.requireName(input); Objects.requireNonNull(captured);
	}
	public RequestContinuation capture(DataValue.RecordValue value) {
		return new RequestContinuation(origin, input, Optional.of(value));
	}
	public DataValue.RecordValue value() {
		return new DataValue.RecordValue(Map.of("origin", origin.value(), "input", new DataValue.TextValue(input),
			"captured", captured.<DataValue>map(value -> value).orElse(DataValue.NullValue.INSTANCE)));
	}
}
