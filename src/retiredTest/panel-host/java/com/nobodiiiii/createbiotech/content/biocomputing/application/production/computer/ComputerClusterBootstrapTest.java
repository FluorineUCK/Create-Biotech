package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer.*;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.factorycluster.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

class ComputerClusterBootstrapTest {
    private static final BlockPos PANEL_POS = new BlockPos(0, 1, 1);
    private static final class TestPanel extends com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlockEntity {
        TestPanel() {
            super(BlockEntityType.FURNACE, PANEL_POS, Blocks.FURNACE.defaultBlockState());
            // The pure transaction fixture needs only the registry lookup used by snapshot saving.
            // Real scheduled publication and rollback are separately exercised in server GameTests.
            try {
                var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                var level = (net.minecraft.server.level.ServerLevel) ((sun.misc.Unsafe) field.get(null))
                    .allocateInstance(net.minecraft.server.level.ServerLevel.class);
                var registries = Level.class.getDeclaredField("registryAccess");
                registries.setAccessible(true);
                registries.set(level, RegistryAccess.EMPTY);
                setLevel(level);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }

    private static final ComputerStructureScanner.Limits LIMITS = new ComputerStructureScanner.Limits(3, 4, 64, 16);
    @BeforeAll static void initialize() { ComputerTopologyControllerTest.bootstrapMinecraft(); }

    @Test void plainPanelImportRejectsBeforeAccessingPlayerOrWorld() {
        var panel = new TestPanel();
        panel.setLevel(null);
        assertFalse(ComputerClusterBootstrap.requiresConfigurationImport(panel));
        assertEquals(ClusterBindingService.BindResult.NO_SOURCE,
            ComputerClusterBootstrap.importConfiguration(null, panel),
            "The local carried-config gate must reject ordinary Panels before any player/world access");
    }

    @Test void onlyCarriedNetworksOrCoordinatorAuthorityRequestConfigurationImport() {
        var panel = new TestPanel();
        panel.setLevel(null);
        panel.commitClusterBinding(new ClusterBinding(UUID.randomUUID(), 1,
            new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()),
            List.of(new LogisticsBinding(UUID.randomUUID()))));
        assertTrue(ComputerClusterBootstrap.requiresConfigurationImport(panel));
        panel.commitClusterBinding(new ClusterBinding(UUID.randomUUID(), 2,
            new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, UUID.randomUUID()), List.of()));
        assertTrue(ComputerClusterBootstrap.requiresConfigurationImport(panel),
            "A configured item may retain the previous coordinator even with no logistics networks");
        panel.commitClusterBinding(new ClusterBinding(UUID.randomUUID(), 3,
            new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()), List.of()));
        assertFalse(ComputerClusterBootstrap.requiresConfigurationImport(panel));
    }

    @Test void scheduledFormationBootstrapsOneCoordinatorOwnedLocalIdentity() {
        World world = new World(true);
        world.tick(0);
        ProductionEpoch epoch = world.computer.epoch().orElseThrow();
        ClusterBinding binding = world.computer.bindingState();
        assertEquals(new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
            world.computer.computerStructureMemberId().orElseThrow()), binding.authority());
        assertEquals(binding.clusterId(), epoch.groupId());
        assertTrue(binding.logisticsBindings().isEmpty());
        world.tick(40);
        assertEquals(epoch, world.computer.epoch().orElseThrow());
        assertEquals(binding, world.computer.bindingState());
    }

    @Test void automaticBindingAndFirstEpochReplicateToEveryLoadedComputer() {
        World world = new World(true);
        world.replica = new ObservedComputer(new BlockPos(1, 1, 1)).production();
        physical(world.replica).installResidentSnapshot(new ItemStack(Items.PAPER),
            ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 3, 0).orElseThrow());
        world.tick(0);
        assertNotNull(world.computer.bindingState());
        assertEquals(world.computer.bindingState(), world.replica.bindingState());
        assertEquals(world.computer.epoch().orElseThrow(), world.replica.epoch().orElseThrow());
        assertEquals(2, world.computer.epoch().orElseThrow().layout().members().size());
    }

    @Test void missingResidentAndOpenShellCannotClaimLocalIdentity() {
        World missing = new World(false);
        missing.tick(0);
        assertNull(missing.computer.bindingState());
        assertTrue(missing.computer.epoch().isEmpty());
        World open = new World(true);
        open.invalid = true;
        open.tick(0);
        assertNull(open.computer.bindingState());
        assertTrue(open.computer.epoch().isEmpty());
    }

    @Test void savedReloadedFirstEpochKeepsIdentity() {
        World world = new World(true);
        world.tick(0);
        ProductionEpoch epoch = world.computer.epoch().orElseThrow();
        ClusterBinding binding = world.computer.bindingState();
        CompoundTag saved = new CompoundTag();
        physical(world.computer).write(saved, RegistryAccess.EMPTY, false);
        world.computer = new ObservedComputer().production();
        physical(world.computer).read(saved, RegistryAccess.EMPTY, false);
        world.tick(40);
        assertEquals(epoch, world.computer.epoch().orElseThrow());
        assertEquals(binding, world.computer.bindingState());
    }

    @Test void existingBoundInactiveStructureIsNotAutomaticallyStarted() {
        World world = new World(true);
        ComputerTopologyController.refresh(world.computer, world, LIMITS);
        ClusterBinding binding = new ClusterBinding(UUID.randomUUID(), 0,
            new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
                world.computer.computerStructureMemberId().orElseThrow()), List.of());
        assertEquals(ClusterBindingPreparation.READY, world.computer.coordinatorMember.prepareClusterBinding(binding));
        world.computer.coordinatorMember.commitClusterBinding(binding);
        world.tick(40);
        assertTrue(world.computer.epoch().isEmpty());
        assertEquals(binding, world.computer.bindingState());
    }

    @Test void explicitOrderlyCloseRemainsStoppedAcrossScheduledRefresh() {
        World world = new World(true);
        world.tick(0);
        UUID epoch = world.computer.epoch().orElseThrow().epochId();
        assertEquals(ProductionComputer.EpochCloseResult.CLOSED,
            ComputerTopologyController.closeIdleEpoch(world.computer, epoch, new EpochQuiescence() {
                public boolean allRootsStopped() { return true; }
                public boolean nodeIdle(UUID id) { return true; }
                public boolean nodeRootFree(UUID id) { return true; }
                public boolean nodeMailboxEmpty(UUID id) { return true; }
            }, world, LIMITS));
        world.tick(40);
        assertTrue(world.computer.epoch().isEmpty());
    }

    @Test void latchedFaultIsNeverResetByBootstrap() {
        World world = new World(true);
        world.tick(0);
        ProductionEpoch epoch = world.computer.epoch().orElseThrow();
        assertEquals(ProductionComputer.FaultLatchResult.LATCHED,
            ComputerTopologyController.latchEpochFault(world.computer, epoch.epochId(), EpochFault.WIDTH, world));
        world.tick(40);
        assertEquals(epoch, world.computer.epoch().orElseThrow());
        assertTrue(world.computer.latchedEpochFaults().contains(EpochFault.WIDTH));
    }

    private static final class ObservedComputer extends TestComputer {
        ObservedComputer() { this(BlockPos.ZERO); }
        ObservedComputer(BlockPos pos) { super(BlockEntityType.FURNACE, pos, Blocks.FURNACE.defaultBlockState()); }
    }

    private static final class World implements ComputerTopologyController.WorldAccess {
        ProductionComputer computer;
        ProductionComputer replica;
        boolean invalid;
        final TestPanel panel = new TestPanel();
        UUID publishedEpoch;
        World(boolean resident) {
            computer = new ObservedComputer().production();
            if (resident) physical(computer).installResidentSnapshot(new ItemStack(Items.PAPER),
                ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 3, 0).orElseThrow());
        }
        void tick(long time) { computer.scheduledTopologyTick(time, this, LIMITS); }
        public ComputerStructureScanner.ScanResult scan(BlockPos seed, ComputerStructureScanner.Limits limits) {
            Set<BlockPos> casing = new HashSet<>();
            for (int x = 0; x < 3; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 3; z++)
                if (x == 0 || x == 2 || y == 0 || y == 3 || z == 0 || z == 2) casing.add(new BlockPos(x,y,z));
            List<ProductionLayout.Member> nodes = new ArrayList<>();
            nodes.add(new ProductionLayout.Member(computer.computerId().orElseThrow(), address(computer.owner().getBlockPos()),
                computer.installedProfile().orElse(null)));
            if (replica != null) nodes.add(new ProductionLayout.Member(replica.computerId().orElseThrow(),
                address(replica.owner().getBlockPos()), replica.installedProfile().orElse(null)));
            nodes.sort(Comparator.comparing(ProductionLayout.Member::computerId));
            ProductionStructure snapshot = new ProductionStructure(new ProductionLayout(productionBounds(new BoundingBox(0, 0, 0, 2, 3, 2)), nodes, List.of(new ProductionLayout.Panel(panel.memberId(), address(PANEL_POS),
                    Direction.WEST, ComputerShellMaterial.OPAQUE))), casing, Set.of(new ChunkPos(0, 0)));
            return new ComputerStructureScanner.ScanResult(invalid ? ComputerStructureScanner.State.OPEN_SHELL
                : computer.installedProfile().isPresent() ? ComputerStructureScanner.State.VALID
                : ComputerStructureScanner.State.VALID_NOT_READY, invalid ? null : snapshot,
                computer.computerStructureMemberId().orElse(null));
        }
        public com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlockEntity loadedPanel(BlockPos pos) {
            return PANEL_POS.equals(pos) ? panel : null;
        }
        public net.minecraft.world.level.block.state.BlockState blockState(BlockPos pos) { return new com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlock(
            net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()).defaultBlockState()
            .setValue(com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlock.FACING, Direction.WEST); }
        public boolean publishPanel(ProductionLayout.Panel node, ClusterBinding binding, ProductionEpoch epoch) {
            publishedEpoch = epoch.epochId(); return true;
        }
        public boolean panelPublishedFor(ProductionLayout.Panel node, ProductionEpoch epoch) {
            return epoch.epochId().equals(publishedEpoch);
        }
        public void withdrawPanel(ProductionLayout.Panel node) { publishedEpoch = null; }
        public boolean isLoaded(BlockPos pos) { return true; }
        public boolean isChunkLoaded(ChunkPos pos) { return true; }
        public boolean sameSpace(BlockPos origin, BlockPos pos) { return true; }
        public ProductionComputer loadedComputer(BlockPos pos) { return pos.equals(BlockPos.ZERO) ? computer : replica != null && pos.equals(replica.owner().getBlockPos()) ? replica : null; }
        public ProductionLayout.Address address(BlockPos pos) { return new ProductionLayout.Address(Level.OVERWORLD, null, pos); }
        public UUID newStructureMemberId() { return UUID.randomUUID(); }
    }
	private static ProductionLayout.Bounds productionBounds(BoundingBox bounds) {
		return new ProductionLayout.Bounds(bounds.minX(), bounds.minY(), bounds.minZ(),
			bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}
}
