package com.nobodiiiii.createbiotech.content.shulkerpackager;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class ShulkerPackagerRange {

	private ShulkerPackagerRange() {}

	public static boolean isWithinCube(Level level, BlockPos center, BlockPos candidate, int radius) {
		if (level == null || center == null || candidate == null)
			return false;

		Vec3 centerWorld = SubLevelCompat.toWorld(level, center, Vec3.atCenterOf(center));
		Vec3 candidateWorld = SubLevelCompat.toWorld(level, candidate, Vec3.atCenterOf(candidate));
		return isWithinCube(centerWorld, candidateWorld, radius);
	}

	public static boolean isWithinCube(Vec3 center, Vec3 candidate, int radius) {
		if (center == null || candidate == null || radius < 0)
			return false;

		return Math.abs(candidate.x - center.x) <= radius
			&& Math.abs(candidate.y - center.y) <= radius
			&& Math.abs(candidate.z - center.z) <= radius;
	}
}
