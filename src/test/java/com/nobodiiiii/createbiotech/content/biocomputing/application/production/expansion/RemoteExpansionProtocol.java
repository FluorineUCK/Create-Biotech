package com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionResult;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRevision;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteProcessLease;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;

/** Durable protocol values retaining historical wire identity. */
public final class RemoteExpansionProtocol {
	private RemoteExpansionProtocol() {}
	public enum RemoteComputationPhase {
		OFFER_PENDING,
		REVISION_COMPUTING,
		REVISION_PENDING,
		ACK_PENDING,
		RESULT_COMPUTING,
		RESULT_PENDING,
		COMPLETED
	}

	public enum RemoteComputationBeginStatus {
		STARTED,
		NO_ROUTE,
		RECOVERY_LEDGER_FULL
	}

	public record RemoteComputationBeginResult(RemoteComputationBeginStatus status,
		Optional<RemoteComputationExchange> exchange) {
		public RemoteComputationBeginResult {
			status = Objects.requireNonNull(status, "status");
			exchange = Objects.requireNonNull(exchange, "exchange");
			if ((status == RemoteComputationBeginStatus.STARTED) != exchange.isPresent())
				throw new IllegalArgumentException("remote begin status/exchange mismatch");
		}
	}

	public static WaitReason remoteComputationBeginWaitReason(RemoteComputationBeginStatus status) {
		return switch (Objects.requireNonNull(status, "status")) {
			case NO_ROUTE -> WaitReason.ROUTE;
			case RECOVERY_LEDGER_FULL -> WaitReason.RECOVERY_LEDGER_FULL;
			case STARTED -> throw new IllegalArgumentException(
				"a started remote computation has no wait reason");
		};
	}


	public enum RemoteComputationJobPhase {
		REVISION_WORK_PENDING,
		REVISION_READY,
		ACK_DURABLE,
		RESULT_READY,
		COMPLETED
	}

