package com.nobodiiiii.createbiotech.content.frogportal;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Pure geometry + builder for the private rooms in the Frog Stomach dimension. Each space index maps
 * to a fixed, non-overlapping cube laid out on a grid so coordinates stay bounded. A room is a hollow
 * cube walled with the indestructible {@link CBBlocks#FROG_STOMACH_WALL}. Its north wall contains a
 * high, pre-activated mouth portal and its south wall contains a low, slimeball-activated tail portal.
 */
public final class FrogStomachSpace {

	/** Empty blocks left between adjacent rooms. */
	private static final int GAP = 16;
	/** Rooms per grid row before wrapping to the next row of the layout. */
	private static final int ROW = 4096;
	/** Y of the room's floor shell. */
	private static final int BASE_Y = 64;
	private static final int PORTAL_SIZE = 3;
	private static final int PORTAL_FRONT_SIZE = 5;
	private static final int BOTTOM_WALL_Y_OFFSET = 1;
	private static final int TAIL_PORTAL_Y_OFFSET = BOTTOM_WALL_Y_OFFSET + 1;
	private static final int MIN_RANDOM_PILES = 3;
	private static final int RANDOM_PILE_VARIATION = 4;
	private static final int MAX_PILE_HEIGHT = 3;
	private static final int MAX_PILE_RADIUS = 6;
	private static final double MOUTH_ENTRY_SPEED = 0.5d;

	private FrogStomachSpace() {}

	public enum PortalType {
		MOUTH,
		TAIL
	}

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

	/** Bottom-left portal block of the low tail exit, viewed from inside the room. */
	public static BlockPos tailPortalPos(long index) {
		BlockPos o = origin(index);
		return new BlockPos(o.getX() + boxSize() / 2 - 1, o.getY() + TAIL_PORTAL_Y_OFFSET,
			o.getZ() + boxSize() - 2);
	}

	/** Bottom-left portal block of the high mouth entrance, viewed from inside the room. */
	public static BlockPos mouthPortalPos(long index) {
		BlockPos o = origin(index);
		int size = boxSize();
		return new BlockPos(o.getX() + size / 2 - 1, o.getY() + size - PORTAL_SIZE - 2,
			o.getZ() + 1);
	}

	/** Exact centre of the mouth portal, used by everything entering through the Giant Frog's mouth. */
	public static Vec3 mouthPortalCenter(long index) {
		return Vec3.atCenterOf(mouthPortalPos(index).offset(1, 1, 0));
	}

	/** The southward impulse applied after an entity or item arrives through the mouth. */
	public static Vec3 mouthEntryVelocity() {
		return Vec3.atLowerCornerOf(Direction.SOUTH.getNormal())
			.scale(MOUTH_ENTRY_SPEED);
	}

	public static long spaceIndexFromDigestiveTractPos(BlockPos pos) {
		int spacing = boxSize() + GAP;
		long col = Math.floorDiv(pos.getX(), spacing);
		long row = Math.floorDiv(pos.getZ(), spacing);
		if (col < 0 || col >= ROW || row < 0)
			return -1L;

		long index = row * ROW + col;
		return portalTypeFromDigestiveTractPos(index, pos) != null ? index : -1L;
	}

	@Nullable
	public static PortalType portalTypeFromDigestiveTractPos(long index, BlockPos pos) {
		for (PortalType type : PortalType.values())
			if (isPortalPos(index, type, pos) || isWallPos(index, type, pos))
				return type;
		return null;
	}

	/** Checks the shell, both wall rings, and the permanently active mouth portal. */
	public static boolean isBuilt(ServerLevel level, long index) {
		if (!level.getBlockState(origin(index)).is(CBBlocks.FROG_STOMACH_WALL.get()))
			return false;
		for (PortalType type : PortalType.values())
			for (BlockPos wallPos : wallPositions(index, type))
				if (!level.getBlockState(wallPos).is(CBBlocks.FROG_DIGESTIVE_TRACT_WALL.get()))
					return false;
		for (BlockPos wallPos : wallPositions(index, PortalType.MOUTH))
			if (!level.getBlockState(wallPos).getValue(FrogDigestiveTractWallBlock.HAS_SLIME))
				return false;
		for (BlockPos portalPos : portalPositions(index, PortalType.MOUTH))
			if (!level.getBlockState(portalPos).is(CBBlocks.FROG_DIGESTIVE_TRACT.get()))
				return false;
		return true;
	}

	/**
	 * Build (or repair) the room for {@code index}: force-loads the covered chunks, places the six
	 * indestructible wall faces, optionally generates secretion piles for a newly allocated room,
	 * and places a pre-activated high mouth portal plus an inactive low tail portal.
	 */
	public static void buildRoom(ServerLevel level, long index, boolean generateSecretions) {
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

		if (generateSecretions)
			generateSecretionPiles(level, index, o, size);

		// Keep a 5x5 plane in front of each portal and its 3x3 field clear. The mouth field is
		// installed again below, after the generated terrain has been removed from its opening.
		clearPortalAccess(level, index, PortalType.TAIL);
		clearPortalAccess(level, index, PortalType.MOUTH);

		placeWallRing(level, index, PortalType.TAIL, false);
		placeWallRing(level, index, PortalType.MOUTH, true);
		activatePortal(level, index, PortalType.MOUTH, false);

		// Remove the old, single floor portal when an existing room is migrated to the walled exit.
		BlockPos legacyPortal = o.offset(2, 1, 2);
		if (level.getBlockState(legacyPortal).is(CBBlocks.FROG_DIGESTIVE_TRACT.get()))
			level.setBlock(legacyPortal, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
	}

	private static void generateSecretionPiles(ServerLevel level, long index, BlockPos origin, int size) {
		long seed = level.getSeed() ^ Long.rotateLeft(index * 0x9E3779B97F4A7C15L, 17);
		RandomSource random = RandomSource.create(seed);
		int minX = origin.getX() + 1;
		int maxX = origin.getX() + size - 2;
		int minZ = origin.getZ() + 1;
		int maxZ = origin.getZ() + size - 2;
		int floorY = origin.getY();

		int cornerRadius = Math.max(1, Math.min(2, (size - 2) / 3));
		placeNaturalMound(level, random, minX, minZ, floorY, cornerRadius, cornerRadius, 1 + random.nextInt(2),
			minX, maxX, minZ, maxZ);
		placeNaturalMound(level, random, maxX, minZ, floorY, cornerRadius, cornerRadius, 1 + random.nextInt(2),
			minX, maxX, minZ, maxZ);
		placeNaturalMound(level, random, minX, maxZ, floorY, cornerRadius, cornerRadius, 1 + random.nextInt(2),
			minX, maxX, minZ, maxZ);
		placeNaturalMound(level, random, maxX, maxZ, floorY, cornerRadius, cornerRadius, 1 + random.nextInt(2),
			minX, maxX, minZ, maxZ);

		int interiorWidth = Math.max(1, size - 2);
		int maxRadius = Math.max(1, Math.min(MAX_PILE_RADIUS, interiorWidth / 4));
		int minRadius = Math.min(3, maxRadius);
		int pileCount = MIN_RANDOM_PILES + random.nextInt(RANDOM_PILE_VARIATION);
		for (int pile = 0; pile < pileCount; pile++) {
			int centerX = minX + random.nextInt(interiorWidth);
			int centerZ = minZ + random.nextInt(interiorWidth);
			int radiusX = minRadius + random.nextInt(maxRadius - minRadius + 1);
			int radiusZ = minRadius + random.nextInt(maxRadius - minRadius + 1);
			int height = Math.min(MAX_PILE_HEIGHT, 2 + random.nextInt(2));
			placeNaturalMound(level, random, centerX, centerZ, floorY, radiusX, radiusZ, height,
				minX, maxX, minZ, maxZ);
		}
	}

	private static void placeNaturalMound(ServerLevel level, RandomSource random, int centerX, int centerZ, int floorY,
		int radiusX, int radiusZ, int height, int minX, int maxX, int minZ, int maxZ) {
		BlockState secretion = CBBlocks.FROG_STOMACH_SECRETION.get().defaultBlockState();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

		int lobeCount = 2 + random.nextInt(2);
		MoundLobe[] lobes = new MoundLobe[lobeCount];
		lobes[0] = new MoundLobe(centerX, centerZ, radiusX, radiusZ,
			random.nextDouble() * Math.PI * 2.0d, random.nextDouble() * Math.PI * 2.0d);
		int scanRadiusX = radiusX;
		int scanRadiusZ = radiusZ;
		for (int lobe = 1; lobe < lobeCount; lobe++) {
			int offsetX = random.nextInt(radiusX + 1) - radiusX / 2;
			int offsetZ = random.nextInt(radiusZ + 1) - radiusZ / 2;
			int lobeRadiusX = Math.max(1, radiusX * (2 + random.nextInt(2)) / 4);
			int lobeRadiusZ = Math.max(1, radiusZ * (2 + random.nextInt(2)) / 4);
			lobes[lobe] = new MoundLobe(centerX + offsetX, centerZ + offsetZ, lobeRadiusX, lobeRadiusZ,
				random.nextDouble() * Math.PI * 2.0d, random.nextDouble() * Math.PI * 2.0d);
			scanRadiusX = Math.max(scanRadiusX, Math.abs(offsetX) + lobeRadiusX);
			scanRadiusZ = Math.max(scanRadiusZ, Math.abs(offsetZ) + lobeRadiusZ);
		}

		for (int x = Math.max(minX, centerX - scanRadiusX); x <= Math.min(maxX, centerX + scanRadiusX); x++)
			for (int z = Math.max(minZ, centerZ - scanRadiusZ); z <= Math.min(maxZ, centerZ + scanRadiusZ); z++) {
				double distance = normalizedMoundDistance(x, z, lobes);
				if (distance > 1.0d)
					continue;

				int columnHeight = moundColumnHeight(distance, height);
				for (int dy = 1; dy <= columnHeight; dy++) {
					p.set(x, floorY + dy, z);
					if (level.getBlockState(p).isAir())
						level.setBlock(p, secretion, Block.UPDATE_CLIENTS);
				}
			}
	}

	private static double normalizedMoundDistance(int x, int z, MoundLobe[] lobes) {
		double nearest = Double.POSITIVE_INFINITY;
		for (MoundLobe lobe : lobes) {
			double dx = (x - lobe.centerX()) / (double) lobe.radiusX();
			double dz = (z - lobe.centerZ()) / (double) lobe.radiusZ();
			double angle = Math.atan2(dz, dx);
			double outline = 1.0d
				+ 0.16d * Math.sin(angle * 3.0d + lobe.firstPhase())
				+ 0.10d * Math.sin(angle * 5.0d + lobe.secondPhase());
			nearest = Math.min(nearest, Math.sqrt(dx * dx + dz * dz) / outline);
		}
		return nearest;
	}

	private static int moundColumnHeight(double distance, int maximumHeight) {
		if (maximumHeight <= 1)
			return 1;
		if (maximumHeight == 2)
			return distance <= 0.42d ? 2 : 1;
		if (distance <= 0.30d)
			return 3;
		return distance <= 0.66d ? 2 : 1;
	}

	private record MoundLobe(int centerX, int centerZ, int radiusX, int radiusZ,
		double firstPhase, double secondPhase) {}

	private static void clearPortalAccess(ServerLevel level, long index, PortalType type) {
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockPos portal = portalPos(index, type);
		for (BlockPos portalBlock : portalPositions(index, type))
			level.setBlock(portalBlock, air, Block.UPDATE_CLIENTS);

		int frontStep = type == PortalType.MOUTH ? 1 : -1;
		int border = (PORTAL_FRONT_SIZE - PORTAL_SIZE) / 2;
		for (int x = -border; x < PORTAL_SIZE + border; x++)
			for (int y = -border; y < PORTAL_SIZE + border; y++)
				level.setBlock(portal.offset(x, y, frontStep), air, Block.UPDATE_CLIENTS);
	}

	/**
	 * Activates the generated room exit containing {@code filledWallPos}, if all twelve
	 * digestive-tract wall blocks have received slimeballs.
	 */
	public static boolean tryActivatePortal(ServerLevel level, BlockPos filledWallPos) {
		if (!level.dimension().equals(FrogStomachDimensions.FROG_STOMACH))
			return false;
		long index = spaceIndexFromDigestiveTractPos(filledWallPos);
		PortalType type = index >= 0 ? portalTypeFromDigestiveTractPos(index, filledWallPos) : null;
		if (type == null || !isWallPos(index, type, filledWallPos))
			return false;
		for (BlockPos wallPos : wallPositions(index, type)) {
			BlockState state = level.getBlockState(wallPos);
			if (!state.is(CBBlocks.FROG_DIGESTIVE_TRACT_WALL.get())
				|| !state.getValue(FrogDigestiveTractWallBlock.HAS_SLIME))
				return false;
		}

		activatePortal(level, index, type, true);
		return true;
	}

	private static void placeWallRing(ServerLevel level, long index, PortalType type, boolean activated) {
		Direction facing = type == PortalType.MOUTH ? Direction.SOUTH : Direction.NORTH;
		BlockState wall = CBBlocks.FROG_DIGESTIVE_TRACT_WALL.get()
			.defaultBlockState()
			.setValue(FrogDigestiveTractWallBlock.HAS_SLIME, activated)
			.setValue(FrogDigestiveTractWallBlock.FACING, facing);
		for (BlockPos wallPos : wallPositions(index, type))
			level.setBlock(wallPos, wall, Block.UPDATE_CLIENTS);
	}

	private static void activatePortal(ServerLevel level, long index, PortalType type, boolean playEffect) {
		BlockState portal = CBBlocks.FROG_DIGESTIVE_TRACT.get()
			.defaultBlockState()
			.setValue(FrogDigestiveTractBehaviour.AXIS, Direction.Axis.X);
		for (BlockPos portalPos : portalPositions(index, type)) {
			level.setBlock(portalPos, portal, Block.UPDATE_CLIENTS);
			if (level.getBlockEntity(portalPos) instanceof FrogDigestiveTractBlockEntity digestiveTract)
				digestiveTract.setBinding(index, type);
		}
		if (playEffect)
			level.globalLevelEvent(1038, portalPos(index, type).offset(1, 1, 0), 0);
	}

	private static BlockPos[] wallPositions(long index, PortalType type) {
		BlockPos portal = portalPos(index, type);
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

	private static BlockPos[] portalPositions(long index, PortalType type) {
		BlockPos portal = portalPos(index, type);
		BlockPos[] positions = new BlockPos[PORTAL_SIZE * PORTAL_SIZE];
		int next = 0;
		for (int x = 0; x < PORTAL_SIZE; x++)
			for (int y = 0; y < PORTAL_SIZE; y++)
				positions[next++] = portal.offset(x, y, 0);
		return positions;
	}

	private static boolean isPortalPos(long index, PortalType type, BlockPos pos) {
		BlockPos portal = portalPos(index, type);
		return pos.getZ() == portal.getZ()
			&& pos.getX() >= portal.getX() && pos.getX() < portal.getX() + PORTAL_SIZE
			&& pos.getY() >= portal.getY() && pos.getY() < portal.getY() + PORTAL_SIZE;
	}

	private static boolean isWallPos(long index, PortalType type, BlockPos pos) {
		BlockPos portal = portalPos(index, type);
		if (pos.getZ() != portal.getZ())
			return false;
		int dx = pos.getX() - portal.getX();
		int dy = pos.getY() - portal.getY();
		boolean horizontalEdge = dx >= 0 && dx < PORTAL_SIZE && (dy == -1 || dy == PORTAL_SIZE);
		boolean verticalEdge = dy >= 0 && dy < PORTAL_SIZE && (dx == -1 || dx == PORTAL_SIZE);
		return horizontalEdge || verticalEdge;
	}

	private static BlockPos portalPos(long index, PortalType type) {
		return type == PortalType.MOUTH ? mouthPortalPos(index) : tailPortalPos(index);
	}
}
