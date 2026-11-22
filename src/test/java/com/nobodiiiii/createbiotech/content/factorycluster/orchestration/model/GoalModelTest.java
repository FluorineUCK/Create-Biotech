package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRunExpansion;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class GoalModelTest {
	private static final UUID GOAL = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID PATTERN = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final CreateRouteKey INPUT = route(3, "smelter");
	private static final CreateRouteKey WAREHOUSE = route(4, "warehouse");

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			var bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
		Items.AIR.getDefaultInstance();
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null) return;
			Object empty = loadingModList.getMethod("of", List.class, List.class, List.class,
				List.class, Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(),
				Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void submissionsRejectNonPositiveMultipliersButAllowEveryRepresentablePositiveValue() {
		assertThrows(IllegalArgumentException.class,
			() -> new GoalSubmission(GOAL, PATTERN, 0, InputMode.BULK));
		assertThrows(IllegalArgumentException.class,
			() -> new GoalSubmission(GOAL, PATTERN, -1, InputMode.EXACT));

		GoalSubmission submission = new GoalSubmission(GOAL, PATTERN, Long.MAX_VALUE,
			InputMode.PARTIAL);
		assertEquals(Long.MAX_VALUE, submission.multiplier());
	}

	@Test
	void bulkExpansionFreezesTheExactMultipliedManifestAndRejectsOverflow() {
		StackKey iron = iron();
		ProductionRun.RunSequence sequence = ProductionRunExpansion.expand(GOAL, PATTERN, 9, 3,
			InputMode.BULK, List.of(INPUT), WAREHOUSE, 12, Map.of(iron, 2L), Map.of());
		ProductionRun run = sequence.currentRun();

		assertEquals(1, sequence.remainingRuns());
		assertEquals(3, run.runMultiplier());
		assertEquals(Map.of(iron, 6L), run.manifest());
		assertThrows(ArithmeticException.class,
			() -> ProductionRunExpansion.expand(GOAL, PATTERN, 9, 2, InputMode.BULK, List.of(INPUT),
				WAREHOUSE, 12, Map.of(iron, Long.MAX_VALUE), Map.of()));
	}

	@Test
	void exactExpansionGeneratesOneSerialRunAtATime() {
		ProductionRun.RunSequence sequence = ProductionRunExpansion.expand(GOAL, PATTERN, 9, 3,
			InputMode.EXACT, List.of(INPUT), WAREHOUSE, 12, Map.of(iron(), 2L), Map.of());
		ProductionRun first = sequence.currentRun();
		ProductionRun.RunSequence afterFirst = sequence.afterOutputSealed(first);
		ProductionRun second = afterFirst.currentRun();
		ProductionRun.RunSequence afterSecond = afterFirst.afterOutputSealed(second);
		ProductionRun third = afterSecond.currentRun();
		ProductionRun.RunSequence completed = afterSecond.afterOutputSealed(third);

		assertEquals(List.of(1L, 1L, 1L), List.of(first, second, third).stream()
			.map(ProductionRun::runMultiplier).toList());
		assertNotEquals(first.runId(), second.runId());
		assertNotEquals(second.runId(), third.runId());
		assertEquals(0, completed.remainingRuns());
		assertThrows(IllegalStateException.class, () -> completed.afterOutputSealed(third));
	}

	@Test
	void exactExpansionAcceptsLongMaxWithoutMaterializingItsSequence() {
		ProductionRun.RunSequence sequence = ProductionRunExpansion.expand(GOAL, PATTERN, 9,
			Long.MAX_VALUE, InputMode.EXACT, List.of(INPUT), WAREHOUSE, 12,
			Map.of(iron(), 1L), Map.of());
		ProductionRun first = sequence.currentRun();
		ProductionRun.RunSequence next = sequence.afterOutputSealed(first);

		assertEquals(Long.MAX_VALUE, sequence.remainingRuns());
		assertEquals(Long.MAX_VALUE - 1, next.remainingRuns());
		assertEquals(1, first.runMultiplier());
		assertEquals(1, next.currentRun().runMultiplier());
		assertNotEquals(first.runId(), next.currentRun().runId());
	}

	@Test
	void partialExpansionUsesTheLargestNumberOfCompleteFeasibleOperations() {
		StackKey iron = iron();
		ProductionRun.RunSequence sequence = ProductionRunExpansion.expand(GOAL, PATTERN, 9, 5,
			InputMode.PARTIAL, List.of(INPUT), WAREHOUSE, 12, Map.of(iron, 3L),
			Map.of(iron, 7L));
		ProductionRun run = sequence.currentRun();

		assertEquals(1, sequence.remainingRuns());
		assertEquals(2, run.runMultiplier());
		assertEquals(Map.of(iron, 6L), run.manifest());
	}

	@Test
	void runSequenceConstructorKeepsAnEmptySequenceLegal() {
		ProductionRun.RunSequence completed = new ProductionRun.RunSequence(null, 0);

		assertEquals(0, completed.remainingRuns());
		assertEquals(null, completed.currentRun());
	}

	@Test
	void runSequenceConstructorRejectsExactRunsWithoutUnitMultiplier() {
		assertThrows(IllegalArgumentException.class,
			() -> new ProductionRun.RunSequence(run(InputMode.EXACT, 2), 1));
	}

	@Test
	void runSequenceConstructorRejectsMultipleBulkOrPartialRuns() {
		assertThrows(IllegalArgumentException.class,
			() -> new ProductionRun.RunSequence(run(InputMode.BULK, 2), 2));
		assertThrows(IllegalArgumentException.class,
			() -> new ProductionRun.RunSequence(run(InputMode.PARTIAL, 2), 2));
	}
	@Test
	void runsRejectNonPositiveMultipliers() {
		assertThrows(IllegalArgumentException.class,
			() -> new ProductionRun(UUID.randomUUID(), GOAL, PATTERN, 9, 0, InputMode.BULK,
				List.of(INPUT), WAREHOUSE, 12, Map.of(iron(), 1L)));
	}

	private static ProductionRun run(InputMode inputMode, long multiplier) {
		return new ProductionRun(UUID.randomUUID(), GOAL, PATTERN, 9, multiplier, inputMode,
			List.of(INPUT), WAREHOUSE, 12, Map.of(iron(), 1L));
	}
	private static StackKey iron() {
		return new StackKey(new ItemStack(Items.IRON_INGOT));
	}

	private static CreateRouteKey route(long leastSignificantBits, String address) {
		return new CreateRouteKey(new UUID(0, leastSignificantBits), address);
	}
}
