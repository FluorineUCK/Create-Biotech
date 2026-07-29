package com.nobodiiiii.createbiotech.content.beltsurface;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Cached tilted shapes for BeltFunnel states.
 * <p>
 * Create's BeltFunnel outline is state-only. Item collision uses a different base shape, so the
 * two result sets must not share a cache.
 */
public final class BeltFunnelShapeCache {

	private static final ConcurrentMap<BlockState, VoxelShape> OUTLINES = new ConcurrentHashMap<>();
	private static final ConcurrentMap<BlockState, VoxelShape> ITEM_COLLISIONS = new ConcurrentHashMap<>();

	private BeltFunnelShapeCache() {}

	public static VoxelShape outline(BlockState state, VoxelShape base, Direction outwardNormal) {
		return getOrCreate(OUTLINES, state, base, outwardNormal);
	}

	public static VoxelShape itemCollision(BlockState state, VoxelShape base, Direction outwardNormal) {
		return getOrCreate(ITEM_COLLISIONS, state, base, outwardNormal);
	}

	private static VoxelShape getOrCreate(ConcurrentMap<BlockState, VoxelShape> cache, BlockState state,
		VoxelShape base, Direction outwardNormal) {
		VoxelShape cached = cache.get(state);
		if (cached != null)
			return cached;

		VoxelShape transformed = BeltSurface.transformShape(base, outwardNormal);
		VoxelShape raced = cache.putIfAbsent(state, transformed);
		return raced != null ? raced : transformed;
	}
}
