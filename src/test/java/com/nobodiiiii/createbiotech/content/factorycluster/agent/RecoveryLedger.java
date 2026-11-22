package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.AgentProtocolMessage;

/**
 * Bounded durable target ledger for remote Agent work. Unacknowledged records never expire or
 * disappear under pressure; capacity pressure rejects only new offers.
 */
public final class RecoveryLedger {
	private final int capacity;
	private final StateStore store;
	private final LinkedHashMap<RemoteRequestKey, RemoteAgentRecord> records =
		new LinkedHashMap<>();
	private final LinkedHashMap<RemoteRequestKey, AcknowledgedTombstone> acknowledged =
		new LinkedHashMap<>();
	private final LinkedHashMap<RemoteRequestKey, PendingEffect> pendingEffects =
		new LinkedHashMap<>();
	private final LinkedHashMap<LineageId, LineageHighWatermark> lineages =
		new LinkedHashMap<>();
	private final HashSet<LineageId> liveLineages = new HashSet<>();

	public RecoveryLedger(FactoryRuntimeConfig config, StateStore store) {
		this.capacity = Objects.requireNonNull(config, "config").recoveryLedgerCapacity();
		this.store = Objects.requireNonNull(store, "store");
		store.load().ifPresent(this::restore);
		// Construction after loading is the crash/reload boundary; never expose a bare intent.
		recoverAfterReload();
	}

	public OfferResult offer(AgentProtocolMessage.Offer offer, AgentPendingRunQueue queue,
		AgentAddressRegistration registration) {
		return offerWithinCapacity(offer, queue, registration, capacity);
	}

	/** Applies a narrower correctness-only admission domain without evicting global evidence. */
	public OfferResult offerWithinCapacity(AgentProtocolMessage.Offer offer,
		AgentPendingRunQueue queue, AgentAddressRegistration registration, int admissionCapacity) {
		offer = Objects.requireNonNull(offer, "offer");
		queue = Objects.requireNonNull(queue, "queue");
		registration = Objects.requireNonNull(registration, "registration");
		if (admissionCapacity <= 0 || admissionCapacity > capacity)
			throw new IllegalArgumentException("invalid recovery admission capacity");
		RemoteRequestKey key = offer.key();
		RemoteAgentRecord exact = records.get(key);
		if (exact != null) {
			if (!exact.ticket().equals(offer.ticket()))
				throw new IllegalStateException("Conflicting duplicate remote offer");
			return new OfferResult(OfferDisposition.DUPLICATE, exact, Optional.empty());
		}
		Optional<LineageHighWatermark> lineage = knownLineage(key);
		if (lineage.isPresent()) {
			LineageHighWatermark highWatermark = lineage.orElseThrow();
			RemoteRequestKey known = highWatermark.key();
			if (!highWatermark.id().equals(LineageId.from(key))
				|| !highWatermark.sourceClusterId().equals(offer.ticket().sourceClusterId())
				|| !highWatermark.agentId().equals(offer.ticket().agentId())
				|| !highWatermark.targetRoute().equals(offer.ticket().targetRoute()))
				return rejected(OfferDisposition.LINEAGE_MUTATION_REJECTED);
			if (highWatermark.key().equals(key) && highWatermark.terminalAcknowledged())
				return new OfferResult(OfferDisposition.ACKNOWLEDGED_DUPLICATE, null,
					Optional.empty());
			if (key.fence() != known.fence())
				return rejected(OfferDisposition.STALE_FENCE);
			if (key.sourceGeneration() != known.sourceGeneration())
				return rejected(OfferDisposition.STALE_SOURCE_GENERATION);
			if (key.agentGeneration() != known.agentGeneration())
				return rejected(OfferDisposition.STALE_AGENT_GENERATION);
		}
		if (!registration.agentId().equals(offer.ticket().agentId())
			|| !registration.route().equals(offer.ticket().targetRoute())
			|| registration.agentGeneration() != key.agentGeneration()
			|| registration.loadState() != AgentAddressRegistration.LoadState.LOADED
			|| registration.reachability() != AgentAddressRegistration.Reachability.REACHABLE)
			return rejected(OfferDisposition.STALE_AGENT_GENERATION);
		if (correctnessOccupancy() >= admissionCapacity)
			return new OfferResult(OfferDisposition.RECOVERY_LEDGER_FULL, null,
				Optional.of(WaitReason.RECOVERY_LEDGER_FULL));

		RemoteAgentRecord record = RemoteAgentRecord.queued(key, registration.ownerClusterId(),
			offer.ticket());
		LinkedHashMap<RemoteRequestKey, RemoteAgentRecord> nextRecords =
			new LinkedHashMap<>(records);
		nextRecords.put(key, record);
		LinkedHashMap<RemoteRequestKey, PendingEffect> nextEffects =
			new LinkedHashMap<>(pendingEffects);
		nextEffects.put(key, PendingEffect.of(key, EffectKind.ENQUEUE));
		LinkedHashMap<LineageId, LineageHighWatermark> nextLineages =
			new LinkedHashMap<>(lineages);
		nextLineages.put(LineageId.from(key), LineageHighWatermark.from(key, offer.ticket()));
		commit(proposed(nextRecords, acknowledged, nextEffects, nextLineages));

		AgentPendingRunQueue.EnqueueResult enqueue = queue.enqueue(offer.ticket(), registration);
		if (enqueue == AgentPendingRunQueue.EnqueueResult.DUPLICATE
			&& queue.find(key.requestId()).filter(offer.ticket()::equals).isPresent())
			enqueue = AgentPendingRunQueue.EnqueueResult.ACCEPTED;
		if (enqueue != AgentPendingRunQueue.EnqueueResult.ACCEPTED) {
			LinkedHashMap<RemoteRequestKey, RemoteAgentRecord> rollbackRecords =
				new LinkedHashMap<>(records);
			rollbackRecords.remove(key);
			LinkedHashMap<RemoteRequestKey, PendingEffect> rollbackEffects =
				new LinkedHashMap<>(pendingEffects);
			rollbackEffects.remove(key);
			LinkedHashMap<LineageId, LineageHighWatermark> rollbackLineages =
				new LinkedHashMap<>(lineages);
			rollbackLineages.remove(LineageId.from(key));
			commit(proposed(rollbackRecords, acknowledged, rollbackEffects, rollbackLineages));
			if (enqueue == AgentPendingRunQueue.EnqueueResult.CAPACITY_FULL
				|| enqueue == AgentPendingRunQueue.EnqueueResult.PRIORITY_LEASE_UNAVAILABLE)
				return new OfferResult(OfferDisposition.QUEUE_REJECTED, null,
					Optional.of(WaitReason.PROCESS));
			if (enqueue == AgentPendingRunQueue.EnqueueResult.AGENT_OWNER_MISMATCH)
				return rejected(OfferDisposition.STALE_AGENT_GENERATION);
			if (enqueue == AgentPendingRunQueue.EnqueueResult.DUPLICATE)
				return new OfferResult(OfferDisposition.REMOTE_RECOVERY, null,
					Optional.of(WaitReason.REMOTE_RECOVERY));
			throw new IllegalStateException("Queue and recovery ledger disagree: " + enqueue);
		}
		clearEffect(key);
		return new OfferResult(OfferDisposition.ACCEPTED, record, Optional.empty());
	}

