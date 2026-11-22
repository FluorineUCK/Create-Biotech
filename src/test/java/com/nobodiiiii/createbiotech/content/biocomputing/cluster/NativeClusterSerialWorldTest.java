package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.DataCableBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.junit.jupiter.api.*;

/** Actual controller routing and world schedulers, not a direct host.exchange shortcut.
 * This JVM world fixture is not rendered-client or fresh-process acceptance. */
class NativeClusterSerialWorldTest {
    @BeforeAll static void setup() throws Exception { bootstrap(); }

    @Test void externalTextCrossesExplicitGatewayAndIndependentWorkerThenReturnsAsCorrelatedSerialFrame() throws Exception {
        try (var f = new Rig()) {
            var ordinary = f.ordinary.flowSnapshot().orElseThrow();
            var hardware = f.computers().stream().map(ComputerBehaviour::hardware).toList();
            f.start();
            int ticks = f.untilCallerComplete();
            var locals = f.caller.flowSnapshot().orElseThrow().state().locals();
            var outcome = (DataValue.RecordValue)locals.field("outcome");
            assertEquals(new DataValue.BooleanValue(true), outcome.field("ok"), outcome.toString());
            var reply = (DataValue.RecordValue)outcome.field("v");
            assertEquals(new DataValue.IntegerValue(7), reply.field("v"));
            assertEquals(new DataValue.TextValue("text_length"), reply.field("t"));
            assertInstanceOf(DataValue.TextValue.class, reply.field("r"));
            assertTrue(ticks > 1, "CPU encoding and actual media time are not instantaneous");
            assertEquals("transmitted", f.caller.serialDevice().status("s0").transmit());
            assertEquals("transmitted", f.gateway.serialDevice().status("s0").transmit());
            assertEquals(0, f.caller.serialDevice().status("s0").receive().frames());
            assertTrue(f.worker.serialDevice().attachments().isEmpty(), "Worker cannot be reached by external serial");
            assertFalse(f.world.markers.isEmpty(), "Actual world ticks still publish line feedback");
            assertEquals(ordinary, f.ordinary.flowSnapshot().orElseThrow(), "Unrouted lower-tier member is not borrowed CPU or RAM");
            assertEquals(hardware, f.computers().stream().map(ComputerBehaviour::hardware).toList());
            f.drainClaims();
            assertTrue(f.gateway.flowSnapshot().orElseThrow().receipts().isEmpty());
            assertTrue(f.worker.flowSnapshot().orElseThrow().receipts().isEmpty());
            System.out.println("native external roundtrip ticks=" + ticks + " caller work=" + f.caller.flowSnapshot().orElseThrow().state().work());
        }
    }

    @Test void oldRepliesCannotCompleteANewRequestAndContinuousServicesRetireTheirOwnReceipts() throws Exception {
        try (var f = new Rig()) {
            f.start(); f.untilCallerComplete(); f.drainClaims();
            var previous = f.reply();
            var gatewayId = f.gateway.communicationIdentity(); var workerId = f.worker.communicationIdentity();
            for (String value : List.of("a\uD83D\uDE00b", "next result")) {
                // Delayed old reply really traverses the finite shared cable, not a fabricated RAM value.
                assertEquals("queued", f.gateway.serialDevice().send("s0", previous));
                for (int i = 0; i < 200 && f.caller.serialDevice().status("s0").receive().frames() == 0; i++) f.tick();
                assertEquals(1, f.caller.serialDevice().status("s0").receive().frames());
                var old = f.caller.document().orElseThrow(); var body = old.flow().orElseThrow();
                var inputs = new LinkedHashMap<>(body.initial().fields()); inputs.put("value", new DataValue.TextValue(value));
                var workspace = new ProgramWorkspace(old);
                workspace.setFlow(Optional.of(new FlowDocumentBody(body.inputs(), body.steps(), new DataValue.RecordValue(inputs), body.canvas(), body.inline(), body.execution())));
                f.caller.installDocument(workspace.snapshot(), ComputerRuntime.MemoryPolicy.RESET);
                f.caller.setPaused(false); f.untilCallerComplete(); f.drainClaims();
                var next = f.reply();
                assertNotEquals(previous.field("r"), next.field("r"), "Every explicit caller run owns a fresh correlation");
                assertEquals(new DataValue.IntegerValue(value.codePointCount(0, value.length())), next.field("v"));
                assertEquals(0, f.caller.serialDevice().status("s0").receive().frames());
                assertTrue(f.gateway.flowSnapshot().orElseThrow().receipts().isEmpty());
                assertTrue(f.worker.flowSnapshot().orElseThrow().receipts().isEmpty());
                assertEquals(gatewayId, f.gateway.communicationIdentity()); assertEquals(workerId, f.worker.communicationIdentity());
                previous = next;
            }
        }
    }

