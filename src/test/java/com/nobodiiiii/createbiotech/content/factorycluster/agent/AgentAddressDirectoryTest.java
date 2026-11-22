package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import static com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService.PublicService.AGENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService;

class AgentAddressDirectoryTest {
	private static final UUID REQUESTER = id(1);
	private static final UUID REMOTE_CLUSTER = id(2);
	private static final CreateRouteKey ROUTE = new CreateRouteKey(id(3), "mixer");

	@Test
	void publicSubnetSummaryIsAnOpaqueAgentCapability() {
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		assertTrue(directory.publicSubnetSummary().isEmpty());

		directory.publish(registration(10, 1, AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE, "17"));

		assertEquals(SubnetBoundaryService.PublicServiceSummary.of(AGENT),
			directory.publicSubnetSummary().orElseThrow());
	}

	@Test
	void exactlyOneReachableClaimResolvesUniquely() {
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		AgentAddressRegistration registration = registration(10, 1,
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE, "17");
		assertEquals(AgentAddressDirectory.PublicationResult.ACCEPTED,
			directory.publish(registration));

		AgentResolution.Unique unique = assertInstanceOf(AgentResolution.Unique.class,
			directory.resolve(ROUTE, REQUESTER, CommunicationFrequency.of("17")));
		assertEquals(registration, unique.registration());
	}

	@Test
	void twoActiveAgentsAlwaysConflictRatherThanChoosingAClaim() {
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		directory.publish(registration(20, 8, AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE, "17"));
		directory.publish(registration(10, 1, AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE, "17"));

		AgentResolution.Conflict conflict = assertInstanceOf(AgentResolution.Conflict.class,
			directory.resolve(ROUTE, REQUESTER, CommunicationFrequency.of("17")));
		assertEquals(2, conflict.registrations().size());
		assertEquals(id(10), conflict.registrations().getFirst().agentId(),
			"deterministic ordering is diagnostic only; resolution must not choose a winner");
	}

	@Test
	void knownOfflineUnreachableOrWrongFrequencyNeverBecomesBareFallback() {
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		directory.publish(registration(10, 1, AgentAddressRegistration.LoadState.UNLOADED,
			AgentAddressRegistration.Reachability.REACHABLE, "17"));
		assertInstanceOf(AgentResolution.KnownUnavailable.class,
			directory.resolve(ROUTE, REQUESTER, CommunicationFrequency.of("17")));

		directory.publish(registration(10, 2, AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.UNREACHABLE, "17"));
		assertInstanceOf(AgentResolution.KnownUnavailable.class,
			directory.resolve(ROUTE, REQUESTER, CommunicationFrequency.of("17")));

		directory.publish(registration(10, 3, AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE, "18"));
		assertInstanceOf(AgentResolution.KnownUnavailable.class,
			directory.resolve(ROUTE, REQUESTER, CommunicationFrequency.of("17")));
	}

	@Test
	void tombstoneMeansKnownUnavailableWhileAnUnseenRouteIsNeverRegistered() {
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		AgentAddressRegistration registration = registration(10, 4,
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE, "17");
		directory.publish(registration);
		assertEquals(AgentAddressDirectory.PublicationResult.ACCEPTED,
			directory.withdraw(ROUTE, registration.agentId(), 4));

		assertInstanceOf(AgentResolution.KnownUnavailable.class,
			directory.resolve(ROUTE, REQUESTER, CommunicationFrequency.of("17")));
		assertInstanceOf(AgentResolution.NeverRegistered.class,
			directory.resolve(new CreateRouteKey(id(4), "unknown"), REQUESTER, CommunicationFrequency.of("17")));
	}

	@Test
	void staleGenerationCannotReplaceOrWithdrawTheActiveClaim() {
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		AgentAddressRegistration current = registration(10, 5,
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE, "17");
		directory.publish(current);

		assertEquals(AgentAddressDirectory.PublicationResult.STALE,
			directory.publish(registration(10, 4, AgentAddressRegistration.LoadState.UNLOADED,
				AgentAddressRegistration.Reachability.UNREACHABLE, "17")));
		assertEquals(AgentAddressDirectory.PublicationResult.STALE,
			directory.withdraw(ROUTE, current.agentId(), 4));
		AgentResolution.Unique unique = assertInstanceOf(AgentResolution.Unique.class,
			directory.resolve(ROUTE, REQUESTER, CommunicationFrequency.of("17")));
		assertEquals(5, unique.registration().agentGeneration());
	}

