package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCoordinator;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResetService;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.AgentRevocationDrainState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;

import net.minecraft.core.HolderLookup;

/**
 * Production bridge from the complete current-schema journal namespaces to the revocation audit.
 * Durable contract and dispatch records are deliberately re-read for every command instead of
 * being inferred from resident maps, so an unloaded record that has not been rehydrated remains
 * authoritative.
 */
public final class ProductionAgentRevocationEvidenceAdapter {
	private final FactoryOrchestrationSavedData journal;
	private final HolderLookup.Provider registries;
	private final Supplier<RecoveryLedger.State> recovery;
	private final Supplier<List<ProductionRequestTicket>> pendingRuns;
	private final Supplier<List<AgentCoordinator.PersistedState>> liveCoordinators;
	private final Supplier<List<AgentResetService.State>> liveResets;

	public ProductionAgentRevocationEvidenceAdapter(FactoryOrchestrationSavedData journal,
		HolderLookup.Provider registries, Supplier<RecoveryLedger.State> recovery,
		Supplier<List<ProductionRequestTicket>> pendingRuns,
		Supplier<List<AgentCoordinator.PersistedState>> liveCoordinators,
		Supplier<List<AgentResetService.State>> liveResets) {
		this.journal = Objects.requireNonNull(journal, "journal");
		this.registries = Objects.requireNonNull(registries, "registries");
		this.recovery = Objects.requireNonNull(recovery, "recovery");
		this.pendingRuns = Objects.requireNonNull(pendingRuns, "pendingRuns");
		this.liveCoordinators = Objects.requireNonNull(liveCoordinators, "liveCoordinators");
		this.liveResets = Objects.requireNonNull(liveResets, "liveResets");
	}

	public AgentRevocationDrainState query(UUID clusterId) {
		UUID exactCluster = Objects.requireNonNull(clusterId, "clusterId");
		List<AgentRevocationDrainState.RadioDispatchEvidence> dispatches = journal.readPrefix(
			FactoryOrchestrationServer.REMOTE_AGENT_DISPATCH_PREFIX,
			FactoryOrchestrationServer.RemoteAgentRadioDispatch.class, registries).values().stream()
			.map(dispatch -> new AgentRevocationDrainState.RadioDispatchEvidence(
				dispatch.messageId(), dispatch.intent(), dispatch.agentId(),
				dispatch.agentGeneration(), dispatch.envelope(), dispatch.relays(),
				dispatch.committed())).toList();
		DurableRadioOutbox.State outbox = journal.read(FactoryOrchestrationServer.RADIO_OUTBOX_KEY,
			DurableRadioOutbox.State.class, registries)
			.orElseGet(() -> new DurableRadioOutbox.State(java.util.Map.of()));
		AgentRevocationDrainState.Sources sources = new AgentRevocationDrainState.Sources(
			List.copyOf(journal.readPrefix("agent/", AgentCoordinator.PersistedState.class,
				registries).values()),
			List.copyOf(Objects.requireNonNull(liveCoordinators.get(), "live coordinators")),
			List.copyOf(journal.readPrefix("reset/", AgentResetService.State.class,
				registries).values()),
			List.copyOf(Objects.requireNonNull(liveResets.get(), "live resets")),
			Objects.requireNonNull(recovery.get(), "recovery"),
			List.copyOf(Objects.requireNonNull(pendingRuns.get(), "pending runs")),
			List.copyOf(journal.readPrefix("input_contract/", AgentInputContract.State.class,
				registries).values()),
			List.copyOf(journal.readPrefix("output_contract/", ProductionOutputContract.Snapshot.class,
				registries).values()), dispatches, outbox);
		return AgentRevocationDrainState.assess(exactCluster, sources);
	}
}