    @Test void pausedIncrementalReceiveSurvivesOwnerReconstructionWithoutResendOrRemotePause() throws Exception {
        try (var f = new Rig()) {
            f.start(); boolean captured = false;
            for (int i = 0; i < 6000; i++) {
                f.tick();
                if (f.caller.flowSnapshot().orElseThrow().state().assembly().flatMap(a -> a.serial()).filter(p -> p.receiving()).isEmpty()) continue;
                captured = true; f.caller.setPaused(true);
                var checkpoint = f.caller.flowSnapshot().orElseThrow();
                var workerClock = f.worker.flowSnapshot().orElseThrow().state().work();
                for (int j = 0; j < 30; j++) f.tick();
                assertEquals(checkpoint, f.caller.flowSnapshot().orElseThrow());
                assertTrue(f.worker.flowSnapshot().orElseThrow().state().work() > workerClock, "Pausing caller does not pause the worker");
                var raw = new CompoundTag(); f.caller.write(raw, RegistryAccess.EMPTY, false);
                var serial = f.caller.serialDevice().write(); var hardware = f.caller.hardware();
                f.caller.unload();
                f.caller = addResident(f.world, f.external, "cleric", 5, "caller");
                f.caller.read(raw, RegistryAccess.EMPTY, false); f.caller.initialize(); f.tick();
                assertEquals(checkpoint, f.caller.flowSnapshot().orElseThrow());
                assertEquals(serial, f.caller.serialDevice().write()); assertEquals(hardware, f.caller.hardware());
                f.caller.setPaused(false); break;
            }
            assertTrue(captured, "Must restore a paid receive cursor before result publication");
            f.untilCallerComplete();
            assertEquals(new DataValue.IntegerValue(7), f.reply().field("v"));
            assertEquals(0, f.caller.serialDevice().status("s0").receive().frames());
        }
    }

    @Test void cutCableFailsTheApplicationAndRepairDoesNotSilentlyResend() throws Exception {
        try (var f = new Rig()) {
            f.start(); boolean cut = false;
            for (int i = 0; i < 2000; i++) {
                f.tick();
                if (!f.caller.serialDevice().status("s0").transmit().equals("transmitting")) continue;
                cut = true; f.world.states.remove(CONTROLLER.west()); break;
            }
            assertTrue(cut); f.untilCallerComplete();
            var outcome = (DataValue.RecordValue)f.caller.flowSnapshot().orElseThrow().state().locals().field("outcome");
            assertEquals(new DataValue.BooleanValue(false), outcome.field("ok"));
            assertEquals("interrupted", f.caller.serialDevice().status("s0").transmit());
            var stopped = f.caller.flowSnapshot().orElseThrow();
            f.world.states.put(CONTROLLER.west(), new DataCableBlock(BlockBehaviour.Properties.of()).defaultBlockState());
            for (int i = 0; i < 200; i++) f.tick();
            assertEquals(stopped, f.caller.flowSnapshot().orElseThrow());
            assertEquals(0, f.gateway.serialDevice().status("s0").receive().frames());
            assertEquals(0, f.caller.serialDevice().status("s0").receive().frames());
            assertEquals("interrupted", f.caller.serialDevice().status("s0").transmit());
        }
    }

