package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;

import net.minecraft.nbt.CompoundTag;

class FactoryPanelBlockItemTest {
	private static final UUID FIRST_NETWORK =
		UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final UUID SECOND_NETWORK =
		UUID.fromString("30000000-0000-0000-0000-000000000002");

	@Test
	void bindingTagUpdatesPreserveUnrelatedBlockEntityDataAndNormalizeIds() {
		CompoundTag blockEntityData = new CompoundTag();
		blockEntityData.putString("Unrelated", "kept");
		blockEntityData.putUUID("PanelId", UUID.randomUUID());
		blockEntityData.putUUID("ClusterId", UUID.randomUUID());

		FactoryPanelBlockItem.writeBindingsToTag(blockEntityData, List.of(
			new LogisticsBinding(FIRST_NETWORK, "First"),
			new LogisticsBinding(FIRST_NETWORK, "Ignored duplicate"),
			new LogisticsBinding(SECOND_NETWORK, "Second")));

		assertEquals("kept", blockEntityData.getString("Unrelated"));
		assertEquals(List.of(FIRST_NETWORK, SECOND_NETWORK),
			FactoryPanelBlockItem.readBindingsFromTag(blockEntityData).stream()
				.map(LogisticsBinding::logisticsId).toList());

		FactoryPanelBlockItem.clearBindingsFromTag(blockEntityData);

		assertFalse(blockEntityData.contains("LogisticsBindings"));
		assertEquals("kept", blockEntityData.getString("Unrelated"));
		assertTrueIdentitiesRemain(blockEntityData);
	}

	private static void assertTrueIdentitiesRemain(CompoundTag tag) {
		org.junit.jupiter.api.Assertions.assertTrue(tag.hasUUID("PanelId"));
		org.junit.jupiter.api.Assertions.assertTrue(tag.hasUUID("ClusterId"));
	}
}
