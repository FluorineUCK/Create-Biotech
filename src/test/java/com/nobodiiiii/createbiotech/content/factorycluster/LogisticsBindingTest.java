package com.nobodiiiii.createbiotech.content.factorycluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

class LogisticsBindingTest {
	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Test
	void normalizePreservesFirstUuidOccurrenceAndOrder() {
		List<LogisticsBinding> actual = LogisticsBinding.normalize(List.of(
			new LogisticsBinding(A), new LogisticsBinding(B), new LogisticsBinding(A)));
		assertEquals(List.of(new LogisticsBinding(A), new LogisticsBinding(B)), actual);
	}

	@Test
	void persistenceContainsOnlyTheNetworkUuidAndRejectsAnAliasBearingBinding() {
		CompoundTag encoded = new LogisticsBinding(A).save();
		assertEquals(java.util.Set.of("Id"), encoded.getAllKeys());
		assertEquals(new LogisticsBinding(A), LogisticsBinding.load(encoded).orElseThrow());
		encoded.putString("Alias", "old local value");
		assertTrue(LogisticsBinding.load(encoded).isEmpty());
	}
}
