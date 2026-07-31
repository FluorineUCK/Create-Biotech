package com.nobodiiiii.createbiotech.content.frogportal;

import java.util.ArrayList;
import java.util.List;

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

/**
 * Deterministic ecology generation for a newly allocated Frog Stomach room. The hard stomach-wall
 * shell stays as the room boundary while a softer mucosa layer forms rolling ground, an irregular
 * ceiling and walls, water, shelves, and hanging glow-berry vines inside it.
 */
final class FrogStomachEcology {

	static final int MAX_FLOOR_SURFACE_OFFSET = 6;

	private static final long ROOM_SEED_SALT = 0x6A09E667F3BCC909L;
	private static final int MIN_LINING_THICKNESS = 1;
	private static final int MAX_LINING_THICKNESS = 3;
	private static final int MIN_POOL_ROOM_SIZE = 12;
	private static final int MIN_PLATFORM_ROOM_SIZE = 18;
	private static final int MIN_PLATFORM_WIDTH = 5;
	private static final int MAX_PLATFORM_WIDTH = 9;
	private static final int MIN_PLATFORM_DEPTH = MAX_LINING_THICKNESS + 2;
	private static final int MAX_PLATFORM_DEPTH = 7;
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
		generateFloorAndPool(level, origin, size, random, terrainNoise, terrainDetailNoise, poolShoreNoise);
		generateWallPlatforms(level, origin, size, random);
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

	private static void generateFloorAndPool(ServerLevel level, BlockPos origin, int size, RandomSource random,
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
		int radiusLimit = Math.max(2, Math.min(5, interiorWidth / 5));
		int radiusX = Math.max(2, radiusLimit - random.nextInt(2));
		int radiusZ = Math.max(2, radiusLimit - random.nextInt(2));
		int maximumMargin = Math.max(1, (interiorWidth - 1) / 2);
		int wallMargin = Math.min(radiusLimit + 4, maximumMargin);
		int centerX = randomCoordinate(random,
			origin.getX() + 1 + wallMargin, origin.getX() + size - 2 - wallMargin);
		int centerZ = randomCoordinate(random,
			origin.getZ() + 1 + wallMargin, origin.getZ() + size - 2 - wallMargin);

		List<PoolLobe> lobes = new ArrayList<>();
		lobes.add(new PoolLobe(centerX, centerZ, radiusX, radiusZ));
		int lobeCount = 2 + random.nextInt(3);
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

	private static void generateWallPlatforms(ServerLevel level, BlockPos origin, int size, RandomSource random) {
		if (size < MIN_PLATFORM_ROOM_SIZE)
			return;
		int platformCount = Math.max(2, size / 12) + random.nextInt(4);
		for (int i = 0; i < platformCount; i++)
			generateWallPlatform(level, origin, size, random,
				PLATFORM_WALLS[random.nextInt(PLATFORM_WALLS.length)]);
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

	private static void generateGlowBerryVine(ServerLevel level, BlockPos anchor, int floorY,
		RandomSource random) {
		if (!level.getBlockState(anchor).is(CBBlocks.FROG_STOMACH_MUCOSA.get()))
			return;
		int requestedLength = 3 + random.nextInt(6);
		int availableLength = 0;
		BlockPos.MutableBlockPos cursor = anchor.mutable().move(Direction.DOWN);
		while (availableLength < requestedLength && cursor.getY() > floorY + 2
			&& level.getBlockState(cursor).isAir()) {
			availableLength++;
			cursor.move(Direction.DOWN);
		}
		if (availableLength == 0)
			return;

		for (int depth = 1; depth <= availableLength; depth++) {
			boolean head = depth == availableLength;
			BlockState vine = (head ? Blocks.CAVE_VINES : Blocks.CAVE_VINES_PLANT)
				.defaultBlockState()
				.setValue(CaveVines.BERRIES, head || random.nextFloat() < 0.35f);
			level.setBlock(anchor.below(depth), vine, Block.UPDATE_CLIENTS);
		}
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
}
