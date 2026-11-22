package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;

/** Real reader/registry/vanilla signal algorithms; substitutes only the headless world boundary. */
class EnvironmentalReadComponentsTest {
    private static final String WORLD = "create_biotech:world", ENVIRONMENT = "create_biotech:environment",
        REDSTONE = "create_biotech:redstone";
    private static final Set<String> IDS = Set.of(WORLD, ENVIRONMENT, REDSTONE);
    private static final ReadLimits LIMITS = new ReadLimits(4096, 32768, 65536);
    private static DirectionalBlock source, conductor, analog;
    @BeforeAll static void bootstrap() throws Exception {
        CapabilitySnapshotValuesTest.bootstrap();
        source = new DirectionalBlock(false, false);
        conductor = new DirectionalBlock(true, false);
        analog = new DirectionalBlock(false, true);
    }

    @Test void catalogExposesExactTypesAsGeneralTickComponentsWithoutCreateDependency() {
        var builder = ReadComponentRegistry.<BlockReadContext>builder();
        BuiltinReadComponents.register(builder);
        var standalone = builder.build();
        assertTrue(standalone.ids().containsAll(IDS));
        assertFalse(standalone.ids().contains(CreateReadComponents.KINETICS));
        var weather = new DataType.RecordType(Map.of("raining", BOOLEAN, "thundering", BOOLEAN,
            "rain_level", DECIMAL, "thunder_level", DECIMAL));
        Map<String, DataType> expected = Map.of(
            WORLD, new DataType.RecordType(Map.of("dimension", TEXT, "game_time", INTEGER, "day_time", INTEGER,
                "day_cycle_tick", INTEGER, "is_day", BOOLEAN, "is_night", BOOLEAN, "weather", weather)),
            ENVIRONMENT, new DataType.RecordType(Map.of("biome", ANY, "block_light", INTEGER,
                "sky_light", INTEGER, "light", INTEGER, "sky_visible", BOOLEAN)),
            REDSTONE, new DataType.RecordType(Map.of("face", TEXT, "signal", INTEGER, "direct", INTEGER, "comparator", ANY)));
        var catalog = ReadComponents.registry().describe();
        expected.forEach((id, type) -> {
            var descriptor = record(catalog.field(id));
            assertEquals(bool(false), descriptor.field("additional"));
            assertEquals(TypeArchiveCodec.encode(type), descriptor.field("type"));
            assertEquals(text(id), descriptor.field("id"));
            assertEquals(text("tick"), descriptor.field("update_hint"));
            assertEquals(new DataValue.ListValue(List.of(text("block"))), descriptor.field("target_kinds"));
        });
    }

    @Test void worldUsesCurrentWeatherRawTimeFloorModuloAndVanillaFixedTimeDayNight() throws Exception {
        var world = world();
        var value = component(read(world, Direction.NORTH), WORLD);
        assertEquals(text("minecraft:overworld"), value.field("dimension"));
        assertEquals(integer(1234), value.field("game_time"));
        assertEquals(integer(-1), value.field("day_time"));
        assertEquals(integer(23999), value.field("day_cycle_tick"));
        assertEquals(bool(true), value.field("is_day"));
        assertEquals(bool(false), value.field("is_night"));
        assertEquals(new DataValue.RecordValue(Map.of("raining", bool(true), "thundering", bool(true),
            "rain_level", new DataValue.DecimalValue(1), "thunder_level", new DataValue.DecimalValue(1))), value.field("weather"));
        world.fixed = true;
        var fixed = component(read(world, Direction.NORTH), WORLD);
        assertEquals(bool(false), fixed.field("is_day"));
        assertEquals(bool(false), fixed.field("is_night"));
        world.fixed = false; world.dayTime = 24002;
        var skyDarken = Level.class.getDeclaredField("skyDarken"); skyDarken.setAccessible(true); skyDarken.setInt(world, 8);
        var night = component(read(world, Direction.NORTH), WORLD);
        assertEquals(bool(false), night.field("is_day"));
        assertEquals(bool(true), night.field("is_night"));
        assertEquals(integer(2), night.field("day_cycle_tick"));
    }