    @Test void anotherTransmitterCausesAnObservableCollisionNotAnImplicitRetry() throws Exception {
        try (var f = new Rig()) {
            f.start(); boolean overlapped = false;
            for (int i = 0; i < 2000; i++) {
                f.tick();
                if (!f.caller.serialDevice().status("s0").transmit().equals("transmitting")) continue;
                overlapped = true;
                assertEquals("queued", f.gateway.serialDevice().send("s0", NativeLibraryReadDocuments.record("noise", true)));
                break;
            }
            assertTrue(overlapped); f.untilCallerComplete();
            var outcome = (DataValue.RecordValue)f.caller.flowSnapshot().orElseThrow().state().locals().field("outcome");
            assertEquals(new DataValue.BooleanValue(false), outcome.field("ok"));
            assertEquals(new DataValue.TextValue("collision"), ((DataValue.RecordValue)outcome.field("v")).field("failure"));
            var stopped = f.caller.flowSnapshot().orElseThrow();
            for (int i = 0; i < 100; i++) f.tick();
            assertEquals(stopped, f.caller.flowSnapshot().orElseThrow());
            assertEquals("collision", f.caller.serialDevice().status("s0").transmit());
            assertEquals(0, f.caller.serialDevice().status("s0").receive().frames());
        }
    }

    @Test void unmatchedFramesAreIgnoredAsFiniteTransactionsBeforeTheNextValidRequest() throws Exception {
        try (var f = new Rig()) {
            f.worker.setPaused(false); f.gateway.setPaused(false);
            for (var noise : List.of(NativeLibraryReadDocuments.record("r", "old", "t", "text_length", "v", 99),
                    NativeLibraryReadDocuments.record("q", "not-for-you", "t", "another-device", "v", "noise"))) {
                assertEquals("queued", f.caller.serialDevice().send("s0", noise));
                for (int i = 0; i < 400; i++) f.tick();
                f.drainClaims();
                assertEquals(0, f.caller.serialDevice().status("s0").receive().frames(), "No reply for another application");
                assertEquals(0, f.gateway.serialDevice().status("s0").receive().frames());
                assertTrue(f.gateway.flowSnapshot().orElseThrow().receipts().isEmpty());
                assertTrue(f.worker.flowSnapshot().orElseThrow().receipts().isEmpty());
            }
            f.caller.setPaused(false); f.untilCallerComplete();
            assertEquals(new DataValue.IntegerValue(7), f.reply().field("v"));
        }
    }

