package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.Objects;
import java.util.Optional;

import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;

/** Idempotent remote Agent ticket protocol carried over the existing radio envelope. */
public sealed interface AgentProtocolMessage extends ProcessProtocolMessage
	permits AgentProtocolMessage.Offer, AgentProtocolMessage.StatusQuery,
	AgentProtocolMessage.Status, AgentProtocolMessage.Cancel, AgentProtocolMessage.Terminal,
	AgentProtocolMessage.EvidenceAck {
	RemoteRequestKey key();

	record Offer(RemoteRequestKey key, ProductionRequestTicket ticket)
		implements AgentProtocolMessage {
		public Offer {
			key = Objects.requireNonNull(key, "key");
			ticket = Objects.requireNonNull(ticket, "ticket");
			if (!key.requestId().equals(ticket.requestId())
				|| !key.runId().equals(ticket.runId()) || key.fence() != ticket.fence())
				throw new IllegalArgumentException("Offer key differs from its immutable ticket");
		}
		@Override public String fingerprintMaterial() {
			return "agent-offer|" + key + "|" + ticket.requestId() + "|" + ticket.runId()
				+ "|" + ticket.fence() + "|" + ticket.sourceClusterId() + "|"
				+ ticket.agentId() + "|" + ticket.targetRoute() + "|" + ticket.patternId()
				+ "|" + ticket.mode() + "|" + ticket.multiplier() + "|"
				+ ticket.enqueueSequence() + "|" + ticket.requestedQueuePriority() + "|"
				+ ticket.sourceMaterialSnapshotGeneration();
		}
	}

	record StatusQuery(RemoteRequestKey key) implements AgentProtocolMessage {
		public StatusQuery { key = Objects.requireNonNull(key, "key"); }
		@Override public String fingerprintMaterial() { return "agent-status-query|" + key; }
	}

	record Status(RemoteRequestKey key, StatusCode code, Optional<RemoteAgentRecord> record)
		implements AgentProtocolMessage {
		public Status {
			key = Objects.requireNonNull(key, "key");
			code = Objects.requireNonNull(code, "code");
			record = Objects.requireNonNull(record, "record");
			if (record.isPresent() != (code == StatusCode.PRESENT)
				|| record.isPresent() && !record.orElseThrow().key().equals(key))
				throw new IllegalArgumentException("Status code and exact record disagree");
		}
		@Override public String fingerprintMaterial() {
			return "agent-status|" + key + "|" + code + "|"
				+ record.map(RemoteAgentRecord::evidenceIdentity).orElse("missing");
		}
	}

	record Cancel(RemoteRequestKey key) implements AgentProtocolMessage {
		public Cancel { key = Objects.requireNonNull(key, "key"); }
		@Override public String fingerprintMaterial() { return "agent-cancel|" + key; }
	}

	record Terminal(RemoteRequestKey key, RemoteAgentRecord record)
		implements AgentProtocolMessage {
		public Terminal {
			key = Objects.requireNonNull(key, "key");
			record = Objects.requireNonNull(record, "record");
			if (!record.key().equals(key) || !record.isTerminal())
				throw new IllegalArgumentException("Terminal message needs exact terminal evidence");
		}
		@Override public String fingerprintMaterial() {
			return "agent-terminal|" + record.evidenceIdentity();
		}
	}

	record EvidenceAck(RemoteRequestKey key, long revision) implements AgentProtocolMessage {
		public EvidenceAck {
			key = Objects.requireNonNull(key, "key");
			if (revision < 0) throw new IllegalArgumentException("ACK revision cannot be negative");
		}
		@Override public String fingerprintMaterial() {
			return "agent-evidence-ack|" + key + "|" + revision;
		}
	}

	enum StatusCode {
		PRESENT,
		REMOTE_RECOVERY,
		STALE_FENCE,
		STALE_SOURCE_GENERATION,
		STALE_AGENT_GENERATION,
		ACKNOWLEDGED_TOMBSTONE,
		LINEAGE_MUTATION_REJECTED
	}
}
