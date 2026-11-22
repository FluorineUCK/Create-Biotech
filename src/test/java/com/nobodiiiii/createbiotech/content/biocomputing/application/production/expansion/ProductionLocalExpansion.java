package com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryComputeWorker;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRevision;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionResult;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteProcessLease;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;

/** Single authority for same-cluster production pure expansion. No radio or Computer RAM ownership. */
public final class ProductionLocalExpansion {
	public static final String LIFECYCLE_KEY = "local_computation/lifecycle";
	public interface Persistence {
		Optional<Lifecycle> load();
		/** Atomically commit the lifecycle and, when present, the changed production plan. */
		void commit(Lifecycle lifecycle, Optional<FrozenProductionPlan> plan);
		Optional<ProductionOutputContract.Snapshot> output(UUID runId);
	}
	public interface Evidence {
		@Nullable FrozenProductionPlan plan(UUID goalId);
		@Nullable ProductionOutputContract.Snapshot output(UUID runId);
		Set<UUID> remoteChildIds();
		void publishPlan(FrozenProductionPlan plan);
	}
	private final HolderLookup.Provider registries;
	private final FactoryRuntimeService runtime;
	private final int recoveryLedgerCapacity;
	private final Persistence persistence;
	private final Evidence evidence;
	private final Map<UUID, Job> jobs = new LinkedHashMap<>();
	public ProductionLocalExpansion(HolderLookup.Provider registries, FactoryRuntimeService runtime,
		int recoveryLedgerCapacity, Persistence persistence, Evidence evidence) {
		this.registries = Objects.requireNonNull(registries, "registries");
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		if (recoveryLedgerCapacity <= 0)
			throw new IllegalArgumentException("local computation capacity must be positive");
		this.recoveryLedgerCapacity = recoveryLedgerCapacity;
		this.persistence = Objects.requireNonNull(persistence, "persistence");
		this.evidence = Objects.requireNonNull(evidence, "evidence");
	}
	/** Called after remote recovery so lineage overlap uses the current remote authority. */
	public void restore() {
		Lifecycle restored = persistence.load().orElseGet(() -> new Lifecycle(Map.of()));
		validateLocalComputationLifecycle(restored);
		installLocalComputationLifecycle(restored);
	}
	public Optional<Job> job(UUID runId) { return Optional.ofNullable(jobs.get(runId)); }
	public boolean containsRun(UUID runId) { return jobs.containsKey(runId); }
	public int count() { return jobs.size(); }
	public Optional<Job> jobForGoal(UUID goalId) {
		Objects.requireNonNull(goalId, "goalId");
		return jobs.values().stream().filter(job -> job.goalId().equals(goalId))
			.sorted(Comparator.comparing(Job::runId).reversed()).findFirst();
	}
	private static boolean waitsForPureComputation(GoalRecord goal) {
		return goal != null && goal.state() == GoalState.WAITING
			&& (goal.waitReason() == WaitReason.ROUTE || goal.waitReason() == WaitReason.PROCESS
				|| goal.waitReason() == WaitReason.RECOVERY_LEDGER_FULL);
	}
	public enum BeginStatus {
		STARTED,
		NO_PROCESS_CAPACITY,
		RECOVERY_LEDGER_FULL
	}

	public record BeginResult(BeginStatus status,
		Optional<Job> job) {
		public BeginResult {
			status = Objects.requireNonNull(status, "status");
			job = Objects.requireNonNull(job, "job");
			if ((status == BeginStatus.STARTED) != job.isPresent())
				throw new IllegalArgumentException("local begin status/job mismatch");
		}
	}

	public static WaitReason beginWaitReason(BeginStatus status) {
		return switch (Objects.requireNonNull(status, "status")) {
			case NO_PROCESS_CAPACITY -> WaitReason.PROCESS;
			case RECOVERY_LEDGER_FULL -> WaitReason.RECOVERY_LEDGER_FULL;
			case STARTED -> throw new IllegalArgumentException(
				"a started local computation has no wait reason");
		};
	}

