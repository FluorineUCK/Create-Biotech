package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

import java.util.Objects;

/** Committed dispatch metadata; physical transport supplies a request's return name. */
public sealed interface OutboundProtocol {
	enum Message implements OutboundProtocol { INSTANCE }
	record Request(RequestCorrelation correlation) implements OutboundProtocol {
		public Request { Objects.requireNonNull(correlation); }
	}
	record Reply(RequestCorrelation correlation) implements OutboundProtocol {
		public Reply { Objects.requireNonNull(correlation); }
	}
}
