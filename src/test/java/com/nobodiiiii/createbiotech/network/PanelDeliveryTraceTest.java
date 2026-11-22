package com.nobodiiiii.createbiotech.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PanelDeliveryTraceTest {
	@Test void recordsEveryDeliveryInOrderRatherThanOverwritingWrongDomainOrDuplicates() {
		PanelDeliveryTrace<String> trace = new PanelDeliveryTrace<>();
		trace.record("wrong-domain");
		trace.record("expected-domain");
		trace.record("duplicate");
		assertEquals("wrong-domain", trace.take().orElseThrow());
		assertEquals("expected-domain", trace.take().orElseThrow());
		assertEquals("duplicate", trace.take().orElseThrow());
		assertTrue(trace.take().isEmpty());
	}
	@Test void overflowIsVisibleRatherThanSilentlyDroppingExcessResponses() {
		PanelDeliveryTrace<String> trace = new PanelDeliveryTrace<>();
		for (int index = 0; index <= PanelDeliveryTrace.MAX_EVENTS; index++) trace.record("response");
		assertThrows(IllegalStateException.class, trace::take);
	}
}