	public boolean reserve(RemoteRequestKey key, AgentPendingRunQueue queue, AgentProcess process) {
		RemoteRequestKey exactKey = Objects.requireNonNull(key, "key");
		queue = Objects.requireNonNull(queue, "queue");
		process = Objects.requireNonNull(process, "process");
		RemoteAgentRecord record = records.get(exactKey);
		if (record == null) return false;
		if (record.phase() == RemoteAgentPhase.RESERVED)
			return process.activeContract().filter(exactKey.requestId()::equals).isPresent();
		if (record.phase() != RemoteAgentPhase.QUEUED
			|| !process.formation().agentId().equals(record.ticket().agentId())
			|| !process.formation().ownerClusterId().equals(record.targetClusterId())
			|| process.formation().agentGeneration() != exactKey.agentGeneration()) return false;
		commitRecordAndEffect(record, PendingEffect.of(exactKey, EffectKind.RESERVE));
		boolean alreadyReserved = process.activeContractState().filter(active ->
			active.contractId().equals(exactKey.requestId())
				&& active.fence() == exactKey.fence()).isPresent();
		if (!alreadyReserved
			&& !process.startContract(exactKey.requestId(), exactKey.fence())) {
			clearEffect(exactKey);
			return false;
		}
		if (!queue.cancel(exactKey.requestId()) && !alreadyReserved) {
			process.finishContract(exactKey.requestId());
			return false;
		}
		RemoteAgentRecord reserved = record.reserve();
		LinkedHashMap<RemoteRequestKey, RemoteAgentRecord> nextRecords =
			new LinkedHashMap<>(records);
		nextRecords.put(exactKey, reserved);
		LinkedHashMap<RemoteRequestKey, PendingEffect> nextEffects =
			new LinkedHashMap<>(pendingEffects);
		nextEffects.remove(exactKey);
		commit(proposed(nextRecords, acknowledged, nextEffects, lineages));
		return true;
	}

	/** Durable compensation for an admission that never crossed DISPATCH_INTENT. */
	public boolean rollbackAdmission(RemoteRequestKey key, AgentPendingRunQueue queue,
		AgentProcess process) {
		key = Objects.requireNonNull(key, "key");
		queue = Objects.requireNonNull(queue, "queue");
		process = Objects.requireNonNull(process, "process");
		RemoteAgentRecord record = records.get(key);
		if (record == null || record.crossedDispatchIntent()
			|| record.phase() != RemoteAgentPhase.QUEUED
				&& record.phase() != RemoteAgentPhase.RESERVED
			|| !processMatches(record, key, process)) return false;
		commitRecordAndEffect(record, PendingEffect.of(key, EffectKind.ROLLBACK_ADMISSION));
		return finishRollbackAdmission(key, record, queue, process);
	}

	private boolean finishRollbackAdmission(RemoteRequestKey key, RemoteAgentRecord record,
		AgentPendingRunQueue queue, AgentProcess process) {
		if (record.phase() == RemoteAgentPhase.QUEUED) {
			Optional<ProductionRequestTicket> queued = queue.find(key.requestId());
			if (queued.isPresent() && (!queued.filter(record.ticket()::equals).isPresent()
				|| !queue.cancel(key.requestId()))) return false;
		} else if (!finishExact(process, key)) return false;
		LinkedHashMap<RemoteRequestKey, RemoteAgentRecord> nextRecords =
			new LinkedHashMap<>(records);
		nextRecords.remove(key);
		LinkedHashMap<RemoteRequestKey, PendingEffect> nextEffects =
			new LinkedHashMap<>(pendingEffects);
		nextEffects.remove(key);
		LinkedHashMap<LineageId, LineageHighWatermark> nextLineages =
			new LinkedHashMap<>(lineages);
		nextLineages.remove(LineageId.from(key));
		commit(proposed(nextRecords, acknowledged, nextEffects, nextLineages));
		return true;
	}

	/** Advances only from the durable input contract evidence, never from message arrival order. */
	public boolean recordInput(RemoteRequestKey key, AgentInputContract.State inputState) {
		key = Objects.requireNonNull(key, "key");
		inputState = Objects.requireNonNull(inputState, "inputState");
		RemoteAgentRecord record = records.get(key);
		if (record == null) return false;
		if (record.inputState().filter(inputState::equals).isPresent()) return true;
		RemoteAgentRecord updated = record.withInput(inputState);
		if (updated == record) return false;
		commitRecord(updated);
		return true;
	}

	/** Records only the output contract's durable milestone and never infers a Create action. */
	public boolean recordOutput(RemoteRequestKey key, ProductionOutputContract.Snapshot outputState) {
		key = Objects.requireNonNull(key, "key");
		outputState = Objects.requireNonNull(outputState, "outputState");
		RemoteAgentRecord record = records.get(key);
		if (record == null) return false;
		if (record.outputState().filter(outputState::equals).isPresent()) return true;
		RemoteAgentRecord updated = record.withOutput(outputState);
		if (updated == record) return false;
		commitRecord(updated);
		return true;
	}

	/** OUTPUT_DISPATCHED may release local production resources without waiting for source ACK. */
	public boolean releaseCompleted(RemoteRequestKey key,
		ProductionOutputContract.Snapshot outputState, ProductionOutputLedger outputLedger,
		AgentProcess process) {
		key = Objects.requireNonNull(key, "key");
		outputState = Objects.requireNonNull(outputState, "outputState");
		outputLedger = Objects.requireNonNull(outputLedger, "outputLedger");
		process = Objects.requireNonNull(process, "process");
		RemoteAgentRecord record = records.get(key);
		if (record != null && record.isTerminal()) {
			PendingEffect pending = pendingEffects.get(key);
			if (record.phase() != RemoteAgentPhase.RELEASED
				|| record.condition() != RemoteAgentRecord.RecoveryCondition.NORMAL
				|| record.terminalOutcome().filter(
					RemoteAgentRecord.TerminalOutcome.COMPLETED::equals).isEmpty()
				|| record.outputState().filter(outputState::equals).isEmpty()
				|| record.outputLedger().filter(outputLedger::equals).isEmpty()
				|| !processMatches(record, key, process)
				|| pending != null && pending.kind() != EffectKind.RELEASE_PROCESS
				|| !finishExact(process, key)) return false;
			if (pending != null) clearEffect(key);
			return true;
		}
		if (record == null || record.condition() != RemoteAgentRecord.RecoveryCondition.NORMAL
			|| record.phase() != RemoteAgentPhase.OUTPUT_DRAIN
			|| record.outputState().filter(outputState::equals).isEmpty()
			|| (outputState.milestone() != ProductionOutputContract.Milestone.OUTPUT_DISPATCHED
				&& outputState.milestone() != ProductionOutputContract.Milestone.OUTPUT_RECEIVED)
			|| !outputState.agentReleased()
			|| !process.formation().agentId().equals(record.ticket().agentId())
			|| !process.formation().ownerClusterId().equals(record.targetClusterId())
			|| process.formation().agentGeneration() != key.agentGeneration()) return false;
		RemoteAgentRecord terminal = record.terminal(RemoteAgentPhase.RELEASED,
			RemoteAgentRecord.RecoveryCondition.NORMAL,
			RemoteAgentRecord.TerminalOutcome.COMPLETED, record.inputState(),
			Optional.of(outputState), Optional.of(outputLedger));
		commitRecordAndEffect(terminal, PendingEffect.of(key, EffectKind.RELEASE_PROCESS));
		if (!finishExact(process, key)) return false;
		clearEffect(key);
		return true;
	}

	/** Reload reconciliation turns only a bare durable intent window into uncertainty. */
	public int recoverAfterReload() {
		int changed = 0;
		LinkedHashMap<RemoteRequestKey, RemoteAgentRecord> recoveredRecords =
			new LinkedHashMap<>(records);
		for (Map.Entry<RemoteRequestKey, RemoteAgentRecord> entry : records.entrySet()) {
			RemoteAgentRecord recovered = entry.getValue().uncertainAfterReload();
			if (recovered != entry.getValue()) {
				recoveredRecords.put(entry.getKey(), recovered);
				changed++;
			}
		}
		if (changed > 0)
			commit(proposed(recoveredRecords, acknowledged, pendingEffects, lineages));
		return changed;
	}

