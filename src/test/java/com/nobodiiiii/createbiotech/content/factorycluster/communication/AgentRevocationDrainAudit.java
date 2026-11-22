package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCoordinator;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputPhase;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentProcess;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResetService;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentPhase;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.AgentRevocationDrainState.Disposition;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.AgentRevocationDrainState.Finding;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.AgentRevocationDrainState.RadioDispatchEvidence;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.AgentRevocationDrainState.Source;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.AgentRevocationDrainState.Sources;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;

/** Exact correlation rules behind the public {@link AgentRevocationDrainState} audit. */
final class AgentRevocationDrainAudit {
	private AgentRevocationDrainAudit() {}

	static AgentRevocationDrainState assess(UUID clusterId, Sources sources) {
		ArrayList<Finding> findings = new ArrayList<>();
		CoordinatorIndex coordinators = coordinatorIndex(clusterId, sources, findings);
		Set<UUID> agentIds = coordinators.agentIds();
		assessCoordinators(clusterId, coordinators, sources.recovery(), findings);
		assessResets(clusterId, coordinators, sources, findings);
		assessRecovery(clusterId, agentIds, sources.recovery(), sources.pendingRuns(), findings);
		assessPending(agentIds, sources.recovery(), sources.pendingRuns(), findings);
		assessNative(clusterId, agentIds, sources.recovery(), sources.inputContracts(),
			sources.outputContracts(), findings);
		Map<UUID, RadioDispatchEvidence> dispatches = assessDispatches(clusterId, agentIds,
			sources.recovery(), sources.radioDispatches(), findings);
		assessOutbox(clusterId, dispatches, sources.outbox(), findings);
		Disposition aggregate = findings.stream().map(Finding::disposition)
			.reduce(Disposition.DRAINED, AgentRevocationDrainAudit::worse);
		return new AgentRevocationDrainState(clusterId, aggregate, findings);
	}

	private static void assessResets(UUID clusterId, CoordinatorIndex coordinators,
		Sources sources, List<Finding> findings) {
		LinkedHashMap<UUID, AgentResetService.State> durable = new LinkedHashMap<>();
		Disposition disposition = Disposition.DRAINED;
		for (AgentResetService.State state : sources.durableResets()) {
			if (!state.ownerClusterId().equals(clusterId)) continue;
			AgentResetService.State old = durable.putIfAbsent(state.agentId(), state);
			if (old != null && !old.equals(state))
				disposition = worse(disposition, Disposition.MISSING_EVIDENCE);
		}
		LinkedHashMap<UUID, AgentResetService.State> live = new LinkedHashMap<>();
		for (AgentResetService.State state : sources.liveResets()) {
			if (!state.ownerClusterId().equals(clusterId)) continue;
			AgentResetService.State old = live.putIfAbsent(state.agentId(), state);
			if (old != null && !old.equals(state))
				disposition = worse(disposition, Disposition.MISSING_EVIDENCE);
		}
		for (UUID unknown : durable.keySet())
			if (!coordinators.agentIds().contains(unknown))
				disposition = worse(disposition, Disposition.MISSING_EVIDENCE);
		for (UUID unknown : live.keySet())
			if (!coordinators.agentIds().contains(unknown))
				disposition = worse(disposition, Disposition.MISSING_EVIDENCE);
		for (UUID agentId : coordinators.agentIds()) {
			AgentCoordinator.PersistedState coordinator = coordinators.durable().get(agentId);
			AgentResetService.State reset = durable.get(agentId);
			if (reset == null || reset.agentResetGeneration() != coordinator.agentGeneration()) {
				disposition = worse(disposition, Disposition.MISSING_EVIDENCE);
				continue;
			}
			AgentResetService.State loaded = live.get(agentId);
			boolean loadedCoordinator = coordinators.live().containsKey(agentId);
			if (loadedCoordinator != (loaded != null)
				|| loaded != null && !loaded.equals(reset)) {
				disposition = worse(disposition, Disposition.MISSING_EVIDENCE);
				continue;
			}
			if (reset.resetRequired())
				disposition = worse(disposition, Disposition.IRREVERSIBLE);
			else if (reset.activeKey().isPresent())
				disposition = worse(disposition, Disposition.NON_DRAINED);
		}
		findings.add(new Finding(Source.RESET, disposition,
			disposition == Disposition.DRAINED
				? "every durable Agent Reset state is explicitly idle"
				: "Agent Reset evidence is missing, active, required, or contradictory"));
	}

