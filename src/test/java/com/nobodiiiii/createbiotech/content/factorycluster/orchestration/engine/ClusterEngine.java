package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.CoordinationAssociation;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.DefaultRoutePolicy;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;

/** Pure, deterministic state transition engine. */
public final class ClusterEngine {
	private final LongSupplier nanoTime;

	public ClusterEngine() { this(System::nanoTime); }
	ClusterEngine(LongSupplier nanoTime) { this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime"); }

	public EngineAdvance advance(ClusterEngineState state, List<WorldFact> facts,
		FactoryRuntimeConfig config, long workDeadlineNanos) {
		Objects.requireNonNull(state, "state"); Objects.requireNonNull(config, "config");
		facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
		if (facts.size() > 1) throw new IllegalArgumentException("engine advances one immutable fact at a time");
		if (deadlineReached(workDeadlineNanos)) return new EngineAdvance(state, List.of(), true);

		Mutable mutable;
		try {
			mutable = new Mutable(state, workDeadlineNanos);
		} catch (DeadlineYield yielded) {
			return new EngineAdvance(state, List.of(), true);
		}
		List<ProductionEffect> emitted = new ArrayList<>();
		if (!facts.isEmpty()) {
			try {
				applyFact(mutable, facts.getFirst(), emitted, workDeadlineNanos);
			} catch (DeadlineYield yielded) {
				return new EngineAdvance(state, List.of(), true);
			}
			mutable.generation = Math.addExact(mutable.generation, 1);
			return new EngineAdvance(mutable.freeze(), emitted, true);
		}

		try {
			boolean changed = transferOneUnloadedProcess(mutable, workDeadlineNanos);
			if (!changed) changed = allocateOneGoal(mutable, workDeadlineNanos);
			if (changed) mutable.generation = Math.addExact(mutable.generation, 1);
			ClusterEngineState next = changed ? mutable.freeze() : state;
			return new EngineAdvance(next, emitted, deadlineReached(workDeadlineNanos) || changed);
		} catch (DeadlineYield yielded) {
			mutable.generation = Math.addExact(mutable.generation, 1);
			return new EngineAdvance(mutable.freeze(), List.of(), true);
		}
	}

	private void applyFact(Mutable state, WorldFact fact, List<ProductionEffect> emitted, long deadline) {
		Objects.requireNonNull(fact, "fact");
		if (!state.clusterId.equals(fact.clusterId()) || !state.epochId.equals(fact.epochId()))
			throw new IllegalArgumentException("fact does not belong to this Cluster epoch");
		state.goalScanOffset = 0; state.goalScanCandidate = null;
		if (fact instanceof WorldFact.ClusterEpochFact) return;
		if (fact instanceof WorldFact.MemberLoadState member) {
			ArrayList<ClusterEngineState.MemberSnapshot> rebuiltMembers = new ArrayList<>(state.members.size() + 1);
			ClusterEngineState.MemberSnapshot replacement = new ClusterEngineState.MemberSnapshot(member.computerId(),
				member.profile(), member.loaded(), member.routeGeneration(), member.fence());
			boolean inserted = false;
			for (ClusterEngineState.MemberSnapshot existing : state.members) {
				checkDeadline(deadline);
				if (existing.computerId().equals(member.computerId())) continue;
				if (!inserted && replacement.computerId().compareTo(existing.computerId()) < 0) {
					rebuiltMembers.add(replacement); inserted = true;
				}
				rebuiltMembers.add(existing);
			}
			if (!inserted) rebuiltMembers.add(replacement);
			state.members = rebuiltMembers;
			wakeCapacityWaiters(state, deadline);
			state.rebuildMemberIndexes(deadline);
			if (!state.members.isEmpty()) state.memberCursor %= state.members.size();
			return;
		}
		if (fact instanceof WorldFact.PlayerCommand player) {
			applyCommand(state, player.command(), emitted, deadline);
			return;
		}
		if (fact instanceof WorldFact.IntentResult result) {
			ProductionEffect pending = state.pendingIntents.get(result.intentId());
			if (pending != null && pending.generation() <= result.generation()) {
				state.pendingIntents.remove(result.intentId());
				state.uncertainIntentIds.remove(result.intentId());
			}
		}
	}

	private void applyCommand(Mutable state, WorldFact.Command command, List<ProductionEffect> emitted, long deadline) {
		if (command instanceof WorldFact.Command.SubmitGoal submit) {
			GoalRecord goal = submit.goal();
			if (!goal.owningClusterId().equals(state.clusterId) || state.goals.containsKey(goal.goalId()))
				throw new IllegalArgumentException("invalid or duplicate Goal admission");
			state.goals.put(goal.goalId(), goal);
			state.submissionSequences.put(goal.goalId(), state.nextSubmissionSequence++);
			state.requestedKinds.put(goal.goalId(), submit.processKind());
			return;
		}
		if (command instanceof WorldFact.Command.ChangePriority priority) {
			GoalRecord prior = requireGoal(state, priority.goalId());
			state.goals.put(prior.goalId(), new GoalRecord(prior.goalId(), prior.owningClusterId(),
				prior.submission(), prior.deliveryRoute(), priority.policy(), prior.state(), prior.waitReason()));
			return;
		}
		if (command instanceof WorldFact.Command.CancelGoal cancel) {
			GoalRecord prior = requireGoal(state, cancel.goalId());
			state.goals.put(prior.goalId(), withGoalState(prior, GoalState.TERMINAL, null));
			LinkedHashMap<UUID, ProcessRecord> retained = new LinkedHashMap<>();
			boolean releasedSlot = false;
			for (Map.Entry<UUID, ProcessRecord> entry : state.processes.entrySet()) {
				checkDeadline(deadline);
				if (entry.getValue().goalId().equals(cancel.goalId())) releasedSlot = true;
				else retained.put(entry.getKey(), entry.getValue());
			}
			state.processes = retained;
			state.coordinationAssociations.keySet().retainAll(retained.keySet());
			state.priorityLeaseAssociations.keySet().retainAll(retained.keySet());
			if (releasedSlot) wakeCapacityWaiters(state, deadline);
			state.processCursor = state.processes.isEmpty() ? 0 : state.processCursor % state.processes.size();
			return;
		}
		if (command instanceof WorldFact.Command.CreditActualStock receipt) {
			GoalRecord prior = requireGoal(state, receipt.goalId());
			if (prior.state() != GoalState.TERMINAL
				&& receipt.actualCount() >= prior.submission().multiplier()) {
				state.goals.put(prior.goalId(), withGoalState(prior, GoalState.TERMINAL, null));
				state.processes.entrySet().removeIf(entry -> entry.getValue().goalId().equals(prior.goalId()));
				state.coordinationAssociations.keySet().retainAll(state.processes.keySet());
				state.priorityLeaseAssociations.keySet().retainAll(state.processes.keySet());
				state.processCursor = state.processes.isEmpty() ? 0
					: Math.min(state.processCursor, state.processes.size() - 1);
			}
			return;
		}
		WorldFact.Command.DispatchCreate dispatch = (WorldFact.Command.DispatchCreate) command;
		ProcessRecord process = state.processes.get(dispatch.processId());
		if (process == null) throw new IllegalArgumentException("Create dispatch process is not durable");
		ClusterEngineState.MemberSnapshot target = state.member(process.owningComputerId());
		if (target == null || !target.loaded()) throw new IllegalStateException("Create dispatch target is unloaded");
		long intentGeneration = Math.addExact(state.generation, 1);
		UUID intentId = deterministicId("create", state.clusterId, dispatch.processId(), dispatch.runId(), intentGeneration);
		ProductionEffect intent = new ProductionEffect.CreateDispatch(intentId, state.clusterId, intentGeneration,
			new ProductionEffect.TargetFence(state.epochId, target.computerId(), target.routeGeneration(), target.fence()),
			dispatch.processId(), dispatch.runId(), dispatch.route(), dispatch.ingredients());
		if (state.pendingIntents.putIfAbsent(intent.intentId(), intent) != null)
			throw new IllegalStateException("duplicate intent identity");
		state.uncertainIntentIds.add(intent.intentId());
		emitted.add(intent);
	}

	private boolean transferOneUnloadedProcess(Mutable state, long deadline) {
		List<ProcessRecord> ordered = new ArrayList<>(state.processes.values());
		if (ordered.isEmpty()) return false;
		int start = state.processCursor;
		for (int offset = 0; offset < ordered.size(); offset++) {
			int index = (start + offset) % ordered.size();
			state.processCursor = index;
			checkDeadline(deadline);
			ProcessRecord process = ordered.get(index);
			ClusterEngineState.MemberSnapshot owner = state.member(process.owningComputerId());
			if (owner != null && owner.loaded()) {
				if (process.state() != ProcessState.WAITING || process.waitReason() != WaitReason.PROCESS) {
					state.processCursor = (index + 1) % ordered.size();
					continue;
				}
				state.processes.put(process.processId(), withProcess(process, process.owningComputerId(),
					process.owningSlot(), process.owningComputer(), ProcessState.RUNNABLE, null,
					process.generation()));
				state.processCursor = (index + 1) % ordered.size();
				return true;
			}
			if (!process.kind().automaticallyMigratesOnOwnerUnload()) {
				state.processCursor = (index + 1) % ordered.size();
				if (process.state() == ProcessState.WAITING
					&& process.waitReason() == WaitReason.PROCESS) continue;
				state.processes.put(process.processId(), withProcess(process,
					process.owningComputerId(), process.owningSlot(), process.owningComputer(),
					ProcessState.WAITING, WaitReason.PROCESS, process.generation()));
				return true;
			}
			Allocation allocation = findAllocation(state, process.kind(), process.processId(), deadline);
			state.processCursor = (index + 1) % ordered.size();
			if (allocation == null) {
				if (process.state() == ProcessState.WAITING && process.waitReason() == WaitReason.PROCESS) continue;
				state.processes.put(process.processId(), withProcess(process, process.owningComputerId(),
					process.owningSlot(), process.owningComputer(), ProcessState.WAITING, WaitReason.PROCESS,
					process.generation()));
				return true;
			}
			state.processes.put(process.processId(), withProcess(process, allocation.member.computerId(),
				allocation.slot, allocation.member.profile(), ProcessState.RUNNABLE, null,
				Math.addExact(process.generation(), 1)));
			state.memberCursor = allocation.nextCursor;
			return true;
		}
		return false;
	}

	private boolean allocateOneGoal(Mutable state, long deadline) {
			int index = 0;
			GoalRecord candidate = state.goalScanCandidate == null ? null : state.goals.get(state.goalScanCandidate);
			for (GoalRecord goal : state.goals.values()) {
				if (index++ < state.goalScanOffset) continue;
				checkDeadline(deadline);
				boolean waitingForCapacity = goal.state() == GoalState.WAITING && goal.waitReason() == WaitReason.PROCESS;
				if (!waitingForCapacity && !state.allocatedGoals.contains(goal.goalId())
					&& goal.state() != GoalState.TERMINAL
					&& (candidate == null || betterCandidate(state, goal, candidate))) candidate = goal;
				state.goalScanOffset = index;
				state.goalScanCandidate = candidate == null ? null : candidate.goalId();
			}
			if (candidate == null) { resetGoalScan(state); return false; }
			ProcessKind kind = state.requestedKinds.get(candidate.goalId());
			Allocation allocation = findAllocation(state, kind, null, deadline);
			resetGoalScan(state);
			if (allocation == null) {
				state.goals.put(candidate.goalId(), withGoalState(candidate, GoalState.WAITING, WaitReason.PROCESS));
				return true;
			}
			UUID processId = deterministicId("process", state.clusterId, candidate.goalId(),
				state.submissionSequences.get(candidate.goalId()));
			ProcessRecord process = new ProcessRecord(processId, candidate.goalId(), allocation.member.computerId(),
				allocation.slot, allocation.member.profile(), kind, ProcessState.RUNNABLE, null, 0, Map.of());
			state.processes.put(processId, process);
			state.allocatedGoals.add(candidate.goalId());
			state.goals.put(candidate.goalId(), withGoalState(candidate, GoalState.RUNNABLE, null));
			state.memberCursor = allocation.nextCursor;
			return true;
	}

	/** Invoke whenever a durable process removal releases a member slot. */
	private void wakeCapacityWaiters(Mutable state, long deadline) {
		LinkedHashMap<UUID, GoalRecord> awakened = new LinkedHashMap<>();
		for (GoalRecord goal : state.goals.values()) {
			checkDeadline(deadline);
			GoalRecord next = goal.state() == GoalState.WAITING && goal.waitReason() == WaitReason.PROCESS
				? withGoalState(goal, GoalState.RUNNABLE, null) : goal;
			awakened.put(next.goalId(), next);
		}
		state.goals = awakened;
	}

	private static boolean betterCandidate(Mutable state, GoalRecord candidate, GoalRecord current) {
		int priority = Integer.compare(candidate.policy().priority(), current.policy().priority());
		return priority > 0 || priority == 0
			&& state.submissionSequences.get(candidate.goalId()) < state.submissionSequences.get(current.goalId());
	}

	private static void resetGoalScan(Mutable state) { state.goalScanOffset = 0; state.goalScanCandidate = null; }

	private Allocation findAllocation(Mutable state, ProcessKind kind, UUID ignoredProcess, long deadline) {
		if (state.members.isEmpty()) return null;
		int start = state.memberCursor;
		for (int offset = 0; offset < state.members.size(); offset++) {
			int index = (start + offset) % state.members.size();
			state.memberCursor = index;
			checkDeadline(deadline);
			ClusterEngineState.MemberSnapshot member = state.members.get(index);
			if (member.loaded() && (!kind.requiresAlchemist() || member.profile().supports(ProductionCapability.ALCHEMIST))) {
				UUID[] slotOwners = state.slotOwnersByMember.get(member.computerId());
				for (int slot = 0; slot < member.profile().processSlots(); slot++)
					if (slotOwners == null || slotOwners[slot] == null || slotOwners[slot].equals(ignoredProcess))
						return new Allocation(member, slot, (index + 1) % state.members.size());
			}
			state.memberCursor = (index + 1) % state.members.size();
		}
		return null;
	}

	private static GoalRecord requireGoal(Mutable state, UUID goalId) {
		GoalRecord goal = state.goals.get(goalId);
		if (goal == null) throw new IllegalArgumentException("unknown Goal");
		return goal;
	}
	private static GoalRecord withGoalState(GoalRecord goal, GoalState state, WaitReason reason) {
		return new GoalRecord(goal.goalId(), goal.owningClusterId(), goal.submission(), goal.deliveryRoute(),
			goal.policy(), state, reason);
	}
	private static ProcessRecord withProcess(ProcessRecord process, UUID computerId, int slot,
		com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile profile,
		ProcessState state, WaitReason reason, long generation) {
		return new ProcessRecord(process.processId(), process.goalId(), computerId, slot, profile,
			process.kind(), state, reason, generation, process.childRevisionStates());
	}
	private boolean deadlineReached(long deadline) { return deadline <= 0 || nanoTime.getAsLong() >= deadline; }
	private void checkDeadline(long deadline) { if (deadlineReached(deadline)) throw DeadlineYield.INSTANCE; }
	private static UUID deterministicId(String namespace, Object... values) {
		return UUID.nameUUIDFromBytes((namespace + Arrays.toString(values)).getBytes(StandardCharsets.UTF_8));
	}

	public record EngineAdvance(ClusterEngineState state, List<ProductionEffect> intents, boolean yielded) {
		public EngineAdvance { Objects.requireNonNull(state); intents = List.copyOf(Objects.requireNonNull(intents)); }
	}
	private record Allocation(ClusterEngineState.MemberSnapshot member, int slot, int nextCursor) {}
	private static final class DeadlineYield extends RuntimeException {
		static final DeadlineYield INSTANCE = new DeadlineYield();
		private DeadlineYield() { super(null, null, false, false); }
	}

	private final class Mutable {
		final UUID clusterId, epochId;
		long generation, nextSubmissionSequence, nextRouteSelectionSequence;
		int memberCursor, processCursor, goalScanOffset;
		UUID goalScanCandidate;
		LinkedHashMap<UUID, GoalRecord> goals;
		final LinkedHashMap<UUID, Long> submissionSequences;
		final LinkedHashMap<UUID, ProcessKind> requestedKinds;
		LinkedHashMap<UUID, ProcessRecord> processes;
		ArrayList<ClusterEngineState.MemberSnapshot> members;
		HashMap<UUID, ClusterEngineState.MemberSnapshot> membersById;
		HashMap<UUID, UUID[]> slotOwnersByMember;
		final HashSet<UUID> allocatedGoals;
		final LinkedHashMap<UUID, ProductionEffect> pendingIntents;
		final LinkedHashSet<UUID> uncertainIntentIds;
		final com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.DefaultRoutePolicy.CursorState routeCursor;
		LinkedHashMap<UUID, com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.CoordinationAssociation> coordinationAssociations;
		LinkedHashMap<UUID, PriorityLeaseAssociation> priorityLeaseAssociations;
		Mutable(ClusterEngineState state, long deadline) {
			clusterId = state.clusterId(); epochId = state.epochId(); generation = state.generation();
			nextSubmissionSequence = state.nextSubmissionSequence();
			nextRouteSelectionSequence = state.nextRouteSelectionSequence();
			memberCursor = state.memberCursor(); processCursor = state.processCursor();
			goalScanOffset = state.goalScanOffset(); goalScanCandidate = state.goalScanCandidate();
			routeCursor = state.routeCursor();
			coordinationAssociations = copyMap(state.coordinationAssociations(), deadline);
			priorityLeaseAssociations = copyMap(state.priorityLeaseAssociations(), deadline);
			goals = copyMap(state.goals(), deadline); submissionSequences = copyMap(state.submissionSequences(), deadline);
			requestedKinds = copyMap(state.requestedKinds(), deadline); processes = copyMap(state.processes(), deadline);
			members = copyList(state.members(), deadline); pendingIntents = copyMap(state.pendingIntents(), deadline);
			membersById = new HashMap<>();
			for (ClusterEngineState.MemberSnapshot member : members) { checkDeadline(deadline); membersById.put(member.computerId(), member); }
			slotOwnersByMember = new HashMap<>(); allocatedGoals = new HashSet<>();
			for (ProcessRecord process : processes.values()) {
				checkDeadline(deadline);
				// Search coordination/speculation may be adopted by a Goal while retaining its
				// specialised process kind.  It consumes a slot, but it is not the Goal's ordinary
				// execution process and therefore must not suppress normal Goal allocation.
				if (process.kind() == requestedKinds.get(process.goalId()))
					allocatedGoals.add(process.goalId());
				if (process.state() == ProcessState.TERMINAL) continue;
				ClusterEngineState.MemberSnapshot owner = membersById.get(process.owningComputerId());
				if (owner != null) slotOwnersByMember.computeIfAbsent(owner.computerId(),
					ignored -> new UUID[owner.profile().processSlots()])[process.owningSlot()] = process.processId();
			}
			uncertainIntentIds = new LinkedHashSet<>();
			for (UUID intentId : state.uncertainIntentIds()) { checkDeadline(deadline); uncertainIntentIds.add(intentId); }
		}
		<K, V> LinkedHashMap<K, V> copyMap(Map<K, V> source, long deadline) {
			LinkedHashMap<K, V> copy = new LinkedHashMap<>();
			for (Map.Entry<K, V> entry : source.entrySet()) { checkDeadline(deadline); copy.put(entry.getKey(), entry.getValue()); }
			return copy;
		}
		<V> ArrayList<V> copyList(List<V> source, long deadline) {
			ArrayList<V> copy = new ArrayList<>(source.size());
			for (V value : source) { checkDeadline(deadline); copy.add(value); }
			return copy;
		}
		void rebuildMemberIndexes(long deadline) {
			HashMap<UUID, ClusterEngineState.MemberSnapshot> rebuiltMembers = new HashMap<>();
			for (ClusterEngineState.MemberSnapshot member : members) { checkDeadline(deadline); rebuiltMembers.put(member.computerId(), member); }
			HashMap<UUID, UUID[]> rebuiltSlots = new HashMap<>();
			for (ProcessRecord process : processes.values()) {
				checkDeadline(deadline);
				if (process.state() == ProcessState.TERMINAL) continue;
				ClusterEngineState.MemberSnapshot owner = rebuiltMembers.get(process.owningComputerId());
				if (owner == null || !owner.profile().equals(process.owningComputer()))
					throw new IllegalArgumentException("process owner is outside current membership");
				rebuiltSlots.computeIfAbsent(owner.computerId(), ignored ->
					new UUID[owner.profile().processSlots()])[process.owningSlot()] = process.processId();
			}
			membersById = rebuiltMembers; slotOwnersByMember = rebuiltSlots;
		}
		ClusterEngineState.MemberSnapshot member(UUID id) { return membersById.get(id); }
		ClusterEngineState freeze() { return new ClusterEngineState(clusterId, epochId, generation,
			nextSubmissionSequence, nextRouteSelectionSequence, memberCursor, processCursor,
			ClusterEngineState.trustedMap(goals), ClusterEngineState.trustedMap(submissionSequences),
			ClusterEngineState.trustedMap(requestedKinds), ClusterEngineState.trustedMap(processes),
			ClusterEngineState.trustedList(members), ClusterEngineState.trustedMap(pendingIntents),
			ClusterEngineState.trustedSet(uncertainIntentIds), goalScanOffset, goalScanCandidate,
			routeCursor, ClusterEngineState.trustedMap(coordinationAssociations),
			ClusterEngineState.trustedMap(priorityLeaseAssociations)); }
	}
}