	public ReconcileResult reconcile(RemoteRequestKey key) {
		RemoteAgentRecord record = records.get(Objects.requireNonNull(key, "key"));
		if (record == null)
			return new ReconcileResult(ReconcileAction.REMOTE_RECOVERY, null, false,
				Optional.of(WaitReason.REMOTE_RECOVERY));
		ReconcileAction action = record.crossedDispatchIntent()
			? ReconcileAction.RESEND_EVIDENCE : ReconcileAction.RESUME_PRE_INTENT;
		return new ReconcileResult(action, record, !record.crossedDispatchIntent(),
			Optional.empty());
	}

	/** Rebuilds or finishes pre-recorded local effects from a fresh queue/process runtime. */
	public ResourceReconcileResult reconcileResources(RemoteRequestKey key,
		AgentPendingRunQueue queue, AgentAddressRegistration registration,
		AgentProcess process) {
		key = Objects.requireNonNull(key, "key");
		queue = Objects.requireNonNull(queue, "queue");
		registration = Objects.requireNonNull(registration, "registration");
		process = Objects.requireNonNull(process, "process");
		RemoteAgentRecord record = records.get(key);
		PendingEffect effect = pendingEffects.get(key);
		if (record != null && effect != null && effect.kind() == EffectKind.SUPERSEDE) {
			RemoteRequestKey newKey = effect.supersedingKey().orElseThrow();
			RemoteAgentRecord next = records.get(newKey);
			if (next == null || !registration.agentId().equals(next.ticket().agentId())
				|| !registration.route().equals(next.ticket().targetRoute())
				|| registration.agentGeneration() != newKey.agentGeneration()
				|| !processMatches(record, key, process))
				return ResourceReconcileResult.RESOURCE_MISMATCH;
			Optional<ProductionRequestTicket> queued = queue.find(key.requestId());
			if (queued.filter(record.ticket()::equals).isPresent()) {
				if (queue.replaceGeneration(record.ticket(), next.ticket(), registration)
					!= AgentPendingRunQueue.EnqueueResult.ACCEPTED)
					return ResourceReconcileResult.RESOURCE_MISMATCH;
			} else if (queued.filter(next.ticket()::equals).isEmpty()
				&& queue.restoreDurable(next.ticket(), registration)
					!= AgentPendingRunQueue.EnqueueResult.ACCEPTED) {
				return ResourceReconcileResult.RESOURCE_MISMATCH;
			}
			if (activeExact(process, key) && !finishExact(process, key))
				return ResourceReconcileResult.RESOURCE_MISMATCH;
			clearEffect(key);
			return ResourceReconcileResult.COMPLETED_PENDING;
		}
		if (record == null || !registration.agentId().equals(record.ticket().agentId())
			|| !registration.route().equals(record.ticket().targetRoute())
			|| registration.agentGeneration() != key.agentGeneration()
			|| !processMatches(record, key, process))
			return ResourceReconcileResult.RESOURCE_MISMATCH;
		if (effect != null && effect.kind() == EffectKind.ENQUEUE) {
			if (!ensureQueued(record.ticket(), queue, registration))
				return ResourceReconcileResult.RESOURCE_MISMATCH;
			clearEffect(key);
			return ResourceReconcileResult.COMPLETED_PENDING;
		}
		if (effect != null && effect.kind() == EffectKind.RESERVE) {
			if (!ensureActive(process, key)) return ResourceReconcileResult.RESOURCE_MISMATCH;
			if (queue.find(key.requestId()).isPresent()) queue.cancel(key.requestId());
			RemoteAgentRecord reserved = record.phase() == RemoteAgentPhase.QUEUED
				? record.reserve() : record;
			LinkedHashMap<RemoteRequestKey, RemoteAgentRecord> nextRecords =
				new LinkedHashMap<>(records);
			nextRecords.put(key, reserved);
			LinkedHashMap<RemoteRequestKey, PendingEffect> nextEffects =
				new LinkedHashMap<>(pendingEffects);
			nextEffects.remove(key);
			commit(proposed(nextRecords, acknowledged, nextEffects, lineages));
			return ResourceReconcileResult.COMPLETED_PENDING;
		}
		if (effect != null && effect.kind() == EffectKind.CANCEL_QUEUE) {
			if (queue.find(key.requestId()).isPresent()) queue.cancel(key.requestId());
			clearEffect(key);
			return ResourceReconcileResult.COMPLETED_PENDING;
		}
		if (effect != null && effect.kind() == EffectKind.RELEASE_PROCESS) {
			if (!finishExact(process, key)) return ResourceReconcileResult.RESOURCE_MISMATCH;
			clearEffect(key);
			return ResourceReconcileResult.COMPLETED_PENDING;
		}
		if (effect != null && effect.kind() == EffectKind.ROLLBACK_ADMISSION) {
			return finishRollbackAdmission(key, record, queue, process)
				? ResourceReconcileResult.COMPLETED_PENDING
				: ResourceReconcileResult.RESOURCE_MISMATCH;
		}
		if (effect != null) return ResourceReconcileResult.RESOURCE_MISMATCH;
		if (record.phase() == RemoteAgentPhase.QUEUED) {
			return ensureQueued(record.ticket(), queue, registration)
				? ResourceReconcileResult.RESTORED_QUEUED
				: ResourceReconcileResult.RESOURCE_MISMATCH;
		}
		if (record.phase() == RemoteAgentPhase.RESERVED) {
			if (queue.find(key.requestId()).isPresent()) queue.cancel(key.requestId());
			return ensureActive(process, key) ? ResourceReconcileResult.RESTORED_RESERVED
				: ResourceReconcileResult.RESOURCE_MISMATCH;
		}
		return ResourceReconcileResult.NOTHING_TO_RESTORE;
	}

	private static boolean ensureQueued(ProductionRequestTicket ticket,
		AgentPendingRunQueue queue, AgentAddressRegistration registration) {
		Optional<ProductionRequestTicket> existing = queue.find(ticket.requestId());
		if (existing.isPresent()) return existing.filter(ticket::equals).isPresent();
		return queue.restoreDurable(ticket, registration)
			== AgentPendingRunQueue.EnqueueResult.ACCEPTED;
	}

	private static boolean ensureActive(AgentProcess process, RemoteRequestKey key) {
		return activeExact(process, key)
			|| process.activeContract().isEmpty()
				&& process.startContract(key.requestId(), key.fence());
	}

	private static boolean activeExact(AgentProcess process, RemoteRequestKey key) {
		return process != null && process.activeContractState().filter(active ->
			active.contractId().equals(key.requestId()) && active.fence() == key.fence()).isPresent();
	}

	private static boolean processMatches(RemoteAgentRecord record, RemoteRequestKey key,
		AgentProcess process) {
		return process != null
			&& process.formation().agentId().equals(record.ticket().agentId())
			&& process.formation().ownerClusterId().equals(record.targetClusterId())
			&& process.formation().agentGeneration() == key.agentGeneration();
	}

	private static boolean finishExact(@Nullable AgentProcess process, RemoteRequestKey key) {
		if (process == null) return false;
		if (process.activeContract().isEmpty()) return true;
		return activeExact(process, key) && process.finishContract(key.requestId());
	}

