package com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource;

import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.WanderingTrader;

/** Reads a resident into immutable production facts; never retains or mutates the entity. */
public final class ProductionResidentProfiles {
	private ProductionResidentProfiles() {}
	/** Temporary production application adapter; physical admission does not require this profile. */
	public static Optional<ProductionResourceProfile> fromHardwareIdentity(
		com.nobodiiiii.createbiotech.content.biocomputing.hardware.ResidentHardwareIdentity identity,
		int traderRangeBonus) {
		if (identity.entityType().equals(net.minecraft.resources.ResourceLocation.withDefaultNamespace("villager")))
			return net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION
				.getOptional(identity.profession())
				.flatMap(profession -> forVillagerProfession(profession, identity.level(), traderRangeBonus));
		if (identity.entityType().equals(net.minecraft.resources.ResourceLocation.withDefaultNamespace("wandering_trader")))
			return ProductionResourceProfile.forProfession(ProductionProfession.WANDERING_TRADER, 1, traderRangeBonus);
		if (identity.entityType().equals(net.minecraft.resources.ResourceLocation.withDefaultNamespace("zombie_villager")))
			return ProductionResourceProfile.forProfession(ProductionProfession.ZOMBIE_VILLAGER, 1, traderRangeBonus);
		return Optional.empty();
	}
	public static Optional<ProductionResourceProfile> fromEntity(Entity entity, int traderRangeBonus) {
		if (entity instanceof Villager villager) {
			if (villager.isBaby()) return Optional.empty();
			VillagerData data = villager.getVillagerData();
			return forVillagerProfession(data.getProfession(), data.getLevel(), traderRangeBonus);
		}
		if (entity instanceof WanderingTrader trader)
			return trader.isBaby() ? Optional.empty()
				: ProductionResourceProfile.forProfession(ProductionProfession.WANDERING_TRADER, 1, traderRangeBonus);
		if (entity instanceof ZombieVillager zombieVillager)
			return zombieVillager.isBaby() ? Optional.empty()
				: ProductionResourceProfile.forProfession(ProductionProfession.ZOMBIE_VILLAGER, 1, traderRangeBonus);
		return Optional.empty();
	}

	public static Optional<ProductionResourceProfile> forVillagerProfession(VillagerProfession profession,
		int level, int traderRangeBonus) {
		return mapVillagerProfession(profession)
			.flatMap(mapped -> ProductionResourceProfile.forProfession(mapped, level, traderRangeBonus));
	}

	private static Optional<ProductionProfession> mapVillagerProfession(
		VillagerProfession profession) {
		if (profession == VillagerProfession.ARMORER) return Optional.of(ProductionProfession.ARMORER);
		if (profession == VillagerProfession.BUTCHER) return Optional.of(ProductionProfession.BUTCHER);
		if (profession == VillagerProfession.CARTOGRAPHER) return Optional.of(ProductionProfession.CARTOGRAPHER);
		if (profession == VillagerProfession.CLERIC) return Optional.of(ProductionProfession.CLERIC);
		if (profession == VillagerProfession.FARMER) return Optional.of(ProductionProfession.FARMER);
		if (profession == VillagerProfession.FISHERMAN) return Optional.of(ProductionProfession.FISHERMAN);
		if (profession == VillagerProfession.FLETCHER) return Optional.of(ProductionProfession.FLETCHER);
		if (profession == VillagerProfession.LEATHERWORKER) return Optional.of(ProductionProfession.LEATHERWORKER);
		if (profession == VillagerProfession.LIBRARIAN) return Optional.of(ProductionProfession.LIBRARIAN);
		if (profession == VillagerProfession.MASON) return Optional.of(ProductionProfession.MASON);
		if (profession == VillagerProfession.SHEPHERD) return Optional.of(ProductionProfession.SHEPHERD);
		if (profession == VillagerProfession.TOOLSMITH) return Optional.of(ProductionProfession.TOOLSMITH);
		if (profession == VillagerProfession.WEAPONSMITH) return Optional.of(ProductionProfession.WEAPONSMITH);
		if (profession == VillagerProfession.NONE) return Optional.of(ProductionProfession.UNEMPLOYED);
		if (profession == VillagerProfession.NITWIT) return Optional.of(ProductionProfession.NITWIT);
		return Optional.empty();
	}

}
