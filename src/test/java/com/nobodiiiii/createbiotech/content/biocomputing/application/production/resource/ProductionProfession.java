package com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/** Historical production role and canonical saved slot domain, not a generic hardware preset. */
public enum ProductionProfession {
	ARMORER,
	BUTCHER,
	CARTOGRAPHER,
	CLERIC,
	FARMER,
	FISHERMAN,
	FLETCHER,
	LEATHERWORKER,
	LIBRARIAN,
	MASON,
	SHEPHERD,
	TOOLSMITH,
	WEAPONSMITH,
	UNEMPLOYED,
	NITWIT,
	WANDERING_TRADER,
	ZOMBIE_VILLAGER;

	private static final Map<ProductionProfession, List<Integer>> PROCESS_SLOTS_BY_LEVEL = Map.ofEntries(
		Map.entry(ARMORER, List.of(2, 3, 5, 7, 9)),
		Map.entry(BUTCHER, List.of(2, 3, 4, 5, 6)),
		Map.entry(CARTOGRAPHER, List.of(2, 3, 5, 7, 9)),
		Map.entry(CLERIC, List.of(2, 3, 4, 5, 6)),
		Map.entry(FARMER, List.of(2, 3, 4, 5, 6)),
		Map.entry(FISHERMAN, List.of(2, 3, 4, 5, 6)),
		Map.entry(FLETCHER, List.of(2, 3, 5, 7, 9)),
		Map.entry(LEATHERWORKER, List.of(2, 3, 4, 5, 6)),
		Map.entry(LIBRARIAN, List.of(4, 6, 8, 12, 16)),
		Map.entry(MASON, List.of(2, 3, 5, 7, 9)),
		Map.entry(SHEPHERD, List.of(2, 3, 4, 5, 6)),
		Map.entry(TOOLSMITH, List.of(2, 3, 5, 7, 9)),
		Map.entry(WEAPONSMITH, List.of(2, 3, 5, 7, 9)),
		Map.entry(UNEMPLOYED, List.of(2, 2, 2, 2, 2)),
		Map.entry(NITWIT, List.of(1, 1, 1, 1, 1)),
		Map.entry(WANDERING_TRADER, List.of(ProductionResourceProfile.WANDERING_TRADER_PROCESS_SLOTS,
			ProductionResourceProfile.WANDERING_TRADER_PROCESS_SLOTS,
			ProductionResourceProfile.WANDERING_TRADER_PROCESS_SLOTS,
			ProductionResourceProfile.WANDERING_TRADER_PROCESS_SLOTS,
			ProductionResourceProfile.WANDERING_TRADER_PROCESS_SLOTS)),
		Map.entry(ZOMBIE_VILLAGER, List.of(0, 0, 0, 0, 0))
	);

	public OptionalInt processSlotsAtLevel(int level) {
		if (level < 1 || level > 5) return OptionalInt.empty();
		return OptionalInt.of(PROCESS_SLOTS_BY_LEVEL.get(this).get(level - 1));
	}

	public boolean acceptsProcessSlots(int processSlots) {
		return PROCESS_SLOTS_BY_LEVEL.get(this).contains(processSlots);
	}
}
