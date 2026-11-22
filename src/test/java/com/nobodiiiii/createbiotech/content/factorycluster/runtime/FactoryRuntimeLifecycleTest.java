package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FactoryRuntimeLifecycleTest {
	@Test
	void orderlyStopFreezesExactlyOnceAndNeverAcceptsWorkAgain() {
		FactoryRuntimeLifecycle lifecycle = new FactoryRuntimeLifecycle();
		assertEquals(FactoryRuntimeLifecycle.Phase.RUNNING, lifecycle.phase());
		assertTrue(lifecycle.acceptsWork());
		assertTrue(lifecycle.beginOrderlyShutdown());
		assertEquals(FactoryRuntimeLifecycle.Phase.STOPPING_FROZEN, lifecycle.phase());
		assertFalse(lifecycle.acceptsWork());
		assertTrue(lifecycle.technicalUnload());
		assertFalse(lifecycle.beginOrderlyShutdown());
		assertTrue(lifecycle.markStopped());
		assertEquals(FactoryRuntimeLifecycle.Phase.STOPPED, lifecycle.phase());
		assertFalse(lifecycle.markStopped());
		assertFalse(lifecycle.technicalUnload());
	}
}
