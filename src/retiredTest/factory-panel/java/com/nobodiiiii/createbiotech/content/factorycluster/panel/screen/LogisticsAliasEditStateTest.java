package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LogisticsAliasEditStateTest {
	@Test
	void localizedFallbackIsNeverTreatedAsTheRawAlias() {
		LogisticsAliasEditState edit = new LogisticsAliasEditState();
		edit.select(UUID.randomUUID(), 0, Optional.empty());
		assertEquals("", edit.text());
		assertFalse(edit.canSubmit());
		assertFalse(edit.edit(""));
		assertTrue(edit.edit("global-name"));
		assertEquals(Optional.of("global-name"), edit.commandAlias());
	}

	@Test
	void canonicalEquivalentAndInvalidAliasesCannotBeSubmitted() {
		LogisticsAliasEditState edit = new LogisticsAliasEditState();
		edit.select(UUID.randomUUID(), 0, Optional.of("Alpha"));

		assertTrue(edit.edit("  Alpha  "));
		assertFalse(edit.canSubmit());
		assertEquals(Optional.of("Alpha"), edit.commandAlias());

		assertTrue(edit.edit("Ａｌｐｈａ"));
		assertFalse(edit.canSubmit());
		assertEquals(Optional.of("Alpha"), edit.commandAlias());

		assertTrue(edit.edit("bad\u0007alias"));
		assertFalse(edit.canSubmit());
		assertTrue(edit.commandAlias().isEmpty());
	}

	@Test
	void samePublicationPreservesDraftAndNewAliasRevisionConfirmsOrRebasesIt() {
		UUID network = UUID.randomUUID();
		LogisticsAliasEditState edit = new LogisticsAliasEditState();
		edit.select(network, 4, Optional.of("Alpha"));
		edit.edit("Beta");

		edit.select(network, 4, Optional.of("Alpha"));
		assertEquals("Beta", edit.text());
		assertTrue(edit.canSubmit());

		edit.select(network, 5, Optional.of("Beta"));
		assertEquals("Beta", edit.text());
		assertFalse(edit.canSubmit());

		edit.edit("Gamma");
		edit.select(network, 6, Optional.of("Server Value"));
		assertEquals("Server Value", edit.text());
		assertFalse(edit.canSubmit());
	}
}
