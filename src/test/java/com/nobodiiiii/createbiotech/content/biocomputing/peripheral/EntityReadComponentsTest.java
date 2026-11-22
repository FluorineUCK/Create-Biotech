package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.TypeArchiveCodec;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;

/** Real registered projection with a headless world/index; native tests exercise the actual server index. */
class EntityReadComponentsTest {
    static final String ID = "create_biotech:entities";
    static final BlockPos TARGET = new BlockPos(0, 16, 0);
    static final ReadLimits LIMITS = new ReadLimits(4096, 32768, 65536);
    static java.lang.reflect.Field emptyFluidHolder;
    static Object previousEmptyFluid;
    @BeforeAll static void bootstrap() throws Exception {
        CapabilitySnapshotValuesTest.bootstrap();
        // Entity constructors require NeoForge's empty-fluid holder, absent in headless JUnit.
        // This projection never queries fluids. Restore the binding after the class, not a global registry mutation.
        emptyFluidHolder = net.neoforged.neoforge.registries.DeferredHolder.class.getDeclaredField("holder");
        emptyFluidHolder.setAccessible(true);
        previousEmptyFluid = emptyFluidHolder.get(net.neoforged.neoforge.common.NeoForgeMod.EMPTY_TYPE);
        if (previousEmptyFluid == null) emptyFluidHolder.set(net.neoforged.neoforge.common.NeoForgeMod.EMPTY_TYPE,
            Holder.direct(new net.neoforged.neoforge.fluids.FluidType(net.neoforged.neoforge.fluids.FluidType.Properties.create()) {}));
    }
    @AfterAll static void restoreFluidHolder() throws Exception {
        if (emptyFluidHolder != null) emptyFluidHolder.set(net.neoforged.neoforge.common.NeoForgeMod.EMPTY_TYPE, previousEmptyFluid);
    }

    @Test void registeredGeneralComponentHasExactTypedPublicSchema() throws Exception {
        var builder = ReadComponentRegistry.<BlockReadContext>builder();
        BuiltinReadComponents.register(builder);
        var descriptor = record(builder.build().describe().field(ID));
        assertEquals(new DataValue.BooleanValue(false), descriptor.field("additional"));
        assertEquals(text("tick"), descriptor.field("update_hint"));
        var type = new DataType.ListType(new DataType.RecordType(Map.of(
            "id", TEXT, "type", TEXT, "dimension", TEXT, "position", new DataType.RecordType(Map.of("x", DECIMAL, "y", DECIMAL, "z", DECIMAL)),
            "name", TEXT, "is_player", BOOLEAN, "alive", BOOLEAN, "health", new DataType.OptionalType(DECIMAL))));
        assertEquals(TypeArchiveCodec.encode(type), descriptor.field("type"));
        assertTrue(type.accepts(read(world())));
    }

    @Test void emptyCellIsEmptyListAndQueryIsConfinedToTargetCell() throws Exception {
        var w = world();
        assertEquals(new DataValue.ListValue(List.of()), read(w));
        assertEquals(new AABB(TARGET), w.lastBounds);
        assertEquals(1, w.queries);
    }

    @Test void recordsAreUuidOrderedAndExcludeOutsideRemovedAndSpectatorEntities() throws Exception {
        var w = world(); var second = entity(w, 2); var first = entity(w, 1);
        var outside = entity(w, 3); outside.setPos(5, 16, 5);
        var removed = entity(w, 4); removed.discard();
        var spectator = entity(w, 5); spectator.spectator = true;
        w.entities = List.of(second, outside, removed, spectator, first);
        var rows = list(read(w));
        assertEquals(2, rows.size());
        var row = record(rows.getFirst());
        assertEquals(text("00000000-0000-0000-0000-000000000001"), row.field("id"));
        assertEquals(text("minecraft:item"), row.field("type"));
        assertEquals(text("minecraft:overworld"), row.field("dimension"));
        assertEquals(text("sample"), row.field("name"));
        assertEquals(new DataValue.BooleanValue(false), row.field("is_player"));
        assertEquals(new DataValue.BooleanValue(true), row.field("alive"));
        assertEquals(DataValue.NullValue.INSTANCE, row.field("health"));
        assertEquals(new DataValue.DecimalValue(0.25), record(row.field("position")).field("x"));
        assertEquals(text("00000000-0000-0000-0000-000000000002"), record(rows.get(1)).field("id"));
    }

