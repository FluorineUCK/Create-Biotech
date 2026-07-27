package com.nobodiiiii.createbiotech.content.frogportal;

import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Pure geometry + builder for the private rooms in the Frog Stomach dimension. Each space index maps
 * to a fixed, non-overlapping cube laid out on a grid so coordinates stay bounded. A room is a hollow
 * cube walled with the indestructible {@link CBBlocks#FROG_STOMACH_WALL}. Its south wall contains an
 * upright, slimeball-activated 3x3 {@link CBBlocks#FROG_ESOPHAGUS} return portal.
 */
public final class FrogStomachSpace {

	/** Empty blocks left between adjacent rooms. */
	private static final int GAP = 16;
	/** Rooms per grid row before wrapping to the next row of the layout. */
	private static final int ROW = 4096;
	/** Y of the room's floor shell. */
	private static final int BASE_Y = 64;
	private static final int PORTAL_SIZE = 3;
	private static final int BOTTOM_FRAME_Y_OFFSET = 1;
	private static final int PORTAL_Y_OFFSET = BOTTOM_FRAME_Y_OFFSET + 1;

	private FrogStomachSpace() {}

	/** Edge length of a room in blocks, from server config (default 48 = 3x3 chunks). */
	public static int boxSize() {
		return Math.max(7, CBConfigs.SERVER.frogStomach.boxSize.get());
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

	/** Bottom-left block of the 3x3 portal field, viewed from inside the room. */
	public static BlockPos esophagusPos(long index) {
		BlockPos o = origin(index);
		return new BlockPos(o.getX() + boxSize() / 2 - 1, o.getY() + PORTAL_Y_OFFSET,
			o.getZ() + boxSize() - 2);
	}

	public static long spaceIndexFromEsophagusPos(BlockPos pos) {
		int spacing = boxSize() + GAP;
		long col = Math.floorDiv(pos.getX(), spacing);
		long row = Math.floorDiv(pos.getZ(), spacing);
		if (col < 0 || col >= ROW || row < 0)
			return -1L;

		long index = row * ROW + col;
		BlockPos portal = esophagusPos(index);
		boolean insidePortal = pos.getZ() == portal.getZ()
			&& pos.getX() >= portal.getX() && pos.getX() < portal.getX() + PORTAL_SIZE
			&& pos.getY() >= portal.getY() && pos.getY() < portal.getY() + PORTAL_SIZE;
		boolean insideFrame = isFramePos(index, pos);
		return insidePortal || insideFrame ? index : -1L;
	}

	/** Checks both the room shell marker and all twelve generated exit frames. */
	public static boolean isBuilt(ServerLevel level, long index) {
		if (!level.getBlockState(origin(index)).is(CBBlocks.FROG_STOMACH_WALL.get()))
			return false;
		for (BlockPos framePos : framePositions(index))
			if (!level.getBlockState(framePos).is(CBBlocks.FROG_ESOPHAGUS_FRAME.get()))
				return false;
		return true;
	}

	/**
	 * Build (or repair) the room for {@code index}: force-loads the covered chunks, places the six
	 * indestructible wall faces and the inactive return-portal frame. The interior is left as the
	 * dimension's native void air, so only the shell (~13k blocks for a 48-cube) is written.
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

		BlockState emptyFrame = CBBlocks.FROG_ESOPHAGUS_FRAME.get().defaultBlockState();
		for (BlockPos framePos : framePositions(index))
			level.setBlock(framePos, emptyFrame, Block.UPDATE_CLIENTS);
		for (BlockPos portalPos : portalPositions(index))
			level.setBlock(portalPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);

		// Remove the old, single floor portal when an existing room is migrated to the framed exit.
		BlockPos legacyPortal = o.offset(2, 1, 2);
		if (level.getBlockState(legacyPortal).is(CBBlocks.FROG_ESOPHAGUS.get()))
			level.setBlock(legacyPortal, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
	}

	/**
	 * Activates the generated room exit containing {@code filledFramePos}, if all twelve frames have
	 * received slimeballs.
	 */
	public static boolean tryActivatePortal(ServerLevel level, BlockPos filledFramePos) {
		if (!level.dimension().equals(FrogStomachDimensions.FROG_STOMACH))
			return false;
		long index = spaceIndexFromEsophagusPos(filledFramePos);
		if (index < 0 || !isFramePos(index, filledFramePos))
			return false;
		for (BlockPos framePos : framePositions(index)) {
			BlockState state = level.getBlockState(framePos);
			if (!state.is(CBBlocks.FROG_ESOPHAGUS_FRAME.get())
				|| !state.getValue(FrogEsophagusFrameBlock.HAS_SLIME))
				return false;
		}

		BlockState portal = CBBlocks.FROG_ESOPHAGUS.get()
			.defaultBlockState()
			.setValue(FrogPortalBehaviour.AXIS, Direction.Axis.X);
		for (BlockPos portalPos : portalPositions(index)) {
			level.setBlock(portalPos, portal, Block.UPDATE_CLIENTS);
			if (level.getBlockEntity(portalPos) instanceof FrogEsophagusBlockEntity esophagus)
				esophagus.setSpaceIndex(index);
		}
		BlockPos center = esophagusPos(index).offset(1, 1, 0);
		level.globalLevelEvent(1038, center, 0);
		return true;
	}

	private static BlockPos[] framePositions(long index) {
		BlockPos portal = esophagusPos(index);
		BlockPos[] positions = new BlockPos[PORTAL_SIZE * 4];
		int next = 0;
		for (int x = 0; x < PORTAL_SIZE; x++) {
			positions[next++] = portal.offset(x, -1, 0);
			positions[next++] = portal.offset(x, PORTAL_SIZE, 0);
		}
		for (int y = 0; y < PORTAL_SIZE; y++) {
			positions[next++] = portal.offset(-1, y, 0);
			positions[next++] = portal.offset(PORTAL_SIZE, y, 0);
		}
		return positions;
	}

	private static BlockPos[] portalPositions(long index) {
		BlockPos portal = esophagusPos(index);
		BlockPos[] positions = new BlockPos[PORTAL_SIZE * PORTAL_SIZE];
		int next = 0;
		for (int x = 0; x < PORTAL_SIZE; x++)
			for (int y = 0; y < PORTAL_SIZE; y++)
				positions[next++] = portal.offset(x, y, 0);
		return positions;
	}

	private static boolean isFramePos(long index, BlockPos pos) {
		BlockPos portal = esophagusPos(index);
		if (pos.getZ() != portal.getZ())
			return false;
		int dx = pos.getX() - portal.getX();
		int dy = pos.getY() - portal.getY();
		boolean horizontalEdge = dx >= 0 && dx < PORTAL_SIZE && (dy == -1 || dy == PORTAL_SIZE);
		boolean verticalEdge = dy >= 0 && dy < PORTAL_SIZE && (dx == -1 || dx == PORTAL_SIZE);
		return horizontalEdge || verticalEdge;
	}
}