	public BeginResult begin(FrozenProductionPlan plan, ProductionRun run,
		ProcessRecord sourceProcess, ClusterEngineState sourceState, Target target) {
		Job existing = jobs.get(
			Objects.requireNonNull(run, "run").runId());
		if (existing != null) return new BeginResult(
			BeginStatus.STARTED, Optional.of(existing));
		if (jobs.size() >= recoveryLedgerCapacity)
			return new BeginResult(
				BeginStatus.RECOVERY_LEDGER_FULL, Optional.empty());
		RemoteProcessLease lease;
		try {
			lease = leaseFor(registries, Objects.requireNonNull(plan, "plan"), run,
				Objects.requireNonNull(sourceProcess, "sourceProcess"),
				Objects.requireNonNull(sourceState, "sourceState"),
				Objects.requireNonNull(target, "target"));
		} catch (IllegalArgumentException | ArithmeticException staleTarget) {
			return new BeginResult(
				BeginStatus.NO_PROCESS_CAPACITY, Optional.empty());
		}
		FactoryRuntimeService.RemoteComputationAllocation allocation = runtime
			.acquireRemoteComputationProcess(lease, target.memberId()).orElse(null);
		if (allocation == null) return new BeginResult(
			BeginStatus.NO_PROCESS_CAPACITY, Optional.empty());
		Job job = Job.begin(run.runId(), plan.goalId(), lease,
			sourceProcess.owningComputerId(), allocation);
		try {
			persistLocalComputation(job);
		} catch (RuntimeException persistenceFailure) {
			runtime.releaseRemoteComputationProcess(allocation.processId(), lease,
				allocation.receiverMemberId(), allocation.owningSlot());
			throw persistenceFailure;
		}
		return new BeginResult(BeginStatus.STARTED,
			Optional.of(job));
	}

	public record Target(UUID memberId, long generation, int owningSlot,
		int occupiedProcesses) {
		public Target {
			memberId = Objects.requireNonNull(memberId, "memberId");
			if (generation < 0 || owningSlot < 0 || occupiedProcesses < 0)
				throw new IllegalArgumentException("negative local computation placement");
		}
	}

	public static Optional<Target> selectTarget(
		ClusterEngineState state, ProcessRecord source, ProductionCapability requiredCapability) {
		ClusterEngineState exactState = Objects.requireNonNull(state, "state");
		ProcessRecord exactSource = Objects.requireNonNull(source, "source");
		ProductionCapability exactCapability = Objects.requireNonNull(requiredCapability,
			"requiredCapability");
		GoalRecord goal = exactState.goals().get(exactSource.goalId());
		if (!exactSource.equals(exactState.processes().get(exactSource.processId())) || goal == null
			|| !exactState.clusterId().equals(goal.owningClusterId()))
			throw new IllegalArgumentException("local expansion source is outside the Cluster");
		return exactState.members().stream()
			.filter(member -> member.loaded()
				&& !member.computerId().equals(exactSource.owningComputerId())
				&& member.profile().supports(exactCapability))
			.map(member -> {
				Set<Integer> occupiedSlots = exactState.processes().values().stream()
					.filter(process -> process.state()
						!= com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState.TERMINAL)
					.filter(process -> process.owningComputerId().equals(member.computerId()))
					.map(ProcessRecord::owningSlot)
					.collect(Collectors.toCollection(LinkedHashSet::new));
				int slot = -1;
				for (int candidate = 0; candidate < member.profile().processSlots(); candidate++)
					if (!occupiedSlots.contains(candidate)) { slot = candidate; break; }
				return slot < 0 ? null : new Target(member.computerId(),
					member.routeGeneration(), slot, occupiedSlots.size());
			})
			.filter(Objects::nonNull)
			.sorted(Comparator.comparingInt(Target::occupiedProcesses)
				.thenComparing(Target::memberId))
			.findFirst();
	}

	public enum Phase {
		REVISION_WORK_PENDING,
		RESULT_WORK_PENDING,
		COMPLETED
	}

