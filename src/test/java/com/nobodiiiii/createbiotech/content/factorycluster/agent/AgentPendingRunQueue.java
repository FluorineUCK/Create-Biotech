package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;

/** One globally bounded pending queue; Agent/address views are secondary indexes only. */
public final class AgentPendingRunQueue implements AutoCloseable {
	public static final int DEFAULT_PRIORITY = 0;
	private static final Comparator<OrderKey> ORDER = Comparator
		.comparingInt(OrderKey::priority).reversed()
		.thenComparingLong(OrderKey::sequence)
		.thenComparing(OrderKey::requestId);

	private final int capacity;
	private final PriorityLeaseAllocator leaseAllocator;
	private final Map<UUID, QueueEntry> byRequest = new LinkedHashMap<>();
	private final Map<UUID, UUID> byRun = new LinkedHashMap<>();
	private final Map<UUID, LinkedHashSet<UUID>> byAgent = new LinkedHashMap<>();
	private final Map<CreateRouteKey, LinkedHashSet<UUID>> byRoute = new LinkedHashMap<>();
	private final Map<UUID, PriorityLease> priorityLeases = new LinkedHashMap<>();
	private final TreeSet<OrderKey> ordered = new TreeSet<>(ORDER);
	private long nextSequence;
	private boolean closed;

	AgentPendingRunQueue(FactoryRuntimeConfig config,
		PriorityLeaseAllocator leaseAllocator) {
		this(config, leaseAllocator, 0);
	}

	private AgentPendingRunQueue(FactoryRuntimeConfig config,
		PriorityLeaseAllocator leaseAllocator, long initialSequence) {
		this.capacity = Objects.requireNonNull(config, "config").pendingRunCapacity();
		this.leaseAllocator = Objects.requireNonNull(leaseAllocator, "leaseAllocator");
		if (initialSequence < 0) throw new IllegalArgumentException("negative initial sequence");
		this.nextSequence = initialSequence;
	}

	public static AgentPendingRunQueue createServerGlobal(FactoryRuntimeConfig config,
		PriorityLeaseAllocator leaseAllocator) {
		return new AgentPendingRunQueue(config, leaseAllocator);
	}

	static AgentPendingRunQueue forTesting(FactoryRuntimeConfig config,
		PriorityLeaseAllocator leaseAllocator, long initialSequence) {
		return new AgentPendingRunQueue(config, leaseAllocator, initialSequence);
	}

	public EnqueueResult enqueue(ProductionRequestTicket ticket,
		AgentAddressRegistration resolvedOwner) {
		ticket = Objects.requireNonNull(ticket, "ticket");
		resolvedOwner = Objects.requireNonNull(resolvedOwner, "resolvedOwner");
		if (!ticket.agentId().equals(resolvedOwner.agentId())
			|| !ticket.targetRoute().equals(resolvedOwner.route())
			|| resolvedOwner.loadState() != AgentAddressRegistration.LoadState.LOADED
			|| resolvedOwner.reachability() != AgentAddressRegistration.Reachability.REACHABLE)
			return EnqueueResult.AGENT_OWNER_MISMATCH;
		return enqueueResolved(ticket, resolvedOwner.ownerClusterId());
	}

	EnqueueResult enqueueResolved(ProductionRequestTicket ticket, UUID agentOwnerClusterId) {
		ticket = Objects.requireNonNull(ticket, "ticket");
		agentOwnerClusterId = Objects.requireNonNull(agentOwnerClusterId, "agentOwnerClusterId");
		if (closed) return EnqueueResult.CLOSED;
		if (byRequest.containsKey(ticket.requestId()) || byRun.containsKey(ticket.runId()))
			return EnqueueResult.DUPLICATE;
		if (byRequest.size() >= capacity) return EnqueueResult.CAPACITY_FULL;
		if (ticket.enqueueSequence() != nextSequence) return EnqueueResult.STALE_SEQUENCE;
		if (nextSequence == Long.MAX_VALUE) return EnqueueResult.SEQUENCE_EXHAUSTED;
		PriorityLease lease = null;
		if (ticket.requestedQueuePriority() != DEFAULT_PRIORITY) {
			lease = acquireLease(ticket, agentOwnerClusterId).orElse(null);
			if (lease == null) return EnqueueResult.PRIORITY_LEASE_UNAVAILABLE;
		}
		insert(new QueueEntry(ticket, agentOwnerClusterId));
		if (lease != null) priorityLeases.put(ticket.requestId(), lease);
		nextSequence = Math.incrementExact(nextSequence);
		return EnqueueResult.ACCEPTED;
	}

	public Optional<ProductionRequestTicket> peek() {
		return ordered.isEmpty() ? Optional.empty()
			: Optional.ofNullable(byRequest.get(ordered.getFirst().requestId()))
				.map(QueueEntry::ticket);
	}