	public OfferResult retryToNewAgentGeneration(RemoteRequestKey oldKey,
		AgentProtocolMessage.Offer retry, AgentPendingRunQueue queue,
		AgentAddressRegistration registration, @Nullable AgentProcess oldProcess) {
		oldKey = Objects.requireNonNull(oldKey, "oldKey");
		retry = Objects.requireNonNull(retry, "retry");
		RemoteAgentRecord old = records.get(oldKey);
		if (old == null || old.isTerminal() || old.crossedDispatchIntent()
			|| !oldKey.requestId().equals(retry.key().requestId())
			|| !oldKey.runId().equals(retry.key().runId())
			|| oldKey.fence() != retry.key().fence()
			|| oldKey.sourceGeneration() != retry.key().sourceGeneration()
			|| retry.key().agentGeneration() <= oldKey.agentGeneration())
			return rejected(OfferDisposition.POST_INTENT_RETRY_REJECTED);
		if (!sameRetryIdentity(old.ticket(), retry.ticket()))
			return rejected(OfferDisposition.LINEAGE_MUTATION_REJECTED);
		if (correctnessOccupancy() >= capacity)
			return new OfferResult(OfferDisposition.RECOVERY_LEDGER_FULL, null,
				Optional.of(WaitReason.RECOVERY_LEDGER_FULL));
		if (registration.agentGeneration() != retry.key().agentGeneration()
			|| !registration.agentId().equals(retry.ticket().agentId())
			|| !registration.route().equals(retry.ticket().targetRoute())
			|| registration.loadState() != AgentAddressRegistration.LoadState.LOADED
			|| registration.reachability() != AgentAddressRegistration.Reachability.REACHABLE)
			return rejected(OfferDisposition.STALE_AGENT_GENERATION);
		if (old.phase() == RemoteAgentPhase.QUEUED
			&& queue.preflightGenerationReplacement(old.ticket(), retry.ticket(), registration)
				!= AgentPendingRunQueue.EnqueueResult.ACCEPTED)
			return rejected(OfferDisposition.QUEUE_REJECTED);
		if (old.phase() == RemoteAgentPhase.RESERVED && (oldProcess == null
			|| !processMatches(old, oldKey, oldProcess) || !activeExact(oldProcess, oldKey)))
			return rejected(OfferDisposition.QUEUE_REJECTED);
		if (old.phase() == RemoteAgentPhase.RESERVED
			&& queue.preflightEnqueue(retry.ticket(), registration)
				!= AgentPendingRunQueue.EnqueueResult.ACCEPTED)
			return rejected(OfferDisposition.QUEUE_REJECTED);

		RemoteAgentRecord terminal = old.markCancelledPreIntent(
			RemoteAgentRecord.TerminalOutcome.RETRIED_PRE_INTENT);
		RemoteAgentRecord next = RemoteAgentRecord.queued(retry.key(),
			registration.ownerClusterId(), retry.ticket());
		LinkedHashMap<RemoteRequestKey, RemoteAgentRecord> nextRecords =
			new LinkedHashMap<>(records);
		nextRecords.put(oldKey, terminal);
		nextRecords.put(retry.key(), next);
		LinkedHashMap<RemoteRequestKey, PendingEffect> nextEffects =
			new LinkedHashMap<>(pendingEffects);
		nextEffects.put(oldKey, new PendingEffect(oldKey, EffectKind.SUPERSEDE,
			Optional.of(retry.key())));
		LinkedHashMap<LineageId, LineageHighWatermark> nextLineages =
			new LinkedHashMap<>(lineages);
		nextLineages.put(LineageId.from(oldKey), LineageHighWatermark.from(retry.key(),
			retry.ticket()));
		commit(proposed(nextRecords, acknowledged, nextEffects, nextLineages));

		AgentPendingRunQueue.EnqueueResult enqueued = old.phase() == RemoteAgentPhase.QUEUED
			? queue.replaceGeneration(old.ticket(), retry.ticket(), registration)
			: queue.enqueue(retry.ticket(), registration);
		if (enqueued != AgentPendingRunQueue.EnqueueResult.ACCEPTED) {
			LinkedHashMap<RemoteRequestKey, RemoteAgentRecord> rollbackRecords =
				new LinkedHashMap<>(records);
			rollbackRecords.put(oldKey, old);
			rollbackRecords.remove(retry.key());
			LinkedHashMap<RemoteRequestKey, PendingEffect> rollbackEffects =
				new LinkedHashMap<>(pendingEffects);
			rollbackEffects.remove(oldKey);
			LinkedHashMap<LineageId, LineageHighWatermark> rollbackLineages =
				new LinkedHashMap<>(lineages);
			rollbackLineages.put(LineageId.from(oldKey),
				LineageHighWatermark.from(oldKey, old.ticket()));
			commit(proposed(rollbackRecords, acknowledged, rollbackEffects, rollbackLineages));
			return rejected(OfferDisposition.QUEUE_REJECTED);
		}
		if (old.phase() == RemoteAgentPhase.RESERVED && !finishExact(oldProcess, oldKey))
			throw new IllegalStateException("preflighted reserved contract changed during supersession");
		clearEffect(oldKey);
		return new OfferResult(OfferDisposition.ACCEPTED, next, Optional.empty());
	}

	public CancelResult cancel(AgentProtocolMessage.Cancel message, AgentPendingRunQueue queue,
		@Nullable AgentProcess process, @Nullable AgentInputContract input) {
		message = Objects.requireNonNull(message, "message");
		queue = Objects.requireNonNull(queue, "queue");
		RemoteAgentRecord record = records.get(message.key());
		if (record == null) return CancelResult.NOT_FOUND;
		if (record.isTerminal()) return CancelResult.ALREADY_TERMINAL;
		if (record.condition() == RemoteAgentRecord.RecoveryCondition.CANCEL_DRAIN) {
			PendingEffect pending = pendingEffects.get(message.key());
			if (pending == null) return CancelResult.CANCEL_DRAIN;
			if (pending.kind() != EffectKind.BEGIN_CANCEL_DRAIN || input == null)
				return CancelResult.RESOURCE_MISMATCH;
			return finishBeginCancelDrain(message.key(), record, input);
		}
		if (!record.crossedDispatchIntent()) {
			if (record.phase() == RemoteAgentPhase.QUEUED
				&& queue.find(message.key().requestId()).filter(record.ticket()::equals).isEmpty())
				return CancelResult.NOT_FOUND;
			if (record.phase() == RemoteAgentPhase.RESERVED
				&& (process == null || !processMatches(record, message.key(), process)
					|| !activeExact(process, message.key())))
				return CancelResult.RESOURCE_MISMATCH;
			RemoteAgentRecord cancelled = record.markCancelledPreIntent(
				RemoteAgentRecord.TerminalOutcome.CANCELLED_PRE_INTENT);
			EffectKind effect = record.phase() == RemoteAgentPhase.QUEUED
				? EffectKind.CANCEL_QUEUE : EffectKind.RELEASE_PROCESS;
			commitRecordAndEffect(cancelled, PendingEffect.of(message.key(), effect));
			boolean released = effect == EffectKind.CANCEL_QUEUE
				? queue.cancel(message.key().requestId()) : finishExact(process, message.key());
			if (!released) return CancelResult.RESOURCE_MISMATCH;
			clearEffect(message.key());
			return CancelResult.CANCELLED_PRE_INTENT;
		}
		if (input == null || record.inputState().isEmpty()
			|| !record.inputState().orElseThrow().equals(input.snapshot()))
			return CancelResult.RESOURCE_MISMATCH;
		RemoteAgentRecord requested = record.requestCancelDrain();
		commitRecordAndEffect(requested,
			PendingEffect.of(message.key(), EffectKind.BEGIN_CANCEL_DRAIN));
		return finishBeginCancelDrain(message.key(), requested, input);
	}

