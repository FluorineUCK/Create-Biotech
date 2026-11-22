package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class ClusterXBusObservationTest {
    @BeforeAll static void setup() throws Exception { bootstrap(); }

    @Test void controllerFeedbackCapturesEachConnectedComponentOnceWithoutAdvancingIt() throws Exception {
        var f=ClusterWorldIndicatorTest.writers(); var world=f.fixture().world(); var controller=f.fixture().controller();
        var initial=controller.routingState().orElseThrow().configuration();
        assertEquals("configured",controller.configure(new ClusterConfiguration(initial.members(),initial.ports(),initial.connections(),
            Map.of("data",initial.wires().get("data"),"branch",new XBusWire(
                new XBusWire.Pin("A","worker","x0"),new XBusWire.Pin("B","caller","x1"),true))),f.fixture().player()));
        world.clock=1; finishFlowTick(f);
        world.blockStateReads=0; controller.enclosure(); long observation=world.blockStateReads;
        var left=f.fixture().computer().flowSnapshot(); var right=f.caller().flowSnapshot();
        var history=XBusHistorySavedData.get(world).save(new CompoundTag(),null);
        world.blockStateReads=0; controller.tick();
        System.out.println("Controller feedback window="+observation+" reads="+world.blockStateReads);
        assertTrue(world.blockStateReads<5*observation,"Reusing a component needs a fresh proof, but its first display already has one");
        assertEquals(Map.of("data","xbus_write_write","branch","xbus_write_idle"),controller.wireDiagnostics());
        var corner=world.states.remove(BlockPos.ZERO); controller.tick();
        assertTrue(controller.wireDiagnostics().values().stream().noneMatch(code->code.startsWith("xbus_write")));
        world.states.put(BlockPos.ZERO,corner); controller.tick();
        assertEquals(Map.of("data","xbus_write_write","branch","xbus_write_idle"),controller.wireDiagnostics());
        assertEquals(left,f.fixture().computer().flowSnapshot()); assertEquals(right,f.caller().flowSnapshot());
        assertEquals(history,XBusHistorySavedData.get(world).save(new CompoundTag(),null));
    }

    @Test void connectedCaptureDoesNotMultiplyWholeWindowObservationsByInternalWireCount() throws Exception {
        var f = ClusterWorldIndicatorTest.writers();
        var world = f.fixture().world(); var controller = f.fixture().controller();
        var initial = controller.routingState().orElseThrow().configuration();
        assertEquals("configured",controller.configure(new ClusterConfiguration(initial.members(),initial.ports(),initial.connections(),
            Map.of("data",initial.wires().get("data"),"branch",new XBusWire(
                new XBusWire.Pin("A","worker","x0"),new XBusWire.Pin("B","caller","x1"),true))),f.fixture().player()));
        world.blockStateReads=0;
        assertEquals(EnclosureScanner.Status.READY,controller.enclosure().status());
        long observation=world.blockStateReads;
        var left=f.fixture().computer().flowSnapshot(); var right=f.caller().flowSnapshot();
        var history=XBusHistorySavedData.get(world).save(new CompoundTag(),null);
        for (int attempt=0;attempt<2;attempt++) {
            world.blockStateReads=0;
            var capture=XBusConnectedNetwork.capture(world,f.fixture().computer(),"x0");
            assertEquals("ready",capture.code());
            assertEquals(2,capture.connected().orElseThrow().displayWires().size());
            System.out.println("Connected capture window="+observation+" reads="+world.blockStateReads);
            assertTrue(world.blockStateReads>=2*observation,"Both discovery and the final post-callback proof must observe the world");
            assertTrue(world.blockStateReads<3*observation,"An internal group must not repeat the whole-component terminal proof");
        }
        var corner=world.states.remove(BlockPos.ZERO);
        assertTrue(XBusConnectedNetwork.capture(world,f.fixture().computer(),"x0").connected().isEmpty());
        world.states.put(BlockPos.ZERO,corner);
        assertEquals("ready",XBusConnectedNetwork.capture(world,f.fixture().computer(),"x0").code(),"No cached failure across same-tick repair");
        assertEquals(left,f.fixture().computer().flowSnapshot()); assertEquals(right,f.caller().flowSnapshot());
        assertEquals(history,XBusHistorySavedData.get(world).save(new CompoundTag(),null));
    }

    @Test void separatelyCapturedWiresShareOnlyTheirIdenticalTerminalGeometryWithinEachBarrier() throws Exception {
        var f = ClusterWorldIndicatorTest.writers();
        var world = f.fixture().world(); var controller = f.fixture().controller();
        var initial = controller.routingState().orElseThrow().configuration();
        assertEquals("configured", controller.configure(new ClusterConfiguration(initial.members(), initial.ports(), initial.connections(),
            Map.of("data", initial.wires().get("data"), "branch", new XBusWire(
                new XBusWire.Pin("A", "worker", "x0"), new XBusWire.Pin("B", "caller", "x1"), true))), f.fixture().player()));
        var data = controller.captureXBusWire("data").binding().orElseThrow();
        var branch = controller.captureXBusWire("branch").binding().orElseThrow();
        world.blockStateReads = 0;
        assertEquals(EnclosureScanner.Status.READY, controller.enclosure().status());
        long observation = world.blockStateReads;
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var accesses = new ArrayList<>(data.accesses()); accesses.addAll(branch.accesses());
        var network = XBusAccess.network(accesses.stream().map(a -> a.guarded(() -> {
            calls.incrementAndGet(); return Optional.empty();
        })).toList());
        var left = f.fixture().computer().flowSnapshot(); var right = f.caller().flowSnapshot();
        var history = XBusHistorySavedData.get(world).save(new CompoundTag(), null);
        for (int attempt = 0; attempt < 2; attempt++) {
            world.blockStateReads = 0; calls.set(0);
            assertTrue(network.inspectBindings().isEmpty());
            assertEquals(accesses.size(), calls.get());
            assertTrue(world.blockStateReads >= observation, "Every new barrier must re-read the complete window");
            assertTrue(world.blockStateReads < 2 * observation,
                "Independent wire captures multiplied the same geometry: " + world.blockStateReads + " vs " + observation);
        }
        var corner = world.states.remove(BlockPos.ZERO);
        assertEquals(Optional.of(XBusExchange.Status.UNAVAILABLE), network.inspectBindings());
        world.states.put(BlockPos.ZERO, corner);
        assertTrue(network.inspectBindings().isEmpty());
        assertEquals(left, f.fixture().computer().flowSnapshot()); assertEquals(right, f.caller().flowSnapshot());
        assertEquals(history, XBusHistorySavedData.get(world).save(new CompoundTag(), null));
    }

    @Test void oneValidationFreshlyObservesSharedGeometryOnceRatherThanOncePerPinOrAlias() throws Exception {
        var f = ClusterWorldIndicatorTest.writers();
        var world = f.fixture().world(); var controller = f.fixture().controller();
        world.blockStateReads = 0;
        assertEquals(EnclosureScanner.Status.READY, controller.enclosure().status());
        long observation = world.blockStateReads;
        assertTrue(observation > 0);
        var binding = controller.captureXBusWire("data").binding().orElseThrow();
        // Aliases must still validate each route, but cannot multiply the identical terminal geometry proof.
        var accesses = new ArrayList<>(binding.accesses()); accesses.addAll(binding.accesses());
        var routeCalls = new java.util.concurrent.atomic.AtomicInteger();
        var network = XBusAccess.network(accesses.stream().map(access -> access.guarded(() -> {
            routeCalls.incrementAndGet(); return Optional.empty();
        })).toList());
        var left = f.fixture().computer().flowSnapshot(); var right = f.caller().flowSnapshot();
        var history = XBusHistorySavedData.get(world).save(new CompoundTag(), null);
        for (int attempt = 0; attempt < 2; attempt++) {
            world.blockStateReads = 0;
            routeCalls.set(0);
            assertTrue(network.inspectBindings().isEmpty());
            assertEquals(accesses.size(), routeCalls.get(), "Distinct routes must not be deduplicated with geometry");
            long reads = world.blockStateReads;
            System.out.println("XBus geometry observation=" + observation + " validation=" + reads);
            assertTrue(reads >= observation, "Each invocation must freshly observe the whole bounded window");
            assertTrue(reads < 2 * observation, "One callback-free validation barrier must not repeat full topology scans: " + reads);
        }
        assertEquals(left, f.fixture().computer().flowSnapshot()); assertEquals(right, f.caller().flowSnapshot());
        assertEquals(history, XBusHistorySavedData.get(world).save(new CompoundTag(), null));
    }

    @Test void capturedWireSeesSameTickBreakAndRepairWithoutPublishingOrRetainingAPermission() throws Exception {
        var f = ClusterWorldIndicatorTest.writers(); var world = f.fixture().world();
        var binding = f.fixture().controller().captureXBusWire("data").binding().orElseThrow();
        var network = XBusAccess.network(binding.accesses());
        var left = f.fixture().computer().flowSnapshot(); var right = f.caller().flowSnapshot();
        var history = XBusHistorySavedData.get(world).save(new CompoundTag(), null);
        assertTrue(network.inspectBindings().isEmpty());
        var corner = world.states.remove(BlockPos.ZERO);
        assertEquals(Optional.of(XBusExchange.Status.UNAVAILABLE), network.inspectBindings());
        world.states.put(BlockPos.ZERO, corner);
        assertTrue(network.inspectBindings().isEmpty(), "Same tick repair must be freshly observed, not cached failure");
        assertEquals(left, f.fixture().computer().flowSnapshot()); assertEquals(right, f.caller().flowSnapshot());
        assertEquals(history, XBusHistorySavedData.get(world).save(new CompoundTag(), null));
    }

    @Test void distinctReadyObservationsNeverShareADecisionOrReplaceEachOthersCapturedEvidence() throws Exception {
        var f = ClusterWorldIndicatorTest.writers(); var world = f.fixture().world();
        var controller = f.fixture().controller();
        var old = controller.captureXBusWire("data").binding().orElseThrow();
        var changed = new BlockPos(1, 1, 2); var prior = world.states.get(changed);
        world.states.put(changed, Blocks.STONE.defaultBlockState());
        assertEquals(EnclosureScanner.Status.READY, controller.enclosure().status());
        var fresh = controller.captureXBusWire("data").binding().orElseThrow();
        var left = f.fixture().computer().flowSnapshot(); var right = f.caller().flowSnapshot();
        var history = XBusHistorySavedData.get(world).save(new CompoundTag(), null);
        for (boolean restored : List.of(false, true)) {
            if (restored) { if (prior == null) world.states.remove(changed); else world.states.put(changed, prior); }
            assertTrue(XBusAccess.network((restored ? old : fresh).accesses()).inspectBindings().isEmpty());
            for (boolean reverse : List.of(false, true)) {
                var mixed = new ArrayList<>((reverse ? fresh : old).accesses());
                mixed.addAll((reverse ? old : fresh).accesses());
                assertEquals(Optional.of(XBusExchange.Status.UNAVAILABLE), XBusAccess.network(mixed).inspectBindings());
            }
        }
        assertEquals(left, f.fixture().computer().flowSnapshot()); assertEquals(right, f.caller().flowSnapshot());
        assertEquals(history, XBusHistorySavedData.get(world).save(new CompoundTag(), null));
    }

    @Test void lateCallbackCannotUnloadGeometryOrReplaceCapturedEvidenceWithANewReadyScan() throws Exception {
        for (boolean direct : List.of(false, true)) for (boolean rescan : List.of(false, true)) {
            var f = ClusterWorldIndicatorTest.writers(); var world = f.fixture().world();
            var controller = f.fixture().controller();
            f.caller().installFlow(ClusterWorldIndicatorTest.reader(), new DataValue.RecordValue(Map.of()));
            f.fixture().computer().step(); f.caller().step(); f.caller().setPaused(false);
            var binding = controller.captureXBusWire("data").binding().orElseThrow();
            var left = f.fixture().computer().flowSnapshot(); var right = f.caller().flowSnapshot();
            var history = XBusHistorySavedData.get(world).save(new CompoundTag(), null);
            var late = binding.right().access().withPhysicalRoute(() -> {
                if (rescan) {
                    world.states.put(new BlockPos(1, 1, 2), Blocks.STONE.defaultBlockState());
                    assertEquals(EnclosureScanner.Status.READY, controller.enclosure().status());
                } else world.unloaded = BlockPos.ZERO;
                return Optional.empty();
            }, Optional::empty);
            var result = direct ? binding.left().access().exchange(late)
                : XBusAccess.network(List.of(binding.left().access(), late)).exchange(binding.left().pin(), binding.right().pin());
            assertEquals(XBusExchange.Status.UNAVAILABLE, result, "direct=" + direct + ", rescan=" + rescan);
            assertEquals(left, f.fixture().computer().flowSnapshot()); assertEquals(right, f.caller().flowSnapshot());
            assertEquals(history, XBusHistorySavedData.get(world).save(new CompoundTag(), null));
            world.unloaded = null;
            assertEquals("ready", controller.captureXBusWire("data").code(), "Only a fresh capture may adopt the changed ready geometry");
        }
    }
}
