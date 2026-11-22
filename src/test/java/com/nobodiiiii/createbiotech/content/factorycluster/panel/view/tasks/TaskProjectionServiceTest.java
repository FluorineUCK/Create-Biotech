package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentNewTaskDefaults;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalSubmission;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.PolicySnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelAuthorityValidator;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskProjectionSnapshot.PublishedSnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class TaskProjectionServiceTest {
	private static final UUID CLUSTER = id(1), EPOCH = id(2), PANEL = id(3), SESSION = id(4);
	private static final UUID RUNNABLE = id(10), WAITING = id(11), TERMINAL = id(12);
	private static final UUID PROCESS = id(20), COMPUTER = id(21), NETWORK = id(22);
	private static final SpaceAddress ADDRESS = new SpaceAddress(Level.OVERWORLD, null,
		new BlockPos(4, 5, 6));
	private static final FactoryPanelOpening OPENING = new FactoryPanelOpening(ADDRESS, PANEL,
		SESSION, CLUSTER, EPOCH);
	private static final CreateRouteKey ROUTE = new CreateRouteKey(NETWORK, "warehouse");
	private static final CreateRouteKey RECOMMENDED = new CreateRouteKey(NETWORK, "recommended_input");
	private static StackKey IRON;
	private static final FactoryRuntimeConfig PANEL_CONFIG = new FactoryRuntimeConfig(1, 500, 4,
		32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);
	private static final List<FactoryRuntimeService> SNAPSHOT_RUNTIMES = new ArrayList<>();

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		installEmptyLoadingModList(); net.minecraft.SharedConstants.tryDetectVersion();
		try {
			var bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true); bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
		Items.AIR.getDefaultInstance(); IRON = new StackKey(new ItemStack(Items.IRON_INGOT));
	}
	@AfterAll static void closeSnapshotRuntimes() { SNAPSHOT_RUNTIMES.forEach(FactoryRuntimeService::close); }

	@Test
	void taskProjectionContainsOrderedCardsAndRuntimePolicyActionMask() {
		TaskListProjection projection = service(snapshot()).projectTasks(viewAuthority(FactoryPanelView.TASKS,
			OptionalLong.empty(), 0, 16, Optional.of(WAITING)));
		assertEquals(TaskListProjection.Status.CURRENT, projection.status());
		assertEquals(List.of(RUNNABLE, WAITING, TERMINAL), projection.tasks().stream()
			.map(TaskCardProjection::goalId).toList());
		assertEquals(EnumSet.of(TaskCardProjection.Action.CHANGE_PRIORITY,
			TaskCardProjection.Action.CHANGE_MODE, TaskCardProjection.Action.CANCEL),
			projection.tasks().getFirst().allowedActions());
		assertEquals(EnumSet.of(TaskCardProjection.Action.CHANGE_PRIORITY,
			TaskCardProjection.Action.RESUME, TaskCardProjection.Action.CANCEL,
			TaskCardProjection.Action.VIEW_COMPUTATION), projection.tasks().get(1).allowedActions());
		assertEquals(Optional.of(WAITING), projection.selectedGoalId());
	}

	@Test
	void epochChangedAndSupersededPublicationsFailClosedWithoutRetaggingRows() {
		ClusterEngineState firstState = state(false);
		FactoryRuntimeService runtime = runtimeFor(firstState);
		PublishedSnapshot first = publish(runtime, firstState, defaults());
		ClusterEngineState changedState = stateForEpoch(id(200));
		runtime.restoreSnapshot(changedState);
		PublishedSnapshot changed = publish(runtime, changedState, defaults());
		TaskListProjection epochStale = service(changed).projectTasks(viewAuthority(FactoryPanelView.TASKS,
			OptionalLong.empty(), 0, 16, Optional.empty()));
		assertEquals(TaskListProjection.Status.STALE, epochStale.status());
		assertTrue(epochStale.tasks().isEmpty());
		TaskListProjection retiredEpoch = service(first).projectTasks(viewAuthority(FactoryPanelView.TASKS,
			OptionalLong.empty(), 0, 16, Optional.empty()));
		assertEquals(TaskListProjection.Status.STALE, retiredEpoch.status());
		assertEquals(EPOCH, retiredEpoch.epochId());
	}


	@Test
	void captureFencesDefaultOwnerRoutesAndStalePageOffsets() {
		AgentNewTaskDefaults foreign = AgentNewTaskDefaults.standard(id(999),
			new CreateRouteKey(id(998), "foreign"), InputMode.BULK, 1, 0);
		ClusterEngineState currentState = state(false);
		FactoryRuntimeService runtime = runtimeFor(currentState);
		assertThrows(IllegalArgumentException.class, () -> publish(runtime, currentState, foreign));
		PublishedSnapshot current = snapshot();
		assertDoesNotThrow(() -> service(current).projectTasks(viewAuthority(FactoryPanelView.TASKS,
			OptionalLong.of(8), 10_000, 16, Optional.empty())));
		TaskListProjection stale = service(current).projectTasks(viewAuthority(FactoryPanelView.TASKS,
			OptionalLong.of(8), 10_000, 16, Optional.empty()));
		assertEquals(TaskListProjection.Status.STALE, stale.status());
		assertEquals(0, stale.offset());
	}

	@Test
	void actionPolicyRejectsPendingIntentsAndPriorityGenerationOverflow() {
		ClusterEngineState pending = stateWith(Map.of(id(91), new ProductionEffect.CreateDispatch(id(91),
			CLUSTER, 11, new ProductionEffect.TargetFence(EPOCH, id(94), 0, 0), id(92), id(93), ROUTE,
			List.of(new OrderedIngredient(IRON, 1)))), 0);
		assertFalse(TaskProjectionService.allowedActions(pending, RUNNABLE)
			.contains(TaskCardProjection.Action.CHANGE_MODE));
		assertFalse(TaskProjectionService.allowedActions(pending, RUNNABLE)
			.contains(TaskCardProjection.Action.CANCEL));
		ClusterEngineState exhausted = stateWith(Map.of(), Long.MAX_VALUE);
		assertFalse(TaskProjectionService.allowedActions(exhausted, RUNNABLE)
			.contains(TaskCardProjection.Action.CHANGE_PRIORITY));
	}

	@Test
	void newTaskHasStrictCurrentPlainContextualAndStaleShapes() {
		TaskProjectionService service = service(snapshot());
		NewTaskContextProjection contextual = service.projectNewTask(viewAuthority(FactoryPanelView.NEW_TASK,
			OptionalLong.empty(), 0, 1, Optional.of(RUNNABLE)));
		NewTaskContextProjection plain = service.projectNewTask(viewAuthority(FactoryPanelView.NEW_TASK,
			OptionalLong.empty(), 0, 1, Optional.of(TERMINAL)));
		NewTaskContextProjection stale = service.projectNewTask(viewAuthority(FactoryPanelView.NEW_TASK,
			OptionalLong.of(3), 0, 1, Optional.empty()));
		assertEquals(Optional.of(RUNNABLE), contextual.prefill().map(NewTaskContextProjection.Prefill::parentGoalId));
		assertEquals(Optional.of(IRON), contextual.prefill().map(NewTaskContextProjection.Prefill::output));
		assertEquals(2, contextual.prefill().orElseThrow().multiplier());
		assertEquals(CLUSTER, contextual.defaults().orElseThrow().value().ownerClusterId());
		assertTrue(plain.defaults().isPresent()); assertTrue(plain.prefill().isEmpty());
		assertTrue(stale.defaults().isEmpty()); assertTrue(stale.prefill().isEmpty());
		assertThrows(IllegalArgumentException.class, () -> new NewTaskContextProjection(CLUSTER, EPOCH,
			11, 4, NewTaskContextProjection.Status.STALE,
			Optional.of(new NewTaskContextProjection.Defaults(defaults())), Optional.empty()));
	}

	@Test
	void selectionOutsideCurrentPageFailsClosed() throws Exception {
		TaskCardProjection card = new TaskCardProjection(RUNNABLE, IRON, 1, 0, 1,
			InputMode.EXACT, GoalState.RUNNABLE, 0, Optional.empty(), Set.of());
		assertThrows(IllegalArgumentException.class, () -> new TaskListProjection(CLUSTER, EPOCH,
			11, 9, TaskListProjection.Status.CURRENT, 0, 1, List.of(card), Optional.of(WAITING), 16));
		
	}


	@Test
	void runtimeOwnedPublicationAuthorityAllocatesSubviewRevisionsAndRejectsDelayedRollback() {
		ClusterEngineState firstState = state(false);
		FactoryRuntimeService runtime = runtimeFor(firstState);
		PublishedSnapshot first = publish(runtime, firstState, defaults());
		ClusterEngineState newerState = stateWithGeneration(12);
		runtime.restoreSnapshot(newerState);
		PublishedSnapshot newer = publish(runtime, newerState, defaults());
		assertTrue(newer.tasksRevision() > first.tasksRevision());
		assertTrue(newer.newTaskRevision() > first.newTaskRevision());
		assertThrows(IllegalArgumentException.class, () -> publish(runtime, firstState, defaults()));
	}

	@Test
	void equivalentDefaultsDoNotAdvanceNewTaskRevision() {
		ClusterEngineState currentState = state(false);
		FactoryRuntimeService runtime = runtimeFor(currentState);
		PublishedSnapshot first = publish(runtime, currentState, defaults());
		PublishedSnapshot repeated = publish(runtime, currentState, defaults());
		assertEquals(first.newTaskRevision(), repeated.newTaskRevision());
		assertEquals(first.tasksRevision(), repeated.tasksRevision());
		assertTrue(repeated.isCurrentPublication());
	}

	@Test
	void authorityLifecycleIsPerRuntimeAndCloseInvalidatesPublishedSnapshots() {
		ClusterEngineState currentState = state(false);
		FactoryRuntimeService first = runtimeFor(currentState);
		FactoryRuntimeService second = runtimeFor(currentState);
		PublishedSnapshot one = publish(first, currentState, defaults());
		PublishedSnapshot two = publish(second, currentState, defaults());
		assertTrue(one.isCurrentPublication()); assertTrue(two.isCurrentPublication());
		first.close();
		assertFalse(one.isCurrentPublication()); assertTrue(two.isCurrentPublication());
		assertEquals(new FactoryRuntimeService.PanelPublicationCounts(0, 0),
			first.panelPublicationCountsForTest());
	}
	private static TaskProjectionService service(PublishedSnapshot snapshot) {
		return new TaskProjectionService(cluster -> Optional.of(snapshot), 16);
	}
	private static PublishedSnapshot snapshot() {
		ClusterEngineState currentState = state(false);
		return publish(runtimeFor(currentState), currentState, defaults());
	}
	private static FactoryRuntimeService runtimeFor(ClusterEngineState engine) {
		FactoryRuntimeService runtime = new FactoryRuntimeService(PANEL_CONFIG);
		runtime.start();
		runtime.installPanelCommunicationSnapshotSource(request -> Optional.of(
			new FactoryRuntimeService.PanelCommunicationSnapshot(
				new ClusterCommunicationConfiguration(request.clusterId(),
					CommunicationFrequency.UNCONFIGURED, ClusterServiceRole.STANDARD, 0), 0)));
		runtime.restoreSnapshot(engine); SNAPSHOT_RUNTIMES.add(runtime); return runtime;
	}
	private static PublishedSnapshot publish(FactoryRuntimeService runtime,
		ClusterEngineState engine, AgentNewTaskDefaults defaults) {
		return runtime.publishPanelStandardSnapshot(engine, displays(), defaults, Set.of(ROUTE, RECOMMENDED));
	}
	private static AgentNewTaskDefaults defaults() {
		return AgentNewTaskDefaults.standard(CLUSTER, ROUTE, InputMode.BULK, 1, 0);
	}
	private static Map<UUID, TaskProjectionService.GoalDisplay> displays() {
		return Map.of(RUNNABLE, new TaskProjectionService.GoalDisplay(IRON, 12, 5, 7, 3,
			Optional.of(WaitReason.OUTPUT_DELIVERY), Optional.empty(), true, Optional.of(RECOMMENDED)),
			WAITING, new TaskProjectionService.GoalDisplay(IRON, 9, 3, 6, 3,
				Optional.of(WaitReason.MATERIAL), Optional.of(PROCESS), true, Optional.empty()),
			TERMINAL, new TaskProjectionService.GoalDisplay(IRON, 4, 4, 0, 2,
				Optional.empty(), Optional.empty(), false, Optional.empty()));
	}
	private static ClusterEngineState state(boolean runnableHasProcess) {
		GoalRecord runnable = goal(RUNNABLE, GoalState.RUNNABLE, null, 5, InputMode.PARTIAL, 0);
		GoalRecord waiting = goal(WAITING, GoalState.WAITING, WaitReason.MATERIAL, 7, InputMode.EXACT, 0);
		GoalRecord terminal = goal(TERMINAL, GoalState.TERMINAL, null, -1, InputMode.BULK, 0);
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
		ProcessRecord process = runnableHasProcess ? new ProcessRecord(PROCESS, RUNNABLE, COMPUTER, 0,
			profile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of()) :
			new ProcessRecord(PROCESS, WAITING, COMPUTER, 0, profile, ProcessKind.GOAL,
				ProcessState.WAITING, WaitReason.MATERIAL, 0, Map.of());
		return new ClusterEngineState(CLUSTER, EPOCH, 11, 3, 0, 0,
			Map.of(RUNNABLE, runnable, WAITING, waiting, TERMINAL, terminal),
			Map.of(RUNNABLE, 0L, WAITING, 1L, TERMINAL, 2L),
			Map.of(RUNNABLE, ProcessKind.GOAL, WAITING, ProcessKind.GOAL, TERMINAL, ProcessKind.GOAL),
			Map.of(PROCESS, process), List.of(new ClusterEngineState.MemberSnapshot(COMPUTER, profile, true, 0, 0)),
			Map.of(), Set.of(), 0, null);
	}
	private static ClusterEngineState stateWithGeneration(long generation) {
		ClusterEngineState original = state(false);
		return new ClusterEngineState(CLUSTER, EPOCH, generation, 3, 0, 0, original.goals(),
			original.submissionSequences(), original.requestedKinds(), original.processes(), original.members(),
			Map.of(), Set.of(), 0, null);
	}
	private static ClusterEngineState stateWith(Map<UUID, ProductionEffect> pending, long policyGeneration) {
		ClusterEngineState original = state(false);
		GoalRecord runnable = goal(RUNNABLE, GoalState.RUNNABLE, null, 5, InputMode.PARTIAL, policyGeneration);
		return new ClusterEngineState(CLUSTER, EPOCH, 11, 3, 0, 0,
			Map.of(RUNNABLE, runnable, WAITING, original.goals().get(WAITING), TERMINAL, original.goals().get(TERMINAL)),
			original.submissionSequences(), original.requestedKinds(), original.processes(), original.members(),
			pending, Set.of(), 0, null);
	}
	private static ClusterEngineState stateForEpoch(UUID epoch) {
		ClusterEngineState original = state(false);
		return new ClusterEngineState(CLUSTER, epoch, 12, 3, 0, 0, original.goals(),
			original.submissionSequences(), original.requestedKinds(), original.processes(), original.members(),
			Map.of(), Set.of(), 0, null);
	}
	private static GoalRecord goal(UUID id, GoalState state, WaitReason wait, int priority,
		InputMode mode, long generation) {
		return new GoalRecord(id, CLUSTER, new GoalSubmission(id, new UUID(1, id.getLeastSignificantBits()),
			3, mode), ROUTE, new PolicySnapshot(generation, priority, false), state, wait);
	}
	private static FactoryPanelAuthorityValidator.ViewValidation viewAuthority(FactoryPanelView view,
		OptionalLong revision, int offset, int limit, Optional<UUID> selection) {
		return FactoryPanelAuthorityValidator.validateView(new FactoryPanelAuthorityValidator.ViewRequest(
			OPENING, view, revision, offset, limit, selection), new FactoryPanelAuthorityValidator.ViewFacts(
			ADDRESS, PANEL, SESSION, CLUSTER, CLUSTER, EPOCH, true, true, true, true, true, true, true,
			Optional.empty(), 16)).orElseThrow();
	}
	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList"); if (type.getMethod("get").invoke(null) != null) return;
			Object empty = type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
	}
	private static UUID id(long value) { return new UUID(0, value); }
}
