package com.nobodiiiii.createbiotech.content.beltsurface;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class StandardItemBeltPortResolver {

	private StandardItemBeltPortResolver() {}

	@Nullable
	public static StandardItemBeltPort getHorizontalPort(BlockGetter world, BlockPos pos) {
		BlockEntity blockEntity = world instanceof Level level
			? SubLevelCompat.getLoadedBlockEntity(level, pos)
			: world.getBlockEntity(pos);
		if (!(blockEntity instanceof StandardItemBeltPort port) || !port.createBiotech$isHorizontalItemPort())
			return null;
		return port;
	}

	public static boolean isHorizontalItemBelt(BlockState state) {
		return state.getBlock() instanceof StandardItemBeltBlock belt
			&& belt.createBiotech$isHorizontalItemBelt(state);
	}

	public static boolean canSupportTunnel(BlockState state) {
		return state.getBlock() instanceof StandardItemBeltBlock belt
			&& belt.createBiotech$isHorizontalItemBelt(state)
			&& belt.createBiotech$canSupportTunnel(state);
	}
}
