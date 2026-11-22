package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlock;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.neoforge.capabilities.BlockCapability;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockSnapshotReaderTest {
    @Test void genericReadsRespectOwnershipOfPhysicallyAttachedPackagerLinks() throws Exception {
        TestWorld world = world(); world.entity = new PackagerTarget();
        var link = new AttachedLinkTarget(); world.adjacentLink = link;
        for (String component : List.of(BuiltinReadComponents.ENERGY, BuiltinReadComponents.SERIALIZED_DATA, BuiltinReadComponents.INVENTORY_ITEM_TAGS)) {
            assertEquals("access_denied", error(BlockSnapshotReader.readComponents(world, BlockPos.ZERO, Direction.NORTH,
                actor(world), LIMITS, Set.of(component))));
        }
        assertEquals(0, world.capabilities);
        link.allowed = true;
        var allowed = BlockSnapshotReader.readComponents(world, BlockPos.ZERO, Direction.NORTH, actor(world), LIMITS,
            Set.of(BuiltinReadComponents.ENERGY));
        assertEquals("unsupported", ((DataValue.ErrorValue)allowed.field(BuiltinReadComponents.ENERGY)).code());
        assertEquals(1, world.capabilities);
    }
    @Test void unattachedLinkDoesNotClaimPackagerButUnloadedAdjacencyFailsClosed() throws Exception {
        TestWorld world = world(); world.entity = new PackagerTarget();
        var link = new AttachedLinkTarget(); world.adjacentLink = link;
        link.setBlockState(link.getBlockState().setValue(PackagerLinkBlock.FACING, Direction.WEST));
        var allowed = BlockSnapshotReader.readComponents(world, BlockPos.ZERO, Direction.NORTH, actor(world), LIMITS,
            Set.of(BuiltinReadComponents.ENERGY));
        assertEquals("unsupported", ((DataValue.ErrorValue)allowed.field(BuiltinReadComponents.ENERGY)).code());
        world.unloadedAdjacent = true; world.capabilities = 0;
        assertEquals("target_unloaded", error(BlockSnapshotReader.readComponents(world, BlockPos.ZERO, Direction.NORTH,
            actor(world), LIMITS, Set.of(BuiltinReadComponents.ENERGY))));
        assertEquals(0, world.capabilities);
    }
    @Test void actualComponentCatalogAndSnapshotTravelExistingWireAndOrdinaryGraphChangesOnlyItsCopy() throws Exception {
        TestWorld world = world(); var target = new DataTarget(); world.entity = target;
        var snapshot = new DataValue.RecordValue(Map.of("component_types", ReadComponents.registry().describe(),
            "readers", new DataValue.RecordValue(Map.of("0,0,0", read(world)))));
        var archiveLimits = new ArchiveLimits(100000, 100000, 1000000);
        var received = ProgramEditorWire.decodeValue(ProgramEditorWire.encodeValue(snapshot, archiveLimits), archiveLimits);
        assertEquals(snapshot, received);
        var graph = new ComputationGraph(Map.of("peripherals", DataType.Scalar.ANY), Map.of(
            "in", new ComputationGraph.Input("peripherals"),
            "readers", new ComputationGraph.Field("in", "readers"),
            "reader", new ComputationGraph.Field("readers", "0,0,0"),
            "components", new ComputationGraph.Field("reader", "components"),
            "data", new ComputationGraph.Field("components", BuiltinReadComponents.SERIALIZED_DATA),
            "stored", new ComputationGraph.Field("data", "stored_only"),
            "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "stored", "one"),
            "copy", new ComputationGraph.MakeRecord(Map.of("stored_only", "next"))), Map.of("result", "copy"));
        var program = new ProgramDefinition(graph, Map.of("remembered", new ProgramDefinition.Cell(
            DataType.Scalar.ANY, DataValue.NullValue.INSTANCE)), List.of(new ProgramDefinition.Write("remembered", "result")));
        var computer = new ComputerRuntime(java.util.UUID.randomUUID(), new ProgramArchive(program, Map.of()),
            new ResourceLimits(100, 100000, 10));
        computer.begin(new DataValue.RecordValue(Map.of("peripherals", received)));
        for (int i = 0; i < 100 && computer.report().status() == ComputationMachine.Status.RUNNING; i++) computer.tick();
        assertEquals(ComputationMachine.Status.SUCCEEDED, computer.report().status());
        assertEquals(new DataValue.RecordValue(Map.of("stored_only", new DataValue.IntegerValue(43))), computer.memory().field("remembered"));
        assertEquals(42, target.stored);
        assertEquals(snapshot, received); assertEquals(1, target.saveReads);
    }
    @Test void catalogMarksOptionalItemTagsAndCreateAsAdditionalAndFreezesLatePolicyRegistration() {
        var catalog = ReadComponents.registry().describe();
        assertEquals(15, catalog.fields().size());
        for (var entry : catalog.fields().entrySet()) {
            var descriptor = (DataValue.RecordValue)entry.getValue();
            assertEquals(new DataValue.BooleanValue(Set.of(CreateReadComponents.KINETICS, CreateReadComponents.LOGISTICS,
                CreateReadComponents.STOCK_APPLICATION, CreateFluidReadComponent.ID, BuiltinReadComponents.INVENTORY_ITEM_TAGS)
                .contains(entry.getKey())), descriptor.field("additional"));
        }
        assertThrows(IllegalStateException.class, () -> ReadComponents.registerPolicy(c -> java.util.Optional.empty()));
    }
    @Test void rangeCannotBypassTargetWideCreateOwnershipPolicy()throws Exception{
        TestWorld world=world();world.entity=new PrivateLinkTarget();
        assertEquals("access_denied",error(BlockSnapshotReader.readRange(world,BlockPos.ZERO,Direction.NORTH,actor(world),LIMITS,BuiltinReadComponents.INVENTORY_ITEM_TAGS,new ReadRange(0,1))));
        assertEquals(0,world.capabilities);
        ((PrivateLinkTarget)world.entity).failPermission=true;
        assertEquals("provider_failure",error(BlockSnapshotReader.readRange(world,BlockPos.ZERO,Direction.NORTH,actor(world),LIMITS,BuiltinReadComponents.INVENTORY_ITEM_TAGS,new ReadRange(0,1))));
        assertEquals(0,world.capabilities);
    }
    @Test void rangeRejectsComputerAndLibraryBeforeCapabilityAccess()throws Exception{
        for(var target:List.of(new ComputerTarget(),new LibraryTarget())){
            TestWorld world=world();world.entity=target;
            assertEquals("access_denied",error(BlockSnapshotReader.readRange(world,BlockPos.ZERO,Direction.NORTH,actor(world),LIMITS,BuiltinReadComponents.INVENTORY_ITEM_TAGS,new ReadRange(0,1))));
            assertEquals(0,world.capabilities);
        }
    }
    @Test void failedCreateOwnershipPolicyFailsClosedWithoutInvokingComponents() throws Exception {
        TestWorld world = world(); var target = new PrivateLinkTarget(); target.failPermission = true; world.entity = target;
        assertEquals("provider_failure", error(BlockSnapshotReader.readComponents(world, BlockPos.ZERO, Direction.NORTH,
            actor(world), LIMITS, Set.of(BuiltinReadComponents.SERIALIZED_DATA))));
        assertEquals(0, world.capabilities);
    }
    @Test void createOwnershipPolicyAlsoDeniesGenericComponentsBeforeSerialization() throws Exception {
        TestWorld world = world(); world.entity = new PrivateLinkTarget();
        for (String component : List.of(BuiltinReadComponents.SERIALIZED_DATA, BuiltinReadComponents.ENERGY)) {
            assertEquals("access_denied", error(BlockSnapshotReader.readComponents(world, BlockPos.ZERO, Direction.NORTH,
                actor(world), LIMITS, Set.of(component))));
        }
        assertEquals(0, world.capabilities);
    }
    @Test void builtinRegistryDoesNotRequireCreateProvidersAndAdditionalFailureIsIsolated() throws Exception {
        var builder = ReadComponentRegistry.<BlockReadContext>builder();
        BuiltinReadComponents.register(builder);
        assertFalse(builder.build().ids().contains(CreateReadComponents.KINETICS));
        var extended = ReadComponentRegistry.<BlockReadContext>builder();
        BuiltinReadComponents.register(extended);
        extended.register(new ReadComponentProvider<>() {
            public ReadComponentDescriptor descriptor() {
                return new ReadComponentDescriptor("test:additional", "test.label", Set.of("block"),
                    com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.ANY,
                    ReadComponentDescriptor.UpdateHint.EXPLICIT, true);
            }
            public boolean supports(BlockReadContext c) { return true; }
            public DataValue read(BlockReadContext c) { throw new IllegalStateException("private provider diagnostics"); }
        });
        TestWorld world = world(); var target = new DataTarget(); world.entity = target;
        var context = new BlockReadContext(world, BlockPos.ZERO, Direction.NORTH, actor(world),
            Blocks.FURNACE.defaultBlockState(), target, LIMITS, true);
        var registry = extended.build(); var values = registry.session(context, LIMITS).read(registry.ids());
        assertInstanceOf(DataValue.RecordValue.class, values.field(BuiltinReadComponents.BLOCK));
        assertEquals("provider_failure", ((DataValue.ErrorValue)values.field("test:additional")).code());
        assertEquals(new DataValue.IntegerValue(42), ((DataValue.RecordValue)values.field(BuiltinReadComponents.SERIALIZED_DATA)).field("stored_only"));
    }
    @Test void componentCaptureKeepsLegacyViewsAndDistinguishesFullDataFromPublicSync() throws Exception {
        TestWorld world = world(); var target = new DataTarget(); world.entity = target;
        var result = read(world);
        var components = (DataValue.RecordValue)result.field("components");
        assertEquals(result.field("data"), components.field("create_biotech:public_data"));
        assertEquals(result.field("energy"), components.field("create_biotech:energy"));
        assertEquals(result.field("state"), ((DataValue.RecordValue)components.field("create_biotech:block")).field("state"));
        var serialized = (DataValue.RecordValue)components.field("create_biotech:serialized_data");
        assertEquals(new DataValue.IntegerValue(42), serialized.field("stored_only"));
        assertFalse(((DataValue.RecordValue)result.field("data")).fields().containsKey("stored_only"));
        assertEquals(1, target.syncReads); assertEquals(1, target.saveReads);
        target.stored = 99;
        assertEquals(new DataValue.IntegerValue(42), serialized.field("stored_only"));
    }
    @Test void selectingEnergyNeverReadsUnrequestedTagsOrOtherCapabilities() throws Exception {
        TestWorld world = world(); var target = new DataTarget(); world.entity = target;
        var result = BlockSnapshotReader.readComponents(world, BlockPos.ZERO, Direction.NORTH, actor(world), LIMITS,
            Set.of("create_biotech:energy"));
        assertEquals(Set.of("create_biotech:energy"), result.fields().keySet());
        assertEquals("unsupported", ((DataValue.ErrorValue)result.field("create_biotech:energy")).code());
        assertEquals(0, target.syncReads); assertEquals(0, target.saveReads); assertEquals(1, world.capabilities);
    }
    @Test void serializedDataHonorsPolicyWithoutAffectingPublicDataAndIsCachedWithinCapture() throws Exception {
        TestWorld world = world(); var target = new DataTarget(); world.entity = target;
        var denied = new BlockReadContext(world, BlockPos.ZERO, Direction.NORTH, actor(world),
            Blocks.FURNACE.defaultBlockState(), target, LIMITS, false);
        assertEquals("access_denied", ((DataValue.ErrorValue)denied.serializedData()).code());
        assertInstanceOf(DataValue.RecordValue.class, denied.publicData()); assertEquals(0, target.saveReads);
        var allowed = new BlockReadContext(world, BlockPos.ZERO, Direction.NORTH, actor(world),
            Blocks.FURNACE.defaultBlockState(), target, LIMITS, true);
        assertSame(allowed.serializedData(), allowed.serializedData()); assertEquals(1, target.saveReads);
        assertSame(allowed.publicData(), allowed.publicData()); assertEquals(2, target.syncReads);
    }
    @Test void genericSerializationCannotBypassLibraryVolumeAuthorization() throws Exception {
        TestWorld world = world(); world.entity = new LibraryTarget();
        assertEquals("access_denied", error(BlockSnapshotReader.readComponents(world, BlockPos.ZERO, Direction.NORTH,
            actor(world), LIMITS, Set.of(BuiltinReadComponents.SERIALIZED_DATA))));
        assertEquals(0, world.capabilities);
    }
    private static final class LibraryTarget extends SmartBlockEntity {
        LibraryTarget() { super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        @Override public void addBehaviours(java.util.List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours) {
            behaviours.add(new com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryServiceBehaviour(this));
        }
        @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { throw new AssertionError("Private service state"); }
        @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) { throw new AssertionError("Private service state"); }
    }
    @Test void evenExplicitSerializationSelectionCannotReadComputerMemory() throws Exception {
        TestWorld world = world(); world.entity = new ComputerTarget();
        assertEquals("access_denied", error(BlockSnapshotReader.readComponents(world, BlockPos.ZERO, Direction.NORTH,
            actor(world), LIMITS, Set.of("create_biotech:serialized_data"))));
        assertEquals(0, world.capabilities);
    }
    private static final ReadLimits LIMITS = new ReadLimits(4096, 32768, 65536);
    @BeforeAll static void bootstrap() throws Exception { CapabilitySnapshotValuesTest.bootstrap(); }

    @Test void unloadedAndDeniedTargetsNeverReadBlocksOrCapabilities() throws Exception {
        TestWorld world = world();
        world.loaded = false;
        assertEquals("target_unloaded", error(read(world)));
        assertEquals(0, world.reads);
        world.loaded = true;
        world.allowed = false;
        assertEquals("access_denied", error(read(world)));
        assertEquals(0, world.reads);
        assertEquals(0, world.capabilities);
    }

    @Test void computerBehaviourIsRejectedBeforePublicTagOrCapabilities() throws Exception {
        TestWorld world = world();
        world.entity = new ComputerTarget();
        assertEquals("access_denied", error(read(world)));
        assertEquals(0, world.capabilities);
    }

    @Test void permissionProviderFailureIsReportedWithoutReadingTarget() throws Exception {
        TestWorld world = world();
        world.failPermission = true;
        assertEquals("provider_failure", error(read(world)));
        assertEquals(0, world.reads);
        assertEquals(0, world.capabilities);
    }

    @Test void kineticsUsePublicAggregatesWithoutCreatingOrRecalculatingNetwork() throws Exception {
        TestWorld world = world();
        world.entity = new KineticTarget();
        var result = read(world);
        var create = (DataValue.RecordValue)result.field("create");
        var kinetics = (DataValue.RecordValue)create.field("kinetics");
        assertEquals(new DataValue.DecimalValue(16), kinetics.field("speed"));
        assertEquals(new DataValue.DecimalValue(100), kinetics.field("network_capacity"));
        assertEquals(new DataValue.DecimalValue(40), kinetics.field("network_stress"));
        assertEquals(new DataValue.BooleanValue(true), kinetics.field("overstressed"));
        assertEquals("unsupported", ((DataValue.ErrorValue)result.field("inventory")).code());
        assertEquals("unsupported", ((DataValue.ErrorValue)create.field("fluid_transport")).code());
    }

    private static DataValue.RecordValue read(TestWorld world) throws Exception {
        return BlockSnapshotReader.read(world, BlockPos.ZERO, Direction.NORTH, actor(world), LIMITS);
    }
    private static TestPlayer actor(TestWorld world) throws Exception {
        TestPlayer actor = allocate(TestPlayer.class);
        actor.world = world;
        return actor;
    }

    private static String error(DataValue.RecordValue value) { return ((DataValue.ErrorValue)value.field("error")).code(); }

    private static TestWorld world() throws Exception {
        TestWorld world = allocate(TestWorld.class);
        world.loaded = true;
        world.allowed = true;
        world.border = new WorldBorder();
        return world;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe)field.get(null)).allocateInstance(type));
    }

    // Test-only ServerLevel/Player subclasses avoid constructing a server. All world
    // operations the real reader uses are explicit; production is not given a fake API.
    private static final class TestPlayer extends ServerPlayer {
        TestWorld world;
        private TestPlayer() { super(null, null, null, null); }
        @Override public ServerLevel serverLevel() { return world; }
    }

    private static final class TestWorld extends ServerLevel {
        boolean loaded, allowed, failPermission, unloadedAdjacent;
        int reads, capabilities;
        WorldBorder border;
        BlockEntity entity;
        BlockEntity adjacentLink;
        private TestWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public boolean isLoaded(BlockPos position) { return loaded && !(unloadedAdjacent && position.equals(BlockPos.ZERO.east())); }
        @Override public boolean mayInteract(Player actor, BlockPos position) {
            if (failPermission) throw new IllegalStateException("permission provider failed");
            return allowed;
        }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public BlockState getBlockState(BlockPos position) {
            assertTrue(loaded && allowed, "Access guard must precede block state read");
            reads++;
            return Blocks.FURNACE.defaultBlockState();
        }
        @Override public BlockEntity getBlockEntity(BlockPos position) {
            assertTrue(loaded && allowed, "Access guard must precede block entity read");
            reads++;
            return position.equals(BlockPos.ZERO) ? entity : position.equals(BlockPos.ZERO.east()) ? adjacentLink : null;
        }
        @Override public RegistryAccess registryAccess() { return RegistryAccess.EMPTY; }
        @Override public <T, C> T getCapability(BlockCapability<T, C> capability, BlockPos position, C context) {
            capabilities++;
            return null;
        }
    }

    private static final class ComputerTarget extends SmartBlockEntity {
        ComputerTarget() { super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            behaviours.add(new ComputerBehaviour(this, () -> net.minecraft.world.item.ItemStack.EMPTY, () -> false));
        }
        @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            throw new AssertionError("Computer RAM/public tag must not be read");
        }
        @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            throw new AssertionError("Computer RAM/save must not be read");
        }
    }

    private static final class PackagerTarget extends PackagerBlockEntity {
        PackagerTarget() { super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}
        @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            throw new AssertionError("Unrequested or denied packager sync must not be read");
        }
        @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            throw new AssertionError("Denied packager saved data must not be read");
        }
    }
    private static final class AttachedLinkTarget extends PackagerLinkBlockEntity {
        private static final PackagerLinkBlock BLOCK = new PackagerLinkBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of());
        private static final BlockEntityType<AttachedLinkTarget> TYPE = BlockEntityType.Builder.of(AttachedLinkTarget::new, BLOCK).build(null);
        boolean allowed;
        AttachedLinkTarget() {
            this(BlockPos.ZERO.east(), BLOCK.defaultBlockState()
                    .setValue(PackagerLinkBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.WALL)
                    .setValue(PackagerLinkBlock.FACING, Direction.EAST));
        }
        AttachedLinkTarget(BlockPos pos, BlockState state) { super(TYPE, pos, state); }
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            behaviours.add(behaviour = new LogisticallyLinkedBehaviour(this, true) {
                @Override public boolean mayInteract(Player player) { return allowed; }
            });
        }
    }

    private static final class PrivateLinkTarget extends SmartBlockEntity {
        boolean failPermission;
        PrivateLinkTarget() { super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            behaviours.add(new LogisticallyLinkedBehaviour(this, false) {
                @Override public boolean mayInteract(Player player) {
                    if (failPermission) throw new IllegalStateException("private ownership diagnostic");
                    return false;
                }
            });
        }
        @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            throw new AssertionError("Denied link sync data must not be read");
        }
        @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            throw new AssertionError("Denied link saved data must not be read");
        }
    }

    private static final class DataTarget extends BlockEntity {
        int syncReads, saveReads, stored = 42;
        DataTarget() { super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            syncReads++; var tag = new CompoundTag(); tag.putBoolean("lit", true); return tag;
        }
        @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            saveReads++; tag.putInt("stored_only", stored);
        }
    }

    private static final class KineticTarget extends KineticBlockEntity {
        KineticTarget() { super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}
        @Override public float getSpeed() { return 16; }
        @Override public float getTheoreticalSpeed() { return -16; }
        @Override public boolean isOverStressed() { return true; }
        @Override public float calculateStressApplied() { throw new AssertionError("No recalculation"); }
        @Override public float calculateAddedStressCapacity() { throw new AssertionError("No recalculation"); }
        @Override public KineticNetwork getOrCreateNetwork() { throw new AssertionError("No network creation"); }
        @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            var tag = new CompoundTag();
            var network = new CompoundTag();
            network.putFloat("Capacity", 100);
            network.putFloat("Stress", 40);
            tag.put("Network", network);
            return tag;
        }
    }
}
