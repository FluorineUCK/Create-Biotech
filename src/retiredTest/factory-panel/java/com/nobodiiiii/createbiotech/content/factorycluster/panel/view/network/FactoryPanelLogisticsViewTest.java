package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentRole;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentStatus;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentType;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressObserver;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkProjection.RemoteCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkProjectionService;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelOpeningHint;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelAuthorityValidator;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelMenu;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelViewRequestPacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.LogisticsNetworkResponsePacket.CommandKind;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.LogisticsNetworkResponsePacket.Status;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredHolder;

class FactoryPanelLogisticsViewTest {
	private static final UUID PANEL = id(1), CLUSTER = id(2), EPOCH = id(3), SESSION = id(4);
	private static final UUID NETWORK = id(5), OTHER_NETWORK = id(6), UNBOUND = id(7);
	private static final SpaceAddress PANEL_ADDRESS = new SpaceAddress(Level.OVERWORLD, null,
		new BlockPos(4, 5, 6));
	private static final FactoryPanelOpening OPENING = new FactoryPanelOpening(PANEL_ADDRESS,
		PANEL, SESSION, CLUSTER, EPOCH);
	private static final ClusterAuthority PANEL_AUTHORITY = new ClusterAuthority(
		ClusterMemberType.PANEL, PANEL);

