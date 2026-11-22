package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ChildExecutionRevision;

/** Receiver-produced revision of the source-owned expansion child lineage. */
public record RemoteExpansionRevision(UUID patternId, int patternVersion, UUID runId,
	long runFence, UUID parentProcessId, long parentProcessGeneration,
	long parentStateGeneration, ChildExecutionRevision childRevision,
	String expandedManifestFingerprint) {
	public RemoteExpansionRevision {
		patternId = Objects.requireNonNull(patternId, "patternId");
		runId = Objects.requireNonNull(runId, "runId");
		parentProcessId = Objects.requireNonNull(parentProcessId, "parentProcessId");
		childRevision = Objects.requireNonNull(childRevision, "childRevision");
		expandedManifestFingerprint = boundedDigest(expandedManifestFingerprint);
		if (patternVersion != 2 || runFence < 0 || parentProcessGeneration < 0
			|| parentStateGeneration < 0)
			throw new IllegalArgumentException("invalid expansion revision fences");
	}

	public static RemoteExpansionRevision compute(RemoteExpansionRequest request) {
		request = Objects.requireNonNull(request, "request");
		UUID child = logicalChildId(request);
		ChildExecutionRevision lineage = new ChildExecutionRevision(child,
			request.parentProcessGeneration(), request.parentStateGeneration(), request.inputMode(),
			request.inputMode(), request.runMultiplier(), request.runMultiplier(), 0);
		String fingerprint = RemoteExpansionDigest.expansion(request.expandedStages(),
			request.expandedManifest(), request.exactStackSnapshot());
		return new RemoteExpansionRevision(request.patternId(), request.patternVersion(),
			request.runId(), request.runFence(), request.parentProcessId(),
			request.parentProcessGeneration(), request.parentStateGeneration(), lineage, fingerprint);
	}

	/**
	 * EXACT Runs are serial units of one logical Pattern expansion. Their Run ids fence protocol
	 * messages, but must not create permanent sibling lineage entries in the source Process.
	 */
	public static UUID logicalChildId(RemoteExpansionRequest request) {
		request = Objects.requireNonNull(request, "request");
		String source = request.sourceGoalId() + "/" + request.parentProcessId() + "/"
			+ request.patternId() + "/" + request.patternVersion();
		String identity = request.inputMode() == com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode.EXACT
			? source + "/EXACT"
			: source + "/RUN/" + request.runId();
		return UUID.nameUUIDFromBytes(("remote-expansion-child/" + identity)
			.getBytes(StandardCharsets.UTF_8));
	}

	public boolean matches(RemoteExpansionRequest request) {
		if (request == null) return false;
		RemoteExpansionRevision expected = compute(request);
		return equals(expected);
	}

	public String fingerprint() {
		return patternId + "|" + patternVersion + "|" + runId + "|" + runFence + "|"
			+ parentProcessId + "|" + parentProcessGeneration + "|" + parentStateGeneration
			+ "|" + childRevision + "|" + expandedManifestFingerprint;
	}

	private static String boundedDigest(String value) {
		value = Objects.requireNonNull(value, "expandedManifestFingerprint");
		if (value.length() != 64 || !value.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("invalid expansion digest");
		return value;
	}
}