	public record Job(UUID runId, UUID goalId, RemoteProcessLease lease,
		UUID sourceMemberId, UUID receiverMemberId, int owningSlot, UUID processId,
		Phase phase,
		Optional<RemoteExpansionRevision> revisionState,
		Optional<RemoteExpansionResult> result) {
		public Job {
			runId = Objects.requireNonNull(runId, "runId");
			goalId = Objects.requireNonNull(goalId, "goalId");
			lease = Objects.requireNonNull(lease, "lease");
			sourceMemberId = Objects.requireNonNull(sourceMemberId, "sourceMemberId");
			receiverMemberId = Objects.requireNonNull(receiverMemberId, "receiverMemberId");
			processId = Objects.requireNonNull(processId, "processId");
			phase = Objects.requireNonNull(phase, "phase");
			revisionState = Objects.requireNonNull(revisionState, "revisionState");
			result = Objects.requireNonNull(result, "result");
			if (owningSlot < 0 || sourceMemberId.equals(receiverMemberId)
				|| !runId.equals(lease.expansionRequest().runId())
				|| !goalId.equals(lease.sourceGoalId())
				|| !lease.sourceClusterId().equals(lease.receiverClusterId()))
				throw new IllegalArgumentException("invalid local computation ownership");
			boolean hasRevision = revisionState.isPresent();
			boolean hasResult = result.isPresent();
			if (switch (phase) {
				case REVISION_WORK_PENDING -> hasRevision || hasResult;
				case RESULT_WORK_PENDING -> !hasRevision || hasResult;
				case COMPLETED -> !hasRevision || !hasResult;
			}) throw new IllegalArgumentException("local computation phase/evidence mismatch");
			if (revisionState.isPresent()
				&& !revisionState.orElseThrow().matches(lease.expansionRequest()))
				throw new IllegalArgumentException("local revision does not match lease");
			if (hasResult && !result.orElseThrow().matches(lease.expansionRequest(),
				revisionState.orElseThrow()))
				throw new IllegalArgumentException("local result does not match revision");
		}

		public static Job begin(UUID runId, UUID goalId,
			RemoteProcessLease lease, UUID sourceMemberId,
			FactoryRuntimeService.RemoteComputationAllocation allocation) {
			Objects.requireNonNull(allocation, "allocation");
			return new Job(runId, goalId, lease,
				Objects.requireNonNull(sourceMemberId, "sourceMemberId"),
				allocation.receiverMemberId(), allocation.owningSlot(), allocation.processId(),
				Phase.REVISION_WORK_PENDING, Optional.empty(), Optional.empty());
		}

		private Job reassigned(
			FactoryRuntimeService.RemoteComputationAllocation allocation) {
			if (!allocation.receiverMemberId().equals(receiverMemberId))
				throw new IllegalArgumentException("local computation changed receiver member");
			return new Job(runId, goalId, lease, sourceMemberId, receiverMemberId,
				allocation.owningSlot(), allocation.processId(), phase, revisionState, result);
		}

		private Job withRevision(RemoteExpansionRevision revision) {
			if (phase != Phase.REVISION_WORK_PENDING)
				throw new IllegalStateException("local revision work is not pending");
			return new Job(runId, goalId, lease, sourceMemberId, receiverMemberId,
				owningSlot, processId, Phase.RESULT_WORK_PENDING,
				Optional.of(Objects.requireNonNull(revision)), Optional.empty());
		}

		private Job withResult(RemoteExpansionResult completed) {
			if (phase != Phase.RESULT_WORK_PENDING)
				throw new IllegalStateException("local result work is not pending");
			return new Job(runId, goalId, lease, sourceMemberId, receiverMemberId,
				owningSlot, processId, Phase.COMPLETED, revisionState,
				Optional.of(Objects.requireNonNull(completed)));
		}
	}

	public record Lifecycle(Map<UUID, Job> jobs) {
		public Lifecycle {
			jobs = Map.copyOf(Objects.requireNonNull(jobs, "jobs"));
			jobs.forEach((runId, job) -> {
				if (!runId.equals(job.runId()))
					throw new IllegalArgumentException("local computation lifecycle key mismatch");
			});
		}
	}