	private CancelResult finishBeginCancelDrain(RemoteRequestKey key,
		RemoteAgentRecord requested, AgentInputContract input) {
		if (!input.beginCancellationDrain(key.fence())) return CancelResult.RESOURCE_MISMATCH;
		RemoteAgentRecord draining = requested.beginCancelDrain(input.snapshot());
		LinkedHashMap<RemoteRequestKey, RemoteAgentRecord> nextRecords =
			new LinkedHashMap<>(records);
		nextRecords.put(key, draining);
		LinkedHashMap<RemoteRequestKey, PendingEffect> nextEffects =
			new LinkedHashMap<>(pendingEffects);
		nextEffects.remove(key);
		commit(proposed(nextRecords, acknowledged, nextEffects, lineages));
		return CancelResult.CANCEL_DRAIN;
	}

	public boolean completeCancelDrain(CancelDrainEvidence evidence, AgentProcess process) {
		evidence = Objects.requireNonNull(evidence, "evidence");
		process = Objects.requireNonNull(process, "process");
		RemoteAgentRecord record = records.get(evidence.key());
		if (record == null) return false;
		if (record.isTerminal()) {
			return record.terminalOutcome().orElse(null)
					== RemoteAgentRecord.TerminalOutcome.CANCEL_DRAINED
				&& record.condition() == RemoteAgentRecord.RecoveryCondition.CANCEL_DRAIN
				&& record.inputState().filter(evidence.inputState()::equals).isPresent()
				&& record.outputState().filter(evidence.outputState()::equals).isPresent()
				&& record.outputLedger().filter(evidence.outputLedger()::equals).isPresent();
		}
		if (record.condition() != RemoteAgentRecord.RecoveryCondition.CANCEL_DRAIN
			|| record.inputState().filter(evidence.inputState()::equals).isEmpty()
			|| !evidence.inputState().cancellationDrain()
			|| evidence.inputState().phase() != AgentInputPhase.INPUT_ENDED
			|| evidence.inputState().nativeOrders().map(NativeOrderLedger::restore)
				.stream().noneMatch(NativeOrderLedger::complete)
			|| !evidence.authoritativeEmpty()
			|| (evidence.outputState().milestone()
				!= ProductionOutputContract.Milestone.OUTPUT_DISPATCHED
				&& evidence.outputState().milestone()
					!= ProductionOutputContract.Milestone.OUTPUT_RECEIVED)
			|| !evidence.outputState().agentReleased()
			|| !evidence.outputLedger().runId().equals(evidence.key().runId())
			|| evidence.outputLedger().fence() != evidence.key().fence()
			|| !process.formation().agentId().equals(record.ticket().agentId())
			|| !process.formation().ownerClusterId().equals(record.targetClusterId())
			|| process.formation().agentGeneration() != evidence.key().agentGeneration()) return false;
		RemoteAgentRecord terminal = record.terminal(RemoteAgentPhase.RESET_REQUIRED,
			RemoteAgentRecord.RecoveryCondition.CANCEL_DRAIN,
			RemoteAgentRecord.TerminalOutcome.CANCEL_DRAINED,
			Optional.of(evidence.inputState()), Optional.of(evidence.outputState()),
			Optional.of(evidence.outputLedger()));
		commitRecordAndEffect(terminal,
			PendingEffect.of(evidence.key(), EffectKind.RELEASE_PROCESS));
		if (!finishExact(process, evidence.key())) return false;
		clearEffect(evidence.key());
		return true;
	}

	public QueryResult query(AgentProtocolMessage.StatusQuery query) {
		RemoteRequestKey key = Objects.requireNonNull(query, "query").key();
		RemoteAgentRecord exact = records.get(key);
		if (exact != null)
			return new QueryResult(QueryDisposition.FOUND, Optional.of(exact), Optional.empty());
		if (acknowledged.containsKey(key))
			return new QueryResult(QueryDisposition.ACKNOWLEDGED_TOMBSTONE, Optional.empty(),
				Optional.empty());
		Optional<LineageHighWatermark> lineage = knownLineage(key);
		if (lineage.isPresent()) {
			LineageHighWatermark highWatermark = lineage.orElseThrow();
			RemoteRequestKey known = highWatermark.key();
			if (!highWatermark.id().equals(LineageId.from(key)))
				return queryRejected(QueryDisposition.LINEAGE_MUTATION_REJECTED);
			if (highWatermark.key().equals(key) && highWatermark.terminalAcknowledged())
				return new QueryResult(QueryDisposition.ACKNOWLEDGED_TOMBSTONE,
					Optional.empty(), Optional.empty());
			if (known.fence() != key.fence()) return queryRejected(QueryDisposition.STALE_FENCE);
			if (known.sourceGeneration() != key.sourceGeneration())
				return queryRejected(QueryDisposition.STALE_SOURCE_GENERATION);
			if (known.agentGeneration() != key.agentGeneration())
				return queryRejected(QueryDisposition.STALE_AGENT_GENERATION);
		}
		return new QueryResult(QueryDisposition.REMOTE_RECOVERY, Optional.empty(),
			Optional.of(WaitReason.REMOTE_RECOVERY));
	}

	public AgentProtocolMessage.Status protocolStatus(AgentProtocolMessage.StatusQuery query) {
		QueryResult result = query(query);
		AgentProtocolMessage.StatusCode code = switch (result.disposition()) {
			case FOUND -> AgentProtocolMessage.StatusCode.PRESENT;
			case REMOTE_RECOVERY -> AgentProtocolMessage.StatusCode.REMOTE_RECOVERY;
			case STALE_FENCE -> AgentProtocolMessage.StatusCode.STALE_FENCE;
			case STALE_SOURCE_GENERATION -> AgentProtocolMessage.StatusCode.STALE_SOURCE_GENERATION;
			case STALE_AGENT_GENERATION -> AgentProtocolMessage.StatusCode.STALE_AGENT_GENERATION;
			case ACKNOWLEDGED_TOMBSTONE -> AgentProtocolMessage.StatusCode.ACKNOWLEDGED_TOMBSTONE;
			case LINEAGE_MUTATION_REJECTED ->
				AgentProtocolMessage.StatusCode.LINEAGE_MUTATION_REJECTED;
		};
		return new AgentProtocolMessage.Status(query.key(), code, result.record());
	}

	public boolean acknowledge(AgentProtocolMessage.EvidenceAck acknowledgement) {
		acknowledgement = Objects.requireNonNull(acknowledgement, "acknowledgement");
		RemoteAgentRecord record = records.get(acknowledgement.key());
		if (record == null) {
			AcknowledgedTombstone tombstone = acknowledged.get(acknowledgement.key());
			return tombstone != null && tombstone.revision() == acknowledgement.revision();
		}
		if (!record.isTerminal()
			|| record.revision() != acknowledgement.revision()
			|| pendingEffects.containsKey(acknowledgement.key())) return false;
		LinkedHashMap<RemoteRequestKey, RemoteAgentRecord> nextRecords =
			new LinkedHashMap<>(records);
		nextRecords.remove(acknowledgement.key());
		LinkedHashMap<RemoteRequestKey, AcknowledgedTombstone> nextAcknowledged =
			new LinkedHashMap<>(acknowledged);
		while (nextAcknowledged.size() >= capacity)
			nextAcknowledged.remove(nextAcknowledged.keySet().iterator().next());
		nextAcknowledged.put(acknowledgement.key(), new AcknowledgedTombstone(
			acknowledgement.key(), record.terminalOutcome().orElseThrow(), record.revision()));
		LinkedHashMap<LineageId, LineageHighWatermark> nextLineages =
			new LinkedHashMap<>(lineages);
		LineageId lineageId = LineageId.from(acknowledgement.key());
		LineageHighWatermark highWatermark = nextLineages.get(lineageId);
		if (highWatermark != null && highWatermark.key().equals(acknowledgement.key()))
			nextLineages.put(lineageId, highWatermark.acknowledgeTerminal());
		commit(proposed(nextRecords, nextAcknowledged, pendingEffects, nextLineages));
		return true;
	}