    @Test void livingEntityHealthIsDecimalAndOldSnapshotsNeverTrackWorldChanges() throws Exception {
        var w = world(); var pig = allocate(TestPig.class);
        seedLiving(pig, w, EntityType.PIG); placeLiving(pig, 0.5, 16, 0.5); pig.health = 7.5F; pig.name = "before";
        w.entities = List.of(pig);
        var old = record(list(read(w)).getFirst());
        assertEquals(new DataValue.DecimalValue(7.5), old.field("health"));
        pig.health = 4; pig.name = "after";
        assertEquals(text("before"), old.field("name"));
        assertEquals(new DataValue.DecimalValue(7.5), old.field("health"));
        var fresh = record(list(read(w)).getFirst());
        assertEquals(text("after"), fresh.field("name"));
        assertEquals(new DataValue.DecimalValue(4), fresh.field("health"));
        placeLiving(pig, 8, 16, 8);
        assertEquals(0, list(read(w)).size());
        assertEquals(new DataValue.DecimalValue(0.5), record(old.field("position")).field("x"));
    }

    @Test void playerIsAnOrdinaryRecordWithoutGlobalPlayerLookup() throws Exception {
        var w = world(); var player = actor(w); seedLiving(player, w, EntityType.PLAYER);
        placeLiving(player, 0.5, 16, 0.5); w.entities = List.of(player);
        var row = record(list(read(w)).getFirst());
        assertEquals(new DataValue.BooleanValue(true), row.field("is_player"));
        assertEquals(text("minecraft:player"), row.field("type"));
        assertEquals(new DataValue.DecimalValue(20), row.field("health"));
    }

    @Test void targetGuardsRunBeforeQueryAndAdjacentEntityGuardsRunBeforeAttributes() throws Exception {
        var w = world(); w.unloaded = TARGET;
        assertError("target_unloaded", envelope(w).field("error")); assertEquals(0, w.queries);
        w.unloaded = null; w.denied = TARGET;
        assertError("access_denied", envelope(w).field("error")); assertEquals(0, w.queries);
        w.denied = null;
        var e = entity(w, 1); e.setPos(-0.05, 16, 0.5); e.setBoundingBox(new AABB(-0.2, 16, 0.2, 0.2, 16.5, 0.8));
        w.entities = List.of(e); w.unloaded = new BlockPos(-1, 16, 0);
        assertError("target_unloaded", read(w)); assertEquals(0, e.nameReads);
        w.unloaded = null; w.denied = e.blockPosition();
        assertError("access_denied", read(w)); assertEquals(0, e.nameReads);
        w.denied = null;
        assertEquals(1, list(read(w)).size());
    }

    @Test void oneDeniedEntityRejectsWholeComponentRatherThanReturningAuthorizedPrefix() throws Exception {
        var w = world(); var one = entity(w, 1); var denied = entity(w, 2);
        denied.setPos(-0.05, 16, 0.5); denied.setBoundingBox(new AABB(-0.2, 16, 0.2, 0.2, 16.5, 0.8));
        w.entities = List.of(one, denied); w.denied = denied.blockPosition();
        assertError("access_denied", read(w));
    }

    @Test void providerFailureAndNonfiniteCoordinatesNeverLeakDetails() throws Exception {
        var w = world(); var e = entity(w, 1); w.entities = List.of(e); e.failName = true;
        var failure = assertInstanceOf(DataValue.ErrorValue.class, read(w));
        assertEquals("provider_failure", failure.code()); assertFalse(failure.message().contains("private"));
        e.failName = false;
        var position = Entity.class.getDeclaredField("position"); position.setAccessible(true);
        position.set(e, new net.minecraft.world.phys.Vec3(Double.NaN, 16, 0.5));
        assertError("provider_failure", read(w));
    }

    @Test void indexedProviderCannotKeepAddingPastBudgetOrSilentlyReturnPrefix() throws Exception {
        var w = world(); w.entities = List.of(entity(w, 1)); w.repeat = 1000000;
        assertError("snapshot_limit", read(w, new ReadLimits(32, 32768, 65536)));
        assertTrue(w.additions < 10, "The output sink must abort before a provider can create an unbounded list");
        w.repeat = 1; w.additions = 0;
        assertError("snapshot_limit", read(w, new ReadLimits(4096, 25, 65536)));
        assertError("snapshot_limit", read(w, new ReadLimits(4096, 32768, 30)));
    }