	@Test
	void sameGenerationPublicationIsIdempotentOnlyWhenTheClaimIsExactlyEqual() {
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		AgentAddressRegistration original = registration(10, 5,
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE, "17");
		assertEquals(AgentAddressDirectory.PublicationResult.ACCEPTED,
			directory.publish(original));
		assertEquals(AgentAddressDirectory.PublicationResult.ACCEPTED,
			directory.publish(original));

		assertEquals(AgentAddressDirectory.PublicationResult.STALE,
			directory.publish(new AgentAddressRegistration(ROUTE, original.agentId(), id(99),
				5, CommunicationFrequency.of("17"), original.loadState(), original.reachability())));
		assertEquals(AgentAddressDirectory.PublicationResult.STALE,
			directory.publish(new AgentAddressRegistration(ROUTE, original.agentId(),
				original.ownerClusterId(), 5, CommunicationFrequency.of("18"), original.loadState(), original.reachability())));
		assertEquals(AgentAddressDirectory.PublicationResult.STALE,
			directory.publish(new AgentAddressRegistration(ROUTE, original.agentId(),
				original.ownerClusterId(), 5, CommunicationFrequency.of("17"), AgentAddressRegistration.LoadState.UNLOADED,
				AgentAddressRegistration.Reachability.UNREACHABLE)));
		assertEquals(original, assertInstanceOf(AgentResolution.Unique.class,
			directory.resolve(ROUTE, REQUESTER, CommunicationFrequency.of("17"))).registration());
	}

	@Test
	void activeAndTombstoneCapacityStopAdmissionsWithoutLosingKnownEvidence() {
		AgentAddressDirectory directory = new AgentAddressDirectory(2, 1);
		AgentAddressRegistration first = registration(10, 1,
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE, "17");
		AgentAddressRegistration second = new AgentAddressRegistration(
			new CreateRouteKey(id(3), "press"), id(11), REMOTE_CLUSTER, 1, CommunicationFrequency.of("17"),
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE);
		AgentAddressRegistration third = new AgentAddressRegistration(
			new CreateRouteKey(id(3), "saw"), id(12), REMOTE_CLUSTER, 1, CommunicationFrequency.of("17"),
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE);
		directory.publish(first);
		directory.publish(second);

		assertEquals(AgentAddressDirectory.PublicationResult.CAPACITY_FULL,
			directory.publish(third));
		assertEquals(AgentAddressDirectory.PublicationResult.ACCEPTED,
			directory.withdraw(first.route(), first.agentId(), first.agentGeneration()));
		assertEquals(AgentAddressDirectory.PublicationResult.CAPACITY_FULL,
			directory.withdraw(second.route(), second.agentId(), second.agentGeneration()));
		assertInstanceOf(AgentResolution.KnownUnavailable.class,
			directory.resolve(first.route(), REQUESTER, CommunicationFrequency.of("17")));
		assertInstanceOf(AgentResolution.KnownUnavailable.class,
			directory.resolve(second.route(), REQUESTER, CommunicationFrequency.of("17")));
		assertEquals(1, directory.tombstoneCount());
		assertEquals(1, directory.activeCount());
	}

	@Test
	void newerGenerationCanReclaimItsTombstonedRoute() {
		AgentAddressDirectory directory = new AgentAddressDirectory(1, 1);
		AgentAddressRegistration first = registration(10, 1,
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE, "17");
		directory.publish(first);
		directory.withdraw(first.route(), first.agentId(), 1);

		assertEquals(AgentAddressDirectory.PublicationResult.STALE,
			directory.publish(first));
		assertEquals(AgentAddressDirectory.PublicationResult.ACCEPTED,
			directory.publish(registration(10, 2, AgentAddressRegistration.LoadState.LOADED,
				AgentAddressRegistration.Reachability.REACHABLE, "17")));
		assertEquals(0, directory.tombstoneCount());
		assertInstanceOf(AgentResolution.Unique.class,
			directory.resolve(ROUTE, REQUESTER, CommunicationFrequency.of("17")));
	}

	@Test
	void publicationVersionIsIndependentFromMembershipResetGeneration() {
		AgentAddressDirectory directory = new AgentAddressDirectory(1, 1);
		AgentAddressRegistration loaded = registration(10, 7,
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE, "17");
		AgentAddressRegistration unloaded = registration(10, 7,
			AgentAddressRegistration.LoadState.UNLOADED,
			AgentAddressRegistration.Reachability.UNREACHABLE, "17");

		assertEquals(AgentAddressDirectory.PublicationResult.ACCEPTED,
			directory.publish(loaded, 40));
		assertEquals(AgentAddressDirectory.PublicationResult.ACCEPTED,
			directory.publish(unloaded, 41));
		AgentResolution.KnownUnavailable unavailable = assertInstanceOf(
			AgentResolution.KnownUnavailable.class,
			directory.resolve(ROUTE, REQUESTER, CommunicationFrequency.of("17")));
		assertEquals(7, unavailable.newestKnownGeneration(),
			"reset fencing consumes the membership generation, never the directory version");
	}

	private static AgentAddressRegistration registration(long agent, long generation,
		AgentAddressRegistration.LoadState loadState,
		AgentAddressRegistration.Reachability reachability, String frequency) {
		return new AgentAddressRegistration(ROUTE, id(agent), REMOTE_CLUSTER, generation,
			CommunicationFrequency.of(frequency), loadState, reachability);
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}
}