	public Optional<RemoteAgentRecord> status(RemoteRequestKey key) {
		return Optional.ofNullable(records.get(Objects.requireNonNull(key, "key")));
	}

	public int size() { return records.size(); }

	public int unacknowledgedEvidenceCount() {
		return (int) records.values().stream().filter(RemoteAgentRecord::isTerminal).count();
	}

	public int correctnessOccupancy() {
		return correctnessOccupancySnapshot().total();
	}

	public CorrectnessOccupancy correctnessOccupancySnapshot() {
		int acknowledgedOnly = Math.subtractExact(lineages.size(), liveLineages.size());
		int total = Math.addExact(records.size(), acknowledgedOnly);
		return new CorrectnessOccupancy(records.size(), liveLineages.size(), acknowledgedOnly, total);
	}

	public boolean compactAcknowledgedLineage(LineageCompactionEvidence evidence) {
		evidence = Objects.requireNonNull(evidence, "evidence");
		LineageId id = LineageId.from(evidence.key());
		LineageHighWatermark lineage = lineages.get(id);
		if (lineage == null || !lineage.key().equals(evidence.key())
			|| !lineage.terminalAcknowledged()
			|| records.values().stream().anyMatch(record -> LineageId.from(record.key()).equals(id)))
			return false;
		LinkedHashMap<LineageId, LineageHighWatermark> nextLineages =
			new LinkedHashMap<>(lineages);
		nextLineages.remove(id);
		LinkedHashMap<RemoteRequestKey, AcknowledgedTombstone> nextAcknowledged =
			new LinkedHashMap<>(acknowledged);
		nextAcknowledged.remove(evidence.key());
		commit(proposed(records, nextAcknowledged, pendingEffects, nextLineages));
		return true;
	}

	public State snapshot() {
		return new State(Map.copyOf(records), Map.copyOf(acknowledged),
			Map.copyOf(pendingEffects), Map.copyOf(lineages));
	}

	private Optional<LineageHighWatermark> knownLineage(RemoteRequestKey key) {
		return lineages.values().stream().filter(lineage ->
			lineage.key().requestId().equals(key.requestId())
				|| lineage.key().runId().equals(key.runId())).findFirst();
	}

	private static boolean sameRetryIdentity(ProductionRequestTicket expected,
		ProductionRequestTicket retry) {
		return expected.requestId().equals(retry.requestId())
			&& expected.runId().equals(retry.runId())
			&& expected.fence() == retry.fence()
			&& expected.sourceClusterId().equals(retry.sourceClusterId())
			&& expected.agentId().equals(retry.agentId())
			&& expected.targetRoute().equals(retry.targetRoute())
			&& expected.patternId().equals(retry.patternId())
			&& expected.mode() == retry.mode()
			&& expected.multiplier() == retry.multiplier()
			&& expected.requestedQueuePriority() == retry.requestedQueuePriority()
			&& expected.sourceMaterialSnapshotGeneration()
				== retry.sourceMaterialSnapshotGeneration();
	}

	private void restore(State state) {
		state = Objects.requireNonNull(state, "state");
		records.putAll(state.records());
		acknowledged.putAll(state.acknowledgedTombstones());
		pendingEffects.putAll(state.pendingEffects());
		lineages.putAll(state.lineages());
		rebuildLiveLineages();
	}

	private OfferResult rejected(OfferDisposition disposition) {
		return new OfferResult(disposition, null, Optional.empty());
	}

	private static QueryResult queryRejected(QueryDisposition disposition) {
		return new QueryResult(disposition, Optional.empty(), Optional.empty());
	}

	private void commit(State next) {
		next = Objects.requireNonNull(next, "next");
		store.persist(next);
		records.clear();
		records.putAll(next.records());
		acknowledged.clear();
		acknowledged.putAll(next.acknowledgedTombstones());
		pendingEffects.clear();
		pendingEffects.putAll(next.pendingEffects());
		lineages.clear();
		lineages.putAll(next.lineages());
		rebuildLiveLineages();
	}

	private void rebuildLiveLineages() {
		liveLineages.clear();
		for (RemoteRequestKey key : records.keySet())
			liveLineages.add(LineageId.from(key));
	}

	private State proposed(Map<RemoteRequestKey, RemoteAgentRecord> nextRecords,
		Map<RemoteRequestKey, AcknowledgedTombstone> nextAcknowledged,
		Map<RemoteRequestKey, PendingEffect> nextEffects,
		Map<LineageId, LineageHighWatermark> nextLineages) {
		return new State(nextRecords, nextAcknowledged, nextEffects, nextLineages);
	}

	private void commitRecord(RemoteAgentRecord record) {
		LinkedHashMap<RemoteRequestKey, RemoteAgentRecord> next = new LinkedHashMap<>(records);
		next.put(record.key(), record);
		commit(proposed(next, acknowledged, pendingEffects, lineages));
	}

	private void commitRecordAndEffect(RemoteAgentRecord record, PendingEffect effect) {
		LinkedHashMap<RemoteRequestKey, RemoteAgentRecord> nextRecords =
			new LinkedHashMap<>(records);
		nextRecords.put(record.key(), record);
		LinkedHashMap<RemoteRequestKey, PendingEffect> nextEffects =
			new LinkedHashMap<>(pendingEffects);
		nextEffects.put(effect.key(), effect);
		commit(proposed(nextRecords, acknowledged, nextEffects, lineages));
	}

	private void clearEffect(RemoteRequestKey key) {
		if (!pendingEffects.containsKey(key)) return;
		LinkedHashMap<RemoteRequestKey, PendingEffect> next =
			new LinkedHashMap<>(pendingEffects);
		next.remove(key);
		commit(proposed(records, acknowledged, next, lineages));
	}

	public interface StateStore {
		default Optional<State> load() { return Optional.empty(); }
		void persist(State state);
	}

