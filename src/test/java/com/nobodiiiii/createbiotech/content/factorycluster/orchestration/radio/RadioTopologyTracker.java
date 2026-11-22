package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

import net.minecraft.world.phys.Vec3;

/**
 * Server-thread owner of current-pose radio samples.
 *
 * <p>Only {@link #refresh(long, ProjectionResolver)} may invoke a live projection resolver. The
 * published {@link RadioTopologyFrame} contains a wholly replaced immutable map, never an object
 * from the world or physical-structure APIs.</p>
 */
public final class RadioTopologyTracker {
	public static final int SAMPLE_CADENCE_TICKS = 20;

	private final boolean dynamic;
	private final Map<RadioEndpoint.Key, Observation> observations = new LinkedHashMap<>();
	private volatile RadioTopologyFrame frame;
	private boolean acceptedTick;
	private long lastAcceptedTick;

	public RadioTopologyTracker(boolean dynamic) {
		this.dynamic = dynamic;
		this.frame = new RadioTopologyFrame(0, dynamic, Map.of());
	}

	public RadioTopologyFrame frame() {
		return frame;
	}

	/**
	 * Removes an endpoint permanently retired by its owning physical Computer. A stale unload for
	 * an already replaced endpoint is ignored, so it cannot revoke the replacement's frame.
	 */
	public boolean retire(RadioEndpoint endpoint) {
		endpoint = Objects.requireNonNull(endpoint, "endpoint");
		RadioEndpoint.Key key = endpoint.key();
		Observation current = observations.get(key);
		if (current == null || !current.endpoint().equals(endpoint)) return false;
		observations.remove(key);

		RadioTopologyFrame.Sample sample = frame.samples().get(key);
		if (sample == null || !matches(endpoint, sample)) return true;
		LinkedHashMap<RadioEndpoint.Key, RadioTopologyFrame.Sample> samples =
			new LinkedHashMap<>(frame.samples());
		samples.remove(key);
		frame = new RadioTopologyFrame(frame.sampleTick(), dynamic, samples);
		return true;
	}

	/**
	 * Applies one event-driven endpoint availability update without reading the live pose.
	 *
	 * <p>Dynamic mode only carries a prior complete sample through an unload. A new load or a
	 * generation replacement has no current-pose sample until the next cadence boundary, so an old
	 * world point cannot become a ghost link.</p>
	 */
	public void observe(RadioEndpoint endpoint, Pose pose, boolean loaded, long observationTick) {
		acceptTick(observationTick);
		endpoint = Objects.requireNonNull(endpoint, "endpoint");
		pose = Objects.requireNonNull(pose, "pose");
		RadioEndpoint.Key key = endpoint.key();
		Observation next = new Observation(endpoint, pose, loaded);
		Observation prior = observations.get(key);
		if (next.equals(prior)) return;
		observations.put(key, next);
		if (!dynamic) {
			publishStaticAvailability(key, endpoint, loaded);
			return;
		}
		publishDynamicAvailability(key, endpoint, loaded, prior);
	}

	/**
	 * Replaces all old physical observations with one loaded endpoint and publishes at most one
	 * complete frame. This is the lifecycle boundary for reformed, retuned, moved, or reequipped
	 * Computers: dynamic mode removes every old point without fabricating one for {@code endpoint};
	 * disabled mode directly substitutes its local-coordinate sample.
	 */
	public void replace(List<RadioEndpoint> retired, RadioEndpoint endpoint, Pose pose,
		long observationTick) {
		acceptTick(observationTick);
		retired = List.copyOf(Objects.requireNonNull(retired, "retired"));
		endpoint = Objects.requireNonNull(endpoint, "endpoint");
		pose = Objects.requireNonNull(pose, "pose");
		for (RadioEndpoint old : retired) Objects.requireNonNull(old, "retired endpoint");

		RadioEndpoint.Key key = endpoint.key();
		Observation next = new Observation(endpoint, pose, true);
		Observation prior = observations.get(key);
		boolean changed = false;
		for (RadioEndpoint old : retired) {
			Observation oldObservation = observations.get(old.key());
			if (oldObservation != null && oldObservation.endpoint().equals(old)) {
				observations.remove(old.key());
				changed = true;
			}
		}
		Observation current = observations.get(key);
		if (!next.equals(current)) {
			observations.put(key, next);
			changed = true;
		}
		if (!changed) return;

		LinkedHashMap<RadioEndpoint.Key, RadioTopologyFrame.Sample> samples =
			new LinkedHashMap<>(frame.samples());
		for (RadioEndpoint old : retired) removeMatchingSample(samples, old);
		if (!dynamic) {
			samples.put(key, staticSample(endpoint, true));
		} else if (prior != null && !next.equals(prior)) {
			// A same-key reload has a prior unavailable sample; do not treat it as a pose for the
			// newly available endpoint. Replacements in retired were removed above.
			removeMatchingSample(samples, prior.endpoint());
		}
		frame = new RadioTopologyFrame(frame.sampleTick(), dynamic, samples);
	}

