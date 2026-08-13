package com.nobodiiiii.createbiotech.content.factorycluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LogisticsBindingTest {
	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Test
	void normalizePreservesFirstOccurrenceAndOrder() {
		List<LogisticsBinding> actual = LogisticsBinding.normalize(List.of(
			new LogisticsBinding(A, " first "),
			new LogisticsBinding(B, "second"),
			new LogisticsBinding(A, "replacement")));
		assertEquals(List.of(new LogisticsBinding(A, "first"),
			new LogisticsBinding(B, "second")), actual);
	}

	@Test
	void emptyAliasFallsBackToShortNetworkId() {
		assertEquals("00000000", new LogisticsBinding(A, "  ").alias());
	}

	@Test
	void aliasIsLimitedToThirtyTwoUnicodeCodePoints() {
		String alias = "😀".repeat(33);
		assertEquals("😀".repeat(32), new LogisticsBinding(A, alias).alias());
	}
}
