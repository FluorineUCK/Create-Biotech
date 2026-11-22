package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.Objects;
import java.util.UUID;

/** Immutable exact-recipient envelope; routing creates a new value for every hop. */
public record RadioEnvelope(UUID messageId, UUID sourceClusterId, UUID sourceMemberId,
	UUID targetClusterId, UUID targetMemberId, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency, long hopSequence,
	long sourceGeneration, long targetGeneration, String payloadFingerprint,
	ProcessProtocolMessage payload, SubnetRouteTrace subnetTrace,
	SubnetBoundaryTransition boundaryTransition) {
	/** Compatibility constructor for routes that begin in their source Cluster's own network. */
	public RadioEnvelope(UUID messageId, UUID sourceClusterId, UUID sourceMemberId,
		UUID targetClusterId, UUID targetMemberId,
		com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency,
		long hopSequence, long sourceGeneration, long targetGeneration,
		String payloadFingerprint, ProcessProtocolMessage payload) {
		this(messageId, sourceClusterId, sourceMemberId, targetClusterId, targetMemberId, frequency,
			hopSequence, sourceGeneration, targetGeneration, payloadFingerprint, payload,
			SubnetRouteTrace.start(sourceClusterId, sourceClusterId), null);
	}

	public RadioEnvelope {
		messageId = Objects.requireNonNull(messageId, "messageId");
		sourceClusterId = Objects.requireNonNull(sourceClusterId, "sourceClusterId");
		sourceMemberId = Objects.requireNonNull(sourceMemberId, "sourceMemberId");
		targetClusterId = Objects.requireNonNull(targetClusterId, "targetClusterId");
		targetMemberId = Objects.requireNonNull(targetMemberId, "targetMemberId");
		frequency = Objects.requireNonNull(frequency, "frequency");
		if (hopSequence < 0 || sourceGeneration < 0 || targetGeneration < 0)
			throw new IllegalArgumentException("envelope fences cannot be negative");
		payload = Objects.requireNonNull(payload, "payload");
		payloadFingerprint = Objects.requireNonNull(payloadFingerprint, "payloadFingerprint");
		if (!payloadFingerprint.equals(payload.fingerprint()))
			throw new IllegalArgumentException("payload fingerprint does not match the immutable payload");
		subnetTrace = Objects.requireNonNull(subnetTrace, "subnetTrace");
		if (!subnetTrace.hasVisitedCluster(sourceClusterId))
			throw new IllegalArgumentException("subnet trace does not include the source Cluster");
		RadioEndpoint.Hop envelopeSourceHop = new RadioEndpoint.Hop(
			new RadioEndpoint.Key(sourceClusterId, sourceMemberId), sourceGeneration);
		RadioEndpoint.Hop envelopeTargetHop = new RadioEndpoint.Hop(
			new RadioEndpoint.Key(targetClusterId, targetMemberId), targetGeneration);
		if (boundaryTransition != null
			&& (!boundaryTransition.sourceClusterId().equals(sourceClusterId)
				|| !boundaryTransition.targetClusterId().equals(targetClusterId)
				|| !boundaryTransition.listenerFrequency().equals(frequency)
				|| !boundaryTransition.sourceHop().equals(envelopeSourceHop)
				|| !boundaryTransition.targetHop().equals(envelopeTargetHop)
				|| !subnetTrace.hasVisitedNetwork(boundaryTransition.sourceNetworkId())
				|| !subnetTrace.hasVisitedNetwork(boundaryTransition.targetNetworkId())
				|| !subnetTrace.hasVisitedCluster(boundaryTransition.targetClusterId())
				|| !boundaryTransition.exactlyExtends(subnetTrace)))
			throw new IllegalArgumentException("boundary transition differs from envelope trace evidence");
	}

	public static RadioEnvelope create(UUID messageId, RadioEndpoint source,
		RadioEndpoint target, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency, ProcessProtocolMessage payload) {
		return create(messageId, source, target, frequency, payload,
			SubnetRouteTrace.start(source.clusterId(), source.clusterId()));
	}

	/** Creates a route with caller-supplied immutable source-network identity evidence. */
	public static RadioEnvelope create(UUID messageId, RadioEndpoint source,
		RadioEndpoint target,
		com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency,
		ProcessProtocolMessage payload, SubnetRouteTrace subnetTrace) {
		source = Objects.requireNonNull(source, "source");
		target = Objects.requireNonNull(target, "target");
		payload = Objects.requireNonNull(payload, "payload");
		subnetTrace = Objects.requireNonNull(subnetTrace, "subnetTrace");
		return new RadioEnvelope(messageId, source.clusterId(), source.memberId(),
			target.clusterId(), target.memberId(), frequency, 0, source.generation(),
			target.generation(), payload.fingerprint(), payload, subnetTrace, null);
	}

	/**
	 * Installs one router-authorized destination transition. Package access keeps raw callers
	 * from declaring a boundary crossing without the router's registered binding.
	 */
	RadioEnvelope withBoundaryTransition(SubnetRouteTrace nextTrace,
		SubnetBoundaryBinding binding) {
		nextTrace = Objects.requireNonNull(nextTrace, "nextTrace");
		binding = Objects.requireNonNull(binding, "binding");
		if (!binding.sourceClusterId().equals(sourceClusterId)
			|| !binding.targetClusterId().equals(targetClusterId)
			|| !binding.listenerFrequency().equals(frequency)
			|| !binding.sourceHop().equals(sourceHop())
			|| !binding.targetHop().equals(targetHop())
			|| !subnetTrace.hasVisitedNetwork(binding.sourceNetworkId())
			|| !subnetTrace.hasVisitedCluster(binding.sourceClusterId())
			|| !nextTrace.equals(subnetTrace.visit(binding.targetNetworkId(),
				binding.targetClusterId())))
			throw new IllegalArgumentException("boundary transition does not exactly extend its trace");
		return new RadioEnvelope(messageId, sourceClusterId, sourceMemberId, targetClusterId,
			targetMemberId, frequency, hopSequence, sourceGeneration, targetGeneration,
			payloadFingerprint, payload, nextTrace, SubnetBoundaryTransition.from(binding, subnetTrace));
	}

	public RadioEnvelope advanceHop() {
		return new RadioEnvelope(messageId, sourceClusterId, sourceMemberId,
			targetClusterId, targetMemberId, frequency, Math.incrementExact(hopSequence),
			sourceGeneration, targetGeneration, payloadFingerprint, payload, subnetTrace,
			boundaryTransition);
	}

	public RadioEndpoint.Hop sourceHop() {
		return new RadioEndpoint.Hop(new RadioEndpoint.Key(sourceClusterId, sourceMemberId),
			sourceGeneration);
	}

	public RadioEndpoint.Hop targetHop() {
		return new RadioEndpoint.Hop(new RadioEndpoint.Key(targetClusterId, targetMemberId),
			targetGeneration);
	}
}
