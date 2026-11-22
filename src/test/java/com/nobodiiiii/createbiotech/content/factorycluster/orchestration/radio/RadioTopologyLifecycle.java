package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

/**
 * Server-thread lifecycle ownership for physical topology observations.
 *
 * <p>A registration contains both the loaded owner token and one logical endpoint identity.
 * A delayed callback can therefore retire only the observation it originally published; it can
 * never remove a successor that inherited the same Computer id or block position. One host may
 * own multiple registrations (notably an external listener source and relay at the same block).
 * </p>
 */
public final class RadioTopologyLifecycle {
	static final long UNAVAILABLE_RETENTION_TICKS = RadioTopologyTracker.SAMPLE_CADENCE_TICKS * 2L;
	static final int MAX_UNLOADED = 256;

	private final RadioTopologyTracker tracker;
	private final Map<RadioEndpoint.Key, Registered> current = new LinkedHashMap<>();
	/** Unavailable samples retain one frozen frame window, then expire deterministically. */
	private final Map<RadioEndpoint.Key, Registered> unloaded = new LinkedHashMap<>();
	private boolean acceptedTick;
	private long lastAcceptedTick;
	private long authorityRevision;

	public RadioTopologyLifecycle(boolean dynamic) {
		tracker = new RadioTopologyTracker(dynamic);
	}

	public RadioTopologyFrame frame() {
		return tracker.frame();
	}

	long authorityRevision() { return authorityRevision; }

	/** Returns whether this exact endpoint remains the current topology observation. */
	public boolean isCurrent(RadioEndpoint endpoint) {
		endpoint = Objects.requireNonNull(endpoint, "endpoint");
		Registered registered = current.get(endpoint.key());
		return registered != null && registered.endpoint().equals(endpoint);
	}

	/** Exact loaded-instance ownership check used to keep router and topology publications aligned. */
	public boolean owns(Registration registration, RadioEndpoint endpoint) {
		registration = Objects.requireNonNull(registration, "registration");
		endpoint = Objects.requireNonNull(endpoint, "endpoint");
		Registered registered = current.get(endpoint.key());
		return registered != null && registered.registration().equals(registration)
			&& registered.endpoint().equals(endpoint);
	}

	/**
	 * Records a live endpoint under an exact loaded-instance registration.
	 * Returned endpoints have lost lifecycle ownership and must be retired from route-only state.
	 */
	public List<RadioEndpoint> observe(Registration registration, RadioEndpoint endpoint,
		RadioTopologyTracker.Pose pose, long observationTick) {
		acceptTick(observationTick);
		registration = Objects.requireNonNull(registration, "registration");
		RadioEndpoint next = Objects.requireNonNull(endpoint, "endpoint");
		pose = Objects.requireNonNull(pose, "pose");
		Registered registered = current.get(next.key());
		if (registered != null && registered.registration().equals(registration)
			&& registered.endpoint().equals(next) && registered.pose().equals(pose)) return List.of();

		ArrayList<RadioEndpoint> retired = new ArrayList<>();
		for (Map.Entry<RadioEndpoint.Key, Registered> entry : List.copyOf(current.entrySet())) {
			Registered candidate = entry.getValue();
			if (!entry.getKey().equals(next.key())
				&& !candidate.registration().equals(registration)) continue;
			current.remove(entry.getKey());
			retired.add(candidate.endpoint());
		}
		for (Map.Entry<RadioEndpoint.Key, Registered> entry : List.copyOf(unloaded.entrySet())) {
			Registered candidate = entry.getValue();
			if (!entry.getKey().equals(next.key())
				&& !candidate.registration().equals(registration)) continue;
			unloaded.remove(entry.getKey());
			tracker.retire(candidate.endpoint());
		}
		current.put(next.key(), new Registered(registration, next, pose, Long.MIN_VALUE));
		tracker.replace(retired, next, pose, observationTick);
		advanceAuthority();
		return List.copyOf(retired);
	}

	/** Compatibility entry point for callers that cannot retain an owner fence. */
	public List<RadioEndpoint> observe(RadioEndpoint endpoint, RadioTopologyTracker.Pose pose,
		long observationTick) {
		endpoint = Objects.requireNonNull(endpoint, "endpoint");
		return observe(legacyRegistration(endpoint), endpoint, pose, observationTick);
	}

	/** Marks only this registration unavailable while retaining its last proven frame sample. */
	public List<RadioEndpoint> unload(Registration registration, long observationTick) {
		acceptTick(observationTick);
		Registration exact = Objects.requireNonNull(registration, "registration");
		return removeMatching(candidate -> candidate.registration().equals(exact), true,
			observationTick);
	}

	/** Fully retires only this registration, so a cadence boundary cannot resurrect it. */
	public List<RadioEndpoint> retire(Registration registration) {
		Registration exact = Objects.requireNonNull(registration, "registration");
		return removeMatching(candidate -> candidate.registration().equals(exact), false, 0);
	}