	public Optional<ProductionRequestTicket> dequeue() {
		return peek().map(this::remove);
	}

	public boolean cancel(UUID requestId) {
		QueueEntry entry = byRequest.get(Objects.requireNonNull(requestId));
		if (entry == null) return false;
		remove(entry.ticket());
		return true;
	}

	/** Rehydrates an already-durable ticket without pretending its historical sequence is new. */
	EnqueueResult restoreDurable(ProductionRequestTicket ticket,
		AgentAddressRegistration resolvedOwner) {
		ticket = Objects.requireNonNull(ticket, "ticket");
		resolvedOwner = Objects.requireNonNull(resolvedOwner, "resolvedOwner");
		Optional<ProductionRequestTicket> existing = find(ticket.requestId());
		if (existing.isPresent())
			return existing.filter(ticket::equals).isPresent()
				? EnqueueResult.ACCEPTED : EnqueueResult.DUPLICATE;
		if (!ticket.agentId().equals(resolvedOwner.agentId())
			|| !ticket.targetRoute().equals(resolvedOwner.route())
			|| resolvedOwner.loadState() != AgentAddressRegistration.LoadState.LOADED
			|| resolvedOwner.reachability() != AgentAddressRegistration.Reachability.REACHABLE)
			return EnqueueResult.AGENT_OWNER_MISMATCH;
		if (closed) return EnqueueResult.CLOSED;
		if (byRun.containsKey(ticket.runId())) return EnqueueResult.DUPLICATE;
		if (byRequest.size() >= capacity) return EnqueueResult.CAPACITY_FULL;
		PriorityLease lease = null;
		if (ticket.requestedQueuePriority() != DEFAULT_PRIORITY) {
			lease = acquireLease(ticket, resolvedOwner.ownerClusterId()).orElse(null);
			if (lease == null) return EnqueueResult.PRIORITY_LEASE_UNAVAILABLE;
		}
		insert(new QueueEntry(ticket, resolvedOwner.ownerClusterId()));
		if (lease != null) priorityLeases.put(ticket.requestId(), lease);
		nextSequence = Math.max(nextSequence, Math.incrementExact(ticket.enqueueSequence()));
		return EnqueueResult.ACCEPTED;
	}

	EnqueueResult preflightEnqueue(ProductionRequestTicket ticket,
		AgentAddressRegistration resolvedOwner) {
		ticket = Objects.requireNonNull(ticket, "ticket");
		resolvedOwner = Objects.requireNonNull(resolvedOwner, "resolvedOwner");
		if (!ticket.agentId().equals(resolvedOwner.agentId())
			|| !ticket.targetRoute().equals(resolvedOwner.route())
			|| resolvedOwner.loadState() != AgentAddressRegistration.LoadState.LOADED
			|| resolvedOwner.reachability() != AgentAddressRegistration.Reachability.REACHABLE)
			return EnqueueResult.AGENT_OWNER_MISMATCH;
		if (closed) return EnqueueResult.CLOSED;
		if (byRequest.containsKey(ticket.requestId()) || byRun.containsKey(ticket.runId()))
			return EnqueueResult.DUPLICATE;
		if (byRequest.size() >= capacity) return EnqueueResult.CAPACITY_FULL;
		if (ticket.enqueueSequence() != nextSequence) return EnqueueResult.STALE_SEQUENCE;
		return nextSequence == Long.MAX_VALUE
			? EnqueueResult.SEQUENCE_EXHAUSTED : EnqueueResult.ACCEPTED;
	}

	EnqueueResult preflightGenerationReplacement(ProductionRequestTicket expected,
		ProductionRequestTicket replacement, AgentAddressRegistration resolvedOwner) {
		expected = Objects.requireNonNull(expected, "expected");
		replacement = Objects.requireNonNull(replacement, "replacement");
		resolvedOwner = Objects.requireNonNull(resolvedOwner, "resolvedOwner");
		QueueEntry current = byRequest.get(expected.requestId());
		if (current == null || !current.ticket().equals(expected)) return EnqueueResult.DUPLICATE;
		if (!replacement.requestId().equals(expected.requestId())
			|| !replacement.runId().equals(expected.runId())
			|| replacement.requestedQueuePriority() != expected.requestedQueuePriority()
			|| replacement.enqueueSequence() != nextSequence)
			return EnqueueResult.STALE_SEQUENCE;
		if (!replacement.agentId().equals(resolvedOwner.agentId())
			|| !replacement.targetRoute().equals(resolvedOwner.route())
			|| resolvedOwner.loadState() != AgentAddressRegistration.LoadState.LOADED
			|| resolvedOwner.reachability() != AgentAddressRegistration.Reachability.REACHABLE)
			return EnqueueResult.AGENT_OWNER_MISMATCH;
		return nextSequence == Long.MAX_VALUE
			? EnqueueResult.SEQUENCE_EXHAUSTED : EnqueueResult.ACCEPTED;
	}