	/** Durable receiver-owned allocation and pure-work evidence for one source lease. */
	public record RemoteComputationJob(UUID runId, RemoteProcessLease lease,
		UUID receiverMemberId, int owningSlot, UUID processId,
		RemoteComputationJobPhase phase, Optional<RemoteExpansionRevision> revisionState,
		Optional<RemoteExpansionResult> result) {
		public RemoteComputationJob {
			runId = Objects.requireNonNull(runId, "runId");
			lease = Objects.requireNonNull(lease, "lease");
			receiverMemberId = Objects.requireNonNull(receiverMemberId, "receiverMemberId");
			processId = Objects.requireNonNull(processId, "processId");
			if (owningSlot < 0) throw new IllegalArgumentException("negative receiver process slot");
			phase = Objects.requireNonNull(phase, "phase");
			revisionState = Objects.requireNonNull(revisionState, "revisionState");
			result = Objects.requireNonNull(result, "result");
			boolean needsRevision = phase != RemoteComputationJobPhase.REVISION_WORK_PENDING;
			boolean needsResult = phase == RemoteComputationJobPhase.RESULT_READY
				|| phase == RemoteComputationJobPhase.COMPLETED;
			if (revisionState.isPresent() != needsRevision || result.isPresent() != needsResult)
				throw new IllegalArgumentException("remote receiver job phase/evidence mismatch");
		}

		private static RemoteComputationJob begin(RemoteComputationExchange exchange,
			UUID receiverMemberId, int owningSlot, UUID processId) {
			return new RemoteComputationJob(exchange.runId(), exchange.lease(), receiverMemberId,
				owningSlot, processId, RemoteComputationJobPhase.REVISION_WORK_PENDING,
				Optional.empty(), Optional.empty());
		}

		public static RemoteComputationJob begin(RemoteComputationExchange exchange,
			FactoryRuntimeService.RemoteComputationAllocation allocation) {
			Objects.requireNonNull(allocation, "allocation");
			return begin(Objects.requireNonNull(exchange, "exchange"),
				allocation.receiverMemberId(), allocation.owningSlot(), allocation.processId());
		}

		RemoteComputationJob reassigned(
			FactoryRuntimeService.RemoteComputationAllocation allocation) {
			Objects.requireNonNull(allocation, "allocation");
			if (!allocation.receiverMemberId().equals(receiverMemberId))
				throw new IllegalArgumentException("remote receiver reassignment changed member");
			return new RemoteComputationJob(runId, lease, receiverMemberId,
				allocation.owningSlot(), allocation.processId(), phase, revisionState, result);
		}

		RemoteComputationJob withRevision(RemoteExpansionRevision value) {
			if (phase != RemoteComputationJobPhase.REVISION_WORK_PENDING)
				throw new IllegalStateException("receiver revision work is not pending");
			if (!Objects.requireNonNull(value).matches(lease.expansionRequest()))
				throw new IllegalArgumentException("receiver revision does not match lease input");
			return new RemoteComputationJob(runId, lease, receiverMemberId, owningSlot,
				processId, RemoteComputationJobPhase.REVISION_READY,
				Optional.of(Objects.requireNonNull(value)), Optional.empty());
		}

		RemoteComputationJob acknowledgementDurable() {
			if (phase != RemoteComputationJobPhase.REVISION_READY)
				throw new IllegalStateException("receiver revision is not awaiting acknowledgement");
			return new RemoteComputationJob(runId, lease, receiverMemberId, owningSlot,
				processId, RemoteComputationJobPhase.ACK_DURABLE, revisionState,
				Optional.empty());
		}

		RemoteComputationJob withResult(RemoteExpansionResult value) {
			if (phase != RemoteComputationJobPhase.ACK_DURABLE)
				throw new IllegalStateException("receiver result work is not authorized");
			if (!Objects.requireNonNull(value).matches(lease.expansionRequest(),
				revisionState.orElseThrow()))
				throw new IllegalArgumentException("receiver result does not match acknowledged revision");
			return new RemoteComputationJob(runId, lease, receiverMemberId, owningSlot,
				processId, RemoteComputationJobPhase.RESULT_READY, revisionState,
				Optional.of(Objects.requireNonNull(value)));
		}

		RemoteComputationJob completed() {
			if (phase != RemoteComputationJobPhase.RESULT_READY)
				throw new IllegalStateException("receiver result is not ready");
			return new RemoteComputationJob(runId, lease, receiverMemberId, owningSlot,
				processId, RemoteComputationJobPhase.COMPLETED, revisionState, result);
		}
	}

	/** Exact receiver allocation proposal for one delivered OFFER business application. */
	public record RemoteOfferAcceptance(RemoteComputationExchange exchange,
		RemoteComputationJob job) {
		public RemoteOfferAcceptance {
			exchange = Objects.requireNonNull(exchange, "exchange");
			job = Objects.requireNonNull(job, "job");
			if (exchange.phase() != RemoteComputationPhase.REVISION_COMPUTING
				|| !exchange.runId().equals(job.runId()) || !exchange.lease().equals(job.lease()))
				throw new IllegalArgumentException("remote OFFER acceptance fences disagree");
		}
	}

	/**
	 * Allocates before the caller persists the receiver job. No slot leaves the immutable delivered
	 * OFFER retryable; an existing exact job makes crash/reload replay idempotent.
	 */


	public enum RemoteComputationTerminalOutcome { COMPLETED, CANCELLED, MIGRATED }

	/** One bounded high-watermark replaces all terminal serial Runs of one logical child. */
	public record RetiredRemoteComputation(UUID childProcessId, UUID sourceGoalId, UUID runId,
		UUID leaseId, long revisionFence, RemoteComputationTerminalOutcome outcome,
		String requestFingerprint, Optional<String> resultFingerprint) {
		public RetiredRemoteComputation {
			childProcessId = Objects.requireNonNull(childProcessId, "childProcessId");
			sourceGoalId = Objects.requireNonNull(sourceGoalId, "sourceGoalId");
			runId = Objects.requireNonNull(runId, "runId");
			leaseId = Objects.requireNonNull(leaseId, "leaseId");
			outcome = Objects.requireNonNull(outcome, "outcome");
			if (revisionFence < 0) throw new IllegalArgumentException("negative retired computation fence");
			requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
			resultFingerprint = Objects.requireNonNull(resultFingerprint, "resultFingerprint");
			if (requestFingerprint.isBlank() || resultFingerprint.filter(String::isBlank).isPresent()
				|| (outcome == RemoteComputationTerminalOutcome.COMPLETED)
					!= resultFingerprint.isPresent())
				throw new IllegalArgumentException("invalid retired computation fingerprints");
		}
	}

