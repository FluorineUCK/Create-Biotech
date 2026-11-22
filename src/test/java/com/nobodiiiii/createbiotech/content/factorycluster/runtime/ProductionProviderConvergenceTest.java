package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.ProductionPlanFreezer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch.ProductionPlanDispatch;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCoordinator;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentFormation;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentProcess;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentRouteRuntimeAdapter;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionRecipeCatalog;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryRegistration;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.OrdinaryProviderRegistry;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalSubmission;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.PolicySnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class ProductionProviderConvergenceTest {
	private static SpaceAddress worldAddress(RecipeSourceLocation source) {
		return new SpaceAddress(source.dimension(), source.subLevelId(), source.localPos());
	}

	private static final UUID CLUSTER = id(1), EPOCH = id(2), NETWORK = id(3);
	private static final UUID LIBRARY = id(4), PATTERN = id(5), AGENT = id(6);
	private static final UUID GOAL = id(7), PROCESS = id(8);
	private static final CommunicationFrequency FREQUENCY = CommunicationFrequency.of("17");
	private static final FactoryRuntimeConfig CONFIG = new FactoryRuntimeConfig(1, 500, 4,
		32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class,
					Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	@Test
	void realPatternDeclaredAgentNativeGaugeAndOrdinaryCollectorsChooseGlobalPriority()
		throws Exception {
		PatternRecord pattern = pattern(List.of());
		try (ProductionFixture fixture = new ProductionFixture(pattern,
			new Priorities(10, 20, 30, 40))) {
			FactoryRuntimeService.SelectedPattern selected = fixture.select();

			assertTrue(fixture.collectionCount() > 0);
			assertEquals(List.of(PATTERN), fixture.collectedPatternIds());
			assertEquals(4, fixture.lastSources().size());
			assertEquals(RouteCandidate.ProviderKind.ORDINARY_PROVIDER,
				selected.provider().providerKind());
			assertEquals("ordinary/" + id(9), selected.provider().providerKey());
		}
	}

	@Test
	void equalPriorityProductionCollectorsOverflowDeterministicallyAndCreateDispatchKeepsRecipeOrder()
		throws Exception {
		StackKey iron = new StackKey(new ItemStack(Items.IRON_INGOT));
		StackKey copper = new StackKey(new ItemStack(Items.COPPER_INGOT));
		PatternRecord pattern = pattern(List.of(
			new PatternIngredient(ResourceLocation.withDefaultNamespace("iron_ingot"), null, 1,
				DataComponentPredicate.EMPTY),
			new PatternIngredient(ResourceLocation.withDefaultNamespace("copper_ingot"), null, 2,
				DataComponentPredicate.EMPTY)));
		try (ProductionFixture fixture = new ProductionFixture(pattern,
			new Priorities(7, 7, 7, 7))) {
			ArrayList<RouteCandidate.ProviderKind> selectedKinds = new ArrayList<>();
			FactoryRuntimeService.SelectedPattern selected = null;
			for (int revision = 1; revision <= 8; revision++) {
				if (revision > 1) fixture.republish(revision);
				selected = fixture.select();
				selectedKinds.add(selected.provider().providerKind());
				fixture.retireSelection();
			}
			assertEquals(selectedKinds.subList(0, 4), selectedKinds.subList(4, 8));
			assertEquals(4, new LinkedHashSet<>(selectedKinds.subList(0, 4)).size());

			ClusterEngineState state = fixture.installDispatchState(pattern);
			FrozenProductionPlan plan = ProductionPlanFreezer.freeze(GOAL, CLUSTER,
				selected.provider(), pattern, new CreateRouteKey(NETWORK, "warehouse"), 1,
				InputMode.EXACT, 0,
				new StockObservationScope.NetworkSummary(NETWORK, pattern.mainOutput().stack(), 1),
				Map.of(iron, 1L, copper, 2L)).orElseThrow();
			assertTrue(ProductionPlanDispatch.publishSelectedProviderCreateDispatch(
				fixture.runtime(), state, state.processes().get(PROCESS), plan,
				RegistryAccess.EMPTY));

			ProductionEffect.CreateDispatch dispatch = awaitCreateDispatch(fixture.runtime());
			assertEquals(List.of(iron, copper), dispatch.ingredients().stream()
				.map(OrderedIngredient::stack).toList());
			assertEquals(List.of(1L, 2L), dispatch.ingredients().stream()
				.map(OrderedIngredient::amount).toList());
		}
	}

	private static PatternRecord pattern(List<PatternIngredient> inputs) {
		return new PatternRecord(PATTERN, new PatternPageKey(
			new RecipeSourceLocation(Level.OVERWORLD, LIBRARY, BlockPos.ZERO), 0, 0), inputs,
			List.of(new PatternOutput(new StackKey(new ItemStack(Items.GOLD_INGOT)), 1)),
			new StandardPatternBody("mix"), OptionalInt.empty());
	}

	private static ProductionEffect.CreateDispatch awaitCreateDispatch(FactoryRuntimeService runtime)
		throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
		long tick = 1;
		while (System.nanoTime() < deadline) {
			for (ProductionEffect intent : runtime.drainIntents(CLUSTER, tick++))
				if (intent instanceof ProductionEffect.CreateDispatch dispatch) return dispatch;
			Thread.sleep(5);
		}
		throw new AssertionError("Create dispatch did not reach the actual Engine intent path: "
			+ runtime.snapshot(CLUSTER) + " diagnostics=" + runtime.drainDiagnostics());
	}

	private static final class ProductionFixture implements AutoCloseable {
		private final PatternRecord pattern;
		private final Priorities priorities;
		private final FactoryRuntimeService runtime;
		private final AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		private final OrdinaryProviderRegistry ordinary = new OrdinaryProviderRegistry();
		private final AgentCoordinator coordinator;
		private final AtomicReference<List<ProductionRouteTable.ProviderSource>> lastSources =
			new AtomicReference<>(List.of());
		private final AtomicReference<List<UUID>> collectedPatternIds =
			new AtomicReference<>(List.of());
		private final AtomicInteger collectionCount = new AtomicInteger();

		private ProductionFixture(PatternRecord pattern, Priorities priorities) {
			this.pattern = pattern;
			this.priorities = priorities;
			CreateRouteKey inputRoute = new CreateRouteKey(NETWORK, "mix");
			CreateRouteKey warehouse = new CreateRouteKey(NETWORK, "warehouse");
			AgentFormation formation = AgentFormation.form(List.of(
				endpoint(0, AgentEndpointRole.INPUT, inputRoute, priorities.agent(), warehouse),
				endpoint(1, AgentEndpointRole.OUTPUT,
					new CreateRouteKey(NETWORK, "output"), 0, warehouse)));
			AgentProcess.ResidentSlot slot = new AgentProcess.ResidentSlot(id(10), id(11), 0,
				EPOCH, ProcessKind.AGENT, true);
			AgentProcess.SlotAllocator allocator = new AgentProcess.SlotAllocator() {
				@Override public Optional<AgentProcess.ResidentSlot> acquire(UUID agentId,
					UUID ownerClusterId, ProcessKind kind) { return Optional.of(slot); }
				@Override public boolean stillValid(AgentProcess.ResidentSlot candidate) {
					return slot.equals(candidate);
				}
				@Override public void release(AgentProcess.ResidentSlot ignored) {}
			};
			coordinator = new AgentCoordinator(formation, AgentProcess.form(formation, allocator),
				state -> {}, 8);
			coordinator.publishInputRegistrations(directory,
				new ClusterCommunicationConfiguration(CLUSTER, FREQUENCY,
					ClusterServiceRole.AGENT, 0));
			ordinary.register(new OrdinaryProviderRegistry.Registration(id(9), NETWORK,
				pattern.mainOutput().stack(), priorities.ordinary(),
				RouteCandidate.Availability.AVAILABLE));

			runtime = new FactoryRuntimeService(CONFIG);
			runtime.start();
			runtime.restoreSnapshot(selectionState());
			runtime.setProductionRequesterFrequencyResolver(ignored -> FREQUENCY);
			runtime.setProductionRouteCollector(this::collect);
			runtime.setProductionProviderResolver(request -> lastSources.get().stream()
				.filter(request.expectedProvider()::matches).findFirst());
			republish(1);
		}

		private FactoryRuntimeService.ProductionRouteSources collect(
			FactoryRuntimeService.ProductionCollectionRequest request) {
			collectionCount.incrementAndGet();
			collectedPatternIds.set(request.patternSources().stream()
				.map(ProductionRouteTable.ProviderSource::patternId).toList());
			ArrayList<ProductionRouteTable.ProviderSource> patterns =
				new ArrayList<>(request.patternSources());
			ArrayList<ProductionRouteTable.ProviderSource> agents = new ArrayList<>();
			ArrayList<ProductionRouteTable.ProviderSource> gauges = new ArrayList<>();
			ArrayList<ProductionRouteTable.ProviderSource> ordinarySources = new ArrayList<>();
			AgentRouteRuntimeAdapter agentCollector = new AgentRouteRuntimeAdapter(
				new AgentRouteRuntimeAdapter.ScopedCollector() {
					@Override public UUID clusterId() { return CLUSTER; }
					@Override public boolean replace(RouteSelectionScope ignored,
						List<ProductionRouteTable.ProviderSource> sources) { return true; }
					@Override public boolean complete(RouteSelectionScope ignored) { return true; }
				});
			for (ProductionRouteTable.ProviderSource source : request.patternSources()) {
				CreateRouteKey route = new CreateRouteKey(request.logisticsId(), "mix");
				agents.addAll(agentCollector.collect(request.scope(), source.libraryId(),
					source.patternId(), route, FREQUENCY, directory, List.of(coordinator)));
				gauges.addAll(FactoryOrchestrationServer.collectLegalNativeGaugeProviders(
					request.scope(), source.libraryId(), source.patternId(),
					directory.resolve(route, CLUSTER, FREQUENCY),
					List.of(new FactoryOrchestrationServer.NativeGaugeProvider(
						"factory-gauge/native", priorities.gauge(),
						RouteCandidate.Availability.AVAILABLE, false))));
				ordinarySources.addAll(ordinary.collect(request.scope(), request.logisticsId(),
					source.libraryId(), source.patternId()));
			}
			ArrayList<ProductionRouteTable.ProviderSource> all = new ArrayList<>(patterns);
			all.addAll(agents); all.addAll(gauges); all.addAll(ordinarySources);
			lastSources.set(List.copyOf(all));
			return new FactoryRuntimeService.ProductionRouteSources(patterns, agents, gauges,
				ordinarySources);
		}

		private FactoryRuntimeService.SelectedPattern select() throws Exception {
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			Optional<FactoryRuntimeService.SelectedPattern> selected = Optional.empty();
			while (selected.isEmpty() && System.nanoTime() < deadline) {
				selected = runtime.selectProductionPattern(CLUSTER, NETWORK,
					pattern.mainOutput().stack());
				if (selected.isEmpty()) Thread.sleep(5);
			}
			return selected.orElseThrow(() -> new AssertionError(
				"production selection did not complete: " + runtime.productionSearchSnapshot(
					CLUSTER, NETWORK, pattern.mainOutput().stack()) + " diagnostics="
					+ runtime.drainDiagnostics()));
		}

		private void republish(long revision) {
			runtime.publishProductionLibraryAccess(CLUSTER, Set.of(NETWORK), revision, FREQUENCY,
				priorities.pattern(), worldAddress(pattern.source().shelf()),
				LibraryRegistration.Reachability.REACHABLE,
				new ProductionRecipeCatalog(LIBRARY, 4, revision, true, List.of(pattern)));
		}

		private void retireSelection() {
			UUID searchId = runtime.productionSearchSnapshot(CLUSTER, NETWORK,
				pattern.mainOutput().stack()).orElseThrow().searchId();
			assertTrue(runtime.retireProductionSearch(searchId));
		}

		private ClusterEngineState installDispatchState(PatternRecord selectedPattern)
			throws Exception {
			runtime.snapshotForServerMutation(CLUSTER);
			long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
			while (runtime.workerInFlight(CLUSTER) && System.nanoTime() < deadline)
				Thread.sleep(5);
			if (runtime.workerInFlight(CLUSTER))
				throw new AssertionError("production worker did not quiesce before dispatch proof");
			ClusterEngineState state = dispatchState(selectedPattern);
			runtime.restoreSnapshot(state);
			runtime.releasePanelMutationReservation(CLUSTER);
			return state;
		}

		private FactoryRuntimeService runtime() { return runtime; }
		private int collectionCount() { return collectionCount.get(); }
		private List<UUID> collectedPatternIds() { return collectedPatternIds.get(); }
		private List<ProductionRouteTable.ProviderSource> lastSources() {
			return lastSources.get();
		}

		@Override public void close() {
			runtime.close();
			coordinator.process().close();
		}
	}

	private static ClusterEngineState selectionState() {
		ClusterEngineState empty = ClusterEngineState.empty(CLUSTER, EPOCH);
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 5, 0)
			.orElseThrow();
		return new ClusterEngineState(CLUSTER, EPOCH, 0, 0, 0, 0,
			empty.goals(), empty.submissionSequences(), empty.requestedKinds(), empty.processes(),
			List.of(new ClusterEngineState.MemberSnapshot(id(12), profile, true, 1, 1)),
			empty.pendingIntents(), empty.uncertainIntentIds(), 0, null);
	}

	private static ClusterEngineState dispatchState(PatternRecord pattern) {
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 5, 0)
			.orElseThrow();
		GoalSubmission submission = new GoalSubmission(GOAL, pattern.patternId(), 1,
			InputMode.EXACT);
		GoalRecord goal = new GoalRecord(GOAL, CLUSTER, submission,
			new CreateRouteKey(NETWORK, "warehouse"), new PolicySnapshot(0, 0, false),
			GoalState.RUNNABLE, null);
		ProcessRecord process = new ProcessRecord(PROCESS, GOAL, id(12), 0, profile,
			ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
		return new ClusterEngineState(CLUSTER, EPOCH, 0, 1, 0, 0,
			Map.of(GOAL, goal), Map.of(GOAL, 0L), Map.of(GOAL, ProcessKind.GOAL),
			Map.of(PROCESS, process),
			List.of(new ClusterEngineState.MemberSnapshot(id(12), profile, true, 1, 1)),
			Map.of(), Set.of(), 0, null);
	}

	private static AgentEndpointState endpoint(int x, AgentEndpointRole role,
		CreateRouteKey route, int priority, CreateRouteKey warehouse) {
		return new AgentEndpointState(new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(x, 0, 0)), AGENT, CLUSTER, 4, role, route, priority, FREQUENCY,
			AgentCompletionMode.QUIET, Optional.empty(), AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			0, warehouse, true, true);
	}

	private record Priorities(int pattern, int agent, int gauge, int ordinary) {}

	private static UUID id(long value) { return new UUID(0, value); }
}
