package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;

class ClusterWorldIndicatorTest {
    @BeforeAll static void setup() throws Exception { bootstrap(); }

    @Test void whiteReadWaitRendersBeforeTheTransparentShellRatherThanBehindItsDepth() throws Exception {
        // Rendering policy is independent of the particle's random age, level or GL context.
        var particle = allocate(com.nobodiiiii.createbiotech.content.biocomputing.client.ClusterReadWaitParticle.class);
        assertSame(net.minecraft.client.particle.ParticleRenderType.PARTICLE_SHEET_OPAQUE, particle.getRenderType());
    }

    @Test void writerWaitMarkersAppearBesideTheResidentTowardThePeerWithoutClaimingDelivery() throws Exception {
        var f = writers(); var world = f.fixture().world();
        world.clock = com.nobodiiiii.createbiotech.registry.CBConfigs.SERVER.biocomputing.xbusIndicatorInterval(false);
        finishFlowTick(f);
        f.fixture().controller().tick();
        assertEquals(FlowRuntime.Status.WAITING_WRITE, f.fixture().computer().flowSnapshot().orElseThrow().state().status());
        assertEquals(FlowRuntime.Status.WAITING_WRITE, f.caller().flowSnapshot().orElseThrow().state().status());
        assertEquals(0, world.particles, "Waiting does not emit a successful directed transfer");
        assertEquals(2, world.markers.size());
        assertTrue(world.markers.stream().allMatch(m -> m.type() == ParticleTypes.SMALL_FLAME));
        assertEquals(Set.of(new Vec3(1.5, 2.1, 2.1), new Vec3(1.5, 2.1, 2.9)),
            new HashSet<>(world.markers.stream().map(MarkerParticle::point).toList()));
    }

