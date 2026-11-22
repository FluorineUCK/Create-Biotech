package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

class PatternPriorityPolicyTest {

	@Test
	void unauthorizedOverrideRemainsStoredButDoesNotReplaceLibraryPriority() {
		var result = PatternPriorityPolicy.resolve(OptionalInt.of(30), 8, false);

		assertEquals(OptionalInt.of(30), result.storedOverride());
		assertFalse(result.active());
		assertEquals(8, result.effectivePriority());
	}

	@Test
	void authorizedOverrideReplacesRatherThanAddsToLibraryPriority() {
		var result = PatternPriorityPolicy.resolve(OptionalInt.of(30), 8, true);

		assertEquals(OptionalInt.of(30), result.storedOverride());
		assertTrue(result.active());
		assertEquals(30, result.effectivePriority());
	}

	@Test
	void absentOverrideAlwaysUsesLibraryPriorityEvenWhenAuthorized() {
		for (boolean authorized : new boolean[] { false, true }) {
			for (int priority : new int[] { Integer.MIN_VALUE, 0, Integer.MAX_VALUE }) {
				var result = PatternPriorityPolicy.resolve(OptionalInt.empty(), priority, authorized);
				assertEquals(OptionalInt.empty(), result.storedOverride());
				assertFalse(result.active());
				assertEquals(priority, result.effectivePriority());
			}
		}
	}

	@Test
	void signedPriorityExtremesAreNotClampedOrCombined() {
		assertEquals(Integer.MIN_VALUE, PatternPriorityPolicy.resolve(
			OptionalInt.of(Integer.MIN_VALUE), Integer.MAX_VALUE, true).effectivePriority());
		assertEquals(Integer.MAX_VALUE, PatternPriorityPolicy.resolve(
			OptionalInt.of(Integer.MAX_VALUE), Integer.MIN_VALUE, true).effectivePriority());
		assertEquals(Integer.MIN_VALUE, PatternPriorityPolicy.resolve(
			OptionalInt.of(Integer.MAX_VALUE), Integer.MIN_VALUE, false).effectivePriority());
		assertEquals(0, PatternPriorityPolicy.resolve(OptionalInt.of(0), -7, true).effectivePriority());
	}

	@Test
	void revokingAndRestoringAuthorizationDoesNotEraseTheStoredOverride() {
		OptionalInt stored = OptionalInt.of(-7);
		var authorized = PatternPriorityPolicy.resolve(stored, 8, true);
		var revoked = PatternPriorityPolicy.resolve(stored, 8, false);

		assertEquals(new PatternPriorityPolicy.Resolution(stored, true, -7), authorized);
		assertEquals(new PatternPriorityPolicy.Resolution(stored, false, 8), revoked);
		assertEquals(authorized, PatternPriorityPolicy.resolve(stored, 8, true));
	}

	@Test
	void invalidOverrideInputsCannotCreateAnActiveResolution() {
		assertThrows(NullPointerException.class, () -> PatternPriorityPolicy.resolve(null, 8, false));
		assertThrows(NullPointerException.class, () -> new PatternPriorityPolicy.Resolution(null, false, 8));
		assertThrows(IllegalArgumentException.class,
			() -> new PatternPriorityPolicy.Resolution(OptionalInt.empty(), true, 8));
	}
}