	/** Stable historical lease identity; runtime allocation, not this slot hint, owns the slot. */
	public static RemoteProcessLease leaseFor(
		net.minecraft.core.HolderLookup.Provider registries, FrozenProductionPlan plan,
		ProductionRun run, ProcessRecord sourceProcess, ClusterEngineState sourceState,
		Target target) {
		Objects.requireNonNull(sourceState, "sourceState");
		target = Objects.requireNonNull(target, "target");
		ClusterEngineState.MemberSnapshot sourceMember = sourceState.member(
			Objects.requireNonNull(sourceProcess, "sourceProcess").owningComputerId());
		ClusterEngineState.MemberSnapshot receiverMember = sourceState.member(target.memberId());
		if (sourceMember == null || receiverMember == null || !receiverMember.loaded()
			|| receiverMember.routeGeneration() != target.generation()
			|| !receiverMember.profile().supports(ProductionCapability.ALCHEMIST)
			|| sourceMember.computerId().equals(receiverMember.computerId()))
			throw new IllegalArgumentException("same-Cluster expansion endpoints are not eligible");
		RemoteExpansionRequest request = PureExpansionCapture.capture(registries, plan, run,
			sourceProcess, sourceState.generation());
		UUID leaseId = UUID.nameUUIDFromBytes(("local-computation/lease/" + run.runId() + "/"
			+ sourceState.clusterId() + "/" + sourceMember.computerId() + "/"
			+ sourceMember.routeGeneration() + "/" + receiverMember.computerId() + "/"
			+ receiverMember.routeGeneration()).getBytes(StandardCharsets.UTF_8));
		return new RemoteProcessLease(leaseId, run.goalId(), sourceProcess.processId(),
			sourceState.clusterId(), sourceState.clusterId(), sourceMember.routeGeneration(),
			receiverMember.routeGeneration(), ProductionCapability.ALCHEMIST, request,
			request.parentStateGeneration());
	}

	public boolean sourceFenceCurrent(Job job,
		ProcessRecord process, ClusterEngineState sourceState) {
		RemoteExpansionRequest request = job.lease().expansionRequest();
		ClusterEngineState.MemberSnapshot current = sourceState.member(
			process.owningComputerId());
		return request.parentProcessId().equals(process.processId())
			&& request.parentProcessGeneration() == process.generation()
			&& process.owningComputerId().equals(job.sourceMemberId())
			&& current != null && current.loaded()
			&& current.routeGeneration() == job.lease().sourceGeneration()
			&& sourceState.clusterId().equals(job.lease().sourceClusterId());
	}

	/** Completion alone is insufficient: revalidate the live parent and durable child ACK. */
	public Optional<FrozenProductionPlan> materialReady(
		FrozenProductionPlan plan, Job job) {
		if (job.phase() != Phase.COMPLETED
			|| !plan.remoteExpansionMaterialReady()) return Optional.empty();
		FrozenProductionPlan.RemoteExpansionInstallation installation = plan.remoteExpansion()
			.orElse(null);
		if (installation == null
			|| !installation.request().equals(job.lease().expansionRequest())
			|| !job.result().equals(installation.result())
			|| !runtime.installRemoteChildRevision(job.lease(), installation.revision())
			|| !runtime.remoteExpansionMaterialAuthorized(job.lease(),
				installation.revision())) return Optional.empty();
		return Optional.of(plan);
	}

