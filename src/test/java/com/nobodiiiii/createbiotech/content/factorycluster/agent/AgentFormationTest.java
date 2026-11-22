package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationCommandService;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ProductionAgentRoleTransitionPolicy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class AgentFormationTest {
	private static final UUID AGENT = id(1);
	private static final UUID CLUSTER = id(2);
	private static final UUID NETWORK = id(3);

	@BeforeAll static void bootstrapMinecraftRegistries() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) == null)
				loadingModList.getMethod("of", List.class, List.class, List.class, List.class,
					java.util.Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(),
					java.util.Map.of());
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			java.lang.reflect.Field bootstrapped = net.minecraft.server.Bootstrap.class
				.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test void formationNeedsAtLeastOneLoadedInputAndOutput() {
		assertThrows(IllegalArgumentException.class,
			() -> AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT))));
		assertThrows(IllegalArgumentException.class,
			() -> AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.OUTPUT))));
		assertThrows(IllegalArgumentException.class, () -> AgentFormation.form(List.of(
			endpoint(0, AgentEndpointRole.INPUT), endpoint(1, AgentEndpointRole.OUTPUT, false))));
	}

	@Test void declaredAgentRequiresAgentRoleAndStableClusterServiceIdentity() {
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT)));
		ClusterCommunicationConfiguration standard = new ClusterCommunicationConfiguration(CLUSTER,
			CommunicationFrequency.of("17"), ClusterServiceRole.STANDARD, 0);
		ClusterCommunicationConfiguration agent = new ClusterCommunicationConfiguration(CLUSTER,
			CommunicationFrequency.of("17"), ClusterServiceRole.AGENT, 1);

		assertThrows(IllegalArgumentException.class,
			() -> formation.requireDeclaredAgent(standard));
		assertEquals(new AgentFormation.AgentServiceIdentity(AGENT, 7),
			formation.requireDeclaredAgent(agent));
	}

	@Test void formationSupportsMultipleEndpointsAndElectsLowestLoadedSpaceAddress() {
		AgentEndpointState inputHigh = endpoint(9, AgentEndpointRole.INPUT);
		AgentEndpointState inputLow = endpoint(-4, AgentEndpointRole.INPUT);
		AgentEndpointState output = endpoint(2, AgentEndpointRole.OUTPUT);
		AgentFormation formation = AgentFormation.form(List.of(inputHigh, output, inputLow));

		assertEquals(List.of(inputLow, inputHigh), formation.inputs());
		assertEquals(List.of(output), formation.outputs());
		assertEquals(inputLow.endpointAddress(), formation.coordinatorAddress());
		assertEquals(3, formation.members().size());
	}

	@Test void mixedIdentityOwnerOrGenerationCannotFormOneAgent() {
		AgentEndpointState input = endpoint(0, AgentEndpointRole.INPUT);
		AgentEndpointState output = endpoint(1, AgentEndpointRole.OUTPUT);
		assertThrows(IllegalArgumentException.class, () -> AgentFormation.form(List.of(input,
			output.withAgentIdentity(id(90), CLUSTER, 7))));
		assertThrows(IllegalArgumentException.class, () -> AgentFormation.form(List.of(input,
			output.withAgentIdentity(AGENT, id(91), 7))));
		assertThrows(IllegalArgumentException.class, () -> AgentFormation.form(List.of(input,
			output.withAgentIdentity(AGENT, CLUSTER, 8))));
	}

	@Test void directFormationConstructionRejectsRolePartitionCorruption() {
		AgentEndpointState input = endpoint(0, AgentEndpointRole.INPUT);
		AgentEndpointState output = endpoint(1, AgentEndpointRole.OUTPUT);
		assertThrows(IllegalArgumentException.class, () -> new AgentFormation(AGENT, CLUSTER, 7,
			input.endpointAddress(), List.of(output), List.of(input), List.of(input, output)));
	}

	@Test void oneResidentAlchemistProcessOwnsAllEndpointsEvenWhileIdleAndOnlyOneContract() {
		FakeSlots slots = new FakeSlots();
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.INPUT), endpoint(2, AgentEndpointRole.OUTPUT)));
		AgentProcess process = AgentProcess.form(formation, slots);

		assertTrue(process.available());
		assertEquals(1, slots.acquisitions);
		assertEquals(ProcessKind.AGENT, slots.requestedKinds.getFirst());
		assertEquals(3, process.managedEndpoints().size());
		assertTrue(process.activeContract().isEmpty(), "idle residency must retain the slot");

		UUID first = id(100);
		assertTrue(process.startContract(first));
		assertFalse(process.startContract(id(101)), "one Agent executes one contract at a time");
		assertTrue(process.finishContract(first));
		assertTrue(process.activeContract().isEmpty());
		assertEquals(0, slots.releases, "idleness must not release the resident slot");
	}

	@Test void membershipOrEpochLossFailsClosedWhilePreservingActiveContractForRecovery() {
		FakeSlots slots = new FakeSlots();
		AgentFormation original = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT)));
		AgentProcess process = AgentProcess.form(original, slots);
		UUID contract = id(110);
		assertTrue(process.startContract(contract));

		AgentFormation changed = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT), endpoint(2, AgentEndpointRole.OUTPUT)));
		assertFalse(process.refreshFormation(changed));
		assertFalse(process.available());
		assertEquals(Optional.of(contract), process.activeContract());

		slots.invalidateCurrent();
		process.reconcileResidentSlot();
		assertFalse(process.available());
		assertEquals(Optional.of(contract), process.activeContract());
		slots.offer(slot(900));
		assertTrue(process.recover(original));
		assertEquals(Optional.of(contract), process.activeContract());
	}

	@Test void activeRoleChangeAtTheSamePhysicalAddressesAlsoFailsClosed() {
		FakeSlots slots = new FakeSlots();
		AgentEndpointState input = endpoint(0, AgentEndpointRole.INPUT);
		AgentEndpointState output = endpoint(1, AgentEndpointRole.OUTPUT);
		AgentFormation original = AgentFormation.form(List.of(input, output));
		AgentProcess process = AgentProcess.form(original, slots);
		assertTrue(process.startContract(id(115)));
		AgentFormation swapped = AgentFormation.form(List.of(withRole(input, AgentEndpointRole.OUTPUT),
			withRole(output, AgentEndpointRole.INPUT)));

		assertFalse(process.refreshFormation(swapped));
		assertFalse(process.available());
	}

	@Test void coordinatorPublishesOnlyInputRegistrations() {
		FakeSlots slots = new FakeSlots();
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.INPUT), endpoint(2, AgentEndpointRole.OUTPUT)));
		AgentCoordinator coordinator = new AgentCoordinator(formation, AgentProcess.form(formation, slots),
			state -> {}, 8);
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);

		assertEquals(2, coordinator.publishInputRegistrations(directory, declaration()));
		assertEquals(2, directory.activeCount());
		assertTrue(directory.resolve(formation.outputs().getFirst().route(), CLUSTER, CommunicationFrequency.of("17"))
			instanceof AgentResolution.NeverRegistered);
	}

	@Test void declaredClusterPrimaryFrequencyOwnsAgentPublication() {
		FakeSlots slots = new FakeSlots();
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT)));
		AgentCoordinator coordinator = new AgentCoordinator(formation,
			AgentProcess.form(formation, slots), state -> {}, 8);
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		ClusterCommunicationConfiguration declaration = new ClusterCommunicationConfiguration(CLUSTER,
			CommunicationFrequency.of("cluster-primary"), ClusterServiceRole.AGENT, 1);

		assertEquals(1, coordinator.publishInputRegistrations(directory, declaration));
		assertTrue(directory.resolve(formation.inputs().getFirst().route(), id(90),
			CommunicationFrequency.of("cluster-primary")) instanceof AgentResolution.Unique);
		assertTrue(directory.resolve(formation.inputs().getFirst().route(), id(90),
			CommunicationFrequency.of("17")) instanceof AgentResolution.KnownUnavailable);
	}

	@Test void anUndeclaredFormationCannotPublishItsEndpointFrequency() {
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT)));
		AgentCoordinator coordinator = new AgentCoordinator(formation,
			AgentProcess.form(formation, new FakeSlots()), state -> {}, 8);
		assertThrows(IllegalArgumentException.class, () -> coordinator.publishInputRegistrations(
			new AgentAddressDirectory(8, 8), new ClusterCommunicationConfiguration(CLUSTER,
				CommunicationFrequency.UNCONFIGURED, ClusterServiceRole.STANDARD, 0)));
	}

	@Test
	void durableStandardRestartThenLoadedEndpointsCanDeclareAndRepublishExactAgent() {
		UUID epoch = id(200), panel = id(201);
		FactoryOrchestrationSavedData firstJournal = new FactoryOrchestrationSavedData();
		assertFalse(firstJournal.isDirty());
		ClusterCommunicationDirectory firstDirectory = new ClusterCommunicationDirectory(
			firstJournal.clusterCommunicationStore(RegistryAccess.EMPTY));
		ClusterCommunicationConfiguration standard = firstDirectory.ensure(CLUSTER);
		assertEquals(ClusterServiceRole.STANDARD, standard.role());
		assertEquals(CommunicationFrequency.UNCONFIGURED, standard.primaryFrequency());
		assertTrue(firstJournal.isDirty(), "default declaration must dirty SavedData");

		FactoryOrchestrationSavedData restartedJournal = reload(firstJournal);
		assertFalse(restartedJournal.isDirty(), "a decoded SavedData snapshot starts clean");
		ClusterCommunicationDirectory restartedDirectory = new ClusterCommunicationDirectory(
			restartedJournal.clusterCommunicationStore(RegistryAccess.EMPTY));
		assertEquals(standard, restartedDirectory.configuration(CLUSTER).orElseThrow());

		AgentFormation formation = AgentFormation.form(List.of(
			endpoint(0, AgentEndpointRole.INPUT), endpoint(1, AgentEndpointRole.OUTPUT)));
		ClusterCommunicationCommandService commands = new ClusterCommunicationCommandService(
			restartedDirectory,
			authority -> ClusterCommunicationCommandService.AuthorityStatus.READY,
			new ProductionAgentRoleTransitionPolicy(cluster -> cluster.equals(CLUSTER)
				&& !formation.inputs().isEmpty() && !formation.outputs().isEmpty(), cluster -> {
					throw new AssertionError("Agent declaration must not request revocation evidence");
				}));
		var authority = new ClusterCommunicationCommandService.CommandAuthority(CLUSTER, epoch,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, id(202)), panel);
		assertEquals(ClusterCommunicationCommandService.ChangeResult.APPLIED,
			commands.changeRole(authority, ClusterServiceRole.AGENT, standard.revision()));
		ClusterCommunicationConfiguration declared = commands.configuration(CLUSTER).orElseThrow();
		assertTrue(restartedJournal.isDirty(), "role CAS must dirty the restarted SavedData");

		AgentCoordinator first = AgentCoordinator.formOrRestore(formation, new FakeSlots(),
			restartedJournal.agentCoordinatorStore(RegistryAccess.EMPTY), 8);
		AgentAddressDirectory firstClaims = new AgentAddressDirectory(8, 8);
		assertEquals(1, first.publishInputRegistrations(firstClaims, declared));
		assertExactAgent(firstClaims, formation);

		FactoryOrchestrationSavedData secondRestart = reload(restartedJournal);
		ClusterCommunicationDirectory secondDirectory = new ClusterCommunicationDirectory(
			secondRestart.clusterCommunicationStore(RegistryAccess.EMPTY));
		assertEquals(declared, secondDirectory.configuration(CLUSTER).orElseThrow());
		FakeSlots restoredSlots = new FakeSlots();
		AgentCoordinator restored = AgentCoordinator.formOrRestore(formation, restoredSlots,
			secondRestart.agentCoordinatorStore(RegistryAccess.EMPTY), 8);
		assertTrue(restored.recover(formation),
			"loaded endpoints must reacquire one resident process after composition restart");
		AgentAddressDirectory restoredClaims = new AgentAddressDirectory(8, 8);
		assertEquals(1, restored.publishInputRegistrations(restoredClaims,
			secondDirectory.configuration(CLUSTER).orElseThrow()));
		assertExactAgent(restoredClaims, formation);
	}

	@Test void restoredCoordinatorReplaysIdenticalClaimsIntoFreshDirectory() {
		FakeSlots slots = new FakeSlots();
		MemoryStateStore store = new MemoryStateStore();
		AgentFormation formation = AgentFormation.form(List.of(
			endpoint(0, AgentEndpointRole.INPUT), endpoint(1, AgentEndpointRole.OUTPUT)));
		AgentCoordinator first = AgentCoordinator.formOrRestore(formation, slots, store, 8);
		AgentAddressDirectory beforeRestart = new AgentAddressDirectory(8, 8);
		assertEquals(1, first.publishInputRegistrations(beforeRestart, declaration()));

		AgentCoordinator restored = AgentCoordinator.formOrRestore(formation, slots, store, 8);
		AgentAddressDirectory freshDirectory = new AgentAddressDirectory(8, 8);
		assertEquals(1, restored.publishInputRegistrations(freshDirectory, declaration()));
		AgentAddressRegistration registration = ((AgentResolution.Unique) freshDirectory.resolve(
			formation.inputs().getFirst().route(), CLUSTER, CommunicationFrequency.of("17"))).registration();
		assertEquals(formation.agentGeneration(), registration.agentGeneration());
	}

	@Test void registrationLifecycleChangesPublicationWithoutChangingResetGeneration() {
		FakeSlots slots = new FakeSlots();
		AgentEndpointState input = endpoint(0, AgentEndpointRole.INPUT);
		AgentEndpointState output = endpoint(1, AgentEndpointRole.OUTPUT);
		AgentFormation original = AgentFormation.form(List.of(input, output));
		AgentCoordinator coordinator = new AgentCoordinator(original,
			AgentProcess.form(original, slots), state -> {}, 8);
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		coordinator.publishInputRegistrations(directory, declaration());
		long initialGeneration = ((AgentResolution.Unique) directory.resolve(input.route(),
			CLUSTER, CommunicationFrequency.of("17"))).registration().agentGeneration();

		slots.invalidateCurrent();
		coordinator.reconcileResidentSlot();
		coordinator.publishInputRegistrations(directory, declaration());
		AgentResolution.KnownUnavailable lost = (AgentResolution.KnownUnavailable)
			directory.resolve(input.route(), CLUSTER, CommunicationFrequency.of("17"));
		assertEquals(initialGeneration, lost.newestKnownGeneration());

		slots.offer(slot(925));
		assertTrue(coordinator.recover(original));
		coordinator.publishInputRegistrations(directory, declaration());
		long recoveredGeneration = ((AgentResolution.Unique) directory.resolve(input.route(),
			CLUSTER, CommunicationFrequency.of("17"))).registration().agentGeneration();
		assertEquals(lost.newestKnownGeneration(), recoveredGeneration);

		AgentFormation roleChanged = AgentFormation.form(List.of(
			withRole(input, AgentEndpointRole.OUTPUT),
			withRole(output, AgentEndpointRole.INPUT)));
		assertTrue(coordinator.refresh(roleChanged));
		coordinator.publishInputRegistrations(directory, declaration());
		assertTrue(directory.resolve(input.route(), CLUSTER, CommunicationFrequency.of("17"))
			instanceof AgentResolution.KnownUnavailable);
		assertTrue(directory.resolve(output.route(), CLUSTER, CommunicationFrequency.of("17"))
			instanceof AgentResolution.Unique);
	}

	@Test void withdrawnInputCanBeReaddedAfterSaturationWithStableMembershipGeneration() {
		FakeSlots slots = new FakeSlots();
		AgentEndpointState inputA = endpoint(0, AgentEndpointRole.INPUT);
		AgentEndpointState outputB = endpoint(1, AgentEndpointRole.OUTPUT);
		AgentEndpointState outputC = endpoint(2, AgentEndpointRole.OUTPUT);
		AgentFormation initial = AgentFormation.form(List.of(inputA, outputB, outputC));
		AgentCoordinator coordinator = new AgentCoordinator(initial,
			AgentProcess.form(initial, slots), state -> {}, 8);
		AgentAddressDirectory directory = new AgentAddressDirectory(2, 8);
		coordinator.publishInputRegistrations(directory, declaration());
		long initialGeneration = ((AgentResolution.Unique) directory.resolve(inputA.route(),
			CLUSTER, CommunicationFrequency.of("17"))).registration().agentGeneration();
		assertEquals(initial.agentGeneration(), initialGeneration,
			"the first claim may use the current membership generation");

		AgentFormation removed = AgentFormation.form(List.of(
			withRole(inputA, AgentEndpointRole.OUTPUT),
			withRole(outputB, AgentEndpointRole.INPUT), outputC));
		assertTrue(coordinator.refresh(removed));
		coordinator.publishInputRegistrations(directory, declaration());
		long tombstoneGeneration = ((AgentResolution.KnownUnavailable) directory.resolve(
			inputA.route(), CLUSTER, CommunicationFrequency.of("17"))).newestKnownGeneration();
		UUID blocker = id(778);
		directory.publish(new AgentAddressRegistration(new CreateRouteKey(NETWORK, "blocker"),
			blocker, CLUSTER, 1, CommunicationFrequency.of("17"), AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE));

		AgentFormation readded = AgentFormation.form(List.of(inputA,
			withRole(outputB, AgentEndpointRole.INPUT), outputC));
		assertTrue(coordinator.refresh(readded));
		assertEquals(1, coordinator.publishInputRegistrations(directory, declaration()),
			"the retained input stays live while the re-add is capacity-blocked");
		assertTrue(directory.resolve(inputA.route(), CLUSTER, CommunicationFrequency.of("17"))
			instanceof AgentResolution.KnownUnavailable);
		assertEquals(AgentAddressDirectory.PublicationResult.ACCEPTED,
			directory.withdraw(new CreateRouteKey(NETWORK, "blocker"), blocker, 2));

		assertEquals(2, coordinator.publishInputRegistrations(directory, declaration()));
		long readdedGeneration = ((AgentResolution.Unique) directory.resolve(inputA.route(),
			CLUSTER, CommunicationFrequency.of("17"))).registration().agentGeneration();
		assertEquals(tombstoneGeneration, readdedGeneration);
	}

	@Test void retiredRouteLedgerBackpressuresWithoutEvictingEvidenceAndReaddFreesCapacity() {
		FakeSlots slots = new FakeSlots();
		MemoryStateStore store = new MemoryStateStore();
		AgentEndpointState routeA = endpoint(0, AgentEndpointRole.INPUT);
		AgentEndpointState output = endpoint(1, AgentEndpointRole.OUTPUT);
		AgentEndpointState routeB = endpoint(2, AgentEndpointRole.INPUT);
		AgentEndpointState routeC = endpoint(3, AgentEndpointRole.INPUT);
		AgentFormation initial = AgentFormation.form(List.of(routeA, output, routeB));
		AgentCoordinator coordinator = new AgentCoordinator(initial,
			AgentProcess.form(initial, slots), store, 1);
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		assertEquals(2, coordinator.publishInputRegistrations(directory, declaration()));

		AgentFormation second = AgentFormation.form(List.of(
			withRole(routeA, AgentEndpointRole.OUTPUT), output, routeB));
		assertTrue(coordinator.refresh(second));
		assertEquals(1, coordinator.publishInputRegistrations(directory, declaration()));
		assertEquals(1, store.states.get(AGENT).retiredRouteGenerations().size());

		AgentFormation saturated = AgentFormation.form(List.of(
			withRole(routeA, AgentEndpointRole.OUTPUT), output,
			withRole(routeB, AgentEndpointRole.OUTPUT), routeC));
		assertTrue(coordinator.refresh(saturated));
		assertEquals(0, coordinator.publishInputRegistrations(directory, declaration()));
		assertTrue(directory.resolve(routeB.route(), CLUSTER, CommunicationFrequency.of("17"))
			instanceof AgentResolution.KnownUnavailable,
			"unretirable live evidence must become unavailable rather than remain falsely live");
		assertTrue(directory.resolve(routeC.route(), CLUSTER, CommunicationFrequency.of("17"))
			instanceof AgentResolution.NeverRegistered,
			"a full unresolved ledger must backpressure new claims");
		assertEquals(1, store.states.get(AGENT).retiredRouteGenerations().size());

		AgentFormation readded = AgentFormation.form(List.of(routeA, output,
			withRole(routeB, AgentEndpointRole.OUTPUT), withRole(routeC, AgentEndpointRole.OUTPUT)));
		assertTrue(coordinator.refresh(readded));
		assertEquals(1, coordinator.publishInputRegistrations(directory, declaration()));
		assertTrue(store.states.get(AGENT).retiredRouteGenerations().isEmpty(),
			"successful re-add resolves and removes its retired ledger entry");

		AgentCoordinator.PersistedState saved = store.states.get(AGENT);
		assertThrows(IllegalArgumentException.class, () -> new AgentCoordinator.PersistedState(
			saved.agentId(), saved.ownerClusterId(), saved.agentGeneration(), saved.coordinatorAddress(),
			saved.endpointSnapshots(), saved.residentSlot(), saved.activeContractState(), saved.available(),
			saved.publicationGeneration(), saved.publishedInputRegistrations(), 1,
			Map.of(routeA.route(), 1L, routeC.route(), 2L)));
	}

	@Test void slotLifecycleKeepsACompetingClaimInConflictInsteadOfFalseFallback() {
		FakeSlots slots = new FakeSlots();
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT)));
		AgentCoordinator coordinator = new AgentCoordinator(formation,
			AgentProcess.form(formation, slots), state -> {}, 8);
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		coordinator.publishInputRegistrations(directory, declaration());
		CreateRouteKey route = formation.inputs().getFirst().route();
		directory.publish(new AgentAddressRegistration(route, id(999), CLUSTER, 1, CommunicationFrequency.of("17"),
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE));
		assertTrue(directory.resolve(route, CLUSTER, CommunicationFrequency.of("17")) instanceof AgentResolution.Conflict);

		slots.invalidateCurrent();
		coordinator.reconcileResidentSlot();
		coordinator.publishInputRegistrations(directory, declaration());
		assertTrue(directory.resolve(route, CLUSTER, CommunicationFrequency.of("17")) instanceof AgentResolution.Conflict);
		slots.offer(slot(926));
		assertTrue(coordinator.recover(formation));
		coordinator.publishInputRegistrations(directory, declaration());
		assertTrue(directory.resolve(route, CLUSTER, CommunicationFrequency.of("17")) instanceof AgentResolution.Conflict);
	}

	@Test void coordinatorPersistsTheSingleActiveContractAndUnavailableRecoveryState() {
		FakeSlots slots = new FakeSlots();
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT)));
		List<AgentCoordinator.PersistedState> saved = new ArrayList<>();
		AgentCoordinator coordinator = new AgentCoordinator(formation,
			AgentProcess.form(formation, slots), saved::add, 8);
		UUID contract = id(300);

		assertTrue(coordinator.startContract(contract));
		assertEquals(Optional.of(contract), saved.getLast().activeContract());
		slots.invalidateCurrent();
		coordinator.reconcileResidentSlot();
		assertFalse(saved.getLast().available());
		assertEquals(Optional.of(contract), saved.getLast().activeContract());
	}

	@Test void preparedForceResetRetiresOldClaimsOnlyAfterTheAtomicStateIsCommitted() {
		FakeSlots slots = new FakeSlots();
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT)));
		MemoryStateStore store = new MemoryStateStore();
		AgentCoordinator coordinator = AgentCoordinator.formOrRestore(formation, slots, store, 8);
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		coordinator.publishInputRegistrations(directory, declaration());
		UUID contract = id(310);
		assertTrue(coordinator.startContract(contract, 44));
		int writesBefore = store.writes;
		AgentFormation next = AgentFormation.form(formation.members().stream()
			.map(member -> member.withAgentIdentity(AGENT, CLUSTER, 8)).toList());

		AgentCoordinator.ForceResetPreparation prepared = coordinator
			.prepareForceReset(contract, next).orElseThrow();
		assertEquals(formation, coordinator.formation(), "preflight must retain old formation");
		assertEquals(Optional.of(contract), coordinator.process().activeContract());
		assertEquals(writesBefore, store.writes, "preflight must not persist coordinator state");
		assertEquals(7, ((AgentResolution.Unique) directory.resolve(
			formation.inputs().getFirst().route(), CLUSTER, CommunicationFrequency.of("17"))).registration().agentGeneration());

		store.persist(prepared.nextState());
		coordinator.installCommittedForceReset(prepared, directory);
		assertEquals(8, coordinator.formation().agentGeneration());
		assertTrue(coordinator.process().activeContract().isEmpty());
		assertFalse(directory.resolve(formation.inputs().getFirst().route(), CLUSTER, CommunicationFrequency.of("17"))
			instanceof AgentResolution.Unique, "old generation claim must be retired");
		coordinator.publishInputRegistrations(directory, declaration());
		assertEquals(8, ((AgentResolution.Unique) directory.resolve(
			formation.inputs().getFirst().route(), CLUSTER, CommunicationFrequency.of("17"))).registration().agentGeneration());
	}

	@Test void preparedIdleMembershipChangeRetiresOldClaimsOnlyAfterTheBatchCommits() {
		FakeSlots slots = new FakeSlots();
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT)));
		MemoryStateStore store = new MemoryStateStore();
		AgentCoordinator coordinator = AgentCoordinator.formOrRestore(formation, slots, store, 8);
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		coordinator.publishInputRegistrations(directory, declaration());
		AgentEndpointState added = endpoint(2, AgentEndpointRole.INPUT)
			.withAgentIdentity(AGENT, CLUSTER, 8);
		AgentFormation next = AgentFormation.form(java.util.stream.Stream.concat(
			formation.members().stream().map(member -> member.withAgentIdentity(AGENT, CLUSTER, 8)),
			java.util.stream.Stream.of(added)).toList());
		int writesBefore = store.writes;

		AgentCoordinator.MembershipChangePreparation prepared = coordinator
			.prepareMembershipChange(next).orElseThrow();
		assertEquals(formation, coordinator.formation());
		assertEquals(writesBefore, store.writes);
		assertEquals(7, ((AgentResolution.Unique) directory.resolve(
			formation.inputs().getFirst().route(), CLUSTER, CommunicationFrequency.of("17"))).registration().agentGeneration());

		store.persist(prepared.nextState());
		coordinator.installCommittedMembershipChange(prepared, directory);
		assertEquals(next, coordinator.formation());
		assertTrue(coordinator.process().available());
		assertFalse(directory.resolve(formation.inputs().getFirst().route(), CLUSTER, CommunicationFrequency.of("17"))
			instanceof AgentResolution.Unique);
		assertEquals(2, coordinator.publishInputRegistrations(directory, declaration()));
		assertEquals(8, ((AgentResolution.Unique) directory.resolve(
			added.route(), CLUSTER, CommunicationFrequency.of("17"))).registration().agentGeneration());
	}

	@Test void coordinatorRestoresCompleteEndpointAndFencedActiveContractBeforePersisting() {
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT)));
		MemoryStateStore store = new MemoryStateStore();
		FakeSlots firstSlots = new FakeSlots();
		AgentCoordinator first = AgentCoordinator.formOrRestore(formation, firstSlots, store, 8);
		UUID contract = id(320);
		assertTrue(first.startContract(contract, 44));
		AgentCoordinator.PersistedState saved = store.load(AGENT).orElseThrow();
		assertEquals(formation.members(), saved.endpointSnapshots());
		assertEquals(44, saved.activeContractState().orElseThrow().fence());
		assertEquals(0, saved.residentSlot().orElseThrow().slotGeneration());

		FakeSlots restoredSlots = new FakeSlots();
		restoredSlots.restoreCurrent(saved.residentSlot().orElseThrow());
		int writesBeforeRestore = store.writes;
		AgentCoordinator restored = new AgentCoordinator(formation,
			AgentProcess.form(formation, restoredSlots), store, 8);
		assertEquals(writesBeforeRestore, store.writes,
			"constructor must load before it ever persists over recovery state");
		assertEquals(Optional.of(contract), restored.process().activeContract());
		assertEquals(44, restored.process().activeContractState().orElseThrow().fence());
		assertTrue(restored.process().available());
	}

	@Test void closedProcessCannotRecoverOrAcquireAnotherResidentSlot() {
		FakeSlots slots = new FakeSlots();
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT)));
		AgentProcess process = AgentProcess.form(formation, slots);
		process.close();
		slots.offer(slot(950));
		int acquisitionsAfterClose = slots.acquisitions;
		int releasesAfterClose = slots.releases;

		assertFalse(process.recover(formation));
		assertEquals(acquisitionsAfterClose, slots.acquisitions);
		assertEquals(releasesAfterClose, slots.releases);
		process.close();
		assertEquals(releasesAfterClose, slots.releases, "close must remain idempotent");
	}

	@Test void nativePanelAgentCompoundRoundTripsWithoutTouchingCreateSettings() {
		AgentSignalSelector selector = new AgentSignalSelector(new SpaceAddress(Level.OVERWORLD,
			null, new BlockPos(9, 8, 7)), Direction.NORTH, 11);
		FactoryPanelAgentAccess.Configuration configuration =
			new FactoryPanelAgentAccess.Configuration(AGENT, CLUSTER, 7, AgentEndpointRole.INPUT,
				"  mixer  ", 12, CommunicationFrequency.of("17"), AgentCompletionMode.SIGNAL,
				Optional.of(selector), AgentEndpointState.OutputMode.DIRECT_REQUIRED, 4,
				new CreateRouteKey(NETWORK, "warehouse"));
		CompoundTag root = new CompoundTag();
		root.putString("Address", "create-owned");
		root.putString("Recipe", "create-owned-recipe");
		root.putBoolean("Requester", true);

		FactoryPanelAgentAccess.writeConfiguration(root, configuration);

		assertEquals("create-owned", root.getString("Address"));
		assertEquals("create-owned-recipe", root.getString("Recipe"));
		assertTrue(root.getBoolean("Requester"));
		assertEquals("mixer", FactoryPanelAgentAccess.readConfiguration(root)
			.orElseThrow().normalizedAddress());
		assertEquals(configuration.returnWarehouseRoute(),
			FactoryPanelAgentAccess.readConfiguration(root).orElseThrow().returnWarehouseRoute());
		assertEquals(AgentCompletionMode.SIGNAL,
			FactoryPanelAgentAccess.readConfiguration(root).orElseThrow().completionMode());
		assertEquals(Optional.of(selector), FactoryPanelAgentAccess.readConfiguration(root)
			.orElseThrow().signalSelector());
		CompoundTag missingSelector = root.copy();
		missingSelector.getCompound(FactoryPanelAgentAccess.ROOT_KEY).remove("signalSelector");
		assertTrue(FactoryPanelAgentAccess.readConfiguration(missingSelector).isEmpty(),
			"old endpoint schema must not be silently accepted");
		AgentEndpointState captured = AgentEndpointState.fromNative(
			new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO), CLUSTER, NETWORK,
			FactoryPanelAgentAccess.readConfiguration(root).orElseThrow(), true, true);
		assertEquals(CLUSTER, captured.ownerClusterId());
		assertEquals(new CreateRouteKey(NETWORK, "mixer"), captured.route());
		assertEquals(AgentCompletionMode.SIGNAL, captured.completionMode());
		assertEquals(1, root.getAllKeys().stream()
			.filter(FactoryPanelAgentAccess.ROOT_KEY::equals).count());
	}

	@Test void nativeGaugePriorityHasAnOwnerLocalCurrentSchemaCompound() {
		CompoundTag root = new CompoundTag();
		root.putString("Address", "create-owned");

		FactoryPanelAgentAccess.writeProviderConfiguration(root,
			new FactoryPanelAgentAccess.ProviderConfiguration(37));

		assertEquals("create-owned", root.getString("Address"));
		assertEquals(37, FactoryPanelAgentAccess.readProviderConfiguration(root)
			.orElseThrow().routePriority());
		FactoryPanelAgentAccess.ProviderCompound reloaded =
			new FactoryPanelAgentAccess.ProviderCompound();
		reloaded.read(root);
		CompoundTag saved = new CompoundTag();
		reloaded.write(saved);
		assertEquals(37, FactoryPanelAgentAccess.readProviderConfiguration(saved)
			.orElseThrow().routePriority());
	}

	@Test void mixinCompoundHelperSurvivesReadWriteLifecycleAndRemoval() {
		FactoryPanelAgentAccess.Configuration configuration =
			new FactoryPanelAgentAccess.Configuration(AGENT, CLUSTER, 7, AgentEndpointRole.OUTPUT,
				"packing", 3, CommunicationFrequency.of("17"), AgentCompletionMode.QUIET,
				Optional.empty(), AgentEndpointState.OutputMode.WAREHOUSE_ALL, 9,
				new CreateRouteKey(NETWORK, "warehouse"));
		CompoundTag firstRoot = new CompoundTag();
		firstRoot.putString("Filter", "create-filter");
		FactoryPanelAgentAccess.AgentCompound writer =
			new FactoryPanelAgentAccess.AgentCompound();
		writer.set(configuration);
		writer.write(firstRoot);

		FactoryPanelAgentAccess.AgentCompound reader =
			new FactoryPanelAgentAccess.AgentCompound();
		reader.read(firstRoot);
		assertEquals(Optional.of(configuration), reader.get());
		CompoundTag secondRoot = firstRoot.copy();
		reader.write(secondRoot);
		assertEquals("create-filter", secondRoot.getString("Filter"));
		assertEquals(firstRoot.getCompound(FactoryPanelAgentAccess.ROOT_KEY),
			secondRoot.getCompound(FactoryPanelAgentAccess.ROOT_KEY));
		reader.set(null);
		reader.write(secondRoot);
		assertFalse(secondRoot.contains(FactoryPanelAgentAccess.ROOT_KEY));
		assertEquals("create-filter", secondRoot.getString("Filter"));
	}

	@Test void agentWorldAdapterPublishesScopedInputSourceAndCompletesCollector() {
		FakeSlots slots = new FakeSlots();
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT)));
		AgentCoordinator coordinator = new AgentCoordinator(formation,
			AgentProcess.form(formation, slots), state -> {}, 8);
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		coordinator.publishInputRegistrations(directory, declaration());
		RouteSelectionScope scope = new RouteSelectionScope(id(400), id(401),
			new StackKey(new ItemStack(Items.STONE)), 3, 0, id(402));
		FakeCollector collector = new FakeCollector();

		new AgentRouteRuntimeAdapter(collector).publish(scope, id(403), id(404),
			formation.inputs().getFirst().route(), CommunicationFrequency.of("17"), directory, List.of(coordinator));

		ProductionRouteTable.ProviderSource source = collector.sources.getFirst();
		assertEquals(scope, source.scope());
		assertEquals(RouteCandidate.ProviderKind.AGENT, source.providerKind());
		assertEquals(RouteCandidate.Availability.AVAILABLE, source.availability());
		assertEquals(formation.inputs().getFirst().routePriority(), source.effectivePriority());
		assertEquals("agent/" + AGENT + "/" + formation.agentGeneration() + "/"
			+ NETWORK + "/" + formation.inputs().getFirst().route().address(),
			source.providerKey(), "the selected Agent provider must freeze its formation generation");
		assertTrue(collector.completed, "Agent collector must explicitly complete the scope");
	}

	@Test void agentWorldAdapterRejectsUniqueClaimWhoseOwnerDoesNotMatchCoordinator() {
		FakeSlots slots = new FakeSlots();
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT)));
		AgentCoordinator coordinator = new AgentCoordinator(formation,
			AgentProcess.form(formation, slots), state -> {}, 8);
		CreateRouteKey route = formation.inputs().getFirst().route();
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		directory.publish(new AgentAddressRegistration(route, AGENT, id(777), 7, CommunicationFrequency.of("17"),
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE));
		RouteSelectionScope scope = new RouteSelectionScope(id(410), id(411),
			new StackKey(new ItemStack(Items.STONE)), 3, 0, id(412));
		FakeCollector collector = new FakeCollector();

		new AgentRouteRuntimeAdapter(collector).publish(scope, id(413), id(414), route, CommunicationFrequency.of("17"),
			directory, List.of(coordinator));

		assertEquals(RouteCandidate.Availability.UNAVAILABLE,
			collector.sources.getFirst().availability());
		assertEquals(RouteCandidate.Blocker.UNREACHABLE_KNOWN_ROUTE,
			collector.sources.getFirst().blocker());
	}

	@Test void agentWorldAdapterAllowsReachableFrequencyMatchedRemoteOwner() {
		UUID requesterCluster = id(780);
		FakeSlots slots = new FakeSlots();
		AgentFormation formation = AgentFormation.form(List.of(endpoint(0, AgentEndpointRole.INPUT),
			endpoint(1, AgentEndpointRole.OUTPUT)));
		AgentCoordinator coordinator = new AgentCoordinator(formation,
			AgentProcess.form(formation, slots), state -> {}, 8);
		AgentAddressDirectory directory = new AgentAddressDirectory(8, 8);
		coordinator.publishInputRegistrations(directory, declaration());
		RouteSelectionScope scope = new RouteSelectionScope(id(781), id(782),
			new StackKey(new ItemStack(Items.STONE)), 3, 0, id(783));
		FakeCollector collector = new FakeCollector(requesterCluster);

		new AgentRouteRuntimeAdapter(collector).publish(scope, id(784), id(785),
			formation.inputs().getFirst().route(), CommunicationFrequency.of("17"), directory, List.of(coordinator));

		assertEquals(RouteCandidate.Availability.AVAILABLE,
			collector.sources.getFirst().availability());
	}

	private static AgentEndpointState endpoint(int x, AgentEndpointRole role) {
		return endpoint(x, role, true);
	}

	private static AgentEndpointState endpoint(int x, AgentEndpointRole role, boolean loaded) {
		return new AgentEndpointState(new SpaceAddress(Level.OVERWORLD, null, new BlockPos(x, 64, 0)),
			AGENT, CLUSTER, 7, role, new CreateRouteKey(NETWORK, role.name().toLowerCase() + x),
			x, CommunicationFrequency.of("17"), AgentCompletionMode.QUIET, Optional.empty(),
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, 4,
			new CreateRouteKey(NETWORK, "warehouse"), loaded, true);
	}

	private static AgentProcess.ResidentSlot slot(long value) {
		return new AgentProcess.ResidentSlot(id(value), id(value + 1), 0, id(value + 2),
			0, ProcessKind.AGENT, true);
	}

	private static AgentEndpointState withRole(AgentEndpointState endpoint,
		AgentEndpointRole role) {
		return new AgentEndpointState(endpoint.endpointAddress(), endpoint.agentId(),
			endpoint.ownerClusterId(), endpoint.agentGeneration(), role, endpoint.route(),
			endpoint.routePriority(), endpoint.frequency(), endpoint.completionMode(),
			endpoint.signalSelector(), endpoint.outputMode(),
			endpoint.outputPolicyGeneration(), endpoint.returnWarehouseRoute(), endpoint.loaded(),
			endpoint.reachable());
	}

	private static final class FakeSlots implements AgentProcess.SlotAllocator {
		private final ArrayDeque<AgentProcess.ResidentSlot> offered = new ArrayDeque<>();
		private final List<ProcessKind> requestedKinds = new ArrayList<>();
		private AgentProcess.ResidentSlot current;
		private boolean valid = true;
		private int acquisitions;
		private int releases;

		private FakeSlots() { offer(slot(800)); }
		private void offer(AgentProcess.ResidentSlot slot) { offered.addLast(slot); }
		private void invalidateCurrent() { valid = false; }
		private void restoreCurrent(AgentProcess.ResidentSlot slot) {
			current = slot;
			valid = true;
		}

		@Override public Optional<AgentProcess.ResidentSlot> acquire(UUID agentId,
			UUID ownerClusterId, ProcessKind kind) {
			acquisitions++;
			requestedKinds.add(kind);
			current = offered.pollFirst();
			valid = current != null;
			return Optional.ofNullable(current);
		}

		@Override public boolean stillValid(AgentProcess.ResidentSlot slot) {
			return valid && slot.equals(current);
		}

		@Override public void release(AgentProcess.ResidentSlot slot) {
			releases++;
			if (slot.equals(current)) current = null;
		}
	}

	private static final class MemoryStateStore implements AgentCoordinator.StateStore {
		private final Map<UUID, AgentCoordinator.PersistedState> states = new java.util.HashMap<>();
		private int writes;
		@Override public Optional<AgentCoordinator.PersistedState> load(UUID agentId) {
			return Optional.ofNullable(states.get(agentId));
		}
		@Override public void persist(AgentCoordinator.PersistedState state) {
			states.put(state.agentId(), state);
			writes++;
		}
	}

	private static final class FakeCollector implements AgentRouteRuntimeAdapter.ScopedCollector {
		private final UUID clusterId;
		private List<ProductionRouteTable.ProviderSource> sources = List.of();
		private boolean completed;
		private FakeCollector() { this(CLUSTER); }
		private FakeCollector(UUID clusterId) { this.clusterId = clusterId; }
		@Override public UUID clusterId() { return clusterId; }
		@Override public boolean replace(RouteSelectionScope scope,
			List<ProductionRouteTable.ProviderSource> sources) {
			this.sources = List.copyOf(sources);
			return true;
		}
		@Override public boolean complete(RouteSelectionScope scope) {
			completed = true;
			return true;
		}
	}

	private static void assertExactAgent(AgentAddressDirectory directory,
		AgentFormation formation) {
		AgentAddressRegistration registration = ((AgentResolution.Unique) directory.resolve(
			formation.inputs().getFirst().route(), CLUSTER,
			CommunicationFrequency.UNCONFIGURED)).registration();
		assertEquals(formation.agentId(), registration.agentId());
		assertEquals(formation.agentGeneration(), registration.agentGeneration());
		assertEquals(CLUSTER, registration.ownerClusterId());
	}

	private static FactoryOrchestrationSavedData reload(
		FactoryOrchestrationSavedData journal) {
		try {
			CompoundTag root = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
			java.lang.reflect.Method load = FactoryOrchestrationSavedData.class.getDeclaredMethod(
				"load", CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
			load.setAccessible(true);
			return (FactoryOrchestrationSavedData) load.invoke(null, root, RegistryAccess.EMPTY);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private static ClusterCommunicationConfiguration declaration() { return new ClusterCommunicationConfiguration(CLUSTER, CommunicationFrequency.of("17"), ClusterServiceRole.AGENT, 0); }

	private static UUID id(long value) { return new UUID(0, value); }
}
