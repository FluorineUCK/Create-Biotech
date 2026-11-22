package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressObserver;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkProjectionService;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

class FactoryPanelBlockEntityTest {
	private static final UUID FIRST_NETWORK =
		UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID SECOND_NETWORK =
		UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final UUID REBOUND_CLUSTER =
		UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID ACTIVE_CLUSTER =
		UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final UUID STRUCTURE_MEMBER =
		UUID.fromString("30000000-0000-0000-0000-000000000002");
	private static final SpaceAddress PANEL_ADDRESS = new SpaceAddress(Level.OVERWORLD, null,
		new BlockPos(0, 1, 1));

	@BeforeAll
	static void bootstrapMinecraft() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		markMinecraftBootstrapped();
	}

	@Test
	void panelObservationCapturesItsIdentityAddressFaceAndBaseMaterial() {
		FactoryPanelBlockEntity panel = panel();
		assertEquals(panel.memberId(), panel.panelObservation(new com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress(
			net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
				net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create_biotech", "test")),
			null, BlockPos.ZERO), Direction.EAST, ComputerShellMaterial.TRANSPARENT).panelId());
	}

	@Test
	void bindingStateRoundTripsAndMissingIdentityTagsDoNotEraseGeneratedIds() {
		FactoryPanelBlockEntity source = panel();
		ClusterBinding rebound = new ClusterBinding(REBOUND_CLUSTER, 4,
			new ClusterAuthority(ClusterMemberType.PANEL, source.memberId()), List.of(
			new LogisticsBinding(FIRST_NETWORK),
			new LogisticsBinding(FIRST_NETWORK),
			new LogisticsBinding(SECOND_NETWORK)));
		assertEquals(ClusterBindingPreparation.READY,
			source.prepareClusterBinding(rebound));
		source.commitClusterBinding(rebound);
		source.setSelectedNetwork(SECOND_NETWORK);
		CompoundTag saved = new CompoundTag();
		source.write(saved, RegistryAccess.EMPTY, false);

		FactoryPanelBlockEntity restored = panel();
		restored.read(saved, RegistryAccess.EMPTY, false);

		assertEquals(source.memberId(), restored.memberId());
		assertEquals(REBOUND_CLUSTER, restored.clusterId());
		assertEquals(4, restored.bindingState().revision());
		assertEquals(rebound.authority(), restored.bindingState().authority());
		assertEquals(List.of(FIRST_NETWORK, SECOND_NETWORK), restored.logisticsBindings().stream()
			.map(LogisticsBinding::logisticsNetworkId).toList());
		assertEquals(SECOND_NETWORK, restored.selectedNetwork());

		UUID generatedPanelId = restored.memberId();
		UUID loadedClusterId = restored.clusterId();
		restored.read(new CompoundTag(), RegistryAccess.EMPTY, false);
		assertEquals(generatedPanelId, restored.memberId());
		assertEquals(loadedClusterId, restored.clusterId());
	}

	@Test
	void oversizedPersistedBindingStateIsMarkedInvalidInsteadOfTruncated() {
		FactoryPanelBlockEntity panel = panel();
		CompoundTag saved = new CompoundTag();
		panel.write(saved, RegistryAccess.EMPTY, false);
		net.minecraft.nbt.ListTag oversized = new net.minecraft.nbt.ListTag();
		for (int index = 0; index <= ClusterBinding.MAX_BINDINGS; index++)
			oversized.add(new LogisticsBinding(new UUID(0, index + 1L)).save());
		saved.getCompound(FactoryPanelBlockEntity.BINDING_STATE_KEY)
			.put("LogisticsBindings", oversized);

		FactoryPanelBlockEntity restored = panel();
		restored.read(saved, RegistryAccess.EMPTY, false);

		assertFalse(restored.hasValidBindingState());
		assertFalse(restored.canRebind());
		CompoundTag resaved = new CompoundTag();
		restored.write(resaved, RegistryAccess.EMPTY, false);
		FactoryPanelBlockEntity reloaded = panel();
		reloaded.read(resaved, RegistryAccess.EMPTY, false);
		assertFalse(reloaded.hasValidBindingState());
	}

	@Test
	void legacyClusterAndBindingTagsMigrateToSelfAuthoritativeRevisionZero() {
		UUID panelId = UUID.randomUUID();
		CompoundTag legacy = new CompoundTag();
		legacy.putUUID(FactoryPanelBlockEntity.PANEL_ID_KEY, panelId);
		legacy.putUUID(FactoryPanelBlockEntity.CLUSTER_ID_KEY, REBOUND_CLUSTER);
		net.minecraft.nbt.ListTag bindings = new net.minecraft.nbt.ListTag();
		bindings.add(new LogisticsBinding(FIRST_NETWORK).save());
		legacy.put(FactoryPanelBlockEntity.LOGISTICS_BINDINGS_KEY, bindings);

		FactoryPanelBlockEntity restored = panel();
		restored.read(legacy, RegistryAccess.EMPTY, false);

		assertTrue(restored.hasValidBindingState());
		assertEquals(REBOUND_CLUSTER, restored.clusterId());
		assertEquals(0, restored.bindingState().revision());
		assertEquals(new ClusterAuthority(ClusterMemberType.PANEL, panelId),
			restored.bindingState().authority());
		assertEquals(List.of(new LogisticsBinding(FIRST_NETWORK)),
			restored.logisticsBindings());
	}

	@Test
	void panelOwnsBindingsWithoutCreateLogisticsBehaviour() {
		FactoryPanelBlockEntity panel = panel();

        assertEquals(1, panel.getAllBehaviours().size());
        assertNotNull(panel.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortBehaviour.TYPE));
        assertNull(panel.getBehaviour(com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.TYPE));
		assertFalse(panel.logisticsBindings().iterator().hasNext());
	}

	@Test
	void panelProjectionEntryDelegatesToTheReadOnlyLogisticsService() {
		FactoryPanelBlockEntity panel = panel();
		ClusterAuthority authority = new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId());
		ClusterBinding binding = new ClusterBinding(REBOUND_CLUSTER, 1, authority,
			List.of(new LogisticsBinding(FIRST_NETWORK)));
		panel.commitClusterBinding(binding);
		panel.setSelectedNetwork(FIRST_NETWORK);
		LogisticsNetworkDirectory directory = new LogisticsNetworkDirectory();
		directory.ensure(FIRST_NETWORK);
		LogisticsNetworkProjectionService projection = new LogisticsNetworkProjectionService(directory,
			new LogisticsAddressObserver(8), requested -> requested.equals(authority)
				? java.util.Optional.of(binding) : java.util.Optional.empty(), 8);

		assertEquals(FIRST_NETWORK, panel.projectLogisticsDirectory(projection, 0, 8)
			.networks().getFirst().logisticsNetworkId());
	}

	@Test
	void activeEpochPublicationRegistersActualPanelOnceAndWithdrawalRestoresOnlyLocalRebinding() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerPanel panel = new ServerPanel(server, PANEL_ADDRESS);
		ProductionEpoch epoch = epochFor(panel);
		ClusterBinding binding = new ClusterBinding(ACTIVE_CLUSTER, 7,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, STRUCTURE_MEMBER),
			List.of(new LogisticsBinding(FIRST_NETWORK)));

		assertTrue(panel.publishForComputerEpoch(binding, epoch));
		assertTrue(panel.publishForComputerEpoch(binding, epoch),
			"publishing the identical epoch must be idempotent");

		assertTrue(panel.isPublishedFor(ACTIVE_CLUSTER, epoch.epochId()));
		assertFalse(panel.isPublishedFor(ACTIVE_CLUSTER, UUID.randomUUID()));
		assertEquals(binding, panel.bindingState());
		assertTrue(panel.hasValidBindingState());
		assertFalse(panel.canRebind());
		assertEquals(List.of(panel), ClusterMemberIndex.members(server, ACTIVE_CLUSTER,
			ClusterMemberType.PANEL), "the same actual Panel must be indexed once");

		panel.setSelectedNetwork(FIRST_NETWORK);
		panel.withdrawComputerEpochPublication();

		assertFalse(panel.isPublishedFor(ACTIVE_CLUSTER, epoch.epochId()));
		assertTrue(ClusterMemberIndex.members(server, ACTIVE_CLUSTER,
			ClusterMemberType.PANEL).isEmpty());
		assertTrue(panel.hasValidBindingState());
		assertTrue(panel.canRebind());
		assertNotEquals(ACTIVE_CLUSTER, panel.clusterId());
		assertEquals(new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()),
			panel.bindingState().authority());
		assertEquals(null, panel.selectedNetwork());
	}

	@Test
	void sameClusterComputerAuthorityFromAnotherCoordinatorCannotPublishThePanel() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerPanel panel = new ServerPanel(server, PANEL_ADDRESS);
		ProductionEpoch epoch = epochFor(panel);
		ClusterBinding wrongCoordinator = new ClusterBinding(ACTIVE_CLUSTER, 7,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
				UUID.fromString("30000000-0000-0000-0000-000000000099")),
			List.of());

		assertFalse(panel.publishForComputerEpoch(wrongCoordinator, epoch));
		assertFalse(panel.isPublishedFor(ACTIVE_CLUSTER, epoch.epochId()));
		assertTrue(ClusterMemberIndex.members(server, ACTIVE_CLUSTER,
			ClusterMemberType.PANEL).isEmpty());
		assertTrue(panel.canRebind());
	}

	@Test
	void invalidationClearsPublicationMarkerAndAllowsOneCleanRepublish() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerPanel panel = new ServerPanel(server, PANEL_ADDRESS);
		ProductionEpoch epoch = epochFor(panel);
		ClusterBinding binding = new ClusterBinding(ACTIVE_CLUSTER, 7,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, STRUCTURE_MEMBER), List.of());
		assertTrue(panel.publishForComputerEpoch(binding, epoch));

		panel.invalidate();

		assertFalse(panel.isPublishedFor(ACTIVE_CLUSTER, epoch.epochId()));
		assertTrue(panel.canRebind());
		assertTrue(ClusterMemberIndex.members(server, ACTIVE_CLUSTER,
			ClusterMemberType.PANEL).isEmpty());

		panel.initialize();
		assertTrue(panel.publishForComputerEpoch(binding, epoch));
		assertEquals(List.of(panel), ClusterMemberIndex.members(server, ACTIVE_CLUSTER,
			ClusterMemberType.PANEL));
		panel.withdrawComputerEpochPublication();
	}

	@Test
	void withdrawalRemovesFrozenIndexEntryAfterPersistentIdentityAndClusterTamper() {
		MinecraftServer server = allocate(DedicatedServer.class);
		ServerPanel panel = new ServerPanel(server, PANEL_ADDRESS);
		ProductionEpoch epoch = epochFor(panel);
		ClusterBinding published = new ClusterBinding(ACTIVE_CLUSTER, 7,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, STRUCTURE_MEMBER), List.of());
		assertTrue(panel.publishForComputerEpoch(published, epoch));

		CompoundTag tampered = new CompoundTag();
		panel.write(tampered, RegistryAccess.EMPTY, false);
		tampered.putUUID(FactoryPanelBlockEntity.PANEL_ID_KEY, UUID.randomUUID());
		tampered.put(FactoryPanelBlockEntity.BINDING_STATE_KEY, new ClusterBinding(
			UUID.randomUUID(), 8,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, STRUCTURE_MEMBER),
			List.of()).save());
		panel.read(tampered, RegistryAccess.EMPTY, false);

		assertFalse(panel.isPublishedFor(ACTIVE_CLUSTER, epoch.epochId()),
			"persistent identity/cluster tamper must revoke live authority immediately");
		assertEquals(List.of(panel), ClusterMemberIndex.members(server, ACTIVE_CLUSTER,
			ClusterMemberType.PANEL), "control: the frozen publication remains indexed until withdrawal");
		panel.withdrawComputerEpochPublication();
		assertTrue(ClusterMemberIndex.members(server, ACTIVE_CLUSTER,
			ClusterMemberType.PANEL).isEmpty(),
			"withdrawal must remove the frozen old-key entry, not look up the tampered live key");
	}

	private static FactoryPanelBlockEntity panel() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		markMinecraftBootstrapped();
		return new FactoryPanelBlockEntity(BlockEntityType.FURNACE, BlockPos.ZERO,
			Blocks.FURNACE.defaultBlockState());
	}

	private static ProductionEpoch epochFor(FactoryPanelBlockEntity panel) {
		BoundingBox bounds = new BoundingBox(0, 0, 0, 2, 2, 2);
		Set<BlockPos> shell = new LinkedHashSet<>();
		for (int x = 0; x <= 2; x++)
			for (int y = 0; y <= 2; y++)
				for (int z = 0; z <= 2; z++)
					if (x == 0 || x == 2 || y == 0 || y == 2 || z == 0 || z == 2)
						shell.add(new BlockPos(x, y, z));
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN,
			2, 0).orElseThrow();
		ProductionLayout.Member computer = new ProductionLayout.Member(STRUCTURE_MEMBER,
			new ProductionLayout.Address(Level.OVERWORLD, null, new BlockPos(1, 1, 1)), profile);
		ProductionLayout.Panel node = new ProductionLayout.Panel(panel.memberId(),
			new ProductionLayout.Address(PANEL_ADDRESS.dimension(), PANEL_ADDRESS.subLevelId(), PANEL_ADDRESS.localPos()), Direction.WEST, ComputerShellMaterial.OPAQUE);
		return ProductionEpoch.freeze(ACTIVE_CLUSTER, STRUCTURE_MEMBER, new ProductionStructure(new ProductionLayout(productionBounds(bounds), List.of(computer), List.of(node)), shell, Set.of(new ChunkPos(0, 0))).layout());
	}

	private static <T> T allocate(Class<T> type) {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static final class ServerPanel extends FactoryPanelBlockEntity {
		private static final FactoryPanelBlock TEST_BLOCK =
			new FactoryPanelBlock(Block.Properties.of());
		private static final BlockEntityType<BlockEntity> TEST_TYPE =
			BlockEntityType.Builder.<BlockEntity>of((pos, state) -> null, TEST_BLOCK).build(null);
		private final MinecraftServer server;
		private final SpaceAddress address;

		private ServerPanel(MinecraftServer server, SpaceAddress address) {
			super(TEST_TYPE, address.localPos(), TEST_BLOCK.defaultBlockState()
				.setValue(FactoryPanelBlock.FACING, Direction.WEST)
				.setValue(FactoryPanelBlock.BASE_MATERIAL, ComputerShellMaterial.OPAQUE));
			this.server = server;
			this.address = address;
		}

		@Override
		public SpaceAddress memberAddress() {
			return address;
		}

		@Override
		protected MinecraftServer server() {
			return server;
		}
	}

	private static void markMinecraftBootstrapped() {
		try {
			java.lang.reflect.Field bootstrapped =
				net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			Object current = loadingModList.getMethod("get").invoke(null);
			if (current != null)
				return;
			Object empty = loadingModList.getMethod("of", List.class, List.class, List.class,
				List.class, java.util.Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
			if (empty == null)
				throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
	private static ProductionLayout.Bounds productionBounds(BoundingBox bounds) {
		return new ProductionLayout.Bounds(bounds.minX(), bounds.minY(), bounds.minZ(),
			bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}
}
