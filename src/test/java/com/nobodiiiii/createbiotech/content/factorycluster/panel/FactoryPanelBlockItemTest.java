package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;

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

	@Test
	void thirtyThirdItemBindingIsRefusedWithoutChangingPersistedBindings() {
		CompoundTag blockEntityData = new CompoundTag();
		List<LogisticsBinding> maximum = bindings(ClusterBinding.MAX_BINDINGS);
		assertTrue(FactoryPanelBlockItem.writeBindingsToTag(blockEntityData, maximum));

		List<LogisticsBinding> oversized = new ArrayList<>(maximum);
		oversized.add(new LogisticsBinding(UUID.randomUUID(), "overflow"));

		assertFalse(FactoryPanelBlockItem.writeBindingsToTag(blockEntityData, oversized));
		assertEquals(maximum, FactoryPanelBlockItem.readBindingsFromTag(blockEntityData));
	}

	@Test
	void nonAuthorityConfiguredPanelCannotEditItsOfflineBindingReplica() {
		UUID panelId = UUID.randomUUID();
		CompoundTag blockEntityData = new CompoundTag();
		blockEntityData.putUUID("PanelId", panelId);
		ClusterBinding replica = new ClusterBinding(UUID.randomUUID(), 7,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
				UUID.randomUUID()), bindings(1));
		blockEntityData.put("BindingState", replica.save());

		assertFalse(FactoryPanelBlockItem.writeBindingsToTag(blockEntityData,
			bindings(2)));
		assertEquals(replica,
			ClusterBinding.tryLoad(blockEntityData.getCompound("BindingState"))
				.orElseThrow());
	}

	private static List<LogisticsBinding> bindings(int count) {
		List<LogisticsBinding> bindings = new ArrayList<>();
		for (int index = 0; index < count; index++)
			bindings.add(new LogisticsBinding(new UUID(0, index + 1L), "network-" + index));
		return List.copyOf(bindings);
	}

	private static void assertTrueIdentitiesRemain(CompoundTag tag) {
		org.junit.jupiter.api.Assertions.assertTrue(tag.hasUUID("PanelId"));
		org.junit.jupiter.api.Assertions.assertTrue(tag.hasUUID("ClusterId"));
	}
}