	/** Copy-on-write singleton: active exchanges/jobs plus bounded logical tombstones. */
	public record RemoteComputationLifecycleState(
		Map<UUID, RemoteComputationExchange> exchanges,
		Map<UUID, RemoteComputationJob> jobs,
		Map<UUID, RetiredRemoteComputation> retiredChildren) {
		public RemoteComputationLifecycleState {
			exchanges = Map.copyOf(Objects.requireNonNull(exchanges, "exchanges"));
			jobs = Map.copyOf(Objects.requireNonNull(jobs, "jobs"));
			retiredChildren = Map.copyOf(Objects.requireNonNull(retiredChildren,
				"retiredChildren"));
			Map<UUID, RemoteComputationExchange> durableExchanges = exchanges;
			exchanges.forEach((runId, exchange) -> {
				if (!runId.equals(exchange.runId()))
					throw new IllegalArgumentException("remote exchange lifecycle key mismatch");
			});
			jobs.forEach((runId, job) -> {
				RemoteComputationExchange exchange = durableExchanges.get(runId);
				if (!runId.equals(job.runId()) || exchange == null
					|| !exchange.lease().equals(job.lease()))
					throw new IllegalArgumentException("remote job has no active source exchange");
			});
			retiredChildren.forEach((childId, retired) -> {
				if (!childId.equals(retired.childProcessId()))
					throw new IllegalArgumentException("retired child lifecycle key mismatch");
			});
		}
	}