	public void advance() {
		for (Job original : List.copyOf(jobs.values())) {
			Job job = original;
			FrozenProductionPlan plan = evidence.plan(job.goalId());
			ProductionRun current = plan == null ? null : plan.runs().currentRun();
			if (plan == null || current == null || !current.runId().equals(job.runId())) {
				terminate(job, plan != null);
				continue;
			}
			if (job.phase() == Phase.COMPLETED) {
				if (!runtime.releaseRemoteComputationProcess(job.processId(), job.lease(),
					job.receiverMemberId(), job.owningSlot())) continue;
				continue;
			}
			ClusterEngineState receiverState = runtime.snapshotForServerMutation(
				job.lease().receiverClusterId()).orElse(null);
			if (receiverState == null) continue;
			ProcessRecord sourceProcess = receiverState == null ? null
				: receiverState.processes().get(job.lease().sourceProcessId());
			ClusterEngineState.MemberSnapshot receiver = receiverState == null ? null
				: receiverState.member(job.receiverMemberId());
			if (sourceProcess == null
				|| !sourceFenceCurrent(job, sourceProcess, receiverState)
				|| receiver == null || !receiver.loaded()
				|| receiver.routeGeneration() != job.lease().receiverGeneration()
				|| !receiver.profile().supports(job.lease().requiredCapability())) {
				terminate(job, true);
				continue;
			}
			if (!runtime.ensureRemoteComputationProcess(job.processId(), job.lease(),
				job.receiverMemberId(), job.owningSlot())) {
				FactoryRuntimeService.RemoteComputationAllocation reassigned = runtime
					.acquireRemoteComputationProcess(job.lease(), job.receiverMemberId())
					.orElse(null);
				if (reassigned == null) continue;
				job = job.reassigned(reassigned);
				persistLocalComputation(job);
			}
			if (job.phase() == Phase.REVISION_WORK_PENDING) {
				FactoryComputeWorker.RemotePureWork work = localPureWork(job,
					FactoryComputeWorker.RemotePureStage.REVISION);
				Optional<FactoryComputeWorker.RemotePureResult> ready =
					runtime.drainRemotePureResult(work);
				if (ready.isEmpty()) {
					runtime.submitRemotePureWork(work);
					continue;
				}
				RemoteExpansionRevision revision = ready.orElseThrow().revisionState()
					.orElseThrow();
				if (!runtime.installRemoteChildRevision(job.lease(), revision)) continue;
				FrozenProductionPlan nextPlan = plan.installRemoteRevision(
					job.lease().expansionRequest(), revision);
				Job nextJob = job.withRevision(revision);
				persistLocalComputation(nextJob, nextPlan);
				continue;
			}
			RemoteExpansionRevision revision = job.revisionState().orElseThrow();
			if (!runtime.installRemoteChildRevision(job.lease(), revision)
				|| !runtime.remoteExpansionMaterialAuthorized(job.lease(), revision)) continue;
			FactoryComputeWorker.RemotePureWork work = localPureWork(job,
				FactoryComputeWorker.RemotePureStage.RESULT);
			Optional<FactoryComputeWorker.RemotePureResult> ready =
				runtime.drainRemotePureResult(work);
			if (ready.isEmpty()) {
				runtime.submitRemotePureWork(work);
				continue;
			}
			RemoteExpansionResult result = ready.orElseThrow().expansionResult()
				.orElseThrow();
			FrozenProductionPlan nextPlan = plan.installRemoteResult(result);
			Job nextJob = job.withResult(result);
			persistLocalComputation(nextJob, nextPlan);
			runtime.releaseRemoteComputationProcess(nextJob.processId(), nextJob.lease(),
				nextJob.receiverMemberId(), nextJob.owningSlot());
		}
	}

	private static FactoryComputeWorker.RemotePureWork localPureWork(
		Job job, FactoryComputeWorker.RemotePureStage stage) {
		return new FactoryComputeWorker.RemotePureWork(job.lease().leaseId(),
			job.lease().receiverClusterId(), job.receiverMemberId(), job.processId(),
			job.owningSlot(), job.lease().receiverGeneration(), job.lease().revisionFence(),
			job.lease().requiredCapability(), job.lease().expansionRequest(), stage,
			stage == FactoryComputeWorker.RemotePureStage.RESULT
				? job.revisionState() : Optional.empty());
	}

	private void persistLocalComputation(Job job) {
		Lifecycle lifecycle = localLifecycleWith(job);
		validateLocalComputationLifecycle(lifecycle);
		persistence.commit(lifecycle, Optional.empty());
		installLocalComputationLifecycle(lifecycle);
	}

	private void persistLocalComputation(Job job,
		FrozenProductionPlan plan) {
		if (!job.goalId().equals(plan.goalId()))
			throw new IllegalArgumentException("local job/plan identity mismatch");
		Lifecycle lifecycle = localLifecycleWith(job);
		validateLocalComputationLifecycle(lifecycle);
		persistence.commit(lifecycle, Optional.of(plan));
		installLocalComputationLifecycle(lifecycle);
		evidence.publishPlan(plan);
	}

	private Lifecycle localLifecycleWith(Job job) {
		LinkedHashMap<UUID, Job> nextJobs =
			new LinkedHashMap<>(jobs);
		nextJobs.put(job.runId(), job);
		return new Lifecycle(nextJobs);
	}

	private void installLocalComputationLifecycle(Lifecycle state) {
		jobs.clear();
		jobs.putAll(state.jobs());
	}

