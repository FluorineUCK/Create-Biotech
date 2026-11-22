package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentRole;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentStatus;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentType;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressObservationRegistry.AgentMetadata;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressObservationRegistry.LiveObservation;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkAssociation.AssociationKind;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

class LogisticsAddressObserverTest {
	private static final UUID NETWORK = id(1);

	@Test
	void observesPackagerPortRequesterAndFactoryGaugeAddresses() {
		LogisticsAddressObserver observer = new LogisticsAddressObserver(8);

		observer.observe(NETWORK, 20,
			List.of(packager("Mill", 1), postbox("Dock", 2), requester("Assembler", 3),
				gauge("Press", 4)));

		assertEquals(Set.of("Mill", "Dock", "Assembler", "Press"),
			observer.snapshot(NETWORK).stream().map(LogisticsAddressComponent::createAddress)
				.collect(Collectors.toSet()));
	}

	@Test
	void unloadRetainsLastSeenButMarksOffline() {
		LogisticsAddressObserver observer = new LogisticsAddressObserver(8);
		observer.observe(NETWORK, 20, List.of(packager("Mill", 1)));

		observer.observe(NETWORK, 40, List.of());

		LogisticsAddressComponent retained = observer.snapshot(NETWORK).getFirst();
		assertEquals(ComponentStatus.OFFLINE, retained.status());
		assertEquals(20, retained.lastSeenTick());
	}

	@Test
	void addressChangeUpdatesTheStableComponentInsteadOfLeavingAnOfflineGhost() {
		LogisticsAddressObserver observer = new LogisticsAddressObserver(8);
		ComponentIdentity stablePackager = identity(1, "packager");
		observer.observe(NETWORK, 20, List.of(live(stablePackager, "Old",
			ComponentType.PACKAGER, ComponentRole.ORDINARY, Optional.empty())));

		observer.observe(NETWORK, 40, List.of(live(stablePackager, "New",
			ComponentType.PACKAGER, ComponentRole.ORDINARY, Optional.empty())));

		assertEquals(1, observer.snapshot(NETWORK).size());
		assertEquals("New", observer.snapshot(NETWORK).getFirst().createAddress());
		assertEquals(ComponentStatus.ONLINE, observer.snapshot(NETWORK).getFirst().status());
	}

	@Test
	void duplicateLiveRouteClaimsConflictWithoutCollapsingDifferentComponents() {
		LogisticsAddressObserver observer = new LogisticsAddressObserver(8);

		observer.observe(NETWORK, 20,
			List.of(packager("Shared", 1), postbox("Shared", 2)));

		assertEquals(2, observer.snapshot(NETWORK).size());
		assertEquals(Set.of(ComponentStatus.CONFLICT), observer.snapshot(NETWORK).stream()
			.map(LogisticsAddressComponent::status).collect(Collectors.toSet()));
	}

	@Test
	void sameAddressOnDifferentNetworksDoesNotConflict() {
		UUID other = id(2);
		LogisticsAddressObserver observer = new LogisticsAddressObserver(8);

		observer.observe(NETWORK, 20, List.of(packager("Shared", 1)));
		observer.observe(other, 20, List.of(withNetwork(packager("Shared", 2), other)));

		assertEquals(ComponentStatus.ONLINE, observer.snapshot(NETWORK).getFirst().status());
		assertEquals(ComponentStatus.ONLINE, observer.snapshot(other).getFirst().status());
	}

	@Test
	void boundedHistoryEvictsOldestOfflineComponentBeforeLiveComponents() {
		LogisticsAddressObserver observer = new LogisticsAddressObserver(2);
		observer.observe(NETWORK, 20, List.of(packager("Old", 1), postbox("Live", 2)));
		observer.observe(NETWORK, 40, List.of(postbox("Live", 2)));

		observer.observe(NETWORK, 60,
			List.of(postbox("Live", 2), requester("New", 3)));

		assertEquals(Set.of("Live", "New"), observer.snapshot(NETWORK).stream()
			.map(LogisticsAddressComponent::createAddress).collect(Collectors.toSet()));
	}

	@Test
	void mergesOrdinaryInputAndOutputRolesAtStableAgentGeneration() {
		LogisticsAddressObserver observer = new LogisticsAddressObserver(8);
		ComponentIdentity identity = identity(7, "factory_gauge:Agent");
		AgentMetadata agent = new AgentMetadata(id(70), 11);

		observer.observe(NETWORK, 20, List.of(
			live(identity, "Agent", ComponentType.FACTORY_GAUGE, ComponentRole.ORDINARY,
				Optional.empty()),
			live(identity, "Agent", ComponentType.FACTORY_GAUGE, ComponentRole.INPUT,
				Optional.of(agent)),
			live(identity, "Agent", ComponentType.FACTORY_GAUGE, ComponentRole.OUTPUT,
				Optional.of(agent))));

		LogisticsAddressComponent component = observer.snapshot(NETWORK).getFirst();
		assertEquals(Set.of(ComponentRole.ORDINARY, ComponentRole.INPUT, ComponentRole.OUTPUT),
			component.roles());
		assertEquals(Optional.of(id(70)), component.agentId());
		assertEquals(11, component.memberGeneration());
	}

