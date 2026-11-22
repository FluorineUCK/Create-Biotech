package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.PeripheralNetworkLimits;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class PeripheralCaptureServiceTest {
    @Test void componentCatalogIsCapturedOnceAtEnvelopeAndBudgetedBeforeWorldValues() {
        var world = new World();
        world.catalog = new DataValue.RecordValue(Map.of("test:sample", new DataValue.TextValue("descriptor")));
        var snapshot = PeripheralCaptureService.capture(world, BlockPos.ZERO, limits);
        assertSame(world.catalog, snapshot.field("component_types"));
        assertEquals(1, world.catalogReads);
        var reader = (DataValue.RecordValue)((DataValue.RecordValue)snapshot.field("readers")).field("2,0,0");
        assertFalse(reader.fields().containsKey("component_types"));
        var oversized = new World();
        oversized.catalog = new DataValue.RecordValue(Map.of("test:large", new DataValue.TextValue("x".repeat(40000))));
        assertEquals("too_large", assertThrows(PeripheralCaptureService.CaptureException.class,
            () -> PeripheralCaptureService.capture(oversized, BlockPos.ZERO, limits)).code());
        assertEquals(0, oversized.reads);
    }
    @Test void providerExceptionsAreExplicitAndNeverPublishAPartialSnapshot() {
        var world = new World(); world.providerFailure = true;
        var error = assertThrows(PeripheralCaptureService.CaptureException.class,
            () -> PeripheralCaptureService.capture(world, BlockPos.ZERO, limits));
        assertEquals("invalid", error.code()); assertEquals("provider_failure", error.getMessage());
    }
    @Test void ordinaryFieldArithmeticAndRecordNodesComputeAndRememberACopyNotTheSource() {
        var world = new World(); var captured = PeripheralCaptureService.capture(world, BlockPos.ZERO, limits);
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        nodes.put("in", new ComputationGraph.Input("peripherals"));
        nodes.put("readers", new ComputationGraph.Field("in", "readers"));
        nodes.put("reader", new ComputationGraph.Field("readers", "2,0,0"));
        nodes.put("count", new ComputationGraph.Field("reader", "count"));
        nodes.put("one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)));
        nodes.put("next", new ComputationGraph.Binary(
            ComputationGraph.BinaryOp.ADD_INTEGER, "count", "one"));
        nodes.put("copy", new ComputationGraph.MakeRecord(Map.of("count", "next")));
        var graph = new ComputationGraph(
            Map.of("peripherals", DataType.Scalar.ANY), nodes, Map.of("view", "copy"));
        var program = new ProgramDefinition(graph,
            Map.of("remembered", new ProgramDefinition.Cell(
                DataType.Scalar.ANY, DataValue.NullValue.INSTANCE)),
            List.of(new ProgramDefinition.Write("remembered", "view")));
        var computer = new ComputerRuntime(UUID.randomUUID(),
            new ProgramArchive(program, Map.of()),
            new ResourceLimits(100, 100000, 1));
        computer.begin(new DataValue.RecordValue(Map.of("peripherals", captured)));
        for (int i = 0; i < 100 && computer.report().status() == ComputationMachine.Status.RUNNING; i++) computer.tick();
        assertEquals(ComputationMachine.Status.SUCCEEDED, computer.report().status());
        assertEquals(new DataValue.RecordValue(Map.of("count", new DataValue.IntegerValue(6))), computer.memory().field("remembered"));
        assertEquals(new DataValue.IntegerValue(5), world.data.get("count"));
        assertEquals(new DataValue.IntegerValue(5), ((DataValue.RecordValue)((DataValue.RecordValue)captured.field("readers")).field("2,0,0")).field("count"));
    }
    private final PeripheralNetworkLimits limits = new PeripheralNetworkLimits(100, 8, new ReadLimits(4096, 32768, 65536));
    @Test void discoversTargetsNotClientCoordinatesAndCopiesNamedValues() {
        var world = new World();
        var captured = PeripheralCaptureService.capture(world, BlockPos.ZERO, limits);
        assertEquals(new DataValue.IntegerValue(42), captured.field("game_time"));
        assertEquals(new DataValue.TextValue("minecraft:overworld"), captured.field("dimension"));
        var reader = (DataValue.RecordValue)((DataValue.RecordValue)captured.field("readers")).field("2,0,0");
        assertEquals(BlockSnapshotReader.position(new BlockPos(3, 0, 0)), reader.field("target"));
        assertEquals(BlockSnapshotReader.position(new BlockPos(2, 0, 0)), reader.field("reader"));
        world.data.put("count", new DataValue.IntegerValue(99));
        assertEquals(new DataValue.IntegerValue(5), reader.field("count"));
        assertEquals(1, world.reads);
    }
    @Test void incompleteTopologyNeverInvokesReaderAndAggregateOverflowIsNotPartialSuccess() {
        var world = new World(); world.unloaded = true;
        var error = assertThrows(PeripheralCaptureService.CaptureException.class,
            () -> PeripheralCaptureService.capture(world, BlockPos.ZERO, limits));
        assertEquals("invalid", error.code()); assertTrue(error.getMessage().contains("topology_unloaded"));
        assertEquals(0, world.reads);
        world.unloaded = false;
        var tiny = new PeripheralNetworkLimits(100, 8, new ReadLimits(5, 32768, 65536));
        assertEquals("too_large", assertThrows(PeripheralCaptureService.CaptureException.class,
            () -> PeripheralCaptureService.capture(world, BlockPos.ZERO, tiny)).code());
    }
    private static final class World implements PeripheralCaptureService.CaptureWorld {
        boolean unloaded, providerFailure; int reads, catalogReads;
        DataValue.RecordValue catalog = new DataValue.RecordValue(Map.of());
        final Map<String, DataValue> data = new HashMap<>(Map.of("count", new DataValue.IntegerValue(5)));
        public boolean loaded(BlockPos pos) { return !unloaded || !pos.equals(new BlockPos(1,1,0)); }
        public CableDiscovery.Cell cell(BlockPos pos) {
            if (pos.equals(new BlockPos(1,0,0))) return new CableDiscovery.Cell(CableDiscovery.Kind.CABLE, null);
            if (pos.equals(new BlockPos(2,0,0))) return new CableDiscovery.Cell(CableDiscovery.Kind.READER, Direction.EAST);
            return new CableDiscovery.Cell(CableDiscovery.Kind.EMPTY, null);
        }
        public long gameTime() { return 42; }
        public String dimension() { return "minecraft:overworld"; }
        public DataValue.RecordValue componentTypes() { catalogReads++; return catalog; }
        public DataValue.RecordValue read(BlockPos reader, BlockPos target, Direction targetFace) {
            if (providerFailure) throw new IllegalStateException("private provider diagnostic must not escape");
            assertEquals(new BlockPos(2,0,0), reader); assertEquals(new BlockPos(3,0,0), target);
            assertEquals(Direction.WEST, targetFace); reads++;
            return new DataValue.RecordValue(data);
        }
    }
}