	/**
	 * Durable source-owned lifecycle for one pure expansion lease. Each transition is published
	 * before the delivered envelope is retired, so reload can replay only the current phase.
	 */
	public record RemoteComputationExchange(UUID goalId, UUID runId,
		RemoteProcessLease lease, long revision, RadioEnvelope offerEnvelope,
		Optional<RadioEnvelope> revisionEnvelope,
		Optional<RadioEnvelope> acknowledgementEnvelope,
		Optional<RadioEnvelope> resultEnvelope, List<RadioEndpoint.Hop> forwardRelays,
		List<RadioEndpoint.Hop> reverseRelays, RemoteComputationPhase phase) {
		public RemoteComputationExchange {
			goalId = Objects.requireNonNull(goalId, "goalId");
			runId = Objects.requireNonNull(runId, "runId");
			lease = Objects.requireNonNull(lease, "lease");
			offerEnvelope = Objects.requireNonNull(offerEnvelope, "offerEnvelope");
			revisionEnvelope = Objects.requireNonNull(revisionEnvelope, "revisionEnvelope");
			acknowledgementEnvelope = Objects.requireNonNull(acknowledgementEnvelope,
				"acknowledgementEnvelope");
			resultEnvelope = Objects.requireNonNull(resultEnvelope, "resultEnvelope");
			RemoteProcessLease exactLease = lease;
			forwardRelays = List.copyOf(Objects.requireNonNull(forwardRelays, "forwardRelays"));
			reverseRelays = List.copyOf(Objects.requireNonNull(reverseRelays, "reverseRelays"));
			phase = Objects.requireNonNull(phase, "phase");
			if (revision < lease.revisionFence() || !goalId.equals(lease.sourceGoalId()))
				throw new IllegalArgumentException("remote computation fence mismatch");
			if (!(offerEnvelope.payload() instanceof ProcessProtocolMessage.LeaseOffer offer)
				|| !offer.lease().equals(lease))
				throw new IllegalArgumentException("remote computation protocol payload mismatch");
			validateForward(offerEnvelope, lease);
			revisionEnvelope.ifPresent(envelope -> {
				if (!(envelope.payload() instanceof ProcessProtocolMessage.LeaseRevision revised)
					|| !revised.leaseId().equals(exactLease.leaseId()) || revised.revision() != revision
					|| !revised.calculationState().matches(exactLease.expansionRequest()))
					throw new IllegalArgumentException("remote revision payload mismatch");
				validateReverse(envelope, exactLease);
			});
			acknowledgementEnvelope.ifPresent(envelope -> {
				if (!(envelope.payload() instanceof ProcessProtocolMessage.RevisionAcknowledgement ack)
					|| !ack.leaseId().equals(exactLease.leaseId()) || ack.revision() != revision)
					throw new IllegalArgumentException("remote acknowledgement payload mismatch");
				validateForward(envelope, exactLease);
			});
			if (resultEnvelope.isPresent()) {
				RadioEnvelope envelope = resultEnvelope.orElseThrow();
				if (!(envelope.payload() instanceof ProcessProtocolMessage.LeaseResult result)
					|| !result.leaseId().equals(exactLease.leaseId()) || result.revision() != revision
					|| revisionEnvelope.isEmpty()
					|| !(revisionEnvelope.orElseThrow().payload()
						instanceof ProcessProtocolMessage.LeaseRevision accepted)
					|| !result.result().matches(exactLease.expansionRequest(),
						accepted.calculationState()))
					throw new IllegalArgumentException("remote result payload mismatch");
				validateReverse(envelope, exactLease);
			}
			boolean hasRevision = revisionEnvelope.isPresent();
			boolean hasAck = acknowledgementEnvelope.isPresent();
			boolean hasResult = resultEnvelope.isPresent();
			boolean validShape = switch (phase) {
				case OFFER_PENDING, REVISION_COMPUTING -> !hasRevision && !hasAck && !hasResult;
				case REVISION_PENDING -> hasRevision && !hasAck && !hasResult;
				case ACK_PENDING, RESULT_COMPUTING -> hasRevision && hasAck && !hasResult;
				case RESULT_PENDING, COMPLETED -> hasRevision && hasAck && hasResult;
			};
			if (!validShape) throw new IllegalArgumentException(
				"remote computation phase/envelope mismatch");
		}

		public static RemoteComputationExchange begin(net.minecraft.core.HolderLookup.Provider registries,
			FrozenProductionPlan plan, ProductionRun run,
			ProcessRecord sourceProcess, long parentStateGeneration,
			RadioEndpoint source, RadioEndpoint receiver, List<RadioEndpoint.Hop> forwardRelays,
			List<RadioEndpoint.Hop> reverseRelays) {
			plan = Objects.requireNonNull(plan, "plan");
			run = Objects.requireNonNull(run, "run");
			sourceProcess = Objects.requireNonNull(sourceProcess, "sourceProcess");
			source = Objects.requireNonNull(source, "source");
			receiver = Objects.requireNonNull(receiver, "receiver");
			if (!plan.goalId().equals(run.goalId()) || !plan.patternId().equals(run.patternId())
				|| !sourceProcess.goalId().equals(run.goalId()) || parentStateGeneration < 0)
				throw new IllegalArgumentException("remote computation source lineage mismatch");
			if (source.clusterId().equals(receiver.clusterId())
				|| !source.frequency().configured()
				|| !source.frequency().equals(receiver.frequency())
				|| !receiver.supports(ProductionCapability.ALCHEMIST))
				throw new IllegalArgumentException("remote computation endpoints are not eligible");
			RemoteExpansionRequest request = PureExpansionCapture.capture(registries, plan, run,
				sourceProcess, parentStateGeneration);
			long revision = request.parentStateGeneration();
			UUID leaseId = stableRemoteComputationId("lease", run.runId(), source, receiver);
			RemoteProcessLease lease = new RemoteProcessLease(leaseId, run.goalId(),
				sourceProcess.processId(), source.clusterId(), receiver.clusterId(), source.generation(),
				receiver.generation(), ProductionCapability.ALCHEMIST,
				request, revision);
			RadioEnvelope offer = RadioEnvelope.create(stableRemoteComputationId("offer",
				run.runId(), source, receiver), source, receiver, source.frequency(),
				new ProcessProtocolMessage.LeaseOffer(lease));
			return new RemoteComputationExchange(run.goalId(), run.runId(), lease, revision,
				offer, Optional.empty(), Optional.empty(), Optional.empty(),
				forwardRelays, reverseRelays,
				RemoteComputationPhase.OFFER_PENDING);
		}

		public boolean materialDispatchAllowed() {
			return phase == RemoteComputationPhase.COMPLETED;
		}

		public Optional<RadioEnvelope> activeEnvelope() {
			return switch (phase) {
				case OFFER_PENDING -> Optional.of(offerEnvelope);
				case REVISION_PENDING -> revisionEnvelope;
				case ACK_PENDING -> acknowledgementEnvelope;
				case RESULT_PENDING -> resultEnvelope;
				case REVISION_COMPUTING, RESULT_COMPUTING, COMPLETED -> Optional.empty();
			};
		}

		public List<RadioEndpoint.Hop> activeRelays() {
			return switch (phase) {
				case OFFER_PENDING, ACK_PENDING -> forwardRelays;
				case REVISION_PENDING, RESULT_PENDING -> reverseRelays;
				case REVISION_COMPUTING, RESULT_COMPUTING, COMPLETED -> List.of();
			};
		}

		public boolean ownsMessage(UUID messageId) {
			messageId = Objects.requireNonNull(messageId, "messageId");
			return offerEnvelope.messageId().equals(messageId)
				|| revisionEnvelope.map(RadioEnvelope::messageId).filter(messageId::equals).isPresent()
				|| acknowledgementEnvelope.map(RadioEnvelope::messageId)
					.filter(messageId::equals).isPresent()
				|| resultEnvelope.map(RadioEnvelope::messageId).filter(messageId::equals).isPresent();
		}

		RemoteComputationExchange receiverAcceptedOffer() {
			if (phase != RemoteComputationPhase.OFFER_PENDING)
				throw new IllegalStateException("lease offer is not pending");
			return new RemoteComputationExchange(goalId, runId, lease, revision,
				offerEnvelope, Optional.empty(), Optional.empty(), Optional.empty(),
				forwardRelays, reverseRelays, RemoteComputationPhase.REVISION_COMPUTING);
		}

		RemoteComputationExchange withReceiverRevision(RemoteComputationJob job) {
			if (phase != RemoteComputationPhase.REVISION_COMPUTING || !job.lease().equals(lease)
				|| job.phase() != RemoteComputationJobPhase.REVISION_READY)
				throw new IllegalStateException("receiver revision is not ready");
			ProcessProtocolMessage payload = new ProcessProtocolMessage.LeaseRevision(
				lease.leaseId(), revision, job.revisionState().orElseThrow());
			return new RemoteComputationExchange(goalId, runId, lease, revision,
				offerEnvelope, Optional.of(reverseEnvelope("revision", payload)), Optional.empty(),
				Optional.empty(), forwardRelays, reverseRelays,
				RemoteComputationPhase.REVISION_PENDING);
		}

		RemoteComputationExchange sourceAcceptedRevision() {
			if (phase != RemoteComputationPhase.REVISION_PENDING)
				throw new IllegalStateException("remote revision is not pending");
			ProcessProtocolMessage payload = new ProcessProtocolMessage.RevisionAcknowledgement(
				lease.leaseId(), revision);
			return new RemoteComputationExchange(goalId, runId, lease, revision,
				offerEnvelope, revisionEnvelope, Optional.of(forwardEnvelope("ack", payload)),
				Optional.empty(), forwardRelays, reverseRelays,
				RemoteComputationPhase.ACK_PENDING);
		}

		RemoteComputationExchange receiverAcceptedAcknowledgement() {
			if (phase != RemoteComputationPhase.ACK_PENDING)
				throw new IllegalStateException("remote acknowledgement is not pending");
			return new RemoteComputationExchange(goalId, runId, lease, revision,
				offerEnvelope, revisionEnvelope, acknowledgementEnvelope, Optional.empty(),
				forwardRelays, reverseRelays, RemoteComputationPhase.RESULT_COMPUTING);
		}

		RemoteComputationExchange withReceiverResult(RemoteComputationJob job) {
			if (phase != RemoteComputationPhase.RESULT_COMPUTING || !job.lease().equals(lease)
				|| job.phase() != RemoteComputationJobPhase.RESULT_READY)
				throw new IllegalStateException("receiver result is not ready");
			ProcessProtocolMessage payload = new ProcessProtocolMessage.LeaseResult(
				lease.leaseId(), revision, job.result().orElseThrow());
			return new RemoteComputationExchange(goalId, runId, lease, revision,
				offerEnvelope, revisionEnvelope, acknowledgementEnvelope,
				Optional.of(reverseEnvelope("result", payload)), forwardRelays, reverseRelays,
				RemoteComputationPhase.RESULT_PENDING);
		}

		RemoteComputationExchange sourceAcceptedResult() {
			if (phase != RemoteComputationPhase.RESULT_PENDING)
				throw new IllegalStateException("remote result is not pending");
			return new RemoteComputationExchange(goalId, runId, lease, revision,
				offerEnvelope, revisionEnvelope, acknowledgementEnvelope, resultEnvelope,
				forwardRelays, reverseRelays, RemoteComputationPhase.COMPLETED);
		}

		private RadioEnvelope forwardEnvelope(String kind, ProcessProtocolMessage payload) {
			return derivedEnvelope(kind, payload, false);
		}

		private RadioEnvelope reverseEnvelope(String kind, ProcessProtocolMessage payload) {
			return derivedEnvelope(kind, payload, true);
		}

		private RadioEnvelope derivedEnvelope(String kind, ProcessProtocolMessage payload,
			boolean reverse) {
			UUID messageId = stableRemoteComputationId(kind, runId, offerEnvelope);
			return reverse
				? new RadioEnvelope(messageId, offerEnvelope.targetClusterId(),
					offerEnvelope.targetMemberId(), offerEnvelope.sourceClusterId(),
					offerEnvelope.sourceMemberId(), offerEnvelope.frequency(), 0,
					offerEnvelope.targetGeneration(), offerEnvelope.sourceGeneration(),
					payload.fingerprint(), payload)
				: new RadioEnvelope(messageId, offerEnvelope.sourceClusterId(),
					offerEnvelope.sourceMemberId(), offerEnvelope.targetClusterId(),
					offerEnvelope.targetMemberId(), offerEnvelope.frequency(), 0,
					offerEnvelope.sourceGeneration(), offerEnvelope.targetGeneration(),
					payload.fingerprint(), payload);
		}

		private static void validateForward(RadioEnvelope envelope, RemoteProcessLease lease) {
			if (!envelope.sourceClusterId().equals(lease.sourceClusterId())
				|| !envelope.targetClusterId().equals(lease.receiverClusterId())
				|| envelope.sourceGeneration() != lease.sourceGeneration()
				|| envelope.targetGeneration() != lease.receiverGeneration())
				throw new IllegalArgumentException("remote computation forward route mismatch");
		}

		private static void validateReverse(RadioEnvelope envelope, RemoteProcessLease lease) {
			if (!envelope.sourceClusterId().equals(lease.receiverClusterId())
				|| !envelope.targetClusterId().equals(lease.sourceClusterId())
				|| envelope.sourceGeneration() != lease.receiverGeneration()
				|| envelope.targetGeneration() != lease.sourceGeneration())
				throw new IllegalArgumentException("remote computation reverse route mismatch");
		}
	}



	private static UUID stableRemoteComputationId(String kind, UUID runId,
		RadioEnvelope offer) {
		return UUID.nameUUIDFromBytes(("remote-computation/" + kind + "/" + runId + "/"
			+ offer.sourceClusterId() + "/" + offer.sourceMemberId() + "/"
			+ offer.sourceGeneration() + "/" + offer.targetClusterId() + "/"
			+ offer.targetMemberId() + "/" + offer.targetGeneration())
			.getBytes(StandardCharsets.UTF_8));
	}

	private static UUID stableRemoteComputationId(String kind, UUID runId,
		RadioEndpoint source, RadioEndpoint receiver) {
		return UUID.nameUUIDFromBytes(("remote-computation/" + kind + "/" + runId + "/"
			+ source.key() + "/" + source.generation() + "/" + receiver.key() + "/"
			+ receiver.generation()).getBytes(StandardCharsets.UTF_8));
	}


}
