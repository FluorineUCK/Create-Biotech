package com.nobodiiiii.createbiotech.content.biocomputing.cluster;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterControllerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ClusterSignalRuntimeTest {    @Test void signalContinuityFailuresAndRecoveryAreLoggedWithoutPublishingSignalLevels() throws Exception {
        var f = configured(sleeper(), sleepInput(2), sleeper(), sleepInput(7)); var a = f.fixture().computer(); var b = f.caller();
        a.step(); b.step(); var controller = f.fixture().controller(); controller.tick();
        b.disableEndpoint(); controller.tick(); controller.tick();
        b.configureEndpoint(new com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration("caller", true)); controller.tick(); controller.tick();
        var log = controller.recentEvents(); var events = ((DataValue.ListValue)log.field("entries")).values();
        assertEquals(1, events.stream().filter(e -> ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue("signal_closed"))).count());
        assertEquals(1, events.stream().filter(e -> ((DataValue.RecordValue)e).field("code").equals(new DataValue.TextValue("recovered"))).count());
        assertFalse(log.toString().contains("signal_70")); assertFalse(log.toString().contains("signal_20"));
        assertEquals(Map.of("p0", 7), b.signalOutputs().orElseThrow().outputs());
    }
    @BeforeAll static void setup() throws Exception { bootstrap(); }
    @Test void removedConfiguredHardwarePinReportsHardwareFailureNotMissingProgram() throws Exception {
        var f = configured(sleeper(), sleepInput(2), sleeper(), sleepInput(7)); var b = f.caller(); b.step();
        var saved = b.flowSnapshot(); var field = ComputerBehaviour.class.getDeclaredField("hardware"); field.setAccessible(true);
        var original = field.get(b);
        var reduced = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), new HardwarePins(List.of("x0", "x1"), List.of("p1"))));
        field.set(b, (java.util.function.Supplier<HardwareSelection>) () -> new HardwareSelection(HardwareSelection.Status.READY, Optional.empty(), "test", reduced));
        try {
            f.fixture().controller().tick();
            assertEquals("signal_hardware_mismatch", f.fixture().controller().wireDiagnostics().get("voltage"));
            assertFalse(f.fixture().controller().signalInputs(f.fixture().computer()).containsKey("p0"));
        } finally { field.set(b, original); }
        assertEquals(Map.of("p0", 7), b.signalOutputs().orElseThrow().outputs());
        assertEquals(saved, b.flowSnapshot(), "Hardware mismatch must retain the previous checkpoint for recovery");
    }
    @Test void explicitSignalBranchUsesOtherDriversMaxWithoutJoiningUnwiredPins() throws Exception {
        var writer = new FlowProgram(Map.of("low", DataType.Scalar.INTEGER, "high", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.SignalWrite("low", "p0", "low"), new FlowProgram.SignalWrite("high", "p1", "high")));
        var f = configured(sleeper(), sleepInput(2), writer, new DataValue.RecordValue(Map.of(
            "low", new DataValue.IntegerValue(7), "high", new DataValue.IntegerValue(15))));
        var controller = f.fixture().controller(); var a = f.fixture().computer(); var b = f.caller(); a.step(); b.step(); b.step();
        assertEquals(Map.of("p0", 7, "p1", 0), controller.signalInputs(a));
        var config = controller.routingState().orElseThrow().configuration(); var signals = new LinkedHashMap<>(config.signals());
        signals.put("branch", new SimpleIoWire(new SimpleIoWire.Pin("B", "caller", "p0"), new SimpleIoWire.Pin("B", "caller", "p1"), true));
        assertEquals("configured", controller.configure(new ClusterConfiguration(config.members(), config.ports(), config.connections(), config.wires(), signals), f.fixture().player()));
        assertEquals(Map.of("p0", 15, "p1", 0), controller.signalInputs(a));
        assertEquals(Map.of("p0", 15, "p1", 7), controller.signalInputs(b));
    }
    @Test void failureDoesNotRetractAnEarlierCommittedPhysicalOutput() throws Exception {
        var failing = new FlowProgram(Map.of("level", DataType.Scalar.INTEGER, "ticks", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.SignalWrite("drive", "p0", "level"), new FlowProgram.Sleep("invalid", "ticks")));
        var reader = new FlowProgram(Map.of(), List.of(new FlowProgram.SignalRead("read", "p0", "sample")));
        var f = configured(reader, new DataValue.RecordValue(Map.of()), failing,
            new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(7), "ticks", new DataValue.IntegerValue(-1))));
        var b = f.caller(); b.step(); b.step();
        assertEquals(FlowRuntime.Status.FAILED, b.flowSnapshot().orElseThrow().state().status());
        assertEquals(Map.of("p0", 7), b.flowSnapshot().orElseThrow().state().signals().outputs());
        var before = b.flowSnapshot(); f.fixture().computer().step();
        assertEquals(new DataValue.IntegerValue(7), f.fixture().computer().flowSnapshot().orElseThrow().state().locals().field("sample"));
        assertEquals(before, b.flowSnapshot(), "Sampling a failed peer cannot execute or clear it");
    }
    @Test void sleepUsesOneActivityTickAndPausedPeerKeepsDriveWithoutBeingStepped() throws Exception {
        var program = sleeper(); var f = configured(program, sleepInput(2), program, sleepInput(7));
        var a = f.fixture().computer(); var b = f.caller(); var controller = f.fixture().controller();
        a.setPaused(false); b.setPaused(false); a.tick(); controller.tick(); b.tick();
        finishFlowTick(f);
        assertEquals(3, a.flowSnapshot().orElseThrow().state().sleepTicks());
        b.setPaused(true); f.fixture().world().clock++; b.tick(); controller.tick(); a.tick();
        finishFlowTick(f);
        assertEquals(2, a.flowSnapshot().orElseThrow().state().sleepTicks());
        var paused = b.flowSnapshot().orElseThrow();
        a.step(); assertEquals(1, a.flowSnapshot().orElseThrow().state().sleepTicks()); assertEquals(paused, b.flowSnapshot().orElseThrow());
        f.fixture().world().clock++; controller.tick(); a.tick();
        finishFlowTick(f);
        assertEquals(new DataValue.IntegerValue(7), a.flowSnapshot().orElseThrow().state().locals().field("sample"));
        assertEquals(paused, b.flowSnapshot().orElseThrow());
        assertEquals(Map.of("p0", 7), b.flowSnapshot().orElseThrow().state().signals().outputs());
    }
    @Test void debuggerSamplesPausedPeerWithoutExecutingItAndClosedGrantIsUnknownNotZero() throws Exception {
        for (boolean closed : List.of(false, true)) {
            var read = new FlowProgram(Map.of(), List.of(new FlowProgram.SignalRead("read", "p0", "sample")));
            var f = configured(read, new DataValue.RecordValue(Map.of()), sleeper(), sleepInput(7));
            var a = f.fixture().computer(); var b = f.caller(); b.step(); var before = b.flowSnapshot();
            if (closed) b.disableEndpoint(); a.step();
            var state = a.flowSnapshot().orElseThrow().state();
            if (closed) { assertEquals(FlowRuntime.Status.FAILED, state.status()); assertFalse(state.locals().fields().containsKey("sample")); }
            else assertEquals(new DataValue.IntegerValue(7), state.locals().field("sample"));
            assertEquals(before, b.flowSnapshot());
        }
    }
    @Test void brokenShellAndAbsentControllerKeepLocalSleepButDoNotInventSignals() throws Exception {
        for (boolean removed : List.of(false, true)) {
            var f = configured(sleeper(), sleepInput(2), sleeper(), sleepInput(7)); var a = f.fixture().computer();
            a.setPaused(false); f.caller().setPaused(false); a.tick(); finishFlowTick(f);
            if (removed) f.fixture().world().entities.remove(CONTROLLER); else f.fixture().world().states.remove(net.minecraft.core.BlockPos.ZERO);
            f.fixture().world().clock++; a.tick(); f.fixture().controller().tick(); a.tick();
            finishFlowTick(f);
            assertEquals(2, a.flowSnapshot().orElseThrow().state().sleepTicks());
            f.fixture().world().clock++; a.tick(); finishFlowTick(f); f.fixture().world().clock++; a.tick(); finishFlowTick(f);
            var state = a.flowSnapshot().orElseThrow().state();
            assertEquals(FlowRuntime.Status.FAILED, state.status()); assertFalse(state.locals().fields().containsKey("sample"));
            assertEquals(Map.of("p0", 2), state.signals().outputs());
        }
    }
    @Test void controllerCannotPoolBudgetsOrRepeatOneMembersWorkInTheSameTick() throws Exception {
        var steps = new ArrayList<FlowProgram.Step>();
        for (int i = 0; i < 50; i++) steps.add(new FlowProgram.SignalWrite("drive_" + i, "p0", "level"));
        var busy = new FlowProgram(Map.of("level", DataType.Scalar.INTEGER), steps);
        var read = new FlowProgram(Map.of(), List.of(new FlowProgram.SignalRead("read", "p0", "sample")));
        var f = configured(busy, value(2), read, new DataValue.RecordValue(Map.of())); var a = f.fixture().computer();
        a.setPaused(false); f.caller().setPaused(false); a.tick(); f.caller().tick(); f.fixture().controller().tick();
        finishFlowTick(f);
        assertEquals(32, a.flowSnapshot().orElseThrow().state().work());
        assertEquals(new DataValue.IntegerValue(0), f.caller().flowSnapshot().orElseThrow().state().locals().field("sample"), "First round precedes the writer's first drive");
        f.fixture().world().clock++; f.fixture().controller().tick(); a.tick();
        finishFlowTick(f);
        assertEquals(50, a.flowSnapshot().orElseThrow().state().work());
    }
    static FlowProgram sleeper() { return new FlowProgram(Map.of("level", DataType.Scalar.INTEGER, "ticks", DataType.Scalar.INTEGER), List.of(
        new FlowProgram.SignalWrite("drive", "p0", "level"), new FlowProgram.Sleep("delay", "ticks"), new FlowProgram.SignalRead("read", "p0", "sample"))); }
    static DataValue.RecordValue sleepInput(long level) { return new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(level), "ticks", new DataValue.IntegerValue(3))); }
    @Test void replacingACompletedFlowAfterTheClusterRoundCannotRefillThisTicksBudgetOrUseFallbackSamples() throws Exception {
        var program = new FlowProgram(Map.of("level", DataType.Scalar.INTEGER), List.of(new FlowProgram.SignalWrite("drive", "p0", "level")));
        var f = configured(program, value(2), program, value(7)); var a = f.fixture().computer();
        a.setPaused(false); f.caller().setPaused(false); f.fixture().controller().tick();
        finishFlowTick(f);
        a.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.SignalRead("read", "p0", "sample"))), new DataValue.RecordValue(Map.of()));
        a.setPaused(false); a.tick();
        finishFlowTick(f);
        assertEquals(FlowRuntime.Status.READY, a.flowSnapshot().orElseThrow().state().status());
        assertEquals(0, a.flowSnapshot().orElseThrow().state().work());
        f.fixture().world().clock++; a.tick();
        finishFlowTick(f);
        assertEquals(new DataValue.IntegerValue(7), a.flowSnapshot().orElseThrow().state().locals().field("sample"));
    }
    static InternalFixture configured(FlowProgram left, DataValue.RecordValue leftInput, FlowProgram right, DataValue.RecordValue rightInput) throws Exception {
        var f = internal(false); var a = f.fixture().computer(); var b = f.caller();
        a.installFlow(left, leftInput); b.installFlow(right, rightInput);
        var config = new ClusterConfiguration(Map.of("A", new ClusterConfiguration.Member(a.communicationIdentity().orElseThrow(), MEMBER),
            "B", new ClusterConfiguration.Member(b.communicationIdentity().orElseThrow(), FAR)), Map.of(), Map.of(), Map.of(),
            Map.of("voltage", new SimpleIoWire(new SimpleIoWire.Pin("A", "worker", "p0"), new SimpleIoWire.Pin("B", "caller", "p0"), true)));
        assertEquals("configured", f.fixture().controller().configure(config, f.fixture().player())); return f;
    }
    @Test void controllerAndMemberTickOrderCannotChangeSameRoundSignalExchange() throws Exception {
        for (boolean memberFirst : List.of(false, true)) {
            var f = internal(false); var a = f.fixture().computer(); var b = f.caller();
            var program = new FlowProgram(Map.of("level", DataType.Scalar.INTEGER), List.of(
                new FlowProgram.SignalWrite("drive", "p0", "level"), new FlowProgram.SignalRead("read", "p0", "sample")));
            a.installFlow(program, value(2)); b.installFlow(program, value(7));
            var config = new ClusterConfiguration(Map.of("A", new ClusterConfiguration.Member(a.communicationIdentity().orElseThrow(), MEMBER),
                "B", new ClusterConfiguration.Member(b.communicationIdentity().orElseThrow(), FAR)), Map.of(), Map.of(), Map.of(),
                Map.of("voltage", new SimpleIoWire(new SimpleIoWire.Pin("A", "worker", "p0"), new SimpleIoWire.Pin("B", "caller", "p0"), true)));
            assertEquals("configured", f.fixture().controller().configure(config, f.fixture().player()));
            a.setPaused(false); b.setPaused(false);
            if (memberFirst) b.tick(); else f.fixture().controller().tick();
            a.tick(); b.tick(); f.fixture().controller().tick();
            finishFlowTick(f);
            assertEquals(new DataValue.IntegerValue(7), a.flowSnapshot().orElseThrow().state().locals().field("sample"));
            assertEquals(new DataValue.IntegerValue(2), b.flowSnapshot().orElseThrow().state().locals().field("sample"));
            assertEquals(2, a.flowSnapshot().orElseThrow().state().work());
            assertEquals(2, b.flowSnapshot().orElseThrow().state().work());
            assertEquals(SimpleIoState.EMPTY, a.flowSnapshot().orElseThrow().state().signals());
        }
    }
    static DataValue.RecordValue value(long value) { return new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(value))); }
}
