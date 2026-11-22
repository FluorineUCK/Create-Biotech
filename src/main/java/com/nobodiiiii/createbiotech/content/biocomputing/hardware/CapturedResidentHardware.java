package com.nobodiiiii.createbiotech.content.biocomputing.hardware;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Reads the owner's frozen resident description, never an entity instance or its production profile. */
public final class CapturedResidentHardware {
	private static final ResourceLocation VILLAGER = ResourceLocation.parse("minecraft:villager");


	private static final ResourceLocation NONE = ResourceLocation.parse("minecraft:none");
	private final Supplier<ItemStack> resident;
	private CustomData cachedData;
	private Reading cachedReading;

	public CapturedResidentHardware(Supplier<ItemStack> resident) { this.resident = Objects.requireNonNull(resident); }

	/** The same strict snapshot interpretation used by execution, independent of configured capacities. */
	public static Optional<ResidentHardwareIdentity> readIdentity(ItemStack snapshot) {
		var data = snapshot.get(DataComponents.CUSTOM_DATA);
		return snapshot.isEmpty() || data == null ? Optional.empty() : parse(data.copyTag()).resident();
	}

	public HardwareSelection select(BiocomputingConfig config, boolean loaded) {
		var stack = Objects.requireNonNull(resident.get());
		if (stack.isEmpty()) {
			cachedData = null; cachedReading = null;
			return unavailable(HardwareSelection.Status.NO_RESIDENT, config, loaded);
		}
		var data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			cachedData = null; cachedReading = null;
			return unavailable(HardwareSelection.Status.INVALID_RESIDENT, config, loaded);
		}
		// CustomData is immutable; component replacement on the same ItemStack must invalidate this cache.
		if (data != cachedData) { cachedReading = parse(data.copyTag()); cachedData = data; }
		return cachedReading.resident().map(identity -> config.select(identity, loaded))
			.orElseGet(() -> unavailable(cachedReading.status(), config, loaded));
	}

	private static Reading parse(CompoundTag item) {
		if (!item.contains("CapturedEntity", Tag.TAG_COMPOUND)) return invalid();
		var entity = item.getCompound("CapturedEntity");
		var type = identifier(entity, "id");
		if (type == null) return invalid();
		if (!type.equals(VILLAGER) && !ResidentModels.ENTITY_IDS.contains(type))
			return new Reading(HardwareSelection.Status.UNSUPPORTED_RESIDENT, Optional.empty());
		// Vanilla's absent Age / IsBaby fields mean adult; wrong present types never become default values.
		if (entity.contains("Age") && (!entity.contains("Age", Tag.TAG_INT) || entity.getInt("Age") < 0)) return invalid();
		if (entity.contains("IsBaby") && (!entity.contains("IsBaby", Tag.TAG_BYTE) || entity.getByte("IsBaby") != 0)) return invalid();
		ResourceLocation profession = NONE;
		int level = 1;
		if (type.equals(VILLAGER)) {
			if (!entity.contains("VillagerData", Tag.TAG_COMPOUND)) return invalid();
			var data = entity.getCompound("VillagerData");
			profession = identifier(data, "profession");
			if (profession == null || !data.contains("level", Tag.TAG_INT)) return invalid();
			level = data.getInt("level");
			if (level < 1 || level > 5) return invalid();
		}
		return new Reading(HardwareSelection.Status.READY, Optional.of(new ResidentHardwareIdentity(type, profession, level)));
	}
	private static ResourceLocation identifier(CompoundTag data, String key) {
		if (!data.contains(key, Tag.TAG_STRING)) return null;
		String text = data.getString(key);
		var identifier = text.indexOf(':') > 0 ? ResourceLocation.tryParse(text) : null;
		return identifier != null && !identifier.getPath().isEmpty() ? identifier : null;
	}
	private static Reading invalid() { return new Reading(HardwareSelection.Status.INVALID_RESIDENT, Optional.empty()); }
	private static HardwareSelection unavailable(HardwareSelection.Status status, BiocomputingConfig config, boolean loaded) {
		return new HardwareSelection(status, Optional.empty(), "", config.current(loaded));
	}
	private record Reading(HardwareSelection.Status status, Optional<ResidentHardwareIdentity> resident) {}
}