	private static CoordinatorIndex coordinatorIndex(UUID clusterId, Sources sources,
		List<Finding> findings) {
		LinkedHashMap<UUID, AgentCoordinator.PersistedState> durable = new LinkedHashMap<>();
		boolean conflicting = false;
		for (AgentCoordinator.PersistedState state : sources.durableCoordinators()) {
			if (!state.ownerClusterId().equals(clusterId)) continue;
			AgentCoordinator.PersistedState old = durable.putIfAbsent(state.agentId(), state);
			conflicting |= old != null && !old.equals(state);
		}
		LinkedHashMap<UUID, AgentCoordinator.PersistedState> live = new LinkedHashMap<>();
		for (AgentCoordinator.PersistedState state : sources.liveCoordinators()) {
			if (!state.ownerClusterId().equals(clusterId)) continue;
			AgentCoordinator.PersistedState old = live.putIfAbsent(state.agentId(), state);
			conflicting |= old != null && !old.equals(state);
			AgentCoordinator.PersistedState journal = durable.get(state.agentId());
			if (journal == null || journal.agentGeneration() != state.agentGeneration()
				|| !journal.coordinatorAddress().equals(state.coordinatorAddress()))
				findings.add(new Finding(Source.COORDINATOR, Disposition.MISSING_EVIDENCE,
					"loaded Agent coordinator lacks its exact durable identity"));
		}
		if (conflicting)
			findings.add(new Finding(Source.COORDINATOR, Disposition.MISSING_EVIDENCE,
				"conflicting durable or loaded Agent coordinator identities"));
		return new CoordinatorIndex(Map.copyOf(durable), Map.copyOf(live),
			Set.copyOf(durable.keySet()));
	}

	private static void assessCoordinators(UUID clusterId, CoordinatorIndex coordinators,
		RecoveryLedger.State recovery, List<Finding> findings) {
		if (coordinators.durable().isEmpty()) {
			findings.add(new Finding(Source.COORDINATOR, Disposition.MISSING_EVIDENCE,
				"no durable Agent coordinator identifies the declared Cluster"));
			return;
		}
		Disposition disposition = Disposition.DRAINED;
		for (AgentCoordinator.PersistedState durable : coordinators.durable().values()) {
			AgentCoordinator.PersistedState effective = coordinators.live()
				.getOrDefault(durable.agentId(), durable);
			Optional<AgentProcess.ActiveContract> active = effective.activeContractState();
			if (active.isEmpty()) continue;
			Disposition proof = contractProof(clusterId, durable.agentId(),
				effective.agentGeneration(), active.orElseThrow(), recovery);
			// A loaded snapshot is current process truth. A terminal journal may only override a
			// stale unloaded coordinator snapshot written before RecoveryLedger released it.
			if (coordinators.live().containsKey(durable.agentId()))
				proof = worse(proof, Disposition.NON_DRAINED);
			disposition = worse(disposition, proof);
		}
		findings.add(new Finding(Source.COORDINATOR, disposition,
			disposition == Disposition.DRAINED
				? "all durable Agent coordinators are explicitly idle or durably released"
				: "an Agent coordinator still has an unproved contract boundary"));
	}

