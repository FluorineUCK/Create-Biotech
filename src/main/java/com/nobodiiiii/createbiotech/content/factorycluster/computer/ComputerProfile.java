package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.Optional;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.WanderingTrader;

public record ComputerProfile(NodeKind kind, int slots, int depth, boolean loopAbort,
	int patternRangeBonus) {
	private static final int VERSION = 1;
	private static final int MAX_TRADER_RANGE_BONUS = 4096;
	private static final Set<String> KEYS = Set.of("Version", "Kind", "Slots", "Depth",
		"LoopAbort", "PatternRangeBonus");

	public static Optional<ComputerProfile> fromEntity(Entity entity, int traderRangeBonus) {
		if (entity instanceof Villager villager) {
			if (villager.isBaby()) return Optional.empty();
			VillagerData data = villager.getVillagerData();
			NodeKind kind = data.getProfession() == VillagerProfession.LIBRARIAN
				? NodeKind.LIBRARIAN
				: data.getProfession() == VillagerProfession.NITWIT ? NodeKind.NITWIT : NodeKind.VILLAGER;
			return forKind(kind, data.getLevel(), traderRangeBonus);
		}
		if (entity instanceof WanderingTrader trader)
			return trader.isBaby() ? Optional.empty()
				: forKind(NodeKind.WANDERING_TRADER, 1, traderRangeBonus);
		if (entity instanceof ZombieVillager zombieVillager)
			return zombieVillager.isBaby() ? Optional.empty()
				: forKind(NodeKind.ZOMBIE_VILLAGER, 1, traderRangeBonus);
		return Optional.empty();
	}

	public static Optional<ComputerProfile> forKind(NodeKind kind, int level, int traderRangeBonus) {
		if (kind == null) return Optional.empty();
		return switch (kind) {
			case VILLAGER -> villagerProfile(kind, level, new int[][] {{1, 1}, {2, 1}, {3, 2}, {4, 2}, {4, 2}});
			case LIBRARIAN -> villagerProfile(kind, level,
				new int[][] {{4, 2}, {6, 2}, {8, 3}, {12, 3}, {16, 4}});
			case NITWIT -> level >= 1 && level <= 5
				? Optional.of(new ComputerProfile(kind, 1, 0, true, 0)) : Optional.empty();
			case WANDERING_TRADER -> level == 1 && traderRangeBonus >= 0
				&& traderRangeBonus <= MAX_TRADER_RANGE_BONUS
				? Optional.of(new ComputerProfile(kind, 2, 1, false, traderRangeBonus)) : Optional.empty();
			case ZOMBIE_VILLAGER -> level == 1
				? Optional.of(new ComputerProfile(kind, 0, 0, false, 0)) : Optional.empty();
		};
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", VERSION);
		tag.putString("Kind", kind.name());
		tag.putInt("Slots", slots);
		tag.putInt("Depth", depth);
		tag.putBoolean("LoopAbort", loopAbort);
		tag.putInt("PatternRangeBonus", patternRangeBonus);
		return tag;
	}

	public static Optional<ComputerProfile> load(CompoundTag tag) {
		if (tag == null || !tag.getAllKeys().equals(KEYS)
			|| !tag.contains("Version", Tag.TAG_INT) || !tag.contains("Kind", Tag.TAG_STRING)
			|| !tag.contains("Slots", Tag.TAG_INT) || !tag.contains("Depth", Tag.TAG_INT)
			|| !tag.contains("LoopAbort", Tag.TAG_BYTE)
			|| !tag.contains("PatternRangeBonus", Tag.TAG_INT) || tag.getInt("Version") != VERSION)
			return Optional.empty();
		byte loopAbort = tag.getByte("LoopAbort");
		if (loopAbort != 0 && loopAbort != 1) return Optional.empty();
		NodeKind kind;
		try {
			kind = NodeKind.valueOf(tag.getString("Kind"));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
		return matchingProfile(kind, tag.getInt("Slots"), tag.getInt("Depth"), loopAbort == 1,
			tag.getInt("PatternRangeBonus"));
	}

	private static Optional<ComputerProfile> villagerProfile(NodeKind kind, int level, int[][] table) {
		if (level < 1 || level > table.length) return Optional.empty();
		int[] values = table[level - 1];
		return Optional.of(new ComputerProfile(kind, values[0], values[1], false, 0));
	}

	private static Optional<ComputerProfile> matchingProfile(NodeKind kind, int slots, int depth,
		boolean loopAbort, int patternRangeBonus) {
		for (int level = 1; level <= 5; level++) {
			Optional<ComputerProfile> expected = forKind(kind, level, patternRangeBonus);
			if (expected.filter(profile -> profile.slots == slots && profile.depth == depth
				&& profile.loopAbort == loopAbort && profile.patternRangeBonus == patternRangeBonus).isPresent())
				return expected;
		}
		return Optional.empty();
	}
}