	@Test
	void publishesOnlyOnTwentyTickBoundaryAndPersistsBeforeVisibility() {
		AtomicInteger writes = new AtomicInteger();
		LogisticsAddressObserver observer = LogisticsAddressObserver.restore(
			LogisticsAddressObserver.State.empty(), 8, ignored -> writes.incrementAndGet());

		assertEquals(false, observer.publishIfDue(19, List.of(packager("Mill", 1))));
		assertEquals(true, observer.publishIfDue(20, List.of(packager("Mill", 1))));
		assertEquals(false, observer.publishIfDue(20, List.of(packager("Mill", 1))));
		assertEquals(false, observer.publishIfDue(39, List.of(packager("Mill", 1))));
		assertEquals(true, observer.publishIfDue(40, List.of()));
		assertEquals(2, writes.get());
		assertEquals(ComponentStatus.OFFLINE, observer.snapshot(NETWORK).getFirst().status());
	}

	@Test
	void failedPersistenceDoesNotPublishProspectiveSnapshot() {
		LogisticsAddressObserver observer = LogisticsAddressObserver.restore(
			LogisticsAddressObserver.State.empty(), 8, ignored -> {
				throw new IllegalStateException("durable store unavailable");
			});

		assertThrows(IllegalStateException.class,
			() -> observer.observe(NETWORK, 20, List.of(packager("Mill", 1))));
		assertEquals(List.of(), observer.snapshot(NETWORK));
	}

	@Test
	void lowerRetentionCapacityTrimsAndPersistsBeforeRestoredStateBecomesVisible() {
		LogisticsAddressObserver source = new LogisticsAddressObserver(3);
		source.observe(NETWORK, 20, List.of(packager("Old A", 1), packager("Old B", 2),
			packager("Live", 3)));
		source.observe(NETWORK, 40, List.of(packager("Live", 3)));
		AtomicReference<LogisticsAddressObserver.State> persisted = new AtomicReference<>();

		LogisticsAddressObserver restored = LogisticsAddressObserver.restore(source.state(), 2,
			persisted::set);

		assertEquals(restored.state(), persisted.get());
		assertEquals(Set.of("Old B", "Live"), restored.snapshot(NETWORK).stream()
			.map(LogisticsAddressComponent::createAddress).collect(Collectors.toSet()));
		assertEquals(source.state().revision() + 1, restored.state().revision());
		assertEquals(source.state().lastPublicationTick(),
			restored.state().lastPublicationTick());
	}

	@Test
	void savedDataRestoresThePersistentLastSeenDirectory() throws ReflectiveOperationException {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		LogisticsAddressObserver observer = journal.logisticsAddressObserver(RegistryAccess.EMPTY, 8);
		observer.observe(NETWORK, 20, List.of(packager("Mill", 1)));

		CompoundTag saved = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
		FactoryOrchestrationSavedData reloaded = reloadJournal(saved);
		LogisticsAddressObserver restored = reloaded.logisticsAddressObserver(RegistryAccess.EMPTY, 8);

		assertEquals(observer.state(), restored.state());
		assertEquals("Mill", restored.snapshot(NETWORK).getFirst().createAddress());
	}

	private static LiveObservation packager(String address, int position) {
		return live(identity(position, "packager"), address, ComponentType.PACKAGER,
			ComponentRole.ORDINARY, Optional.empty());
	}

	private static LiveObservation postbox(String address, int position) {
		return live(identity(position, "package_port"), address,
			ComponentType.PACKAGE_PORT, ComponentRole.ORDINARY, Optional.empty());
	}

	private static LiveObservation requester(String address, int position) {
		return live(identity(position, "redstone_requester"), address,
			ComponentType.REDSTONE_REQUESTER, ComponentRole.ORDINARY, Optional.empty());
	}

	private static LiveObservation gauge(String address, int position) {
		return live(identity(position, "factory_gauge:bottom_left"), address,
			ComponentType.FACTORY_GAUGE, ComponentRole.ORDINARY, Optional.empty());
	}

	private static LiveObservation live(ComponentIdentity identity, String address,
		ComponentType type, ComponentRole role, Optional<AgentMetadata> agent) {
		return new LiveObservation(identity, new ObservedAddress(address, type, role),
			new LogisticsNetworkAssociation(NETWORK, AssociationKind.DIRECT_LINK), agent);
	}

	private static LiveObservation withNetwork(LiveObservation observation, UUID network) {
		return new LiveObservation(observation.componentIdentity(), observation.address(),
			new LogisticsNetworkAssociation(network, observation.association().associationKind()),
			observation.agentMetadata());
	}

	private static ComponentIdentity identity(int position, String discriminator) {
		return new ComponentIdentity(new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(position, 64, 0)), discriminator);
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}

	private static FactoryOrchestrationSavedData reloadJournal(CompoundTag root)
		throws ReflectiveOperationException {
		var load = FactoryOrchestrationSavedData.class.getDeclaredMethod("load", CompoundTag.class,
			net.minecraft.core.HolderLookup.Provider.class);
		load.setAccessible(true);
		return (FactoryOrchestrationSavedData) load.invoke(null, root, RegistryAccess.EMPTY);
	}
}