	private static Disposition contractProof(UUID clusterId, UUID agentId, long agentGeneration,
		AgentProcess.ActiveContract active, RecoveryLedger.State recovery) {
		List<RemoteAgentRecord> records = recovery.records().values().stream().filter(record ->
			record.targetClusterId().equals(clusterId)
				&& record.ticket().agentId().equals(agentId)
				&& record.key().agentGeneration() == agentGeneration
				&& record.key().requestId().equals(active.contractId())
				&& record.key().fence() == active.fence()).toList();
		if (records.size() == 1) return recordDisposition(records.getFirst(), recovery);
		if (records.size() > 1) return Disposition.MISSING_EVIDENCE;
		List<RecoveryLedger.AcknowledgedTombstone> tombstones = matchingTombstones(recovery,
			agentId, agentGeneration, active.contractId(), null, active.fence());
		return tombstones.size() == 1 ? outcomeDisposition(tombstones.getFirst().outcome())
			: Disposition.MISSING_EVIDENCE;
	}

	private static void assessRecovery(UUID clusterId, Set<UUID> agentIds,
		RecoveryLedger.State recovery, List<ProductionRequestTicket> pendingRuns,
		List<Finding> findings) {
		Disposition disposition = Disposition.DRAINED;
		int relevant = 0;
		for (RemoteAgentRecord record : recovery.records().values()) {
			if (!record.targetClusterId().equals(clusterId)
				&& !agentIds.contains(record.ticket().agentId())) continue;
			relevant++;
			if (!agentIds.contains(record.ticket().agentId())
				|| !record.targetClusterId().equals(clusterId))
				disposition = worse(disposition, Disposition.MISSING_EVIDENCE);
			Disposition recordState = recordDisposition(record, recovery);
			if (record.phase() == RemoteAgentPhase.QUEUED
				&& pendingRuns.stream().noneMatch(record.ticket()::equals)
				&& !recovery.pendingEffects().containsKey(record.key()))
				recordState = worse(recordState, Disposition.MISSING_EVIDENCE);
			disposition = worse(disposition, recordState);
		}
		for (RecoveryLedger.AcknowledgedTombstone tombstone
			: recovery.acknowledgedTombstones().values()) {
			RecoveryLedger.LineageHighWatermark lineage = lineage(recovery, tombstone.key());
			if (lineage == null || !agentIds.contains(lineage.agentId())) continue;
			relevant++;
			disposition = worse(disposition, outcomeDisposition(tombstone.outcome()));
		}
		for (RecoveryLedger.PendingEffect effect : recovery.pendingEffects().values()) {
			RemoteAgentRecord record = recovery.records().get(effect.key());
			if (record != null && (record.targetClusterId().equals(clusterId)
				|| agentIds.contains(record.ticket().agentId())))
				disposition = worse(disposition, Disposition.NON_DRAINED);
		}
		findings.add(new Finding(Source.RECOVERY, disposition,
			relevant == 0 ? "recovery ledger has no Agent work"
				: "audited " + relevant + " durable Agent recovery artifact(s)"));
	}

	private static Disposition recordDisposition(RemoteAgentRecord record,
		RecoveryLedger.State recovery) {
		Disposition disposition;
		if (record.condition() == RemoteAgentRecord.RecoveryCondition.UNCERTAIN_DISPATCH)
			disposition = Disposition.UNCERTAIN;
		else if (record.condition() == RemoteAgentRecord.RecoveryCondition.FAULT)
			disposition = Disposition.IRREVERSIBLE;
		else if (record.isTerminal())
			disposition = outcomeDisposition(record.terminalOutcome().orElseThrow());
		else if (record.condition() == RemoteAgentRecord.RecoveryCondition.CANCEL_DRAIN)
			disposition = Disposition.NON_DRAINED;
		else disposition = switch (record.phase()) {
			case QUEUED, RESERVED -> Disposition.NON_DRAINED;
			case DISPATCH_INTENT -> Disposition.UNCERTAIN;
			case DISPATCH_CONFIRMED, INPUT_ACTIVE, OUTPUT_DRAIN -> Disposition.IRREVERSIBLE;
			case RELEASED, RESET_REQUIRED -> throw new IllegalStateException(
				"terminal recovery phase was not terminal");
		};
		if (recovery.pendingEffects().containsKey(record.key()))
			disposition = worse(disposition, Disposition.NON_DRAINED);
		return disposition;
	}

