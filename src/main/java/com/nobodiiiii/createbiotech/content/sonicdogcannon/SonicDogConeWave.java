package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.registry.CBMobEffects;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SonicDogConeWave {

	private static final double HALF_ANGLE_RADIANS = Math.toRadians(60.0d);
	private static final double MIN_DIRECTION_DOT = Math.cos(HALF_ANGLE_RADIANS);
	private static final int STUN_DURATION_TICKS = 5 * 20;

	private SonicDogConeWave() {}

	public static void fire(ServerLevel level, Player owner, Vec3 damageOrigin, Vec3 direction, double range) {
		Vec3 normalizedDirection = direction.normalize();
		SonicDogCannonFirePacket packet = new SonicDogCannonFirePacket(
			owner.getId(), owner.getUsedItemHand(), normalizedDirection, (float) range);
		CBPackets.sendToTrackingEntity(packet, owner);
		if (owner instanceof ServerPlayer serverPlayer)
			CBPackets.sendToPlayer(packet, serverPlayer);

		AABB bounds = new AABB(damageOrigin,
			damageOrigin.add(normalizedDirection.scale(range)))
			.inflate(range * Math.sin(HALF_ANGLE_RADIANS));
		Holder<MobEffect> stun = CBMobEffects.sonicDogCannonStun();
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, bounds,
			candidate -> candidate != owner && candidate.isAlive() && !candidate.isSpectator())) {
			Vec3 offset = target.getBoundingBox().getCenter().subtract(damageOrigin);
			double distance = offset.length();
			if (distance < 1.0e-5d || distance > range + target.getBbWidth() * 0.5d)
				continue;
			if (offset.dot(normalizedDirection) / distance < MIN_DIRECTION_DOT)
				continue;

			target.addEffect(new MobEffectInstance(stun, STUN_DURATION_TICKS), owner);
		}
	}
}
