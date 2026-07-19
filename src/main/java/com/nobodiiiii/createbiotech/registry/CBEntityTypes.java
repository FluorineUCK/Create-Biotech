package com.nobodiiiii.createbiotech.registry;

import net.minecraft.core.registries.Registries;

import net.minecraft.core.registries.BuiltInRegistries;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.cardboardbox.CardboardBoxEntity;
import com.nobodiiiii.createbiotech.content.ghasthotairballoon.GhastHotAirBalloonEntity;
import com.nobodiiiii.createbiotech.content.ghasthotairballoon.GhastHotAirBalloonSeatEntity;
import com.simibubi.create.content.logistics.box.PackageEntity;
import com.yision.allay.entity.courier.AllayCourierEntity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.allay.Allay;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import net.neoforged.neoforge.registries.DeferredHolder;

public class CBEntityTypes {

	public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
		DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, CreateBiotech.MOD_ID);

	public static final DeferredHolder<EntityType<?>, EntityType<CardboardBoxEntity>> CARDBOARD_BOX =
		ENTITY_TYPES.register("cardboard_box", () -> EntityType.Builder
			.<CardboardBoxEntity>of(CardboardBoxEntity::new, MobCategory.MISC)
			.setTrackingRange(10)
			.setUpdateInterval(3)
			.setShouldReceiveVelocityUpdates(true)
			.sized(1, 1)
			.build("cardboard_box"));

	public static final DeferredHolder<EntityType<?>, EntityType<GhastHotAirBalloonEntity>> GHAST_HOT_AIR_BALLOON =
		ENTITY_TYPES.register("ghast_hot_air_balloon", () -> {
			EntityType.Builder<GhastHotAirBalloonEntity> builder = EntityType.Builder
				.<GhastHotAirBalloonEntity>of(GhastHotAirBalloonEntity::new, MobCategory.MISC)
				.setTrackingRange(20)
				.setUpdateInterval(1)
				.setShouldReceiveVelocityUpdates(true)
				.fireImmune();
			GhastHotAirBalloonEntity.build(builder);
			return builder.build("ghast_hot_air_balloon");
		});

	public static final DeferredHolder<EntityType<?>, EntityType<GhastHotAirBalloonSeatEntity>> GHAST_HOT_AIR_BALLOON_SEAT =
		ENTITY_TYPES.register("ghast_hot_air_balloon_seat", () -> {
			EntityType.Builder<GhastHotAirBalloonSeatEntity> builder = EntityType.Builder
				.<GhastHotAirBalloonSeatEntity>of(GhastHotAirBalloonSeatEntity::new, MobCategory.MISC)
				.setTrackingRange(5)
				.setUpdateInterval(Integer.MAX_VALUE)
				.setShouldReceiveVelocityUpdates(false);
			GhastHotAirBalloonSeatEntity.build(builder);
			return builder.build("ghast_hot_air_balloon_seat");
		});

	public static final DeferredHolder<EntityType<?>, EntityType<AllayCourierEntity>> ALLAY_COURIER =
		ENTITY_TYPES.register("allay_courier", () -> EntityType.Builder
			.<AllayCourierEntity>of(AllayCourierEntity::createEmpty, MobCategory.MISC)
			.sized(0.35F, 0.6F)
			.setTrackingRange(96)
			.setUpdateInterval(1)
			.setShouldReceiveVelocityUpdates(true)
			.build("allay_courier"));

	private CBEntityTypes() {}

	public static void register(IEventBus modEventBus) {
		ENTITY_TYPES.register(modEventBus);
		modEventBus.addListener(CBEntityTypes::registerEntityAttributes);
	}

	private static void registerEntityAttributes(EntityAttributeCreationEvent event) {
		event.put(CARDBOARD_BOX.get(), PackageEntity.createPackageAttributes()
			.build());
		event.put(ALLAY_COURIER.get(), Allay.createAttributes().build());
	}
}