	/** Legacy physical matching remains for non-fenced callers only. */
	public List<RadioEndpoint> unload(@Nullable UUID computerId, SpaceAddress source,
		long observationTick) {
		acceptTick(observationTick);
		return removeMatching(candidate -> samePhysicalComputer(candidate.endpoint(), computerId, source),
			true, observationTick);
	}

	/** Legacy physical retirement remains for non-fenced callers only. */
	public List<RadioEndpoint> retire(@Nullable UUID computerId, SpaceAddress source) {
		return removeMatching(candidate -> samePhysicalComputer(candidate.endpoint(), computerId, source),
			false, 0);
	}

	/** Captures one atomic current-pose frame on the server tick boundary. */
	public void refresh(long serverTick, RadioTopologyTracker.ProjectionResolver resolver) {
		acceptTick(serverTick);
		tracker.refresh(serverTick, resolver);
		if (Math.floorMod(serverTick, RadioTopologyTracker.SAMPLE_CADENCE_TICKS) == 0)
			pruneUnavailable(serverTick);
	}

	private void acceptTick(long tick) {
		if (acceptedTick && tick < lastAcceptedTick
			&& !(lastAcceptedTick >= Long.MAX_VALUE - RadioTopologyTracker.SAMPLE_CADENCE_TICKS
				&& tick <= Long.MIN_VALUE + RadioTopologyTracker.SAMPLE_CADENCE_TICKS))
			throw new IllegalArgumentException("topology lifecycle ticks cannot move backwards");
		acceptedTick = true;
		lastAcceptedTick = tick;
	}

	int unloadedCount() {
		return unloaded.size();
	}

	private List<RadioEndpoint> removeMatching(Predicate<Registered> matcher, boolean markUnavailable,
		long observationTick) {
		ArrayList<RadioEndpoint> retired = new ArrayList<>();
		for (Map.Entry<RadioEndpoint.Key, Registered> entry : List.copyOf(current.entrySet())) {
			Registered registered = entry.getValue();
			if (!matcher.test(registered)) continue;
			current.remove(entry.getKey());
			if (markUnavailable) {
				Registered unavailable = registered.withUnavailableSince(observationTick);
				unloaded.put(entry.getKey(), unavailable);
				tracker.observe(unavailable.endpoint(), unavailable.pose(), false, observationTick);
			} else tracker.retire(registered.endpoint());
			retired.add(registered.endpoint());
		}
		if (!markUnavailable) {
			for (Map.Entry<RadioEndpoint.Key, Registered> entry : List.copyOf(unloaded.entrySet())) {
				Registered registered = entry.getValue();
				if (!matcher.test(registered)) continue;
				unloaded.remove(entry.getKey());
				tracker.retire(registered.endpoint());
				retired.add(registered.endpoint());
			}
		} else enforceUnavailableBound();
		if (!retired.isEmpty()) advanceAuthority();
		return List.copyOf(retired);
	}

	private void pruneUnavailable(long serverTick) {
		boolean changed = false;
		for (Map.Entry<RadioEndpoint.Key, Registered> entry : List.copyOf(unloaded.entrySet())) {
			Registered registered = entry.getValue();
			if (serverTick - registered.unavailableSince() < UNAVAILABLE_RETENTION_TICKS) continue;
			unloaded.remove(entry.getKey());
			tracker.retire(registered.endpoint());
			changed = true;
		}
		if (changed) advanceAuthority();
		enforceUnavailableBound();
	}

	private void enforceUnavailableBound() {
		while (unloaded.size() > MAX_UNLOADED) {
			Map.Entry<RadioEndpoint.Key, Registered> oldest = unloaded.entrySet().iterator().next();
			unloaded.remove(oldest.getKey());
			tracker.retire(oldest.getValue().endpoint());
			advanceAuthority();
		}
	}

	private void advanceAuthority() {
		if (authorityRevision == Long.MAX_VALUE)
			throw new IllegalStateException("topology lifecycle authority revision exhausted");
		authorityRevision++;
	}

	private static Registration legacyRegistration(RadioEndpoint endpoint) {
		return new Registration(endpoint.memberId(), endpoint.memberId());
	}

	private static boolean samePhysicalComputer(RadioEndpoint candidate, @Nullable UUID computerId,
		SpaceAddress source) {
		source = Objects.requireNonNull(source, "source");
		return (computerId != null && computerId.equals(candidate.memberId()))
			|| source.equals(candidate.address());
	}

	/** Loaded-owner plus one non-reusable logical endpoint identity. */
	public record Registration(UUID ownerToken, UUID endpointIdentity) {
		public Registration {
			ownerToken = Objects.requireNonNull(ownerToken, "owner token");
			endpointIdentity = Objects.requireNonNull(endpointIdentity, "endpoint identity");
		}
	}

	private record Registered(Registration registration, RadioEndpoint endpoint,
		RadioTopologyTracker.Pose pose, long unavailableSince) {
		private Registered withUnavailableSince(long tick) {
			return new Registered(registration, endpoint, pose, tick);
		}
	}
}
