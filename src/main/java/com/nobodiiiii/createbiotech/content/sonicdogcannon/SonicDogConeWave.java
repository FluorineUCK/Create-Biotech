package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import com.nobodiiiii.createbiotech.network.CBPackets;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SonicDogConeWave {

	private static final double HALF_ANGLE_RADIANS = Math.toRadians(60.0d);
	private static final double MIN_DIRECTION_DOT = Math.cos(HALF_ANGLE_RADIANS);

	private SonicDogConeWave() {}

	public static void fire(ServerLevel level, Player owner, Vec3 damageOrigin, Vec3 direction) {
		Vec3 normalizedDirection = direction.normalize();
		SonicDogCannonFirePacket packet = new SonicDogCannonFirePacket(
			owner.getId(), owner.getUsedItemHand(), normalizedDirection);
		CBPackets.sendToTrackingEntity(packet, owner);
		if (owner instanceof ServerPlayer serverPlayer)
			CBPackets.sendToPlayer(packet, serverPlayer);

		AABB bounds = new AABB(damageOrigin,
			damageOrigin.add(normalizedDirection.scale(SonicDogCannonItem.CONE_RANGE)))
			.inflate(SonicDogCannonItem.CONE_RANGE * Math.sin(HALF_ANGLE_RADIANS));
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, bounds,
			candidate -> candidate != owner && candidate.isAlive() && !candidate.isSpectator())) {
			Vec3 offset = target.getBoundingBox().getCenter().subtract(damageOrigin);
			double distance = offset.length();
			if (distance < 1.0e-5d || distance > SonicDogCannonItem.CONE_RANGE + target.getBbWidth() * 0.5d)
				continue;
			if (offset.dot(normalizedDirection) / distance >= MIN_DIRECTION_DOT)
				target.hurt(level.damageSources().sonicBoom(owner), 1.0f);
		}
	}
}