	/**
	 * Captures every observed loaded endpoint at one server-tick boundary and atomically publishes
	 * the resulting complete immutable frame. Callers must invoke this only on the Minecraft server
	 * thread; the resolver is the sole live-world boundary.
	 */
	public void refresh(long serverTick, ProjectionResolver resolver) {
		resolver = Objects.requireNonNull(resolver, "resolver");
		acceptTick(serverTick);
		if (Math.floorMod(serverTick, SAMPLE_CADENCE_TICKS) != 0) return;

		LinkedHashMap<RadioEndpoint.Key, RadioTopologyFrame.Sample> samples = new LinkedHashMap<>();
		RadioTopologyFrame prior = frame;
		for (Map.Entry<RadioEndpoint.Key, Observation> entry : observations.entrySet()) {
			RadioEndpoint.Key key = entry.getKey();
			Observation observation = entry.getValue();
			RadioEndpoint endpoint = observation.endpoint();
			if (!dynamic) {
				samples.put(key, staticSample(endpoint, observation.loaded()));
				continue;
			}
			if (!observation.loaded()) {
				prior.find(key).filter(sample -> matches(endpoint, sample))
					.ifPresent(sample -> samples.put(key, sample.withLoaded(false)));
				continue;
			}
			ResolvedPose resolved = resolver.resolve(endpoint, observation.pose());
			if (resolved == null || !resolved.physicalStructureId().equals(
				Optional.ofNullable(endpoint.address().subLevelId()))) continue;
			samples.put(key, new RadioTopologyFrame.Sample(endpoint.address().dimension(),
				endpoint.address(), resolved.worldCenter(), true, endpoint.generation(),
				resolved.physicalStructureId()));
		}
		frame = new RadioTopologyFrame(serverTick, dynamic, samples);
	}

	private void acceptTick(long tick) {
		if (acceptedTick && tick < lastAcceptedTick && !signedTickOverflow(lastAcceptedTick, tick))
			throw new IllegalArgumentException("topology ticks cannot move backwards");
		acceptedTick = true;
		lastAcceptedTick = tick;
	}

	private static boolean signedTickOverflow(long prior, long next) {
		return prior >= Long.MAX_VALUE - SAMPLE_CADENCE_TICKS
			&& next <= Long.MIN_VALUE + SAMPLE_CADENCE_TICKS;
	}

	private void publishStaticAvailability(RadioEndpoint.Key key, RadioEndpoint endpoint,
		boolean loaded) {
		LinkedHashMap<RadioEndpoint.Key, RadioTopologyFrame.Sample> samples =
			new LinkedHashMap<>(frame.samples());
		samples.put(key, staticSample(endpoint, loaded));
		frame = new RadioTopologyFrame(frame.sampleTick(), false, samples);
	}

	private void publishDynamicAvailability(RadioEndpoint.Key key, RadioEndpoint endpoint,
		boolean loaded, Observation prior) {
		LinkedHashMap<RadioEndpoint.Key, RadioTopologyFrame.Sample> samples =
			new LinkedHashMap<>(frame.samples());
		RadioTopologyFrame.Sample current = samples.get(key);
		if (current == null || !matches(endpoint, current)) {
			samples.remove(key);
		} else if (!loaded) {
			samples.put(key, current.withLoaded(false));
		} else if (prior == null || !prior.loaded() || !sameIdentity(prior.endpoint(), endpoint)) {
			samples.remove(key);
		}
		frame = new RadioTopologyFrame(frame.sampleTick(), true, samples);
	}

	private static boolean sameIdentity(RadioEndpoint first, RadioEndpoint second) {
		return first.equals(second);
	}

	private static boolean matches(RadioEndpoint endpoint, RadioTopologyFrame.Sample sample) {
		return endpoint.generation() == sample.generation()
			&& endpoint.address().equals(sample.source());
	}

	private static void removeMatchingSample(
		Map<RadioEndpoint.Key, RadioTopologyFrame.Sample> samples, RadioEndpoint endpoint) {
		RadioTopologyFrame.Sample sample = samples.get(endpoint.key());
		if (sample != null && matches(endpoint, sample)) samples.remove(endpoint.key());
	}

	private static RadioTopologyFrame.Sample staticSample(RadioEndpoint endpoint,
		boolean loaded) {
		SpaceAddress source = endpoint.address();
		return new RadioTopologyFrame.Sample(source.dimension(), source,
			Vec3.atCenterOf(source.localPos()), loaded, endpoint.generation(),
			Optional.ofNullable(source.subLevelId()));
	}

	private record Observation(RadioEndpoint endpoint, Pose pose, boolean loaded) {}

	/** Event metadata only; dynamic projection is deliberately deferred until {@link #refresh}. */
	public record Pose(Optional<UUID> physicalStructureId, Vec3 localCenter) {
		public Pose {
			physicalStructureId = Objects.requireNonNull(physicalStructureId,
				"physicalStructureId");
			localCenter = Objects.requireNonNull(localCenter, "localCenter");
			localCenter = new Vec3(localCenter.x, localCenter.y, localCenter.z);
		}
	}

	/** Result of one current server-thread projection. Returning {@code null} marks no sample. */
	@FunctionalInterface
	public interface ProjectionResolver {
		ResolvedPose resolve(RadioEndpoint endpoint, Pose observedPose);
	}

	/** Immutable output copied into the frame after a live pose has been read. */
	public record ResolvedPose(Vec3 worldCenter, Optional<UUID> physicalStructureId) {
		public ResolvedPose {
			worldCenter = Objects.requireNonNull(worldCenter, "worldCenter");
			physicalStructureId = Objects.requireNonNull(physicalStructureId,
				"physicalStructureId");
			worldCenter = new Vec3(worldCenter.x, worldCenter.y, worldCenter.z);
		}
	}
}
