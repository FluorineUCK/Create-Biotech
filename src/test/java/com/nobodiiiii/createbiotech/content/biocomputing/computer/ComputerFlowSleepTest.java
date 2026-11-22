package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerFlowSleepTest {
    @Test void debuggerDistinguishesSleepTimeFromInstructionWork() {
        var host = new ComputerBlockHost(() -> {}); host.installFlow(program(), inputs(3), LIMITS, true); host.step(LIMITS, true);
        var view = com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerStatusView.flow(host.flowSnapshot(), host.loaded(), host.diagnostic());
        assertEquals(new DataValue.IntegerValue(3), view.field("sleep_ticks"));
        assertEquals(new DataValue.IntegerValue(1), view.field("work"));
    }
    private static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(
        new ResourceLimits(100, 100000, 100), new ArchiveLimits(100000, 1000000, 1000000)));
    private static DataValue.RecordValue obj(String... pairs) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) fields.put(pairs[i], new DataValue.TextValue(pairs[i + 1]));
        return new DataValue.RecordValue(fields);
    }
    private static DataValue.RecordValue inputs(long ticks) {
        return new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(ticks), "packet", new DataValue.RecordValue(Map.of())));
    }
    private static FlowProgram program() {
        var base = new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER, "packet", new DataType.RecordType(Map.of())), List.of());
        var data = new LinkedHashMap<>(((DataValue.RecordValue) FlowProgramCodec.encode(base)).fields());
        data.put("steps", new DataValue.ListValue(List.of(obj("kind", "sleep", "source", "delay", "ticks", "ticks"),
            obj("kind", "write", "source", "send", "port", "x0", "local", "packet"))));
        return FlowProgramCodec.decode(new DataValue.RecordValue(data));
    }
    @Test void activityTicksNotInstructionBudgetReleaseTheFollowingWrite() {
        var host = new ComputerBlockHost(() -> {}); host.installFlow(program(), inputs(2), LIMITS, true);
        host.setPaused(false, LIMITS, true); host.tick(LIMITS, true);
        assertEquals("SLEEPING", host.flowSnapshot().orElseThrow().state().status().name());
        assertEquals(0, host.flowSnapshot().orElseThrow().state().cursor());
        assertEquals(1, host.flowSnapshot().orElseThrow().state().work());
        host.tick(LIMITS, true);
        assertEquals("SLEEPING", host.flowSnapshot().orElseThrow().state().status().name());
        assertEquals(1, host.flowSnapshot().orElseThrow().state().work());
        host.tick(LIMITS, true);
        assertEquals(FlowRuntime.Status.WAITING_WRITE, host.flowSnapshot().orElseThrow().state().status());
        assertEquals(2, host.flowSnapshot().orElseThrow().state().work());
    }
    @Test void pausedUnloadedAndRecreatedOwnerKeepTheRemainingTimeAndStepAdvancesOnlyOneTick() {
        var host = new ComputerBlockHost(() -> {}); host.installFlow(program(), inputs(3), LIMITS, true);
        host.step(LIMITS, true);
        assertEquals(3, host.flowSnapshot().orElseThrow().state().sleepTicks());
        var frozen = host.write();
        for (int i = 0; i < 20; i++) host.tick(LIMITS, true);
        assertEquals(frozen, host.write());
        host.setPaused(false, LIMITS, true); host.tick(LIMITS, true);
        assertEquals(2, host.flowSnapshot().orElseThrow().state().sleepTicks());
        frozen = host.write();
        for (int i = 0; i < 20; i++) host.tick(LIMITS, false);
        assertEquals(frozen, host.write());
        var restored = new ComputerBlockHost(() -> {}); restored.read(frozen);
        assertTrue(restored.synchronize(LIMITS, true));
        assertEquals(2, restored.flowSnapshot().orElseThrow().state().sleepTicks());
        assertEquals(frozen, restored.write());
        restored.setPaused(true, LIMITS, true); restored.step(LIMITS, true); restored.step(LIMITS, true);
        var wake = restored.flowSnapshot().orElseThrow().state();
        assertTrue(wake.paused()); assertEquals(FlowRuntime.Status.READY, wake.status()); assertEquals(1, wake.work());
        restored.step(LIMITS, true);
        assertEquals(FlowRuntime.Status.WAITING_WRITE, restored.flowSnapshot().orElseThrow().state().status());
        restored.reset(LIMITS, true);
        assertEquals(0, restored.flowSnapshot().orElseThrow().state().sleepTicks());
        assertEquals(inputs(3), restored.flowSnapshot().orElseThrow().state().locals());
    }
    @Test void timeIsNotAdvancedByPureBudgetAndZeroNegativeOrNonIntegerDurationsAreDistinct() {
        var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program(), inputs(Long.MAX_VALUE), LIMITS.hardware());
        flow.advance(100); var wait = flow.checkpoint();
        assertEquals(0, flow.advance(100)); assertEquals(wait, flow.checkpoint());
        flow.unload(); flow.elapseTick(); assertEquals(wait, flow.checkpoint());
        flow.load(); flow.elapseTick(); assertEquals(Long.MAX_VALUE - 1, flow.checkpoint().sleepTicks());
        var zero = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program(), inputs(0), LIMITS.hardware());
        zero.advance(100); assertEquals(FlowRuntime.Status.WAITING_WRITE, zero.checkpoint().status());
        var negative = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program(), inputs(-1), LIMITS.hardware());
        negative.advance(100); assertEquals(FlowRuntime.Status.FAILED, negative.checkpoint().status());
        assertEquals(ComputationException.Code.INVALID_ARGUMENT, negative.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(0, negative.checkpoint().cursor());
        var wrong = new FlowProgram(Map.of("ticks", DataType.Scalar.TEXT), List.of(new FlowProgram.Sleep("sleep", "ticks")));
        assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> wrong.validate(LIMITS.hardware())).code());
    }
    @Test void sleepContinuationIsChargedAndForgedRemainingTimeIsRejected() {
        var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program(), inputs(3), LIMITS.hardware());
        var initial = flow.checkpoint(); var usage = program().expressions().compile(LIMITS.hardware()).usage();
        long initialUnits = initial.controlUnits() + usage.constantUnits() + 2 * usage.initialMemoryUnits();
        var tight = new FlowRuntime(initial.computer(), initial.flow(), program(), inputs(3), new ResourceLimits(100, initialUnits, 100));
        tight.advance(100);
        assertEquals(FlowRuntime.Status.FAILED, tight.checkpoint().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, tight.checkpoint().diagnostic().orElseThrow().code());
        flow.advance(100); var s = flow.checkpoint();
        assertTrue(s.controlUnits() > initial.controlUnits());
        for (long remaining : List.of(0L, 4L)) {
            var invalid = new FlowRuntime.Checkpoint(s.computer(), s.flow(), s.cursor(), s.status(), s.locals(), s.work(), s.paused(),
                s.expression(), s.diagnostic(), remaining);
            assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class,
                () -> FlowRuntime.restore(program(), invalid, LIMITS.hardware())).code());
        }
        var invalid = new FlowRuntime.Checkpoint(s.computer(), s.flow(), 1, FlowRuntime.Status.SLEEPING, s.locals(), 2, false,
            Optional.empty(), Optional.empty(), 1);
        assertThrows(ComputationException.class, () -> FlowRuntime.restore(program(), invalid, LIMITS.hardware()));
    }
}
