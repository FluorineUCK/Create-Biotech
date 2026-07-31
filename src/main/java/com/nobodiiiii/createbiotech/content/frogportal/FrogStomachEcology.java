package com.nobodiiiii.createbiotech.content.frogportal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.nobodiiiii.createbiotech.registry.CBBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.world.level.material.Fluids;

/**
 * Deterministic ecology generation for a newly allocated Frog Stomach room. The hard stomach-wall
 * shell stays as the room boundary while a softer mucosa layer forms rolling ground, an irregular
 * ceiling and walls, water, shelves, and hanging glow-berry vines inside it.
 */
final class FrogStomachEcology {

	static final int MAX_FLOOR_SURFACE_OFFSET = 6;
	static final int MAX_SECRETION_GROWTH_DEPTH = 3;

	private static final long ROOM_SEED_SALT = 0x6A09E667F3BCC909L;
	private static final int MIN_LINING_THICKNESS = 1;
	private static final int MAX_LINING_THICKNESS = 3;
	private static final int MIN_POOL_ROOM_SIZE = 12;
	private static final int MIN_PLATFORM_ROOM_SIZE = 18;
	private static final int MIN_SECRETION_ROOM_SIZE = 18;
	private static final int MIN_SECRETION_GROWTHS = 4;
	private static final int SECRETION_GROWTH_VARIATION = 5;
	private static final int MAX_SECRETION_RADIUS = 6;
	private static final int EMBEDDED_CONTENT_CHANCE = 40;
	private static final int MIN_PLATFORM_WIDTH = 5;
	private static final int MAX_PLATFORM_WIDTH = 9;
	private static final int MIN_PLATFORM_DEPTH = MAX_LINING_THICKNESS + 2;
	private static final int MAX_PLATFORM_DEPTH = 7;
	private static final float VINE_FROGLIGHT_CHANCE = 0.25f;
	private static final BlockState[] FROGLIGHTS = {
		Blocks.OCHRE_FROGLIGHT.defaultBlockState(),
		Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState(),
		Blocks.VERDANT_FROGLIGHT.defaultBlockState()
	};
	private static final Direction[] SECRETION_NORMALS = {
		Direction.UP,
		Direction.DOWN,
		Direction.SOUTH,
		Direction.NORTH,
		Direction.EAST,
		Direction.WEST
	};
	private static final Direction[] PLATFORM_WALLS = {
		Direction.NORTH,
		Direction.SOUTH,
		Direction.WEST,
		Direction.EAST
	};

	private FrogStomachEcology() {}

	static void generate(ServerLevel level, long index, BlockPos origin, int size) {
		long seed = level.getSeed()
			^ Long.rotateLeft(index * 0x9E3779B97F4A7C15L, 17)
			^ ROOM_SEED_SALT;
		RandomSource random = RandomSource.create(seed);
		SimplexNoise terrainNoise = new SimplexNoise(random);
		SimplexNoise terrainDetailNoise = new SimplexNoise(random);
		SimplexNoise liningNoise = new SimplexNoise(random);
		SimplexNoise poolShoreNoise = new SimplexNoise(random);

		generateCeilingAndWallLining(level, origin, size, liningNoise);
		Pool pool = generateFloorAndPool(level, origin, size, random, terrainNoise, terrainDetailNoise,
			poolShoreNoise);
		generateSecretionGrowths(level, origin, size, random);
		generateWallPlatforms(level, origin, size, random);
		if (pool != null)
			generatePoolWaterfallPlatform(level, origin, size, pool, random);
		generateCeilingVines(level, origin, size, random);
	}

	private static void generateCeilingAndWallLining(ServerLevel level, BlockPos origin, int size,
		SimplexNoise noise) {
		BlockState mucosa = CBBlocks.FROG_STOMACH_MUCOSA.get().defaultBlockState();
		int minX = origin.getX();
		int minY = origin.getY();
		int minZ = origin.getZ();
		int maxX = minX + size - 1;
		int maxY = minY + size - 1;
		int maxZ = minZ + size - 1;
		int maximumThickness = maximumLiningThickness(size);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int x = minX + 1; x < maxX; x++)
			for (int z = minZ + 1; z < maxZ; z++) {
				int thickness = liningThickness(noise, x - minX, z - minZ, 0, maximumThickness);
				for (int depth = 1; depth <= thickness; depth++)
					setMucosa(level, pos.set(x, maxY - depth, z), mucosa);
			}

