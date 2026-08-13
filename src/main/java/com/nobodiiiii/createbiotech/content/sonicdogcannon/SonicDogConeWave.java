package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.VibrationParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class SonicDogConeWave {

	private static final int TRAVEL_TICKS = 16;
	private static final double HALF_ANGLE_RADIANS = Math.toRadians(30.0d);
	private static final double MIN_DIRECTION_DOT = Math.cos(HALF_ANGLE_RADIANS);
	private static final Map<ServerLevel, List<Wave>> ACTIVE_WAVES = new WeakHashMap<>();

	private SonicDogConeWave() {}

	public static void fire(ServerLevel level, Player owner, Vec3 origin, Vec3 direction) {
		Vec3 normalizedDirection = direction.normalize();
		spawnVibrationCone(level, origin, normalizedDirection);
		ACTIVE_WAVES.computeIfAbsent(level, ignored -> new ArrayList<>())
			.add(new Wave(owner, origin, normalizedDirection));
	}

	private static void spawnVibrationCone(ServerLevel level, Vec3 origin, Vec3 forward) {
		Vec3 reference = Math.abs(forward.y) < 0.99d ? new Vec3(0.0d, 1.0d, 0.0d) : new Vec3(1.0d, 0.0d, 0.0d);
		Vec3 right = forward.cross(reference).normalize();
		Vec3 up = right.cross(forward).normalize();

		spawnVibration(level, origin, origin.add(forward.scale(SonicDogCannonItem.CONE_RANGE)));
		int rings = 4;
		for (int ring = 1; ring <= rings; ring++) {
			double angle = HALF_ANGLE_RADIANS * ring / rings;
			double forwardScale = Math.cos(angle);
			double outwardScale = Math.sin(angle);
			int samples = ring * 8;
			for (int sample = 0; sample < samples; sample++) {
				double azimuth = Math.PI * 2.0d * sample / samples;
				Vec3 outward = right.scale(Math.cos(azimuth)).add(up.scale(Math.sin(azimuth)));
				Vec3 ray = forward.scale(forwardScale).add(outward.scale(outwardScale));
				spawnVibration(level, origin, origin.add(ray.scale(SonicDogCannonItem.CONE_RANGE)));
			}
		}
	}

	private static void spawnVibration(ServerLevel level, Vec3 origin, Vec3 destination) {
		VibrationParticleOption particle = new VibrationParticleOption(
			new BlockPositionSource(BlockPos.containing(destination)), TRAVEL_TICKS);
		level.sendParticles(particle, origin.x, origin.y, origin.z, 1, 0.0d, 0.0d, 0.0d, 0.0d);
	}

	@SubscribeEvent
	public static void onLevelTick(LevelTickEvent.Post event) {
		if (!(event.getLevel() instanceof ServerLevel level))
			return;
		List<Wave> waves = ACTIVE_WAVES.get(level);
		if (waves == null)
			return;

		waves.removeIf(wave -> wave.tick(level));
		if (waves.isEmpty())
			ACTIVE_WAVES.remove(level);
	}

	private static final class Wave {

		private final Player owner;
		private final Vec3 origin;
		private final Vec3 direction;
		private final Set<UUID> hitEntities = new HashSet<>();
		private int age;

		private Wave(Player owner, Vec3 origin, Vec3 direction) {
			this.owner = owner;
			this.origin = origin;
			this.direction = direction;
		}

		private boolean tick(ServerLevel level) {
			age++;
			double travelled = SonicDogCannonItem.CONE_RANGE * age / TRAVEL_TICKS;
			AABB bounds = new AABB(origin, origin.add(direction.scale(SonicDogCannonItem.CONE_RANGE)))
				.inflate(SonicDogCannonItem.CONE_RANGE * Math.sin(HALF_ANGLE_RADIANS));

			for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, bounds,
				candidate -> candidate != owner && candidate.isAlive() && !candidate.isSpectator()
					&& !hitEntities.contains(candidate.getUUID()))) {
				Vec3 targetCenter = target.getBoundingBox().getCenter();
				Vec3 offset = targetCenter.subtract(origin);
				double distance = offset.length();
				if (distance > travelled + target.getBbWidth() * 0.5d || distance < 1.0e-5d)
					continue;
				if (offset.dot(direction) / distance < MIN_DIRECTION_DOT)
					continue;

				hitEntities.add(target.getUUID());
				target.hurt(level.damageSources().sonicBoom(owner), 1.0f);
			}

			return age >= TRAVEL_TICKS;
		}
	}
}