	/** Atomically replaces one queued pre-intent ticket while retaining its priority lease. */
	EnqueueResult replaceGeneration(ProductionRequestTicket expected,
		ProductionRequestTicket replacement, AgentAddressRegistration resolvedOwner) {
		EnqueueResult preflight = preflightGenerationReplacement(expected, replacement,
			resolvedOwner);
		if (preflight != EnqueueResult.ACCEPTED) return preflight;
		ordered.remove(OrderKey.from(expected));
		byRequest.put(expected.requestId(), new QueueEntry(replacement,
			resolvedOwner.ownerClusterId()));
		ordered.add(OrderKey.from(replacement));
		nextSequence = Math.incrementExact(nextSequence);
		return EnqueueResult.ACCEPTED;
	}

	public PriorityEditResult editPriority(UUID requestId, int priority) {
		QueueEntry currentEntry = byRequest.get(Objects.requireNonNull(requestId));
		if (currentEntry == null) return PriorityEditResult.NOT_FOUND;
		ProductionRequestTicket current = currentEntry.ticket();
		if (current.requestedQueuePriority() == priority) return PriorityEditResult.UNCHANGED;
		ProductionRequestTicket updated = current.withPriority(priority);
		PriorityLease acquired = null;
		if (current.requestedQueuePriority() == DEFAULT_PRIORITY && priority != DEFAULT_PRIORITY) {
			acquired = acquireLease(updated, currentEntry.agentOwnerClusterId()).orElse(null);
			if (acquired == null) return PriorityEditResult.LEASE_UNAVAILABLE;
		} else if (current.requestedQueuePriority() != DEFAULT_PRIORITY
			&& priority != DEFAULT_PRIORITY) {
			PriorityLease retained = priorityLeases.get(requestId);
			if (retained == null || !leaseAllocator.replace(retained, current, updated,
				currentEntry.agentOwnerClusterId()))
				return PriorityEditResult.LEASE_UNAVAILABLE;
		}

		ordered.remove(OrderKey.from(current));
		byRequest.put(requestId, new QueueEntry(updated, currentEntry.agentOwnerClusterId()));
		ordered.add(OrderKey.from(updated));
		if (acquired != null) priorityLeases.put(requestId, acquired);
		if (priority == DEFAULT_PRIORITY) releaseLease(requestId);
		return PriorityEditResult.UPDATED;
	}

	public Optional<ProductionRequestTicket> failHeadMaterial() {
		return dequeue();
	}

	public Optional<ProductionRequestTicket> reenterAfterMaterialRecovery(
		ProductionRequestTicket removed, long refreshedMaterialSnapshotGeneration,
		AgentAddressRegistration resolvedOwner) {
		removed = Objects.requireNonNull(removed, "removed");
		if (refreshedMaterialSnapshotGeneration <= removed.sourceMaterialSnapshotGeneration())
			throw new IllegalArgumentException("Material recovery needs a newer source snapshot");
		ProductionRequestTicket reentered = removed.withEnqueueSequence(nextSequence)
			.withMaterialSnapshotGeneration(refreshedMaterialSnapshotGeneration);
		return enqueue(reentered, resolvedOwner) == EnqueueResult.ACCEPTED
			? Optional.of(reentered) : Optional.empty();
	}

	public Optional<ProductionRequestTicket> find(UUID requestId) {
		return Optional.ofNullable(byRequest.get(Objects.requireNonNull(requestId))).map(QueueEntry::ticket);
	}

	public List<ProductionRequestTicket> snapshot() {
		return ordered.stream().map(key -> byRequest.get(key.requestId()).ticket()).toList();
	}
	public List<ProductionRequestTicket> forAgent(UUID agentId) {
		return indexed(byAgent.get(Objects.requireNonNull(agentId)));
	}
	public List<ProductionRequestTicket> forRoute(CreateRouteKey route) {
		return indexed(byRoute.get(Objects.requireNonNull(route)));
	}
	public int size() { return byRequest.size(); }
	public int capacity() { return capacity; }
	public long nextSequence() { return nextSequence; }
	public int livePriorityLeaseCount() { return priorityLeases.size(); }

	@Override public void close() {
		if (closed) return;
		closed = true;
		for (QueueEntry entry : List.copyOf(byRequest.values())) remove(entry.ticket());
	}