		for (int x = minX + 1; x < maxX; x++)
			for (int y = minY + 1; y < maxY; y++) {
				int northThickness = liningThickness(noise, x - minX, y - minY, 1, maximumThickness);
				int southThickness = liningThickness(noise, x - minX, y - minY, 2, maximumThickness);
				for (int depth = 1; depth <= northThickness; depth++)
					setMucosa(level, pos.set(x, y, minZ + depth), mucosa);
				for (int depth = 1; depth <= southThickness; depth++)
					setMucosa(level, pos.set(x, y, maxZ - depth), mucosa);
			}

		for (int z = minZ + 1; z < maxZ; z++)
			for (int y = minY + 1; y < maxY; y++) {
				int westThickness = liningThickness(noise, z - minZ, y - minY, 3, maximumThickness);
				int eastThickness = liningThickness(noise, z - minZ, y - minY, 4, maximumThickness);
				for (int depth = 1; depth <= westThickness; depth++)
					setMucosa(level, pos.set(minX + depth, y, z), mucosa);
				for (int depth = 1; depth <= eastThickness; depth++)
					setMucosa(level, pos.set(maxX - depth, y, z), mucosa);
			}
	}

	private static int liningThickness(SimplexNoise noise, int firstCoordinate, int secondCoordinate,
		int faceSalt, int maximumThickness) {
		double value = noise.getValue(
			firstCoordinate / 7.0d + faceSalt * 31.75d,
			secondCoordinate / 7.0d - faceSalt * 19.25d);
		int range = maximumThickness - MIN_LINING_THICKNESS + 1;
		return MIN_LINING_THICKNESS + Mth.clamp((int) Math.floor((value + 1.0d) * range / 2.0d),
			0, range - 1);
	}

	private static int maximumLiningThickness(int size) {
		return Math.max(MIN_LINING_THICKNESS,
			Math.min(MAX_LINING_THICKNESS, (size - 3) / 2));
	}

	private static Pool generateFloorAndPool(ServerLevel level, BlockPos origin, int size, RandomSource random,
		SimplexNoise terrainNoise, SimplexNoise detailNoise, SimplexNoise poolShoreNoise) {
		BlockState mucosa = CBBlocks.FROG_STOMACH_MUCOSA.get().defaultBlockState();
		BlockState water = Blocks.WATER.defaultBlockState();
		int minX = origin.getX();
		int minY = origin.getY();
		int minZ = origin.getZ();
		int maxX = minX + size - 1;
		int maxZ = minZ + size - 1;
		int maximumSurfaceY = minY + Math.max(1, size - maximumLiningThickness(size) - 3);
		Pool pool = createPool(random, origin, size, poolShoreNoise);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int x = minX + 1; x < maxX; x++)
			for (int z = minZ + 1; z < maxZ; z++) {
				int surfaceY = Math.min(maximumSurfaceY,
					terrainSurfaceY(minY, x - minX, z - minZ, terrainNoise, detailNoise));
				boolean underwater = pool != null && pool.contains(x, z);
				if (underwater)
					surfaceY = pool.bedY();

				for (int y = minY + 1; y <= surfaceY; y++)
					setMucosa(level, pos.set(x, y, z), mucosa);

				if (underwater)
					for (int y = surfaceY + 1; y <= pool.waterY(); y++)
						level.setBlock(pos.set(x, y, z), water, Block.UPDATE_CLIENTS);
			}
		return pool;
	}

	private static int terrainSurfaceY(int floorY, int localX, int localZ, SimplexNoise terrainNoise,
		SimplexNoise detailNoise) {
		double rolling = terrainNoise.getValue(localX / 18.0d, localZ / 18.0d);
		double detail = detailNoise.getValue(localX / 7.0d, localZ / 7.0d);
		double combined = rolling * 0.72d + detail * 0.28d;
		int relief = Mth.clamp((int) Math.floor((combined + 1.0d) * 2.0d), 0, 3);
		return floorY + 3 + relief;
	}

	private static Pool createPool(RandomSource random, BlockPos origin, int size, SimplexNoise shoreNoise) {
		if (size < MIN_POOL_ROOM_SIZE)
			return null;
		int interiorWidth = size - 2;
		int radiusLimit = Math.max(3, Math.min(8, interiorWidth / 4));
		int radiusX = Math.max(3, radiusLimit - random.nextInt(3));
		int radiusZ = Math.max(3, radiusLimit - random.nextInt(3));
		int maximumMargin = Math.max(1, (interiorWidth - 1) / 2);
		int wallMargin = Math.min(radiusLimit + 4, maximumMargin);
		int centerX = randomCoordinate(random,
			origin.getX() + 1 + wallMargin, origin.getX() + size - 2 - wallMargin);
		int centerZ = randomCoordinate(random,
			origin.getZ() + 1 + wallMargin, origin.getZ() + size - 2 - wallMargin);

		List<PoolLobe> lobes = new ArrayList<>();
		lobes.add(new PoolLobe(centerX, centerZ, radiusX, radiusZ));
		int lobeCount = 3 + random.nextInt(3);
		for (int i = 1; i < lobeCount; i++) {
			int lobeRadiusX = Math.max(2, radiusX - 1 - random.nextInt(2));
			int lobeRadiusZ = Math.max(2, radiusZ - 1 - random.nextInt(2));
			double angle = random.nextDouble() * Math.PI * 2.0d;
			double displacement = Math.min(radiusX, radiusZ) * (0.35d + random.nextDouble() * 0.5d);
			int candidateX = centerX + Mth.floor(Math.cos(angle) * displacement);
			int candidateZ = centerZ + Mth.floor(Math.sin(angle) * displacement);
			int lobeCenterX = clampPoolLobeCenter(candidateX, origin.getX(), size, lobeRadiusX, centerX);
			int lobeCenterZ = clampPoolLobeCenter(candidateZ, origin.getZ(), size, lobeRadiusZ, centerZ);
			lobes.add(new PoolLobe(lobeCenterX, lobeCenterZ, lobeRadiusX, lobeRadiusZ));
		}
		return new Pool(List.copyOf(lobes), shoreNoise, origin.getY() + 1, origin.getY() + 3);
	}

	private static int randomCoordinate(RandomSource random, int minimum, int maximum) {
		if (maximum <= minimum)
			return minimum;
		return minimum + random.nextInt(maximum - minimum + 1);
	}

	private static int clampPoolLobeCenter(int candidate, int roomMinimum, int size, int radius,
		int fallback) {
		int minimum = roomMinimum + 1 + radius + 3;
		int maximum = roomMinimum + size - 2 - radius - 3;
		return minimum <= maximum ? Mth.clamp(candidate, minimum, maximum) : fallback;
	}

	private static void generateSecretionGrowths(ServerLevel level, BlockPos origin, int size,
		RandomSource random) {
		if (size < MIN_SECRETION_ROOM_SIZE)
			return;
		int maximumRadius = Math.max(2, Math.min(MAX_SECRETION_RADIUS, (size - 6) / 4));
		generateGuaranteedFloorSecretion(level, origin, size, maximumRadius, random);
		int targetCount = MIN_SECRETION_GROWTHS + random.nextInt(SECRETION_GROWTH_VARIATION);
		int attempts = targetCount * 6;
		int generated = 0;
		while (generated < targetCount && attempts-- > 0) {
			Direction normal = SECRETION_NORMALS[random.nextInt(SECRETION_NORMALS.length)];
			int radiusFirst = Math.max(2, maximumRadius - random.nextInt(3));
			int radiusSecond = Math.max(2, maximumRadius - random.nextInt(3));
			int margin = maximumRadius + 3;
			SurfaceCoordinates center = randomSurfaceCoordinates(random, origin, size, normal, margin);
			if (center == null)
				continue;
			int depth = 2 + random.nextInt(2);
			if (placeSecretionGrowth(level, origin, size, normal, center, radiusFirst, radiusSecond,
				depth, random))
				generated++;
		}
	}

	private static void generateGuaranteedFloorSecretion(ServerLevel level, BlockPos origin, int size,
		int maximumRadius, RandomSource random) {
		int margin = maximumRadius + 3;
		for (int attempt = 0; attempt < 64; attempt++) {
			SurfaceCoordinates center = randomSurfaceCoordinates(random, origin, size, Direction.UP, margin);
			if (center != null && placeSecretionGrowth(level, origin, size, Direction.UP, center,
				maximumRadius, Math.max(2, maximumRadius - random.nextInt(2)),
				MAX_SECRETION_GROWTH_DEPTH, random))
				return;
		}

		int minX = origin.getX() + 3;
		int maxX = origin.getX() + size - 4;
		int minZ = origin.getZ() + 3;
		int maxZ = origin.getZ() + size - 4;
		for (int x = minX; x <= maxX; x++)
			for (int z = minZ; z <= maxZ; z++)
				if (placeSecretionGrowth(level, origin, size, Direction.UP,
					new SurfaceCoordinates(x, z), 2, 2, 2, random))
					return;
	}

	private static SurfaceCoordinates randomSurfaceCoordinates(RandomSource random, BlockPos origin, int size,
		Direction normal, int margin) {
		int firstMinimum;
		int firstMaximum;
		if (normal.getAxis() == Direction.Axis.X) {
			firstMinimum = origin.getZ() + margin;
			firstMaximum = origin.getZ() + size - 1 - margin;
		} else {
			firstMinimum = origin.getX() + margin;
			firstMaximum = origin.getX() + size - 1 - margin;
		}

		int secondMinimum;
		int secondMaximum;
		if (normal.getAxis() == Direction.Axis.Y) {
			secondMinimum = origin.getZ() + margin;
			secondMaximum = origin.getZ() + size - 1 - margin;
		} else {
			secondMinimum = origin.getY() + margin;
			secondMaximum = origin.getY() + size - 1 - margin;
		}
		if (firstMinimum > firstMaximum || secondMinimum > secondMaximum)
			return null;
		return new SurfaceCoordinates(
			randomCoordinate(random, firstMinimum, firstMaximum),
			randomCoordinate(random, secondMinimum, secondMaximum));
	}

	private static boolean placeSecretionGrowth(ServerLevel level, BlockPos origin, int size, Direction normal,
		SurfaceCoordinates center, int radiusFirst, int radiusSecond, int maximumDepth, RandomSource random) {
		if (findLiningSurface(level, origin, size, normal, center.first(), center.second()) == null)
			return false;
		BlockState secretion = CBBlocks.FROG_STOMACH_SECRETION.get().defaultBlockState();
		List<BlockPos> placed = new ArrayList<>();
		double firstPhase = random.nextDouble() * Math.PI * 2.0d;
		double secondPhase = random.nextDouble() * Math.PI * 2.0d;
		for (int firstOffset = -radiusFirst; firstOffset <= radiusFirst; firstOffset++)
			for (int secondOffset = -radiusSecond; secondOffset <= radiusSecond; secondOffset++) {
				double dx = firstOffset / (double) radiusFirst;
				double dz = secondOffset / (double) radiusSecond;
				double angle = Math.atan2(dz, dx);
				double outline = 1.0d
					+ 0.16d * Math.sin(angle * 3.0d + firstPhase)
					+ 0.10d * Math.sin(angle * 5.0d + secondPhase);
				double distance = Math.sqrt(dx * dx + dz * dz) / outline;
				if (distance > 1.0d)
					continue;
				BlockPos surface = findLiningSurface(level, origin, size, normal,
					center.first() + firstOffset, center.second() + secondOffset);
				if (surface == null)
					continue;
				int columnDepth = secretionColumnDepth(distance, maximumDepth);
				for (int depth = 1; depth <= columnDepth; depth++) {
					BlockPos target = surface.relative(normal, depth);
					if (!level.getBlockState(target).isAir())
						break;
					level.setBlock(target, secretion, Block.UPDATE_CLIENTS);
					placed.add(target);
				}
			}
		if (placed.isEmpty())
			return false;
		embedSecretionContents(level, placed, random);
		return true;
	}

	private static int secretionColumnDepth(double distance, int maximumDepth) {
		if (maximumDepth <= 2)
			return distance <= 0.42d ? 2 : 1;
		if (distance <= 0.30d)
			return 3;
		return distance <= 0.66d ? 2 : 1;
	}

	private static BlockPos findLiningSurface(ServerLevel level, BlockPos origin, int size, Direction normal,
		int first, int second) {
		int minX = origin.getX();
		int minY = origin.getY();
		int minZ = origin.getZ();
		int maxX = minX + size - 1;
		int maxY = minY + size - 1;
		int maxZ = minZ + size - 1;
		BlockPos surface = switch (normal) {
			case UP -> new BlockPos(first, minY + 1, second);
			case DOWN -> new BlockPos(first, maxY - 1, second);
			case SOUTH -> new BlockPos(first, second, minZ + 1);
			case NORTH -> new BlockPos(first, second, maxZ - 1);
			case EAST -> new BlockPos(minX + 1, second, first);
			case WEST -> new BlockPos(maxX - 1, second, first);
		};
		if (!level.getBlockState(surface).is(CBBlocks.FROG_STOMACH_MUCOSA.get()))
			return null;
		while (level.getBlockState(surface.relative(normal)).is(CBBlocks.FROG_STOMACH_MUCOSA.get()))
			surface = surface.relative(normal);
		return level.getBlockState(surface.relative(normal)).isAir() ? surface : null;
	}

	private static void embedSecretionContents(ServerLevel level, List<BlockPos> secretionPositions,
		RandomSource random) {
		List<BlockPos> enclosed = new ArrayList<>();
		for (BlockPos pos : secretionPositions)
			if (level.getBlockState(pos).is(CBBlocks.FROG_STOMACH_SECRETION.get())
				&& isEnclosedSecretion(level, pos))
				enclosed.add(pos);
		if (enclosed.isEmpty())
			return;

		for (BlockPos pos : enclosed) {
			int roll = random.nextInt(EMBEDDED_CONTENT_CHANCE);
			if (roll == 0)
				level.setBlock(pos, FROGLIGHTS[random.nextInt(FROGLIGHTS.length)], Block.UPDATE_CLIENTS);
			else if (roll == 1)
				level.setBlock(pos, Blocks.SLIME_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	private static boolean isEnclosedSecretion(ServerLevel level, BlockPos pos) {
		return secretionNeighbourCount(level, pos) == Direction.values().length;
	}

	private static int secretionNeighbourCount(ServerLevel level, BlockPos pos) {
		int count = 0;
		for (Direction direction : Direction.values()) {
			BlockState neighbour = level.getBlockState(pos.relative(direction));
			if (neighbour.is(CBBlocks.FROG_STOMACH_SECRETION.get())
				|| neighbour.is(CBBlocks.FROG_STOMACH_MUCOSA.get())
				|| neighbour.is(CBBlocks.FROG_STOMACH_WALL.get()))
				count++;
		}
		return count;
	}

	private static void generateWallPlatforms(ServerLevel level, BlockPos origin, int size, RandomSource random) {
		if (size < MIN_PLATFORM_ROOM_SIZE)
			return;
		int platformCount = Math.max(2, size / 12) + random.nextInt(4);
		for (int i = 0; i < platformCount; i++)
			generateWallPlatform(level, origin, size, random,
				PLATFORM_WALLS[random.nextInt(PLATFORM_WALLS.length)]);
	}

	private static void generatePoolWaterfallPlatform(ServerLevel level, BlockPos origin, int size, Pool pool,
		RandomSource random) {
		PoolEdge edge = findNearestPoolEdge(pool, origin, size, random);
		if (edge == null)
			return;

		int heightVariation = Math.max(1, size / 10);
		int desiredTopY = origin.getY() + size / 2
			+ random.nextInt(heightVariation * 2 + 1) - heightVariation;
		int minimumTopY = pool.waterY() + 4;
		int maximumTopY = origin.getY() + size - 1 - maximumLiningThickness(size) - 3;
		int topY = maximumTopY >= minimumTopY
			? Mth.clamp(desiredTopY, minimumTopY, maximumTopY)
			: Math.max(pool.waterY() + 2, maximumTopY);
		BlockState mucosa = CBBlocks.FROG_STOMACH_MUCOSA.get().defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int halfWidth = 2 + random.nextInt(2);
		double outlinePhase = random.nextDouble() * Math.PI * 2.0d;
		double undersidePhase = random.nextDouble() * Math.PI * 2.0d;
		for (int offset = -halfWidth; offset <= halfWidth; offset++) {
			int edgeInset = offset == 0 ? 0 : Mth.clamp(
				Mth.floor(Math.abs(offset) / (double) halfWidth * 1.4d
					+ (Math.sin(offset * 1.73d + outlinePhase) + 1.0d) * 0.65d),
				0, Math.max(0, edge.wallDistance() - 1));
			int localDepth = Math.max(1, edge.wallDistance() - edgeInset);
			for (int inwardStep = 1; inwardStep <= localDepth; inwardStep++) {
				int x = waterfallPlatformX(origin, size, edge, offset, inwardStep);
				int z = waterfallPlatformZ(origin, size, edge, offset, inwardStep);
				double undersideNoise = Math.sin(offset * 1.41d + inwardStep * 0.83d + undersidePhase);
				int thickness = inwardStep <= Math.max(2, localDepth / 2) || undersideNoise > 0.4d ? 2 : 1;
				for (int layer = 0; layer < thickness; layer++)
					setMucosa(level, pos.set(x, topY - layer, z), mucosa);
			}
		}

		BlockPos sourceSupport = new BlockPos(edge.x(), topY, edge.z());
		setMucosa(level, sourceSupport, mucosa);
		BlockPos sourcePos = sourceSupport.above();
		for (Direction blockedSide : new Direction[] {
			edge.inward().getOpposite(),
			edge.inward().getClockWise(),
			edge.inward().getCounterClockWise()
		})
			setMucosa(level, sourcePos.relative(blockedSide), mucosa);
		level.setBlock(sourcePos, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
		level.scheduleTick(sourcePos, Fluids.WATER, 1);
	}

	private static PoolEdge findNearestPoolEdge(Pool pool, BlockPos origin, int size, RandomSource random) {
		int minX = origin.getX();
		int maxX = minX + size - 1;
		int minZ = origin.getZ();
		int maxZ = minZ + size - 1;
		List<PoolEdge> candidates = new ArrayList<>();
		for (int x = minX + 1; x < maxX; x++)
			for (int z = minZ + 1; z < maxZ; z++) {
				if (!pool.contains(x, z))
					continue;
				if (!pool.contains(x - 1, z) && pool.contains(x + 1, z))
					candidates.add(new PoolEdge(x, z, Direction.EAST, x - minX));
				if (!pool.contains(x + 1, z) && pool.contains(x - 1, z))
					candidates.add(new PoolEdge(x, z, Direction.WEST, maxX - x));
				if (!pool.contains(x, z - 1) && pool.contains(x, z + 1))
					candidates.add(new PoolEdge(x, z, Direction.SOUTH, z - minZ));
				if (!pool.contains(x, z + 1) && pool.contains(x, z - 1))
					candidates.add(new PoolEdge(x, z, Direction.NORTH, maxZ - z));
			}
		if (candidates.isEmpty())
			return null;

		int nearestDistance = Integer.MAX_VALUE;
		for (PoolEdge candidate : candidates)
			nearestDistance = Math.min(nearestDistance, candidate.wallDistance());
		List<PoolEdge> nearestEdges = new ArrayList<>();
		for (PoolEdge candidate : candidates)
			if (candidate.wallDistance() == nearestDistance)
				nearestEdges.add(candidate);
		return nearestEdges.get(random.nextInt(nearestEdges.size()));
	}

	private static int waterfallPlatformX(BlockPos origin, int size, PoolEdge edge, int offset,
		int inwardStep) {
		int minX = origin.getX();
		int maxX = minX + size - 1;
		return switch (edge.inward()) {
			case EAST -> minX + inwardStep;
			case WEST -> maxX - inwardStep;
			case NORTH, SOUTH -> edge.x() + offset;
			default -> throw new IllegalArgumentException("Waterfall platform direction must be horizontal");
		};
	}

	private static int waterfallPlatformZ(BlockPos origin, int size, PoolEdge edge, int offset,
		int inwardStep) {
		int minZ = origin.getZ();
		int maxZ = minZ + size - 1;
		return switch (edge.inward()) {
			case SOUTH -> minZ + inwardStep;
			case NORTH -> maxZ - inwardStep;
			case EAST, WEST -> edge.z() + offset;
			default -> throw new IllegalArgumentException("Waterfall platform direction must be horizontal");
		};
	}

	private static void generateCeilingVines(ServerLevel level, BlockPos origin, int size, RandomSource random) {
		int margin = Math.min(MAX_LINING_THICKNESS + 2, Math.max(1, (size - 3) / 2));
		int minX = origin.getX() + margin;
		int maxX = origin.getX() + size - 1 - margin;
		int minZ = origin.getZ() + margin;
		int maxZ = origin.getZ() + size - 1 - margin;
		if (minX > maxX || minZ > maxZ)
			return;

		int targetCount = Math.max(2, size / 4) + random.nextInt(Math.max(1, size / 8));
		int attempts = targetCount * 5;
		int generated = 0;
		Set<Long> usedColumns = new HashSet<>();
		while (generated < targetCount && attempts-- > 0) {
			int x = randomCoordinate(random, minX, maxX);
			int z = randomCoordinate(random, minZ, maxZ);
			long columnKey = BlockPos.asLong(x, 0, z);
			if (!usedColumns.add(columnKey))
				continue;
			BlockPos anchor = findCeilingAnchor(level, origin, size, x, z);
			if (anchor != null && generateGlowBerryVine(level, anchor, origin.getY(), random))
				generated++;
		}
	}

	private static BlockPos findCeilingAnchor(ServerLevel level, BlockPos origin, int size, int x, int z) {
		BlockPos anchor = null;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(
			x, origin.getY() + size - 2, z);
		while (cursor.getY() > origin.getY() + 2
			&& level.getBlockState(cursor).is(CBBlocks.FROG_STOMACH_MUCOSA.get())) {
			anchor = cursor.immutable();
			cursor.move(Direction.DOWN);
		}
		return anchor != null && level.getBlockState(anchor.below()).isAir() ? anchor : null;
	}

	private static void generateWallPlatform(ServerLevel level, BlockPos origin, int size, RandomSource random,
		Direction wall) {
		int minX = origin.getX();
		int minY = origin.getY();
		int minZ = origin.getZ();
		int maxX = minX + size - 1;
		int maxY = minY + size - 1;
		int maxZ = minZ + size - 1;
		int alongMin = (wall.getAxis() == Direction.Axis.Z ? minX : minZ) + 4;
		int alongMax = (wall.getAxis() == Direction.Axis.Z ? maxX : maxZ) - 4;
		int availableWidth = alongMax - alongMin + 1;
		if (availableWidth < MIN_PLATFORM_WIDTH)
			return;

		int widthLimit = Math.min(MAX_PLATFORM_WIDTH, availableWidth);
		int width = MIN_PLATFORM_WIDTH + random.nextInt(widthLimit - MIN_PLATFORM_WIDTH + 1);
		int halfWidth = width / 2;
		int centerMin = alongMin + halfWidth;
		int centerMax = alongMax - (width - halfWidth - 1);
		int center = centerMin + random.nextInt(centerMax - centerMin + 1);
		int depthLimit = Math.min(MAX_PLATFORM_DEPTH, Math.max(MIN_PLATFORM_DEPTH, (size - 4) / 3));
		int depth = MIN_PLATFORM_DEPTH + random.nextInt(depthLimit - MIN_PLATFORM_DEPTH + 1);
		int lowestY = minY + Math.max(8, size / 5);
		int highestY = maxY - Math.max(8, size / 6);
		if (highestY < lowestY)
			return;
		int topY = lowestY + random.nextInt(highestY - lowestY + 1);

		BlockState mucosa = CBBlocks.FROG_STOMACH_MUCOSA.get().defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		List<BlockPos> vineAnchors = new ArrayList<>();
		int firstOffset = -halfWidth;
		int lastOffset = width - halfWidth - 1;
		for (int offset = firstOffset; offset <= lastOffset; offset++) {
			double edgeDistance = Math.abs(offset) / (double) Math.max(1, halfWidth);
			int localDepth = Math.max(2, depth - (int) Math.floor(edgeDistance * 2.0d));
			for (int inward = 0; inward < localDepth; inward++) {
				int x = platformX(wall, minX, maxX, center, offset, inward);
				int z = platformZ(wall, minZ, maxZ, center, offset, inward);
				int thickness = inward <= 1 ? 2 : 1;
				for (int layer = 0; layer < thickness; layer++)
					setMucosa(level, pos.set(x, topY - layer, z), mucosa);

				if (inward >= MAX_LINING_THICKNESS && inward >= localDepth - 2)
					vineAnchors.add(new BlockPos(x, topY - thickness + 1, z));
			}
		}

		int vineCount = Math.min(vineAnchors.size(), Math.max(2, width / 3));
		for (int i = 0; i < vineCount && !vineAnchors.isEmpty(); i++) {
			BlockPos anchor = vineAnchors.remove(random.nextInt(vineAnchors.size()));
			generateGlowBerryVine(level, anchor, minY, random);
		}
	}

	private static int platformX(Direction wall, int minX, int maxX, int center, int offset, int inward) {
		return switch (wall) {
			case NORTH -> center + offset;
			case SOUTH -> center + offset;
			case WEST -> minX + 1 + inward;
			case EAST -> maxX - 1 - inward;
			default -> throw new IllegalArgumentException("Wall must be horizontal");
		};
	}

	private static int platformZ(Direction wall, int minZ, int maxZ, int center, int offset, int inward) {
		return switch (wall) {
			case NORTH -> minZ + 1 + inward;
			case SOUTH -> maxZ - 1 - inward;
			case WEST -> center + offset;
			case EAST -> center + offset;
			default -> throw new IllegalArgumentException("Wall must be horizontal");
		};
	}

	private static boolean generateGlowBerryVine(ServerLevel level, BlockPos anchor, int floorY,
		RandomSource random) {
		if (!level.getBlockState(anchor).is(CBBlocks.FROG_STOMACH_MUCOSA.get()))
			return false;
		int requestedLength = 3 + random.nextInt(6);
		boolean attachFroglight = random.nextFloat() < VINE_FROGLIGHT_CHANCE;
		int requestedSpace = requestedLength + (attachFroglight ? 1 : 0);
		int availableLength = 0;
		BlockPos.MutableBlockPos cursor = anchor.mutable().move(Direction.DOWN);
		while (availableLength < requestedSpace && cursor.getY() > floorY + 2
			&& level.getBlockState(cursor).isAir()) {
			availableLength++;
			cursor.move(Direction.DOWN);
		}
		if (availableLength == 0)
			return false;
		if (attachFroglight && availableLength < 2)
			attachFroglight = false;
		int vineLength = Math.min(requestedLength, availableLength - (attachFroglight ? 1 : 0));
		if (vineLength == 0)
			return false;

		for (int depth = 1; depth <= vineLength; depth++) {
			boolean head = depth == vineLength;
			BlockState vine = (head ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT)
				.defaultBlockState()
				.setValue(CaveVines.BERRIES, head || random.nextFloat() < 0.35f);
			level.setBlock(anchor.below(depth), vine, Block.UPDATE_CLIENTS);
		}
		if (attachFroglight)
			level.setBlock(anchor.below(vineLength + 1), FROGLIGHTS[random.nextInt(FROGLIGHTS.length)],
				Block.UPDATE_CLIENTS);
		return true;
	}

	private static void setMucosa(ServerLevel level, BlockPos pos, BlockState mucosa) {
		level.setBlock(pos, mucosa, Block.UPDATE_CLIENTS);
	}

	private record Pool(List<PoolLobe> lobes, SimplexNoise shoreNoise, int bedY, int waterY) {
		boolean contains(int x, int z) {
			double nearestLobe = Double.POSITIVE_INFINITY;
			for (PoolLobe lobe : lobes) {
				double dx = (x - lobe.centerX()) / (double) lobe.radiusX();
				double dz = (z - lobe.centerZ()) / (double) lobe.radiusZ();
				nearestLobe = Math.min(nearestLobe, Math.sqrt(dx * dx + dz * dz));
			}
			double broadRoughness = shoreNoise.getValue(x / 3.0d, z / 3.0d) * 0.20d;
			double fineRoughness = shoreNoise.getValue(x * 0.83d + 17.0d, z * 0.83d - 29.0d) * 0.08d;
			return nearestLobe <= 1.0d + broadRoughness + fineRoughness;
		}
	}

	private record PoolLobe(int centerX, int centerZ, int radiusX, int radiusZ) {}

	private record PoolEdge(int x, int z, Direction inward, int wallDistance) {}

	private record SurfaceCoordinates(int first, int second) {}
}