	public record State(Map<RemoteRequestKey, RemoteAgentRecord> records,
		Map<RemoteRequestKey, AcknowledgedTombstone> acknowledgedTombstones,
		Map<RemoteRequestKey, PendingEffect> pendingEffects,
		Map<LineageId, LineageHighWatermark> lineages) {
		public State {
			records = Map.copyOf(Objects.requireNonNull(records, "records"));
			acknowledgedTombstones = Map.copyOf(Objects.requireNonNull(acknowledgedTombstones,
				"acknowledgedTombstones"));
			pendingEffects = Map.copyOf(Objects.requireNonNull(pendingEffects, "pendingEffects"));
			lineages = Map.copyOf(Objects.requireNonNull(lineages, "lineages"));
			for (Map.Entry<RemoteRequestKey, RemoteAgentRecord> entry : records.entrySet())
				if (!entry.getKey().equals(entry.getValue().key())
					|| acknowledgedTombstones.containsKey(entry.getKey()))
					throw new IllegalArgumentException("Recovery state has inconsistent exact keys");
			for (Map.Entry<RemoteRequestKey, AcknowledgedTombstone> entry
				: acknowledgedTombstones.entrySet())
				if (!entry.getKey().equals(entry.getValue().key()))
					throw new IllegalArgumentException("Acknowledged tombstone key mismatch");
			for (Map.Entry<RemoteRequestKey, PendingEffect> entry : pendingEffects.entrySet())
				if (!entry.getKey().equals(entry.getValue().key())
					|| !records.containsKey(entry.getKey()))
					throw new IllegalArgumentException("Pending effect lacks its exact record");
			for (PendingEffect effect : pendingEffects.values()) {
				RemoteAgentRecord record = records.get(effect.key());
				if (effect.kind() == EffectKind.SUPERSEDE
					&& !records.containsKey(effect.supersedingKey().orElseThrow()))
					throw new IllegalArgumentException("Supersession lacks its replacement record");
				if (effect.kind() == EffectKind.BEGIN_CANCEL_DRAIN
					&& record.condition() != RemoteAgentRecord.RecoveryCondition.CANCEL_DRAIN)
					throw new IllegalArgumentException("Cancellation effect lacks its drain fence");
			}
			HashSet<UUID> lineageRequests = new HashSet<>();
			HashSet<UUID> lineageRuns = new HashSet<>();
			for (Map.Entry<LineageId, LineageHighWatermark> entry : lineages.entrySet())
				if (!entry.getKey().equals(entry.getValue().id())
					|| !lineageRequests.add(entry.getKey().requestId())
					|| !lineageRuns.add(entry.getKey().runId()))
					throw new IllegalArgumentException("Lineage high-watermark identity mismatch");
			for (RemoteRequestKey key : records.keySet())
				if (!lineages.containsKey(LineageId.from(key)))
					throw new IllegalArgumentException("Live record lacks lineage high-watermark");
			for (AcknowledgedTombstone tombstone : acknowledgedTombstones.values()) {
				LineageHighWatermark lineage = lineages.get(LineageId.from(tombstone.key()));
				if (lineage == null || !lineage.key().equals(tombstone.key())
					|| !lineage.terminalAcknowledged())
					throw new IllegalArgumentException("Acknowledged tombstone lacks high-watermark");
			}
		}
	}

	public record LineageId(UUID requestId, UUID runId) {
		public LineageId {
			requestId = Objects.requireNonNull(requestId, "requestId");
			runId = Objects.requireNonNull(runId, "runId");
		}
		static LineageId from(RemoteRequestKey key) {
			return new LineageId(key.requestId(), key.runId());
		}
	}

	public record CorrectnessOccupancy(int liveRecordCount, int liveLineageCount,
		int acknowledgedOnlyLineageCount, int total) {
		public CorrectnessOccupancy {
			if (liveRecordCount < 0 || liveLineageCount < 0
				|| acknowledgedOnlyLineageCount < 0 || total < 0
				|| total != liveRecordCount + acknowledgedOnlyLineageCount)
				throw new IllegalArgumentException("Invalid RecoveryLedger correctness occupancy");
		}
	}

	public record LineageHighWatermark(LineageId id, RemoteRequestKey key,
		UUID sourceClusterId, UUID agentId,
		com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey targetRoute,
		boolean terminalAcknowledged) {
		public LineageHighWatermark {
			id = Objects.requireNonNull(id, "id");
			key = Objects.requireNonNull(key, "key");
			sourceClusterId = Objects.requireNonNull(sourceClusterId, "sourceClusterId");
			agentId = Objects.requireNonNull(agentId, "agentId");
			targetRoute = Objects.requireNonNull(targetRoute, "targetRoute");
			if (!id.equals(LineageId.from(key)))
				throw new IllegalArgumentException("Lineage id differs from its frozen key");
		}
		static LineageHighWatermark from(RemoteRequestKey key, ProductionRequestTicket ticket) {
			return new LineageHighWatermark(LineageId.from(key), key, ticket.sourceClusterId(),
				ticket.agentId(), ticket.targetRoute(), false);
		}
		LineageHighWatermark acknowledgeTerminal() {
			return terminalAcknowledged ? this : new LineageHighWatermark(id, key,
				sourceClusterId, agentId, targetRoute, true);
		}
	}

	public record PendingEffect(RemoteRequestKey key, EffectKind kind,
		Optional<RemoteRequestKey> supersedingKey) {
		public PendingEffect {
			key = Objects.requireNonNull(key, "key");
			kind = Objects.requireNonNull(kind, "kind");
			supersedingKey = Objects.requireNonNull(supersedingKey, "supersedingKey");
			if ((kind == EffectKind.SUPERSEDE) != supersedingKey.isPresent())
				throw new IllegalArgumentException("Supersession effect lacks its new key");
		}
		static PendingEffect of(RemoteRequestKey key, EffectKind kind) {
			return new PendingEffect(key, kind, Optional.empty());
		}
	}

	public enum EffectKind {
		ENQUEUE,
		RESERVE,
		RELEASE_PROCESS,
		CANCEL_QUEUE,
		BEGIN_CANCEL_DRAIN,
		ROLLBACK_ADMISSION,
		SUPERSEDE
	}

	public record AcknowledgedTombstone(RemoteRequestKey key,
		RemoteAgentRecord.TerminalOutcome outcome, long revision) {
		public AcknowledgedTombstone {
			key = Objects.requireNonNull(key, "key");
			outcome = Objects.requireNonNull(outcome, "outcome");
			if (revision < 0) throw new IllegalArgumentException("Tombstone revision is negative");
		}
	}

	public record OfferResult(OfferDisposition disposition,
		@Nullable RemoteAgentRecord record, Optional<WaitReason> waitReason) {
		public OfferResult {
			disposition = Objects.requireNonNull(disposition, "disposition");
			waitReason = Objects.requireNonNull(waitReason, "waitReason");
			if ((disposition == OfferDisposition.ACCEPTED
				|| disposition == OfferDisposition.DUPLICATE) != (record != null))
				throw new IllegalArgumentException("Offer disposition and record disagree");
		}
	}

	public record QueryResult(QueryDisposition disposition, Optional<RemoteAgentRecord> record,
		Optional<WaitReason> waitReason) {
		public QueryResult {
			disposition = Objects.requireNonNull(disposition, "disposition");
			record = Objects.requireNonNull(record, "record");
			waitReason = Objects.requireNonNull(waitReason, "waitReason");
			if (record.isPresent() != (disposition == QueryDisposition.FOUND))
				throw new IllegalArgumentException("Query disposition and record disagree");
		}
	}

	public record ReconcileResult(ReconcileAction action, @Nullable RemoteAgentRecord record,
		boolean redispatchAllowed, Optional<WaitReason> waitReason) {
		public ReconcileResult {
			action = Objects.requireNonNull(action, "action");
			waitReason = Objects.requireNonNull(waitReason, "waitReason");
			if ((action == ReconcileAction.REMOTE_RECOVERY) != (record == null))
				throw new IllegalArgumentException("Reconcile action and record disagree");
			if (redispatchAllowed && (record == null || record.crossedDispatchIntent()))
				throw new IllegalArgumentException("Irreversible record cannot redispatch");
		}
	}

	public static final class CancelDrainEvidence {
		private final RemoteRequestKey key;
		private final AgentInputContract.State inputState;
		private final ProductionOutputContract.Snapshot outputState;
		private final ProductionOutputLedger outputLedger;
		private final ResolvedCancelDrainFacts endpointFacts;

