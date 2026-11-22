package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommandScope;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelNewTaskPacket.Failure;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelNewTaskPacket.PatternAdmission;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelNewTaskPacket.SubmissionSnapshot;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelAuthorityValidator;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.simibubi.create.content.logistics.box.PackageItem;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class FactoryPanelNewTaskPacketTest {
	private static final UUID CLUSTER = id(1), EPOCH = id(2), PANEL = id(3), SESSION = id(4);
	private static final UUID NETWORK = id(5), PATTERN = id(6), EXISTING_GOAL = id(7);
	private static final SpaceAddress ADDRESS = new SpaceAddress(Level.OVERWORLD, null,
		new BlockPos(4, 5, 6));
	private static final FactoryPanelOpening OPENING = new FactoryPanelOpening(ADDRESS, PANEL,
		SESSION, CLUSTER, EPOCH);
	private static final CreateRouteKey ROUTE = new CreateRouteKey(NETWORK, "agent_input");

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

	@Test
	void validSubmissionCreatesAndSelectsOneFreshGoalWithoutReplacingTheDraft() {
		StackKey output = key(Items.IRON_INGOT);
		StackKey input = key(Items.COBBLESTONE);
		FactoryPanelNewTaskPacket.Draft draft = draft(output, 3, InputMode.EXACT, 8);
		RecordingTarget target = target(snapshot(output, 2, List.of(Map.of(input, 4L))));
		ArrayDeque<UUID> generated = new ArrayDeque<>(List.of(EXISTING_GOAL, id(80)));

		FactoryPanelNewTaskPacket.SubmissionResult result = packet(draft).execute(authority(),
			target, generated::removeFirst);

		assertTrue(result.succeeded());
		assertEquals(id(80), result.selectedGoalId().orElseThrow());
		assertNotEquals(EXISTING_GOAL, result.selectedGoalId().orElseThrow());
		assertEquals(draft, result.preservedDraft());
		assertEquals(1, target.submissions.size());
		assertEquals(id(80), target.submissions.getFirst().goalId());
		assertEquals(6, target.submissions.getFirst().requestedOutputCount());
	}

	@Test
	void bulkAndPartialManifestMaySpanMultiplePackages() {
		StackKey output = key(Items.IRON_INGOT);
		StackKey input = key(Items.COBBLESTONE);
		long multiplier = (long) PackageItem.SLOTS * input.stack().getMaxStackSize() + 1;
		SubmissionSnapshot snapshot = new SubmissionSnapshot(CLUSTER, EPOCH, 11, 7, Set.of(ROUTE),
			EnumSet.of(InputMode.EXACT, InputMode.BULK, InputMode.PARTIAL), -10, 10,
			Map.of(output, pattern(output, 1, List.of(Map.of(input, 1L)))), Set.of(EXISTING_GOAL));

		for (InputMode mode : List.of(InputMode.BULK, InputMode.PARTIAL)) {
			FactoryPanelNewTaskPacket.Draft draft = draft(output, multiplier, mode, 0);
			RecordingTarget target = target(snapshot);
			var result = packet(draft).execute(authority(), target, () -> id(100 + mode.ordinal()));
			assertTrue(result.succeeded(), mode.name());
			assertEquals(multiplier, target.submissions.getFirst().manifests().getFirst().get(input),
				mode.name());
		}
	}
	@Test
	void validationFailuresAreStructuredAndPreserveEveryEnteredField() {
		StackKey output = key(Items.IRON_INGOT);
		StackKey input = key(Items.COBBLESTONE);
		List<Case> cases = List.of(
			new Case(draft(output, 0, InputMode.EXACT, 0),
				snapshot(output, 1, List.of(Map.of(input, 1L))), Failure.NON_POSITIVE_MULTIPLIER),
			new Case(new FactoryPanelNewTaskPacket.Draft(output, NETWORK, " bad\u0000address ", 1,
				InputMode.EXACT, 0), snapshot(output, 1, List.of(Map.of(input, 1L))),
				Failure.INVALID_ADDRESS),
			new Case(draft(output, 1, InputMode.PARTIAL, 0),
				snapshot(output, 1, List.of(Map.of(input, 1L))), Failure.INVALID_MODE),
			new Case(draft(output, 1, InputMode.EXACT, 99),
				snapshot(output, 1, List.of(Map.of(input, 1L))), Failure.INVALID_PRIORITY),
			new Case(new FactoryPanelNewTaskPacket.Draft(output, id(999), ROUTE.address(), 1,
				InputMode.EXACT, 0), snapshot(output, 1, List.of(Map.of(input, 1L))),
				Failure.INVALID_ROUTE),
			new Case(draft(output, 2, InputMode.EXACT, 0),
				snapshot(output, Long.MAX_VALUE, List.of(Map.of(input, 1L))),
				Failure.COUNT_OVERFLOW),
			new Case(draft(output, 2, InputMode.BULK, 0),
				snapshot(output, 1, List.of(Map.of(input, Long.MAX_VALUE))),
				Failure.MANIFEST_OVERFLOW),
			new Case(draft(output, 1, InputMode.EXACT, 0),
				snapshot(output, 1, List.of(Map.of(input,
					(long) input.stack().getMaxStackSize() * PackageItem.SLOTS + 1))),
				Failure.PACKAGE_CAPACITY)
		);

		for (Case testCase : cases) {
			RecordingTarget target = target(testCase.snapshot());
			var result = packet(testCase.draft()).execute(authority(), target, () -> id(81));
			assertEquals(Optional.of(testCase.failure()), result.failure(), testCase.failure().name());
			assertEquals(testCase.draft(), result.preservedDraft());
			assertTrue(result.selectedGoalId().isEmpty());
			assertTrue(target.submissions.isEmpty());
		}
	}

	@Test
	void unregisteredStackAndStaleEpochGenerationOrRevisionFailBeforeAdmission() {
		StackKey registered = key(Items.IRON_INGOT);
		StackKey unregistered = new StackKey(new ItemStack(new Item(new Item.Properties())));
		SubmissionSnapshot current = snapshot(registered, 1,
			List.of(Map.of(key(Items.COBBLESTONE), 1L)));

		assertFailure(packet(draft(unregistered, 1, InputMode.EXACT, 0)), current,
			Failure.INVALID_STACK);
		assertFailure(packet(draft(registered, 1, InputMode.EXACT, 0)), new SubmissionSnapshot(
			CLUSTER, id(200), 11, 7, Set.of(ROUTE), EnumSet.of(InputMode.EXACT, InputMode.BULK),
			-10, 10, Map.of(registered, pattern(registered, 1,
				List.of(Map.of(key(Items.COBBLESTONE), 1L)))), Set.of(EXISTING_GOAL)),
			Failure.STALE_EPOCH);
		assertFailure(packet(draft(registered, 1, InputMode.EXACT, 0)), new SubmissionSnapshot(
			CLUSTER, EPOCH, 12, 7, Set.of(ROUTE), EnumSet.of(InputMode.EXACT, InputMode.BULK),
			-10, 10, Map.of(registered, pattern(registered, 1,
				List.of(Map.of(key(Items.COBBLESTONE), 1L)))), Set.of(EXISTING_GOAL)),
			Failure.STALE_GENERATION);
		assertFailure(packet(draft(registered, 1, InputMode.EXACT, 0)), new SubmissionSnapshot(
			CLUSTER, EPOCH, 11, 8, Set.of(ROUTE), EnumSet.of(InputMode.EXACT, InputMode.BULK),
			-10, 10, Map.of(registered, pattern(registered, 1,
				List.of(Map.of(key(Items.COBBLESTONE), 1L)))), Set.of(EXISTING_GOAL)),
			Failure.STALE_REVISION);
	}

	private static void assertFailure(FactoryPanelNewTaskPacket packet,
		SubmissionSnapshot snapshot, Failure failure) {
		RecordingTarget target = target(snapshot);
		var result = packet.execute(authority(), target, () -> id(90));
		assertEquals(Optional.of(failure), result.failure());
		assertFalse(result.succeeded());
		assertTrue(target.submissions.isEmpty());
	}

	private static FactoryPanelNewTaskPacket packet(FactoryPanelNewTaskPacket.Draft draft) {
		return new FactoryPanelNewTaskPacket(OPENING, 11, 7, draft);
	}

	private static FactoryPanelNewTaskPacket.Draft draft(StackKey output, long multiplier,
		InputMode mode, int priority) {
		return new FactoryPanelNewTaskPacket.Draft(output, NETWORK, ROUTE.address(), multiplier,
			mode, priority);
	}

	private static SubmissionSnapshot snapshot(StackKey output, long outputPerOperation,
		List<Map<StackKey, Long>> manifests) {
		return new SubmissionSnapshot(CLUSTER, EPOCH, 11, 7, Set.of(ROUTE),
			EnumSet.of(InputMode.EXACT, InputMode.BULK), -10, 10,
			Map.of(output, pattern(output, outputPerOperation, manifests)), Set.of(EXISTING_GOAL));
	}

	private static PatternAdmission pattern(StackKey output, long outputPerOperation,
		List<Map<StackKey, Long>> manifests) {
		return new PatternAdmission(PATTERN, output, outputPerOperation, manifests);
	}

	private static RecordingTarget target(SubmissionSnapshot snapshot) {
		return new RecordingTarget(snapshot);
	}

	private static FactoryPanelAuthorityValidator.Validation authority() {
		return FactoryPanelAuthorityValidator.validate(
			new FactoryPanelAuthorityValidator.Request(ADDRESS, PANEL, SESSION, EPOCH, 11, 7),
			new FactoryPanelAuthorityValidator.Facts(ADDRESS, PANEL, SESSION, CLUSTER, CLUSTER,
				EPOCH, 11, 7, true, true, true, true, true, true, true))
			.orElseThrow();
	}

	private static StackKey key(Item item) {
		return new StackKey(new ItemStack(item));
	}

	private static UUID id(long value) {
		return new UUID(0, value);
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

	private record Case(FactoryPanelNewTaskPacket.Draft draft, SubmissionSnapshot snapshot,
		Failure failure) {}

	private static final class RecordingTarget implements FactoryPanelNewTaskPacket.CommandTarget {
		private final SubmissionSnapshot snapshot;
		private final List<FactoryPanelNewTaskPacket.ValidatedSubmission> submissions =
			new ArrayList<>();

		private RecordingTarget(SubmissionSnapshot snapshot) {
			this.snapshot = snapshot;
		}

		@Override
		public Optional<SubmissionSnapshot> capture(
			FactoryPanelCommandScope scope) {
			return Optional.of(snapshot);
		}

		@Override
		public boolean submit(
			FactoryPanelCommandScope scope,
			FactoryPanelNewTaskPacket.ValidatedSubmission submission) {
			submissions.add(submission);
			return true;
		}
	}
}
