package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SerialIndicatorTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() throws Exception { XBusWiringIndicatorTest.bootstrap(); }
    @Test void serialFeedbackStaysOnObservedCableBlocksAndContainsFailuresWithoutRepeatingIntervals() throws Exception {
        var world = XBusWiringIndicatorTest.world(); var feedback = new SerialIndicator();
        var cables = List.of(new BlockPos(1, 0, 0), new BlockPos(1, 0, 1), new BlockPos(1, 1, 1));
        var terminals = List.of(new CableDiscovery.Connection(BlockPos.ZERO, Direction.EAST));
        var active = new SerialNetwork.Activity(cables, terminals, SerialNetwork.Phase.TRANSMITTING);
        feedback.accept(world, List.of(active), 0, 20); int first = world.dots.size(); assertTrue(first > 0);
        assertTrue(world.dots.subList(1, first).stream().allMatch(dot -> dot.particle() == net.minecraft.core.particles.ParticleTypes.END_ROD),
            "Cable paths retain the existing END_ROD style; colors belong to interface status markers");
        var expected = new HashSet<Vec3>(); cables.forEach(p -> expected.add(Vec3.atCenterOf(p).add(.2, .2, .2)));
        expected.add(Vec3.atCenterOf(BlockPos.ZERO).add(.6, .2, 0));
        assertTrue(world.dots.stream().allMatch(dot -> expected.stream().anyMatch(p -> p.distanceTo(dot.position()) < 1e-8)),
            "Never draw a direct shortcut across cable bends, air or unloaded geometry");
        feedback.accept(world, List.of(active), 0, 20); feedback.accept(world, List.of(active), 1, 20);
        assertEquals(first, world.dots.size());
        var completed = new SerialNetwork.Activity(cables, terminals, SerialNetwork.Phase.TRANSMITTED);
        feedback.accept(world, List.of(completed), 2, 20); assertTrue(world.dots.size() > first, "Short emissions do not wait for the periodic refresh");
        assertNotEquals(world.dots.getFirst().particle(), world.dots.get(first).particle());
        world.dots.clear(); feedback.accept(world, List.of(new SerialNetwork.Activity(List.of(), terminals, SerialNetwork.Phase.INTERRUPTED)), 3, 20);
        assertEquals(1, world.dots.size(), "A broken cable is never drawn from stale topology");
        world.particleFailure = true;
        assertDoesNotThrow(() -> feedback.accept(world, List.of(active), 4, 20));
        world.particleFailure = false; world.dots.clear();
        feedback.accept(world, List.of(), 5, 20); assertTrue(world.dots.isEmpty());
        var large = java.util.stream.IntStream.range(0, 200).mapToObj(i -> new BlockPos(i, 2, 0)).toList();
        feedback.accept(world, List.of(new SerialNetwork.Activity(large, List.of(), SerialNetwork.Phase.COLLISION)), 6, 20);
        assertTrue(world.dots.size() <= 33, "Rendering has its own detail cap, not a transport truncation");
    }
}
