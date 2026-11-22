package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Objects;
import java.util.UUID;

public record MessageEnvelope(UUID source, long sequence, UUID eventId, int ordinal,
	DataValue.RecordValue payload, MessageProtocol protocol) {
	public MessageEnvelope {
		Objects.requireNonNull(source);
		Objects.requireNonNull(eventId);
		Objects.requireNonNull(payload);
		Objects.requireNonNull(protocol);
		if (sequence < 0) throw new IllegalArgumentException("Message sequence must be nonnegative");
		if (ordinal < 0) throw new IllegalArgumentException("Message ordinal must be nonnegative");
		if (protocol instanceof MessageProtocol.Request request) {
			var correlation = request.context().correlation();
			if (!correlation.requester().equals(source) || !correlation.eventId().equals(eventId) || correlation.ordinal() != ordinal)
				throw new IllegalArgumentException("Request correlation must match its source effect");
		}
	}
	public MessageEnvelope(UUID source, long sequence, UUID eventId, int ordinal, DataValue.RecordValue payload) {
		this(source, sequence, eventId, ordinal, payload, MessageProtocol.Message.INSTANCE);
	}
}
