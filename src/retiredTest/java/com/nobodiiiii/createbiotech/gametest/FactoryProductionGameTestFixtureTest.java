package com.nobodiiiii.createbiotech.gametest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FactoryProductionGameTestFixtureTest {
	@Test
	void cleanupTimeoutPreservesTheOriginalFailure() {
		assertEquals("original behavior failure | production isolation cleanup timed out: active=1",
			FactoryProductionGameTestFixture.productionIsolationCleanupFailure(
				"original behavior failure", "active=1"));
	}

	@Test
	void cleanupTimeoutIsFatalWithoutAnEarlierBehaviorFailure() {
		assertEquals("production isolation cleanup timed out: pending={intent}",
			FactoryProductionGameTestFixture.productionIsolationCleanupFailure(
				null, "pending={intent}"));
	}

	@Test
	void cleanTeardownRethrowsTheExactOriginalAssertion() {
		AssertionError original = new AssertionError("original assertion evidence");

		AssertionError observed = assertThrows(AssertionError.class,
			() -> FactoryProductionGameTestFixture.finishProductionIsolationFailure(original));

		assertSame(original, observed);
	}

	@Test
	void cleanupFailureCombinesDiagnosticsWithTheOriginalCause() {
		AssertionError original = new AssertionError("original assertion evidence");

		AssertionError combined = FactoryProductionGameTestFixture
			.combinedProductionIsolationFailure(original, "active=1, pending={intent}");

		assertEquals("original assertion evidence | production isolation cleanup timed out: "
			+ "active=1, pending={intent}", combined.getMessage());
		assertSame(original, combined.getCause());
	}
}