	private Optional<PriorityLease> acquireLease(ProductionRequestTicket ticket,
		UUID agentOwnerClusterId) {
		Optional<PriorityLease> allocated = leaseAllocator.acquire(ticket, agentOwnerClusterId,
			ProcessKind.PRIORITY_LEASE, false);
		if (allocated.isEmpty()) return Optional.empty();
		PriorityLease lease = allocated.orElseThrow();
		if (lease.kind() != ProcessKind.PRIORITY_LEASE || lease.requiresAlchemist()
			|| !lease.ownerClusterId().equals(agentOwnerClusterId)
			|| !lease.agentId().equals(ticket.agentId())
			|| !lease.requestId().equals(ticket.requestId())) {
			leaseAllocator.release(lease);
			throw new IllegalArgumentException("Allocator returned a non-ordinary priority lease");
		}
		return Optional.of(lease);
	}

	private void insert(QueueEntry entry) {
		ProductionRequestTicket ticket = entry.ticket();
		byRequest.put(ticket.requestId(), entry);
		byRun.put(ticket.runId(), ticket.requestId());
		byAgent.computeIfAbsent(ticket.agentId(), ignored -> new LinkedHashSet<>())
			.add(ticket.requestId());
		byRoute.computeIfAbsent(ticket.targetRoute(), ignored -> new LinkedHashSet<>())
			.add(ticket.requestId());
		ordered.add(OrderKey.from(ticket));
	}

	private ProductionRequestTicket remove(ProductionRequestTicket ticket) {
		ordered.remove(OrderKey.from(ticket));
		byRequest.remove(ticket.requestId());
		byRun.remove(ticket.runId());
		removeIndex(byAgent, ticket.agentId(), ticket.requestId());
		removeIndex(byRoute, ticket.targetRoute(), ticket.requestId());
		releaseLease(ticket.requestId());
		return ticket;
	}

	private void releaseLease(UUID requestId) {
		PriorityLease lease = priorityLeases.remove(requestId);
		if (lease != null) leaseAllocator.release(lease);
	}

	private List<ProductionRequestTicket> indexed(Set<UUID> requestIds) {
		if (requestIds == null) return List.of();
		ArrayList<ProductionRequestTicket> result = new ArrayList<>(requestIds.size());
		for (UUID requestId : requestIds) {
			QueueEntry entry = byRequest.get(requestId);
			if (entry != null) result.add(entry.ticket());
		}
		result.sort(Comparator.comparing(ProductionRequestTicket::requestedQueuePriority).reversed()
			.thenComparingLong(ProductionRequestTicket::enqueueSequence)
			.thenComparing(ProductionRequestTicket::requestId));
		return List.copyOf(result);
	}

	private static <K> void removeIndex(Map<K, LinkedHashSet<UUID>> index, K key,
		UUID requestId) {
		LinkedHashSet<UUID> requestIds = index.get(key);
		if (requestIds == null) return;
		requestIds.remove(requestId);
		if (requestIds.isEmpty()) index.remove(key);
	}

	private record OrderKey(int priority, long sequence, UUID requestId) {
		private OrderKey {
			requestId = Objects.requireNonNull(requestId, "requestId");
		}
		private static OrderKey from(ProductionRequestTicket ticket) {
			return new OrderKey(ticket.requestedQueuePriority(), ticket.enqueueSequence(),
				ticket.requestId());
		}
	}

	public interface PriorityLeaseAllocator {
		Optional<PriorityLease> acquire(ProductionRequestTicket ticket, UUID agentOwnerClusterId,
			ProcessKind kind,
			boolean requiresAlchemist);
		boolean replace(PriorityLease lease, ProductionRequestTicket expectedTicket,
			ProductionRequestTicket updatedTicket, UUID agentOwnerClusterId);
		void release(PriorityLease lease);
	}

	private record QueueEntry(ProductionRequestTicket ticket, UUID agentOwnerClusterId) {
		private QueueEntry {
			ticket = Objects.requireNonNull(ticket, "ticket");
			agentOwnerClusterId = Objects.requireNonNull(agentOwnerClusterId, "agentOwnerClusterId");
		}
	}

	public record PriorityLease(UUID processId, UUID ownerClusterId, UUID agentId, UUID requestId,
		ProcessKind kind, boolean requiresAlchemist) {
		public PriorityLease {
			processId = Objects.requireNonNull(processId, "processId");
			ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
			agentId = Objects.requireNonNull(agentId, "agentId");
			requestId = Objects.requireNonNull(requestId, "requestId");
			kind = Objects.requireNonNull(kind, "kind");
		}
	}

	public enum EnqueueResult {
		ACCEPTED, CAPACITY_FULL, PRIORITY_LEASE_UNAVAILABLE, AGENT_OWNER_MISMATCH, DUPLICATE, STALE_SEQUENCE,
		SEQUENCE_EXHAUSTED, CLOSED
	}
	public enum PriorityEditResult { UPDATED, UNCHANGED, LEASE_UNAVAILABLE, NOT_FOUND }
}
