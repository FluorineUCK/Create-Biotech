package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentSignalSelector;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.FactoryPanelAgentAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentRole;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentStatus;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentType;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkAssociation.AssociationKind;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.packagePort.postbox.PostboxBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.Level;

class LogisticsAddressAdaptersTest {
	private static final UUID NETWORK = new UUID(0, 101);
	private static final LogisticsNetworkAssociation DIRECT =
		new LogisticsNetworkAssociation(NETWORK, AssociationKind.DIRECT_LINK);
	private final LogisticsAddressAdapters adapters = LogisticsAddressAdapters.createDefault();

	@BeforeAll
	static void bootstrapMinecraft() {
		installEmptyLoadingModList();
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

	@Test
	void explicitAdaptersReadSupportedCreateAddressCarriers() {
		PackagerBlockEntity packager = packager();
		packager.signBasedAddress = "Mill";
		PostboxBlockEntity postbox = postbox();
		postbox.addressFilter = "Dock";
		RedstoneRequesterBlockEntity requester = requester();
		requester.encodedTargetAdress = "Assembler";
		AgentPanel panel = panel("Press");

		assertEquals(List.of(observed("Mill", ComponentType.PACKAGER)), observe(packager));
		assertEquals(List.of(observed("Dock", ComponentType.PACKAGE_PORT)), observe(postbox));
		assertEquals(List.of(observed("Assembler", ComponentType.REDSTONE_REQUESTER)),
			observe(requester));
		assertEquals(List.of(observed("Press", ComponentType.FACTORY_GAUGE)), observe(panel));
	}

	@Test
	void packagerComputerAddressOverridesItsLastSignAddressOnlyWhenExplicitlyEnabled() {
		PackagerBlockEntity packager = packager();
		packager.signBasedAddress = "Mill Sign";
		packager.customComputerAddress = "Mill Computer";
		packager.hasCustomComputerAddress = true;

		assertEquals(List.of(observed("Mill Computer", ComponentType.PACKAGER)),
			observe(packager));
		packager.hasCustomComputerAddress = false;
		assertEquals(List.of(observed("Mill Sign", ComponentType.PACKAGER)), observe(packager));
	}

	@Test
	void factoryPanelAdapterEmitsOrdinaryProviderAndNativeAgentRoles() {
		AgentPanel panel = panel("Press");
		panel.configuration = Optional.of(configuration(AgentEndpointRole.INPUT, "Press", 7));
		panel.provider = Optional.of(new FactoryPanelAgentAccess.ProviderConfiguration(3));

		assertEquals(Set.of(
			observed("Press", ComponentType.FACTORY_GAUGE, ComponentRole.ORDINARY),
			observed("Press", ComponentType.FACTORY_GAUGE, ComponentRole.PROVIDER),
			observed("Press", ComponentType.FACTORY_GAUGE, ComponentRole.INPUT)),
			Set.copyOf(observe(panel, AssociationKind.AGENT_ENDPOINT)));
	}

	@Test
	void nativeAgentAddressIsPublishedOnlyThroughItsExplicitEndpointAssociation() {
		AgentPanel panel = panel("Recipe");
		panel.configuration = Optional.of(configuration(AgentEndpointRole.INPUT, "Agent", 7));

		assertEquals(List.of(observed("Recipe", ComponentType.FACTORY_GAUGE)),
			observe(panel, AssociationKind.DIRECT_LINK));
		assertEquals(Set.of(
			observed("Recipe", ComponentType.FACTORY_GAUGE),
			observed("Agent", ComponentType.FACTORY_GAUGE, ComponentRole.INPUT)),
			Set.copyOf(observe(panel, AssociationKind.AGENT_ENDPOINT)));
	}

	@Test
	void associationWhitelistContainsOnlyTheThreeSpecifiedProofKinds() {
		assertEquals(Set.of(AssociationKind.DIRECT_LINK,
			AssociationKind.ADJACENT_PACKAGER_LINK, AssociationKind.AGENT_ENDPOINT),
			EnumSet.allOf(AssociationKind.class));
	}

	@Test
	void factoryPanelAdapterUsesOnlyRecipeAddressesForTheAssociatedNetwork() {
		AgentPanel panel = panel("Press");
		FactoryPanelBehaviour other = behaviour(panel, PanelSlot.BOTTOM_RIGHT, new UUID(0, 202),
			"Other Network");
		panel.panels.put(PanelSlot.BOTTOM_RIGHT, other);

		assertEquals(List.of(observed("Press", ComponentType.FACTORY_GAUGE)), observe(panel));
	}

	@Test
	void factoryPanelAdapterRetainsDistinctSlotsThatClaimTheSameAddress() {
		AgentPanel panel = panel("Shared");
		panel.panels.put(PanelSlot.BOTTOM_RIGHT,
			behaviour(panel, PanelSlot.BOTTOM_RIGHT, NETWORK, "Shared"));

		assertEquals(List.of(
			observed("Shared", ComponentType.FACTORY_GAUGE),
			observed("Shared", ComponentType.FACTORY_GAUGE)), observe(panel));
	}

	@Test
	void emptyAddressesAreOmittedByEveryAdapter() {
		PackagerBlockEntity packager = packager();
		packager.signBasedAddress = "   ";
		packager.customComputerAddress = "";
		packager.hasCustomComputerAddress = true;
		PostboxBlockEntity postbox = postbox();
		postbox.addressFilter = "";
		RedstoneRequesterBlockEntity requester = requester();
		requester.encodedTargetAdress = "\t";
		AgentPanel panel = panel("");

		assertEquals(List.of(), observe(packager));
		assertEquals(List.of(), observe(postbox));
		assertEquals(List.of(), observe(requester));
		assertEquals(List.of(), observe(panel));
	}

	@Test
	void arbitraryAddressLookingFieldsAreNeverReflected() {
		MisleadingAddressBlockEntity unknown = new MisleadingAddressBlockEntity();

		assertFalse(adapters.supports(unknown));
		assertEquals(List.of(), observe(unknown));
	}

	@Test
	void registryAcceptsOnlyDirectOwnedAndExplicitEndpointProofs() {
		PackagerBlockEntity direct = packager();
		direct.signBasedAddress = "Direct";
		PackagerBlockEntity owned = packager();
		owned.signBasedAddress = "Owned";
		TestPackagerLink owner = allocate(TestPackagerLink.class);
		owner.linkedPackager = owned;
		AgentPanel endpoint = panel("");
		endpoint.configuration = Optional.of(configuration(AgentEndpointRole.INPUT, "Agent", 7));

		Map<BlockEntity, SpaceAddress> locations = new IdentityHashMap<>();
		locations.put(direct, space(1));
		locations.put(owned, space(2));
		locations.put(endpoint, space(3));
		Map<EndpointProof, SpaceAddress> endpoints = new HashMap<>();
		endpoints.put(new EndpointProof(endpoint, NETWORK), space(3));
		LogisticsAddressObservationRegistry registry = registry(8, () -> true, locations,
			endpoints);
		LogisticallyLinkedBehaviour directLink = link(direct, NETWORK);
		LogisticallyLinkedBehaviour ownerLink = link(owner, NETWORK);

		assertTrue(registry.observe(direct, Optional.of(directLink),
			association(NETWORK, AssociationKind.DIRECT_LINK)));
		assertTrue(registry.observe(owned, Optional.of(ownerLink),
			association(NETWORK, AssociationKind.ADJACENT_PACKAGER_LINK)));
		assertTrue(registry.observe(endpoint, Optional.empty(),
			association(NETWORK, AssociationKind.AGENT_ENDPOINT)));
		assertFalse(registry.observe(direct, Optional.empty(),
			association(NETWORK, AssociationKind.DIRECT_LINK)));
		assertFalse(registry.observe(owned, Optional.of(directLink),
			association(NETWORK, AssociationKind.ADJACENT_PACKAGER_LINK)));
		assertFalse(registry.observe(endpoint, Optional.empty(),
			association(new UUID(0, 999), AssociationKind.AGENT_ENDPOINT)));
		assertEquals(Set.of("Direct", "Owned", "Agent"), registry.snapshot().stream()
			.map(observation -> observation.address().createAddress()).collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	void registryRejectsEveryOperationOffTheServerThread() {
		PackagerBlockEntity packager = packager();
		packager.signBasedAddress = "Mill";
		Map<BlockEntity, SpaceAddress> locations = new IdentityHashMap<>();
		locations.put(packager, space(1));
		AtomicBoolean serverThread = new AtomicBoolean(false);
		LogisticsAddressObservationRegistry registry = registry(2, serverThread::get,
			locations, Map.of());
		LogisticallyLinkedBehaviour link = link(packager, NETWORK);

		assertThrows(IllegalStateException.class, () -> registry.observe(packager,
			Optional.of(link), association(NETWORK, AssociationKind.DIRECT_LINK)));
		assertThrows(IllegalStateException.class, registry::snapshot);
		assertThrows(IllegalStateException.class, registry::beginLinkScan);
	}

	@Test
	void registryCapacityEvictsItsOldestExplicitObservation() {
		PackagerBlockEntity first = addressedPackager("First");
		PackagerBlockEntity second = addressedPackager("Second");
		PackagerBlockEntity third = addressedPackager("Third");
		Map<BlockEntity, SpaceAddress> locations = new IdentityHashMap<>();
		locations.put(first, space(1));
		locations.put(second, space(2));
		locations.put(third, space(3));
		LogisticsAddressObservationRegistry registry = registry(2, () -> true, locations,
			Map.of());

		for (PackagerBlockEntity packager : List.of(first, second, third))
			assertTrue(registry.observe(packager, Optional.of(link(packager, NETWORK)),
				association(NETWORK, AssociationKind.DIRECT_LINK)));

		assertEquals(2, registry.size());
		assertEquals(Set.of("Second", "Third"), registry.snapshot().stream()
			.map(observation -> observation.address().createAddress()).collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	void agentIdentityStaysFixedFromBlankRecipeToMatchingRecipeEndToEnd() {
		AgentFixture fixture = agentFixture("");
		LogisticsAddressObserver observer = new LogisticsAddressObserver(8);
		assertTrue(fixture.registry().observe(fixture.panel(), Optional.empty(),
			association(NETWORK, AssociationKind.AGENT_ENDPOINT)));
		assertTrue(observer.publishIfDue(20, fixture.registry().snapshot()));
		LogisticsAddressComponent before = observer.snapshot(NETWORK).getFirst();

		fixture.recipe().recipeAddress = "Agent";
		assertTrue(observer.publishIfDue(40, fixture.registry().snapshot()));

		LogisticsAddressComponent after = observer.snapshot(NETWORK).getFirst();
		assertEquals(1, observer.snapshot(NETWORK).size());
		assertEquals(before.componentIdentity(), after.componentIdentity());
		assertEquals("factory_gauge:agent_endpoint",
			after.componentIdentity().discriminator());
		assertEquals(Set.of(ComponentRole.ORDINARY, ComponentRole.INPUT), after.roles());
		assertEquals(ComponentStatus.ONLINE, after.status());
	}

	@Test
	void agentEndpointKeepsEveryRecipeOnItsProvenNetworkWithoutFoldingSlots() {
		AgentFixture fixture = agentFixture("Agent");
		fixture.panel().provider = Optional.of(
			new FactoryPanelAgentAccess.ProviderConfiguration(3));
		fixture.panel().panels.put(PanelSlot.BOTTOM_RIGHT,
			behaviour(fixture.panel(), PanelSlot.BOTTOM_RIGHT, NETWORK, "Other"));
		LogisticsAddressObserver observer = new LogisticsAddressObserver(8);
		assertTrue(fixture.registry().observe(fixture.panel(), Optional.empty(),
			association(NETWORK, AssociationKind.AGENT_ENDPOINT)));

		assertTrue(observer.publishIfDue(20, fixture.registry().snapshot()));

		Map<String, LogisticsAddressComponent> byAddress = observer.snapshot(NETWORK).stream()
			.collect(java.util.stream.Collectors.toMap(
				LogisticsAddressComponent::createAddress, component -> component));
		assertEquals(Set.of("Agent", "Other"), byAddress.keySet());
		assertEquals("factory_gauge:agent_endpoint",
			byAddress.get("Agent").componentIdentity().discriminator());
		assertEquals(Set.of(ComponentRole.ORDINARY, ComponentRole.PROVIDER,
			ComponentRole.INPUT), byAddress.get("Agent").roles());
		assertEquals("factory_gauge:bottom_right",
			byAddress.get("Other").componentIdentity().discriminator());
		assertEquals(Set.of(ComponentRole.ORDINARY, ComponentRole.PROVIDER),
			byAddress.get("Other").roles());
		assertEquals(Set.of(ComponentStatus.ONLINE), byAddress.values().stream()
			.map(LogisticsAddressComponent::status).collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	void agentIdentityStaysFixedFromMatchingRecipeToBlankRecipe() {
		AgentFixture fixture = agentFixture("Agent");
		LogisticsAddressObserver observer = new LogisticsAddressObserver(8);
		assertTrue(fixture.registry().observe(fixture.panel(), Optional.empty(),
			association(NETWORK, AssociationKind.AGENT_ENDPOINT)));
		assertTrue(observer.publishIfDue(20, fixture.registry().snapshot()));
		var identity = observer.snapshot(NETWORK).getFirst().componentIdentity();

		fixture.recipe().recipeAddress = "";
		assertTrue(observer.publishIfDue(40, fixture.registry().snapshot()));

		assertEquals(1, observer.snapshot(NETWORK).size());
		assertEquals(identity, observer.snapshot(NETWORK).getFirst().componentIdentity());
		assertEquals(Set.of(ComponentRole.INPUT), observer.snapshot(NETWORK).getFirst().roles());
	}

	@Test
	void removingFirstDuplicateRecipeSlotDoesNotMoveAgentIdentity() {
		AgentFixture fixture = agentFixture("Agent");
		FactoryPanelBehaviour duplicate = behaviour(fixture.panel(), PanelSlot.BOTTOM_RIGHT,
			NETWORK, "Agent");
		fixture.panel().panels.put(PanelSlot.BOTTOM_RIGHT, duplicate);
		LogisticsAddressObserver observer = new LogisticsAddressObserver(8);
		assertTrue(fixture.registry().observe(fixture.panel(), Optional.empty(),
			association(NETWORK, AssociationKind.AGENT_ENDPOINT)));
		assertTrue(observer.publishIfDue(20, fixture.registry().snapshot()));
		var identity = observer.snapshot(NETWORK).getFirst().componentIdentity();

		fixture.recipe().active = false;
		assertTrue(observer.publishIfDue(40, fixture.registry().snapshot()));

		assertEquals(1, observer.snapshot(NETWORK).size());
		assertEquals(identity, observer.snapshot(NETWORK).getFirst().componentIdentity());
		assertEquals("factory_gauge:agent_endpoint", identity.discriminator());
	}

	@Test
	void changingAgentConfigurationKeepsTheAuthoritativeEndpointIdentity() {
		AgentFixture fixture = agentFixture("");
		LogisticsAddressObserver observer = new LogisticsAddressObserver(8);
		assertTrue(fixture.registry().observe(fixture.panel(), Optional.empty(),
			association(NETWORK, AssociationKind.AGENT_ENDPOINT)));
		assertTrue(observer.publishIfDue(20, fixture.registry().snapshot()));
		var identity = observer.snapshot(NETWORK).getFirst().componentIdentity();

		fixture.panel().configuration = Optional.of(configuration(AgentEndpointRole.INPUT,
			"Changed", 8));
		assertTrue(observer.publishIfDue(40, fixture.registry().snapshot()));

		assertEquals(1, observer.snapshot(NETWORK).size());
		assertEquals(identity, observer.snapshot(NETWORK).getFirst().componentIdentity());
		assertEquals("Changed", observer.snapshot(NETWORK).getFirst().createAddress());
		assertEquals(8, observer.snapshot(NETWORK).getFirst().memberGeneration());
	}

	private List<ObservedAddress> observe(BlockEntity blockEntity) {
		return adapters.observe(blockEntity, Optional.empty(), DIRECT);
	}

	private List<ObservedAddress> observe(BlockEntity blockEntity, AssociationKind kind) {
		return adapters.observe(blockEntity, Optional.empty(),
			new LogisticsNetworkAssociation(NETWORK, kind));
	}

	private LogisticsAddressObservationRegistry registry(int capacity,
		java.util.function.BooleanSupplier serverThread, Map<BlockEntity, SpaceAddress> locations,
		Map<EndpointProof, SpaceAddress> endpoints) {
		return new LogisticsAddressObservationRegistry(capacity, serverThread, adapters,
			blockEntity -> Optional.ofNullable(locations.get(blockEntity)),
			(blockEntity, network) -> Optional.ofNullable(
				endpoints.get(new EndpointProof(blockEntity, network))));
	}

	private AgentFixture agentFixture(String recipeAddress) {
		AgentPanel panel = panel(recipeAddress);
		panel.configuration = Optional.of(configuration(AgentEndpointRole.INPUT, "Agent", 7));
		Map<BlockEntity, SpaceAddress> locations = new IdentityHashMap<>();
		locations.put(panel, space(7));
		Map<EndpointProof, SpaceAddress> endpoints = Map.of(
			new EndpointProof(panel, NETWORK), space(7));
		return new AgentFixture(panel, panel.panels.get(PanelSlot.BOTTOM_LEFT),
			registry(8, () -> true, locations, endpoints));
	}

	private static LogisticsNetworkAssociation association(UUID network,
		AssociationKind kind) {
		return new LogisticsNetworkAssociation(network, kind);
	}

	private static SpaceAddress space(int position) {
		return new SpaceAddress(Level.OVERWORLD, null, new BlockPos(position, 64, 0));
	}

	private static PackagerBlockEntity addressedPackager(String address) {
		PackagerBlockEntity packager = packager();
		packager.signBasedAddress = address;
		packager.customComputerAddress = "";
		packager.hasCustomComputerAddress = false;
		return packager;
	}

	private static LogisticallyLinkedBehaviour link(BlockEntity owner, UUID network) {
		LogisticallyLinkedBehaviour link = new LogisticallyLinkedBehaviour(
			(com.simibubi.create.foundation.blockEntity.SmartBlockEntity) owner, false);
		link.freqId = network;
		return link;
	}

	private static ObservedAddress observed(String address, ComponentType type) {
		return observed(address, type, ComponentRole.ORDINARY);
	}

	private static ObservedAddress observed(String address, ComponentType type, ComponentRole role) {
		return new ObservedAddress(address, type, role);
	}

	private static PackagerBlockEntity packager() {
		return allocate(PackagerBlockEntity.class);
	}

	private static PostboxBlockEntity postbox() {
		return allocate(PostboxBlockEntity.class);
	}

	private static RedstoneRequesterBlockEntity requester() {
		return allocate(RedstoneRequesterBlockEntity.class);
	}

	private static AgentPanel panel(String address) {
		AgentPanel panel = allocate(AgentPanel.class);
		panel.configuration = Optional.empty();
		panel.provider = Optional.empty();
		panel.panels = new EnumMap<>(PanelSlot.class);
		panel.panels.put(PanelSlot.BOTTOM_LEFT,
			behaviour(panel, PanelSlot.BOTTOM_LEFT, NETWORK, address));
		return panel;
	}

	private static FactoryPanelBehaviour behaviour(FactoryPanelBlockEntity panel, PanelSlot slot,
		UUID network, String address) {
		FactoryPanelBehaviour behaviour = allocate(FactoryPanelBehaviour.class);
		behaviour.active = true;
		behaviour.network = network;
		behaviour.recipeAddress = address;
		behaviour.slot = slot;
		return behaviour;
	}

	private static FactoryPanelAgentAccess.Configuration configuration(AgentEndpointRole role,
		String address, long generation) {
		return new FactoryPanelAgentAccess.Configuration(new UUID(0, 301), new UUID(0, 302),
			new UUID(0, 303), generation, generation, role, address, 0,
			CommunicationFrequency.of("agent"), AgentCompletionMode.TARGET, Optional.empty(),
			AgentEndpointState.OutputMode.WAREHOUSE_ALL, generation,
			new CreateRouteKey(NETWORK, "Warehouse"));
	}

	private static final class AgentPanel extends FactoryPanelBlockEntity
		implements FactoryPanelAgentAccess {
		private Optional<Configuration> configuration = Optional.empty();
		private Optional<ProviderConfiguration> provider = Optional.empty();

		private AgentPanel() {
			super(BlockEntityType.FURNACE, BlockPos.ZERO,
				Blocks.FURNACE.defaultBlockState());
		}

		@Override public Optional<Configuration> createBiotech$getAgentConfiguration() {
			return configuration;
		}
		@Override public void createBiotech$setAgentConfiguration(
			@Nullable Configuration configuration) {
			this.configuration = Optional.ofNullable(configuration);
		}
		@Override public void createBiotech$setAgentConfigurationSilently(
			@Nullable Configuration configuration) {
			this.configuration = Optional.ofNullable(configuration);
		}
		@Override public Optional<ProviderConfiguration> createBiotech$getProviderConfiguration() {
			return provider;
		}
		@Override public void createBiotech$setProviderConfiguration(
			ProviderConfiguration configuration) {
			provider = Optional.ofNullable(configuration);
		}
	}

	private static final class TestPackagerLink extends PackagerLinkBlockEntity {
		private PackagerBlockEntity linkedPackager;

		private TestPackagerLink() {
			super(BlockEntityType.FURNACE, BlockPos.ZERO,
				Blocks.FURNACE.defaultBlockState());
		}

		@Override public PackagerBlockEntity getPackager() {
			return linkedPackager;
		}
	}

	private record EndpointProof(BlockEntity blockEntity, UUID network) {}
	private record AgentFixture(AgentPanel panel, FactoryPanelBehaviour recipe,
		LogisticsAddressObservationRegistry registry) {}

	private static final class MisleadingAddressBlockEntity extends BlockEntity {
		@SuppressWarnings("unused")
		public String addressFilter = "must-not-be-reflected";

		private MisleadingAddressBlockEntity() {
			super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
		}
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null) return;
			Object empty = loadingModList.getMethod("of", List.class, List.class, List.class,
				List.class, java.util.Map.class).invoke(null, List.of(), List.of(), List.of(),
				List.of(), java.util.Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static <T> T allocate(Class<T> type) {
		try {
			java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
