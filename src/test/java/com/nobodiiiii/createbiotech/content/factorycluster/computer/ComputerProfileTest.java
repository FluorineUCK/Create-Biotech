package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import net.minecraft.nbt.CompoundTag;

class ComputerProfileTest {
	@ParameterizedTest
	@CsvSource({
		"VILLAGER,1,1,1", "VILLAGER,2,2,1", "VILLAGER,3,3,2",
		"VILLAGER,4,4,2", "VILLAGER,5,4,2",
		"LIBRARIAN,1,4,2", "LIBRARIAN,2,6,2", "LIBRARIAN,3,8,3",
		"LIBRARIAN,4,12,3", "LIBRARIAN,5,16,4",
		"NITWIT,1,1,0", "WANDERING_TRADER,1,2,1",
		"ZOMBIE_VILLAGER,1,0,0"
	})
	void approvedTable(NodeKind kind, int level, int slots, int depth) {
		ComputerProfile profile = ComputerProfile.forKind(kind, level, 64).orElseThrow();

		assertEquals(slots, profile.slots());
		assertEquals(depth, profile.depth());
		assertEquals(kind == NodeKind.NITWIT, profile.loopAbort());
		assertEquals(kind == NodeKind.WANDERING_TRADER ? 64 : 0,
			profile.patternRangeBonus());
		assertEquals(profile, ComputerProfile.load(profile.save()).orElseThrow());
	}

	@Test
	void codecRejectsEveryIllegalKindCombination() {
		assertFalse(load("VILLAGER", 16, 4, false, 0).isPresent());
		assertFalse(load("LIBRARIAN", 4, 2, true, 0).isPresent());
		assertFalse(load("NITWIT", 1, 0, false, 0).isPresent());
		assertFalse(load("NITWIT", 1, 0, true, 1).isPresent());
		assertFalse(load("WANDERING_TRADER", 2, 1, false, 4097).isPresent());
		assertFalse(load("ZOMBIE_VILLAGER", 1, 0, false, 0).isPresent());
		assertFalse(load("ZOMBIE_VILLAGER", 0, 1, false, 0).isPresent());
	}

	@Test
	void constructorsRejectInvalidLevelAndBonusInsteadOfClamping() {
		assertTrue(ComputerProfile.forKind(NodeKind.VILLAGER, 0, 64).isEmpty());
		assertTrue(ComputerProfile.forKind(NodeKind.LIBRARIAN, 6, 64).isEmpty());
		assertTrue(ComputerProfile.forKind(NodeKind.NITWIT, 0, 64).isEmpty());
		assertTrue(ComputerProfile.forKind(NodeKind.WANDERING_TRADER, 2, 64).isEmpty());
		assertTrue(ComputerProfile.forKind(NodeKind.ZOMBIE_VILLAGER, 2, 64).isEmpty());
		assertTrue(ComputerProfile.forKind(NodeKind.WANDERING_TRADER, 1, -1).isEmpty());
		assertTrue(ComputerProfile.forKind(NodeKind.WANDERING_TRADER, 1, 4097).isEmpty());
	}

	@Test
	void codecRejectsWrongTypesMissingExtraUnknownAndNonCanonicalValuesWithoutMutation() {
		CompoundTag valid = ComputerProfile.forKind(NodeKind.VILLAGER, 4, 64).orElseThrow().save();

		CompoundTag wrongType = valid.copy();
		wrongType.putString("Slots", "4");
		assertRejectedWithoutMutation(wrongType);
		CompoundTag missing = valid.copy();
		missing.remove("Depth");
		assertRejectedWithoutMutation(missing);
		CompoundTag extra = valid.copy();
		extra.putInt("Extra", 1);
		assertRejectedWithoutMutation(extra);
		CompoundTag unknown = valid.copy();
		unknown.putString("Kind", "GOLEM");
		assertRejectedWithoutMutation(unknown);
		CompoundTag nonCanonicalBoolean = valid.copy();
		nonCanonicalBoolean.putByte("LoopAbort", (byte) 2);
		assertRejectedWithoutMutation(nonCanonicalBoolean);
		CompoundTag negative = valid.copy();
		negative.putInt("PatternRangeBonus", -1);
		assertRejectedWithoutMutation(negative);
		CompoundTag unsupportedVersion = valid.copy();
		unsupportedVersion.putInt("Version", 2);
		assertRejectedWithoutMutation(unsupportedVersion);
	}

	private static java.util.Optional<ComputerProfile> load(String kind, int slots, int depth,
		boolean loopAbort, int patternRangeBonus) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", 1);
		tag.putString("Kind", kind);
		tag.putInt("Slots", slots);
		tag.putInt("Depth", depth);
		tag.putBoolean("LoopAbort", loopAbort);
		tag.putInt("PatternRangeBonus", patternRangeBonus);
		return ComputerProfile.load(tag);
	}

	private static void assertRejectedWithoutMutation(CompoundTag tag) {
		CompoundTag before = tag.copy();
		assertTrue(ComputerProfile.load(tag).isEmpty());
		assertEquals(before, tag);
	}
}