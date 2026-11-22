package com.nobodiiiii.createbiotech.content.biocomputing.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

class ComputerDoubleBlockTest {
	private static final BlockPos LOWER_POS = new BlockPos(12, 64, -8);

	@BeforeAll
	static void bootstrapMinecraft() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			Field field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@ParameterizedTest
	@EnumSource(value = Direction.class, names = {"NORTH", "EAST", "SOUTH", "WEST"})
	void lowerOwnsOneComputerForEveryFacing(Direction facing) {
		ComputerBlock computer = computer();
		BlockState lower = state(computer, facing, DoubleBlockHalf.LOWER);
		BlockState upper = state(computer, facing, DoubleBlockHalf.UPPER);

		assertEquals(DoubleBlockHalf.LOWER, lower.getValue(ComputerBlock.HALF));
		assertEquals(DoubleBlockHalf.UPPER, upper.getValue(ComputerBlock.HALF));
		assertEquals(LOWER_POS, ComputerBlock.lowerPos(lower, LOWER_POS));
		assertEquals(LOWER_POS, ComputerBlock.lowerPos(upper, LOWER_POS.above()));
		assertTrue(ComputerBlock.isCompletePair(lower, upper));
		assertTrue(ComputerBlock.ownsBlockEntity(lower));
		assertFalse(ComputerBlock.ownsBlockEntity(upper));
	}

	@Test
	void obstructionRejectsASecondHalfBeforePlacement() {
		assertFalse(ComputerBlock.canPlaceUpper(Blocks.STONE.defaultBlockState()));
		assertTrue(ComputerBlock.canPlaceUpper(Blocks.AIR.defaultBlockState()));
	}

	@Test
	void upperRedirectsToTheLowerOwnerAndRejectsMismatchedPairs() {
		ComputerBlock computer = computer();
		BlockState lower = state(computer, Direction.EAST, DoubleBlockHalf.LOWER);
		BlockState upper = state(computer, Direction.EAST, DoubleBlockHalf.UPPER);

		assertEquals(LOWER_POS, ComputerBlock.lowerPos(upper, LOWER_POS.above()));
		assertFalse(ComputerBlock.isCompletePair(lower,
			state(computer, Direction.NORTH, DoubleBlockHalf.UPPER)));
		assertFalse(ComputerBlock.isCompletePair(lower, Blocks.AIR.defaultBlockState()));
	}

	@Test
	void modelDerivedLowerShapeRotatesItsAsymmetricFootprint() {
		ComputerBlock computer = computer();
		VoxelShape north = state(computer, Direction.NORTH, DoubleBlockHalf.LOWER)
			.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
		VoxelShape east = state(computer, Direction.EAST, DoubleBlockHalf.LOWER)
			.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());

		assertTrue(contains(north, 3.5 / 16, 3.5 / 16, 8.0 / 16),
			"the north model's left restrictor is a source-model box");
		assertFalse(contains(east, 3.5 / 16, 3.5 / 16, 8.0 / 16));
		assertTrue(contains(east, 8.0 / 16, 3.5 / 16, 12.5 / 16),
			"the same source-model box rotates clockwise for east");
		assertNotEquals(north.toAabbs(), east.toAabbs());
	}

	@Test
	void setBlockHotPathHasNoCapturedWriterOrObservationState() {
		List<String> forbiddenMethods = Arrays.stream(ComputerBlock.class.getDeclaredMethods())
			.filter(method -> isCapturedWriter(method)
				|| method.getName().startsWith("observeActualUpperWrite")
				|| method.getName().startsWith("observeUpperWrites"))
			.map(Method::getName)
			.sorted()
			.toList();
		assertEquals(List.of(), forbiddenMethods,
			"setBlock passthrough must not capture writers or expose production observation hooks");
		assertFalse(Arrays.stream(ComputerBlock.class.getDeclaredFields())
			.anyMatch(field -> field.getType() == ThreadLocal.class),
			"ordinary four-argument setBlock writes must not touch Computer observation ThreadLocals");
	}

	@Test
	void setBlockHotPathInterceptsOnlyTheCanonicalFourArgumentMethod() throws IOException {
		String mixin = Files.readString(Path.of("src/main/java/com/nobodiiiii/createbiotech/mixin/"
			+ "LevelComputerDestroyMixin.java"));
		List<String> setBlockTargets = java.util.regex.Pattern.compile(
			"method\\s*=\\s*\"(setBlock\\([^\"]+)\"")
			.matcher(mixin)
			.results()
			.map(result -> result.group(1))
			.toList();

		assertEquals(List.of(
			"setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z"),
			setBlockTargets,
			"the three-argument overload delegates to the canonical implementation and must not probe twice");
	}

	private static boolean isCapturedWriter(Method method) {
		Class<?>[] parameters = method.getParameterTypes();
		return method.getName().startsWith("lambda$replaceOccupiedUpper$")
			&& parameters.length >= 3 && parameters.length <= 5
			&& parameters[parameters.length - 3] == BlockPos.class
			&& parameters[parameters.length - 2] == BlockState.class
			&& parameters[parameters.length - 1] == int.class;
	}

	private static ComputerBlock computer() {
		return new ComputerBlock(Block.Properties.of());
	}

	private static BlockState state(ComputerBlock computer, Direction facing, DoubleBlockHalf half) {
		return computer.defaultBlockState().setValue(ComputerBlock.FACING, facing)
			.setValue(ComputerBlock.HALF, half);
	}

	private static boolean contains(VoxelShape shape, double x, double y, double z) {
		return shape.toAabbs().stream().anyMatch(box -> box.minX < x && x < box.maxX
			&& box.minY < y && y < box.maxY && box.minZ < z && z < box.maxZ);
	}
}