	private LogisticsNetworkDirectory directory;
	private LogisticsAddressObserver observer;
	private LogisticsNetworkProjectionService service;

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			var bootstrapped = net.minecraft.server.Bootstrap.class
				.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
		net.minecraft.world.item.Items.AIR.getDefaultInstance();
		bind(BuiltInRegistries.MENU, CBMenuTypes.FACTORY_PANEL,
			() -> new MenuType<>((containerId, inventory) -> null, FeatureFlags.VANILLA_SET));
	}

	@BeforeEach
	void setUp() {
		directory = new LogisticsNetworkDirectory();
		directory.ensure(NETWORK);
		directory.compareAndSetAlias(NETWORK, 0, Optional.of("主仓"));
		directory.ensure(OTHER_NETWORK);
		Map<LogisticsAddressObserver.NetworkComponentKey, LogisticsAddressComponent> components =
			new LinkedHashMap<>();
		add(components, component(1, "packager", "Mill", ComponentStatus.ONLINE));
		add(components, component(2, "port", "Dock", ComponentStatus.OFFLINE));
		observer = LogisticsAddressObserver.restore(new LogisticsAddressObserver.State(components,
			9, 40), 32, ignored -> {});
		ClusterBinding binding = new ClusterBinding(CLUSTER, 7, PANEL_AUTHORITY,
			List.of(new LogisticsBinding(NETWORK), new LogisticsBinding(OTHER_NETWORK)));
		service = new LogisticsNetworkProjectionService(directory, observer,
			authority -> authority.equals(PANEL_AUTHORITY) ? Optional.of(binding) : Optional.empty(),
			24);
	}

	@Test
	void logisticsViewSelectsNetworksButCannotEditComponents() {
		LogisticsNetworkResponsePacket response = requestLogistics(OptionalLong.empty(), NETWORK,
			0, 24);

		assertEquals(Status.CURRENT, response.projection().status());
		assertEquals(NETWORK, response.projection().selectedNetworkId().orElseThrow());
		assertTrue(response.projection().components().stream()
			.anyMatch(component -> component.status() == ComponentStatus.OFFLINE));
		assertEquals(Set.of(CommandKind.RENAME_ALIAS), response.commandKinds());
		assertFalse(response.commandKinds().contains(CommandKind.EDIT_CREATE_ADDRESS));
		assertFalse(response.commandKinds().contains(CommandKind.CONFIGURE_AGENT_ENDPOINT));
		assertEquals(Set.of(RemoteCapability.VIEW_LOGISTICS_DIRECTORY),
			response.projection().capabilities());
		assertEquals(2, response.projection().totalComponents());
		assertEquals(9, response.projection().revision());
		assertEquals(Optional.of("主仓"), response.projection().networks().getFirst().customAlias());
		assertTrue(response.projection().networks().get(1).customAlias().isEmpty());
	}

	@Test
	void selectionAndPaginationAreTheOnlyComponentOperations() {
		LogisticsNetworkResponsePacket second = requestLogistics(OptionalLong.empty(), NETWORK,
			1, 1);
		LogisticsNetworkResponsePacket other = requestLogistics(OptionalLong.empty(), OTHER_NETWORK,
			0, 24);

		assertEquals(List.of("Dock"), second.projection().components().stream()
			.map(LogisticsNetworkProjection.ComponentView::createAddress).toList());
		assertEquals(1, second.projection().offset());
		assertTrue(other.projection().components().isEmpty());
		assertEquals(OTHER_NETWORK, other.projection().selectedNetworkId().orElseThrow());
		assertThrows(IllegalArgumentException.class,
			() -> requestLogistics(OptionalLong.empty(), UNBOUND, 0, 24));
	}

	@Test
	void validatedOpeningRejectsSamePanelBindingFromAnotherCluster() {
		ClusterBinding wrongCluster = new ClusterBinding(id(8), 7, PANEL_AUTHORITY,
			List.of(new LogisticsBinding(NETWORK)));
		LogisticsNetworkProjectionService wrongClusterService =
			new LogisticsNetworkProjectionService(directory, observer,
				authority -> authority.equals(PANEL_AUTHORITY)
					? Optional.of(wrongCluster) : Optional.empty(), 24);
		FactoryPanelViewRequestPacket request = new FactoryPanelViewRequestPacket(OPENING,
			FactoryPanelView.NETWORK_LOGISTICS, OptionalLong.empty(), 0, 24,
			Optional.of(NETWORK));

		assertThrows(IllegalStateException.class,
			() -> wrongClusterService.response(viewAuthority(request)));
	}

	@Test
	void staleComponentRevisionReturnsNoComponentPageAndAdvertisesTheExactCurrentRevision() {
		LogisticsNetworkResponsePacket stale = requestLogistics(OptionalLong.of(8), NETWORK, 1, 1);

		assertEquals(Status.STALE, stale.projection().status());
		assertEquals(9, stale.projection().revision());
		assertEquals(0, stale.projection().offset());
		assertTrue(stale.projection().components().isEmpty());
		assertEquals(NETWORK, stale.projection().selectedNetworkId().orElseThrow());
		assertTrue(stale.commandKinds().isEmpty());
		assertEquals(2, stale.projection().networks().size());
	}

	@Test
	void responseCodecPreservesTheBoundedReadOnlyProjection() {
		LogisticsNetworkResponsePacket expected = requestLogistics(OptionalLong.empty(), NETWORK,
			0, 24);

		RegistryFriendlyByteBuf bytes = buffer();
		expected.write(bytes);
		LogisticsNetworkResponsePacket decoded = decode(bytes);

		assertEquals(expected, decoded);
		assertEquals(Set.of(CommandKind.RENAME_ALIAS), decoded.commandKinds());
	}

	@Test
	void capturedToolOpeningSelectsTheLogisticsViewFromServerOpeningContext() {
		RegistryFriendlyByteBuf openingData = buffer();
		OPENING.write(openingData);
		new FactoryPanelOpeningHint.OpeningContext(FactoryPanelOpeningHint.LOGISTICS_NETWORK,
			Optional.of(NETWORK)).write(openingData);
		FactoryPanelMenu client = restoreMenu(openingData);
		List<FactoryPanelViewRequestPacket> requests = new ArrayList<>();

		assertTrue(client.requestInitialView(requests::add));

		assertEquals(1, requests.size());
		assertEquals(FactoryPanelView.NETWORK_LOGISTICS, requests.getFirst().view());
		assertEquals(Optional.of(NETWORK), requests.getFirst().selection());
		assertTrue(requests.getFirst().knownRevision().isEmpty());
	}

	@Test
	void serviceRejectsARowLimitThatCannotBeRepresentedOnTheResponseWire() {
		assertThrows(IllegalArgumentException.class, () -> new LogisticsNetworkProjectionService(
			directory, observer, authority -> Optional.empty(),
			LogisticsNetworkResponsePacket.MAX_WIRE_ROWS + 1));
	}

	private LogisticsNetworkResponsePacket requestLogistics(OptionalLong knownRevision,
		UUID selectedNetwork, int offset, int limit) {
		FactoryPanelViewRequestPacket request = new FactoryPanelViewRequestPacket(OPENING,
			FactoryPanelView.NETWORK_LOGISTICS, knownRevision, offset, limit,
			Optional.of(selectedNetwork));
		return service.response(viewAuthority(request));
	}

	private static FactoryPanelAuthorityValidator.ViewValidation viewAuthority(
		FactoryPanelViewRequestPacket request) {
		return FactoryPanelAuthorityValidator.validateView(request.authorityRequest(),
			new FactoryPanelAuthorityValidator.ViewFacts(PANEL_ADDRESS, PANEL, SESSION, CLUSTER,
				CLUSTER, EPOCH, true, true, true, true, true, true, true, Optional.empty(), 24))
			.orElseThrow();
	}

	private static LogisticsAddressComponent component(int x, String discriminator,
		String createAddress, ComponentStatus status) {
		return new LogisticsAddressComponent(NETWORK, new ComponentIdentity(new SpaceAddress(
			Level.OVERWORLD, null, new BlockPos(x, 64, 0)), discriminator), createAddress,
			ComponentType.PACKAGER, Set.of(ComponentRole.INPUT), Optional.empty(), 0, 40, status);
	}

	private static void add(Map<LogisticsAddressObserver.NetworkComponentKey,
		LogisticsAddressComponent> components, LogisticsAddressComponent component) {
		components.put(new LogisticsAddressObserver.NetworkComponentKey(component.logisticsNetworkId(),
			component.componentIdentity()), component);
	}

	private static RegistryFriendlyByteBuf buffer() {
		try {
			Class<?> byteBufType = Class.forName("io.netty.buffer.ByteBuf");
			Object byteBuf = Class.forName("io.netty.buffer.Unpooled")
				.getMethod("buffer").invoke(null);
			return (RegistryFriendlyByteBuf) RegistryFriendlyByteBuf.class
				.getConstructor(byteBufType, RegistryAccess.class)
				.newInstance(byteBuf, RegistryAccess.EMPTY);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static FactoryPanelMenu restoreMenu(RegistryFriendlyByteBuf data) {
		try {
			return FactoryPanelMenu.class.getConstructor(int.class,
				net.minecraft.world.entity.player.Inventory.class, RegistryFriendlyByteBuf.class)
				.newInstance(23, null, data);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static LogisticsNetworkResponsePacket decode(Object bytes) {
		try {
			return LogisticsNetworkResponsePacket.class
				.getConstructor(RegistryFriendlyByteBuf.class).newInstance(bytes);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null) return;
			type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static <T> void bind(Registry<T> registry,
		DeferredHolder<T, ? extends T> holder, Supplier<? extends T> factory) {
		try {
			if (registry.getOptional(holder.getId()).isEmpty()) {
				registry.getClass().getMethod("unfreeze").invoke(registry);
				Registry.register(registry, holder.getId(), factory.get());
			}
			var bind = DeferredHolder.class.getDeclaredMethod("bind", boolean.class);
			bind.setAccessible(true);
			bind.invoke(holder, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}
}
