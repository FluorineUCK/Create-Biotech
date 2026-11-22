package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Objects;

/** Typed system header, never inferred from keys in a player's record. */
public sealed interface MessageProtocol {
	enum Message implements MessageProtocol { INSTANCE }
	record Request(RequestContext context) implements MessageProtocol {
		public Request { Objects.requireNonNull(context); }
	}
	record Reply(RequestCorrelation correlation) implements MessageProtocol {
		public Reply { Objects.requireNonNull(correlation); }
	}

	default DataValue.RecordValue value() {
		return new DataValue.RecordValue(switch (this) {
			case Message ignored -> Map.of("kind", new DataValue.TextValue("MESSAGE"));
			case Request request -> Map.of("kind", new DataValue.TextValue("REQUEST"), "context", request.context().value());
			case Reply reply -> Map.of("kind", new DataValue.TextValue("REPLY"), "correlation", reply.correlation().value());
		});
	}
}
