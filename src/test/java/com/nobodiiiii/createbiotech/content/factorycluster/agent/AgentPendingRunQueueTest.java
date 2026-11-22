package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;

class AgentPendingRunQueueTest {
	private static final UUID AGENT_A = id(1);
	private static final UUID AGENT_B = id(2);
	private static final CreateRouteKey ROUTE_A = new CreateRouteKey(id(3), "input-a");
	private static final CreateRouteKey ROUTE_B = new CreateRouteKey(id(4), "input-b");

	@Test void defaultOrderingIsGlobalFifoWithAgentAndAddressSecondaryIndexes() {
		FakeLeases leases = new FakeLeases();
		AgentPendingRunQueue queue = queue(8, leases);
		ProductionRequestTicket first = ticket(10, AGENT_A, ROUTE_A, 0, 0);
		ProductionRequestTicket second = ticket(11, AGENT_B, ROUTE_B, 1, 0);
		ProductionRequestTicket third = ticket(12, AGENT_A, ROUTE_B, 2, 0);

		assertEquals(0, queue.nextSequence());
		assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED, admit(queue, first));
		assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED, admit(queue, second));
		assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED, admit(queue, third));
		assertEquals(List.of(first, second, third), queue.snapshot());
		assertEquals(List.of(first, third), queue.forAgent(AGENT_A));
		assertEquals(List.of(second, third), queue.forRoute(ROUTE_B));
		assertEquals(3, queue.size(), "secondary indexes must not duplicate the Run list");
		assertEquals(3, queue.nextSequence(),
			"production admission must freeze the exact global FIFO sequence");
	}

	@Test void globalCapacityComesFromRuntimeConfigWhoseServerDefaultIs256() {
		assertEquals(256, FactoryRuntimeConfig.fromServerConfig().pendingRunCapacity());
		AgentPendingRunQueue queue = new AgentPendingRunQueue(
			FactoryRuntimeConfig.fromServerConfig(), new FakeLeases());
		for (int index = 0; index < 256; index++)
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				admit(queue, ticket(1000 + index, AGENT_A, ROUTE_A, index, 0)));
		assertEquals(AgentPendingRunQueue.EnqueueResult.CAPACITY_FULL,
			admit(queue, ticket(2000, AGENT_A, ROUTE_A, 256, 0)));
		assertEquals(256, queue.size());
	}

	@Test void everyNonDefaultPriorityUsesOneOrdinaryPriorityLease() {
		FakeLeases leases = new FakeLeases();
		leases.offer(10);
		AgentPendingRunQueue queue = queue(8, leases);
		ProductionRequestTicket prioritized = ticket(20, AGENT_A, ROUTE_A, 0, 5);

		assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED, admit(queue, prioritized));
		assertEquals(List.of(ProcessKind.PRIORITY_LEASE), leases.requestedKinds);
		assertEquals(1, queue.livePriorityLeaseCount());
		assertFalse(leases.requestedAlchemist, "queue priority must consume an ordinary slot");
	}

	@Test void failedPriorityEditPreservesFifoAndTicketPriority() {
		FakeLeases leases = new FakeLeases();
		AgentPendingRunQueue queue = queue(8, leases);
		ProductionRequestTicket first = ticket(30, AGENT_A, ROUTE_A, 0, 0);
		ProductionRequestTicket second = ticket(31, AGENT_A, ROUTE_A, 1, 0);
		admit(queue, first);
		admit(queue, second);

		assertEquals(AgentPendingRunQueue.PriorityEditResult.LEASE_UNAVAILABLE,
			queue.editPriority(second.requestId(), 9));
		assertEquals(List.of(first, second), queue.snapshot());
		assertEquals(0, queue.find(second.requestId()).orElseThrow().requestedQueuePriority());
	}

	@Test void failedNonDefaultPriorityReplacementPreservesLeaseTicketAndFifo() {
		FakeLeases leases = new FakeLeases();
		leases.offer(32);
		leases.offer(33);
		AgentPendingRunQueue queue = queue(8, leases);
		ProductionRequestTicket first = ticket(32, AGENT_A, ROUTE_A, 0, 4);
		ProductionRequestTicket second = ticket(33, AGENT_A, ROUTE_A, 1, 4);
		admit(queue, first);
		admit(queue, second);
		leases.replacementsAllowed = false;

		assertEquals(AgentPendingRunQueue.PriorityEditResult.LEASE_UNAVAILABLE,
			queue.editPriority(second.requestId(), 9));
		assertEquals(List.of(first, second), queue.snapshot());
		assertEquals(second, queue.find(second.requestId()).orElseThrow());
		assertEquals(2, queue.livePriorityLeaseCount());
		assertEquals(2, leases.acquisitions, "the existing ordinary lease must be retained");
		assertTrue(leases.released.isEmpty());
	}

	@Test void leaseIsReleasedOnDequeueCancelAndResetToDefault() {
		FakeLeases leases = new FakeLeases();
		leases.offer(40);
		leases.offer(41);
		leases.offer(42);
		AgentPendingRunQueue queue = queue(8, leases);
		ProductionRequestTicket dequeued = ticket(40, AGENT_A, ROUTE_A, 0, 9);
		ProductionRequestTicket cancelled = ticket(41, AGENT_A, ROUTE_A, 1, 8);
		ProductionRequestTicket reset = ticket(42, AGENT_A, ROUTE_A, 2, 7);
		admit(queue, dequeued);
		admit(queue, cancelled);
		admit(queue, reset);

		assertEquals(Optional.of(dequeued), queue.dequeue());
		assertTrue(queue.cancel(cancelled.requestId()));
		assertEquals(AgentPendingRunQueue.PriorityEditResult.UPDATED,
			queue.editPriority(reset.requestId(), AgentPendingRunQueue.DEFAULT_PRIORITY));
		assertEquals(3, leases.released.size());
		assertEquals(0, queue.livePriorityLeaseCount());
	}

	@Test void headMaterialFailureLeavesAndReentersTailWithNewSequenceAndLease() {
		FakeLeases leases = new FakeLeases();
		leases.offer(50);
		leases.offer(51);
		AgentPendingRunQueue queue = queue(8, leases);
		ProductionRequestTicket blocked = ticket(50, AGENT_A, ROUTE_A, 0, 4);
		ProductionRequestTicket ready = ticket(51, AGENT_B, ROUTE_B, 1, 0);
		admit(queue, blocked);
		admit(queue, ready);

		ProductionRequestTicket removed = queue.failHeadMaterial().orElseThrow();
		assertEquals(blocked, removed);
		assertEquals(1, leases.released.size());
		assertEquals(Optional.of(ready), queue.peek());

		long refreshedMaterialGeneration = removed.sourceMaterialSnapshotGeneration() + 1;
		ProductionRequestTicket reentered = queue.reenterAfterMaterialRecovery(removed,
			refreshedMaterialGeneration, ownerClaim(removed)).orElseThrow();
		assertTrue(reentered.enqueueSequence() > ready.enqueueSequence());
		assertEquals(refreshedMaterialGeneration, reentered.sourceMaterialSnapshotGeneration());
		assertEquals(List.of(reentered, ready), queue.snapshot(),
			"the reacquired priority may order ahead, but the new sequence remains the FIFO tie-breaker");
		assertEquals(2, leases.acquisitions);
	}

	@Test void serverRuntimeOwnsOneSharedQueueAcrossAgentsAndIsolatesDifferentServers() {
		FakeLeases firstLeases = new FakeLeases();
		firstLeases.offer(60);
		FactoryRuntimeService first = new FactoryRuntimeService(config(2), firstLeases);
		FactoryRuntimeService second = new FactoryRuntimeService(config(2), new FakeLeases());
		first.start();
		second.start();
		try {
			AgentPendingRunQueue shared = first.pendingAgentRuns();
			assertSame(shared, first.pendingAgentRuns());
			assertNotSame(shared, second.pendingAgentRuns());
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				admit(shared, ticket(60, AGENT_A, ROUTE_A, 0, 5)));
			assertEquals(AgentPendingRunQueue.EnqueueResult.ACCEPTED,
				admit(shared, ticket(61, AGENT_B, ROUTE_B, 1, 0)));
			assertEquals(List.of(ticket(60, AGENT_A, ROUTE_A, 0, 5)), shared.forAgent(AGENT_A));
			assertEquals(AgentPendingRunQueue.EnqueueResult.CAPACITY_FULL,
				admit(shared, ticket(62, AGENT_A, ROUTE_A, 2, 0)));
			assertEquals(0, second.pendingAgentRuns().size());
		} finally {
			first.close();
			second.close();
		}
		assertEquals(1, firstLeases.released.size(),
			"server teardown must release every live priority lease");
		assertThrows(IllegalStateException.class, first::pendingAgentRuns);
	}

	@Test void exhaustedSequenceFailsBeforeLeaseAcquisitionOrAnyQueueMutation() {
		FakeLeases leases = new FakeLeases();
		leases.offer(70);
		AgentPendingRunQueue queue = AgentPendingRunQueue.forTesting(config(4), leases,
			Long.MAX_VALUE);
		ProductionRequestTicket exhausted = ticket(70, AGENT_A, ROUTE_A, Long.MAX_VALUE, 9);

		assertEquals(AgentPendingRunQueue.EnqueueResult.SEQUENCE_EXHAUSTED,
			admit(queue, exhausted));
		assertEquals(0, queue.size());
		assertEquals(0, leases.acquisitions);
		assertEquals(0, leases.released.size());
	}

	private static AgentPendingRunQueue queue(int capacity, FakeLeases leases) {
		return new AgentPendingRunQueue(config(capacity), leases);
	}

	private static AgentPendingRunQueue.EnqueueResult admit(AgentPendingRunQueue queue,
		ProductionRequestTicket ticket) {
		return queue.enqueue(ticket, ownerClaim(ticket));
	}

	private static AgentAddressRegistration ownerClaim(ProductionRequestTicket ticket) {
		return new AgentAddressRegistration(ticket.targetRoute(), ticket.agentId(),
			ticket.sourceClusterId(), 1, CommunicationFrequency.of("17"), AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE);
	}

	private static FactoryRuntimeConfig config(int capacity) {
		return new FactoryRuntimeConfig(50, 2000, 32, capacity, 600, 64, 64, 256,
			200, 40, 200, 8, 256, 256);
	}

	private static ProductionRequestTicket ticket(long value, UUID agentId, CreateRouteKey route,
		long sequence, int priority) {
		return new ProductionRequestTicket(id(value), id(value + 100), value, id(value + 200),
			agentId, route, id(value + 300), InputMode.BULK, 2, sequence, priority, value + 400);
	}

	private static final class FakeLeases implements AgentPendingRunQueue.PriorityLeaseAllocator {
		private final ArrayDeque<Long> offered = new ArrayDeque<>();
		private final List<ProcessKind> requestedKinds = new ArrayList<>();
		private final List<AgentPendingRunQueue.PriorityLease> released = new ArrayList<>();
		private int acquisitions;
		private boolean requestedAlchemist;
		private boolean replacementsAllowed = true;

		private void offer(long value) { offered.addLast(value); }

		@Override public Optional<AgentPendingRunQueue.PriorityLease> acquire(
			ProductionRequestTicket ticket, UUID agentOwnerClusterId, ProcessKind kind,
			boolean requiresAlchemist) {
			acquisitions++;
			requestedKinds.add(kind);
			requestedAlchemist |= requiresAlchemist;
			Long value = offered.pollFirst();
			return value == null ? Optional.empty() : Optional.of(new AgentPendingRunQueue.PriorityLease(
				id(value), agentOwnerClusterId, ticket.agentId(), ticket.requestId(), kind,
				requiresAlchemist));
		}

		@Override public void release(AgentPendingRunQueue.PriorityLease lease) {
			released.add(lease);
		}

		@Override public boolean replace(AgentPendingRunQueue.PriorityLease lease,
			ProductionRequestTicket expectedTicket, ProductionRequestTicket updatedTicket,
			UUID agentOwnerClusterId) {
			return replacementsAllowed;
		}
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