    static InternalFixture writers() throws Exception {
        var f = internal(false); var a = f.fixture().computer(); var b = f.caller();
        f.fixture().world().markers = new ArrayList<>();
        var packet = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
        var program = new FlowProgram(Map.of("packet", packet), List.of(new FlowProgram.Write("send", "x0", "packet")));
        var inputs = new DataValue.RecordValue(Map.of("packet", new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12)))));
        a.installFlow(program, inputs); b.installFlow(program, inputs);
        var config = new ClusterConfiguration(Map.of("A", new ClusterConfiguration.Member(a.communicationIdentity().orElseThrow(), MEMBER),
            "B", new ClusterConfiguration.Member(b.communicationIdentity().orElseThrow(), FAR)), Map.of(), Map.of(),
            Map.of("data", new XBusWire(new XBusWire.Pin("A", "worker", "x0"), new XBusWire.Pin("B", "caller", "x0"), true)));
        assertEquals("configured", f.fixture().controller().configure(config, f.fixture().player()));
        a.setPaused(false); b.setPaused(false); return f;
    }

    @Test void repeatedControllerTicksCannotDuplicateAWaitingDisplayInterval() throws Exception {
        var f = writers(); var world = f.fixture().world(); var controller = f.fixture().controller();
        int interval = com.nobodiiiii.createbiotech.registry.CBConfigs.SERVER.biocomputing.xbusIndicatorInterval(false);
        world.clock = interval; finishFlowTick(f); controller.tick(); var saved = f.fixture().computer().flowSnapshot();
        controller.tick(); controller.tick(); assertEquals(2, world.markers.size());
        world.clock++; controller.tick(); assertEquals(2, world.markers.size());
        world.clock = 2L * interval; controller.tick(); assertEquals(4, world.markers.size());
        assertEquals(saved, f.fixture().computer().flowSnapshot(), "Drawing cannot advance the blocked writer");
    }

    @Test void actualReadWaitsUseTheDedicatedWhiteMarkerAndNeverTransfer() throws Exception {
        var f = writers(); var world = f.fixture().world();
        for (var computer : List.of(f.fixture().computer(), f.caller())) {
            computer.installFlow(reader(), new DataValue.RecordValue(Map.of())); computer.setPaused(false);
        }
        world.clock = 0; finishFlowTick(f); f.fixture().controller().tick();
        assertEquals(2, world.markers.size()); assertEquals(0, world.particles);
        assertTrue(world.markers.stream().allMatch(m -> m.type() == com.nobodiiiii.createbiotech.registry.CBParticleTypes.BIOCOMPUTING_READ_WAIT.get()));
        assertEquals(FlowRuntime.Status.WAITING_READ, f.fixture().computer().flowSnapshot().orElseThrow().state().status());
    }

    @Test void disabledLineClosedGrantAndBrokenShellCannotShowAWorkingConnection() throws Exception {
        for (String fault : List.of("line", "grant", "shell")) {
            var f = writers(); var world = f.fixture().world(); var controller = f.fixture().controller();
            if (fault.equals("line")) {
                var c = controller.routingState().orElseThrow().configuration(); var w = c.wires().get("data");
                assertEquals("configured", controller.configure(new ClusterConfiguration(c.members(), c.ports(), c.connections(),
                    Map.of("data", new XBusWire(w.left(), w.right(), false))), f.fixture().player()));
            } else if (fault.equals("grant")) f.caller().disableEndpoint();
            else world.states.remove(net.minecraft.core.BlockPos.ZERO);
            world.clock = 0; controller.tick();
            assertTrue(world.markers.isEmpty(), fault); assertEquals(0, world.particles, fault);
        }
    }

    @Test void successfulHandoffKeepsWriterToReaderDirectionAndCosmeticFailureCannotUndoIt() throws Exception {
        for (boolean brokenParticles : List.of(false, true)) {
            var f = writers(); var world = f.fixture().world();
            f.caller().installFlow(reader(), new DataValue.RecordValue(Map.of())); f.caller().setPaused(false);
            world.particleFailure = brokenParticles; world.clock = 0; finishFlowTick(f); f.fixture().controller().tick();
            assertEquals(FlowRuntime.Status.COMPLETE, f.fixture().computer().flowSnapshot().orElseThrow().state().status());
            assertEquals(FlowRuntime.Status.COMPLETE, f.caller().flowSnapshot().orElseThrow().state().status());
            assertEquals(new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12))),
                f.caller().flowSnapshot().orElseThrow().state().locals().field("received"));
            if (!brokenParticles) {
                assertEquals(1, world.particles); assertEquals(MEMBER, world.particleFrom); assertEquals(FAR, world.particleTo);
                assertSame(com.nobodiiiii.createbiotech.registry.CBParticleTypes.STRAIGHT_ENCHANT.get(), world.particleType);
            }
            assertTrue(world.markers.isEmpty());
        }
    }

    @Test void adjacentAndVerticalMarkersStayLocalAndFinite() {
        var zero = net.minecraft.core.BlockPos.ZERO;
        assertEquals(new Vec3(1, 1.1, .5), ClusterIndicatorGeometry.waitingPoint(zero, zero.east()));
        assertEquals(new Vec3(.9, 1.1, .9), ClusterIndicatorGeometry.waitingPoint(zero, zero.above(2)));
    }

    @Test void signalContinuityIsPeriodicOncePerTickAndClosedGrantsSuppressIt() throws Exception {
        var f = ClusterSignalRuntimeTest.configured(ClusterSignalRuntimeTest.sleeper(), ClusterSignalRuntimeTest.sleepInput(20),
            ClusterSignalRuntimeTest.sleeper(), ClusterSignalRuntimeTest.sleepInput(70));
        var world = f.fixture().world(); var controller = f.fixture().controller();
        var before = f.fixture().computer().flowSnapshot();
        int interval = com.nobodiiiii.createbiotech.registry.CBConfigs.SERVER.biocomputing.xbusIndicatorInterval(false);
        world.clock = 0; controller.tick(); controller.tick(); assertEquals(1, world.particles);
        world.clock = 1; controller.tick(); assertEquals(1, world.particles);
        world.clock = interval; controller.tick(); assertEquals(2, world.particles);
        assertEquals(before, f.fixture().computer().flowSnapshot(), "Continuity does not execute a paused Computer");
        f.caller().disableEndpoint(); world.clock = 2L * interval; controller.tick(); assertEquals(2, world.particles);
        assertEquals("signal_closed", controller.wireDiagnostics().get("voltage"));
    }

    static FlowProgram reader() { return new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x0", "received",
        new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER))))); }
}
