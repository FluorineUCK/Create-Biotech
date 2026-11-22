package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

class LibraryCoreItemDataTest {
	private static final String ROOT_KEY = "create_biotech:pattern_core";
	private static final UUID LIBRARY_ID = UUID.fromString(
		"b70173fb-4fe1-466e-bcca-d607300b6970");

	@BeforeAll
	static void bootstrap() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		markMinecraftBootstrapped();
	}

	@Test
	void configuredItemRoundTripsExactFrequencyPriorityAndIdentity() {
		LibraryCoreItemData data = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Library-A"), -20, LIBRARY_ID, 4);

		assertEquals(data, LibraryCoreItemData.load(data.save()).orElseThrow());
		assertEquals(Set.of(ROOT_KEY), data.save().getAllKeys());
		assertEquals(Set.of("Configured", "Frequency", "LibraryPriority",
			"PreservedLibraryId", "Revision"), payload(data.save()).getAllKeys());
	}

	@Test
	void unconfiguredPayloadAndOptionalIdentityShapeRoundTripExactly() {
		LibraryCoreItemData unconfigured = LibraryCoreItemData.unconfigured();
		LibraryCoreItemData withoutIdentity = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Literal-A"), Integer.MAX_VALUE, Optional.empty(), 9);

		assertEquals(unconfigured,
			LibraryCoreItemData.load(unconfigured.save()).orElseThrow());
		assertEquals(withoutIdentity,
			LibraryCoreItemData.load(withoutIdentity.save()).orElseThrow());
		assertEquals(Set.of("Configured", "Frequency", "LibraryPriority", "Revision"),
			payload(withoutIdentity.save()).getAllKeys());
	}

	@Test
	void configuredItemsRejectEmptyAndWhitespaceOnlyFrequencies() {
		for (String blank : List.of("", " ", "\t\r\n")) {
			assertThrows(IllegalArgumentException.class,
				() -> LibraryCoreItemData.configured(CommunicationFrequency.of(blank),
					0, LIBRARY_ID, 0), () -> "factory accepted " + blank.length());

			CompoundTag contradictory = new LibraryCoreItemData(false,
				CommunicationFrequency.of(blank), 0, Optional.empty(), 0).save();
			payload(contradictory).putBoolean("Configured", true);
			assertTrue(LibraryCoreItemData.load(contradictory).isEmpty(),
				() -> "codec accepted " + blank.length());
		}
	}

	@Test
	void priorityUsesTheFullSignedIntegerRange() {
		LibraryCoreItemData minimum = LibraryCoreItemData.configured(
			CommunicationFrequency.of("minimum"), Integer.MIN_VALUE, LIBRARY_ID, 0);
		LibraryCoreItemData maximum = LibraryCoreItemData.configured(
			CommunicationFrequency.of("maximum"), Integer.MAX_VALUE, LIBRARY_ID,
			Long.MAX_VALUE);

		assertEquals(Integer.MIN_VALUE,
			LibraryCoreItemData.load(minimum.save()).orElseThrow().libraryPriority());
		assertEquals(Integer.MAX_VALUE,
			LibraryCoreItemData.load(maximum.save()).orElseThrow().libraryPriority());
	}

	@Test
	void malformedOrDuplicateIdentityPayloadFailsClosed() {
		CompoundTag unknownKey = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Library-A"), 0, LIBRARY_ID, 0).save();
		payload(unknownKey).putUUID("LibraryId", LIBRARY_ID);
		assertTrue(LibraryCoreItemData.load(unknownKey).isEmpty());

		CompoundTag malformedIdentity = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Library-A"), 0, LIBRARY_ID, 0).save();
		payload(malformedIdentity).put("PreservedLibraryId",
			new IntArrayTag(new int[] { 1, 2, 3 }));
		assertTrue(LibraryCoreItemData.load(malformedIdentity).isEmpty());

		assertThrows(IllegalArgumentException.class,
			() -> LibraryCoreItemData.configured(
				CommunicationFrequency.of("x".repeat(26)), 0, LIBRARY_ID, 0));
	}

	@Test
	void malformedFlagsTypesStateAndRevisionFailClosed() {
		CompoundTag nonCanonicalFlag = LibraryCoreItemData.unconfigured().save();
		payload(nonCanonicalFlag).putByte("Configured", (byte) 2);
		assertTrue(LibraryCoreItemData.load(nonCanonicalFlag).isEmpty());

		CompoundTag wrongPriorityType = LibraryCoreItemData.unconfigured().save();
		payload(wrongPriorityType).putLong("LibraryPriority", 0);
		assertTrue(LibraryCoreItemData.load(wrongPriorityType).isEmpty());

		CompoundTag negativeRevision = LibraryCoreItemData.unconfigured().save();
		payload(negativeRevision).putLong("Revision", -1);
		assertTrue(LibraryCoreItemData.load(negativeRevision).isEmpty());

		CompoundTag configuredStateOnUnconfigured = LibraryCoreItemData.unconfigured().save();
		payload(configuredStateOnUnconfigured).putString("Frequency", "active");
		assertTrue(LibraryCoreItemData.load(configuredStateOnUnconfigured).isEmpty());

		CompoundTag identityOnUnconfigured = LibraryCoreItemData.unconfigured().save();
		payload(identityOnUnconfigured).putUUID("PreservedLibraryId", LIBRARY_ID);
		assertTrue(LibraryCoreItemData.load(identityOnUnconfigured).isEmpty());

		CompoundTag missingFrequency = LibraryCoreItemData.unconfigured().save();
		payload(missingFrequency).remove("Frequency");
		assertTrue(LibraryCoreItemData.load(missingFrequency).isEmpty());

		CompoundTag wrongRootType = new CompoundTag();
		wrongRootType.putString(ROOT_KEY, "not-a-compound");
		assertTrue(LibraryCoreItemData.load(wrongRootType).isEmpty());

		assertThrows(IllegalArgumentException.class,
			() -> new LibraryCoreItemData(false, CommunicationFrequency.of("active"),
				0, Optional.empty(), 0));
		assertThrows(IllegalArgumentException.class,
			() -> new LibraryCoreItemData(false, CommunicationFrequency.UNCONFIGURED,
				1, Optional.of(LIBRARY_ID), 0));
		assertThrows(IllegalArgumentException.class,
			() -> LibraryCoreItemData.configured(CommunicationFrequency.of("active"),
				0, LIBRARY_ID, -1));
	}

	@Test
	void editingDefaultsOnlyAnAbsentOwnedPayloadAndRejectsMalformedOwnedData() {
		ItemStack plain = new ItemStack(Items.PAPER);
		assertEquals(LibraryCoreItemData.unconfigured(),
			LibraryCoreItemData.readForPlacement(plain).orElseThrow());

		ItemStack unrelated = new ItemStack(Items.PAPER);
		CustomData.update(DataComponents.CUSTOM_DATA, unrelated,
			root -> root.putString("OtherMod", "kept"));
		assertEquals(LibraryCoreItemData.unconfigured(),
			LibraryCoreItemData.readForPlacement(unrelated).orElseThrow());

		ItemStack malformed = new ItemStack(Items.PAPER);
		CustomData.update(DataComponents.CUSTOM_DATA, malformed,
			root -> root.putString(ROOT_KEY, "wrong-type"));
		assertTrue(LibraryCoreItemData.readForPlacement(malformed).isEmpty());
	}

	@Test
	void dataComponentCodecOwnsOnlyItsNamespacedCompound() {
		ItemStack stack = new ItemStack(Items.PAPER);
		CompoundTag foreignData = new CompoundTag();
		foreignData.putString("another_mod:payload", "retained");
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(foreignData));
		LibraryCoreItemData data = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Case-Sensitive"), 17, LIBRARY_ID, 3);

		LibraryCoreItemData.write(stack, data);

		CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
			.copyTag();
		assertEquals("retained", root.getString("another_mod:payload"));
		assertEquals(data, LibraryCoreItemData.read(stack).orElseThrow());
	}

	private static CompoundTag payload(CompoundTag root) {
		return root.getCompound(ROOT_KEY);
	}

	private static void markMinecraftBootstrapped() {
		try {
			Field bootstrapped = net.minecraft.server.Bootstrap.class
				.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null)
				return;
			Object empty = type.getMethod("of", List.class, List.class, List.class,
				List.class, java.util.Map.class).invoke(null, List.of(), List.of(),
					List.of(), List.of(), java.util.Map.of());
			if (empty == null)
				throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