	private static Disposition outcomeDisposition(RemoteAgentRecord.TerminalOutcome outcome) {
		return switch (outcome) {
			case COMPLETED, CANCELLED_PRE_INTENT, CANCEL_DRAINED, RETRIED_PRE_INTENT ->
				Disposition.DRAINED;
			case UNCERTAIN_DISPATCH -> Disposition.UNCERTAIN;
			case FAULT_RESET_REQUIRED -> Disposition.IRREVERSIBLE;
		};
	}

	private static void assessPending(Set<UUID> agentIds, RecoveryLedger.State recovery,
		List<ProductionRequestTicket> pendingRuns, List<Finding> findings) {
		Disposition disposition = Disposition.DRAINED;
		int relevant = 0;
		for (ProductionRequestTicket ticket : pendingRuns) {
			if (!agentIds.contains(ticket.agentId())) continue;
			relevant++;
			long exact = recovery.records().values().stream()
				.filter(record -> record.ticket().equals(ticket)).count();
			disposition = worse(disposition, exact == 1
				? Disposition.NON_DRAINED : Disposition.MISSING_EVIDENCE);
		}
		findings.add(new Finding(Source.PENDING_RUN, disposition,
			relevant == 0 ? "pending-run queue has no Agent work"
				: "audited " + relevant + " queued Agent ticket(s)"));
	}

	private static void assessNative(UUID clusterId, Set<UUID> agentIds,
		RecoveryLedger.State recovery, List<AgentInputContract.State> inputs,
		List<ProductionOutputContract.Snapshot> outputs, List<Finding> findings) {
		Disposition disposition = Disposition.DRAINED;
		int relevant = 0;
		for (AgentInputContract.State input : inputs) {
			if (!agentIds.contains(input.agentId())) continue;
			relevant++;
			Disposition exact = nativeRecoveryDisposition(clusterId, input.agentId(),
				input.agentResetGeneration(), input.run().runId(), input.run().fence(), recovery);
			if (exact == Disposition.DRAINED) continue;
			if (exact == Disposition.MISSING_EVIDENCE && input.phase() == AgentInputPhase.DISPATCH_INTENT)
				exact = Disposition.UNCERTAIN;
			else if (exact != Disposition.MISSING_EVIDENCE) {
				Disposition physical = input.cancellationDrain() ? Disposition.NON_DRAINED
					: switch (input.phase()) {
						case QUEUED, READY -> Disposition.NON_DRAINED;
						case DISPATCH_INTENT -> Disposition.UNCERTAIN;
						case DISPATCH_CONFIRMED, INPUT_CLOSED, INPUT_ENDED,
							INPUT_STALLED, RESET_REQUIRED -> Disposition.IRREVERSIBLE;
					};
				exact = worse(exact, physical);
			}
			disposition = worse(disposition, exact);
		}
		for (ProductionOutputContract.Snapshot output : outputs) {
			if (!agentIds.contains(output.agentId())) continue;
			relevant++;
			Disposition exact = nativeRecoveryDisposition(clusterId, output.agentId(),
				output.agentResetGeneration(), output.runId(), output.run().fence(), recovery);
			if (exact != Disposition.DRAINED && exact != Disposition.MISSING_EVIDENCE) {
				Disposition physical = switch (output.milestone()) {
					case WAITING_INPUT_READY -> Disposition.NON_DRAINED;
					case OBSERVING, OUTPUT_SEALED, OUTPUT_DISPATCHED, OUTPUT_RECEIVED, FAILED ->
						Disposition.IRREVERSIBLE;
				};
				exact = worse(exact, physical);
			}
			disposition = worse(disposition, exact);
		}
		findings.add(new Finding(Source.NATIVE_DISPATCH, disposition,
			relevant == 0 ? "native contract journals have no Agent work"
				: "audited " + relevant + " native Agent contract artifact(s)"));
	}