    @Test void cachedSessionIsFrozenButFreshSessionSamplesAgain() throws Exception {
        var w = world(); var e = entity(w, 1); w.entities = List.of(e);
        var c = new BlockReadContext(w, TARGET, Direction.NORTH, actor(w), Blocks.AIR.defaultBlockState(), null, LIMITS, false);
        var s = ReadComponents.registry().session(c, LIMITS); var old = s.read(ID);
        e.setCustomName(Component.literal("new"));
        assertSame(old, s.read(ID)); assertEquals(1, w.queries);
        assertEquals(text("sample"), record(list(old).getFirst()).field("name"));
        assertEquals(text("new"), record(list(read(w)).getFirst()).field("name"));
    }

    @Test void wireAndOrdinaryGraphOnlyWriteAnIndependentRecordIntoOwnRam() throws Exception {
        var w = world(); var e = entity(w, 1); w.entities = List.of(e);
        var snapshot = read(w);
        var limits = new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(10000, 100000, 1000000);
        var received = com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.decodeValue(
            com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.encodeValue(snapshot, limits), limits);
        var graph = new ComputationGraph(Map.of("snapshot", ANY), Map.of(
            "source", new ComputationGraph.Input("snapshot"), "first", new ComputationGraph.Index("source", 0),
            "id", new ComputationGraph.Field("first", "id"), "name", new ComputationGraph.Constant(text("local-only")),
            "copy", new ComputationGraph.MakeRecord(Map.of("id", "id", "name", "name"))), Map.of("result", "copy"));
        var program = new ProgramDefinition(graph, Map.of("result", new ProgramDefinition.Cell(ANY, DataValue.NullValue.INSTANCE)),
            List.of(new ProgramDefinition.Write("result", "result")));
        var runtime = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime(UUID.randomUUID(),
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(program, Map.of()), new ResourceLimits(100, 100000, 10));
        runtime.begin(new DataValue.RecordValue(Map.of("snapshot", received)));
        while (runtime.report().status() == ComputationMachine.Status.RUNNING) runtime.tick();
        assertEquals(ComputationMachine.Status.SUCCEEDED, runtime.report().status());
        assertEquals(text("local-only"), record(runtime.memory().field("result")).field("name"));
        assertEquals(text("sample"), record(list(snapshot).getFirst()).field("name"));
        assertEquals("sample", e.getName().getString()); assertEquals(snapshot, received);
        assertEquals(1, w.queries, "Evaluating the UUID must never reacquire its entity");
    }

    @Test void differentWorldAndNonfiniteHealthAreExplicitComponentErrors() throws Exception {
        var w = world(); var e = entity(world(), 1); w.entities = List.of(e);
        assertError("access_denied", read(w)); assertEquals(0, e.nameReads);
        var pig = allocate(TestPig.class); seedLiving(pig, w, EntityType.PIG);
        placeLiving(pig, 0.5, 16, 0.5); pig.name = "sample"; pig.health = Float.NaN; w.entities = List.of(pig);
        assertError("provider_failure", read(w));
    }

    @Test void overlongStructuredNameStopsTextTraversalAndNeverAcceptsItsPrefix() throws Exception {
        var w = world(); var e = entity(w, 1); w.entities = List.of(e);
        var visits = new java.util.concurrent.atomic.AtomicInteger();
        e.responseName = net.minecraft.network.chat.MutableComponent.create(new net.minecraft.network.chat.ComponentContents() {
            @Override public <T> Optional<T> visit(net.minecraft.network.chat.FormattedText.ContentConsumer<T> consumer) {
                for (int i = 0; i < 1000000; i++) {
                    if (visits.incrementAndGet() > 3) throw new IllegalStateException("Unbounded name traversal");
                    var stop = consumer.accept("x".repeat(1024));
                    if (stop.isPresent()) return stop;
                }
                return Optional.empty();
            }
            @Override public Type<?> type() { throw new AssertionError("No component serialization"); }
        });
        assertError("snapshot_limit", read(w, new ReadLimits(4096, 256, 65536)));
        assertTrue(visits.get() <= 3, "Only a bounded overflow marker, never render the whole entity name");
    }

