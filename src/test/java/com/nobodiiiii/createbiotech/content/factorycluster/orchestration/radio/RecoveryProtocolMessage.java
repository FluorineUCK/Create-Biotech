package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.Objects;

import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;

/**
 * Recovery traffic whose logical contract generations are independent from radio membership
 * generations. Every message carries the exact request-direction radio peer fence, so the router
 * validates both identities without conflating those two generation domains.
 */
public sealed interface RecoveryProtocolMessage extends ProcessProtocolMessage
	permits RecoveryProtocolMessage.Offer, RecoveryProtocolMessage.Terminal,
	RecoveryProtocolMessage.StatusQuery, RecoveryProtocolMessage.EvidenceAck {
	RemoteRequestKey key();
	PeerFence peerFence();
	boolean reverseDirection();

	default void validateEnvelope(RadioEnvelope envelope) {
		Objects.requireNonNull(envelope, "envelope");
		RadioEndpoint.Hop expectedSource = reverseDirection()
			? peerFence().requestTarget() : peerFence().requestSource();
		RadioEndpoint.Hop expectedTarget = reverseDirection()
			? peerFence().requestSource() : peerFence().requestTarget();
		if (!expectedSource.equals(envelope.sourceHop())
			|| !expectedTarget.equals(envelope.targetHop()))
			throw new IllegalArgumentException("recovery protocol radio peer fence mismatch");
	}

	record PeerFence(RadioEndpoint.Hop requestSource, RadioEndpoint.Hop requestTarget) {
		public PeerFence {
			requestSource = Objects.requireNonNull(requestSource, "requestSource");
			requestTarget = Objects.requireNonNull(requestTarget, "requestTarget");
			if (requestSource.key().equals(requestTarget.key()))
				throw new IllegalArgumentException("recovery peers must be distinct");
		}
		String fingerprintMaterial() { return requestSource + "|" + requestTarget; }
	}

	record Offer(RemoteRequestKey key, PeerFence peerFence, ProductionRequestTicket ticket)
		implements RecoveryProtocolMessage {
		public Offer {
			key = Objects.requireNonNull(key, "key");
			peerFence = Objects.requireNonNull(peerFence, "peerFence");
			ticket = Objects.requireNonNull(ticket, "ticket");
			if (!key.requestId().equals(ticket.requestId()) || !key.runId().equals(ticket.runId())
				|| key.fence() != ticket.fence()
				|| !ticket.sourceClusterId().equals(peerFence.requestSource().key().clusterId()))
				throw new IllegalArgumentException("recovery offer identity mismatch");
		}
		@Override public boolean reverseDirection() { return false; }
		@Override public String fingerprintMaterial() {
			return "recovery-offer|" + key + "|" + peerFence.fingerprintMaterial() + "|" + ticket;
		}
	}

	record Terminal(RemoteRequestKey key, PeerFence peerFence, RemoteAgentRecord record)
		implements RecoveryProtocolMessage {
		public Terminal {
			key = Objects.requireNonNull(key, "key");
			peerFence = Objects.requireNonNull(peerFence, "peerFence");
			record = Objects.requireNonNull(record, "record");
			if (!record.key().equals(key) || !record.isTerminal())
				throw new IllegalArgumentException("terminal recovery evidence mismatch");
		}
		@Override public boolean reverseDirection() { return true; }
		@Override public String fingerprintMaterial() {
			return "recovery-terminal|" + key + "|" + peerFence.fingerprintMaterial() + "|"
				+ record.revision() + "|" + record.evidenceIdentity();
		}
	}

	record StatusQuery(RemoteRequestKey key, PeerFence peerFence)
		implements RecoveryProtocolMessage {
		public StatusQuery {
			key = Objects.requireNonNull(key, "key");
			peerFence = Objects.requireNonNull(peerFence, "peerFence");
		}
		@Override public boolean reverseDirection() { return false; }
		@Override public String fingerprintMaterial() {
			return "recovery-status-query|" + key + "|" + peerFence.fingerprintMaterial();
		}
	}

	record EvidenceAck(RemoteRequestKey key, PeerFence peerFence, long revision)
		implements RecoveryProtocolMessage {
		public EvidenceAck {
			key = Objects.requireNonNull(key, "key");
			peerFence = Objects.requireNonNull(peerFence, "peerFence");
			if (revision < 0) throw new IllegalArgumentException("negative evidence revision");
		}
		@Override public boolean reverseDirection() { return false; }
		@Override public String fingerprintMaterial() {
			return "recovery-evidence-ack|" + key + "|" + peerFence.fingerprintMaterial()
				+ "|" + revision;
		}
	}
}
