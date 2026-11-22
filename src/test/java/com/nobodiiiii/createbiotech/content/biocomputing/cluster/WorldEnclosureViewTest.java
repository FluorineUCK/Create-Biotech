package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner.Kind.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.EnclosureController;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerCasingBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.TransparentComputerCasingBlock;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import org.junit.jupiter.api.*;

class WorldEnclosureViewTest {
    static Block computer, casing, glass;
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null) type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
            .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        // Plain JVM peers construct synthetic block entities later; do not freeze every global registry.
        net.minecraft.SharedConstants.tryDetectVersion();
        var bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        bootstrapped.setAccessible(true); bootstrapped.setBoolean(null, true);
        computer = registered("computer", () -> new ComputerBlock(BlockBehaviour.Properties.of()));
        casing = registered("computer_casing", () -> new ComputerCasingBlock(BlockBehaviour.Properties.of()));
        glass = registered("transparent_computer_casing", () -> new TransparentComputerCasingBlock(BlockBehaviour.Properties.of()));
    }

    @Test void realRegisteredCasingAndTwoHalvesFormGeometryWithoutBlockEntities() throws Exception {
        var world = world();
        var bounds = new ClusterEnclosure.Bounds(BlockPos.ZERO, new BlockPos(2, 3, 2));
        for (var position : BlockPos.betweenClosed(bounds.min(), bounds.max()))
            if (bounds.boundary(position)) world.states.put(position.immutable(), (position.getY() == 0 ? casing : glass).defaultBlockState());
        var controller = new BlockPos(0, 1, 1);
        world.states.put(controller, new Controller().defaultBlockState().setValue(BlockStateProperties.FACING, Direction.WEST));
        world.states.put(new BlockPos(1, 1, 1), computer.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        world.states.put(new BlockPos(1, 2, 1), computer.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        var view = new WorldEnclosureView(world, controller);
        var limits = new EnclosureLimits(3, 7, 32);
        var guard = new ClusterStructureGuard();
        var result = guard.current(view, controller, limits);
        assertEquals(EnclosureScanner.Status.READY, result.status());
        assertEquals(Set.of(new BlockPos(1, 1, 1)), result.enclosure().orElseThrow().contacts().keySet());
        // The fixture state getter also checks loading. Excluding that assertion, each fresh
        // observation should perform one loaded check, not repeat it in the scanner and adapter.
        assertEquals(world.stateReads, world.loadingChecks - world.stateReads, "Initial geometry loading probes");
        for (int i = 0; i < 2; i++) {
            world.stateReads = world.loadingChecks = 0;
            assertSame(result, guard.current(view, controller, limits));
            assertTrue(guard.proof().orElseThrow().unchanged(view, controller, limits));
            assertEquals(2 * 13 * 13 * 13, world.stateReads, "Both barriers still observe the entire discovery window afresh");
            assertEquals(world.stateReads, world.loadingChecks - world.stateReads, "Stable geometry and terminal-proof loading probes");
        }
        world.states.put(new BlockPos(1, 2, 1), computer.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        assertEquals(EnclosureScanner.Status.READY,
            EnclosureScanner.inspect(new WorldEnclosureView(world, controller), bounds, controller, Direction.WEST, new EnclosureLimits(3, 7, 32)).status());
    }

    @Test void ordinaryTwoBlockObjectsDoNotBecomeComputersAndUnregisteredCasingDoesNotBecomeShell() throws Exception {
        var world = world(); var origin = BlockPos.ZERO; var adapter = new WorldEnclosureView(world, origin);
        world.states.put(origin, Blocks.OAK_DOOR.defaultBlockState()); assertEquals(INTERNAL, adapter.cell(origin).kind());
        world.states.put(origin, new ComputerCasingBlock(BlockBehaviour.Properties.of()).defaultBlockState());
        assertEquals(INTERNAL, adapter.cell(origin).kind());
        world.states.clear(); assertEquals(AIR, adapter.cell(origin).kind());
    }

    @Test void directAdapterReadsAlsoRespectUnloadedAndOutsideWorldWithoutStateGetter() throws Exception {
        var world = world(); var adapter = new WorldEnclosureView(world, BlockPos.ZERO);
        world.unloaded = new BlockPos(1, 0, 0);
        assertFalse(adapter.loaded(world.unloaded)); assertEquals(UNLOADED, adapter.cell(world.unloaded).kind());
        assertEquals(ILLEGAL, adapter.cell(new BlockPos(0, 500, 0)).kind());
        assertEquals(ILLEGAL, adapter.cell(new BlockPos(Integer.MAX_VALUE, 0, 0)).kind());
    }

    @Test void reusedViewClassifiesCurrentStateAndFacingRatherThanRememberingCells() throws Exception {
        var world = world(); var origin = BlockPos.ZERO; var adapter = new WorldEnclosureView(world, origin);
        for (var shell : List.of(casing, glass)) {
            world.states.put(origin, shell.defaultBlockState());
            assertEquals(new EnclosureScanner.Cell(CASING, null), adapter.cell(origin));
            world.states.remove(origin);
            assertEquals(new EnclosureScanner.Cell(AIR, null), adapter.cell(origin));
        }
        world.states.put(origin, Blocks.STONE.defaultBlockState());
        assertEquals(new EnclosureScanner.Cell(INTERNAL, null), adapter.cell(origin));
        var controller = new Controller();
        for (var direction : Direction.values()) {
            world.states.put(origin, controller.defaultBlockState().setValue(BlockStateProperties.FACING, direction));
            assertEquals(new EnclosureScanner.Cell(CONTROLLER_BLOCK, direction), adapter.cell(origin));
        }
        for (var half : DoubleBlockHalf.values()) {
            for (var direction : Direction.Plane.HORIZONTAL) {
                world.states.put(origin, computer.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, half)
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, direction));
                assertEquals(new EnclosureScanner.Cell(half == DoubleBlockHalf.LOWER ? COMPUTER_LOWER : COMPUTER_UPPER, direction),
                    adapter.cell(origin));
            }
        }
        world.states.put(origin, casing.defaultBlockState());
        assertEquals(new EnclosureScanner.Cell(CASING, null), adapter.cell(origin));
    }

    @Test void reusedViewRechecksLoadingBeforeEveryCurrentStateRead() throws Exception {
        var world = world(); var origin = BlockPos.ZERO; var adapter = new WorldEnclosureView(world, origin);
        world.states.put(origin, casing.defaultBlockState());
        assertEquals(CASING, adapter.cell(origin).kind());
        assertEquals(1, world.stateReads);
        world.unloaded = origin;
        world.states.put(origin, glass.defaultBlockState());
        assertFalse(adapter.loaded(origin));
        assertEquals(UNLOADED, adapter.cell(origin).kind());
        assertEquals(ILLEGAL, adapter.cell(new BlockPos(0, 500, 0)).kind());
        assertEquals(ILLEGAL, adapter.cell(new BlockPos(Integer.MAX_VALUE, 0, 0)).kind());
        assertEquals(1, world.stateReads);
        world.unloaded = null;
        assertTrue(adapter.loaded(origin));
        assertEquals(CASING, adapter.cell(origin).kind());
        world.states.remove(origin);
        assertEquals(AIR, adapter.cell(origin).kind());
        assertEquals(3, world.stateReads);
    }

    @Test void freshWorldReadsDoNotAllocateAnotherImmutableClassificationForEveryCell() throws Exception {
        var bean = (com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported()); bean.setThreadAllocatedMemoryEnabled(true);
        var world = world(); var origin = BlockPos.ZERO; var adapter = new WorldEnclosureView(world, origin);
        world.states.put(origin, casing.defaultBlockState());
        var observed = new EnclosureScanner.Cell[100_000];
        for (int i = 0; i < 20_000; i++) observed[i] = adapter.cell(origin);
        world.stateReads = 0;
        long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        for (int i = 0; i < observed.length; i++) observed[i] = adapter.cell(origin);
        long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
        assertEquals(observed.length, world.stateReads, "Reuse values, not world observations or loaded permission");
        assertTrue(bytes < 1024 * 1024, "Per-cell classification allocated " + bytes + " bytes");
        assertTrue(Arrays.stream(observed).allMatch(cell -> cell.equals(new EnclosureScanner.Cell(CASING, null))));
        world.states.remove(origin); assertEquals(AIR, adapter.cell(origin).kind());
        world.unloaded = origin; assertEquals(UNLOADED, adapter.cell(origin).kind());
        assertEquals(observed.length + 1, world.stateReads);
    }

    @Test void controllerFacingIsThePhysicalBlockStateNotAssumedHorizontal() throws Exception {
        var world = world(); var block = new Controller(); var adapter = new WorldEnclosureView(world, BlockPos.ZERO);
        for (var direction : Direction.values()) {
            world.states.put(BlockPos.ZERO, block.defaultBlockState().setValue(BlockStateProperties.FACING, direction));
            assertEquals(new EnclosureScanner.Cell(CONTROLLER_BLOCK, direction), adapter.cell(BlockPos.ZERO));
        }
    }

    @Test void missingControllerFacingRetainsTheInvalidGeometryContract() throws Exception {
        var world = world(); var adapter = new WorldEnclosureView(world, BlockPos.ZERO);
        world.states.put(BlockPos.ZERO, new MissingFacingController().defaultBlockState());
        var failure = assertThrows(IllegalArgumentException.class, () -> adapter.cell(BlockPos.ZERO));
        assertEquals("missing_facing", failure.getMessage());
    }

    @Test void oldInternalComponentTagIsNotRequiredAndCannotMakeAHoleIntoCasing() throws Exception {
        var world = world(); var adapter = new WorldEnclosureView(world, BlockPos.ZERO);
        world.states.put(BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        var registry = BuiltInRegistries.BLOCK;
        var prior = registry.getTags().collect(java.util.stream.Collectors.toMap(
            com.mojang.datafixers.util.Pair::getFirst, pair -> pair.getSecond().stream().map(holder -> (Holder<Block>)holder).toList()));
        var tags = new HashMap<>(prior);
        tags.put(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
            ResourceLocation.parse("create_biotech:computer_internal_components")), List.of(registry.wrapAsHolder(Blocks.STONE)));
        try {
            assertEquals(INTERNAL, adapter.cell(BlockPos.ZERO).kind());
            registry.bindTags(tags); assertEquals(INTERNAL, adapter.cell(BlockPos.ZERO).kind());
        } finally { registry.bindTags(prior); }
    }

    private static Block registered(String name, Supplier<Block> factory) throws Exception {
        var id = ResourceLocation.fromNamespaceAndPath("create_biotech", name);
        if (BuiltInRegistries.BLOCK.getOptional(id).isPresent()) return BuiltInRegistries.BLOCK.get(id);
        BuiltInRegistries.BLOCK.getClass().getMethod("unfreeze").invoke(BuiltInRegistries.BLOCK);
        return Registry.register(BuiltInRegistries.BLOCK, id, factory.get());
    }
    private static World world() throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        var world = (World)((sun.misc.Unsafe)field.get(null)).allocateInstance(World.class);
        world.states = new HashMap<>(); return world;
    }
    private static final class World extends ServerLevel {
        Map<BlockPos, BlockState> states; BlockPos unloaded; int stateReads, loadingChecks;
        private World() { super(null, null, null, null, null, null, null, false, 0, List.of(), false, null); }
        @Override public boolean isLoaded(BlockPos pos) { loadingChecks++; return !pos.equals(unloaded); }
        @Override public boolean hasChunkAt(BlockPos pos) { return isLoaded(pos); }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public BlockState getBlockState(BlockPos pos) {
            if (!isLoaded(pos) || !isInWorldBounds(pos)) throw new AssertionError("unsafe state read: " + pos);
            stateReads++;
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { throw new AssertionError("geometry must not touch RAM or any block entity"); }
    }
    private static final class Controller extends Block implements EnclosureController {
        Controller() { super(BlockBehaviour.Properties.of()); registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.FACING, Direction.NORTH)); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(BlockStateProperties.FACING); }
        public Direction outward(BlockState state) { return state.getValue(BlockStateProperties.FACING); }
    }
    private static final class MissingFacingController extends Block implements EnclosureController {
        MissingFacingController() { super(BlockBehaviour.Properties.of()); }
        public Direction outward(BlockState state) { return null; }
    }
}