    static DataValue read(TestWorld w) throws Exception { return read(w, LIMITS); }
    static DataValue read(TestWorld w, ReadLimits limits) throws Exception {
        var result = BlockSnapshotReader.readComponents(w, TARGET, Direction.NORTH, actor(w), limits, Set.of(ID));
        return result.fields().containsKey("error") ? result.field("error") : result.field(ID);
    }
    static DataValue.RecordValue envelope(TestWorld w) throws Exception {
        return BlockSnapshotReader.readComponents(w, TARGET, Direction.NORTH, actor(w), LIMITS, Set.of(ID));
    }
    static DataValue.RecordValue record(DataValue v) { return assertInstanceOf(DataValue.RecordValue.class, v); }
    static List<DataValue> list(DataValue v) { return assertInstanceOf(DataValue.ListValue.class, v).values(); }
    static DataValue.TextValue text(String v) { return new DataValue.TextValue(v); }
    static void assertError(String code, DataValue v) { assertEquals(code, assertInstanceOf(DataValue.ErrorValue.class, v).code()); }
    static TestWorld world() throws Exception {
        var w = allocate(TestWorld.class); w.border = new WorldBorder(); w.entities = List.of(); w.repeat = 1; return w;
    }
    static TestEntity entity(TestWorld w, int id) {
        var e = new TestEntity(w); e.setUUID(new UUID(0, id)); e.setPos(0.25, 16, 0.5);
        e.setCustomName(Component.literal("sample")); return e;
    }
    static TestPlayer actor(TestWorld w) throws Exception { var p = allocate(TestPlayer.class); p.world = w; return p; }
    // LivingEntity constructors start DefaultAttributes (NeoForge's loader-owned registries are absent).
    // Seed only read-side Entity fields; health/name are explicit world-boundary fixtures, never production seams.
    static void seedLiving(Entity e, TestWorld w, EntityType<?> type) throws Exception {
        setEntityField(e, "level", w); setEntityField(e, "type", type); e.setUUID(new UUID(0, 10));
    }
    static void placeLiving(Entity e, double x, double y, double z) throws Exception {
        setEntityField(e, "position", new net.minecraft.world.phys.Vec3(x, y, z));
        setEntityField(e, "blockPosition", BlockPos.containing(x, y, z));
        e.setBoundingBox(new AABB(x - 0.2, y, z - 0.2, x + 0.2, y + 0.5, z + 0.2));
    }
    static void setEntityField(Entity e, String name, Object value) throws Exception {
        var f = Entity.class.getDeclaredField(name); f.setAccessible(true); f.set(e, value);
    }
    static <T> T allocate(Class<T> type) throws Exception {
        var f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) f.get(null)).allocateInstance(type));
    }
    static final class TestPlayer extends ServerPlayer {
        TestWorld world; private TestPlayer() { super(null, null, null, null); }
        @Override public ServerLevel serverLevel() { return world; }
        @Override public boolean isSpectator() { return false; }
        @Override public float getHealth() { return 20; }
        @Override public Component getName() { return Component.literal("player"); }
    }
    static final class TestPig extends Pig {
        float health; String name;
        private TestPig() { super(EntityType.PIG, null); }
        @Override public float getHealth() { return health; }
        @Override public Component getName() { return Component.literal(name); }
    }
    static final class TestEntity extends Entity {
        boolean spectator, failName; int nameReads; Component responseName;
        TestEntity(Level world) { super(EntityType.ITEM, world); }
        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
        @Override protected void readAdditionalSaveData(CompoundTag tag) { throw new AssertionError("No entity NBT writes"); }
        @Override protected void addAdditionalSaveData(CompoundTag tag) { throw new AssertionError("No entity serialization"); }
        @Override public boolean isSpectator() { return spectator; }
        @Override public Component getName() { nameReads++; if (failName) throw new IllegalStateException("private"); return responseName != null ? responseName : super.getName(); }
    }
    static final class TestWorld extends ServerLevel {
        WorldBorder border; BlockPos unloaded, denied; List<Entity> entities; AABB lastBounds;
        int queries, additions, repeat;
        private TestWorld() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public int getMinBuildHeight() { return 0; }
        @Override public int getHeight() { return 32; }
        @Override public boolean isLoaded(BlockPos pos) { return !pos.equals(unloaded); }
        @Override public boolean mayInteract(Player player, BlockPos pos) { return !pos.equals(denied); }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public RegistryAccess registryAccess() { return RegistryAccess.EMPTY; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public <T extends Entity> void getEntities(EntityTypeTest<Entity, T> type, AABB bounds,
                Predicate<? super T> predicate, List<? super T> output, int maximum) {
            queries++; lastBounds = bounds;
            for (int i = 0; i < repeat; i++) for (var entity : entities) {
                var value = type.tryCast(entity);
                if (value != null && value.getBoundingBox().intersects(bounds) && predicate.test(value)) {
                    additions++; output.add(value);
                    // Intentionally keep adding: the NeoForge multipart tail cannot defeat the reader's budget.
                }
            }
        }
        @Override public Entity getEntity(UUID uuid) { throw new AssertionError("No global UUID lookup"); }
        @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) { throw new AssertionError("No world writes"); }
    }
}