    static ProgramDocument document(String role, ComputerHostLimits limits) throws Exception {
        var file = Path.of("src/main/resources/assets/create_biotech/biocomputing/examples/cluster_serial_" + role + ".bcp");
        return ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(file).strip()), limits.archive());
    }

    static ComputerBehaviour addResident(World world, BlockPos pos, String profession, int level, String endpoint) throws Exception {
        addComputer(world, pos, null, endpoint);
        var host = (Host)world.entities.get(pos);
        var constructor = ComputerBehaviour.class.getDeclaredConstructor(SmartBlockEntity.class, java.util.function.BooleanSupplier.class, java.util.function.Supplier.class);
        constructor.setAccessible(true);
        var identity = new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:" + profession), level);
        host.computer = (ComputerBehaviour)constructor.newInstance(host, (java.util.function.BooleanSupplier)() -> true,
            (java.util.function.Supplier<HardwareSelection>)() -> CBConfigs.SERVER.biocomputing.select(identity, false));
        host.computer.configureEndpoint(new EndpointConfiguration(endpoint, true));
        return host.computer;
    }

    static final class Rig implements AutoCloseable {
        final World world;
        final Controller controller;
        final Actor player;
        ComputerBehaviour caller, gateway, worker;
        final ComputerBehaviour ordinary;
        final BlockPos external = CONTROLLER.west(2), workerPos = FAR, ordinaryPos = new BlockPos(3, 1, 3);
        Rig() throws Exception {
            var base = fixture(); world = base.world(); controller = base.controller(); player = base.player();
            world.states.clear();
            for (var p : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(4, 3, 4)))
                if (p.getX() == 0 || p.getX() == 4 || p.getY() == 0 || p.getY() == 3 || p.getZ() == 0 || p.getZ() == 4)
                    world.states.put(p.immutable(), WorldEnclosureViewTest.casing.defaultBlockState());
            world.states.put(CONTROLLER, controller.getBlockState());
            gateway = addResident(world, MEMBER, "cleric", 5, "gateway");
            worker = addResident(world, workerPos, "cleric", 5, "worker");
            caller = addResident(world, external, "cleric", 5, "caller");
            ordinary = addResident(world, ordinaryPos, "none", 1, "ordinary");
            for (var item : Map.of("caller", caller, "gateway", gateway, "worker", worker).entrySet()) {
                var owner = item.getValue(); var doc = document(item.getKey(), owner.hardware().limits());
                owner.installDocument(doc, ComputerRuntime.MemoryPolicy.RESET);
                assertTrue(owner.flowSnapshot().orElseThrow().state().paused());
            }
            ordinary.installFlow(new FlowProgram(Map.of(), List.of()).withAssembly(), new DataValue.RecordValue(Map.of()));
            assertEquals(256, gateway.hardware().limits().hardware().programUnits());
            assertEquals(256, worker.hardware().limits().hardware().programUnits());
            assertEquals(32, ordinary.hardware().limits().hardware().programUnits());
            var configuration = new ClusterConfiguration(Map.of(
                "G", new ClusterConfiguration.Member(gateway.communicationIdentity().orElseThrow(), MEMBER),
                "W", new ClusterConfiguration.Member(worker.communicationIdentity().orElseThrow(), workerPos)), Map.of(), Map.of(),
                Map.of("compute", new XBusWire(new XBusWire.Pin("G", "gateway", "x0"), new XBusWire.Pin("W", "worker", "x0"), true)));
            assertEquals("configured", controller.configure(configuration, player));
            assertTrue(world.getBlockState(new BlockPos(2, 1, 2)).isAir(), "Air is valid inside the enclosure");
            world.states.put(CONTROLLER.west(), new DataCableBlock(BlockBehaviour.Properties.of()).defaultBlockState());
            gateway.serialDevice().configureGateway("s0", Optional.of(new SerialGatewayBinding(controller.controllerId(), CONTROLLER, Direction.WEST, "telemetry")));
            gateway.serialDevice().configure("s0", Direction.WEST, true);
            caller.serialDevice().configure("s0", Direction.EAST, true);
            computers().forEach(ComputerBehaviour::initialize);
            tick();
        }
        List<ComputerBehaviour> computers() { return List.of(caller, gateway, worker, ordinary); }
        void start() { worker.setPaused(false); gateway.setPaused(false); caller.setPaused(false); }
        void tick() {
            world.clock++; controller.tick(); computers().forEach(ComputerBehaviour::tick);
            var event = new LevelTickEvent.Post(() -> true, world);
            ComputerExecutionTicks.endTick(event); SerialWorldTicks.endTick(event);
            for (var c : computers()) c.flowSnapshot().ifPresent(s -> {
                assertNotEquals(FlowRuntime.Status.FAILED, s.state().status(), c.blockEntity.getBlockPos() + ": " + s.state().diagnostic());
                for (var t : s.tasks().contexts()) assertNotEquals(FlowRuntime.Status.FAILED, t.status(), t.diagnostic().toString());
            });
        }
        int untilCallerComplete() {
            for (int i = 1; i <= 6000; i++) {
                tick();
                if (caller.flowSnapshot().orElseThrow().state().status() == FlowRuntime.Status.COMPLETE) return i;
            }
            throw new AssertionError("No reply: caller=" + caller.flowSnapshot() + " gateway=" + gateway.flowSnapshot() + " worker=" + worker.flowSnapshot());
        }
        void drainClaims() {
            for (int i = 0; i < 100; i++) {
                tick();
                if (gateway.flowSnapshot().orElseThrow().receipts().isEmpty() && worker.flowSnapshot().orElseThrow().receipts().isEmpty()) return;
            }
            fail("Service receipts did not retire after result claim");
        }
        DataValue.RecordValue reply() {
            var outcome = (DataValue.RecordValue)caller.flowSnapshot().orElseThrow().state().locals().field("outcome");
            assertEquals(new DataValue.BooleanValue(true), outcome.field("ok"), outcome.toString());
            return (DataValue.RecordValue)outcome.field("v");
        }
        @Override public void close() { computers().forEach(ComputerBehaviour::unload); SerialWorldTicks.unload(world); }
    }
}
