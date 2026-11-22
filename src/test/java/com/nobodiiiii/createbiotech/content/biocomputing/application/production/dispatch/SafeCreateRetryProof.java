package com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.DispatchManifest;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputPhase;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateDispatchCommand;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway;

/**
 * Durable evidence for the exact Create intent proven to have moved no material. Only the trusted
 * production application uses this record; it is not a player value or a world-operation capability.
 * Current live authority must still pass {@link ProductionSafeRetryRecovery#authorize}.
 */
public record SafeCreateRetryProof(ProductionEffect.CreateDispatch intent, CreateDispatchCommand command,
	CreateLogisticsGateway.Rejection rejection) {
	public SafeCreateRetryProof {
		intent = Objects.requireNonNull(intent, "intent");
		command = Objects.requireNonNull(command, "command");
		rejection = Objects.requireNonNull(rejection, "rejection");
		if (!intent.intentId().equals(command.intentId())
			|| !intent.runId().equals(command.runId())
			|| !intent.route().equals(command.route())
			|| intent.generation() != command.fence()
			|| intent.generation() != command.sourceInventorySnapshotGeneration()
			|| switch (rejection) {
				case PACKAGER_UNLOADED, SOURCE_SNAPSHOT_MISMATCH,
					NATIVE_DISPATCH_REJECTED -> false;
				default -> true;
			})
			throw new IllegalArgumentException("invalid safe Create retry authority");
	}

	/** The rejection is mutable diagnostic status, not part of material authority identity. */
	public boolean sameAuthority(SafeCreateRetryProof other) {
		return other != null && intent.equals(other.intent) && command.equals(other.command);
	}

	public static Optional<SafeCreateRetryProof> from(ProductionEffect.CreateDispatch intent,
		CreateLogisticsGateway.PersistedCommit persisted) {
		intent = Objects.requireNonNull(intent, "intent");
		persisted = Objects.requireNonNull(persisted, "persisted");
		if (!persisted.allowsRetryWithoutMaterial()) return Optional.empty();
		CreateDispatchCommand command = persisted.command();
		CreateLogisticsGateway.CommitResult result = persisted.result().orElseThrow();
		if (!command.intentId().equals(intent.intentId())
			|| !command.runId().equals(intent.runId())
			|| !command.route().equals(intent.route())
			|| command.fence() != intent.generation()
			|| command.sourceInventorySnapshotGeneration() != intent.generation())
			return Optional.empty();
		try {
			if (!command.manifest().equals(DispatchManifest.decode(intent.ingredients())))
				return Optional.empty();
		} catch (IllegalArgumentException | ArithmeticException invalid) {
			return Optional.empty();
		}
		return Optional.of(new SafeCreateRetryProof(intent, command, result.rejection()));
	}

	/**
	 * Repairs only the crash window in which Create durably proved that no material moved while
	 * the provisional Agent admission was only partly compensated. The gateway artifact is the
	 * typed no-material proof; an input contract or recovery reservation, when present, must still
	 * be the exact pre-confirmation lineage for that same immutable command.
	 */
	public static Optional<SafeCreateRetryProof> repair(
		ProductionEffect.CreateDispatch intent, CreateLogisticsGateway.PersistedCommit persisted,
		Optional<AgentInputContract.State> inputState, RecoveryLedger.State recoveryState) {
		intent = Objects.requireNonNull(intent, "intent");
		persisted = Objects.requireNonNull(persisted, "persisted");
		inputState = Objects.requireNonNull(inputState, "inputState");
		recoveryState = Objects.requireNonNull(recoveryState, "recoveryState");
		Optional<SafeCreateRetryProof> retry = from(intent, persisted);
		if (retry.isEmpty()) return Optional.empty();
		ProductionEffect.CreateDispatch exactIntent = intent;
		CreateDispatchCommand command = retry.orElseThrow().command();

		if (inputState.isPresent()) {
			AgentInputContract.State input = inputState.orElseThrow();
			if (input.phase() != AgentInputPhase.DISPATCH_INTENT
				|| input.currentCommand().filter(command::equals).isEmpty()
				|| input.nativeOrders().isPresent() || input.cancellationDrain()
				|| !input.leaseRetained()
				|| !input.agentId().equals(command.agentId())
				|| input.agentResetGeneration() != command.agentResetGeneration()
				|| !input.outputObservationEpoch().equals(command.outputObservationEpoch())
				|| !input.run().runId().equals(intent.runId())
				|| input.run().fence() != intent.generation()
				|| input.stageIndex() != command.stageIndex()
				|| input.stageIndex() >= input.stages().size()
				|| !input.stages().get(input.stageIndex()).route().equals(command.route())
				|| !input.stages().get(input.stageIndex()).manifest().equals(command.manifest()))
				return Optional.empty();
		}

		List<Map.Entry<RemoteRequestKey, RemoteAgentRecord>> runRecords = recoveryState.records()
			.entrySet().stream().filter(entry -> entry.getKey().runId().equals(exactIntent.runId()))
			.toList();
		if (runRecords.size() > 1 || inputState.isEmpty() && !runRecords.isEmpty())
			return Optional.empty();
		if (!runRecords.isEmpty()) {
			Map.Entry<RemoteRequestKey, RemoteAgentRecord> entry = runRecords.getFirst();
			RemoteRequestKey key = entry.getKey();
			RemoteAgentRecord record = entry.getValue();
			ProductionRequestTicket ticket = record.ticket();
			if (!key.requestId().equals(intent.intentId())
				|| key.fence() != intent.generation()
				|| key.agentGeneration() != command.agentResetGeneration()
				|| record.crossedDispatchIntent() || record.isTerminal()
				|| record.inputState().isPresent() || record.outputState().isPresent()
				|| !ticket.sourceClusterId().equals(intent.clusterId())
				|| !ticket.agentId().equals(command.agentId())
				|| !ticket.targetRoute().equals(intent.route())
				|| ticket.sourceMaterialSnapshotGeneration() != intent.generation())
				return Optional.empty();
		}
		if (recoveryState.acknowledgedTombstones().keySet().stream()
			.anyMatch(key -> key.runId().equals(exactIntent.runId()))) return Optional.empty();
		List<RecoveryLedger.LineageHighWatermark> runLineages = recoveryState.lineages().values()
			.stream().filter(lineage -> lineage.key().runId().equals(exactIntent.runId())).toList();
		if (runLineages.size() != runRecords.size()
			|| !runRecords.isEmpty()
				&& !runLineages.getFirst().key().equals(runRecords.getFirst().getKey()))
			return Optional.empty();
		return retry;
	}
}
