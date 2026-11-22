package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCoordinator;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResetService;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;

/**
 * One auditable, server-authoritative view of whether an Agent service may be revoked.
 * Every collection in {@link Sources} is a complete durable production snapshot, so an empty
 * source is explicit drained evidence. The Agent identity/coordinator source is the exception:
 * an AGENT declaration without a durable coordinator cannot prove which work belongs to it and
 * therefore fails closed.
 */
public record AgentRevocationDrainState(UUID clusterId, Disposition disposition,
	List<Finding> findings) {
	public AgentRevocationDrainState {
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		disposition = Objects.requireNonNull(disposition, "disposition");
		findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
		if (findings.isEmpty()) throw new IllegalArgumentException("drain audit needs findings");
	}

	public boolean safeToRevoke() { return disposition == Disposition.DRAINED; }

	public static AgentRevocationDrainState assess(UUID clusterId, Sources sources) {
		return AgentRevocationDrainAudit.assess(Objects.requireNonNull(clusterId, "clusterId"),
			Objects.requireNonNull(sources, "sources"));
	}

	public enum Source {
		COORDINATOR,
		RESET,
		RECOVERY,
		PENDING_RUN,
		NATIVE_DISPATCH,
		REMOTE_DISPATCH,
		OUTBOX
	}

	public enum Disposition {
		DRAINED,
		MISSING_EVIDENCE,
		NON_DRAINED,
		IRREVERSIBLE,
		UNCERTAIN
	}

	public record Finding(Source source, Disposition disposition, String detail) {
		public Finding {
			source = Objects.requireNonNull(source, "source");
			disposition = Objects.requireNonNull(disposition, "disposition");
			detail = Objects.requireNonNull(detail, "detail");
			if (detail.isBlank()) throw new IllegalArgumentException("blank drain finding");
		}
	}

	/** Exact normalized view of the production server's durable dispatch record. */
	public record RadioDispatchEvidence(UUID messageId,
		com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect.CreateDispatch intent,
		UUID agentId, long agentGeneration,
		com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope envelope,
		List<com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint.Hop> relays,
		boolean committed) {
		public RadioDispatchEvidence {
			messageId = Objects.requireNonNull(messageId, "messageId");
			intent = Objects.requireNonNull(intent, "intent");
			agentId = Objects.requireNonNull(agentId, "agentId");
			envelope = Objects.requireNonNull(envelope, "envelope");
			relays = List.copyOf(Objects.requireNonNull(relays, "relays"));
			if (agentGeneration < 0 || !messageId.equals(envelope.messageId())
				|| !(envelope.payload() instanceof ProcessProtocolMessage.Data data)
				|| !data.value().equals("agent-dispatch:" + intent.intentId()))
				throw new IllegalArgumentException("invalid Agent radio dispatch evidence");
		}
	}

	public record Sources(List<AgentCoordinator.PersistedState> durableCoordinators,
		List<AgentCoordinator.PersistedState> liveCoordinators,
		List<AgentResetService.State> durableResets,
		List<AgentResetService.State> liveResets,
		RecoveryLedger.State recovery, List<ProductionRequestTicket> pendingRuns,
		List<AgentInputContract.State> inputContracts,
		List<ProductionOutputContract.Snapshot> outputContracts,
		List<RadioDispatchEvidence> radioDispatches, DurableRadioOutbox.State outbox) {
		public Sources(List<AgentCoordinator.PersistedState> durableCoordinators,
			List<AgentResetService.State> durableResets,
			RecoveryLedger.State recovery, List<ProductionRequestTicket> pendingRuns,
			List<AgentInputContract.State> inputContracts,
			List<ProductionOutputContract.Snapshot> outputContracts,
			List<RadioDispatchEvidence> radioDispatches, DurableRadioOutbox.State outbox) {
			this(durableCoordinators, List.of(), durableResets, List.of(), recovery, pendingRuns,
				inputContracts, outputContracts, radioDispatches, outbox);
		}
		public Sources {
			durableCoordinators = List.copyOf(Objects.requireNonNull(durableCoordinators,
				"durableCoordinators"));
			liveCoordinators = List.copyOf(Objects.requireNonNull(liveCoordinators,
				"liveCoordinators"));
			durableResets = List.copyOf(Objects.requireNonNull(durableResets,
				"durableResets"));
			liveResets = List.copyOf(Objects.requireNonNull(liveResets, "liveResets"));
			recovery = Objects.requireNonNull(recovery, "recovery");
			pendingRuns = List.copyOf(Objects.requireNonNull(pendingRuns, "pendingRuns"));
			inputContracts = List.copyOf(Objects.requireNonNull(inputContracts, "inputContracts"));
			outputContracts = List.copyOf(Objects.requireNonNull(outputContracts, "outputContracts"));
			radioDispatches = List.copyOf(Objects.requireNonNull(radioDispatches, "radioDispatches"));
			outbox = Objects.requireNonNull(outbox, "outbox");
		}
	}
}