	private void validateLocalComputationLifecycle(Lifecycle state) {
		if (state.jobs().size() > recoveryLedgerCapacity)
			throw new IllegalStateException("local computation lifecycle evidence is full");
		Set<UUID> children = new HashSet<>();
		for (Job job : state.jobs().values()) {
			UUID child = RemoteExpansionRevision.logicalChildId(
				job.lease().expansionRequest());
			if (!children.add(child) || evidence.remoteChildIds().contains(child))
				throw new IllegalStateException("pure computation child lineage overlaps");
		}
	}

	public boolean terminate(Job job, boolean clearPlan) {
		job = Objects.requireNonNull(job, "job");
		LinkedHashMap<UUID, Job> nextJobs =
			new LinkedHashMap<>(jobs);
		if (nextJobs.remove(job.runId()) == null) return true;
		Lifecycle lifecycle = new Lifecycle(nextJobs);
		validateLocalComputationLifecycle(lifecycle);
		FrozenProductionPlan plan = evidence.plan(job.goalId());
		FrozenProductionPlan cleared = plan;
		if (clearPlan && plan != null && plan.remoteExpansion().isPresent()
			&& plan.remoteExpansion().orElseThrow().request()
				.equals(job.lease().expansionRequest()))
			cleared = plan.clearRemoteExpansion(job.lease().expansionRequest());
		if (runtime.snapshotForServerMutation(job.lease().receiverClusterId()).isEmpty())
			return false;
		if (!runtime.releaseRemoteComputationProcess(job.processId(), job.lease(),
				job.receiverMemberId(), job.owningSlot())) return false;
		persistence.commit(lifecycle, cleared != null && !cleared.equals(plan)
				? Optional.of(cleared) : Optional.empty());
		installLocalComputationLifecycle(lifecycle);
		runtime.retireRemotePureArtifacts(job.lease());
		if (cleared != null) evidence.publishPlan(cleared);
		return true;
	}

	/** Keep cleanup evidence until the exact process release and durable downstream output boundary. */
	public void retireCompleted() {
		for (Job job : List.copyOf(jobs.values())) {
			if (job.phase() != Phase.COMPLETED) continue;
			FrozenProductionPlan plan = evidence.plan(job.goalId());
			ProductionOutputContract.Snapshot output = evidence.output(job.runId());
			if (plan == null || output == null
				|| !durableLocalOutputBoundary(job, plan, output)) continue;
			if (!runtime.releaseRemoteComputationProcess(job.processId(), job.lease(),
				job.receiverMemberId(), job.owningSlot())) continue;
			LinkedHashMap<UUID, Job> nextJobs = new LinkedHashMap<>(jobs);
			nextJobs.remove(job.runId());
			Lifecycle lifecycle = new Lifecycle(nextJobs);
			validateLocalComputationLifecycle(lifecycle);
			persistence.commit(lifecycle, Optional.empty());
			installLocalComputationLifecycle(lifecycle);
			runtime.retireRemotePureArtifacts(job.lease());
		}
	}

	private boolean durableLocalOutputBoundary(Job job,
		FrozenProductionPlan plan, ProductionOutputContract.Snapshot output) {
		if (!output.run().runId().equals(job.runId())
			|| (output.milestone() != ProductionOutputContract.Milestone.OUTPUT_DISPATCHED
				&& output.milestone() != ProductionOutputContract.Milestone.OUTPUT_RECEIVED)
			|| !output.agentReleased()) return false;
		ProductionOutputContract.Snapshot durable = persistence.output(job.runId()).orElse(null);
		if (!output.equals(durable)) return false;
		ProductionRun current = plan.runs().currentRun();
		boolean advanced = current == null || !current.runId().equals(job.runId());
		return advanced && plan.remoteExpansion().isEmpty() || plan.cancelAfterDrain();
	}

	public void resumeSource(Job job) {
		ClusterEngineState source = runtime.snapshotForServerMutation(job.lease().sourceClusterId())
			.orElse(null);
		GoalRecord goal = source == null ? null : source.goals().get(job.goalId());
		if (waitsForPureComputation(goal))
			runtime.resumePanelGoal(source.clusterId(), source.epochId(), source.generation(),
				goal.goalId());
	}
}

