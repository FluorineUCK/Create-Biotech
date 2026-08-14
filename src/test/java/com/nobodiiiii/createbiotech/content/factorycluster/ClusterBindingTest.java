package com.nobodiiiii.createbiotech.content.factorycluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

class ClusterBindingTest {
	private static final UUID CLUSTER =
		UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID AUTHORITY =
		UUID.fromString("00000000-0000-0000-0000-000000000002");

	@Test
	void bindingStateRoundTripsWithRevisionAndAuthority() {
		ClusterBinding expected = new ClusterBinding(CLUSTER, 17,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, AUTHORITY),
			bindings(2));

		assertEquals(expected, ClusterBinding.tryLoad(expected.save()).orElseThrow());
	}

	@Test
	void thirtyTwoBindingsAreAcceptedButThirtyThreeAreRejected() {
		assertEquals(ClusterBinding.MAX_BINDINGS,
			new ClusterBinding(CLUSTER, 0, null,
				bindings(ClusterBinding.MAX_BINDINGS)).logisticsBindings().size());
		assertThrows(IllegalArgumentException.class,
			() -> new ClusterBinding(CLUSTER, 0, null,
				bindings(ClusterBinding.MAX_BINDINGS + 1)));
	}

	@Test
	void strictDecodeRejectsOversizedOrDuplicatePersistedLists() {
		ClusterBinding state = new ClusterBinding(CLUSTER, 3,
			new ClusterAuthority(ClusterMemberType.PANEL, AUTHORITY), bindings(2));
		CompoundTag oversized = state.save();
		ListTag oversizedBindings = new ListTag();
		bindings(ClusterBinding.MAX_BINDINGS + 1)
			.forEach(binding -> oversizedBindings.add(binding.save()));
		oversized.put("LogisticsBindings", oversizedBindings);
		assertTrue(ClusterBinding.tryLoad(oversized).isEmpty());

		CompoundTag duplicated = state.save();
		ListTag duplicateBindings = new ListTag();
		LogisticsBinding duplicate = bindings(1).getFirst();
		duplicateBindings.add(duplicate.save());
		duplicateBindings.add(duplicate.save());
		duplicated.put("LogisticsBindings", duplicateBindings);
		assertTrue(ClusterBinding.tryLoad(duplicated).isEmpty());
	}

	@Test
	void strictDecodeRejectsMalformedSchemaInsteadOfDefaulting() {
		ClusterBinding state = new ClusterBinding(CLUSTER, 3,
			new ClusterAuthority(ClusterMemberType.PANEL, AUTHORITY), bindings(1));
		List<CompoundTag> malformed = new ArrayList<>();

		CompoundTag missingVersion = state.save();
		missingVersion.remove("Version");
		malformed.add(missingVersion);
		CompoundTag wrongRevision = state.save();
		wrongRevision.putString("Revision", "3");
		malformed.add(wrongRevision);
		CompoundTag negativeRevision = state.save();
		negativeRevision.putLong("Revision", -1);
		malformed.add(negativeRevision);
		CompoundTag wrongAuthority = state.save();
		wrongAuthority.putString("Authority", "panel");
		malformed.add(wrongAuthority);
		CompoundTag wrongBindings = state.save();
		wrongBindings.putString("LogisticsBindings", "not a list");
		malformed.add(wrongBindings);

		malformed.forEach(tag -> assertFalse(ClusterBinding.tryLoad(tag).isPresent()));
	}

	private static List<LogisticsBinding> bindings(int count) {
		List<LogisticsBinding> bindings = new ArrayList<>();
		for (int index = 0; index < count; index++)
			bindings.add(new LogisticsBinding(new UUID(0, index + 100L), "network-" + index));
		return List.copyOf(bindings);
	}
}