		private CancelDrainEvidence(RemoteRequestKey key, AgentInputContract.State inputState,
			ProductionOutputContract.Snapshot outputState, ProductionOutputLedger outputLedger,
			ResolvedCancelDrainFacts endpointFacts) {
			this.key = Objects.requireNonNull(key, "key");
			this.inputState = Objects.requireNonNull(inputState, "inputState");
			this.outputState = Objects.requireNonNull(outputState, "outputState");
			this.outputLedger = Objects.requireNonNull(outputLedger, "outputLedger");
			this.endpointFacts = Objects.requireNonNull(endpointFacts, "endpointFacts");
			if (!endpointFacts.key().equals(key)
				|| !inputState.run().runId().equals(key.runId())
				|| inputState.run().fence() != key.fence()
				|| inputState.agentResetGeneration() != key.agentGeneration()
				|| !outputState.runId().equals(key.runId())
				|| outputState.run().fence() != key.fence()
				|| outputState.agentResetGeneration() != key.agentGeneration()
				|| !outputLedger.runId().equals(key.runId())
				|| outputLedger.fence() != key.fence()
				|| !outputLedger.goalId().equals(inputState.run().goalId())
				|| !outputLedger.outputObservationEpoch().equals(inputState.outputObservationEpoch())
				|| outputLedger.outputPolicyGeneration()
					!= inputState.run().outputPolicyGeneration()
				|| !outputLedger.returnWarehouseRoute().equals(
					inputState.run().returnWarehouseRoute()))
				throw new IllegalArgumentException("Cancel drain evidence differs from its key");
		}

		public RemoteRequestKey key() { return key; }
		public AgentInputContract.State inputState() { return inputState; }
		public ProductionOutputContract.Snapshot outputState() { return outputState; }
		public ProductionOutputLedger outputLedger() { return outputLedger; }
		public UUID coherenceEpoch() { return endpointFacts.coherenceEpoch(); }
		public boolean authoritativeEmpty() {
			return endpointFacts.allEndpointsLoaded() && endpointFacts.outputBuffersEmpty()
				&& endpointFacts.destinationBacklogsEmpty();
		}
	}

	public static final class LineageCompactionEvidence {
		private final RemoteRequestKey key;
		private final UUID coherenceEpoch;

		private LineageCompactionEvidence(ResolvedLineageCompactionFacts facts) {
			this.key = facts.key();
			this.coherenceEpoch = facts.coherenceEpoch();
		}

		public RemoteRequestKey key() { return key; }
		public UUID coherenceEpoch() { return coherenceEpoch; }
	}

	public static final class LineageCompactionEvidenceIssuer {
		private final AgentResetService.ServerThreadGuard guard;
		private final LineageCompactionResolver resolver;

		public LineageCompactionEvidenceIssuer(AgentResetService.ServerThreadGuard guard,
			LineageCompactionResolver resolver) {
			this.guard = Objects.requireNonNull(guard, "guard");
			this.resolver = Objects.requireNonNull(resolver, "resolver");
		}

		public Optional<LineageCompactionEvidence> capture(RemoteRequestKey key) {
			key = Objects.requireNonNull(key, "key");
			if (!guard.onServerThread()) return Optional.empty();
			ResolvedLineageCompactionFacts facts = resolver.resolve(
				new LineageCompactionRequest(key)).orElse(null);
			return facts != null && facts.key().equals(key) && facts.peerAckHighWatermarkAdvanced()
				&& facts.sourceGenerationFloor() > key.sourceGeneration()
				&& facts.agentGenerationFloor() > key.agentGeneration()
				? Optional.of(new LineageCompactionEvidence(facts)) : Optional.empty();
		}
	}

	@FunctionalInterface
	public interface LineageCompactionResolver {
		Optional<ResolvedLineageCompactionFacts> resolve(LineageCompactionRequest request);
	}

	public record LineageCompactionRequest(RemoteRequestKey key) {
		public LineageCompactionRequest {
			key = Objects.requireNonNull(key, "key");
		}
	}

	public record ResolvedLineageCompactionFacts(RemoteRequestKey key, UUID coherenceEpoch,
		boolean peerAckHighWatermarkAdvanced, long sourceGenerationFloor,
		long agentGenerationFloor) {
		public ResolvedLineageCompactionFacts {
			key = Objects.requireNonNull(key, "key");
			coherenceEpoch = Objects.requireNonNull(coherenceEpoch, "coherenceEpoch");
			if (sourceGenerationFloor < 0 || agentGenerationFloor < 0)
				throw new IllegalArgumentException("Compaction generation floor is negative");
		}
	}

	public static final class CancelDrainEvidenceIssuer {
		private final AgentResetService.ServerThreadGuard guard;
		private final CancelDrainResolver resolver;

		public CancelDrainEvidenceIssuer(AgentResetService.ServerThreadGuard guard,
			CancelDrainResolver resolver) {
			this.guard = Objects.requireNonNull(guard, "guard");
			this.resolver = Objects.requireNonNull(resolver, "resolver");
		}

		public Optional<CancelDrainEvidence> capture(RemoteRequestKey key,
			AgentInputContract.State inputState, ProductionOutputContract.Snapshot outputState,
			ProductionOutputLedger outputLedger) {
			key = Objects.requireNonNull(key, "key");
			inputState = Objects.requireNonNull(inputState, "inputState");
			outputState = Objects.requireNonNull(outputState, "outputState");
			outputLedger = Objects.requireNonNull(outputLedger, "outputLedger");
			if (!guard.onServerThread()) return Optional.empty();
			CancelDrainRequest request = new CancelDrainRequest(key, inputState, outputState,
				outputLedger);
			ResolvedCancelDrainFacts facts = resolver.resolve(request).orElse(null);
			return facts == null ? Optional.empty() : Optional.of(new CancelDrainEvidence(key,
				inputState, outputState, outputLedger, facts));
		}
	}

	@FunctionalInterface
	public interface CancelDrainResolver {
		Optional<ResolvedCancelDrainFacts> resolve(CancelDrainRequest request);
	}

	public record CancelDrainRequest(RemoteRequestKey key,
		AgentInputContract.State inputState, ProductionOutputContract.Snapshot outputState,
		ProductionOutputLedger outputLedger) {
		public CancelDrainRequest {
			key = Objects.requireNonNull(key, "key");
			inputState = Objects.requireNonNull(inputState, "inputState");
			outputState = Objects.requireNonNull(outputState, "outputState");
			outputLedger = Objects.requireNonNull(outputLedger, "outputLedger");
		}
	}

	public record ResolvedCancelDrainFacts(RemoteRequestKey key, UUID coherenceEpoch,
		boolean allEndpointsLoaded, boolean outputBuffersEmpty,
		boolean destinationBacklogsEmpty) {
		public ResolvedCancelDrainFacts {
			key = Objects.requireNonNull(key, "key");
			coherenceEpoch = Objects.requireNonNull(coherenceEpoch, "coherenceEpoch");
		}
	}

	public enum OfferDisposition {
		ACCEPTED,
		DUPLICATE,
		ACKNOWLEDGED_DUPLICATE,
		STALE_FENCE,
		STALE_SOURCE_GENERATION,
		STALE_AGENT_GENERATION,
		POST_INTENT_RETRY_REJECTED,
		RECOVERY_LEDGER_FULL,
		REMOTE_RECOVERY,
		QUEUE_REJECTED,
		LINEAGE_MUTATION_REJECTED
	}

	public enum QueryDisposition {
		FOUND,
		REMOTE_RECOVERY,
		STALE_FENCE,
		STALE_SOURCE_GENERATION,
		STALE_AGENT_GENERATION,
		ACKNOWLEDGED_TOMBSTONE,
		LINEAGE_MUTATION_REJECTED
	}

	public enum ReconcileAction { RESUME_PRE_INTENT, RESEND_EVIDENCE, REMOTE_RECOVERY }

	public enum ResourceReconcileResult {
		RESTORED_QUEUED,
		RESTORED_RESERVED,
		COMPLETED_PENDING,
		NOTHING_TO_RESTORE,
		RESOURCE_MISMATCH
	}

	public enum CancelResult {
		CANCELLED_PRE_INTENT,
		CANCEL_DRAIN,
		ALREADY_TERMINAL,
		RESOURCE_MISMATCH,
		NOT_FOUND
	}
}