    @Test void environmentUsesTargetLightWithSkyDarkeningAndUnregisteredBiomeIsFieldError() throws Exception {
        var world = world();
        var value = component(read(world, Direction.NORTH), ENVIRONMENT);
        assertEquals(text("minecraft:plains"), value.field("biome"));
        assertEquals(integer(3), value.field("block_light"));
        assertEquals(integer(13), value.field("sky_light"));
        assertEquals(integer(8), value.field("light"));
        assertEquals(bool(false), value.field("sky_visible"));
        world.biome = Holder.direct(allocate(Biome.class));
        var unregistered = component(read(world, Direction.NORTH), ENVIRONMENT);
        assertError("unregistered_biome", unregistered.field("biome"));
        assertEquals(integer(8), unregistered.field("light"));
    }

    @Test void directionalSignalsUseOppositeQuerySideForAllSixAsymmetricFaces() throws Exception {
        var world = world();
        Direction[] faces = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        int[] signal = {2, 1, 4, 3, 6, 5}, direct = {9, 8, 11, 10, 13, 12};
        for (int i = 0; i < faces.length; i++) {
            var value = component(read(world, faces[i]), REDSTONE);
            assertEquals(text(faces[i].getName()), value.field("face"));
            assertEquals(integer(signal[i]), value.field("signal"));
            assertEquals(integer(direct[i]), value.field("direct"));
            assertError("unsupported", value.field("comparator"));
        }
        assertEquals(0, world.neighborReads);
    }

    @Test void conductingTargetUsesVanillaNeighborPowerAndChecksEveryRequiredNeighborBeforeSignal() throws Exception {
        var world = world(); world.state = conductor.defaultBlockState();
        world.neighborState = source.defaultBlockState();
        assertEquals(integer(13), component(read(world, Direction.NORTH), REDSTONE).field("signal"));
        assertTrue(world.neighborReads > 0);
        for (Direction direction : Direction.values()) {
            world.unloaded = world.target.relative(direction); world.neighborReads = 0; world.signalReads = 0;
            var rejected = read(world, Direction.NORTH);
            assertError("target_unloaded", rejected.field(REDSTONE));
            assertEquals(0, world.neighborReads);
            assertEquals(0, world.signalReads);
            assertInstanceOf(DataValue.RecordValue.class, rejected.field(WORLD));
            assertInstanceOf(DataValue.RecordValue.class, rejected.field(ENVIRONMENT));
        }
        world.state = source.defaultBlockState();
        assertEquals(integer(4), component(read(world, Direction.NORTH), REDSTONE).field("signal"));
    }

    @Test void outOfBuildHeightNeighborsAreAirNotUnloadedChunks() throws Exception {
        for (int height : new int[] {0, 31}) {
            var world = world(); world.target = new BlockPos(0, height, 0);
            world.state = conductor.defaultBlockState(); world.neighborState = source.defaultBlockState();
            var result = component(read(world, Direction.NORTH), REDSTONE);
            assertEquals(integer(13), result.field("signal"));
            assertEquals(0, world.outsideLoadChecks);
        }
    }

    @Test void comparatorZeroIsSupportedAndModdedValuesAreNotClamped() throws Exception {
        var world = world(); world.state = analog.defaultBlockState();
        assertEquals(integer(0), component(read(world, Direction.NORTH), REDSTONE).field("comparator"));
        world.analogValue = 23;
        assertEquals(integer(23), component(read(world, Direction.NORTH), REDSTONE).field("comparator"));
        world.state = source.defaultBlockState();
        assertError("unsupported", component(read(world, Direction.NORTH), REDSTONE).field("comparator"));
        assertEquals(2, world.analogReads);
    }

