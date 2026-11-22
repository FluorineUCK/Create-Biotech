package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.CoordinationAssociation;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibrarySearchCoordinator;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.DefaultRoutePolicy;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryDirectory;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryRegistration;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import static com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService.PublicService.LIBRARY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.PatternPriorityPolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternReply;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class LibrarySearchCoordinatorTest {
	private static final UUID CLUSTER = id(1);
	private static final UUID COMPUTER = id(2);
	private static final UUID LOGISTICS = id(3);
	private static final UUID QUERY = id(4);
	private static final UUID EPOCH = id(5);

	@Test
	void completedSearchRejectsLateRepliesWithoutReopeningSelection() {
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory(
			registration(10, 8, 70, LibraryRegistration.LoadState.LOADED)));
		PatternQuery query = coordinator.begin(request(1)).queries().getFirst();
		PatternRecord match = pattern(100, query.libraryId(), OptionalInt.empty());
		var matched = coordinator.accept(PatternReply.matchBatch(query, List.of(match), 1), 1);
		UUID branch = matched.speculativeAllocations().getFirst().branchId();
		coordinator.acknowledgeSpeculation(branch);
		var completed = complete(coordinator, coordinator.accept(PatternReply.groupEnd(query, 1), 1));
		assertEquals(LibrarySearchCoordinator.Status.COMPLETE, completed.status());
		assertEquals(Optional.of(branch), completed.retainedSpeculativeBranch());
		for (PatternReply late : List.of(PatternReply.groupEnd(query, 1),
			PatternReply.matchBatch(query, List.of(pattern(101, query.libraryId(), OptionalInt.empty())), 2))) {
			var rejected = coordinator.accept(late, 8);
			assertEquals(LibrarySearchCoordinator.Status.COMPLETE, rejected.status());
			assertTrue(rejected.staleReplyRejected());
			assertTrue(rejected.queries().isEmpty());
			assertTrue(rejected.speculativeAllocations().isEmpty());
			assertTrue(rejected.providerSources().isEmpty());
			assertTrue(rejected.winner().isEmpty());
			assertTrue(rejected.cancelledSpeculativeBranches().isEmpty());
			assertEquals(1, coordinator.outstandingSpeculation());
			assertEquals(Optional.of(match.patternId()), coordinator.patternForBranch(branch));
		}
	}

	@Test
	void publicSubnetSummaryIsAnOpaqueLibraryCapability() {
		LibraryDirectory empty = new LibraryDirectory();
		assertTrue(SubnetBoundaryService.librarySummary(empty).isEmpty());

		LibraryDirectory directory = directory(registration(10, 8, 30,
			LibraryRegistration.LoadState.LOADED));

		assertEquals(SubnetBoundaryService.PublicServiceSummary.of(LIBRARY),
			SubnetBoundaryService.librarySummary(directory).orElseThrow());
	}

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			java.lang.reflect.Field bootstrapped =
				net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null)
				return;
			Object empty = loadingModList.getMethod("of", List.class, List.class, List.class,
				List.class, java.util.Map.class).invoke(null, List.of(), List.of(), List.of(),
				List.of(), java.util.Map.of());
			if (empty == null)
				throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void oneArgumentQueryableByCannotExposeARemoteReachableLibrary() {
		LibraryRegistration remote = new LibraryRegistration(id(90), id(91),
			new RecipeSourceLocation(Level.OVERWORLD, null, new BlockPos(9, 0, 0)), 1,
			CommunicationFrequency.of("7"), 0, 1, LibraryRegistration.LoadState.LOADED,
			LibraryRegistration.Reachability.REACHABLE);

		assertFalse(remote.queryableBy(CLUSTER));
	}

	@Test
	void equalPriorityLibrariesLaunchTogetherAndEveryQueryNeedsGroupEnd() {
		LibraryDirectory directory = directory(registration(10, 8, 30,
			LibraryRegistration.LoadState.LOADED), registration(11, 8, 31,
			LibraryRegistration.LoadState.LOADED), registration(12, 2, 32,
			LibraryRegistration.LoadState.LOADED));
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory);

		LibrarySearchCoordinator.Update opened = coordinator.begin(request(2));

		assertEquals(List.of(id(10), id(11)), opened.queries().stream()
			.map(PatternQuery::libraryId).toList());
		assertEquals(8, coordinator.currentWave().orElseThrow().priority());

		PatternQuery first = opened.queries().getFirst();
		PatternRecord match = pattern(100, first.libraryId(), OptionalInt.empty());
		LibrarySearchCoordinator.Update matching = coordinator.accept(
			PatternReply.matchBatch(first, List.of(match), first.pageCursor() + 1), 0);
		assertTrue(matching.queries().isEmpty());
		assertTrue(matching.winner().isEmpty());

		coordinator.accept(PatternReply.groupEnd(first, first.pageCursor() + 1), 0);
		PatternQuery second = opened.queries().get(1);
		LibrarySearchCoordinator.Update closed = complete(coordinator, coordinator.accept(
			PatternReply.groupEnd(second, second.pageCursor()), 0));
		assertEquals(match.patternId(), closed.winner().orElseThrow().patternId());
		assertTrue(closed.queries().isEmpty(), "a matching group must suppress lower priorities");
	}

	@Test
	void matchedGroupEmitsPatternProviderSourcesWithoutPrivatelySelecting() {
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory(
			registration(10, 8, 30, LibraryRegistration.LoadState.LOADED)));
		PatternQuery query = coordinator.begin(request(0)).queries().getFirst();
		coordinator.accept(PatternReply.matchBatch(query,
			List.of(pattern(100, query.libraryId(), OptionalInt.empty())), 1), 0);

		LibrarySearchCoordinator.Update ready = coordinator.accept(PatternReply.groupEnd(query, 1), 0);

		assertTrue(ready.winner().isEmpty());
		assertEquals(LibrarySearchCoordinator.Status.SELECTING_ROUTE, ready.status());
		assertEquals(List.of(RouteCandidate.ProviderKind.PATTERN), ready.providerSources().stream()
			.map(ProductionRouteTable.ProviderSource::providerKind).toList());
		assertEquals(new RouteSelectionScope(EPOCH, QUERY,
			new StackKey(new ItemStack(Items.IRON_INGOT)), 5, 0, QUERY),
			ready.providerSources().getFirst().scope());
	}

	@Test
	void emptyCompletedGroupOpensNextPriorityWave() {
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory(
			registration(10, 8, 20, LibraryRegistration.LoadState.LOADED),
			registration(11, 8, 21, LibraryRegistration.LoadState.LOADED),
			registration(12, 3, 22, LibraryRegistration.LoadState.LOADED)));
		LibrarySearchCoordinator.Update opened = coordinator.begin(request(0));

		coordinator.accept(PatternReply.groupEnd(opened.queries().getFirst(), 0), 0);
		LibrarySearchCoordinator.Update next = coordinator.accept(
			PatternReply.groupEnd(opened.queries().get(1), 0), 0);

		assertEquals(List.of(id(12)), next.queries().stream().map(PatternQuery::libraryId).toList());
		assertEquals(3, coordinator.currentWave().orElseThrow().priority());
	}

	@Test
	void unloadedLibraryWaitsAndJoinsItsExistingWaveWhenLoaded() {
		LibraryRegistration unloaded = registration(10, 8, 40,
			LibraryRegistration.LoadState.UNLOADED);
		LibraryDirectory directory = directory(unloaded,
			registration(11, 2, 41, LibraryRegistration.LoadState.LOADED));
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory);

		LibrarySearchCoordinator.Update opened = coordinator.begin(request(0));
		assertTrue(opened.queries().isEmpty());
		assertEquals(LibrarySearchCoordinator.Status.WAITING_LIBRARY, opened.status());
		assertEquals(List.of(id(10)), coordinator.currentWave().orElseThrow().waitingLibraries());

		directory.publish(unloaded.withLoadState(LibraryRegistration.LoadState.LOADED));
		LibrarySearchCoordinator.Update resumed = coordinator.refresh(0);
		assertEquals(List.of(id(10)), resumed.queries().stream().map(PatternQuery::libraryId).toList());
		assertEquals(8, coordinator.currentWave().orElseThrow().priority());
	}

	@Test
	void queriedLibraryThatBecomesUnavailableWaitsAndRestartsAtPublishedGeneration() {
		LibraryRegistration loaded = registration(10, 8, 40,
			LibraryRegistration.LoadState.LOADED);
		LibraryDirectory directory = directory(loaded);
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory);
		PatternQuery first = coordinator.begin(request(0)).queries().getFirst();

		LibrarySearchCoordinator.Update waiting = coordinator.accept(
			PatternReply.unavailable(first), 0);
		assertEquals(LibrarySearchCoordinator.Status.WAITING_LIBRARY, waiting.status());

		directory.publish(new LibraryRegistration(loaded.libraryId(), loaded.clusterId(),
			loaded.address(), loaded.formationGeneration(), loaded.frequency(), loaded.priority(),
			41, LibraryRegistration.LoadState.LOADED, loaded.reachability()));
		LibrarySearchCoordinator.Update resumed = coordinator.refresh(0);

		assertEquals(1, resumed.queries().size());
		assertEquals(41, resumed.queries().getFirst().queryGeneration());
	}

	@Test
	void unavailableRequiresANewDirectoryPublicationAndCancelsTheOldAttempt() {
		LibraryRegistration loaded = registration(10, 8, 40, LibraryRegistration.LoadState.LOADED);
		LibraryDirectory directory = directory(loaded);
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory);
		PatternQuery first = coordinator.begin(request(1)).queries().getFirst();
		LibrarySearchCoordinator.Update match = coordinator.accept(PatternReply.matchBatch(first,
			List.of(pattern(100, first.libraryId(), OptionalInt.empty())), 1), 1);

		LibrarySearchCoordinator.Update unavailable = coordinator.accept(PatternReply.unavailable(first), 1);

		assertEquals(1, unavailable.cancelledSpeculativeBranches().size());
		assertTrue(coordinator.refresh(1).queries().isEmpty(),
			"the unchanged loaded directory entry must not create a retry storm");
		directory.publish(loaded);
		PatternQuery retry = coordinator.refresh(1).queries().getFirst();
		assertFalse(first.attemptId().equals(retry.attemptId()));
		coordinator.accept(PatternReply.groupEnd(retry, 0), 1);
		assertEquals(LibrarySearchCoordinator.Status.NOT_FOUND,
			coordinator.refresh(1).status(), "the old attempt match must not survive");
	}

	@Test
	void restartRequiredUsesTheFullAttemptFenceAndReleasesSpeculation() {
		LibraryRegistration loaded = registration(10, 8, 40, LibraryRegistration.LoadState.LOADED);
		LibraryDirectory directory = directory(loaded);
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory);
		PatternQuery first = coordinator.begin(request(1)).queries().getFirst();
		coordinator.accept(PatternReply.matchBatch(first,
			List.of(pattern(100, first.libraryId(), OptionalInt.empty())), 1), 1);

		LibrarySearchCoordinator.Update restart = coordinator.accept(
			PatternReply.restartRequired(first, 41), 1);

		assertEquals(1, restart.cancelledSpeculativeBranches().size());
		directory.publish(new LibraryRegistration(loaded.libraryId(), loaded.clusterId(), loaded.address(),
			loaded.formationGeneration(), loaded.frequency(), loaded.priority(), 41,
			loaded.loadState(), loaded.reachability()));
		PatternQuery retry = coordinator.refresh(1).queries().getFirst();
		assertFalse(first.attemptId().equals(retry.attemptId()));
		assertTrue(coordinator.accept(PatternReply.groupEnd(first, 1), 1).staleReplyRejected());
	}

	@Test
	void directoryCatalogAdvanceInvalidatesIssuedAttemptBeforeLateReply() {
		LibraryRegistration catalog40 = registration(10, 8, 40, LibraryRegistration.LoadState.LOADED);
		LibraryDirectory directory = directory(catalog40);
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory);
		PatternQuery old = coordinator.begin(request(1)).queries().getFirst();
		coordinator.accept(PatternReply.matchBatch(old,
			List.of(pattern(100, old.libraryId(), OptionalInt.empty())), 1), 1);
		directory.publish(new LibraryRegistration(catalog40.libraryId(), catalog40.clusterId(),
			catalog40.address(), catalog40.formationGeneration(), catalog40.frequency(), catalog40.priority(),
			41, catalog40.loadState(), catalog40.reachability()));

		LibrarySearchCoordinator.Update refreshed = coordinator.refresh(1);

		assertEquals(1, refreshed.cancelledSpeculativeBranches().size());
		PatternQuery replacement = refreshed.queries().getFirst();
		assertEquals(41, replacement.queryGeneration());
		assertTrue(coordinator.accept(PatternReply.groupEnd(old, 1), 1).staleReplyRejected());
		coordinator.accept(PatternReply.groupEnd(replacement, 0), 1);
		assertEquals(LibrarySearchCoordinator.Status.NOT_FOUND, coordinator.refresh(1).status());
	}

	@Test
	void newerFormationMayResetCatalogGenerationWithoutPermanentAwaiting() {
		LibraryRegistration oldFormation = registration(10, 8, 40, LibraryRegistration.LoadState.LOADED);
		LibraryDirectory directory = directory(oldFormation);
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory);
		PatternQuery old = coordinator.begin(request(0)).queries().getFirst();
		directory.publish(new LibraryRegistration(oldFormation.libraryId(), oldFormation.clusterId(),
			oldFormation.address(), 2, oldFormation.frequency(), oldFormation.priority(), 1,
			oldFormation.loadState(), oldFormation.reachability()));

		LibrarySearchCoordinator.Update refreshed = coordinator.refresh(0);

		PatternQuery replacement = refreshed.queries().getFirst();
		assertEquals(2, replacement.formationGeneration());
		assertEquals(1, replacement.queryGeneration());
		assertTrue(coordinator.accept(PatternReply.groupEnd(old, 0), 0).staleReplyRejected());
	}

	@Test
	void restartWaitIsReleasedByNewFormationWithResetCatalog() {
		LibraryRegistration oldFormation = registration(10, 8, 40, LibraryRegistration.LoadState.LOADED);
		LibraryDirectory directory = directory(oldFormation);
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory);
		PatternQuery old = coordinator.begin(request(0)).queries().getFirst();
		coordinator.accept(PatternReply.restartRequired(old, 41), 0);
		directory.publish(new LibraryRegistration(oldFormation.libraryId(), oldFormation.clusterId(),
			oldFormation.address(), 2, oldFormation.frequency(), oldFormation.priority(), 1,
			oldFormation.loadState(), oldFormation.reachability()));

		PatternQuery replacement = coordinator.refresh(0).queries().getFirst();

		assertEquals(2, replacement.formationGeneration());
		assertEquals(1, replacement.queryGeneration());
	}

	@Test
	void staleGenerationRepliesAreRejectedWithoutClosingTheWave() {
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory(
			registration(10, 8, 50, LibraryRegistration.LoadState.LOADED)));
		PatternQuery query = coordinator.begin(request(0)).queries().getFirst();

		PatternReply stale = new PatternReply(query.queryId(), query.requesterComputerId(),
			query.logisticsId(), query.libraryId(), query.queryGeneration() - 1,
			PatternReply.Status.GROUP_END, List.of(), query.pageCursor());
		LibrarySearchCoordinator.Update rejected = coordinator.accept(stale, 0);

		assertTrue(rejected.staleReplyRejected());
		assertFalse(coordinator.currentWave().orElseThrow().awaitingGroupEnd().isEmpty());
	}

	@Test
	void completeMatchUsesOnlyResidualAlchemistCapacityForSideEffectFreeSpeculation() {
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory(
			registration(10, 8, 60, LibraryRegistration.LoadState.LOADED),
			registration(11, 8, 61, LibraryRegistration.LoadState.LOADED)));
		LibrarySearchCoordinator.Update opened = coordinator.begin(request(1));
		PatternQuery first = opened.queries().getFirst();
		PatternRecord firstMatch = pattern(100, first.libraryId(), OptionalInt.empty());

		LibrarySearchCoordinator.Update noCapacity = coordinator.accept(
			PatternReply.matchBatch(first, List.of(firstMatch), 1), 0);
		assertTrue(noCapacity.speculativeAllocations().isEmpty());

		PatternQuery second = opened.queries().get(1);
		PatternRecord secondMatch = pattern(101, second.libraryId(), OptionalInt.empty());
		LibrarySearchCoordinator.Update hasCapacity = coordinator.accept(
			PatternReply.matchBatch(second, List.of(secondMatch), 1), 1);
		LibrarySearchCoordinator.SpeculativeAllocation allocation =
			hasCapacity.speculativeAllocations().getFirst();
		assertEquals(second.libraryId(), allocation.libraryId());
		assertEquals(secondMatch.patternId(), allocation.patternId());
		assertEquals(Optional.of(secondMatch.patternId()), coordinator.patternForBranch(allocation.branchId()));
		assertEquals(1, coordinator.outstandingSpeculation());
	}

	@Test
	void closingGroupRetainsWinnerAndCancelsOtherSpeculativeBranchesWithoutWorldActions() {
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory(
			registration(10, 8, 70, LibraryRegistration.LoadState.LOADED),
			registration(11, 8, 71, LibraryRegistration.LoadState.LOADED)));
		LibrarySearchCoordinator.Update opened = coordinator.begin(request(2));
		PatternQuery first = opened.queries().getFirst();
		PatternQuery second = opened.queries().get(1);
		PatternRecord firstMatch = pattern(100, first.libraryId(), OptionalInt.empty());
		PatternRecord secondMatch = pattern(101, second.libraryId(), OptionalInt.empty());
		LibrarySearchCoordinator.Update firstSpec = coordinator.accept(
			PatternReply.matchBatch(first, List.of(firstMatch), 1), 2);
		LibrarySearchCoordinator.Update secondSpec = coordinator.accept(
			PatternReply.matchBatch(second, List.of(secondMatch), 1), 2);

		coordinator.accept(PatternReply.groupEnd(first, 1), 2);
		LibrarySearchCoordinator.Update closed = complete(coordinator, coordinator.accept(
			PatternReply.groupEnd(second, 1), 2));

		UUID winningPattern = closed.winner().orElseThrow().patternId();
		UUID retainedBranch = closed.retainedSpeculativeBranch().orElseThrow();
		assertEquals(winningPattern, coordinator.patternForBranch(retainedBranch).orElseThrow());
		assertEquals(1, closed.cancelledSpeculativeBranches().size());
		assertEquals(1, coordinator.outstandingSpeculation());
		assertEquals(2, firstSpec.speculativeAllocations().size()
			+ secondSpec.speculativeAllocations().size());
	}

	@Test
	void routePolicyWithoutWinnerCancelsSpeculationAndTerminatesTheSearch() {
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory(
			registration(10, 8, 70, LibraryRegistration.LoadState.LOADED)));
		PatternQuery query = coordinator.begin(request(1)).queries().getFirst();
		LibrarySearchCoordinator.Update matched = coordinator.accept(PatternReply.matchBatch(query,
			List.of(pattern(100, query.libraryId(), OptionalInt.empty())), 1), 1);
		UUID branchId = matched.speculativeAllocations().getFirst().branchId();
		coordinator.acknowledgeSpeculation(branchId);
		LibrarySearchCoordinator.Update ready = coordinator.accept(
			PatternReply.groupEnd(query, 1), 1);

		LibrarySearchCoordinator.Update blocked = coordinator.completeRouteSelection(
			new DefaultRoutePolicy.Decision(Optional.empty(),
				RouteCandidate.Blocker.AGENT_CONFLICT, false));

		assertEquals(LibrarySearchCoordinator.Status.NOT_FOUND, blocked.status());
		assertEquals(Set.of(branchId), blocked.cancelledSpeculativeBranches());
		assertEquals(0, coordinator.outstandingSpeculation());
		assertEquals(LibrarySearchCoordinator.Status.NOT_FOUND, coordinator.refresh(1).status(),
			"a hard policy rejection must not remain in a route-selection retry spin");
		assertEquals(LibrarySearchCoordinator.Status.SELECTING_ROUTE, ready.status());
	}

	@Test
	void outstandingSpeculationNeverExceedsReportedResidualAcrossMatchBatches() {
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory(
			registration(10, 8, 70, LibraryRegistration.LoadState.LOADED)));
		PatternQuery query = coordinator.begin(request(1)).queries().getFirst();
		LibrarySearchCoordinator.Update first = coordinator.accept(PatternReply.matchBatch(query,
			List.of(pattern(100, query.libraryId(), OptionalInt.empty())), 1), 99);
		LibrarySearchCoordinator.Update second = coordinator.accept(PatternReply.matchBatch(
			query.continueAt(1), List.of(pattern(101, query.libraryId(), OptionalInt.empty())), 2), 99);

		assertEquals(1, first.speculativeAllocations().size());
		assertTrue(second.speculativeAllocations().isEmpty());
		coordinator.acknowledgeSpeculation(first.speculativeAllocations().getFirst().branchId());
		assertEquals(1, coordinator.outstandingSpeculation());
	}

	@Test
	void priorityOverrideRequiresAnExactActiveAlchemistCoordinationLease() {
		LibraryRegistration loaded = registration(10, 8, 70, LibraryRegistration.LoadState.LOADED);
		PatternRecord target = pattern(100, loaded.libraryId(), OptionalInt.of(30));
		PatternRecord other = pattern(101, loaded.libraryId(), OptionalInt.of(40));
		PatternPriorityAuthority activeOtherPattern = authoritativeCoordination(loaded, other.patternId());
		PatternPriorityAuthority exact = authoritativeCoordination(loaded, target.patternId());
		assertTrue(exact.authorizes(loaded.clusterId(), loaded.libraryId(), target.patternId(), loaded.publicCatalogGeneration()));
		assertFalse(activeOtherPattern.authorizes(loaded.clusterId(), loaded.libraryId(), target.patternId(), loaded.publicCatalogGeneration()));
		LibrarySearchCoordinator coordinator = new LibrarySearchCoordinator(directory(loaded));
		PatternQuery query = coordinator.begin(request(0, activeOtherPattern)).queries().getFirst();
		coordinator.accept(PatternReply.matchBatch(query, List.of(target), 1), 0);
		LibrarySearchCoordinator.Update closed = complete(coordinator,
			coordinator.accept(PatternReply.groupEnd(query, 1), 0));
		assertEquals(8, closed.winner().orElseThrow().effectivePriority());
		LibrarySearchCoordinator exactCoordinator = new LibrarySearchCoordinator(directory(loaded));
		PatternQuery exactQuery = exactCoordinator.begin(request(0, exact)).queries().getFirst();
		exactCoordinator.accept(PatternReply.matchBatch(exactQuery, List.of(target), 1), 0);
		assertEquals(30, complete(exactCoordinator,
			exactCoordinator.accept(PatternReply.groupEnd(exactQuery, 1), 0))
			.winner().orElseThrow().effectivePriority());
	}

	@Test
	void patternOverrideIsEffectiveOnlyWithAlchemistCoordination() {
		PatternRecord overridden = pattern(100, id(10), OptionalInt.of(30));

		assertEquals(new PatternPriorityPolicy.Resolution(OptionalInt.of(30), false, 8),
			PatternPriorityPolicy.resolve(overridden.priorityOverride(), 8, false));
		assertEquals(new PatternPriorityPolicy.Resolution(OptionalInt.of(30), true, 30),
			PatternPriorityPolicy.resolve(overridden.priorityOverride(), 8, true));
	}

	@Test
	void terminalSearchIgnoresDirectoryChangesUntilAnExplicitNewSearchGeneration() {
		var peer = registration(10, 8, 70, LibraryRegistration.LoadState.LOADED);
		var directory = directory(peer);
		var coordinator = new LibrarySearchCoordinator(directory);
		var oldRequest = request(0);
		var query = coordinator.begin(oldRequest).queries().getFirst();
		coordinator.accept(PatternReply.matchBatch(query, List.of(pattern(100, peer.libraryId(), OptionalInt.empty())), 1), 0);
		complete(coordinator, coordinator.accept(PatternReply.groupEnd(query, 1), 0));
		var before = coordinator.currentWave();
		directory.publish(registration(10, 8, 71, LibraryRegistration.LoadState.LOADED));
		for (var late : List.of(PatternReply.unavailable(query), PatternReply.restartRequired(query, 71))) {
			var rejected = coordinator.accept(late, 0);
			assertEquals(LibrarySearchCoordinator.Status.COMPLETE, rejected.status());
			assertTrue(rejected.staleReplyRejected());
			assertTrue(rejected.queries().isEmpty());
			assertEquals(before, coordinator.currentWave());
		}
		var next = new LibrarySearchCoordinator.SearchRequest(oldRequest.queryId(), oldRequest.requesterComputerId(),
			oldRequest.requesterClusterId(), oldRequest.epochId(), oldRequest.logisticsId(), oldRequest.frequency(),
			oldRequest.requestedStackKey(), oldRequest.generation() + 1, 1, id(99), oldRequest.maximumReplyBatch(), 0,
			com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternPriorityAccess.NONE);
		var fresh = coordinator.begin(next).queries().getFirst();
		assertEquals(71, fresh.queryGeneration());
		assertFalse(fresh.attemptId().equals(query.attemptId()));
		assertTrue(coordinator.accept(PatternReply.groupEnd(query, 1), 0).staleReplyRejected());
		assertEquals(LibrarySearchCoordinator.Status.NOT_FOUND, coordinator.accept(PatternReply.groupEnd(fresh, 0), 0).status());
		var rejected = coordinator.accept(PatternReply.matchBatch(fresh,
			List.of(pattern(101, peer.libraryId(), OptionalInt.empty())), 1), 8);
		assertEquals(LibrarySearchCoordinator.Status.NOT_FOUND, rejected.status());
		assertTrue(rejected.staleReplyRejected());
		assertTrue(rejected.speculativeAllocations().isEmpty());
		assertTrue(rejected.providerSources().isEmpty());
		assertEquals(0, coordinator.outstandingSpeculation());
	}

	@Test
	void runtimeDerivedPriorityProofDoesNotCrossOwnerLibraryOrCatalogGeneration() {
		var peer = registration(10, 8, 70, LibraryRegistration.LoadState.LOADED);
		var target = pattern(100, peer.libraryId(), OptionalInt.of(30));
		var proof = authoritativeCoordination(peer, target.patternId());
		assertTrue(proof.authorizes(peer.clusterId(), peer.libraryId(), target.patternId(), 70));
		for (var changed : List.of(
			new LibraryRegistration(peer.libraryId(), id(999), peer.address(), 1, peer.frequency(), 8, 70, peer.loadState(), peer.reachability()),
			new LibraryRegistration(id(998), peer.clusterId(), peer.address(), 1, peer.frequency(), 8, 70, peer.loadState(), peer.reachability()),
			registration(10, 8, 71, LibraryRegistration.LoadState.LOADED))) {
			var coordinator = new LibrarySearchCoordinator(directory(changed));
			var query = coordinator.begin(request(0, proof)).queries().getFirst();
			coordinator.accept(PatternReply.matchBatch(query, List.of(target), 1), 0);
			var selected = complete(coordinator, coordinator.accept(PatternReply.groupEnd(query, 1), 0));
			assertEquals(8, selected.winner().orElseThrow().effectivePriority(), "A read contract must not erase exact authoritative qualification");
		}
	}

	private static LibrarySearchCoordinator.SearchRequest request(int residualCapacity) {
		return request(residualCapacity, PatternPriorityAuthority.empty());
	}

	private static PatternPriorityAuthority authoritativeCoordination(LibraryRegistration library,
		UUID patternId) {
		UUID goalId = id(850), processId = id(851), computerId = id(852), epoch = id(853);
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow();
		GoalRecord goal = new GoalRecord(goalId, CLUSTER,
			new GoalSubmission(goalId, patternId, 1, InputMode.BULK),
			new CreateRouteKey(id(854), "coordination"), new PolicySnapshot(1, 0, false),
			GoalState.RUNNABLE, null);
		ProcessRecord process = new ProcessRecord(processId, goalId, computerId, 0, profile,
			ProcessKind.PATTERN_PRIORITY_LEASE, ProcessState.RUNNABLE, null, 0, java.util.Map.of());
		ClusterEngineState state = new ClusterEngineState(CLUSTER, epoch, 1, 1, 0, 0,
			java.util.Map.of(goalId, goal), java.util.Map.of(goalId, 0L),
			java.util.Map.of(goalId, ProcessKind.PATTERN_PRIORITY_LEASE), java.util.Map.of(processId, process),
			List.of(new ClusterEngineState.MemberSnapshot(computerId, profile, true, 1, 1)),
			java.util.Map.of(), java.util.Set.of(), 0, null).withCoordinationAssociation(
				new CoordinationAssociation(processId, library.libraryId(), patternId,
					library.publicCatalogGeneration()));
		return PatternPriorityAuthority.fromAuthoritativeState(state);
	}

	private static LibrarySearchCoordinator.Update complete(LibrarySearchCoordinator coordinator,
		LibrarySearchCoordinator.Update ready) {
		DefaultRoutePolicy policy = new DefaultRoutePolicy();
		return coordinator.completeRouteSelection(policy.select(ready.providerSources().stream()
			.map(ProductionRouteTable.ProviderSource::candidate).toList()));
	}

	private static LibrarySearchCoordinator.SearchRequest request(int residualCapacity,
		PatternPriorityAuthority coordination) {
		return new LibrarySearchCoordinator.SearchRequest(QUERY, COMPUTER, CLUSTER, EPOCH, LOGISTICS,
			CommunicationFrequency.of("17"), new StackKey(new ItemStack(Items.IRON_INGOT)), 5, 0, QUERY, 8, residualCapacity,
			coordination);
	}

	private static LibraryDirectory directory(LibraryRegistration... registrations) {
		LibraryDirectory directory = new LibraryDirectory();
		for (LibraryRegistration registration : registrations)
			directory.publish(registration);
		return directory;
	}

	private static LibraryRegistration registration(long library, int priority, long catalogGeneration,
		LibraryRegistration.LoadState loadState) {
		return new LibraryRegistration(id(library), CLUSTER,
			new RecipeSourceLocation(Level.OVERWORLD, null, new BlockPos((int) library, 64, 0)),
			1, CommunicationFrequency.of("17"), priority, catalogGeneration, loadState,
			LibraryRegistration.Reachability.REACHABLE);
	}

	private static PatternRecord pattern(long pattern, UUID library, OptionalInt override) {
		PatternPageKey source = new PatternPageKey(
			new RecipeSourceLocation(Level.OVERWORLD, library, BlockPos.ZERO), 0, (int) (pattern % 100));
		return new PatternRecord(id(pattern), source,
			List.of(new PatternIngredient(ResourceLocation.withDefaultNamespace("copper_ingot"),
				null, 1, DataComponentPredicate.EMPTY)),
			List.of(new PatternOutput(new StackKey(new ItemStack(Items.IRON_INGOT)), 1)),
			new StandardPatternBody("route_" + pattern), override);
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}
}
