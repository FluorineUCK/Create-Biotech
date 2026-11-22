package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Composition-root authority joining one lifecycle frame to one exact router authority state.
 * Public value records cannot be promoted to a current communication topology without this owner.
 */
public final class RadioTopologyPublicationAuthority implements AutoCloseable {
	private final RadioTopologyLifecycle lifecycle;
	private final RadioRouter router;
	private Publication current;
	private Fingerprint fingerprint;
	private long publicationRevision;
	private long lastSampleTick;
	private boolean sampled;
	private boolean closed;

	public RadioTopologyPublicationAuthority(RadioTopologyLifecycle lifecycle, RadioRouter router) {
		this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
		this.router = Objects.requireNonNull(router, "router");
	}

	public synchronized Publication capture() {
		if (closed) throw new IllegalStateException("topology publication authority is closed");
		RadioTopologyFrame frame = lifecycle.frame();
		RadioRouter.AuthorityPublication routerPublication = router.captureAuthorityPublication();
		if (!routerPublication.isCurrent())
			throw new IllegalStateException("router authority changed during topology capture");
		List<RadioEndpoint> endpoints = routerPublication.endpoints();
		for (RadioEndpoint endpoint : endpoints) {
			RadioTopologyFrame.Sample sample = frame.find(endpoint.key()).orElseThrow(() ->
				new IllegalStateException("router endpoint lacks current topology sample"));
			if (!lifecycle.isCurrent(endpoint) || sample.generation() != endpoint.generation()
				|| !sample.source().equals(endpoint.address()))
				throw new IllegalStateException("router endpoint is not owned by the latest topology");
		}
		Set<RadioEndpoint.Hop> hops = new HashSet<>();
		endpoints.forEach(endpoint -> hops.add(endpoint.hop()));
		for (SubnetBoundaryBinding boundary : routerPublication.boundaryBindings())
			if (!hops.contains(boundary.sourceHop()) || !hops.contains(boundary.targetHop()))
				throw new IllegalStateException("router boundary is not joined to current endpoints");
		Fingerprint next = new Fingerprint(frame, lifecycle.authorityRevision(), endpoints,
			routerPublication.boundaryBindings(),
			routerPublication.authorityRevision(), routerPublication.boundaryBindings().stream()
				.collect(java.util.stream.Collectors.toUnmodifiableMap(
					SubnetBoundaryBinding::sourceClusterId,
					binding -> routerPublication.boundaryGeneration(binding.sourceClusterId()))));
		if (sampled && frame.sampleTick() < lastSampleTick)
			throw new IllegalStateException("topology sample tick regressed");
		if (sampled && frame.sampleTick() == lastSampleTick && !next.equals(fingerprint))
			throw new IllegalStateException("topology facts changed without a new sample tick");
		if (current != null && next.equals(fingerprint)) return current;
		if (current != null) publicationRevision = nextRevision(publicationRevision);
		lastSampleTick = frame.sampleTick();
		sampled = true;
		fingerprint = next;
		current = new Publication(this, publicationRevision, next, routerPublication);
		return current;
	}

	public synchronized boolean isCurrent(Publication candidate) {
		return !closed && candidate != null && candidate.owner == this && current == candidate
			&& candidate.routerPublication.isCurrent()
			&& lifecycle.authorityRevision() == candidate.fingerprint.lifecycleAuthorityRevision()
			&& lifecycle.frame().equals(candidate.fingerprint.frame());
	}

	@Override public synchronized void close() {
		closed = true;
		current = null;
		fingerprint = null;
	}

	private static long nextRevision(long revision) {
		if (revision == Long.MAX_VALUE)
			throw new IllegalStateException("topology publication revision exhausted");
		return revision + 1;
	}

	public static final class Publication {
		private final RadioTopologyPublicationAuthority owner;
		private final long publicationRevision;
		private final Fingerprint fingerprint;
		private final RadioRouter.AuthorityPublication routerPublication;

		private Publication(RadioTopologyPublicationAuthority owner, long publicationRevision,
			Fingerprint fingerprint, RadioRouter.AuthorityPublication routerPublication) {
			this.owner = owner;
			this.publicationRevision = publicationRevision;
			this.fingerprint = fingerprint;
			this.routerPublication = routerPublication;
		}

		/** UI change identity omits only wall-clock sampling; exact authority remains separate. */
		public Object semanticKey() {
			return new SemanticFingerprint(owner, fingerprint.frame().dynamic(), fingerprint.frame().samples(),
				fingerprint.lifecycleAuthorityRevision(), fingerprint.endpoints(), fingerprint.boundaries(),
				fingerprint.routerAuthorityRevision(), fingerprint.boundaryGenerations());
		}
		public long publicationRevision() { return publicationRevision; }
		public long sampleTick() { return fingerprint.frame().sampleTick(); }
		public long routerAuthorityRevision() { return fingerprint.routerAuthorityRevision(); }
		public RadioTopologyFrame frame() { return fingerprint.frame(); }
		public List<RadioEndpoint> endpoints() { return fingerprint.endpoints(); }
		public List<SubnetBoundaryBinding> boundaryBindings() { return fingerprint.boundaries(); }
		public boolean isCurrent() { return owner.isCurrent(this); }
		public boolean owns(SubnetBoundaryBinding binding) {
			return fingerprint.boundaries().contains(Objects.requireNonNull(binding, "binding"));
		}
	}

	private record SemanticFingerprint(RadioTopologyPublicationAuthority owner, boolean dynamic,
		Map<RadioEndpoint.Key, RadioTopologyFrame.Sample> samples, long lifecycleAuthorityRevision,
		List<RadioEndpoint> endpoints, List<SubnetBoundaryBinding> boundaries,
		long routerAuthorityRevision, Map<UUID, Long> boundaryGenerations) {}

	private record Fingerprint(RadioTopologyFrame frame, long lifecycleAuthorityRevision,
		List<RadioEndpoint> endpoints,
		List<SubnetBoundaryBinding> boundaries, long routerAuthorityRevision,
		Map<UUID, Long> boundaryGenerations) {
		private Fingerprint {
			frame = Objects.requireNonNull(frame, "frame");
			endpoints = List.copyOf(endpoints);
			boundaries = List.copyOf(boundaries);
			boundaryGenerations = Map.copyOf(boundaryGenerations);
		}
	}
}
