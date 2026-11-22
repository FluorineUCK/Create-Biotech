package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerHardwarePinsTest {
    @Test void missingPinsDoNotAllowResettingAnInvalidCheckpoint() {
        var host = new ComputerBlockHost(() -> {}); host.installFlow(writer(), INPUTS, LIMITS, true);
        var saved = host.flowSnapshot().orElseThrow(); var state = saved.state();
        var forged = new FlowRuntime.Checkpoint(state.computer(), state.flow(), 0, FlowRuntime.Status.WAITING_WRITE, state.locals(), 0, false);
        var raw = com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec.encode(
            new FlowBlockHost.Saved(saved.program(), saved.initialInputs(), forged), LIMITS.archive());
        var empty = new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), new HardwarePins(List.of()));
        var restored = new ComputerBlockHost(() -> fail("Invalid checkpoints must stay opaque")); restored.read(raw); restored.tick(empty, true);
        assertEquals(ComputerBlockHost.Code.INVALID_SAVE, restored.diagnostic().orElseThrow().code());
        assertThrows(ComputerBlockHost.HostException.class, () -> restored.reset(empty, true));
        assertThrows(ComputerBlockHost.HostException.class, () -> restored.installFlow(new FlowProgram(Map.of(), List.of()),
            new DataValue.RecordValue(Map.of()), empty, true));
        assertEquals(raw, restored.write());
    }
    @Test void explicitMissingPinResetRetiresCompletedReceiptsWithoutTouchingThePeer() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        ComputerXBusExchangeTest.start(writer, writer(), INPUTS);
        ComputerXBusExchangeTest.start(reader, ComputerXBusExchangeTest.reader(), ComputerXBusExchangeTest.EMPTY);
        var oldReader = reader.write(); assertEquals(XBusExchange.Status.EXCHANGED, ComputerXBusExchangeTest.exchange(writer, reader));
        var peer = reader.write(); var completed = writer.flowSnapshot().orElseThrow();
        var absent = new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), new HardwarePins(List.of("x1")));
        var restored = new ComputerBlockHost(() -> {}); restored.read(writer.write()); restored.tick(absent, true);
        restored.reset(absent, true); var reset = restored.flowSnapshot().orElseThrow();
        assertFalse(restored.loaded()); assertTrue(reset.state().paused()); assertTrue(reset.receipts().isEmpty());
        assertEquals(completed.control().generation() + 1, reset.control().generation()); assertEquals(peer, reader.write());
        restored.setPaused(false, LIMITS, true); restored.tick(LIMITS, true);
        assertEquals(XBusExchange.Status.RETIRED, ComputerXBusExchangeTest.exchange(restored, ComputerXBusExchangeTest.restore(oldReader)));
    }
    @Test void unknownHardwarePinCannotReplaceTheInstalledProgramEvenInAnUnselectedBranch() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(writer(), INPUTS, LIMITS, true);
        var previous = host.write(); var saved = host.flowSnapshot();
        var unknown = new FlowProgram(writer().inputs(), List.of(new FlowProgram.Write("bad_write", "x9", "packet")));
        assertThrows(FlowPins.Unavailable.class, () -> host.installFlow(unknown, INPUTS, LIMITS, true));
        assertEquals(previous, host.write()); assertEquals(saved, host.flowSnapshot());
        var branched = new FlowProgram(Map.of("ok", DataType.Scalar.BOOLEAN), List.of(new FlowProgram.If("if", "ok"),
            new FlowProgram.AwaitReadable("unused", "x9"), new FlowProgram.EndIf("end")));
        assertThrows(FlowPins.Unavailable.class, () -> host.installFlow(branched,
            new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(false))), LIMITS, true));
        assertEquals(previous, host.write());
    }
    @Test void hardwareDowngradePreservesTheWaitingSaveAndRestoringPinsResumesWithoutReplaying() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(writer(), INPUTS, LIMITS, true); host.setPaused(false, LIMITS, true); host.tick(LIMITS, true);
        var raw = host.write(); var before = host.flowSnapshot().orElseThrow();
        var absent = new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), new HardwarePins(List.of("x1")));
        host.tick(absent, true);
        assertFalse(host.loaded(), "A program cannot recreate x0 after the hardware removes it");
        assertEquals("HARDWARE_MISMATCH", host.diagnostic().orElseThrow().code().name());
        assertEquals(raw, host.write()); assertEquals(before, host.flowSnapshot().orElseThrow());
        var reloaded = new ComputerBlockHost(() -> fail("Restoring hardware must not advance or rewrite a waiting operation"));
        reloaded.read(raw); reloaded.tick(absent, true); assertFalse(reloaded.loaded()); assertEquals(raw, reloaded.write());
        reloaded.tick(LIMITS, true); assertTrue(reloaded.loaded());
        assertEquals(before, reloaded.flowSnapshot().orElseThrow()); assertEquals(raw, reloaded.write());
    }
}
