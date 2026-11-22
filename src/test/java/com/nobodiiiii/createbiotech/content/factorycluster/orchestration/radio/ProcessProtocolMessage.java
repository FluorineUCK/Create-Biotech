package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Bounded immutable payloads used by radio delivery and remote computation fencing. */
public sealed interface ProcessProtocolMessage permits ProcessProtocolMessage.Data,
	ProcessProtocolMessage.LeaseOffer, ProcessProtocolMessage.LeaseRevision,
	ProcessProtocolMessage.RevisionAcknowledgement, ProcessProtocolMessage.LeaseResult,
	AgentProtocolMessage, RecoveryProtocolMessage {
	int MAX_TEXT_BYTES = 4096;

	String fingerprintMaterial();

	default String fingerprint() {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(fingerprintMaterial().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
		}
	}

	record Data(String value) implements ProcessProtocolMessage {
		public Data { value = bounded(value, "value"); }
		@Override public String fingerprintMaterial() { return "data|" + value; }
	}

	record LeaseOffer(RemoteProcessLease lease) implements ProcessProtocolMessage {
		public LeaseOffer { lease = Objects.requireNonNull(lease, "lease"); }
		@Override public String fingerprintMaterial() { return "offer|" + lease.fingerprintMaterial(); }
	}

	record LeaseRevision(UUID leaseId, long revision, RemoteExpansionRevision calculationState)
		implements ProcessProtocolMessage {
		public LeaseRevision {
			leaseId = Objects.requireNonNull(leaseId, "leaseId");
			if (revision < 0) throw new IllegalArgumentException("revision cannot be negative");
			calculationState = Objects.requireNonNull(calculationState, "calculationState");
		}
		@Override public String fingerprintMaterial() {
			return "revision|" + leaseId + "|" + revision + "|" + calculationState.fingerprint();
		}
	}

	record RevisionAcknowledgement(UUID leaseId, long revision)
		implements ProcessProtocolMessage {
		public RevisionAcknowledgement {
			leaseId = Objects.requireNonNull(leaseId, "leaseId");
			if (revision < 0) throw new IllegalArgumentException("revision cannot be negative");
		}
		@Override public String fingerprintMaterial() { return "ack|" + leaseId + "|" + revision; }
	}

	record LeaseResult(UUID leaseId, long revision, RemoteExpansionResult result)
		implements ProcessProtocolMessage {
		public LeaseResult {
			leaseId = Objects.requireNonNull(leaseId, "leaseId");
			if (revision < 0) throw new IllegalArgumentException("revision cannot be negative");
			result = Objects.requireNonNull(result, "result");
		}
		@Override public String fingerprintMaterial() {
			return "result|" + leaseId + "|" + revision + "|" + result.fingerprint();
		}
	}

	private static String bounded(String value, String name) {
		value = Objects.requireNonNull(value, name);
		if (value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES)
			throw new IllegalArgumentException(name + " exceeds the protocol bound");
		return value;
	}
}
