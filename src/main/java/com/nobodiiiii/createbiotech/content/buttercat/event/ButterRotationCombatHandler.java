package com.nobodiiiii.createbiotech.content.buttercat.event;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.buttercat.ButterRotation;
import com.nobodiiiii.createbiotech.content.buttercat.ButterRotationAccess;
import com.nobodiiiii.createbiotech.registry.CBMobEffects;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public class ButterRotationCombatHandler {
	private ButterRotationCombatHandler() {}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onProjectileSpawn(EntityJoinLevelEvent event) {
		if (event.getLevel().isClientSide || event.loadedFromDisk())
			return;
		if (!(event.getEntity() instanceof Projectile projectile))
			return;
		if (!(projectile.getOwner() instanceof LivingEntity owner) || owner instanceof Player)
			return;

		float rotation = ButterRotation.getVisualRotationDegrees(owner, 0.0F);
		Vec3 movement = projectile.getDeltaMovement();
		if (rotation == 0.0F || movement.lengthSqr() < 1.0E-7D)
			return;

		projectile.setDeltaMovement(ButterRotation.rotateHorizontal(movement, rotation));
		ProjectileUtil.rotateTowardsMovement(projectile, 1.0F);
		projectile.yRotO = projectile.getYRot();
		projectile.xRotO = projectile.getXRot();
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onIncomingMeleeDamage(LivingIncomingDamageEvent event) {
		DamageSource source = event.getSource();
		if (!(source.getEntity() instanceof LivingEntity attacker))
			return;
		if (source.getDirectEntity() != attacker || !isMeleeDamage(source))
			return;
		if (ButterRotation.getEffect(attacker) == null)
			return;

		if (!ButterRotation.isFacingForMelee(attacker, event.getEntity()))
			event.setCanceled(true);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onButterRotationRemoved(MobEffectEvent.Remove event) {
		if (!event.getEntity().level().isClientSide
			&& event.getEffect().is(CBMobEffects.BUTTER_ROTATION.getKey()))
			((ButterRotationAccess) event.getEntity()).createBiotech$setButterRotationAmplifier(-1);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST)
	public static void onButterRotationExpired(MobEffectEvent.Expired event) {
		if (!event.getEntity().level().isClientSide
			&& event.getEffectInstance().getEffect().is(CBMobEffects.BUTTER_ROTATION.getKey()))
			((ButterRotationAccess) event.getEntity()).createBiotech$setButterRotationAmplifier(-1);
	}

	private static boolean isMeleeDamage(DamageSource source) {
		return source.is(DamageTypes.MOB_ATTACK)
			|| source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
			|| source.is(DamageTypes.PLAYER_ATTACK)
			|| source.is(DamageTypes.STING);
	}
}
