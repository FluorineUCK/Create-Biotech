package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerAssemblySleepTest {
    private static final DataType.RecordType PACKET = new DataType.RecordType(Map.of());
    private static FlowProgram program() {
        return new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER, "packet", PACKET), List.of(
            new FlowProgram.Sleep("delay", "ticks"), new FlowProgram.Write("send", "x0", "packet"))).withAssembly();
    }
    private static DataValue.RecordValue inputs(long ticks) {
        return new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(ticks), "packet", new DataValue.RecordValue(Map.of())));
    }
    @Test void zeroContinuesNegativeFailsAndLargeDurationNeverUsesInstructionRoundsAsTime() {
        for (long ticks : List.of(0L, -1L, Long.MAX_VALUE)) {
            var p = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("sleep", "ticks"))).withAssembly();
            var values = new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(ticks)));
            var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), p, values, new ResourceLimits(1000, 100000, 100));
            assertEquals(1, flow.advance(100)); var s = flow.checkpoint();
            assertEquals(ticks == 0 ? FlowRuntime.Status.COMPLETE : ticks < 0 ? FlowRuntime.Status.FAILED : FlowRuntime.Status.SLEEPING, s.status());
            if (ticks < 0) assertEquals(ComputationException.Code.INVALID_ARGUMENT, s.diagnostic().orElseThrow().code());
            for (int i = 0; i < 20; i++) assertEquals(0, flow.advance(100));
            assertEquals(s, flow.checkpoint());
            flow = FlowRuntime.restore(p, s, new ResourceLimits(1000, 100000, 100));
            flow.elapseTick(); assertEquals(s, flow.checkpoint());
            flow.load(); flow.elapseTick();
            assertEquals(ticks > 0 ? Long.MAX_VALUE - 1 : 0, flow.checkpoint().sleepTicks());
            assertEquals(1, flow.checkpoint().work());
        }
        var wrong = new FlowProgram(Map.of("ticks", DataType.Scalar.TEXT), List.of(new FlowProgram.Sleep("sleep", "ticks"))).withAssembly();
        assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> wrong.validate(LIMITS.hardware())).code());
    }
    @Test void loopSleepRestoresAtEveryBoundaryAndSignalModeSurvivesItsFrozenPc() {
        var p = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.SignalWrite("drive", "p0", "ticks"), new FlowProgram.Sleep("sleep", "ticks"),
            new FlowProgram.EndForever("end"))).withAssembly();
        var host = new ComputerBlockHost(() -> {}); host.installFlow(p, new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(2))), LIMITS, true);
        long lastWork = 0; int waits = 0;
        for (int i = 0; i < 30; i++) {
            host.step(LIMITS, true); var s = host.flowSnapshot().orElseThrow();
            assertTrue(s.state().work() >= lastWork); lastWork = s.state().work();
            if (s.state().status() == FlowRuntime.Status.SLEEPING) {
                waits++; assertEquals(Map.of("p0", 2), s.state().signals().outputs());
                assertEquals(2, s.state().assembly().orElseThrow().pc());
            }
            var next = new ComputerBlockHost(() -> {}); next.read(host.write());
            assertTrue(next.synchronize(LIMITS, true)); assertEquals(s, next.flowSnapshot().orElseThrow()); host = next;
        }
        assertTrue(waits >= 6, "Multiple loop entries and both remaining-time values restored");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"flow_version", "owner_version", "zero", "too_long", "negative", "no_cycle", "ready", "wrong_pc", "image"})
    void corruptOrDowngradedSleepKeepsRawSaveAndNeverRuns(String mutation) {
        var host = new ComputerBlockHost(() -> {}); host.installFlow(program(), inputs(3), LIMITS, true); host.step(LIMITS, true);
        var raw = (CompoundTag)host.write(); assertEquals(25, raw.getInt(FlowBlockNbtCodec.MARKER));
        var owner = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
        var flow = (DataValue.RecordValue)owner.field("flow"); var state = (DataValue.RecordValue)flow.field("state");
        assertEquals(new DataValue.IntegerValue(26), flow.field("version"));
        switch (mutation) {
            case "flow_version" -> flow = patch(flow, "version", new DataValue.IntegerValue(25));
            case "owner_version" -> raw.putInt(FlowBlockNbtCodec.MARKER, 24);
            case "zero" -> state = patch(state, "sleep_ticks", new DataValue.IntegerValue(0));
            case "too_long" -> state = patch(state, "sleep_ticks", new DataValue.IntegerValue(4));
            case "negative" -> state = patch(state, "sleep_ticks", new DataValue.IntegerValue(-1));
            case "no_cycle" -> state = patch(state, "work", new DataValue.IntegerValue(0));
            case "ready" -> state = patch(state, "status", new DataValue.TextValue("READY"));
            case "wrong_pc" -> {
                state = patch(state, "cursor", new DataValue.IntegerValue(1));
                state = patch(state, "assembly", patch((DataValue.RecordValue)state.field("assembly"), "pc", new DataValue.IntegerValue(1)));
            }
            case "image" -> state = patch(state, "assembly", patch((DataValue.RecordValue)state.field("assembly"), "image", new DataValue.TextValue("0".repeat(64))));
        }
        raw.put("Data", ValueNbtCodec.encode(patch(owner, "flow", patch(flow, "state", state)), LIMITS.archive()));
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(LIMITS, true);
        assertFalse(restored.loaded()); assertEquals(ComputerBlockHost.Code.INVALID_SAVE, restored.diagnostic().orElseThrow().code());
        assertEquals(raw, restored.write());
    }
    @Test void sleepingContinuationConsumesRamAndArchiveFailureDoesNotPublishHalfAdmission() {
        var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program(), inputs(3), LIMITS.hardware());
        var begin = flow.checkpoint(); long room = begin.controlUnits();
        flow = new FlowRuntime(begin.computer(), begin.flow(), program(), inputs(3), new ResourceLimits(1000, room, 1));
        flow.advance(1);
        assertEquals(FlowRuntime.Status.FAILED, flow.checkpoint().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, flow.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(begin.assembly(), flow.checkpoint().assembly()); assertEquals(0, flow.checkpoint().sleepTicks());
        var host = new ComputerBlockHost(() -> {}); host.installFlow(program(), inputs(3), LIMITS, true);
        var installed = (CompoundTag)host.write(); host.step(LIMITS, true);
        var sleeping = host.flowSnapshot().orElseThrow(); var raw = (CompoundTag)host.write();
        long units = ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive()).storageUnits();
        assertTrue(ValueNbtCodec.decode(installed.getCompound("Data"), LIMITS.archive()).storageUnits() < units);
        var tight = new ComputerHostLimits(LIMITS.hardware(),
            new ArchiveLimits(LIMITS.archive().maxNodes(), LIMITS.archive().maxStringChars(), units - 1),
            LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), LIMITS.taskSlots(), LIMITS.isa1a(), LIMITS.isa1b());
        var restored = new ComputerBlockHost(() -> {}); restored.read(installed); assertTrue(restored.synchronize(tight, true));
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, assertThrows(ComputerBlockHost.HostException.class, () -> restored.step(tight, true)).code());
        assertEquals(installed, restored.write());
        restored.step(LIMITS, true); assertEquals(sleeping, restored.flowSnapshot().orElseThrow());
    }
    private static DataValue.RecordValue patch(DataValue.RecordValue value, String key, DataValue replacement) {
        var fields = new LinkedHashMap<>(value.fields()); fields.put(key, replacement); return new DataValue.RecordValue(fields);
    }
    @Test void consumedXBusReceiptsSurviveLoopSleepAndNeverConsumeANewerSend() {
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        writer.installFlow(new FlowProgram(program().inputs(), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.Write("send", "x0", "packet"), new FlowProgram.Sleep("sleep", "ticks"),
            new FlowProgram.EndForever("end"))).withAssembly(), inputs(2), LIMITS, true);
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Forever("loop"),
            new FlowProgram.Read("receive", "x0", "packet", PACKET), new FlowProgram.EndForever("end"))).withAssembly(),
            new DataValue.RecordValue(Map.of()), LIMITS, true);
        writer.setPaused(false, LIMITS, true); reader.setPaused(false, LIMITS, true);
        long previous = -1;
        for (int iteration = 1; iteration <= 3; iteration++) {
            for (int tick = 0; tick < 20 && (writer.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.WAITING_WRITE
                    || reader.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.WAITING_READ); tick++) {
                writer.tick(LIMITS, true); reader.tick(LIMITS, true);
                writer = restored(writer); reader = restored(reader);
            }
            var waiting = writer.flowSnapshot().orElseThrow().state();
            assertEquals(FlowRuntime.Status.WAITING_WRITE, waiting.status()); assertTrue(waiting.operation() > previous); previous = waiting.operation();
            assertEquals(XBusExchange.Status.EXCHANGED, writer.exchange("x0", LIMITS, true, reader, "x0", LIMITS, true));
            assertEquals(iteration, writer.flowSnapshot().orElseThrow().receipts().size());
            writer.tick(LIMITS, true); var sleeping = writer.flowSnapshot().orElseThrow();
            assertEquals(FlowRuntime.Status.SLEEPING, sleeping.state().status());
            writer = restored(writer); reader = restored(reader); assertEquals(sleeping, writer.flowSnapshot().orElseThrow());
            assertNotEquals(XBusExchange.Status.EXCHANGED, writer.exchange("x0", LIMITS, true, reader, "x0", LIMITS, true));
            assertEquals(sleeping, writer.flowSnapshot().orElseThrow());
        }
    }
    private static ComputerBlockHost restored(ComputerBlockHost host) {
        var next = new ComputerBlockHost(() -> {}); next.read(host.write());
        assertTrue(next.synchronize(LIMITS, true)); assertEquals(host.flowSnapshot(), next.flowSnapshot()); return next;
    }
    @Test void waitingRegistersSurvivePauseUnloadAndSingleStepBeforeStartingXBus() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program(), inputs(3), LIMITS, true);
        assertTrue(host.loaded(), () -> host.diagnostic().toString());
        host.step(LIMITS, true);
        var sleeping = host.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.SLEEPING, sleeping.status());
        assertEquals(3, sleeping.sleepTicks()); assertEquals(1, sleeping.work());
        assertEquals(0, sleeping.assembly().orElseThrow().pc());
        var frozen = host.write();
        for (int i = 0; i < 10; i++) host.tick(LIMITS, true);
        assertEquals(frozen, host.write());
        host.setPaused(false, LIMITS, true); host.tick(LIMITS, true);
        assertEquals(2, host.flowSnapshot().orElseThrow().state().sleepTicks());
        frozen = host.write();
        for (int i = 0; i < 10; i++) host.tick(LIMITS, false);
        assertEquals(frozen, host.write());
        var restored = new ComputerBlockHost(() -> {}); restored.read(frozen);
        assertTrue(restored.synchronize(LIMITS, true)); assertEquals(frozen, restored.write());
        restored.setPaused(true, LIMITS, true); restored.step(LIMITS, true);
        assertEquals(1, restored.flowSnapshot().orElseThrow().state().sleepTicks());
        restored.step(LIMITS, true);
        var wake = restored.flowSnapshot().orElseThrow().state();
        assertTrue(wake.paused()); assertEquals(FlowRuntime.Status.READY, wake.status());
        assertEquals(1, wake.assembly().orElseThrow().pc()); assertEquals(1, wake.work());
        assertEquals(sleeping.flow(), wake.flow());
        assertEquals(sleeping.assembly().orElseThrow().registers(), wake.assembly().orElseThrow().registers());
        restored.step(LIMITS, true);
        var sending = restored.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.WAITING_WRITE, sending.status()); assertEquals(2, sending.work());
        assertEquals(1, sending.assembly().orElseThrow().pc());
    }
}
