package com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryComputeWorker;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionResult;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRevision;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionPublication.*;

/** Sole lifecycle authority for remote production expansion; generic programs and RAM are independent. */
public final class ProductionRemoteExpansion {
	public static final String LIFECYCLE_KEY = "remote_computation/lifecycle";
	public interface Persistence {
		/** Historical lifecycle read and one atomic journal batch, without live publication. */
		Optional<RemoteComputationLifecycleState> load();
		void commit(Optional<RemoteComputationLifecycleState> lifecycle, Optional<FrozenProductionPlan> plan,
			Optional<RadioRouter.LeaseProtocolState> leases, Optional<DurableRadioOutbox.State> outbox);
		Optional<ProductionOutputContract.Snapshot> output(UUID runId);
	}
	public interface Evidence {
		/** Current world/plan facts and publication of values already durably committed. */
		@Nullable FrozenProductionPlan plan(UUID goalId);
		@Nullable ProductionOutputContract.Snapshot output(UUID runId);
		@Nullable RadioEndpoint endpoint(RadioEndpoint.Key key);
		List<RadioEndpoint> endpoints();
		boolean linkPossible(RadioEndpoint source, RadioEndpoint target, CommunicationFrequency frequency);
		List<RadioEndpoint.Hop> relays(RadioEndpoint source, RadioEndpoint target, CommunicationFrequency frequency);
		void publishPlan(FrozenProductionPlan plan);
		void enqueueMaterialReplay(UUID runId);
	}
	private final HolderLookup.Provider registries;
	private final FactoryRuntimeService runtime;
	private final int recoveryLedgerCapacity;
	private final RadioRouter radioRouter;
	private final DurableRadioOutbox radioOutbox;
	private final Persistence persistence;
	private final Evidence evidence;
	private final Map<UUID, RemoteComputationExchange> remoteComputationExchanges = new LinkedHashMap<>();
	private final Map<UUID, RemoteComputationJob> remoteComputationJobs = new LinkedHashMap<>();
	private final Map<UUID, RetiredRemoteComputation> retiredRemoteComputations = new LinkedHashMap<>();
	public ProductionRemoteExpansion(HolderLookup.Provider registries, FactoryRuntimeService runtime,
		int recoveryLedgerCapacity, RadioRouter radioRouter, DurableRadioOutbox radioOutbox,
		Persistence persistence, Evidence evidence) {
		this.registries = Objects.requireNonNull(registries);
		this.runtime = Objects.requireNonNull(runtime);
		if (recoveryLedgerCapacity <= 0) throw new IllegalArgumentException("remote capacity must be positive");
		this.recoveryLedgerCapacity = recoveryLedgerCapacity;
		this.radioRouter = Objects.requireNonNull(radioRouter);
		this.radioOutbox = Objects.requireNonNull(radioOutbox);
		this.persistence = Objects.requireNonNull(persistence);
		this.evidence = Objects.requireNonNull(evidence);
	}
	public void restore() {
		RemoteComputationLifecycleState restored = persistence.load().orElseGet(() ->
			new RemoteComputationLifecycleState(Map.of(), Map.of(), Map.of()));
		validateRemoteComputationLifecycle(restored);
		installRemoteComputationLifecycle(restored);
	}
	public RemoteComputationLifecycleState snapshot() {
		return new RemoteComputationLifecycleState(remoteComputationExchanges, remoteComputationJobs, retiredRemoteComputations);
	}
	public Optional<RemoteComputationExchange> exchange(UUID runId) { return Optional.ofNullable(remoteComputationExchanges.get(runId)); }
	public List<RemoteComputationExchange> exchanges() { return List.copyOf(remoteComputationExchanges.values()); }
	public List<RemoteComputationJob> jobs() { return List.copyOf(remoteComputationJobs.values()); }
	public boolean containsRun(UUID runId) { return remoteComputationExchanges.containsKey(runId); }
	public Set<UUID> childIds() { return remoteComputationExchanges.values().stream().map(value ->
		RemoteExpansionRevision.logicalChildId(value.lease().expansionRequest())).collect(Collectors.toUnmodifiableSet()); }
	public int retiredCount() { return retiredRemoteComputations.size(); }
	public int retiredCount(UUID goalId) { return (int) retiredRemoteComputations.values().stream().filter(value -> value.sourceGoalId().equals(goalId)).count(); }
	public static long nextRemoteComputationRevision(long sourceStateGeneration, UUID goalId, UUID runId,
		Collection<RetiredRemoteComputation> retiredComputations) {
		if (sourceStateGeneration < 0)
			throw new IllegalArgumentException("negative source state generation");
		UUID exactGoalId = Objects.requireNonNull(goalId, "goalId");
		UUID exactRunId = Objects.requireNonNull(runId, "runId");
		long retiredFence = retiredComputations.stream()
			.filter(retired -> retired.sourceGoalId().equals(exactGoalId)
				&& retired.runId().equals(exactRunId))
			.mapToLong(RetiredRemoteComputation::revisionFence).max().orElse(-1);
		return retiredFence < sourceStateGeneration ? sourceStateGeneration
			: Math.incrementExact(retiredFence);
	}
	public static boolean shouldMigrateRemoteComputation(boolean durableDownstreamEvidence,
		boolean durableMaterialHandoff, boolean submittedThisSession) {
		return !durableDownstreamEvidence && !durableMaterialHandoff && !submittedThisSession;
	}
	public static boolean remoteMaterialHandoffOwnsRun(FrozenProductionPlan plan, UUID runId) {
		plan = Objects.requireNonNull(plan, "plan");
		runId = Objects.requireNonNull(runId, "runId");
		ProductionRun run = plan.runs().currentRun();
		return plan.dispatchQueued() && run != null && run.runId().equals(runId)
			&& plan.remoteExpansion().flatMap(
				FrozenProductionPlan.RemoteExpansionInstallation::result).isPresent();
	}
	public static boolean remoteMaterialHandoffMayPublish(FrozenProductionPlan plan,
		RemoteComputationExchange exchange, RadioRouter router,
		BooleanSupplier sourceLineageAuthorization) {
		plan = Objects.requireNonNull(plan, "plan");
		exchange = Objects.requireNonNull(exchange, "exchange");
		router = Objects.requireNonNull(router, "router");
		sourceLineageAuthorization = Objects.requireNonNull(sourceLineageAuthorization,
			"sourceLineageAuthorization");
		ProductionRun run = plan.runs().currentRun();
		FrozenProductionPlan.RemoteExpansionInstallation installation = plan.remoteExpansion()
			.orElse(null);
		if (!exchange.materialDispatchAllowed() || run == null
			|| !run.runId().equals(exchange.runId()) || !plan.goalId().equals(exchange.goalId())
			|| installation == null || installation.result().isEmpty()
			|| !installation.request().equals(exchange.lease().expansionRequest())) return false;
		RadioEndpoint.Key receiver = exchange.offerEnvelope().targetHop().key();
		if (plan.dispatchQueued())
			return remoteMaterialHandoffOwnsRun(plan, exchange.runId())
				&& router.materialIntentAuthorized(receiver, exchange.lease().leaseId(),
					exchange.revision());
		return sourceLineageAuthorization.getAsBoolean();
	}
	public boolean sourceFenceCurrent(RemoteComputationExchange exchange,
		ProcessRecord process, ClusterEngineState sourceState) {
		RemoteExpansionRequest request = exchange.lease().expansionRequest();
		RadioEndpoint current = evidence.endpoint(new RadioEndpoint.Key(sourceState.clusterId(),
			process.owningComputerId()));
		return request.parentProcessId().equals(process.processId())
			&& request.parentProcessGeneration() == process.generation()
			&& current != null && current.generation() == exchange.lease().sourceGeneration()
			&& current.key().equals(exchange.offerEnvelope().sourceHop().key());
	}

