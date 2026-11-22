package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerAssemblySignalTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void ownerArchiveLimitCannotHalfPublishDriveOrReadAndRecoveryContinuesTheOriginalOperation(boolean read) {
        var host = installed(List.of(new FlowProgram.SignalWrite("drive", "p0", "level"),
            new FlowProgram.SignalRead("read", "p0", "received_signal_level")));
        if (read) host.step(LIMITS, true);
        var before = host.flowSnapshot().orElseThrow(); var raw = (CompoundTag)host.write();
        host.step(LIMITS, true, Map.of("p0", 8));
        var after = host.flowSnapshot().orElseThrow();
        long needed = ValueNbtCodec.decode(((CompoundTag)host.write()).getCompound("Data"), LIMITS.archive()).storageUnits();
        long original = ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive()).storageUnits();
        assertTrue(needed > original, "This operation must actually grow its saved continuation");
        var tight = new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(LIMITS.archive().maxNodes(), LIMITS.archive().maxStringChars(), needed - 1));
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(tight, true);
        assertTrue(restored.loaded()); assertEquals(before, restored.flowSnapshot().orElseThrow());
        assertThrows(RuntimeException.class, () -> restored.step(tight, true, Map.of("p0", 8)));
        assertEquals(raw, restored.write()); assertEquals(before, restored.flowSnapshot().orElseThrow());
        restored.tick(LIMITS, true); assertTrue(restored.loaded());
        restored.step(LIMITS, true, Map.of("p0", 8));
        assertEquals(after, restored.flowSnapshot().orElseThrow());
    }
    @Test void unavailableDataReadFailsAtomicallyButUnavailableBusStatusIsZero() {
        var host = installed(List.of(new FlowProgram.SignalWrite("drive", "p0", "level"),
            new FlowProgram.SignalRead("read", "p0", "input")));
        host.step(LIMITS, true); var before = host.flowSnapshot().orElseThrow().state();
        host.step(LIMITS, true, Map.of()); var failed = host.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.FAILED, failed.state().status());
        assertEquals(ComputationException.Code.MISSING_REFERENCE, failed.state().diagnostic().orElseThrow().code());
        assertEquals(before.assembly(), failed.state().assembly()); assertEquals(before.locals(), failed.state().locals());
        assertEquals(before.signals(), failed.state().signals()); assertEquals(before.work() + 1, failed.state().work());
        var restored = new ComputerBlockHost(() -> {}); restored.read(host.write()); restored.tick(LIMITS, true);
        assertEquals(failed, restored.flowSnapshot().orElseThrow());
        var status = installed(List.of(new FlowProgram.SignalWrite("drive", "p0", "level"),
            new FlowProgram.XBusStatus("status", "x0", "busy")));
        status.step(LIMITS, true); status.step(LIMITS, true, Map.of());
        assertEquals(new DataValue.IntegerValue(0), status.flowSnapshot().orElseThrow().state().locals().field("busy"));
        assertEquals(Map.of("p0", 15), status.flowSnapshot().orElseThrow().state().signals().outputs());
        assertTrue(status.flowSnapshot().orElseThrow().receipts().isEmpty());
    }
    @Test void driveAllocationFailureKeepsTheOldPcRegistersAndOutputWhilePayingTheFailedCycle() {
        var source = new FlowProgram(Map.of("level", DataType.Scalar.INTEGER), List.of(new FlowProgram.SignalWrite("drive", "p0", "level"))).withAssembly();
        var input = new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(8)));
        var reference = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), source, input, LIMITS.hardware());
        var tight = new ResourceLimits(1000, reference.checkpoint().controlUnits(), 1);
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), source, input, tight);
        var before = runtime.checkpoint(); runtime.load(); runtime.advanceRound(Map.of());
        var failed = runtime.checkpoint();
        assertEquals(FlowRuntime.Status.FAILED, failed.status()); assertEquals(1, failed.work());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, failed.diagnostic().orElseThrow().code());
        assertEquals(before.assembly(), failed.assembly()); assertEquals(before.signals(), failed.signals());
        assertEquals(failed, FlowRuntime.restore(source, failed, LIMITS.hardware()).checkpoint());
    }
    @Test void removedHardwareSuspendsOnlyThatOwnerAndRestoresWithoutReinterpretingTheCheckpoint() {
        var host = installed(List.of(new FlowProgram.SignalWrite("drive", "p0", "level"), new FlowProgram.XBusStatus("status", "x0", "busy")));
        host.step(LIMITS, true); var raw = host.write(); var before = host.flowSnapshot().orElseThrow();
        var withoutSignal = new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), new HardwarePins(List.of("x0")));
        host.tick(withoutSignal, true);
        assertFalse(host.loaded()); assertEquals(ComputerBlockHost.Code.HARDWARE_MISMATCH, host.diagnostic().orElseThrow().code());
        assertEquals(raw, host.write());
        host.tick(LIMITS, true); assertTrue(host.loaded()); assertEquals(before, host.flowSnapshot().orElseThrow());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"flow_version", "owner_version", "missing_drive", "foreign_drive", "cold_loop_drive", "extension_identity"})
    void corruptedVersionsAndImpossibleSignalModesKeepTheirRawSave(String mutation) {
        var loop = List.<FlowProgram.Step>of(new FlowProgram.Forever("loop"), new FlowProgram.SignalWrite("drive", "p0", "level"), new FlowProgram.EndForever("end"));
        var host = installed(mutation.equals("cold_loop_drive") ? loop : List.of(new FlowProgram.SignalWrite("drive", "p0", "level"),
            new FlowProgram.XBusStatus("status", "x0", "busy")));
        if (!mutation.equals("cold_loop_drive")) host.step(LIMITS, true);
        var raw = (CompoundTag)host.write();
        assertEquals(23, raw.getInt(FlowBlockNbtCodec.MARKER));
        var owner = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
        var flow = (DataValue.RecordValue)owner.field("flow"); var state = (DataValue.RecordValue)flow.field("state");
        assertEquals(new DataValue.IntegerValue(24), flow.field("version"));
        switch (mutation) {
            case "flow_version" -> flow = patch(flow, "version", new DataValue.IntegerValue(23));
            case "owner_version" -> raw.putInt(FlowBlockNbtCodec.MARKER, 22);
            case "missing_drive" -> state = patch(state, "signal_outputs", new DataValue.RecordValue(Map.of()));
            case "foreign_drive" -> state = patch(state, "signal_outputs", new DataValue.RecordValue(Map.of("p0", new DataValue.IntegerValue(15), "x0", new DataValue.IntegerValue(15))));
            case "cold_loop_drive" -> state = patch(state, "signal_outputs", new DataValue.RecordValue(Map.of("p0", new DataValue.IntegerValue(15))));
            case "extension_identity" -> state = patch(state, "assembly", patch((DataValue.RecordValue)state.field("assembly"), "image", new DataValue.TextValue("0".repeat(64))));
        }
        raw.put("Data", ValueNbtCodec.encode(patch(owner, "flow", patch(flow, "state", state)), LIMITS.archive()));
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(LIMITS, true);
        assertFalse(restored.loaded()); assertEquals(ComputerBlockHost.Code.INVALID_SAVE, restored.diagnostic().orElseThrow().code());
        assertEquals(raw, restored.write());
    }
    @Test void loopsRestoreRealInstructionModesAtEveryBoundaryWithoutReplayingDriveChanges() {
        var host = installed(List.of(new FlowProgram.Forever("loop"), new FlowProgram.SignalWrite("drive", "p0", "level"),
            new FlowProgram.XBusStatus("status", "x0", "busy"), new FlowProgram.SignalRead("read", "p0", "input"), new FlowProgram.EndForever("end")));
        for (int i = 0; i < 20; i++) {
            host.step(LIMITS, true, Map.of("p0", i % 16, "x0", 15));
            var before = host.flowSnapshot().orElseThrow();
            var next = new ComputerBlockHost(() -> {}); next.read(host.write()); next.tick(LIMITS, true);
            assertTrue(next.loaded(), () -> next.diagnostic().toString()); assertEquals(before, next.flowSnapshot().orElseThrow());
            host = next;
        }
        assertEquals(20, host.flowSnapshot().orElseThrow().state().work());
    }
    private static ComputerBlockHost installed(List<FlowProgram.Step> steps) {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(new FlowProgram(Map.of("level", DataType.Scalar.INTEGER), steps).withAssembly(),
            new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(99))), LIMITS, true);
        return host;
    }
    private static DataValue.RecordValue patch(DataValue.RecordValue value, String key, DataValue replacement) {
        var fields = new LinkedHashMap<>(value.fields()); fields.put(key, replacement); return new DataValue.RecordValue(fields);
    }
    @Test void signalInstructionsRunOnTheRealOwnerAndRestoreWithoutHiddenGraphFrames() {
        var source = new FlowProgram(Map.of("level", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.SignalWrite("drive", "p0", "level"),
            new FlowProgram.XBusStatus("observe", "x0", "busy"),
            new FlowProgram.SignalRead("read", "p0", "input"))).withAssembly();
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(source, new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(99))), LIMITS, true);
        host.step(LIMITS, true, Map.of("p0", 3));
        var driven = host.flowSnapshot().orElseThrow();
        assertEquals(Map.of("p0", 15), driven.state().signals().outputs());
        assertEquals(1, driven.state().work()); assertEquals(1, driven.state().assembly().orElseThrow().pc());
        host.tick(LIMITS, true, Map.of("p0", 6));
        assertEquals(driven, host.flowSnapshot().orElseThrow(), "Paused hardware retains output without executing");
        var restored = new ComputerBlockHost(() -> {}); restored.read(host.write()); restored.tick(LIMITS, true);
        assertTrue(restored.loaded(), () -> restored.diagnostic().toString());
        assertEquals(driven, restored.flowSnapshot().orElseThrow());
        restored.step(LIMITS, true, Map.of("p0", 8, "x0", 15));
        var observed = restored.flowSnapshot().orElseThrow();
        assertEquals(new DataValue.IntegerValue(15), observed.state().locals().field("busy"));
        assertEquals(Map.of("p0", 15), observed.state().signals().outputs());
        assertTrue(observed.receipts().isEmpty()); assertTrue(observed.state().expression().isEmpty());
        restored.step(LIMITS, true, Map.of("p0", 12));
        var done = restored.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.COMPLETE, done.state().status());
        assertEquals(new DataValue.IntegerValue(12), done.state().locals().field("input"));
        assertTrue(done.state().signals().outputs().isEmpty()); assertEquals(3, done.state().work());
        var again = new ComputerBlockHost(() -> {}); again.read(restored.write()); again.tick(LIMITS, true);
        assertEquals(done, again.flowSnapshot().orElseThrow());
    }
}
