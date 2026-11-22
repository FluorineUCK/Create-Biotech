package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowCorrelationTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(16, 32768, 1);
    @Test void hostSaveLoadAndResetKeepAlreadyIssuedTagsButStartANewInvocationSpace() {
        var limits = ComputerFlowHostTest.LIMITS;
        var program = new FlowProgram(Map.of("request", DataType.Scalar.TEXT), List.of(
            new FlowProgram.Forever("repeat"), new FlowProgram.Correlation("tag", "request"), new FlowProgram.EndForever("again")));
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(program, new DataValue.RecordValue(Map.of("request", new DataValue.TextValue(""))), limits, true);
        host.step(limits, true); host.step(limits, true);
        var issued = host.flowSnapshot().orElseThrow(); var raw = host.write();
        assertEquals(12, ((net.minecraft.nbt.CompoundTag)raw).getInt(FlowBlockNbtCodec.MARKER));
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(limits, true);
        assertEquals(issued, restored.flowSnapshot().orElseThrow());
        restored.unload(); restored.tick(limits, false); assertEquals(raw, restored.write());
        restored.tick(limits, true); restored.step(limits, true); restored.step(limits, true); restored.step(limits, true);
        var next = restored.flowSnapshot().orElseThrow();
        assertNotEquals(issued.state().locals().field("request"), next.state().locals().field("request"),
            "A loop returning to the same instruction still generates a fresh tag");
        restored.reset(limits, true); restored.step(limits, true); restored.step(limits, true);
        assertNotEquals(issued.state().locals().field("request"), restored.flowSnapshot().orElseThrow().state().locals().field("request"));
        var downgraded = ((net.minecraft.nbt.CompoundTag)raw).copy(); downgraded.putInt(FlowBlockNbtCodec.MARKER, 11);
        assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(downgraded, limits.archive()));
        var snapshot = new FlowNbtCodec.Snapshot(issued.program(), issued.state());
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)ValueNbtCodec.decode(FlowNbtCodec.encode(snapshot, limits.archive()), limits.archive())).fields());
        fields.put("version", new DataValue.IntegerValue(12));
        assertThrows(ArchiveException.class, () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), limits.archive()), limits.archive()));
    }
    @Test void insufficientMemoryDoesNotPublishAHalfGeneratedTag() {
        var empty = new DataValue.RecordValue(Map.of());
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.Correlation("tag", "request")));
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, empty, LIMITS);
        var usage = program.expressions().compile(LIMITS).usage();
        long available = runtime.checkpoint().controlUnits() + usage.constantUnits() + 2 * usage.initialMemoryUnits();
        var tight = FlowRuntime.restore(program, runtime.checkpoint(), new ResourceLimits(16, available, 1));
        tight.load(); tight.advance(1);
        assertEquals(FlowRuntime.Status.FAILED, tight.checkpoint().status());
        assertEquals(empty, tight.checkpoint().locals());
        assertEquals(0, tight.checkpoint().operation());
    }
    @Test void correlationIsLocalBudgetedAndStableAcrossPauseAndRestoreButChangesForNewCallsAndRuns() {
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.Correlation("first", "first"),
            new FlowProgram.Correlation("second", "second")));
        var computer = UUID.randomUUID(); var run = UUID.randomUUID();
        var runtime = new FlowRuntime(computer, run, program, new DataValue.RecordValue(Map.of()), LIMITS);
        runtime.setPaused(true); assertEquals(0, runtime.advance(1));
        assertTrue(runtime.checkpoint().locals().fields().isEmpty());
        runtime.setPaused(false); assertEquals(1, runtime.advance(1));
        var first = runtime.checkpoint().locals().field("first");
        assertInstanceOf(DataValue.TextValue.class, first); assertEquals(1, runtime.checkpoint().work());
        var restored = FlowRuntime.restore(FlowProgramCodec.decode(FlowProgramCodec.encode(program)), runtime.checkpoint(), LIMITS);
        assertEquals(0, restored.advance(1)); restored.load(); restored.advance(1);
        assertEquals(FlowRuntime.Status.COMPLETE, restored.checkpoint().status());
        assertEquals(first, restored.checkpoint().locals().field("first"));
        assertNotEquals(first, restored.checkpoint().locals().field("second"));
        var fresh = new FlowRuntime(computer, UUID.randomUUID(), program, new DataValue.RecordValue(Map.of()), LIMITS);
        fresh.advance(1); assertNotEquals(first, fresh.checkpoint().locals().field("first"));
        assertThrows(ComputationException.class, () -> program.validate(new ResourceLimits(1, 32768, 1)));
    }
}
