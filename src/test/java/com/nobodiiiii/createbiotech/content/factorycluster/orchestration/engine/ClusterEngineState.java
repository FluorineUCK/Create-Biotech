package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;

import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.DefaultRoutePolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.CoordinationAssociation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PriorityReservationSource;

/** Complete immutable durable snapshot for one Cluster. */
public record ClusterEngineState(UUID clusterId, UUID epochId, long generation,
	long nextSubmissionSequence, long nextRouteSelectionSequence,
	int memberCursor, int processCursor,
	Map<UUID, GoalRecord> goals, Map<UUID, Long> submissionSequences,
	Map<UUID, ProcessKind> requestedKinds, Map<UUID, ProcessRecord> processes,
	List<MemberSnapshot> members, Map<UUID, ProductionEffect> pendingIntents,
	Set<UUID> uncertainIntentIds, int goalScanOffset, UUID goalScanCandidate,
	DefaultRoutePolicy.CursorState routeCursor,
	Map<UUID, CoordinationAssociation> coordinationAssociations,
	Map<UUID, PriorityLeaseAssociation> priorityLeaseAssociations) implements PriorityReservationSource {
	public static final int MAX_GOALS = 4096;
	public static final int MAX_PROCESSES = 4096;
	public static final int MAX_MEMBERS = 256;
	public static final int MAX_PENDING_INTENTS = 4096;

	public ClusterEngineState {
		boolean trusted = goals instanceof TrustedMap && submissionSequences instanceof TrustedMap
			&& requestedKinds instanceof TrustedMap && processes instanceof TrustedMap
			&& members instanceof TrustedList && pendingIntents instanceof TrustedMap
			&& uncertainIntentIds instanceof TrustedSet;
		Objects.requireNonNull(clusterId, "clusterId"); Objects.requireNonNull(epochId, "epochId");
		routeCursor = Objects.requireNonNull(routeCursor, "routeCursor");
		if (generation < 0 || nextSubmissionSequence < 0 || nextRouteSelectionSequence < 0
			|| memberCursor < 0 || processCursor < 0 || goalScanOffset < 0)
			throw new IllegalArgumentException("negative engine cursor");
		goals = immutableMap(goals, MAX_GOALS, "goals");
		submissionSequences = immutableMap(submissionSequences, MAX_GOALS, "submission sequences");
		requestedKinds = immutableMap(requestedKinds, MAX_GOALS, "requested kinds");
		processes = immutableMap(processes, MAX_PROCESSES, "processes");
		members = immutableList(members, MAX_MEMBERS, "members");
		pendingIntents = immutableMap(pendingIntents, MAX_PENDING_INTENTS, "pending intents");
		uncertainIntentIds = immutableSet(uncertainIntentIds, MAX_PENDING_INTENTS, "uncertain intent ids");
		coordinationAssociations = immutableMap(coordinationAssociations, PatternPriorityAuthority.MAX_LEASES,
			"coordination associations");
		priorityLeaseAssociations = immutableMap(priorityLeaseAssociations, MAX_PROCESSES,
			"priority lease associations");
		for (Map.Entry<UUID, CoordinationAssociation> entry : coordinationAssociations.entrySet()) {
			ProcessRecord process = processes.get(entry.getKey());
			GoalRecord goal = process == null ? null : goals.get(process.goalId());
			if (!entry.getKey().equals(entry.getValue().processId()) || process == null
				|| process.kind() != ProcessKind.PATTERN_PRIORITY_LEASE
				|| goal != null && !goal.submission().patternId().equals(entry.getValue().patternId()))
				throw new IllegalArgumentException("orphan coordination association");
		}
		for (Map.Entry<UUID, PriorityLeaseAssociation> entry : priorityLeaseAssociations.entrySet()) {
			ProcessRecord process = processes.get(entry.getKey());
			if (!entry.getKey().equals(entry.getValue().processId()) || process == null
				|| process.kind() != ProcessKind.PRIORITY_LEASE
				|| !clusterId.equals(entry.getValue().agentOwnerClusterId()))
				throw new IllegalArgumentException("orphan priority lease association");
		}
		if (members.isEmpty() ? memberCursor != 0 : memberCursor >= members.size())
			throw new IllegalArgumentException("member cursor exceeds current members");
		if (processes.isEmpty() ? processCursor != 0 : processCursor >= processes.size())
			throw new IllegalArgumentException("process cursor exceeds current processes");
		if (goalScanOffset > goals.size() || goalScanCandidate != null && !goals.containsKey(goalScanCandidate))
			throw new IllegalArgumentException("goal scan cursor exceeds current goals");
		if (!trusted) {
		if (!pendingIntents.keySet().containsAll(uncertainIntentIds))
			throw new IllegalArgumentException("uncertain intents are outside pending intents");
		if (!goals.keySet().equals(submissionSequences.keySet())
			|| !goals.keySet().equals(requestedKinds.keySet())) throw new IllegalArgumentException("inconsistent engine indexes");
		Set<Long> stableSequences = new HashSet<>();
		for (Map.Entry<UUID, GoalRecord> entry : goals.entrySet()) {
			if (!entry.getKey().equals(entry.getValue().goalId())
				|| !clusterId.equals(entry.getValue().owningClusterId()))
				throw new IllegalArgumentException("Goal index has the wrong owner");
			long sequence = submissionSequences.get(entry.getKey());
			if (sequence < 0 || sequence >= nextSubmissionSequence || !stableSequences.add(sequence))
				throw new IllegalArgumentException("invalid stable submission sequence");
		}
		Map<UUID, MemberSnapshot> membersById = new HashMap<>();
		UUID previousMemberId = null;
		for (MemberSnapshot member : members) {
			if (membersById.putIfAbsent(member.computerId(), member) != null
				|| previousMemberId != null && previousMemberId.compareTo(member.computerId()) >= 0)
				throw new IllegalArgumentException("members are not strictly UUID-sorted");
			previousMemberId = member.computerId();
		}
		Set<Map.Entry<UUID, Integer>> occupied = new HashSet<>();
		for (Map.Entry<UUID, ProcessRecord> entry : processes.entrySet()) {
			ProcessRecord process = entry.getValue();
			if (!entry.getKey().equals(process.processId()))
				throw new IllegalArgumentException("invalid process index");
			if (process.kind() != ProcessKind.AGENT
				&& process.kind() != ProcessKind.REMOTE_LISTENER
				&& process.kind() != ProcessKind.RADIO_LISTENER
				&& process.kind() != ProcessKind.LIBRARY_QUERY
				&& process.kind() != ProcessKind.SPECULATIVE_EXPANSION
				&& process.kind() != ProcessKind.PATTERN_PRIORITY_LEASE
				&& process.kind() != ProcessKind.PRIORITY_LEASE
				&& !goals.containsKey(process.goalId()))
				throw new IllegalArgumentException("orphan process");
			MemberSnapshot owner = membersById.get(process.owningComputerId());
			if (owner == null || !owner.profile().equals(process.owningComputer()))
				throw new IllegalArgumentException("process owner is outside current membership");
			if (!occupied.add(Map.entry(process.owningComputerId(), process.owningSlot()))) throw new IllegalArgumentException("duplicate occupied slot");
		}
		for (Map.Entry<UUID, ProductionEffect> entry : pendingIntents.entrySet())
			if (!entry.getKey().equals(entry.getValue().intentId())
				|| !clusterId.equals(entry.getValue().clusterId())
				|| !epochId.equals(entry.getValue().fence().epochId()))
				throw new IllegalArgumentException("pending intent has the wrong owner or fence");
		}
	}

	@Override
	public PriorityReservationSource.Reservation priorityReservation(UUID processId) {
		ProcessRecord process = processes.get(processId);
		return process == null ? null : new PriorityReservationSource.Reservation(process.processId(),
			process.owningComputerId(), process.owningComputer(),
			process.kind() == ProcessKind.PATTERN_PRIORITY_LEASE, process.state() == ProcessState.RUNNABLE);
	}

	@Override
	public PriorityReservationSource.Resident priorityResident(UUID computerId) {
		MemberSnapshot resident = member(computerId);
		return resident == null ? null : new PriorityReservationSource.Resident(resident.computerId(),
			resident.profile(), resident.loaded());
	}

	public ClusterEngineState(UUID clusterId, UUID epochId, long generation,
		long nextSubmissionSequence, int memberCursor, int processCursor,
		Map<UUID, GoalRecord> goals, Map<UUID, Long> submissionSequences,
		Map<UUID, ProcessKind> requestedKinds, Map<UUID, ProcessRecord> processes,
		List<MemberSnapshot> members, Map<UUID, ProductionEffect> pendingIntents,
		Set<UUID> uncertainIntentIds, int goalScanOffset, UUID goalScanCandidate) {
		this(clusterId, epochId, generation, nextSubmissionSequence, 0, memberCursor, processCursor,
			goals, submissionSequences, requestedKinds, processes, members, pendingIntents,
			uncertainIntentIds, goalScanOffset, goalScanCandidate,
			new DefaultRoutePolicy.CursorState(Map.of()), Map.of());
	}

	public ClusterEngineState(UUID clusterId, UUID epochId, long generation,
		long nextSubmissionSequence, int memberCursor, int processCursor,
		Map<UUID, GoalRecord> goals, Map<UUID, Long> submissionSequences,
		Map<UUID, ProcessKind> requestedKinds, Map<UUID, ProcessRecord> processes,
		List<MemberSnapshot> members, Map<UUID, ProductionEffect> pendingIntents,
		Set<UUID> uncertainIntentIds, int goalScanOffset, UUID goalScanCandidate,
		DefaultRoutePolicy.CursorState routeCursor) {
		this(clusterId, epochId, generation, nextSubmissionSequence, 0, memberCursor, processCursor,
			goals, submissionSequences, requestedKinds, processes, members, pendingIntents,
			uncertainIntentIds, goalScanOffset, goalScanCandidate, routeCursor, Map.of());
	}

	public ClusterEngineState(UUID clusterId, UUID epochId, long generation,
		long nextSubmissionSequence, int memberCursor, int processCursor,
		Map<UUID, GoalRecord> goals, Map<UUID, Long> submissionSequences,
		Map<UUID, ProcessKind> requestedKinds, Map<UUID, ProcessRecord> processes,
		List<MemberSnapshot> members, Map<UUID, ProductionEffect> pendingIntents,
		Set<UUID> uncertainIntentIds, int goalScanOffset, UUID goalScanCandidate,
		DefaultRoutePolicy.CursorState routeCursor,
		Map<UUID, CoordinationAssociation> coordinationAssociations) {
		this(clusterId, epochId, generation, nextSubmissionSequence, 0,
			memberCursor, processCursor, goals, submissionSequences, requestedKinds, processes,
			members, pendingIntents, uncertainIntentIds, goalScanOffset, goalScanCandidate,
			routeCursor, coordinationAssociations, Map.of());
	}

	public ClusterEngineState(UUID clusterId, UUID epochId, long generation,
		long nextSubmissionSequence, long nextRouteSelectionSequence,
		int memberCursor, int processCursor,
		Map<UUID, GoalRecord> goals, Map<UUID, Long> submissionSequences,
		Map<UUID, ProcessKind> requestedKinds, Map<UUID, ProcessRecord> processes,
		List<MemberSnapshot> members, Map<UUID, ProductionEffect> pendingIntents,
		Set<UUID> uncertainIntentIds, int goalScanOffset, UUID goalScanCandidate,
		DefaultRoutePolicy.CursorState routeCursor,
		Map<UUID, CoordinationAssociation> coordinationAssociations) {
		this(clusterId, epochId, generation, nextSubmissionSequence,
			nextRouteSelectionSequence, memberCursor, processCursor, goals, submissionSequences,
			requestedKinds, processes, members, pendingIntents, uncertainIntentIds,
			goalScanOffset, goalScanCandidate, routeCursor, coordinationAssociations, Map.of());
	}

	public static ClusterEngineState empty(UUID clusterId, UUID epochId) {
		return new ClusterEngineState(clusterId, epochId, 0, 0, 0, 0,
			Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Set.of(), 0, null);
	}

	public MemberSnapshot member(UUID computerId) {
		Objects.requireNonNull(computerId, "computerId");
		return members.stream().filter(member -> member.computerId().equals(computerId)).findFirst().orElse(null);
	}

	public ClusterEngineState withRouteCursor(DefaultRoutePolicy.CursorState cursor) {
		return new ClusterEngineState(clusterId, epochId, generation, nextSubmissionSequence,
			nextRouteSelectionSequence,
			memberCursor, processCursor, goals, submissionSequences, requestedKinds, processes,
			members, pendingIntents, uncertainIntentIds, goalScanOffset, goalScanCandidate, cursor,
			coordinationAssociations, priorityLeaseAssociations);
	}

	public ClusterEngineState withNextRouteSelectionSequence(long sequence) {
		return new ClusterEngineState(clusterId, epochId, generation, nextSubmissionSequence,
			sequence, memberCursor, processCursor, goals, submissionSequences, requestedKinds,
			processes, members, pendingIntents, uncertainIntentIds, goalScanOffset,
			goalScanCandidate, routeCursor, coordinationAssociations, priorityLeaseAssociations);
	}

	public ClusterEngineState withCoordinationAssociation(CoordinationAssociation association) {
		Objects.requireNonNull(association);
		Map<UUID, CoordinationAssociation> next = new LinkedHashMap<>(coordinationAssociations);
		next.put(association.processId(), association);
		return new ClusterEngineState(clusterId, epochId, generation, nextSubmissionSequence,
			nextRouteSelectionSequence, memberCursor, processCursor, goals, submissionSequences,
			requestedKinds, processes,
			members, pendingIntents, uncertainIntentIds, goalScanOffset, goalScanCandidate, routeCursor,
			next, priorityLeaseAssociations);
	}

	/** Reinstalls one previously durable, negatively-confirmed intent without minting authority. */
	ClusterEngineState withRetriedPendingIntent(ProductionEffect intent) {
		intent = Objects.requireNonNull(intent, "intent");
		if (!clusterId.equals(intent.clusterId()) || !epochId.equals(intent.fence().epochId())
			|| intent.generation() > generation)
			throw new IllegalArgumentException("retried intent is outside the current Cluster fence");
		ProductionEffect prior = pendingIntents.get(intent.intentId());
		if (prior != null) {
			if (!prior.equals(intent)) throw new IllegalArgumentException("retried intent identity collision");
			return this;
		}
		if (pendingIntents.size() == MAX_PENDING_INTENTS)
			throw new IllegalStateException("pending intent invariant exceeded");
		Map<UUID, ProductionEffect> pending = new LinkedHashMap<>(pendingIntents);
		pending.put(intent.intentId(), intent);
		Set<UUID> uncertain = new LinkedHashSet<>(uncertainIntentIds);
		uncertain.add(intent.intentId());
		return new ClusterEngineState(clusterId, epochId, generation, nextSubmissionSequence,
			nextRouteSelectionSequence, memberCursor, processCursor, goals, submissionSequences,
			requestedKinds, processes, members, pending, uncertain, goalScanOffset,
			goalScanCandidate, routeCursor, coordinationAssociations, priorityLeaseAssociations);
	}

	public record MemberSnapshot(UUID computerId, ProductionResourceProfile profile, boolean loaded,
		long routeGeneration, long fence) {
		public MemberSnapshot {
			Objects.requireNonNull(computerId, "computerId"); Objects.requireNonNull(profile, "profile");
			if (routeGeneration < 0 || fence < 0) throw new IllegalArgumentException("negative member fence");
		}
	}

	private static <K, V> Map<K, V> immutableMap(Map<K, V> source, int cap, String name) {
		Objects.requireNonNull(source, name);
		if (source.size() > cap) throw new IllegalArgumentException(name + " exceeds bound");
		if (source instanceof TrustedMap<?, ?>) return source;
		LinkedHashMap<K, V> copy = new LinkedHashMap<>();
		source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, name + " key"), Objects.requireNonNull(value, name + " value")));
		return Collections.unmodifiableMap(copy);
	}

	private static <V> List<V> immutableList(List<V> source, int cap, String name) {
		Objects.requireNonNull(source, name);
		if (source.size() > cap) throw new IllegalArgumentException(name + " exceeds bound");
		return source instanceof TrustedList<?> ? source : List.copyOf(source);
	}

	private static <V> Set<V> immutableSet(Set<V> source, int cap, String name) {
		Objects.requireNonNull(source, name);
		if (source.size() > cap) throw new IllegalArgumentException(name + " exceeds bound");
		return source instanceof TrustedSet<?> ? source : Set.copyOf(source);
	}

	static <K, V> Map<K, V> trustedMap(LinkedHashMap<K, V> source) { return new TrustedMap<>(source); }
	static <V> List<V> trustedList(ArrayList<V> source) { return new TrustedList<>(source); }
	static <V> Set<V> trustedSet(LinkedHashSet<V> source) { return new TrustedSet<>(source); }

	private static final class TrustedMap<K, V> extends AbstractMap<K, V> {
		private final Map<K, V> delegate;
		TrustedMap(LinkedHashMap<K, V> source) { delegate = Collections.unmodifiableMap(source); }
		@Override public Set<Entry<K, V>> entrySet() { return delegate.entrySet(); }
		@Override public V get(Object key) { return delegate.get(key); }
		@Override public boolean containsKey(Object key) { return delegate.containsKey(key); }
		@Override public int size() { return delegate.size(); }
	}
	private static final class TrustedList<V> extends AbstractList<V> implements RandomAccess {
		private final List<V> delegate;
		TrustedList(ArrayList<V> source) { delegate = Collections.unmodifiableList(source); }
		@Override public V get(int index) { return delegate.get(index); }
		@Override public int size() { return delegate.size(); }
	}
	private static final class TrustedSet<V> extends AbstractSet<V> {
		private final Set<V> delegate;
		TrustedSet(LinkedHashSet<V> source) { delegate = Collections.unmodifiableSet(source); }
		@Override public Iterator<V> iterator() { return delegate.iterator(); }
		@Override public boolean contains(Object value) { return delegate.contains(value); }
		@Override public int size() { return delegate.size(); }
	}
}
