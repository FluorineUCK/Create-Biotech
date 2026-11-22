package com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Historical production reservation profile, never generic Computer RAM or execution resources. */
public record ProductionResourceProfile(ProductionProfession profession, int processSlots,
	Set<ProductionCapability> capabilities, int listenerRangeBonus) {
	public static final int WANDERING_TRADER_PROCESS_SLOTS = 2;
	private static final int MAX_LISTENER_RANGE_BONUS = 4096;

	public ProductionResourceProfile {
		Objects.requireNonNull(profession, "profession");
		capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
		if (!profession.acceptsProcessSlots(processSlots)
			|| !capabilities.equals(capabilitiesFor(profession))
			|| listenerRangeBonus < 0 || listenerRangeBonus > MAX_LISTENER_RANGE_BONUS
			|| (profession != ProductionProfession.WANDERING_TRADER && listenerRangeBonus != 0))
			throw new IllegalArgumentException("non-canonical computer profile");
	}

	public boolean supports(ProductionCapability capability) {
		return capabilities.contains(capability);
	}

	public static Optional<ProductionResourceProfile> forProfession(ProductionProfession profession, int level,
		int traderRangeBonus) {
		if (profession == null || traderRangeBonus < 0
			|| traderRangeBonus > MAX_LISTENER_RANGE_BONUS) return Optional.empty();
		OptionalInt slots = profession.processSlotsAtLevel(level);
		if (slots.isEmpty()) return Optional.empty();
		int listenerRangeBonus = profession == ProductionProfession.WANDERING_TRADER
			? traderRangeBonus : 0;
		return Optional.of(new ProductionResourceProfile(profession, slots.getAsInt(),
			capabilitiesFor(profession), listenerRangeBonus));
	}

	private static Set<ProductionCapability> capabilitiesFor(ProductionProfession profession) {
		return switch (profession) {
			case CLERIC -> Set.of(ProductionCapability.ALCHEMIST);
			case WANDERING_TRADER -> Set.of(ProductionCapability.EXTENDED_RADIO);
			default -> Set.of();
		};
	}

}