    @Test void vanillaDoubleChestGuardsOnlyItsPartnerChunkBeforeComparatorReads() throws Exception {
        var world = chestWorld(true);
        var partner = (LootTrackingChest) world.extraEntities.get(world.target.east());
        partner.fillRaw();
        assertEquals(integer(8), component(selected(world, REDSTONE), REDSTONE).field("comparator"));
        assertTrue(world.partnerEntityReads > 0, "Legal control must traverse vanilla double-container path");
        world.unloaded = world.target.east(); world.partnerEntityReads = 0;
        var denied = read(world, Direction.NORTH);
        assertError("target_unloaded", denied.field(REDSTONE));
        assertEquals(0, world.unloadedStateReads); assertEquals(0, world.unloadedEntityReads);
        assertEquals(0, world.partnerEntityReads);
        assertInstanceOf(DataValue.RecordValue.class, denied.field(WORLD));
        assertInstanceOf(DataValue.RecordValue.class, denied.field(ENVIRONMENT));
        world.unloaded = world.target.west();
        assertEquals(integer(8), component(selected(world, REDSTONE), REDSTONE).field("comparator"));
        world.state = world.state.setValue(ChestBlock.TYPE, ChestType.SINGLE);
        world.unloaded = world.target.east();
        assertEquals(integer(0), component(selected(world, REDSTONE), REDSTONE).field("comparator"));
    }

    @Test void vanillaChestPreflightFollowsBlockedAndMismatchedBranchesAndKnownAirHeight() throws Exception {
        for (boolean partnerAbove : new boolean[] {false, true}) {
            var world = chestWorld(true);
            world.unloaded = partnerAbove ? world.target.east().above() : world.target.above();
            assertError("target_unloaded", selected(world, REDSTONE).field(REDSTONE));
            assertEquals(0, world.unloadedStateReads); assertEquals(0, world.unloadedEntityReads);
        }
        var blocked = chestWorld(true);
        blocked.extraStates.put(blocked.target.above(), Blocks.STONE.defaultBlockState());
        blocked.unloaded = blocked.target.east();
        assertEquals(integer(0), component(selected(blocked, REDSTONE), REDSTONE).field("comparator"));
        assertEquals(0, blocked.unloadedStateReads);
        var mismatched = chestWorld(true);
        mismatched.extraStates.put(mismatched.target.east(), Blocks.STONE.defaultBlockState());
        mismatched.unloaded = mismatched.target.east().above();
        assertEquals(integer(0), component(selected(mismatched, REDSTONE), REDSTONE).field("comparator"));
        assertEquals(0, mismatched.unloadedStateReads);
        var top = chestWorld(false);
        top.target = new BlockPos(15, 31, 0);
        top.entity = new LootTrackingChest(top.target, top.state);
        assertEquals(integer(0), component(selected(top, REDSTONE), REDSTONE).field("comparator"));
        assertEquals(0, top.outsideLoadChecks);
    }

    @Test void vanillaChestPendingLootIsAFieldErrorWithoutUnpackingEitherParticipatingContainer() throws Exception {
        for (boolean partnerLoot : new boolean[] {false, true}) {
            var world = chestWorld(partnerLoot);
            var target = (LootTrackingChest) world.entity;
            var pending = partnerLoot ? (LootTrackingChest) world.extraEntities.get(world.target.east()) : target;
            pending.fillRaw();
            var lootKey = ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/simple_dungeon"));
            pending.setLootTable(lootKey, 73L);
            var value = component(selected(world, REDSTONE), REDSTONE);
            assertError("unresolved_loot", value.field("comparator"));
            assertEquals(integer(0), value.field("signal")); assertEquals(integer(0), value.field("direct"));
            assertEquals(0, target.unpackCalls); assertEquals(0, pending.unpackCalls);
            assertEquals(lootKey, pending.getLootTable()); assertEquals(73L, pending.getLootTableSeed());
            assertEquals(27 * 64, pending.rawItemCount());
            pending.setLootTable(null);
            assertInstanceOf(DataValue.IntegerValue.class, component(selected(world, REDSTONE), REDSTONE).field("comparator"));
        }
    }

