package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Objects;

/** The physical transport supplies the literal return endpoint for an incoming request. */
public record RequestContext(RequestCorrelation correlation, String returnEndpoint) {
	public RequestContext {
		Objects.requireNonNull(correlation);
		RpcState.requireName(returnEndpoint);
	}

	public DataValue.RecordValue value() {
		return new DataValue.RecordValue(Map.of("correlation", correlation.value(),
			"return_endpoint", new DataValue.TextValue(returnEndpoint)));
	}
}
