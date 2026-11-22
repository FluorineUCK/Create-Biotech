package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.Objects;
import java.util.Optional;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

/**
 * The one immutable-frame rule for a radio edge.
 *
 * <p>Cluster-local edges are virtual. Every cross-cluster edge is proven against the exact
 * current topology samples; stale, unloaded, or incomparable samples fail closed.</p>
 */
public final class RadioLinkPolicy {
	private final int ordinaryRange;
	private final int extendedRange;

	public RadioLinkPolicy(int ordinaryRange, int extendedRange) {
		if (ordinaryRange <= 0 || extendedRange < ordinaryRange)
			throw new IllegalArgumentException("radio ranges are invalid");
		this.ordinaryRange = ordinaryRange;
		this.extendedRange = extendedRange;
	}

	public LinkResult link(RadioEndpoint first, RadioEndpoint second, RadioTopologyFrame frame) {
		return switch (edge(first, second, frame)) {
			case VIRTUAL -> LinkResult.VIRTUAL;
			case ORDINARY, EXTENDED -> LinkResult.RADIO;
			case UNAVAILABLE -> LinkResult.UNAVAILABLE;
		};
	}

	/**
	 * The router's single directed receiving-edge rule plus its stable display classification.
	 * A cross-Cluster target with no listener slots can never receive or relay a message.
	 */
	public EdgeResult edge(RadioEndpoint first, RadioEndpoint second, RadioTopologyFrame frame) {
		first = Objects.requireNonNull(first, "first");
		second = Objects.requireNonNull(second, "second");
		frame = Objects.requireNonNull(frame, "frame");
		if (first.clusterId().equals(second.clusterId())) return EdgeResult.VIRTUAL;
		if (second.listenerCapacity() == 0) return EdgeResult.UNAVAILABLE;
		if (!first.frequency().configured() || !first.frequency().equals(second.frequency()))
			return EdgeResult.UNAVAILABLE;

		RadioTopologyFrame.Sample firstSample = matchingSample(first, frame);
		RadioTopologyFrame.Sample secondSample = matchingSample(second, frame);
		if (firstSample == null || secondSample == null
			|| !firstSample.dimension().equals(secondSample.dimension())) return EdgeResult.UNAVAILABLE;
		if (!frame.dynamic() && !firstSample.physicalStructureId().equals(
			secondSample.physicalStructureId())) return EdgeResult.UNAVAILABLE;

		double distanceSquared = frame.dynamic()
			? firstSample.worldCenter().distanceToSqr(secondSample.worldCenter())
			: localDistanceSquared(firstSample, secondSample);
		if (distanceSquared <= (double) ordinaryRange * ordinaryRange)
			return EdgeResult.ORDINARY;
		if (distanceSquared <= (double) extendedRange * extendedRange
			&& (first.supports(ProductionCapability.EXTENDED_RADIO)
				|| second.supports(ProductionCapability.EXTENDED_RADIO))) return EdgeResult.EXTENDED;
		return EdgeResult.UNAVAILABLE;
	}

	private static double localDistanceSquared(RadioTopologyFrame.Sample first,
		RadioTopologyFrame.Sample second) {
		double dx = (double) first.source().localPos().getX() - second.source().localPos().getX();
		double dy = (double) first.source().localPos().getY() - second.source().localPos().getY();
		double dz = (double) first.source().localPos().getZ() - second.source().localPos().getZ();
		return dx * dx + dy * dy + dz * dz;
	}

	private static RadioTopologyFrame.Sample matchingSample(RadioEndpoint endpoint,
		RadioTopologyFrame frame) {
		return frame.find(endpoint.key()).filter(sample -> sample.loaded()
			&& sample.generation() == endpoint.generation()
			&& sample.source().equals(endpoint.address())
			&& sample.physicalStructureId().equals(
				Optional.ofNullable(endpoint.address().subLevelId()))).orElse(null);
	}

	public enum LinkResult {
		VIRTUAL,
		RADIO,
		UNAVAILABLE;

		public boolean reachable() { return this != UNAVAILABLE; }
		public boolean virtual() { return this == VIRTUAL; }
		public boolean requiresRemoteListener() { return this == RADIO; }
	}

	public enum EdgeResult {
		VIRTUAL,
		ORDINARY,
		EXTENDED,
		UNAVAILABLE;

		public boolean reachable() { return this != UNAVAILABLE; }
		public boolean virtual() { return this == VIRTUAL; }
		public boolean requiresRemoteListener() {
			return this == ORDINARY || this == EXTENDED;
		}
	}
}