    @Test void pendingLootInBlockedOrNonParticipatingChestDoesNotInvalidateComparatorZero() throws Exception {
        var world = chestWorld(true);
        var partner = (LootTrackingChest) world.extraEntities.get(world.target.east());
        partner.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/simple_dungeon")));
        world.extraStates.put(world.target.east(), world.extraStates.get(world.target.east()).setValue(ChestBlock.TYPE, ChestType.SINGLE));
        assertEquals(integer(0), component(selected(world, REDSTONE), REDSTONE).field("comparator"));
        assertEquals(0, partner.unpackCalls);
        ((LootTrackingChest) world.entity).setLootTable(partner.getLootTable());
        ((LootTrackingChest) world.entity).unpackCalls = 0;
        world.extraStates.put(world.target.above(), Blocks.STONE.defaultBlockState());
        assertEquals(integer(0), component(selected(world, REDSTONE), REDSTONE).field("comparator"));
        assertEquals(0, ((LootTrackingChest) world.entity).unpackCalls);
    }

    @Test void captureGuardsRejectAllNewComponentsBeforeAnyEnvironmentalGetter() throws Exception {
        var world = world();
        assertInstanceOf(DataValue.RecordValue.class, read(world, Direction.NORTH).field(WORLD));
        for (String id : IDS) {
            world.environmentReads = 0; world.loaded = false;
            assertError("target_unloaded", selected(world, id).field("error"));
            assertEquals(0, world.environmentReads);
            world.loaded = true; world.allowed = false;
            assertError("access_denied", selected(world, id).field("error"));
            assertEquals(0, world.environmentReads);
            world.allowed = true; world.playerWorld = world();
            assertError("access_denied", selected(world, id).field("error"));
            assertEquals(0, world.environmentReads);
            world.playerWorld = null; world.border.setCenter(1000, 1000); world.border.setSize(1);
            assertError("access_denied", selected(world, id).field("error"));
            assertEquals(0, world.environmentReads);
            world.border = new WorldBorder(); world.entity = new ComputerTarget();
            assertError("access_denied", selected(world, id).field("error"));
            assertEquals(0, world.environmentReads);
            world.entity = new PrivateTarget();
            assertError("access_denied", selected(world, id).field("error"));
            assertEquals(0, world.environmentReads);
            world.entity = null;
            assertInstanceOf(DataValue.RecordValue.class, selected(world, id).field(id));
        }
    }

    @Test void sessionsCacheEachNewComponentAndFreshCapturesUpdateWithoutSerializedDataPermission() throws Exception {
        var world = world();
        var context = new BlockReadContext(world, world.target, Direction.NORTH, actor(world), world.state, null, LIMITS, false);
        var session = ReadComponents.registry().session(context, LIMITS);
        var initial = session.read(IDS); int reads = world.environmentReads;
        for (String id : IDS) assertSame(initial.field(id), session.read(id));
        assertEquals(reads, world.environmentReads);
        world.dayTime = 55; world.blockLight = 9; world.state = analog.defaultBlockState(); world.analogValue = 4;
        var fresh = read(world, Direction.NORTH);
        assertEquals(integer(55), component(fresh, WORLD).field("day_time"));
        assertEquals(integer(9), component(fresh, ENVIRONMENT).field("block_light"));
        assertEquals(integer(4), component(fresh, REDSTONE).field("comparator"));
        assertEquals(integer(-1), component(initial, WORLD).field("day_time"));
        assertEquals(integer(3), component(initial, ENVIRONMENT).field("block_light"));
        assertError("unsupported", component(initial, REDSTONE).field("comparator"));
    }

