package com.nobodiiiii.createbiotech.content.frogportal;

import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Pure geometry + builder for the private rooms in the Frog Stomach dimension. Each space index maps
 * to a fixed, non-overlapping cube laid out on a grid so coordinates stay bounded. A room is a hollow
 * cube walled with the indestructible {@link CBBlocks#FROG_STOMACH_WALL}, with a single
 * {@link CBBlocks#FROG_ESOPHAGUS} (return portal) sitting on the floor near a corner.
 */
public final class FrogStomachSpace {

	/** Empty blocks left between adjacent rooms. */
	private static final int GAP = 16;
	/** Rooms per grid row before wrapping to the next row of the layout. */
	private static final int ROW = 4096;
	/** Y of the room's floor shell. */
	private static final int BASE_Y = 64;

	private FrogStomachSpace() {}

	/** Edge length of a room in blocks, from server config (default 48 = 3x3 chunks). */
	public static int boxSize() {
		return Math.max(5, CBConfigs.SERVER.frogStomach.boxSize.get());
	}

	/** Lowest-corner (min x/y/z) block position of the room for {@code index}. */
	public static BlockPos origin(long index) {
		int spacing = boxSize() + GAP;
		int col = (int) Math.floorMod(index, (long) ROW);
		int row = (int) Math.floorDiv(index, (long) ROW);
		return new BlockPos(col * spacing, BASE_Y, row * spacing);
	}

	/** Where an arriving entity is placed: interior centre, standing on the floor. */
	public static BlockPos spawnPos(long index) {
		BlockPos o = origin(index);
		int size = boxSize();
		return new BlockPos(o.getX() + size / 2, o.getY() + 1, o.getZ() + size / 2);
	}

	/** Floor tile (offset from the corner) that holds the return portal. */
	public static BlockPos esophagusPos(long index) {
		BlockPos o = origin(index);
		return new BlockPos(o.getX() + 2, o.getY() + 1, o.getZ() + 2);
	}

	/** Cheap sanity check that the room's floor corner is present. */
	public static boolean isBuilt(ServerLevel level, long index) {
		return level.getBlockState(origin(index)).is(CBBlocks.FROG_STOMACH_WALL.get());
	}

	/**
	 * Build (or repair) the room for {@code index}: force-loads the covered chunks, places the six
	 * indestructible wall faces and the return portal. The interior is left as the dimension's native
	 * void air, so only the shell (~13k blocks for a 48-cube) is written.
	 */
	public static void buildRoom(ServerLevel level, long index) {
		int size = boxSize();
		BlockPos o = origin(index);
		int minX = o.getX(), minY = o.getY(), minZ = o.getZ();
		int maxX = minX + size - 1, maxY = minY + size - 1, maxZ = minZ + size - 1;

		for (int cx = minX >> 4; cx <= (maxX >> 4); cx++)
			for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++)
				level.getChunk(cx, cz);

		BlockState wall = CBBlocks.FROG_STOMACH_WALL.get().defaultBlockState();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = minX; x <= maxX; x++)
			for (int y = minY; y <= maxY; y++)
				for (int z = minZ; z <= maxZ; z++) {
					boolean shell = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
					if (shell) {
						p.set(x, y, z);
						level.setBlock(p, wall, Block.UPDATE_CLIENTS);
					}
				}

		BlockPos ep = esophagusPos(index);
		level.setBlock(ep, CBBlocks.FROG_ESOPHAGUS.get().defaultBlockState(), Block.UPDATE_CLIENTS);
		// keep the floor directly under the return portal air-free is unnecessary; the shell already
		// provides it. Ensure the portal's own cell (and the one above, for standing) is clear.
		level.setBlock(ep.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
	}
}
