package com.nobodiiiii.createbiotech.content.biocomputing.cluster;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import net.minecraft.core.BlockPos;

class ClusterXBusBroadcastTest {
    static final ModuleDefinition.Key RECEIVE = new ModuleDefinition.Key("receive", 1);
    static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
    static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());
    @BeforeAll static void setup() throws Exception { bootstrap(); }
    static FlowProgram reader() {
        return new FlowProgram(Map.of(), List.of(new FlowProgram.Read("main_read", "x0", "received", PACKET),
            new FlowProgram.Procedure("receiver", RECEIVE, Map.of(), Map.of("packet", PACKET), Map.of("packet", "received")),
            new FlowProgram.Read("child_read", "x0", "received", PACKET), new FlowProgram.EndProcedure("return")));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, 1, 2, -1})
    void sharedReadableProbesWakeTogetherWithoutConsumingTheSenderOrRunningTheirSuccessors(long seed) throws Exception {
        var f = three(); var a = f.computers().getFirst(); var b = f.computers().get(1); var c = f.third();
        var hardware = b.getClass().getDeclaredField("hardware"); hardware.setAccessible(true);
        var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), 3));
        hardware.set(b, (java.util.function.Supplier<HardwareSelection>)() -> new HardwareSelection(HardwareSelection.Status.READY, Optional.empty(), "test", limits));
        b.reset(); c.reset();
        b.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("main_probe", "x0"),
            new FlowProgram.XBusStatus("main_after", "x0", "status"),
            new FlowProgram.Procedure("receiver", RECEIVE, Map.of(), Map.of(), Map.of()),
            new FlowProgram.AwaitReadable("child_probe", "x0"), new FlowProgram.EndProcedure("return"))), EMPTY);
        c.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("probe", "x0"),
            new FlowProgram.XBusStatus("after", "x0", "status"))), EMPTY);
        b.setPaused(false); c.setPaused(false); b.step(); c.step(); var child = b.startFlowTask(RECEIVE, EMPTY); b.step();
        var sender = a.flowSnapshot();
        var cycle = new ComputerExecutionCycle(f.world(), f.computers(), f.world().clock);
        var result = cycle.advanceRound(seed);
        assertEquals(1, result.wakes(), result.diagnostics().toString()); assertEquals(0, result.exchanges());
        assertEquals(0, result.work(), "Completing an already charged wait grants no extra instruction work");
        assertEquals(1, b.flowSnapshot().orElseThrow().state().cursor());
        assertEquals(4, b.flowTask(child).orElseThrow().cursor());
        assertEquals(1, c.flowSnapshot().orElseThrow().state().cursor(), "A woken peer must not run its successor in the same round");
        assertEquals(sender, a.flowSnapshot()); assertEquals(0, f.world().particles, "Readiness is not a data transfer");
        for (var host : f.computers()) assertTrue(host.flowSnapshot().orElseThrow().receipts().isEmpty());
        assertEquals(15, b.sampleFlowSignals().get("x0"), "Probes leave the same sender occupying the line");
    }
    @Test void physicalClusterWireBroadcastsToMainAndChildAndSavesTheEntireCohort() throws Exception {
        var f = ClusterWorldIndicatorTest.writers(); var a = f.fixture().computer(); var b = f.caller();
        var hardware = b.getClass().getDeclaredField("hardware"); hardware.setAccessible(true);
        var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), 3));
        hardware.set(b, (java.util.function.Supplier<HardwareSelection>)() -> new HardwareSelection(HardwareSelection.Status.READY, Optional.empty(), "test", limits));
        b.installFlow(reader(), EMPTY); b.setPaused(false); b.step();
        var child = b.startFlowTask(RECEIVE, EMPTY); b.step(); a.step();
        assertEquals(2, b.flowSnapshot().orElseThrow().pinClaims().entries().size());
        var result = new ComputerExecutionCycle(f.fixture().world(), List.of(a, b), f.fixture().world().clock).advanceRound(0);
        assertEquals(1, result.exchanges(), result.diagnostics().toString());
        var expected = new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12)));
        assertEquals(expected, b.flowSnapshot().orElseThrow().state().locals().field("received"));
        assertEquals(expected, b.flowTask(child).orElseThrow().locals().field("received"));
        assertEquals(FlowRuntime.Status.COMPLETE, a.flowSnapshot().orElseThrow().state().status());
        assertEquals(3, a.flowSnapshot().orElseThrow().receipts().getFirst().positions().size());
        var journal = XBusHistorySavedData.get(f.fixture().world()); var saved = journal.save(new CompoundTag(), null);
        assertEquals(saved, XBusHistorySavedData.load(saved, null).save(new CompoundTag(), null));
        var claims = saved.getList("Claims", net.minecraft.nbt.Tag.TAG_COMPOUND);
        assertEquals(1, claims.size());
        var claim = claims.getCompound(0);
        assertTrue(claim.getBoolean("Settled"), "All participants completed the same handoff");
        var otherReaders = claim.getList("OtherReaders", net.minecraft.nbt.Tag.TAG_COMPOUND);
        assertEquals(1, otherReaders.size());
        var readerSlots = a.flowSnapshot().orElseThrow().receipts().getFirst().positions().stream()
            .filter(p -> p.computer().equals(b.communicationIdentity().orElseThrow())).map(XBusReceipt.Position::slot)
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(readerSlots, Set.of(claim.getCompound("Reader").getInt("Slot"), otherReaders.getCompound(0).getInt("Slot")));
    }
    @Test void aChildCanReceiveOnThePhysicalBusAfterItsMainFlowHasFinished() throws Exception {
        var f = ClusterWorldIndicatorTest.writers(); var a = f.fixture().computer(); var b = f.caller();
        var hardware = b.getClass().getDeclaredField("hardware"); hardware.setAccessible(true);
        var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), 3));
        hardware.set(b, (java.util.function.Supplier<HardwareSelection>)() -> new HardwareSelection(HardwareSelection.Status.READY, Optional.empty(), "test", limits));
        b.installFlow(new FlowProgram(Map.of(), List.of(
            new FlowProgram.Procedure("receiver", RECEIVE, Map.of(), Map.of("packet", PACKET), Map.of("packet", "received")),
            new FlowProgram.Read("child_read", "x0", "received", PACKET), new FlowProgram.EndProcedure("return"))), EMPTY);
        b.setPaused(false); b.step(); var child = b.startFlowTask(RECEIVE, EMPTY); b.step(); a.step();
        assertEquals(FlowRuntime.Status.COMPLETE, b.flowSnapshot().orElseThrow().state().status());
        assertEquals(FlowRuntime.Status.WAITING_READ, b.flowTask(child).orElseThrow().status());
        var result = new ComputerExecutionCycle(f.fixture().world(), List.of(a, b), f.fixture().world().clock).advanceRound(0);
        assertEquals(1, result.exchanges(), result.diagnostics().toString());
        assertEquals(new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12))), b.flowTask(child).orElseThrow().locals().field("received"));
        assertEquals(1, f.fixture().world().particles);
    }
    record Three(InternalFixture base, ComputerBehaviour third) {
        List<ComputerBehaviour> computers() { return List.of(base.fixture().computer(), base.caller(), third); }
        World world() { return base.fixture().world(); }
    }
    static Three three() throws Exception {
        var base = ClusterWorldIndicatorTest.writers(); var world = base.fixture().world();
        var last = new BlockPos(1, 1, 5);
        for (var pos : BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(2, 3, 6))) {
            if (pos.getX() == 0 || pos.getX() == 2 || pos.getY() == 0 || pos.getY() == 3 || pos.getZ() == 0 || pos.getZ() == 6)
                world.states.put(pos.immutable(), WorldEnclosureViewTest.casing.defaultBlockState());
            else if (pos.getZ() == 4) world.states.remove(pos);
        }
        world.states.put(CONTROLLER, base.fixture().controller().getBlockState());
        var c = addComputer(world, last, null, "third");
        var receive = new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x0", "received", PACKET),
            new FlowProgram.XBusStatus("after", "x0", "status")));
        base.caller().installFlow(receive, EMPTY); c.installFlow(receive, EMPTY);
        var config = new ClusterConfiguration(Map.of(
            "A", new ClusterConfiguration.Member(base.fixture().computer().communicationIdentity().orElseThrow(), MEMBER),
            "B", new ClusterConfiguration.Member(base.caller().communicationIdentity().orElseThrow(), FAR),
            "C", new ClusterConfiguration.Member(c.communicationIdentity().orElseThrow(), last)), Map.of(), Map.of(), Map.of(
            "ab", new XBusWire(new XBusWire.Pin("A", "worker", "x0"), new XBusWire.Pin("B", "caller", "x0"), true),
            "bc", new XBusWire(new XBusWire.Pin("B", "caller", "x0"), new XBusWire.Pin("C", "third", "x0"), true)));
        assertEquals("configured", base.fixture().controller().configure(config, base.fixture().player()));
        for (var host : List.of(base.fixture().computer(), base.caller(), c)) { host.setPaused(false); host.step(); }
        return new Three(base, c);
    }
    @Test void threePhysicalComputersReceiveTogetherAndEveryReceiverHasAVisibleTransferWithoutExtraRoundWork() throws Exception {
        for (long seed : List.of(0L, 1L, 2L, -1L)) {
            var f = three(); var hosts = f.computers();
            var result = new ComputerExecutionCycle(f.world(), hosts, f.world().clock).advanceRound(seed);
            assertEquals(1, result.exchanges(), result.diagnostics().toString());
            for (var reader : List.of(hosts.get(1), hosts.get(2))) {
                var state = reader.flowSnapshot().orElseThrow().state();
                assertEquals(new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(12))), state.locals().field("received"));
                assertEquals(1, state.cursor(), "A receiver completing its read cannot execute the next instruction in the same round");
            }
            assertEquals(2, f.world().particles, "Both receiving devices need UI-external transfer feedback");
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void dimensionReloadRepairsEveryMixedPhysicalOwnerSaveWithoutAnotherTransmission(int oldMask) throws Exception {
        var f = three(); var hosts = f.computers();
        var before = hosts.stream().map(host -> {
            var tag = new CompoundTag(); host.write(tag, net.minecraft.core.RegistryAccess.EMPTY, false); return tag;
        }).toList();
        var beforeHandoff = XBusHistorySavedData.get(f.world()).save(new CompoundTag(), null);
        assertEquals(1, new ComputerExecutionCycle(f.world(), hosts, f.world().clock).advanceRound(0).exchanges());
        var expected = hosts.stream().map(ComputerBehaviour::flowSnapshot).toList();
        var history = XBusHistorySavedData.get(f.world()).save(new CompoundTag(), null);
        for (int i = 0; i < hosts.size(); i++) if ((oldMask & 1 << i) != 0) {
            hosts.get(i).read(before.get(i), net.minecraft.core.RegistryAccess.EMPTY, false);
            hosts.get(i).communicationIdentity(); // Same lazy owner restoration as real endpoint access.
        }
        f.world().getDataStorage().set(XBusHistorySavedData.DATA_NAME, XBusHistorySavedData.load(history, null));
        var captured = XBusConnectedNetwork.capture(f.world(), hosts.getFirst(), "x0").connected().orElseThrow();
        var writer = new XBusNetwork.Pin(hosts.getFirst().communicationIdentity().orElseThrow(), "x0");
        var reader = new XBusNetwork.Pin(hosts.get(1).communicationIdentity().orElseThrow(), "x0");
        var mixed = hosts.stream().map(ComputerBehaviour::flowSnapshot).toList();
        assertEquals(XBusExchange.Status.RETIRED, captured.network().inspect().rejection().orElseThrow());
        assertEquals(XBusExchange.Status.RETIRED, captured.network().exchange(reader, writer));
        assertEquals(mixed, hosts.stream().map(ComputerBehaviour::flowSnapshot).toList(), "Durable completion forbids replay to an older owner");
        // If the dimension instead saved BEFORE this handoff, a retained owner receipt
        // supplies the actual missing value. Both crash cuts use real, unedited journal data.
        f.world().getDataStorage().set(XBusHistorySavedData.DATA_NAME, XBusHistorySavedData.load(beforeHandoff, null));
        captured = XBusConnectedNetwork.capture(f.world(), hosts.getFirst(), "x0").connected().orElseThrow();
        assertEquals(XBusExchange.Status.RECOVERY_REQUIRED, captured.network().inspect().rejection().orElseThrow());
        assertEquals(XBusExchange.Status.EXCHANGED, captured.network().exchange(reader, writer));
        assertEquals(expected, hosts.stream().map(ComputerBehaviour::flowSnapshot).toList());
        var again = XBusConnectedNetwork.capture(f.world(), hosts.getFirst(), "x0").connected().orElseThrow();
        assertEquals(XBusExchange.Status.NOT_READY, again.network().exchange(reader, writer));
        assertEquals(expected, hosts.stream().map(ComputerBehaviour::flowSnapshot).toList());
    }
    @Test void controllerLogShowsBothSegmentsOfTheBroadcastBranchOnceWithoutExposingThePayload() throws Exception {
        var f = three(); var controller = f.base().fixture().controller();
        var config = controller.routingState().orElseThrow().configuration(); var wires = new LinkedHashMap<>(config.wires());
        wires.put("unrelated", new XBusWire(new XBusWire.Pin("A", "worker", "x1"), new XBusWire.Pin("B", "caller", "x1"), true));
        assertEquals("configured", controller.configure(new ClusterConfiguration(config.members(), config.ports(), config.connections(), wires), f.base().fixture().player()));
        assertEquals(1, new ComputerExecutionCycle(f.world(), f.computers(), f.world().clock).advanceRound(0).exchanges());
        var before = f.computers().stream().map(ComputerBehaviour::flowSnapshot).toList();
        var log = controller.recentEvents();
        assertEquals(1, ComputerExecutionDiagnosticsTest.events(log, "xbus_exchanged", "ab"));
        assertEquals(1, ComputerExecutionDiagnosticsTest.events(log, "xbus_exchanged", "bc"), "The controller must show the indirect delivery through the second segment");
        assertEquals(0, ComputerExecutionDiagnosticsTest.events(log, "xbus_exchanged", "unrelated"), "Other pins of the same Computer are not implicitly connected");
        assertFalse(log.toString().contains("IntegerValue[value=12]"));
        assertEquals(log, controller.recentEvents());
        assertEquals(before, f.computers().stream().map(ComputerBehaviour::flowSnapshot).toList());
    }
}
