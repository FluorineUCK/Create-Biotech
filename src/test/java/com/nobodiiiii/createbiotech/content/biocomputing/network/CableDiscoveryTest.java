package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class CableDiscoveryTest {
    @Test void physicalSegmentsDoNotBridgeThroughTheInspectingComputerAndBranchesShareOneSegment() {
        var world = new World();
        world.cable(1, 0, 0); world.endpoint(2, 0, 0); world.endpoint(1, 1, 0);
        world.cable(-1, 0, 0); world.endpoint(-2, 0, 0);
        var segments = CableDiscovery.segments(world, BlockPos.ZERO, 100, 8);
        assertEquals(2, segments.size(), "A common Computer or endpoint domain is not a physical bridge");
        var right = segments.stream().filter(s -> s.cables().contains(new BlockPos(1, 0, 0))).findFirst().orElseThrow();
        assertEquals(List.of(new BlockPos(1, 0, 0)), right.cables());
        assertEquals(Set.of(new CableDiscovery.Connection(BlockPos.ZERO, Direction.EAST),
            new CableDiscovery.Connection(new BlockPos(2, 0, 0), Direction.WEST),
            new CableDiscovery.Connection(new BlockPos(1, 1, 0), Direction.DOWN)), Set.copyOf(right.endpoints()));
        assertFalse(right.endpoints().stream().anyMatch(c -> c.position().getX() < 0));
    }

    @Test void physicalLoopIsTheSameComponentFromEveryEndpointAndNeverTraversesAComputer() {
        var world = new World(); world.endpoint(0, 0, 0);
        world.cable(1, 0, 0); world.cable(1, 0, 1); world.cable(0, 0, 1);
        world.endpoint(2, 0, 0); world.endpoint(0, 0, 2);
        world.cable(-1, 0, 0); world.endpoint(-2, 0, 0);
        var fromOwner = CableDiscovery.segments(world, BlockPos.ZERO, 100, 8);
        assertEquals(2, fromOwner.size());
        var loop = fromOwner.stream().filter(s -> s.cables().contains(new BlockPos(1, 0, 0))).findFirst().orElseThrow();
        assertEquals(3, loop.cables().size());
        assertEquals(4, loop.endpoints().size(), "The same owner touches the loop on two faces");
        assertEquals(List.of(loop), CableDiscovery.segments(world, new BlockPos(2, 0, 0), 100, 8));
        assertEquals(List.of(loop), CableDiscovery.segments(world, new BlockPos(0, 0, 2), 100, 8));
        assertThrows(UnsupportedOperationException.class, () -> loop.cables().clear());
        assertThrows(UnsupportedOperationException.class, () -> loop.endpoints().clear());
    }

    @Test void physicalSegmentsShareOneBudgetAndNeverPublishAnUnloadedOrPartialComponent() {
        var world = new World(); world.cable(1, 0, 0); world.endpoint(2, 0, 0);
        world.cable(-1, 0, 0); world.endpoint(-2, 0, 0);
        assertEquals("topology_limit", assertThrows(CableDiscovery.DiscoveryException.class,
            () -> CableDiscovery.segments(world, BlockPos.ZERO, 10, 8)).code());
        assertEquals("endpoint_limit", assertThrows(CableDiscovery.DiscoveryException.class,
            () -> CableDiscovery.segments(world, BlockPos.ZERO, 100, 1)).code());
        world.unloaded.add(new BlockPos(1, 1, 0));
        assertEquals("topology_unloaded", assertThrows(CableDiscovery.DiscoveryException.class,
            () -> CableDiscovery.segments(world, BlockPos.ZERO, 100, 8)).code());
        assertEquals(0, world.unloadedReads);
        world.unloaded.clear(); world.rejected.add(new Face(BlockPos.ZERO, Direction.WEST));
        var rightOnly = CableDiscovery.segments(world, BlockPos.ZERO, 100, 8);
        assertEquals(1, rightOnly.size());
        assertEquals(List.of(new BlockPos(1, 0, 0)), rightOnly.getFirst().cables());
    }

    @Test void directPhysicalContactAndDanglingCableAreNotInventedAirChannels() {
        var world = new World();
        assertTrue(CableDiscovery.segments(world, BlockPos.ZERO, 100, 8).isEmpty());
        world.endpoint(1, 0, 0);
        var contact = CableDiscovery.segments(world, BlockPos.ZERO, 100, 8).getFirst();
        assertTrue(contact.cables().isEmpty());
        assertEquals(Set.of(new CableDiscovery.Connection(BlockPos.ZERO, Direction.EAST),
            new CableDiscovery.Connection(new BlockPos(1, 0, 0), Direction.WEST)), Set.copyOf(contact.endpoints()));
        world.rejected.add(new Face(new BlockPos(1, 0, 0), Direction.WEST));
        assertTrue(CableDiscovery.segments(world, BlockPos.ZERO, 100, 8).isEmpty());
        world.cells.remove(new BlockPos(1, 0, 0)); world.cable(1, 0, 0);
        assertEquals(1, CableDiscovery.segments(world, BlockPos.ZERO, 100, 8).getFirst().endpoints().size());
    }

    @Test void endpointPathFollowsCableCornersAndHeightInsteadOfCuttingAcrossAir() {
        var world = new World();
        var target = new BlockPos(2, 1, 2);
        var path = List.of(BlockPos.ZERO, new BlockPos(1, 0, 0), new BlockPos(1, 0, 1),
            new BlockPos(1, 1, 1), new BlockPos(1, 1, 2), target);
        for (var pos : path.subList(1, path.size() - 1)) world.cable(pos.getX(), pos.getY(), pos.getZ());
        world.endpoint(target.getX(), target.getY(), target.getZ());
        assertEquals(path, CableDiscovery.findEndpointPath(world, BlockPos.ZERO, target, 100, 8));
        world.cells.remove(new BlockPos(1, 1, 1));
        assertTrue(CableDiscovery.findEndpointPath(world, BlockPos.ZERO, target, 100, 8).isEmpty());
    }
    @Test void cosmeticPathRetainsFaceLimitsLoadedOnlyAndOpaqueEndpointRules() {
        var world = new World(); world.cable(1, 0, 0); world.endpoint(2, 0, 0);
        var target = new BlockPos(2, 0, 0);
        world.rejected.add(new Face(target, Direction.WEST));
        assertTrue(CableDiscovery.findEndpointPath(world, BlockPos.ZERO, target, 100, 8).isEmpty());
        world.rejected.clear(); world.rejected.add(new Face(BlockPos.ZERO, Direction.EAST));
        assertTrue(CableDiscovery.findEndpointPath(world, BlockPos.ZERO, target, 100, 8).isEmpty());
        world.rejected.clear(); world.unloaded.add(new BlockPos(1, 1, 0));
        assertEquals("topology_unloaded", assertThrows(CableDiscovery.DiscoveryException.class,
            () -> CableDiscovery.findEndpointPath(world, BlockPos.ZERO, target, 100, 8)).code());
        assertEquals(0, world.unloadedReads);
        world.unloaded.clear();
        assertEquals("topology_limit", assertThrows(CableDiscovery.DiscoveryException.class,
            () -> CableDiscovery.findEndpointPath(world, BlockPos.ZERO, target, 1, 8)).code());
        world.endpoint(-1, 0, 0);
        assertEquals("endpoint_limit", assertThrows(CableDiscovery.DiscoveryException.class,
            () -> CableDiscovery.findEndpointPath(world, BlockPos.ZERO, target, 100, 1)).code());
        world.cable(3, 0, 0); world.endpoint(4, 0, 0);
        assertTrue(CableDiscovery.findEndpointPath(world, BlockPos.ZERO, new BlockPos(4, 0, 0), 100, 8).isEmpty());
    }
	@Test void endpointsUseOppositeApproachFaceAndSourceFaceFilters() {
		var world=new World(); world.cable(1,0,0); world.endpoint(2,0,0);
		assertEquals(List.of(new BlockPos(2,0,0)), CableDiscovery.scanEndpoints(world,BlockPos.ZERO,100,8));
		world.rejected.add(new Face(new BlockPos(2,0,0),Direction.WEST));
		assertEquals(List.of(),CableDiscovery.scanEndpoints(world,BlockPos.ZERO,100,8));
		world.rejected.clear(); world.rejected.add(new Face(BlockPos.ZERO,Direction.EAST));
		assertEquals(List.of(),CableDiscovery.scanEndpoints(world,BlockPos.ZERO,100,8));
	}
	@Test void endpointAndLegacyComputerAreOpaqueAndEndpointLimitCountsPhysicalResults() {
		var world=new World();world.endpoint(1,0,0);world.cable(2,0,0);world.endpoint(3,0,0);
		assertEquals(List.of(new BlockPos(1,0,0)),CableDiscovery.scanEndpoints(world,BlockPos.ZERO,100,8));
		world.endpoint(-1,0,0);
		assertEquals("endpoint_limit",assertThrows(CableDiscovery.DiscoveryException.class,()->CableDiscovery.scanEndpoints(world,BlockPos.ZERO,100,1)).code());
		world.cells.put(new BlockPos(0,1,0),new CableDiscovery.Cell(CableDiscovery.Kind.COMPUTER,null));
		assertEquals(2,CableDiscovery.scanEndpoints(world,BlockPos.ZERO,100,8).size());
	}
	@Test void endpointScanIsDeterministicLoopSafeLoadedOnlyAndEmptyIsValid() {
		var world=new World(); assertEquals(List.of(),CableDiscovery.scanEndpoints(world,BlockPos.ZERO,100,8));
		world.cable(1,0,0);world.cable(1,1,0);world.cable(0,1,0);world.endpoint(2,0,0);world.endpoint(0,2,0);
		assertEquals(List.of(new BlockPos(0,2,0),new BlockPos(2,0,0)),CableDiscovery.scanEndpoints(world,BlockPos.ZERO,100,8));
		world.unloaded.add(new BlockPos(1,0,1));
		assertEquals("topology_unloaded",assertThrows(CableDiscovery.DiscoveryException.class,()->CableDiscovery.scanEndpoints(world,BlockPos.ZERO,100,8)).code());
		assertEquals(0,world.unloadedReads);
	}
	@Test void disconnectAndReconnectChangesCompleteEndpointSnapshot() {
		var world=new World();world.cable(1,0,0);world.endpoint(2,0,0);
		assertEquals(1,CableDiscovery.scanEndpoints(world,BlockPos.ZERO,100,8).size());
		world.cells.remove(new BlockPos(1,0,0));assertTrue(CableDiscovery.scanEndpoints(world,BlockPos.ZERO,100,8).isEmpty());
		world.cable(1,0,0);assertEquals(1,CableDiscovery.scanEndpoints(world,BlockPos.ZERO,100,8).size());
	}
    @Test void shutCableContactBlocksDiscoveryFromEitherDirection(){
        var world=new World();
        var left=BlockPos.ZERO;var right=left.east(3);
        world.endpoint(0,0,0);world.endpoint(3,0,0);
        int west=1<<Direction.WEST.ordinal(),east=1<<Direction.EAST.ordinal();
        world.cells.put(left.east(),new CableDiscovery.Cell(CableDiscovery.Kind.CABLE,null,east|west));
        world.cells.put(left.east(2),new CableDiscovery.Cell(CableDiscovery.Kind.CABLE,null,east));
        assertTrue(CableDiscovery.scanEndpoints(world,left,128,8).isEmpty());
        assertTrue(CableDiscovery.findEndpointPath(world,right,left,128,8).isEmpty());
        assertEquals(List.of(left.east()),CableDiscovery.segments(world,left,128,8).getFirst().cables());
        world.cells.put(left.east(2),new CableDiscovery.Cell(CableDiscovery.Kind.CABLE,null,east|west));
        assertEquals(List.of(right),CableDiscovery.scanEndpoints(world,left,128,8));
    }
    @Test void readerMustBeReachedFromBackAndComputersNeverBridgeIndependentSegments() {
        var world=new World();
        world.cable(1,0,0);world.reader(2,0,0,Direction.EAST);
        world.reader(1,1,0,Direction.DOWN); // Reached from below, not its back.
        world.cells.put(new BlockPos(-1,0,0),new CableDiscovery.Cell(CableDiscovery.Kind.COMPUTER,null));
        world.cable(-2,0,0);world.reader(-3,0,0,Direction.WEST);
        var result=CableDiscovery.scan(world,BlockPos.ZERO,100,8);
        assertEquals(List.of(new BlockPos(2,0,0)),result);
    }
    @Test void wrongSideFirstDoesNotHideALaterValidReaderConnection() {
        var world=new World();world.reader(1,0,0,Direction.NORTH);
        world.cable(0,0,1);world.cable(1,0,1);
        assertEquals(List.of(new BlockPos(1,0,0)),CableDiscovery.scan(world,BlockPos.ZERO,100,8));
    }
    @Test void unloadedBoundaryAndBudgetOverflowRejectWholeSnapshotInsteadOfPartialReaders() {
        var world=new World();world.cable(1,0,0);world.reader(2,0,0,Direction.EAST);
        world.unloaded.add(new BlockPos(1,1,0));
        var unloaded=assertThrows(CableDiscovery.DiscoveryException.class,()->CableDiscovery.scan(world,BlockPos.ZERO,100,8));
        assertEquals("topology_unloaded",unloaded.code());assertEquals(0,world.unloadedReads);
        world.unloaded.clear();
        assertEquals("topology_limit",assertThrows(CableDiscovery.DiscoveryException.class,()->CableDiscovery.scan(world,BlockPos.ZERO,1,8)).code());
        world.reader(-1,0,0,Direction.WEST);
        assertEquals("reader_limit",assertThrows(CableDiscovery.DiscoveryException.class,()->CableDiscovery.scan(world,BlockPos.ZERO,100,1)).code());
    }
    @Test void removingCableMakesDisconnectedReaderDisappearAndEmptyNetworkIsExplicit() {
        var world=new World();world.cable(1,0,0);world.reader(2,0,0,Direction.EAST);
        assertEquals(1,CableDiscovery.scan(world,BlockPos.ZERO,100,8).size());
        world.cells.remove(new BlockPos(1,0,0));
        assertEquals("no_readers",assertThrows(CableDiscovery.DiscoveryException.class,()->CableDiscovery.scan(world,BlockPos.ZERO,100,8)).code());
    }
	private static class World implements CableDiscovery.World {
		final Map<BlockPos,CableDiscovery.Cell> cells=new HashMap<>();final Set<BlockPos> unloaded=new HashSet<>();final Set<Face> rejected=new HashSet<>();int unloadedReads;
		void cable(int x,int y,int z){cells.put(new BlockPos(x,y,z),new CableDiscovery.Cell(CableDiscovery.Kind.CABLE,null));}
		void reader(int x,int y,int z,Direction front){cells.put(new BlockPos(x,y,z),new CableDiscovery.Cell(CableDiscovery.Kind.READER,front));}
		void endpoint(int x,int y,int z){cells.put(new BlockPos(x,y,z),new CableDiscovery.Cell(CableDiscovery.Kind.ENDPOINT,null));}
		public boolean loaded(BlockPos pos){return !unloaded.contains(pos);}
		public CableDiscovery.Cell cell(BlockPos pos){if(unloaded.contains(pos)){unloadedReads++;throw new AssertionError("forced unloaded read");}return cells.getOrDefault(pos,new CableDiscovery.Cell(CableDiscovery.Kind.EMPTY,null));}
		public boolean acceptsCable(BlockPos pos,Direction side){return !rejected.contains(new Face(pos,side));}
	}
	private record Face(BlockPos pos,Direction side){}
}