	private static Disposition nativeRecoveryDisposition(UUID clusterId, UUID agentId,
		long agentGeneration, UUID runId, long fence, RecoveryLedger.State recovery) {
		List<RemoteAgentRecord> records = recovery.records().values().stream().filter(record ->
			record.ticket().agentId().equals(agentId)
				&& record.key().agentGeneration() == agentGeneration
				&& record.key().runId().equals(runId) && record.key().fence() == fence).toList();
		if (records.size() == 1) {
			RemoteAgentRecord record = records.getFirst();
			if (!record.targetClusterId().equals(clusterId)) return Disposition.MISSING_EVIDENCE;
			return recordDisposition(record, recovery);
		}
		if (records.size() > 1) return Disposition.MISSING_EVIDENCE;
		List<RecoveryLedger.AcknowledgedTombstone> tombstones = matchingTombstones(recovery,
			agentId, agentGeneration, null, runId, fence);
		return tombstones.size() == 1 ? outcomeDisposition(tombstones.getFirst().outcome())
			: Disposition.MISSING_EVIDENCE;
	}

	private static Map<UUID, RadioDispatchEvidence> assessDispatches(UUID clusterId,
		Set<UUID> agentIds, RecoveryLedger.State recovery,
		List<RadioDispatchEvidence> dispatches, List<Finding> findings) {
		LinkedHashMap<UUID, RadioDispatchEvidence> relevantByMessage = new LinkedHashMap<>();
		Disposition disposition = Disposition.DRAINED;
		for (RadioDispatchEvidence dispatch : dispatches) {
			boolean ownedAgent = agentIds.contains(dispatch.agentId());
			boolean targetsCluster = dispatch.envelope().targetClusterId().equals(clusterId);
			if (!ownedAgent && !targetsCluster) continue;
			RadioDispatchEvidence old = relevantByMessage.putIfAbsent(dispatch.messageId(), dispatch);
			if (old != null && !old.equals(dispatch)) {
				disposition = worse(disposition, Disposition.MISSING_EVIDENCE);
				continue;
			}
			if (!ownedAgent || !targetsCluster
				|| !dispatch.intent().clusterId().equals(dispatch.envelope().sourceClusterId())) {
				disposition = worse(disposition, Disposition.MISSING_EVIDENCE);
				continue;
			}
			if (!dispatch.committed()) {
				disposition = worse(disposition, Disposition.NON_DRAINED);
				continue;
			}
			disposition = worse(disposition,
				dispatchRecoveryDisposition(clusterId, dispatch, recovery));
		}
		findings.add(new Finding(Source.REMOTE_DISPATCH, disposition,
			relevantByMessage.isEmpty() ? "remote dispatch journal has no target Agent work"
				: "audited " + relevantByMessage.size() + " target Agent dispatch artifact(s)"));
		return Map.copyOf(relevantByMessage);
	}

	private static Disposition dispatchRecoveryDisposition(UUID clusterId,
		RadioDispatchEvidence dispatch, RecoveryLedger.State recovery) {
		ProductionEffect.CreateDispatch intent = dispatch.intent();
		List<RemoteAgentRecord> records = recovery.records().values().stream().filter(record ->
			record.key().requestId().equals(intent.intentId())
				&& record.key().runId().equals(intent.runId())
				&& record.key().fence() == intent.generation()
				&& record.key().agentGeneration() == dispatch.agentGeneration()).toList();
		if (records.size() == 1) {
			RemoteAgentRecord record = records.getFirst();
			if (!record.targetClusterId().equals(clusterId)
				|| !record.ticket().agentId().equals(dispatch.agentId())
				|| !record.ticket().sourceClusterId().equals(intent.clusterId())
				|| !record.ticket().targetRoute().equals(intent.route()))
				return Disposition.MISSING_EVIDENCE;
			return recordDisposition(record, recovery);
		}
		if (records.size() > 1) return Disposition.MISSING_EVIDENCE;
		List<RecoveryLedger.AcknowledgedTombstone> tombstones = matchingTombstones(recovery,
			dispatch.agentId(), dispatch.agentGeneration(), intent.intentId(), intent.runId(),
			intent.generation()).stream().filter(tombstone -> {
				RecoveryLedger.LineageHighWatermark lineage = lineage(recovery, tombstone.key());
				return lineage != null && lineage.terminalAcknowledged()
					&& lineage.sourceClusterId().equals(intent.clusterId())
					&& lineage.targetRoute().equals(intent.route());
			}).toList();
		return tombstones.size() == 1 ? outcomeDisposition(tombstones.getFirst().outcome())
			: Disposition.MISSING_EVIDENCE;
	}

