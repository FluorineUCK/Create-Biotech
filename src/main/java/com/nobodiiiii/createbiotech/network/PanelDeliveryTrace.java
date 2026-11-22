package com.nobodiiiii.createbiotech.network;

import java.util.Optional;
import java.util.ArrayDeque;
import java.util.Objects;

/** Test observation of deliveries, separate from the production network transport. */
final class PanelDeliveryTrace<T> {
	static final int MAX_EVENTS = 32;
	private final ArrayDeque<T> events = new ArrayDeque<>();
	private boolean overflow;
	void record(T payload) {
		Objects.requireNonNull(payload, "payload");
		if (events.size() == MAX_EVENTS) overflow = true;
		else if (!overflow) events.addLast(payload);
	}
	Optional<T> take() {
		if (overflow) throw new IllegalStateException("Panel delivery observation overflow");
		return Optional.ofNullable(events.pollFirst());
	}
}
