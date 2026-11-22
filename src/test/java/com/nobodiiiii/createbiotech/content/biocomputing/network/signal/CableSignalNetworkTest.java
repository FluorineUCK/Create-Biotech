package com.nobodiiiii.createbiotech.content.biocomputing.network.signal;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.SimpleIoNetwork;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import java.util.*;
import net.minecraft.core.*;
import org.junit.jupiter.api.Test;

class CableSignalNetworkTest {
    @Test void concentratedConnectorKeepsMemberIdentityAndFullInvalidationDependencies() {
        var world = new World(); var member = new BlockPos(4, 1, 2); var shell = new BlockPos(4, 0, 2);
        world.cells.put(BlockPos.ZERO, CableDiscovery.Kind.ENDPOINT); world.cells.put(BlockPos.ZERO.east(), CableDiscovery.Kind.CABLE);
        world.cells.put(member.east(), CableDiscovery.Kind.CABLE);
        var source = new CableSignalNetwork.Source(member, "p0", Direction.EAST, SimpleIoNetwork.Port.output(8), BlockPos.ZERO, Set.of(shell));
        var frame = CableSignalNetwork.sample(world, List.of(source), new CommunicationNetworkLimits(100, 8));
        assertEquals(8, frame.outputs().get(BlockPos.ZERO.east())); assertFalse(frame.outputs().containsKey(member.east()));
        assertEquals("active", frame.status().get(new CableSignalNetwork.Key(member, "p0")));
        assertEquals(Set.of(member, shell, BlockPos.ZERO, BlockPos.ZERO.east()), frame.dependencies().get(BlockPos.ZERO.east()));
    }
    static class World implements CableDiscovery.World {
        final Map<BlockPos, CableDiscovery.Kind> cells = new HashMap<>();
        final Set<BlockPos> unloaded = new HashSet<>();
        public boolean loaded(BlockPos pos) { return !unloaded.contains(pos); }
        public CableDiscovery.Cell cell(BlockPos pos) {
            assertTrue(loaded(pos), "Never fetch or force-load an unloaded cell");
            return new CableDiscovery.Cell(cells.getOrDefault(pos, CableDiscovery.Kind.EMPTY), null);
        }
    }
    @Test void explicitFaceDrivesItsCableAndDoesNotBridgeThroughTheComputer() {
        var world = new World(); world.cells.put(BlockPos.ZERO, CableDiscovery.Kind.ENDPOINT);
        world.cells.put(BlockPos.ZERO.east(), CableDiscovery.Kind.CABLE);
        world.cells.put(BlockPos.ZERO.west(), CableDiscovery.Kind.CABLE);
        var source = new CableSignalNetwork.Source(BlockPos.ZERO, "p0", Direction.EAST, SimpleIoNetwork.Port.output(15));
        var frame = CableSignalNetwork.sample(world, List.of(source), new CommunicationNetworkLimits(100, 8));
        assertEquals(15, frame.outputs().get(BlockPos.ZERO.east()));
        assertFalse(frame.outputs().containsKey(BlockPos.ZERO.west()));
        assertEquals("active", frame.status().get(source.key()));
        assertEquals(Set.of(BlockPos.ZERO, BlockPos.ZERO.east()), frame.dependencies().get(BlockPos.ZERO.east()));
    }
    @Test void multipleReadersDoNotCompeteWithTheOneDriver(){
        var world=new World();world.cells.put(BlockPos.ZERO.east(),CableDiscovery.Kind.CABLE);
        var a=new CableSignalNetwork.Source(BlockPos.ZERO,"p0",Direction.EAST,SimpleIoNetwork.Port.input());
        var b=new CableSignalNetwork.Source(BlockPos.ZERO,"p1",Direction.EAST,SimpleIoNetwork.Port.input());
        var c=new CableSignalNetwork.Source(BlockPos.ZERO,"p2",Direction.EAST,SimpleIoNetwork.Port.output(7));
        var frame=CableSignalNetwork.sample(world,List.of(a,b,c),new CommunicationNetworkLimits(100,8));
        assertEquals("input",frame.status().get(a.key()));assertEquals("input",frame.status().get(b.key()));
        assertEquals(7,frame.outputs().get(BlockPos.ZERO.east()));
    }
    @Test void conflictingSourcesNeverChooseAWinnerEvenInReversedOrder() {
        var world = new World();
        world.cells.put(BlockPos.ZERO, CableDiscovery.Kind.ENDPOINT);
        world.cells.put(BlockPos.ZERO.east(), CableDiscovery.Kind.CABLE);
        world.cells.put(BlockPos.ZERO.east(2), CableDiscovery.Kind.ENDPOINT);
        var a = new CableSignalNetwork.Source(BlockPos.ZERO, "p0", Direction.EAST, SimpleIoNetwork.Port.output(15));
        var b = new CableSignalNetwork.Source(BlockPos.ZERO.east(2), "p1", Direction.WEST, SimpleIoNetwork.Port.output(8));
        for (var sources : List.of(List.of(a, b), List.of(b, a))) {
            var frame = CableSignalNetwork.sample(world, sources, new CommunicationNetworkLimits(100, 8));
            assertEquals(0, frame.outputs().get(BlockPos.ZERO.east()));
            assertEquals(Set.of("source_conflict"), Set.copyOf(frame.status().values()));
        }
    }
    @Test void unloadedUnusedFaceDoesNotBreakSelectedFaceButPartialRoutesNeverDrive() {
        var world = new World(); world.cells.put(BlockPos.ZERO.east(), CableDiscovery.Kind.CABLE);
        world.unloaded.add(BlockPos.ZERO.west());
        var source = new CableSignalNetwork.Source(BlockPos.ZERO, "p0", Direction.EAST, SimpleIoNetwork.Port.output(9));
        assertEquals(9, CableSignalNetwork.sample(world, List.of(source), new CommunicationNetworkLimits(100, 8)).outputs().get(BlockPos.ZERO.east()));
        world.unloaded.add(BlockPos.ZERO.east(2));
        var failed = CableSignalNetwork.sample(world, List.of(source), new CommunicationNetworkLimits(100, 8));
        assertTrue(failed.outputs().isEmpty()); assertEquals("topology_unloaded", failed.status().get(source.key()));
    }
    @Test void unavailableInputAndBudgetFailuresAreNotValidZeroSignals() {
        var world = new World(); world.cells.put(BlockPos.ZERO.east(), CableDiscovery.Kind.CABLE);
        for (var port : List.of(SimpleIoNetwork.Port.input(), SimpleIoNetwork.Port.unavailable(SimpleIoNetwork.Availability.UNLOADED))) {
            var source = new CableSignalNetwork.Source(BlockPos.ZERO, "p0", Direction.EAST, port);
            var frame = CableSignalNetwork.sample(world, List.of(source), new CommunicationNetworkLimits(100, 8));
            assertEquals(0, frame.outputs().get(BlockPos.ZERO.east()));
            assertEquals(port.output().isEmpty() && port.availability() == SimpleIoNetwork.Availability.AVAILABLE ? "input" : "unloaded", frame.status().get(source.key()));
            var limited = CableSignalNetwork.sample(world, List.of(source), new CommunicationNetworkLimits(1, 8));
            assertTrue(limited.outputs().isEmpty()); assertEquals("topology_limit", limited.status().get(source.key()));
        }
    }
}