    @Test void providerExceptionsAndSnapshotBudgetRemainExplicitAndIsolated() throws Exception {
        var world = world(); world.failTime = true;
        var failed = read(world, Direction.NORTH);
        assertError("provider_failure", failed.field(WORLD));
        assertInstanceOf(DataValue.RecordValue.class, failed.field(ENVIRONMENT));
        assertInstanceOf(DataValue.RecordValue.class, failed.field(REDSTONE));
        world.failTime = false;
        var context = new BlockReadContext(world, world.target, Direction.NORTH, actor(world), world.state, null, LIMITS, false);
        assertError("snapshot_limit", ReadComponents.registry().session(context, new ReadLimits(1, 32768, 65536)).read(WORLD));
        var limited = BlockSnapshotReader.readComponents(world, world.target, Direction.NORTH, actor(world),
            new ReadLimits(4096, 32768, 1), IDS);
        assertError("snapshot_limit", limited.field("error"));
    }

    @Test void wireFieldComparisonRecordAndProgramWriteOnlyChangeLocalRamCopy() throws Exception {
        var world = world();
        var snapshot = new DataValue.RecordValue(Map.of("component_types", ReadComponents.registry().describe(),
            "components", read(world, Direction.NORTH)));
        var limits = new ArchiveLimits(100000, 100000, 1000000);
        var received = ProgramEditorWire.decodeValue(ProgramEditorWire.encodeValue(snapshot, limits), limits);
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        nodes.put("input", new ComputationGraph.Input("snapshot"));
        nodes.put("components", new ComputationGraph.Field("input", "components"));
        nodes.put("world", new ComputationGraph.Field("components", WORLD));
        nodes.put("environment", new ComputationGraph.Field("components", ENVIRONMENT));
        nodes.put("redstone", new ComputationGraph.Field("components", REDSTONE));
        nodes.put("day", new ComputationGraph.Field("world", "day_time"));
        nodes.put("light", new ComputationGraph.Field("environment", "block_light"));
        nodes.put("signal", new ComputationGraph.Field("redstone", "signal"));
        nodes.put("one", new ComputationGraph.Constant(integer(1)));
        nodes.put("next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "day", "one"));
        nodes.put("lit", new ComputationGraph.Binary(ComputationGraph.BinaryOp.LESS_INTEGER, "light", "signal"));
        nodes.put("copy", new ComputationGraph.MakeRecord(Map.of("day", "next", "lit", "lit")));
        var graph = new ComputationGraph(Map.of("snapshot", ANY), nodes, Map.of("result", "copy"));
        var program = new ProgramDefinition(graph, Map.of("remembered", new ProgramDefinition.Cell(ANY, DataValue.NullValue.INSTANCE)),
            List.of(new ProgramDefinition.Write("remembered", "result")));
        int sourceReads = world.environmentReads;
        var runtime = new ComputerRuntime(UUID.randomUUID(), new ProgramArchive(program, Map.of()), new ResourceLimits(100, 100000, 10));
        runtime.begin(new DataValue.RecordValue(Map.of("snapshot", received)));
        for (int i = 0; i < 100 && runtime.report().status() == ComputationMachine.Status.RUNNING; i++) runtime.tick();
        assertEquals(ComputationMachine.Status.SUCCEEDED, runtime.report().status());
        assertEquals(new DataValue.RecordValue(Map.of("day", integer(0), "lit", bool(true))), runtime.memory().field("remembered"));
        assertEquals(-1, world.dayTime); assertEquals(3, world.blockLight); assertEquals(0, world.analogValue);
        assertEquals(sourceReads, world.environmentReads);
        assertEquals(snapshot, received);
    }

    private static DataValue.RecordValue selected(TestWorld w, String id) throws Exception {
        return BlockSnapshotReader.readComponents(w, w.target, Direction.NORTH, actor(w), LIMITS, Set.of(id));
    }
    private static DataValue.RecordValue read(TestWorld w, Direction face) throws Exception {
        return BlockSnapshotReader.readComponents(w, w.target, face, actor(w), LIMITS, IDS);
    }
    private static DataValue.RecordValue component(DataValue.RecordValue v, String id) { return record(v.field(id)); }
    private static DataValue.RecordValue record(DataValue v) { return assertInstanceOf(DataValue.RecordValue.class, v); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.BooleanValue bool(boolean value) { return new DataValue.BooleanValue(value); }
    private static void assertError(String code, DataValue v) { assertEquals(code, assertInstanceOf(DataValue.ErrorValue.class, v).code()); }
    private static TestPlayer actor(TestWorld world) throws Exception {
        var player = allocate(TestPlayer.class); player.world = world.playerWorld == null ? world : world.playerWorld; return player;
    }
    private static TestWorld world() throws Exception {
        var w = allocate(TestWorld.class); w.loaded = true; w.allowed = true; w.border = new WorldBorder();
        w.target = new BlockPos(0, 16, 0); w.state = source.defaultBlockState(); w.neighborState = Blocks.AIR.defaultBlockState();
        w.dayTime = -1; w.blockLight = 3;
        w.extraStates = new HashMap<>(); w.extraEntities = new HashMap<>();
        w.biome = Holder.Reference.createStandAlone(new HolderOwner<Biome>() {}, ResourceKey.create(Registries.BIOME,
            ResourceLocation.withDefaultNamespace("plains")));
        return w;
    }
    private static TestWorld chestWorld(boolean paired) throws Exception {
        var w = world(); w.target = new BlockPos(15, 16, 0);
        w.state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
            .setValue(ChestBlock.TYPE, paired ? ChestType.LEFT : ChestType.SINGLE);
        w.entity = new LootTrackingChest(w.target, w.state);
        if (paired) {
            var partner = w.target.east();
            var state = w.state.setValue(ChestBlock.TYPE, ChestType.RIGHT);
            w.extraStates.put(partner, state); w.extraEntities.put(partner, new LootTrackingChest(partner, state));
        }
        return w;
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(((sun.misc.Unsafe)field.get(null)).allocateInstance(type));
    }
    private static final class TestPlayer extends ServerPlayer {
        TestWorld world;
        private TestPlayer() { super(null, null, null, null); }
        @Override public ServerLevel serverLevel() { return world; }
    }
    private static final class TestWorld extends ServerLevel {
        boolean loaded, allowed, fixed, failTime;
        int blockLight, analogValue, analogReads, environmentReads, neighborReads, signalReads, outsideLoadChecks;
        int unloadedStateReads, unloadedEntityReads, partnerEntityReads;
        long dayTime;
        BlockPos target, unloaded;
        BlockState state, neighborState;
        BlockEntity entity;
        Map<BlockPos, BlockState> extraStates;
        Map<BlockPos, BlockEntity> extraEntities;
        Holder<Biome> biome;
        WorldBorder border;
        TestWorld playerWorld;
        private TestWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public boolean isLoaded(BlockPos pos) {
            if (isOutsideBuildHeight(pos)) { outsideLoadChecks++; return false; }
            return loaded && !pos.equals(unloaded);
        }
        @Override public boolean mayInteract(Player player, BlockPos pos) { return allowed; }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public BlockState getBlockState(BlockPos pos) {
            if (isOutsideBuildHeight(pos)) return Blocks.AIR.defaultBlockState();
            if (pos.equals(unloaded)) { unloadedStateReads++; throw new IllegalStateException("Unloaded state query"); }
            assertTrue(loaded && allowed); assertNotEquals(unloaded, pos, "No unloaded block access");
            if (!pos.equals(target)) { neighborReads++; return extraStates.getOrDefault(pos, neighborState); }
            return state;
        }
        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            if (pos.equals(unloaded)) { unloadedEntityReads++; throw new IllegalStateException("Unloaded entity query"); }
            if (pos.equals(target)) return entity;
            if (extraEntities.containsKey(pos)) partnerEntityReads++;
            return extraEntities.get(pos);
        }
        @Override public <T extends net.minecraft.world.entity.Entity> List<T> getEntitiesOfClass(
                Class<T> type, net.minecraft.world.phys.AABB bounds, java.util.function.Predicate<? super T> predicate) {
            return List.of();
        }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public RegistryAccess registryAccess() { return RegistryAccess.EMPTY; }
        @Override public DimensionType dimensionType() {
            return new DimensionType(fixed ? OptionalLong.of(6000) : OptionalLong.empty(), true, false, false, true, 1,
                true, false, 0, 32, 32, BlockTags.INFINIBURN_OVERWORLD, ResourceLocation.withDefaultNamespace("overworld"),
                0, new DimensionType.MonsterSettings(false, true, ConstantInt.of(0), 0));
        }
        @Override public boolean isDay() { environmentReads++; return super.isDay(); }
        @Override public boolean isNight() { environmentReads++; return super.isNight(); }
        @Override public long getGameTime() { environmentReads++; if (failTime) throw new IllegalStateException("private"); return 1234; }
        @Override public long getDayTime() { environmentReads++; return dayTime; }
        @Override public float getRainLevel(float partial) { environmentReads++; assertEquals(1.0F, partial); return 1; }
        @Override public float getThunderLevel(float partial) { environmentReads++; assertEquals(1.0F, partial); return 1; }
        @Override public Holder<Biome> getBiome(BlockPos pos) { environmentReads++; assertEquals(target, pos); return biome; }
        @Override public int getBrightness(LightLayer layer, BlockPos pos) {
            environmentReads++; assertEquals(target, pos); return layer == LightLayer.BLOCK ? blockLight : 13;
        }
        @Override public int getSkyDarken() { return 5; }
        @Override public int getRawBrightness(BlockPos pos, int darkness) {
            environmentReads++; assertEquals(target, pos); assertEquals(5, darkness); return 8;
        }
        @Override public boolean canSeeSky(BlockPos pos) { environmentReads++; assertEquals(target, pos); return false; }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) { throw new AssertionError("No world writes"); }
        @Override public void setDayTime(long time) { throw new AssertionError("No world time writes"); }
    }
    private static final class DirectionalBlock extends Block {
        private final boolean conducts, analog;
        DirectionalBlock(boolean conducts, boolean analog) { super(BlockBehaviour.Properties.of()); this.conducts = conducts; this.analog = analog; }
        @Override public boolean shouldCheckWeakPower(BlockState state, SignalGetter level, BlockPos pos, Direction side) { return conducts; }
        @Override public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
            var world = (TestWorld) level; world.environmentReads++; world.signalReads++; return conducts ? 0 : side.ordinal() + 1;
        }
        @Override public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
            ((TestWorld) level).environmentReads++; return conducts ? 0 : side.ordinal() + 8;
        }
        @Override public boolean hasAnalogOutputSignal(BlockState state) { return analog; }
        @Override public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
            var world = (TestWorld) level; world.environmentReads++; world.analogReads++; return world.analogValue;
        }
    }
    private static final class ComputerTarget extends SmartBlockEntity {
        ComputerTarget() { super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            behaviours.add(new ComputerBehaviour(this, () -> net.minecraft.world.item.ItemStack.EMPTY, () -> false));
        }
    }
    /** Vanilla ChestBlock/CompoundContainer/getItem remain real; observe the loot side-effect boundary. */
    private static final class LootTrackingChest extends ChestBlockEntity {
        int unpackCalls;
        LootTrackingChest(BlockPos pos, BlockState state) { super(pos, state); }
        @Override public void unpackLootTable(Player player) { unpackCalls++; super.unpackLootTable(player); }
        void fillRaw() {
            for (int i = 0; i < getContainerSize(); i++)
                getItems().set(i, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 64));
        }
        int rawItemCount() { return getItems().stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum(); }
    }
    private static final class PrivateTarget extends SmartBlockEntity {
        PrivateTarget() { super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState()); }
        @Override public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
            behaviours.add(new LogisticallyLinkedBehaviour(this, false) {
                @Override public boolean mayInteract(Player player) { return false; }
            });
        }
    }
}
