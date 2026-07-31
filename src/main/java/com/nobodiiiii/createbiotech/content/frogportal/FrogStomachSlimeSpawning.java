package com.nobodiiiii.createbiotech.content.frogportal;

import java.util.List;

import com.nobodiiiii.createbiotech.registry.CBBlocks;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Restricts natural spawning on Frog Stomach Secretion to slimes and gives those slimes the fixed
 * 50% placement probability of a swamp surface spawn under a full moon.
 */
public final class FrogStomachSlimeSpawning {

	private static final float FULL_MOON_SWAMP_CHANCE = 0.5f;
	private static final MobSpawnSettings.SpawnerData SLIME_SPAWN =
		new MobSpawnSettings.SpawnerData(EntityType.SLIME, 1, 1, 1);

	private FrogStomachSlimeSpawning() {}

	public static void register() {
		NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, FrogStomachSlimeSpawning::onPotentialSpawns);
		NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, FrogStomachSlimeSpawning::onSpawnPlacementCheck);
	}

	private static void onPotentialSpawns(LevelEvent.PotentialSpawns event) {
		if (!(event.getLevel() instanceof ServerLevel level)
			|| !level.dimension().equals(FrogStomachDimensions.FROG_STOMACH)
			|| !isStomachSpawnSurface(level.getBlockState(event.getPos().below())))
			return;

		for (MobSpawnSettings.SpawnerData spawn : List.copyOf(event.getSpawnerDataList()))
			event.removeSpawnerData(spawn);
		if (event.getMobCategory() == MobCategory.MONSTER)
			event.addSpawnerData(SLIME_SPAWN);
	}

	private static void onSpawnPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
		if (event.getSpawnType() != MobSpawnType.NATURAL
			|| !event.getLevel().getLevel().dimension().equals(FrogStomachDimensions.FROG_STOMACH)
			|| !isStomachSpawnSurface(event.getLevel().getBlockState(event.getPos().below())))
			return;

		if (event.getEntityType() != EntityType.SLIME) {
			event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
			return;
		}
		event.setResult(event.getRandom().nextFloat() < FULL_MOON_SWAMP_CHANCE
			? MobSpawnEvent.SpawnPlacementCheck.Result.SUCCEED
			: MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
	}

	private static boolean isStomachSpawnSurface(BlockState state) {
		return state.is(CBBlocks.FROG_STOMACH_SECRETION.get());
	}
}