	public Optional<FrozenProductionPlan> materialReady(FrozenProductionPlan plan,
		RemoteComputationExchange exchange) {
		if (!exchange.materialDispatchAllowed() || !plan.remoteExpansionMaterialReady())
			return Optional.empty();
		FrozenProductionPlan.RemoteExpansionInstallation installation = plan.remoteExpansion()
			.orElseThrow();
		if (!installation.request().equals(exchange.lease().expansionRequest()))
			return Optional.empty();
		RadioEndpoint.Key receiver = exchange.offerEnvelope().targetHop().key();
		if (!remoteMaterialHandoffMayPublish(plan, exchange, radioRouter, () ->
			runtime.installRemoteChildRevision(exchange.lease(), installation.revision())
				&& runtime.remoteExpansionMaterialAuthorized(exchange.lease(),
					installation.revision()))) return Optional.empty();
		if (plan.dispatchQueued()) return Optional.of(plan);
		RadioRouter.PreparedMaterialAuthorization authorization = radioRouter
			.prepareMaterialIntentAuthorization(receiver, exchange.lease().leaseId(),
				exchange.revision()).orElse(null);
		if (authorization == null) return Optional.empty();
		FrozenProductionPlan receipt = plan.markDispatchQueued();
		publishRemoteMaterialHandoff((durablePlan, durableLeases) ->
			persistence.commit(Optional.empty(), Optional.of(durablePlan), Optional.of(durableLeases), Optional.empty()),
			receipt, exchange, authorization,
			evidence::publishPlan,
			radioRouter::installPersistedLeaseProtocolState);
		evidence.enqueueMaterialReplay(exchange.runId());
		return Optional.of(receipt);
	}
	public RemoteComputationBeginResult begin(FrozenProductionPlan plan,
		ProductionRun run, ProcessRecord sourceProcess, ClusterEngineState sourceState) {
		UUID sourceClusterId = sourceState.clusterId();
		RadioEndpoint source = evidence.endpoint(new RadioEndpoint.Key(
			Objects.requireNonNull(sourceClusterId, "sourceClusterId"),
			sourceProcess.owningComputerId()));
		if (source == null || !source.frequency().configured())
			return new RemoteComputationBeginResult(
			RemoteComputationBeginStatus.NO_ROUTE, Optional.empty());
		RadioEndpoint receiver = evidence.endpoints().stream()
			.filter(candidate -> !candidate.clusterId().equals(source.clusterId()))
			.filter(candidate -> candidate.frequency().configured())
			.filter(candidate -> candidate.frequency().equals(source.frequency()))
			.filter(candidate -> candidate.listenerCapacity() > 0)
			.filter(candidate -> candidate.supports(ProductionCapability.ALCHEMIST))
			.filter(candidate -> remoteComputationSlotAvailable(candidate))
			.filter(candidate -> evidence.linkPossible(source, candidate, source.frequency())
				|| !evidence.relays(source, candidate, source.frequency()).isEmpty())
			.sorted(Comparator.comparing(RadioEndpoint::clusterId)
				.thenComparing(RadioEndpoint::memberId)).findFirst().orElse(null);
		if (receiver == null) return new RemoteComputationBeginResult(
			RemoteComputationBeginStatus.NO_ROUTE, Optional.empty());
		List<RadioEndpoint.Hop> forward = evidence.relays(source, receiver,
			source.frequency());
		List<RadioEndpoint.Hop> reverse = evidence.relays(receiver, source,
			source.frequency());
		long revision;
		try {
			revision = nextRemoteComputationRevision(sourceState.generation(), plan.goalId(),
				run.runId(), retiredRemoteComputations.values());
		} catch (ArithmeticException exhaustedFence) {
			return new RemoteComputationBeginResult(
				RemoteComputationBeginStatus.RECOVERY_LEDGER_FULL, Optional.empty());
		}
		RemoteComputationExchange exchange = RemoteComputationExchange.begin(
			registries, plan, run,
			sourceProcess, revision, source, receiver, forward, reverse);
		UUID child = RemoteExpansionRevision.logicalChildId(
			exchange.lease().expansionRequest());
		long occupied = (long) remoteComputationExchanges.size()
			+ retiredRemoteComputations.size()
			- (retiredRemoteComputations.containsKey(child) ? 1 : 0);
		if (occupied >= recoveryLedgerCapacity)
			return new RemoteComputationBeginResult(
				RemoteComputationBeginStatus.RECOVERY_LEDGER_FULL, Optional.empty());
		persistRemoteComputation(exchange);
		return new RemoteComputationBeginResult(RemoteComputationBeginStatus.STARTED,
			Optional.of(exchange));
	}
	private boolean remoteComputationSlotAvailable(RadioEndpoint receiver) {
		int free = freeRemoteComputationSlots(receiver).size();
		long offered = remoteComputationExchanges.values().stream()
			.filter(exchange -> !exchange.materialDispatchAllowed())
			.filter(exchange -> !remoteComputationJobs.containsKey(exchange.runId()))
			.filter(exchange -> exchange.offerEnvelope().targetHop().key().equals(receiver.key()))
			.count();
		return offered < free;
	}
	private List<Integer> freeRemoteComputationSlots(RadioEndpoint receiver) {
		ClusterEngineState target = runtime.snapshot(receiver.clusterId()).orElse(null);
		if (target == null) return List.of();
		ClusterEngineState.MemberSnapshot member = target.member(receiver.memberId());
		if (member == null || !member.loaded()
			|| !member.profile().supports(ProductionCapability.ALCHEMIST)) return List.of();
		Set<Integer> occupied = target.processes().values().stream()
			.filter(process -> process.owningComputerId().equals(receiver.memberId()))
			.filter(process -> process.state()
				!= com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState.TERMINAL)
			.map(ProcessRecord::owningSlot).collect(Collectors.toCollection(LinkedHashSet::new));
		remoteComputationJobs.values().stream()
			.filter(job -> job.phase() != RemoteComputationJobPhase.COMPLETED)
			.filter(job -> job.lease().receiverClusterId().equals(receiver.clusterId()))
			.filter(job -> job.receiverMemberId().equals(receiver.memberId()))
			.map(RemoteComputationJob::owningSlot).forEach(occupied::add);
		ArrayList<Integer> free = new ArrayList<>();
		for (int slot = 0; slot < member.profile().processSlots(); slot++)
			if (!occupied.contains(slot)) free.add(slot);
		return List.copyOf(free);
	}
	public Map<UUID, UUID> messageOwners() {
		LinkedHashMap<UUID, UUID> owners = new LinkedHashMap<>();
		for (RemoteComputationExchange exchange : remoteComputationExchanges.values())
			for (UUID messageId : remoteComputationMessageIds(exchange))
				if (owners.put(messageId, exchange.runId()) != null)
					throw new IllegalStateException("duplicate remote computation message owner");
		return Map.copyOf(owners);
	}
	public void advanceExchanges() {
		for (RemoteComputationExchange exchange :
			List.copyOf(remoteComputationExchanges.values())) {
			if (exchange.phase() == RemoteComputationPhase.ACK_PENDING
				|| exchange.phase() == RemoteComputationPhase.RESULT_COMPUTING
				|| exchange.phase() == RemoteComputationPhase.RESULT_PENDING
				|| exchange.phase() == RemoteComputationPhase.COMPLETED) {
				FrozenProductionPlan plan = evidence.plan(exchange.goalId());
				FrozenProductionPlan.RemoteExpansionInstallation installation = plan == null
					? null : plan.remoteExpansion().orElse(null);
				if (installation == null
					|| !installation.request().equals(exchange.lease().expansionRequest())
					|| !runtime.installRemoteChildRevision(exchange.lease(),
						installation.revision())) continue;
			}
			retireDurablyConsumedRemoteComputationMessages(exchange);
			RadioEnvelope active = exchange.activeEnvelope().orElse(null);
			if (active == null) continue;
			radioOutbox.offer(active, exchange.activeRelays());
			if (radioOutbox.status(active.messageId()).orElse(null)
				== RadioRouter.RouteStatus.WAITING_ROUTE)
				waitRemoteComputationSource(exchange);
		}
	}
	/** Receiver jobs alone produce revision/result payloads, using the actual finite process slot. */
	public void advanceJobs() {
		for (RemoteComputationJob job : List.copyOf(remoteComputationJobs.values())) {
			if (job.phase() == RemoteComputationJobPhase.COMPLETED) {
				runtime.releaseRemoteComputationProcess(job.processId(), job.lease(),
					job.receiverMemberId(), job.owningSlot());
				continue;
			}
			RemoteComputationExchange exchange = remoteComputationExchanges.get(job.runId());
			if (exchange == null || !exchange.lease().equals(job.lease()))
				throw new IllegalStateException("receiver job has no exact source exchange");
			if (!runtime.ensureRemoteComputationProcess(job.processId(), job.lease(),
				job.receiverMemberId(), job.owningSlot())) {
				FactoryRuntimeService.RemoteComputationAllocation reassigned = runtime
					.acquireRemoteComputationProcess(job.lease(), job.receiverMemberId())
					.orElse(null);
				if (reassigned == null) continue;
				job = job.reassigned(reassigned);
				persistRemoteComputation(exchange, job);
			}
			if (job.phase() == RemoteComputationJobPhase.REVISION_WORK_PENDING) {
				FactoryComputeWorker.RemotePureWork work = remotePureWork(job,
					FactoryComputeWorker.RemotePureStage.REVISION);
				Optional<FactoryComputeWorker.RemotePureResult> ready =
					runtime.drainRemotePureResult(work);
				if (ready.isEmpty()) {
					runtime.submitRemotePureWork(work);
					continue;
				}
				RemoteComputationJob nextJob = job.withRevision(ready.orElseThrow()
					.revisionState().orElseThrow());
				RemoteComputationExchange nextExchange = exchange.withReceiverRevision(nextJob);
				persistRemoteComputation(nextExchange, nextJob);
				continue;
			}
			if (job.phase() == RemoteComputationJobPhase.ACK_DURABLE) {
				FactoryComputeWorker.RemotePureWork work = remotePureWork(job,
					FactoryComputeWorker.RemotePureStage.RESULT);
				Optional<FactoryComputeWorker.RemotePureResult> ready =
					runtime.drainRemotePureResult(work);
				if (ready.isEmpty()) {
					runtime.submitRemotePureWork(work);
					continue;
				}
				RemoteComputationJob nextJob = job.withResult(ready.orElseThrow()
					.expansionResult().orElseThrow());
				RemoteComputationExchange nextExchange = exchange.withReceiverResult(nextJob);
				persistRemoteComputation(nextExchange, nextJob);
			}
		}
	}
	private static FactoryComputeWorker.RemotePureWork remotePureWork(
		RemoteComputationJob job, FactoryComputeWorker.RemotePureStage stage) {
		return new FactoryComputeWorker.RemotePureWork(job.lease().leaseId(),
			job.lease().receiverClusterId(), job.receiverMemberId(), job.processId(),
			job.owningSlot(), job.lease().receiverGeneration(), job.lease().revisionFence(),
			job.lease().requiredCapability(), job.lease().expansionRequest(), stage,
			stage == FactoryComputeWorker.RemotePureStage.RESULT
				? job.revisionState() : Optional.empty());
	}
	public void receive(RemoteComputationExchange exchange,
		RadioEnvelope envelope) {
		RadioEnvelope active = exchange.activeEnvelope().orElse(null);
		if (active == null || !active.messageId().equals(envelope.messageId())) {
			retireDurablyConsumedRemoteComputationMessages(exchange);
			return;
		}
		switch (exchange.phase()) {
		case OFFER_PENDING -> {
				RemoteOfferAcceptance accepted = prepareRemoteOfferAcceptance(runtime, exchange,
					envelope, remoteComputationJobs.get(exchange.runId()),
					evidence.endpoint(envelope.targetHop().key())).orElse(null);
				if (accepted == null) return;
				persistRemoteComputation(accepted.exchange(), accepted.job());
				radioOutbox.markApplied(envelope.messageId());
			}
			case REVISION_PENDING -> {
				if (!(envelope.payload() instanceof ProcessProtocolMessage.LeaseRevision delivered)
					|| !delivered.calculationState().matches(exchange.lease().expansionRequest()))
					throw new IllegalStateException("delivered remote revision is not verifiable");
				FrozenProductionPlan plan = evidence.plan(exchange.goalId());
				if (plan == null || !runtime.installRemoteChildRevision(exchange.lease(),
					delivered.calculationState())) return;
				FrozenProductionPlan nextPlan = plan.installRemoteRevision(
					exchange.lease().expansionRequest(), delivered.calculationState());
				RemoteComputationExchange next = exchange.sourceAcceptedRevision();
				persistRemoteComputation(next, nextPlan);
				radioOutbox.markApplied(envelope.messageId());
			}
			case ACK_PENDING -> {
				RemoteComputationJob job = remoteComputationJobs.get(exchange.runId());
				if (job == null || job.phase() != RemoteComputationJobPhase.REVISION_READY)
					throw new IllegalStateException("ACK has no durable receiver revision");
				RemoteComputationJob nextJob = job.acknowledgementDurable();
				persistRemoteComputation(exchange.receiverAcceptedAcknowledgement(), nextJob);
				radioOutbox.markApplied(envelope.messageId());
			}
			case RESULT_PENDING -> {
				RemoteComputationJob job = remoteComputationJobs.get(exchange.runId());
				if (job == null || job.phase() != RemoteComputationJobPhase.RESULT_READY)
					throw new IllegalStateException("result has no durable receiver computation");
				if (!(envelope.payload() instanceof ProcessProtocolMessage.LeaseResult delivered)
					|| !delivered.result().equals(job.result().orElseThrow()))
					throw new IllegalStateException("delivered remote result is not receiver-owned");
				FrozenProductionPlan plan = evidence.plan(exchange.goalId());
				if (plan == null || plan.remoteExpansion().isEmpty()) return;
				FrozenProductionPlan completedPlan = plan.installRemoteResult(
					delivered.result());
				RemoteComputationJob completedJob = job.completed();
				RemoteComputationExchange completedExchange = exchange.sourceAcceptedResult();
				persistRemoteComputation(completedExchange, completedJob, completedPlan);
				radioOutbox.markApplied(envelope.messageId());
				runtime.releaseRemoteComputationProcess(completedJob.processId(),
					completedJob.lease(), completedJob.receiverMemberId(), completedJob.owningSlot());
				resumeSource(completedExchange);
			}
			case REVISION_COMPUTING, RESULT_COMPUTING, COMPLETED ->
				retireDurablyConsumedRemoteComputationMessages(exchange);
		}
	}
	private RemoteComputationLifecycleState lifecycleWith(RemoteComputationExchange exchange,
		@Nullable RemoteComputationJob job) {
		LinkedHashMap<UUID, RemoteComputationExchange> exchanges =
			new LinkedHashMap<>(remoteComputationExchanges);
		LinkedHashMap<UUID, RemoteComputationJob> jobs =
			new LinkedHashMap<>(remoteComputationJobs);
		LinkedHashMap<UUID, RetiredRemoteComputation> retired =
			new LinkedHashMap<>(retiredRemoteComputations);
		exchanges.put(exchange.runId(), exchange);
		if (job != null) jobs.put(job.runId(), job);
		retired.remove(RemoteExpansionRevision.logicalChildId(
			exchange.lease().expansionRequest()));
		return new RemoteComputationLifecycleState(exchanges, jobs, retired);
	}
	private void persistRemoteComputationLifecycle(RemoteComputationLifecycleState state) {
		validateRemoteComputationLifecycle(state);
		persistence.commit(Optional.of(state), Optional.empty(), Optional.empty(), Optional.empty());
		installRemoteComputationLifecycle(state);
	}
	private void installRemoteComputationLifecycle(RemoteComputationLifecycleState state) {
		remoteComputationExchanges.clear();
		remoteComputationExchanges.putAll(state.exchanges());
		remoteComputationJobs.clear();
		remoteComputationJobs.putAll(state.jobs());
		retiredRemoteComputations.clear();
		retiredRemoteComputations.putAll(state.retiredChildren());
	}
	private void validateRemoteComputationLifecycle(RemoteComputationLifecycleState state) {
		if ((long) state.exchanges().size() + state.retiredChildren().size()
			> recoveryLedgerCapacity)
			throw new IllegalStateException("remote computation lifecycle evidence is full");
		Set<UUID> activeChildren = new HashSet<>();
		for (RemoteComputationExchange exchange : state.exchanges().values()) {
			UUID child = RemoteExpansionRevision.logicalChildId(
				exchange.lease().expansionRequest());
			if (!activeChildren.add(child) || state.retiredChildren().containsKey(child))
				throw new IllegalStateException("remote computation child lineage overlaps");
		}
	}
	public boolean terminate(RemoteComputationExchange exchange,
		RemoteComputationTerminalOutcome outcome) {
		exchange = Objects.requireNonNull(exchange, "exchange");
		outcome = Objects.requireNonNull(outcome, "outcome");
		if (outcome == RemoteComputationTerminalOutcome.COMPLETED) return false;
		RadioRouter.LeaseTerminalOutcome routerOutcome = switch (outcome) {
			case CANCELLED -> RadioRouter.LeaseTerminalOutcome.CANCELLED;
			case MIGRATED -> RadioRouter.LeaseTerminalOutcome.MIGRATED;
			case COMPLETED -> throw new IllegalStateException();
		};
		RadioRouter.TerminalLeaseRetirement routerRetirement = radioRouter
			.prepareTerminalLeaseRetirement(exchange.lease(), routerOutcome).orElse(null);
		if (routerRetirement == null) return false;

		UUID child = RemoteExpansionRevision.logicalChildId(
			exchange.lease().expansionRequest());
		RetiredRemoteComputation retired = new RetiredRemoteComputation(child,
			exchange.goalId(), exchange.runId(), exchange.lease().leaseId(), exchange.revision(),
			outcome, exchange.lease().expansionRequest().fingerprint(), Optional.empty());
		LinkedHashMap<UUID, RemoteComputationExchange> exchanges =
			new LinkedHashMap<>(remoteComputationExchanges);
		LinkedHashMap<UUID, RemoteComputationJob> jobs =
			new LinkedHashMap<>(remoteComputationJobs);
		LinkedHashMap<UUID, RetiredRemoteComputation> retiredChildren =
			new LinkedHashMap<>(retiredRemoteComputations);
		exchanges.remove(exchange.runId());
		RemoteComputationJob job = jobs.remove(exchange.runId());
		RetiredRemoteComputation prior = retiredChildren.get(child);
		if (prior != null && prior.revisionFence() > retired.revisionFence()) return false;
		retiredChildren.put(child, retired);
		RemoteComputationLifecycleState lifecycle = new RemoteComputationLifecycleState(
			exchanges, jobs, retiredChildren);
		validateRemoteComputationLifecycle(lifecycle);
		List<UUID> messageIds = remoteComputationMessageIds(exchange);
		DurableRadioOutbox.State terminalOutbox = radioOutbox.without(messageIds);
		FrozenProductionPlan currentPlan = evidence.plan(exchange.goalId());
		FrozenProductionPlan clearedPlan = currentPlan == null ? null
			: currentPlan.clearRemoteExpansion(exchange.lease().expansionRequest());

		// Never discard the only allocation owner while its exact receiver process is still live.
		// Releasing before the batch cannot grant material. If publication fails, the still-active
		// durable job safely reacquires an exact slot on its next receiver turn.
		if (job != null && !runtime.releaseRemoteComputationProcess(job.processId(), job.lease(),
			job.receiverMemberId(), job.owningSlot())) return false;
		FrozenProductionPlan durablePlan = clearedPlan;
		publishRemoteComputationTermination((durableLifecycle, durableLeases, durableOutbox) -> {
			persistence.commit(Optional.of(durableLifecycle), Optional.ofNullable(durablePlan),
				Optional.of(durableLeases), Optional.of(durableOutbox));
		}, lifecycle, routerRetirement, terminalOutbox,
			this::installRemoteComputationLifecycle,
			radioRouter::completePersistedLeaseRetirement,
			radioOutbox::installPersistedState,
			radioRouter::retireTerminalRoute, messageIds);
		runtime.retireRemotePureArtifacts(exchange.lease());
		if (clearedPlan != null) evidence.publishPlan(clearedPlan);
		return true;
	}
	/** Atomically tombstone only completed execution after the exact durable downstream output. */
	public void retireCompleted() {
		for (RemoteComputationExchange exchange :
			List.copyOf(remoteComputationExchanges.values()))
			retireCompletedRemoteComputation(exchange);
	}
	private boolean retireCompletedRemoteComputation(RemoteComputationExchange exchange) {
		if (exchange.phase() != RemoteComputationPhase.COMPLETED) return false;
		RemoteComputationJob job = remoteComputationJobs.get(exchange.runId());
		if (job == null || job.phase() != RemoteComputationJobPhase.COMPLETED
			|| !job.lease().equals(exchange.lease())) return false;
		RemoteExpansionResult result = job.result().orElse(null);
		if (result == null || !result.matches(exchange.lease().expansionRequest(),
			job.revisionState().orElse(null))) return false;
		FrozenProductionPlan plan = evidence.plan(exchange.goalId());
		ProductionOutputContract.Snapshot output = evidence.output(exchange.runId());
		if (plan == null || output == null
			|| !durableOutputBoundary(exchange, plan, output)) return false;
		List<UUID> protocolMessages = remoteComputationMessageIds(exchange);
		if (protocolMessages.size() != 4 || protocolMessages.stream().anyMatch(messageId ->
			radioOutbox.entry(messageId).isPresent())) return false;
		RadioRouter.CompletedLeaseRetirement routerRetirement = radioRouter
			.prepareCompletedLeaseRetirement(exchange.lease(), result).orElse(null);
		if (routerRetirement == null) return false;

		UUID child = RemoteExpansionRevision.logicalChildId(
			exchange.lease().expansionRequest());
		RetiredRemoteComputation retired = new RetiredRemoteComputation(child,
			exchange.goalId(), exchange.runId(), exchange.lease().leaseId(), exchange.revision(),
			RemoteComputationTerminalOutcome.COMPLETED,
			exchange.lease().expansionRequest().fingerprint(), Optional.of(result.fingerprint()));
		LinkedHashMap<UUID, RemoteComputationExchange> exchanges =
			new LinkedHashMap<>(remoteComputationExchanges);
		LinkedHashMap<UUID, RemoteComputationJob> jobs =
			new LinkedHashMap<>(remoteComputationJobs);
		LinkedHashMap<UUID, RetiredRemoteComputation> retiredChildren =
			new LinkedHashMap<>(retiredRemoteComputations);
		exchanges.remove(exchange.runId());
		jobs.remove(exchange.runId());
		RetiredRemoteComputation prior = retiredChildren.get(child);
		if (prior != null && prior.revisionFence() > retired.revisionFence()) return false;
		retiredChildren.put(child, retired);
		RemoteComputationLifecycleState lifecycle = new RemoteComputationLifecycleState(
			exchanges, jobs, retiredChildren);
		validateRemoteComputationLifecycle(lifecycle);
		// A completed receiver may still be busy/unavailable when its earlier release was attempted.
		// Keep its sole durable cleanup owner until this exact allocation is confirmed released.
		if (!runtime.releaseRemoteComputationProcess(job.processId(), job.lease(),
			job.receiverMemberId(), job.owningSlot())) return false;
		publishRemoteComputationRetirement((durableLifecycle, durableLeases) ->
			persistence.commit(Optional.of(durableLifecycle), Optional.empty(), Optional.of(durableLeases), Optional.empty()),
			lifecycle, routerRetirement, this::installRemoteComputationLifecycle,
			radioRouter::completePersistedLeaseRetirement,
			radioRouter::retireDeliveredRoute, protocolMessages);
		return true;
	}
	private boolean durableOutputBoundary(RemoteComputationExchange exchange,
		FrozenProductionPlan plan, ProductionOutputContract.Snapshot output) {
		if (!output.run().runId().equals(exchange.runId())
			|| (output.milestone() != ProductionOutputContract.Milestone.OUTPUT_DISPATCHED
				&& output.milestone() != ProductionOutputContract.Milestone.OUTPUT_RECEIVED)
			|| !output.agentReleased()) return false;
		ProductionOutputContract.Snapshot durable = persistence.output(exchange.runId()).orElse(null);
		if (!output.equals(durable)) return false;
		ProductionRun current = plan.runs().currentRun();
		boolean advanced = current == null || !current.runId().equals(exchange.runId());
		return advanced && plan.remoteExpansion().isEmpty() || plan.cancelAfterDrain();
	}
	private static List<UUID> remoteComputationMessageIds(
		RemoteComputationExchange exchange) {
		ArrayList<UUID> messages = new ArrayList<>();
		messages.add(exchange.offerEnvelope().messageId());
		exchange.revisionEnvelope().map(RadioEnvelope::messageId).ifPresent(messages::add);
		exchange.acknowledgementEnvelope().map(RadioEnvelope::messageId)
			.ifPresent(messages::add);
		exchange.resultEnvelope().map(RadioEnvelope::messageId).ifPresent(messages::add);
		return List.copyOf(messages);
	}
	private void retireDurablyConsumedRemoteComputationMessages(
		RemoteComputationExchange exchange) {
		int consumed = switch (exchange.phase()) {
			case OFFER_PENDING -> 0;
			case REVISION_COMPUTING, REVISION_PENDING -> 1;
			case ACK_PENDING -> 2;
			case RESULT_COMPUTING, RESULT_PENDING -> 3;
			case COMPLETED -> 4;
		};
		if (consumed >= 1) radioOutbox.markApplied(exchange.offerEnvelope().messageId());
		if (consumed >= 2) exchange.revisionEnvelope().ifPresent(value ->
			radioOutbox.markApplied(value.messageId()));
		if (consumed >= 3) exchange.acknowledgementEnvelope().ifPresent(value ->
			radioOutbox.markApplied(value.messageId()));
		if (consumed >= 4) exchange.resultEnvelope().ifPresent(value ->
			radioOutbox.markApplied(value.messageId()));
	}
	private void waitRemoteComputationSource(RemoteComputationExchange exchange) {
		ClusterEngineState source = runtime.snapshot(exchange.lease().sourceClusterId())
			.orElse(null);
		GoalRecord goal = source == null ? null : source.goals().get(exchange.goalId());
		if (goal != null && (goal.state() != GoalState.WAITING
			|| goal.waitReason() != WaitReason.ROUTE)) {
			// snapshot drains the runtime mailbox: preserve the old waitSource recapture boundary.
			ClusterEngineState current = runtime.snapshot(source.clusterId()).orElse(source);
			runtime.waitGoalFromServerObservation(current.clusterId(), current.epochId(),
				current.generation(), goal.goalId(), WaitReason.ROUTE);
		}
	}
	public void resumeSource(RemoteComputationExchange exchange) {
		ClusterEngineState source = runtime.snapshot(exchange.lease().sourceClusterId())
			.orElse(null);
		GoalRecord goal = source == null ? null : source.goals().get(exchange.goalId());
		if (goal != null && goal.state() == GoalState.WAITING
			&& Set.of(WaitReason.ROUTE, WaitReason.PROCESS, WaitReason.RECOVERY_LEDGER_FULL).contains(goal.waitReason()))
			runtime.resumePanelGoal(source.clusterId(), source.epochId(), source.generation(),
				goal.goalId());
	}
	private void persistRemoteComputation(RemoteComputationExchange exchange) {
		persistRemoteComputationLifecycle(lifecycleWith(exchange, null));
	}
	private void persistRemoteComputation(RemoteComputationExchange exchange,
		RemoteComputationJob job) {
		if (!exchange.runId().equals(job.runId()) || !exchange.lease().equals(job.lease()))
			throw new IllegalArgumentException("remote exchange/job identity mismatch");
		persistRemoteComputationLifecycle(lifecycleWith(exchange, job));
	}
	private void persistRemoteComputation(RemoteComputationExchange exchange,
		FrozenProductionPlan plan) {
		if (!exchange.goalId().equals(plan.goalId()))
			throw new IllegalArgumentException("remote exchange/plan identity mismatch");
		RemoteComputationLifecycleState lifecycle = lifecycleWith(exchange, null);
		validateRemoteComputationLifecycle(lifecycle);
		persistence.commit(Optional.of(lifecycle), Optional.of(plan), Optional.empty(), Optional.empty());
		installRemoteComputationLifecycle(lifecycle);
		evidence.publishPlan(plan);
	}
	private void persistRemoteComputation(RemoteComputationExchange exchange,
		RemoteComputationJob job, FrozenProductionPlan plan) {
		if (!exchange.runId().equals(job.runId()) || !exchange.lease().equals(job.lease())
			|| !exchange.goalId().equals(plan.goalId()))
			throw new IllegalArgumentException("remote exchange/job/plan identity mismatch");
		RemoteComputationLifecycleState lifecycle = lifecycleWith(exchange, job);
		validateRemoteComputationLifecycle(lifecycle);
		persistence.commit(Optional.of(lifecycle), Optional.of(plan), Optional.empty(), Optional.empty());
		installRemoteComputationLifecycle(lifecycle);
		evidence.publishPlan(plan);
	}

