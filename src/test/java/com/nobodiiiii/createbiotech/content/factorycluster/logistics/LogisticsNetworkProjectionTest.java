package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentRole;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentStatus;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentType;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkProjection.RemoteCapability;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class LogisticsNetworkProjectionTest {
	private static final UUID NETWORK = id(1);
	private static final UUID OTHER_NETWORK = id(2);
	private static final ClusterAuthority AUTHORITY = new ClusterAuthority(
		ClusterMemberType.COMPUTER_COORDINATOR, id(99));

	private LogisticsNetworkDirectory directory;
	private LogisticsAddressObserver observer;
	private LogisticsNetworkProjectionService service;

	@BeforeEach
	void setUp() {
		directory = new LogisticsNetworkDirectory();
		directory.ensure(NETWORK);
		directory.compareAndSetAlias(NETWORK, 0, Optional.of("主仓"));
		directory.ensure(OTHER_NETWORK);
		observer = new LogisticsAddressObserver(32);
		service = service(new ClusterBinding(id(100), 7, AUTHORITY,
			List.of(new LogisticsBinding(NETWORK), new LogisticsBinding(OTHER_NETWORK))));
	}

	@Test
	void projectionContainsReadableNetworksAndReadOnlyComponents() {
		LogisticsAddressComponent observed = component(NETWORK, 1, "packager", "Mill",
			ComponentType.PACKAGER, ComponentStatus.ONLINE, Set.of(ComponentRole.INPUT),
			Optional.of(id(7)), 4, 20);
		setComponents(map(observed));

		var view = service.project(AUTHORITY, Optional.of(NETWORK), 0, 32);

		assertEquals(expectedLiteral("主仓"),
			view.networks().getFirst().displayName());
		assertEquals(1, view.networks().getFirst().revision());
		assertEquals("Mill", view.components().getFirst().createAddress());
		assertEquals(ComponentType.PACKAGER, view.components().getFirst().componentType());
		assertEquals(Optional.of(id(7)), view.components().getFirst().agentId());
		assertEquals(Set.of(ComponentRole.INPUT), view.components().getFirst().roles());
		assertEquals(4, view.components().getFirst().memberGeneration());
		assertEquals(observed.componentIdentity(), view.components().getFirst().componentIdentity());
		assertEquals(observed.componentIdentity().address(), view.components().getFirst().spaceAddress());
		assertFalse(view.capabilities().contains(RemoteCapability.EDIT_CREATE_ADDRESS));
	}

	@Test
	void rejectsUnboundSelectionAndUnformedAuthority() {
		assertThrows(IllegalArgumentException.class,
			() -> service.project(AUTHORITY, Optional.of(id(300)), 0, 1));
		LogisticsNetworkProjectionService noFormedAuthority = service(new ClusterBinding(id(100), 7,
			null, List.of(new LogisticsBinding(NETWORK))));

		assertThrows(IllegalStateException.class,
			() -> noFormedAuthority.project(AUTHORITY, Optional.of(NETWORK), 0, 1));
		ClusterAuthority mismatched = new ClusterAuthority(ClusterMemberType.PATTERN_CORE, id(98));
		LogisticsNetworkProjectionService wrongAuthority = new LogisticsNetworkProjectionService(directory,
			observer, ignored -> Optional.of(new ClusterBinding(id(100), 7, AUTHORITY,
				List.of(new LogisticsBinding(NETWORK)))), 32);
		assertThrows(IllegalStateException.class,
			() -> wrongAuthority.project(mismatched, Optional.of(NETWORK), 0, 1));
	}

	@Test
	void failsClosedWhenAnyAuthoritativeBindingLacksAGlobalDirectoryEntry() {
		UUID missingDirectoryEntry = id(300);
		LogisticsNetworkProjectionService inconsistentDirectory = service(new ClusterBinding(id(100), 7,
			AUTHORITY, List.of(new LogisticsBinding(NETWORK), new LogisticsBinding(missingDirectoryEntry))));

		assertThrows(IllegalStateException.class,
			() -> inconsistentDirectory.project(AUTHORITY, Optional.of(NETWORK), 0, 32));
	}

	@Test
	void keepsOfflineLastSeenComponentsAndDoesNotPutUuidInDefaultDisplay() {
		setComponents(map(component(NETWORK, 3, "port", "Dock", ComponentType.PACKAGE_PORT,
			ComponentStatus.OFFLINE, Set.of(ComponentRole.OUTPUT), Optional.empty(), 0, 20)));

		var view = service.project(AUTHORITY, Optional.of(OTHER_NETWORK), 0, 32);
		var selected = service.project(AUTHORITY, Optional.of(NETWORK), 0, 32);

		assertEquals(expectedTranslatable("create_biotech.factory_cluster.logistics.default_name", 2L),
			view.networks().get(1).displayName());
		assertEquals(ComponentStatus.OFFLINE, selected.components().getFirst().status());
		assertEquals(20, selected.components().getFirst().lastObservedTick());
	}

	@Test
	void ordersComponentsAndBoundsPagesWithoutOverflow() {
		Map<LogisticsAddressObserver.NetworkComponentKey, LogisticsAddressComponent> components =
			new LinkedHashMap<>();
		add(components, component(NETWORK, 4, "conflict", "Zulu", ComponentType.FACTORY_GAUGE,
			ComponentStatus.CONFLICT, Set.of(ComponentRole.ORDINARY), Optional.empty(), 0, 40));
		add(components, component(NETWORK, 3, "offline", "Offline", ComponentType.PACKAGE_PORT,
			ComponentStatus.OFFLINE, Set.of(ComponentRole.ORDINARY), Optional.empty(), 0, 20));
		add(components, component(NETWORK, 2, "port", "Port", ComponentType.PACKAGE_PORT,
			ComponentStatus.ONLINE, Set.of(ComponentRole.ORDINARY), Optional.empty(), 0, 40));
		add(components, component(NETWORK, 1, "mill", "Mill", ComponentType.PACKAGER,
			ComponentStatus.ONLINE, Set.of(ComponentRole.ORDINARY), Optional.empty(), 0, 40));
		setComponents(components);

		var ordered = service.project(AUTHORITY, Optional.of(NETWORK), 0, 32);
		assertEquals(List.of("Mill", "Port", "Offline", "Zulu"), ordered.components().stream()
			.map(LogisticsNetworkProjection.ComponentView::createAddress).toList());
		assertEquals(List.of("Port", "Offline"), service.project(AUTHORITY,
			Optional.of(NETWORK), 1, 2).components().stream()
				.map(LogisticsNetworkProjection.ComponentView::createAddress).toList());
		assertEquals(List.of(), service.project(AUTHORITY, Optional.of(NETWORK),
			Integer.MAX_VALUE, 1).components());
		assertEquals(List.of(), service.project(AUTHORITY, Optional.of(NETWORK), 0, 0).components());
		assertThrows(IllegalArgumentException.class,
			() -> service.project(AUTHORITY, Optional.of(NETWORK), -1, 1));
		assertThrows(IllegalArgumentException.class,
			() -> service.project(AUTHORITY, Optional.of(NETWORK), 0, 33));
	}

	@Test
	void retainsDistinctStableIdentitiesForComponentsAtOneSpaceAddress() {
		LogisticsAddressComponent first = component(NETWORK, 5, "factory_gauge:left", "Gauge",
			ComponentType.FACTORY_GAUGE, ComponentStatus.ONLINE, Set.of(ComponentRole.ORDINARY),
			Optional.empty(), 0, 40);
		LogisticsAddressComponent second = component(NETWORK, 5, "factory_gauge:right", "Gauge",
			ComponentType.FACTORY_GAUGE, ComponentStatus.ONLINE, Set.of(ComponentRole.ORDINARY),
			Optional.empty(), 0, 40);
		Map<LogisticsAddressObserver.NetworkComponentKey, LogisticsAddressComponent> components =
			new LinkedHashMap<>();
		add(components, second);
		add(components, first);
		setComponents(components);

		var rows = service.project(AUTHORITY, Optional.of(NETWORK), 0, 32).components();

		assertEquals(List.of(first.componentIdentity(), second.componentIdentity()), rows.stream()
			.map(LogisticsNetworkProjection.ComponentView::componentIdentity).toList());
		assertEquals(rows.getFirst().spaceAddress(), rows.get(1).spaceAddress());
		assertFalse(rows.getFirst().componentIdentity().equals(rows.get(1).componentIdentity()));
	}

	private LogisticsNetworkProjectionService service(ClusterBinding binding) {
		return new LogisticsNetworkProjectionService(directory, observer,
			authority -> authority.equals(AUTHORITY) ? Optional.of(binding) : Optional.empty(), 32);
	}

	private void setComponents(Map<LogisticsAddressObserver.NetworkComponentKey,
		LogisticsAddressComponent> components) {
		observer = LogisticsAddressObserver.restore(new LogisticsAddressObserver.State(components, 1, 40),
			32, ignored -> {});
		service = service(new ClusterBinding(id(100), 7, AUTHORITY,
			List.of(new LogisticsBinding(NETWORK), new LogisticsBinding(OTHER_NETWORK))));
	}

	private static void add(Map<LogisticsAddressObserver.NetworkComponentKey,
		LogisticsAddressComponent> components, LogisticsAddressComponent component) {
		components.put(new LogisticsAddressObserver.NetworkComponentKey(component.logisticsNetworkId(),
			component.componentIdentity()), component);
	}

	private static Map<LogisticsAddressObserver.NetworkComponentKey, LogisticsAddressComponent> map(
		LogisticsAddressComponent component) {
		Map<LogisticsAddressObserver.NetworkComponentKey, LogisticsAddressComponent> result =
			new LinkedHashMap<>();
		add(result, component);
		return result;
	}

	private static LogisticsAddressComponent component(UUID network, int x, String discriminator,
		String createAddress, ComponentType type, ComponentStatus status, Set<ComponentRole> roles,
		Optional<UUID> agentId, long generation, long lastSeen) {
		ComponentIdentity identity = new ComponentIdentity(new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(x, 64, 0)), discriminator);
		return new LogisticsAddressComponent(network, identity, createAddress, type, roles, agentId,
			generation, lastSeen, status);
	}

	private static UUID id(long value) { return new UUID(0, value); }

	/** The test compile classpath omits Brigadier's Message; invoke the exact Component factories at runtime. */
	private static Object expectedLiteral(String text) {
		return invokeComponentFactory("literal", new Class<?>[] {String.class}, text);
	}

	private static Object expectedTranslatable(String key, Object argument) {
		return invokeComponentFactory("translatable", new Class<?>[] {String.class, Object[].class},
			key, new Object[] {argument});
	}

	private static Object invokeComponentFactory(String name, Class<?>[] parameterTypes,
		Object... arguments) {
		try {
			return Class.forName("net.minecraft.network.chat.Component").getMethod(name, parameterTypes)
				.invoke(null, arguments);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Minecraft Component factory unavailable", exception);
		}
	}

}