	private static List<RecoveryLedger.AcknowledgedTombstone> matchingTombstones(
		RecoveryLedger.State recovery, UUID agentId, long agentGeneration,
		UUID requestId, UUID runId, long fence) {
		return recovery.acknowledgedTombstones().values().stream().filter(tombstone -> {
			RemoteRequestKey key = tombstone.key();
			if (requestId != null && !key.requestId().equals(requestId)) return false;
			if (runId != null && !key.runId().equals(runId)) return false;
			if (key.fence() != fence || key.agentGeneration() != agentGeneration) return false;
			RecoveryLedger.LineageHighWatermark lineage = lineage(recovery, key);
			return lineage != null && lineage.key().equals(key) && lineage.terminalAcknowledged()
				&& lineage.agentId().equals(agentId);
		}).toList();
	}

	private static RecoveryLedger.LineageHighWatermark lineage(RecoveryLedger.State recovery,
		RemoteRequestKey key) {
		return recovery.lineages().get(new RecoveryLedger.LineageId(key.requestId(), key.runId()));
	}

	private static void assessOutbox(UUID clusterId,
		Map<UUID, RadioDispatchEvidence> relevantDispatches, DurableRadioOutbox.State outbox,
		List<Finding> findings) {
		Disposition disposition = Disposition.DRAINED;
		HashSet<UUID> consumed = new HashSet<>();
		for (RadioDispatchEvidence dispatch : relevantDispatches.values()) {
			DurableRadioOutbox.Entry entry = outbox.entries().get(dispatch.messageId());
			if (entry == null) {
				if (!dispatch.committed())
					disposition = worse(disposition, Disposition.NON_DRAINED);
				continue;
			}
			consumed.add(dispatch.messageId());
			if (!entry.envelope().equals(dispatch.envelope())
				|| !entry.relays().equals(dispatch.relays())) {
				disposition = worse(disposition, Disposition.MISSING_EVIDENCE);
				continue;
			}
			if (!dispatch.committed())
				disposition = worse(disposition, Disposition.NON_DRAINED);
			else if (entry.status() != RadioRouter.RouteStatus.DELIVERED)
				disposition = worse(disposition, Disposition.MISSING_EVIDENCE);
		}
		for (Map.Entry<UUID, DurableRadioOutbox.Entry> outboxEntry : outbox.entries().entrySet()) {
			if (consumed.contains(outboxEntry.getKey())) continue;
			RadioEnvelope envelope = outboxEntry.getValue().envelope();
			if (!envelope.targetClusterId().equals(clusterId)) continue;
			if (envelope.payload() instanceof ProcessProtocolMessage.Data data
				&& data.value().startsWith("agent-dispatch:"))
				disposition = worse(disposition, Disposition.MISSING_EVIDENCE);
		}
		findings.add(new Finding(Source.OUTBOX, disposition,
			disposition == Disposition.DRAINED
				? "Agent outbox evidence is absent or in the committed-delivered crash window"
				: "Agent outbox evidence is pending, orphaned, or contradictory"));
	}

	private static Disposition worse(Disposition left, Disposition right) {
		return severity(left) >= severity(right) ? left : right;
	}

	private static int severity(Disposition disposition) {
		return switch (disposition) {
			case DRAINED -> 0;
			case MISSING_EVIDENCE -> 1;
			case NON_DRAINED -> 2;
			case IRREVERSIBLE -> 3;
			case UNCERTAIN -> 4;
		};
	}

	private record CoordinatorIndex(Map<UUID, AgentCoordinator.PersistedState> durable,
		Map<UUID, AgentCoordinator.PersistedState> live, Set<UUID> agentIds) {}
}
