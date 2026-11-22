package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** System-owned identity of one request effect, never part of the player's payload. */
public record RequestCorrelation(UUID requester, UUID eventId, int ordinal) {
	public RequestCorrelation {
		Objects.requireNonNull(requester);
		Objects.requireNonNull(eventId);
		if (ordinal < 0) throw new IllegalArgumentException("Request ordinal must be nonnegative");
	}

	public DataValue.RecordValue value() {
		return new DataValue.RecordValue(Map.of(
			"requester", new DataValue.TextValue(requester.toString()),
			"event_id", new DataValue.TextValue(eventId.toString()),
			"ordinal", new DataValue.IntegerValue(ordinal)));
	}
}
