package com.nobodiiiii.createbiotech.foundation.block;

import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.Level;

/** Dimension- and sublevel-safe first-pulley binding shared by every connector item. */
public final class CBBeltConnectorSelection {
	public static final String POSITION = "FirstPulley";
	public static final String DIMENSION = "FirstPulleyDimension";
	public static final String SUBLEVEL = "FirstPulleySubLevel";

	private CBBeltConnectorSelection() {}

	@Nullable
	public static BlockPos readValid(CompoundTag tag, Level level, BlockPos second, int maxDistance,
		Predicate<BlockPos> validPulley) {
		if (!tag.contains(POSITION) || !tag.contains(DIMENSION))
			return null;
		BlockPos first = NbtUtils.readBlockPos(tag, POSITION).orElse(null);
		UUID subLevelId = tag.hasUUID(SUBLEVEL) ? tag.getUUID(SUBLEVEL) : null;
		if (first == null || !level.dimension().location().toString().equals(tag.getString(DIMENSION))
			|| !SubLevelCompat.matchesSpace(level, first, subLevelId)
			|| !SubLevelCompat.sameSpace(level, first, second)
			|| !first.closerThan(second, maxDistance) || !validPulley.test(first))
			return null;
		return first;
	}

	public static void write(CompoundTag tag, Level level, BlockPos pos) {
		tag.put(POSITION, NbtUtils.writeBlockPos(pos));
		tag.putString(DIMENSION, level.dimension().location().toString());
		UUID subLevelId = SubLevelCompat.getSpaceId(level, pos);
		if (subLevelId == null)
			tag.remove(SUBLEVEL);
		else
			tag.putUUID(SUBLEVEL, subLevelId);
	}
}