	public static Optional<RemoteOfferAcceptance> prepareRemoteOfferAcceptance(
		FactoryRuntimeService runtime, RemoteComputationExchange exchange, RadioEnvelope envelope,
		@Nullable RemoteComputationJob existingJob, @Nullable RadioEndpoint currentReceiver) {
		runtime = Objects.requireNonNull(runtime, "runtime");
		exchange = Objects.requireNonNull(exchange, "exchange");
		envelope = Objects.requireNonNull(envelope, "envelope");
		if (exchange.phase() != RemoteComputationPhase.OFFER_PENDING
			|| !exchange.offerEnvelope().messageId().equals(envelope.messageId())
			|| !(envelope.payload() instanceof ProcessProtocolMessage.LeaseOffer offer)
			|| !offer.lease().equals(exchange.lease())) return Optional.empty();
		RemoteComputationJob job = existingJob;
		if (job == null) {
			if (currentReceiver == null
				|| !currentReceiver.key().equals(envelope.targetHop().key())
				|| currentReceiver.generation() != envelope.targetGeneration()
				|| !currentReceiver.supports(exchange.lease().requiredCapability()))
				return Optional.empty();
			FactoryRuntimeService.RemoteComputationAllocation allocation = runtime
				.acquireRemoteComputationProcess(exchange.lease(), currentReceiver.memberId())
				.orElse(null);
			if (allocation == null) return Optional.empty();
			job = RemoteComputationJob.begin(exchange, allocation);
		}
		if (!job.runId().equals(exchange.runId()) || !job.lease().equals(exchange.lease()))
			throw new IllegalStateException("conflicting receiver job lease");
		return Optional.of(new RemoteOfferAcceptance(exchange.receiverAcceptedOffer(), job));
	}

}
