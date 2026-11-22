package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfileCodecs;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

class ComputerProfileTest {
	@ParameterizedTest(name = "{0} level {1} has {2} process slots")
	@MethodSource("approvedCapacityTable")
	void everyApprovedProfessionUsesItsExactCapacityCurve(ProductionProfession profession,
		int level, int expectedSlots) {
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(profession, level, 64)
			.orElseThrow();

		assertEquals(profession, profile.profession());
		assertEquals(expectedSlots, profile.processSlots());
		assertEquals(profession == ProductionProfession.WANDERING_TRADER ? 64 : 0,
			profile.listenerRangeBonus());
		assertEquals(profile, ProductionResourceProfileCodecs.load(ProductionResourceProfileCodecs.save(profile)).orElseThrow());
	}

	@Test
	void onlyApprovedProfessionsExposeSpecialCapabilitiesWithoutReservingOrdinarySlots() {
		ProductionResourceProfile cleric = profile(ProductionProfession.CLERIC);
		ProductionResourceProfile trader = profile(ProductionProfession.WANDERING_TRADER);

		assertEquals(Set.of(ProductionCapability.ALCHEMIST), cleric.capabilities());
		assertEquals(2, cleric.processSlots());
		assertEquals(Set.of(ProductionCapability.EXTENDED_RADIO), trader.capabilities());
		assertEquals(2, trader.processSlots());
		for (ProductionProfession profession : ProductionProfession.values()) {
			ProductionResourceProfile profile = profile(profession);
			if (profession != ProductionProfession.CLERIC)
				assertFalse(profile.supports(ProductionCapability.ALCHEMIST), profession.name());
			if (profession != ProductionProfession.WANDERING_TRADER)
				assertFalse(profile.supports(ProductionCapability.EXTENDED_RADIO), profession.name());
		}
	}

	@Test
	void profileOwnsAnImmutableCapabilitySet() {
		EnumSet<ProductionCapability> capabilities = EnumSet.of(ProductionCapability.ALCHEMIST);
		ProductionResourceProfile profile = new ProductionResourceProfile(ProductionProfession.CLERIC, 2,
			capabilities, 0);

		capabilities.clear();
		assertTrue(profile.supports(ProductionCapability.ALCHEMIST));
		assertThrows(UnsupportedOperationException.class, profile.capabilities()::clear);
	}

	@Test
	void codecWritesOnlyTheCurrentProfileSchema() {
		CompoundTag current = ProductionResourceProfileCodecs.save(profile(ProductionProfession.CLERIC));
		assertEquals(Set.of("Version", "Profession", "ProcessSlots", "Capabilities",
			"ListenerRangeBonus"), current.getAllKeys());
		assertEquals(List.of("ALCHEMIST"), current.getList("Capabilities", Tag.TAG_STRING)
			.stream().map(Tag::getAsString).toList());

	}

	@Test
	void codecRejectsMalformedOrNonCanonicalCurrentProfilesWithoutMutation() {
		CompoundTag valid = ProductionResourceProfileCodecs.save(profile(ProductionProfession.LIBRARIAN));

		CompoundTag wrongType = valid.copy();
		wrongType.putString("ProcessSlots", "4");
		assertRejectedWithoutMutation(wrongType);
		CompoundTag missing = valid.copy();
		missing.remove("Capabilities");
		assertRejectedWithoutMutation(missing);
		CompoundTag extra = valid.copy();
		extra.putInt("Extra", 1);
		assertRejectedWithoutMutation(extra);
		CompoundTag unknown = valid.copy();
		unknown.putString("Profession", "ARCHIVIST");
		assertRejectedWithoutMutation(unknown);
		CompoundTag nonCanonicalSlots = valid.copy();
		nonCanonicalSlots.putInt("ProcessSlots", 15);
		assertRejectedWithoutMutation(nonCanonicalSlots);
		CompoundTag unsupportedVersion = valid.copy();
		unsupportedVersion.putInt("Version", 2);
		assertRejectedWithoutMutation(unsupportedVersion);
	}

	@Test
	void factoriesRejectInvalidLevelsAndListenerBonusesInsteadOfClamping() {
		assertTrue(ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 0, 64).isEmpty());
		assertTrue(ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 6, 64).isEmpty());
		assertTrue(ProductionResourceProfile.forProfession(ProductionProfession.WANDERING_TRADER, 1, -1).isEmpty());
		assertTrue(ProductionResourceProfile.forProfession(ProductionProfession.WANDERING_TRADER, 1, 4097).isEmpty());
	}

	private static ProductionResourceProfile profile(ProductionProfession profession) {
		return ProductionResourceProfile.forProfession(profession, 1, 64).orElseThrow();
	}

	private static Stream<Arguments> approvedCapacityTable() {
		return Stream.of(
			new CapacityCurve(ProductionProfession.ARMORER, 2, 3, 5, 7, 9),
			new CapacityCurve(ProductionProfession.BUTCHER, 2, 3, 4, 5, 6),
			new CapacityCurve(ProductionProfession.CARTOGRAPHER, 2, 3, 5, 7, 9),
			new CapacityCurve(ProductionProfession.CLERIC, 2, 3, 4, 5, 6),
			new CapacityCurve(ProductionProfession.FARMER, 2, 3, 4, 5, 6),
			new CapacityCurve(ProductionProfession.FISHERMAN, 2, 3, 4, 5, 6),
			new CapacityCurve(ProductionProfession.FLETCHER, 2, 3, 5, 7, 9),
			new CapacityCurve(ProductionProfession.LEATHERWORKER, 2, 3, 4, 5, 6),
			new CapacityCurve(ProductionProfession.LIBRARIAN, 4, 6, 8, 12, 16),
			new CapacityCurve(ProductionProfession.MASON, 2, 3, 5, 7, 9),
			new CapacityCurve(ProductionProfession.SHEPHERD, 2, 3, 4, 5, 6),
			new CapacityCurve(ProductionProfession.TOOLSMITH, 2, 3, 5, 7, 9),
			new CapacityCurve(ProductionProfession.WEAPONSMITH, 2, 3, 5, 7, 9),
			new CapacityCurve(ProductionProfession.UNEMPLOYED, 2, 2, 2, 2, 2),
			new CapacityCurve(ProductionProfession.NITWIT, 1, 1, 1, 1, 1),
			new CapacityCurve(ProductionProfession.WANDERING_TRADER, 2, 2, 2, 2, 2),
			new CapacityCurve(ProductionProfession.ZOMBIE_VILLAGER, 0, 0, 0, 0, 0)
		).flatMap(curve -> IntStream.rangeClosed(1, 5)
			.mapToObj(level -> Arguments.of(curve.profession(), level, curve.slots()[level - 1])));
	}

	private static void assertRejectedWithoutMutation(CompoundTag tag) {
		CompoundTag before = tag.copy();
		assertTrue(ProductionResourceProfileCodecs.load(tag).isEmpty());
		assertEquals(before, tag);
	}

	private record CapacityCurve(ProductionProfession profession, int... slots) {}
}
