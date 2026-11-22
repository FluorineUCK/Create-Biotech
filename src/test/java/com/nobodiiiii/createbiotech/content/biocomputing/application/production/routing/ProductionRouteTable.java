package com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Unified production feed for Pattern, Agent and Factory Gauge routes.
 * World adapters publish {@link ProviderSource}s here; Agent and Gauge adapters are
 * feed the same scoped facts rather than bypassing this table.
 */
public final class ProductionRouteTable {
	public static final int MAX_SOURCES = 4096;
	private final Map<RouteSelectionScope, LinkedHashMap<SourceIdentity, ProviderSource>> scopedSources =
		new LinkedHashMap<>();
	private final DefaultRoutePolicy policy;

	public ProductionRouteTable() { this(new DefaultRoutePolicy.CursorState(Map.of())); }
	public ProductionRouteTable(DefaultRoutePolicy.CursorState cursor) {
		policy = new DefaultRoutePolicy(Objects.requireNonNull(cursor));
	}

	public void publish(ProviderSource source) {
		source = Objects.requireNonNull(source);
		LinkedHashMap<SourceIdentity, ProviderSource> sources = scopedSources.get(source.scope());
		if ((sources == null || !sources.containsKey(source.identity())) && sourceCount() >= MAX_SOURCES)
			throw new IllegalStateException("Production route table exceeds bound");
		if (sources == null) {
			sources = new LinkedHashMap<>();
			scopedSources.put(source.scope(), sources);
		}
		sources.put(source.identity(), source);
	}

	public void remove(ProviderSource source) {
		source = Objects.requireNonNull(source);
		LinkedHashMap<SourceIdentity, ProviderSource> sources = scopedSources.get(source.scope());
		if (sources != null) {
			sources.remove(source.identity());
			if (sources.isEmpty()) scopedSources.remove(source.scope());
		}
	}

	public void replaceProviderKind(RouteSelectionScope scope, RouteCandidate.ProviderKind kind,
		List<ProviderSource> snapshot) {
		Objects.requireNonNull(scope); Objects.requireNonNull(kind);
		snapshot = List.copyOf(Objects.requireNonNull(snapshot));
		if (snapshot.stream().anyMatch(source -> source.providerKind() != kind
			|| !source.scope().equals(scope)))
			throw new IllegalArgumentException("mixed scope or provider-kind snapshot");
		LinkedHashMap<SourceIdentity, ProviderSource> sources = scopedSources.get(scope);
		LinkedHashMap<SourceIdentity, ProviderSource> replacement = sources == null
			? new LinkedHashMap<>() : new LinkedHashMap<>(sources);
		replacement.entrySet().removeIf(entry -> entry.getValue().providerKind() == kind);
		boolean replacesWholeScope = replacement.isEmpty();
		for (ProviderSource source : snapshot) replacement.put(source.identity(), source);
		int otherSources = sourceCount() - (sources == null ? 0 : sources.size());
		if (replacement.size() > MAX_SOURCES - otherSources)
			throw new IllegalStateException("Production route table exceeds bound");
		// Commit only the complete feed; retain the historical reinsertion order of whole-scope replacements.
		if (replacesWholeScope) scopedSources.remove(scope);
		if (!replacement.isEmpty()) scopedSources.put(scope, replacement);
	}

	public void removeScope(RouteSelectionScope scope) {
		scopedSources.remove(Objects.requireNonNull(scope));
	}

	public List<ProviderSource> snapshot(RouteSelectionScope scope) {
		Map<SourceIdentity, ProviderSource> sources = scopedSources.get(Objects.requireNonNull(scope));
		return sources == null ? List.of() : List.copyOf(sources.values());
	}

	public List<ProviderSource> snapshot() {
		return scopedSources.values().stream().flatMap(sources -> sources.values().stream()).toList();
	}

	public DefaultRoutePolicy.Decision select(RouteSelectionScope scope) {
		return policy.select(snapshot(scope).stream().map(ProviderSource::candidate).toList());
	}
	public DefaultRoutePolicy.CursorState cursorState() { return policy.cursorState(); }
	private int sourceCount() { return scopedSources.values().stream().mapToInt(Map::size).sum(); }

	private record SourceIdentity(UUID libraryId, UUID patternId, String providerKey,
		RouteCandidate.ProviderKind kind) {}

	public record ProviderSource(RouteSelectionScope scope, UUID libraryId, UUID patternId, String providerKey,
		RouteCandidate.ProviderKind providerKind, int effectivePriority,
		RouteCandidate.Availability availability, RouteCandidate.Blocker blocker) {
		public ProviderSource {
			scope = Objects.requireNonNull(scope, "scope");
			providerKey = Objects.requireNonNull(providerKey).trim();
			// Reuse the route model as the single validation contract for every provider source.
			new RouteCandidate(libraryId, patternId, providerKey, providerKind,
				effectivePriority, availability, blocker);
		}
		private SourceIdentity identity() {
			return new SourceIdentity(libraryId, patternId, providerKey, providerKind);
		}
		public RouteCandidate candidate() {
			return new RouteCandidate(libraryId, patternId, providerKey, providerKind,
				effectivePriority, availability, blocker);
		}
		public ProviderSource withScope(RouteSelectionScope nextScope) {
			return new ProviderSource(nextScope, libraryId, patternId, providerKey, providerKind,
				effectivePriority, availability, blocker);
		}
	}
}
