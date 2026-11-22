package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class ClusterEngineTest {
	@BeforeAll static void bootstrapMinecraftRegistries() throws ReflectiveOperationException {
		Class<?> loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
		if (loading.getMethod("get").invoke(null) == null)
			loading.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		net.minecraft.SharedConstants.tryDetectVersion();
		Field bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
		bootstrapped.setAccessible(true);
		bootstrapped.setBoolean(null, true);
	}

	private static final UUID CLUSTER = id(1), EPOCH = id(2), COMPUTER_A = id(10), COMPUTER_B = id(11);
	private static final FactoryRuntimeConfig CONFIG = new FactoryRuntimeConfig(1, 50_000, 8,
		64, 8, 8, 32, 64, 20, 10, 100, 3, 64, 16);

	@Test void selectsGoalsByDescendingPriorityThenStableSubmissionSequence() {
		ClusterEngineState state = stateWithMembers(librarian(COMPUTER_A, 2));
		GoalRecord lowFirst = goal(20, 1), highSecond = goal(21, 9), highThird = goal(22, 9);
		state = advance(state, submit(lowFirst, ProcessKind.GOAL), submit(highSecond, ProcessKind.GOAL), submit(highThird, ProcessKind.GOAL)).state();
		ClusterEngine.EngineAdvance first = advance(state);
		ClusterEngine.EngineAdvance second = advance(first.state());
		assertEquals(highSecond.goalId(), first.state().processes().values().iterator().next().goalId());
		assertEquals(List.of(highSecond.goalId(), highThird.goalId()), second.state().processes().values().stream()
			.sorted(Comparator.comparingInt(p -> p.owningSlot())).map(p -> p.goalId()).toList());
	}

	@Test void rotatesComputersDeterministicallyAndOneProcessOccupiesOneSlot() {
		ClusterEngineState state = stateWithMembers(librarian(COMPUTER_A, 2), librarian(COMPUTER_B, 2));
		state = advance(state, submit(goal(30, 0), ProcessKind.GOAL), submit(goal(31, 0), ProcessKind.GOAL), submit(goal(32, 0), ProcessKind.GOAL)).state();
		state = advance(advance(advance(state).state()).state()).state();
		assertEquals(List.of(COMPUTER_A, COMPUTER_B, COMPUTER_A), state.processes().values().stream()
			.sorted(Comparator.comparing(p -> p.goalId())).map(p -> p.owningComputerId()).toList());
		assertEquals(state.processes().size(), state.processes().values().stream()
			.map(p -> Map.entry(p.owningComputerId(), p.owningSlot())).distinct().count());
	}

	@Test void oneGoalMayOwnMultipleProcessesWhileEveryProcessHasOneDistinctSlot() {
		GoalRecord goal = goal(35, 0);
		ClusterEngineState.MemberSnapshot member = librarian(COMPUTER_A, 1);
		var first = new ProcessRecord(id(351), goal.goalId(), COMPUTER_A, 0, member.profile(),
			ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
		var child = new ProcessRecord(id(352), goal.goalId(), COMPUTER_A, 1, member.profile(),
			ProcessKind.LIBRARY_QUERY, ProcessState.RUNNABLE, null, 0, Map.of());
		ClusterEngineState state = new ClusterEngineState(CLUSTER, EPOCH, 1, 1, 0, 0,
			Map.of(goal.goalId(), goal), Map.of(goal.goalId(), 0L), Map.of(goal.goalId(), ProcessKind.GOAL),
			Map.of(first.processId(), first, child.processId(), child), List.of(member), Map.of(), Set.of(), 0, null);
		assertEquals(2, state.processes().size());
	}

	@Test void allocatesOnlyToCapableMembersAndTransfersInsideTheClusterAfterUnload() {
		ClusterEngineState state = stateWithMembers(librarian(COMPUTER_A, 1), cleric(COMPUTER_B, 1));
		state = advance(state, submit(goal(40, 0), ProcessKind.AGENT)).state();
		state = advance(state).state();
		assertEquals(COMPUTER_B, state.processes().values().iterator().next().owningComputerId());
		state = advance(state, member(COMPUTER_B, cleric(COMPUTER_B, 1).profile(), false, 3),
			member(COMPUTER_A, cleric(COMPUTER_A, 1).profile(), true, 4)).state();
		state = advance(state).state();
		assertEquals(COMPUTER_A, state.processes().values().iterator().next().owningComputerId());
		assertEquals(1, state.processes().values().iterator().next().generation());
	}

	@Test void waitsForProcessWhenNoCompatibleSlotExistsThenWakesWhenCapacityAppears() {
		GoalRecord goal = goal(50, 0);
		ClusterEngineState state = stateWithMembers(librarian(COMPUTER_A, 1));
		state = advance(state, submit(goal, ProcessKind.AGENT)).state();
		state = advance(state).state();
		assertEquals(GoalState.WAITING, state.goals().get(goal.goalId()).state());
		assertEquals(WaitReason.PROCESS, state.goals().get(goal.goalId()).waitReason());
		assertTrue(state.processes().isEmpty());
		state = advance(state, member(COMPUTER_B, cleric(COMPUTER_B, 1).profile(), true, 2)).state();
		state = advance(state).state();
		assertEquals(GoalState.RUNNABLE, state.goals().get(goal.goalId()).state());
		assertEquals(ProcessState.RUNNABLE, state.processes().values().iterator().next().state());
	}

	@Test void waitingHighPriorityGoalDoesNotStarveLowerRunnableGoal() {
		GoalRecord high = goal(51, 9), low = goal(52, 1);
		ClusterEngineState state = stateWithMembers(librarian(COMPUTER_A, 1));
		state = advance(state, submit(high, ProcessKind.AGENT), submit(low, ProcessKind.GOAL)).state();
		state = advance(state).state();
		assertEquals(GoalState.WAITING, state.goals().get(high.goalId()).state());
		state = advance(state).state();
		assertEquals(low.goalId(), state.processes().values().iterator().next().goalId());
	}

	@Test void recoveredCapacityRestoresWaitingHighPriorityBeforeNewLowPriorityGoal() {
		GoalRecord high = goal(510, 100), low = goal(511, 0);
		ClusterEngineState state = stateWithMembers(librarian(COMPUTER_A, 1));
		state = advance(state, submit(high, ProcessKind.AGENT)).state();
		state = advance(state).state();
		assertEquals(GoalState.WAITING, state.goals().get(high.goalId()).state());
		state = advance(state, submit(low, ProcessKind.GOAL),
			member(COMPUTER_B, cleric(COMPUTER_B, 1).profile(), true, 2)).state();
		state = advance(state).state();
		assertEquals(high.goalId(), state.processes().values().iterator().next().goalId());
	}

	@Test void cancellingAnOccupantWakesWaitingPriorityWithoutAnotherMemberFact() {
		GoalRecord occupantGoal = goal(512, 50), high = goal(513, 100), low = goal(514, 0);
		ClusterEngineState.MemberSnapshot member = snapshot(COMPUTER_A, ProductionProfession.NITWIT, 1);
		ProcessRecord occupant = new ProcessRecord(id(515), occupantGoal.goalId(), COMPUTER_A, 0,
			member.profile(), ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
		ClusterEngineState state = stateWith(occupantGoal, occupant, member);
		state = advance(state, submit(high, ProcessKind.GOAL), submit(low, ProcessKind.GOAL)).state();
		state = advance(state).state();
		assertEquals(GoalState.WAITING, state.goals().get(high.goalId()).state());
		state = advance(state, new WorldFact.PlayerCommand(CLUSTER, EPOCH, state.generation(),
			new WorldFact.Command.CancelGoal(occupantGoal.goalId()))).state();
		state = advance(state).state();
		assertEquals(high.goalId(), state.processes().values().iterator().next().goalId());
	}

	@Test void eachAllocationAdvanceChangesOnlyOneGoalOrCreatesOneProcess() {
		GoalRecord high = goal(516, 100), middle = goal(517, 50), low = goal(518, 0);
		ClusterEngineState state = stateWithMembers(librarian(COMPUTER_A, 1));
		state = advance(state, submit(high, ProcessKind.AGENT), submit(middle, ProcessKind.AGENT),
			submit(low, ProcessKind.GOAL)).state();
		ClusterEngineState first = advance(state).state();
		assertEquals(GoalState.WAITING, first.goals().get(high.goalId()).state());
		assertEquals(GoalState.RUNNABLE, first.goals().get(middle.goalId()).state());
		assertTrue(first.processes().isEmpty());
		ClusterEngineState second = advance(first).state();
		assertEquals(GoalState.WAITING, second.goals().get(middle.goalId()).state());
		assertTrue(second.processes().isEmpty());
		ClusterEngineState third = advance(second).state();
		assertEquals(1, third.processes().size());
		assertEquals(low.goalId(), third.processes().values().iterator().next().goalId());
	}

	@Test void processWaitingForItsUnloadedOwnerWakesWhenThatOwnerReloads() {
		ClusterEngineState state = stateWithMembers(librarian(COMPUTER_A, 1));
		state = advance(state, submit(goal(53, 0), ProcessKind.GOAL)).state();
		state = advance(state).state();
		UUID processId = state.processes().keySet().iterator().next();
		state = advance(state, member(COMPUTER_A, librarian(COMPUTER_A, 1).profile(), false, 2)).state();
		state = advance(state).state();
		assertEquals(ProcessState.WAITING, state.processes().get(processId).state());
		state = advance(state, member(COMPUTER_A, librarian(COMPUTER_A, 1).profile(), true, 3)).state();
		state = advance(state).state();
		assertEquals(ProcessState.RUNNABLE, state.processes().get(processId).state());
	}

	@Test void snapshotRejectsProcessOwnerOrProfileOutsideCurrentMembership() {
		GoalRecord goal = goal(54, 0);
		ClusterEngineState.MemberSnapshot member = librarian(COMPUTER_A, 1);
		ProcessRecord absentOwner = new ProcessRecord(id(541), goal.goalId(), COMPUTER_B, 0, member.profile(),
			ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
		assertThrows(IllegalArgumentException.class, () -> stateWith(goal, absentOwner, member));
		ProcessRecord wrongProfile = new ProcessRecord(id(542), goal.goalId(), COMPUTER_A, 0,
			cleric(COMPUTER_A, 1).profile(), ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
		assertThrows(IllegalArgumentException.class, () -> stateWith(goal, wrongProfile, member));
	}

	@Test void deadlineYieldsWithoutLosingPendingTransition() {
		ClusterEngineState state = stateWithMembers(librarian(COMPUTER_A, 1));
		state = advance(state, submit(goal(60, 0), ProcessKind.GOAL)).state();
		ClusterEngine.EngineAdvance yielded = new ClusterEngine().advance(state, List.of(), CONFIG, 0);
		assertTrue(yielded.yielded());
		assertTrue(yielded.state().processes().isEmpty());
		assertEquals(1, advance(yielded.state()).state().processes().size());
	}

	@Test void expiredDeadlineDoesNotApplyEvenOneQueuedFact() {
		ClusterEngineState state = ClusterEngineState.empty(CLUSTER, EPOCH);
		ClusterEngine.EngineAdvance yielded = new ClusterEngine().advance(state,
			List.of(member(COMPUTER_A, librarian(COMPUTER_A, 1).profile(), true, 1)), CONFIG, 0);
		assertSame(state, yielded.state());
		assertTrue(yielded.yielded());
	}

	@Test void goalSelectionPersistsItsScanCursorWhenDeadlineExpiresMidScan() {
		ClusterEngineState state = stateWithMembers(librarian(COMPUTER_A, 1));
		for (int index = 0; index < 40; index++) state = advance(state, submit(goal(600 + index, index), ProcessKind.GOAL)).state();
		java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
		ClusterEngine.EngineAdvance yielded = new ClusterEngine(() -> clock.getAndIncrement())
			.advance(state, List.of(), CONFIG, 135);
		assertTrue(yielded.yielded());
		assertTrue(yielded.state().goalScanOffset() > 0);
		assertTrue(yielded.state().goalScanOffset() < state.goals().size());
		ClusterEngine.EngineAdvance resumed = new ClusterEngine().advance(yielded.state(), List.of(), CONFIG, Long.MAX_VALUE);
		assertFalse(resumed.state().processes().isEmpty());
		assertEquals(goal(639, 39).goalId(), resumed.state().processes().values().iterator().next().goalId());
	}

	@Test void maximumSnapshotCopyYieldsBeforeUnboundedMutableConstructionAndResumes() {
		ClusterEngineState state = maximumOccupiedState();
		java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
		ClusterEngine.EngineAdvance yielded = new ClusterEngine(() -> clock.getAndIncrement())
			.advance(state, List.of(), CONFIG, 12);
		assertSame(state, yielded.state());
		assertTrue(yielded.yielded());
		assertTrue(clock.get() <= 14);
		assertDoesNotThrow(() -> new ClusterEngine().advance(state, List.of(), CONFIG, Long.MAX_VALUE));
	}

	@Test void maximumMemberFactRebuildYieldsWithoutPartiallyFreezingThenResumes() {
		List<ClusterEngineState.MemberSnapshot> members = new ArrayList<>();
		for (int index = 0; index < ClusterEngineState.MAX_MEMBERS; index++)
			members.add(librarian(id(10_000 + index), 1));
		ClusterEngineState state = new ClusterEngineState(CLUSTER, EPOCH, 0, 0, 0, 0,
			Map.of(), Map.of(), Map.of(), Map.of(), members, Map.of(), Set.of(), 0, null);
		WorldFact replacement = member(id(10_255), librarian(id(10_255), 1).profile(), false, 2);
		java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
		ClusterEngine.EngineAdvance yielded = new ClusterEngine(() -> clock.getAndIncrement())
			.advance(state, List.of(replacement), CONFIG, 260);
		assertSame(state, yielded.state());
		assertTrue(yielded.yielded());
		ClusterEngineState resumed = new ClusterEngine().advance(state, List.of(replacement), CONFIG, Long.MAX_VALUE).state();
		assertFalse(resumed.member(id(10_255)).loaded());
	}

	@Test void priorityChangeAffectsLaterSelectionWithoutReorderingCreateIngredients() {
		GoalRecord first = goal(70, 1), second = goal(71, 0);
		ClusterEngineState state = stateWithMembers(librarian(COMPUTER_A, 2));
		state = advance(state, submit(first, ProcessKind.GOAL), submit(second, ProcessKind.GOAL)).state();
		state = advance(state).state();
		state = advance(state, new WorldFact.PlayerCommand(CLUSTER, EPOCH, 2,
			new WorldFact.Command.ChangePriority(second.goalId(), new PolicySnapshot(2, 10, false)))).state();
		state = advance(state).state();
		assertEquals(second.goalId(), state.processes().values().stream().max(Comparator.comparingInt(p -> p.owningSlot())).orElseThrow().goalId());
		List<OrderedIngredient> ingredients = List.of(new OrderedIngredient(
			new StackKey(new ItemStack(Items.IRON_INGOT)), 2),
			new OrderedIngredient(new StackKey(new ItemStack(Items.GOLD_INGOT)), 1));
		UUID processId = state.processes().values().iterator().next().processId();
		WorldFact dispatch = new WorldFact.PlayerCommand(CLUSTER, EPOCH, 3,
			new WorldFact.Command.DispatchCreate(processId, id(72),
				new CreateRouteKey(id(73), "mixer"), ingredients));
		ClusterEngine.EngineAdvance result = advance(state, dispatch);
		ProductionEffect.CreateDispatch intent = (ProductionEffect.CreateDispatch) result.intents().getFirst();
		assertEquals(processId, intent.processId(),
			"production commit must retain the exact source process lineage");
		assertEquals(ingredients, intent.ingredients());
		assertTrue(result.state().pendingIntents().containsKey(intent.intentId()));
	}

	@Test void enginePackageHasNoLiveWorldTypesAtItsBoundary() throws Exception {
		Set<String> forbidden = Set.of("net.minecraft.world.level.Level", "net.minecraft.world.level.block.entity.BlockEntity",
			"net.minecraft.world.entity.Entity", "net.neoforged.neoforge.items.IItemHandler",
			"com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour");
		ArrayDeque<Class<?>> engineTypes = new ArrayDeque<>(List.of(WorldFact.class, ProductionEffect.class,
			ClusterEngineState.class, ClusterEngine.class, FactoryComputeWorker.class, FactoryRuntimeService.class));
		while (!engineTypes.isEmpty()) {
			Class<?> type = engineTypes.removeFirst();
			engineTypes.addAll(List.of(type.getDeclaredClasses()));
			for (Field field : type.getDeclaredFields()) assertFalse(forbidden.contains(field.getType().getName()), type + " field " + field);
			for (Method method : type.getDeclaredMethods()) for (Class<?> parameter : method.getParameterTypes())
				assertFalse(forbidden.contains(parameter.getName()), type + " method " + method);
			for (var constructor : type.getDeclaredConstructors()) for (Class<?> parameter : constructor.getParameterTypes())
				assertFalse(forbidden.contains(parameter.getName()), type + " constructor " + constructor);
		}
	}

	@Test void factAndIntentPayloadsAreImmutableAndBounded() {
		List<UUID> mutable = new ArrayList<>(List.of(id(80)));
		WorldFact.LibraryReply reply = new WorldFact.LibraryReply(CLUSTER, EPOCH, 1, id(81), mutable);
		mutable.add(id(82));
		assertEquals(1, reply.patternIds().size());
		assertThrows(UnsupportedOperationException.class, () -> reply.patternIds().add(id(83)));
		assertThrows(IllegalArgumentException.class, () -> new WorldFact.RadioMessage(CLUSTER, EPOCH, 1, id(84), "x".repeat(WorldFact.MAX_TEXT_LENGTH + 1)));
		assertThrows(IllegalArgumentException.class, () -> new ProductionEffect.CreateDispatch(id(85), CLUSTER, 1,
			fence(COMPUTER_A), id(86), new CreateRouteKey(id(87), "route"),
			Collections.nCopies(ProductionEffect.MAX_LIST_ENTRIES + 1, new OrderedIngredient(
				new StackKey(new ItemStack(Items.STONE)), 1))));
	}

	private static ClusterEngine.EngineAdvance advance(ClusterEngineState state, WorldFact... facts) {
		ClusterEngine.EngineAdvance result = new ClusterEngine.EngineAdvance(state, List.of(), false);
		if (facts.length == 0) return new ClusterEngine().advance(state, List.of(), CONFIG, Long.MAX_VALUE);
		for (WorldFact fact : facts)
			result = new ClusterEngine().advance(result.state(), List.of(fact), CONFIG, Long.MAX_VALUE);
		return result;
	}
	private static ClusterEngineState stateWithMembers(ClusterEngineState.MemberSnapshot... members) {
		ClusterEngineState state = ClusterEngineState.empty(CLUSTER, EPOCH);
		List<WorldFact> facts = Arrays.stream(members).map(m -> member(m.computerId(), m.profile(), m.loaded(), m.fence())).toList();
		return advance(state, facts.toArray(WorldFact[]::new)).state();
	}
	private static ClusterEngineState.MemberSnapshot librarian(UUID id, int level) { return snapshot(id, ProductionProfession.LIBRARIAN, level); }
	private static ClusterEngineState.MemberSnapshot cleric(UUID id, int level) { return snapshot(id, ProductionProfession.CLERIC, level); }
	private static ClusterEngineState.MemberSnapshot snapshot(UUID id, ProductionProfession profession, int level) {
		return new ClusterEngineState.MemberSnapshot(id, ProductionResourceProfile.forProfession(profession, level, 0).orElseThrow(), true, 1, 1);
	}
	private static WorldFact member(UUID id, ProductionResourceProfile profile, boolean loaded, long fence) {
		return new WorldFact.MemberLoadState(CLUSTER, EPOCH, 1, id, profile, loaded, 1, fence);
	}
	private static WorldFact submit(GoalRecord goal, ProcessKind kind) {
		return new WorldFact.PlayerCommand(CLUSTER, EPOCH, 1, new WorldFact.Command.SubmitGoal(goal, kind));
	}
	private static GoalRecord goal(long value, int priority) {
		UUID goal = id(value);
		return new GoalRecord(goal, CLUSTER, new GoalSubmission(goal, id(value + 100), 1, InputMode.BULK),
			new CreateRouteKey(id(value + 200), "warehouse"), new PolicySnapshot(1, priority, false), GoalState.RUNNABLE, null);
	}
	private static ClusterEngineState stateWith(GoalRecord goal, ProcessRecord process,
		ClusterEngineState.MemberSnapshot member) {
		return new ClusterEngineState(CLUSTER, EPOCH, 1, 1, 0, 0,
			Map.of(goal.goalId(), goal), Map.of(goal.goalId(), 0L), Map.of(goal.goalId(), process.kind()),
			Map.of(process.processId(), process), List.of(member), Map.of(), Set.of(), 0, null);
	}
	private static ClusterEngineState maximumOccupiedState() {
		LinkedHashMap<UUID, GoalRecord> goals = new LinkedHashMap<>();
		LinkedHashMap<UUID, Long> sequences = new LinkedHashMap<>();
		LinkedHashMap<UUID, ProcessKind> kinds = new LinkedHashMap<>();
		LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>();
		ArrayList<ClusterEngineState.MemberSnapshot> members = new ArrayList<>();
		long sequence = 0;
		for (int memberIndex = 0; memberIndex < ClusterEngineState.MAX_MEMBERS; memberIndex++) {
			UUID computer = id(20_000 + memberIndex);
			ClusterEngineState.MemberSnapshot member = librarian(computer, 5);
			members.add(member);
			for (int slot = 0; slot < member.profile().processSlots(); slot++) {
				GoalRecord goal = goal(30_000 + sequence, 0);
				ProcessRecord process = new ProcessRecord(id(40_000 + sequence), goal.goalId(), computer, slot,
					member.profile(), ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
				goals.put(goal.goalId(), goal); sequences.put(goal.goalId(), sequence++);
				kinds.put(goal.goalId(), ProcessKind.GOAL); processes.put(process.processId(), process);
			}
		}
		return new ClusterEngineState(CLUSTER, EPOCH, 1, sequence, 0, 0, goals, sequences, kinds,
			processes, members, Map.of(), Set.of(), 0, null);
	}
	private static ProductionEffect.TargetFence fence(UUID computer) { return new ProductionEffect.TargetFence(EPOCH, computer, 1, 1); }
	private static UUID id(long value) { return new UUID(0, value); }
}
