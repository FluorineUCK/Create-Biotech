package com.nobodiiiii.createbiotech.content.fluid;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.registry.CBFluids;

import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public class LiquidLivingSlimeInteractionHandler {

	private static final String WAS_TOUCHING_LIQUID_LIVING_SLIME_KEY =
		CreateBiotech.MOD_ID + ".was_touching_liquid_living_slime";
	private static final String PREVIOUS_VERTICAL_SPEED_KEY =
		CreateBiotech.MOD_ID + ".previous_liquid_living_slime_vertical_speed";
	private static final double LANDING_VERTICAL_SPEED_THRESHOLD = -0.16D;

	private LiquidLivingSlimeInteractionHandler() {}

	@SubscribeEvent
	public static void onLivingTick(EntityTickEvent.Post event) {
		if (!(event.getEntity() instanceof LivingEntity entity))
			return;
		boolean wasTouchingLiquidLivingSlime =
			entity.getPersistentData().getBoolean(WAS_TOUCHING_LIQUID_LIVING_SLIME_KEY);
		double previousVerticalSpeed = entity.getPersistentData().getDouble(PREVIOUS_VERTICAL_SPEED_KEY);
		boolean touchingLiquidLivingSlime =
			entity.getFluidTypeHeight(CBFluids.LIQUID_LIVING_SLIME_TYPE.get()) > 0.0D;

		if (!entity.level().isClientSide && touchingLiquidLivingSlime && !wasTouchingLiquidLivingSlime
			&& previousVerticalSpeed < LANDING_VERTICAL_SPEED_THRESHOLD) {
			playLandingSound(entity, previousVerticalSpeed);
		}

		entity.getPersistentData().putBoolean(WAS_TOUCHING_LIQUID_LIVING_SLIME_KEY, touchingLiquidLivingSlime);
		entity.getPersistentData().putDouble(PREVIOUS_VERTICAL_SPEED_KEY, entity.getDeltaMovement().y);
	}

	private static void playLandingSound(LivingEntity entity, double previousVerticalSpeed) {
		Vec3 position = entity.position();
		float volume = (float) Mth.clamp(-previousVerticalSpeed * 0.75D, 0.35D, 1.0D);
		float pitch = 1.0F + (entity.level().random.nextFloat() - entity.level().random.nextFloat()) * 0.1F;
		entity.level().playSound(null, position.x, position.y, position.z, SoundEvents.SLIME_BLOCK_FALL,
			entity.getSoundSource(), volume, pitch);
	}

}
