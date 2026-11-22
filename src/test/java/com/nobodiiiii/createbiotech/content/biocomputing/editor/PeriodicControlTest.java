package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class PeriodicControlTest {
    private static final ArchiveLimits LIMITS = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
    @Test void configurationUsesTypedNamedInputsAndStrictBoundedExistingWire() {
        var inputs = new DataValue.RecordValue(Map.of("literal", new DataValue.ErrorValue("unsupported", "data, not failure")));
        var config = new PeriodicControlData(Long.MAX_VALUE, inputs);
        byte[] bytes = ProgramEditorWire.encodeValue(config.encode(), LIMITS);
        assertEquals(config, PeriodicControlData.decode((DataValue.RecordValue)ProgramEditorWire.decodeValue(bytes, LIMITS)));
        assertThrows(IllegalArgumentException.class, () -> new PeriodicControlData(0, inputs));
        assertThrows(IllegalArgumentException.class, () -> PeriodicControlData.decode(new DataValue.RecordValue(Map.of("interval_ticks", new DataValue.DecimalValue(2), "inputs", inputs))));
        var extra = new LinkedHashMap<>(config.encode().fields()); extra.put("target", new DataValue.TextValue("not allowed"));
        assertThrows(IllegalArgumentException.class, () -> PeriodicControlData.decode(new DataValue.RecordValue(extra)));
        var request = new ProgramControlRequest(1, UUID.randomUUID(), 2, 3, ComputerControlSession.Action.CONFIGURE_PERIOD, bytes);
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try { request.write(wire); var read = new ProgramControlRequest(wire); assertEquals(request.action(), read.action()); assertArrayEquals(bytes, read.inputs()); }
        finally { wire.release(); }
        assertThrows(IllegalArgumentException.class, () -> new ProgramControlRequest(1, UUID.randomUUID(), 1, 1, ComputerControlSession.Action.READ_PERIOD, bytes));
    }
    @Test void periodicRepliesAreCompleteAndIndependentOfLargeRamAndDoNotMasqueradeAsCaptures() {
        var draft = new ProgramDraft(); String n = draft.add(ProgramDraftTest.number(1), 0, 0); draft.output("out", n);
        var ram = new DataValue.RecordValue(Map.of("huge", new DataValue.TextValue("x".repeat(40_000))));
        var machine = new MachineCheckpoint(ram, ComputationMachine.Status.IDLE, false, 0, 0, Optional.empty(), Optional.empty(), List.of());
        var schedule = new PeriodicSchedule(5, 3, false, new DataValue.RecordValue(Map.of("data", new DataValue.MapValue(Map.of("", DataValue.NullValue.INSTANCE)))));
        var snapshot = new ComputerRuntime.Snapshot(UUID.randomUUID(), draft.snapshot(), machine, Optional.empty(), Optional.empty(), Optional.of(schedule));
        var view = ComputerStatusView.periodic(snapshot);
        var answer = new ComputerControlSession.Answer("ok", 3, "", Optional.of(snapshot), Optional.empty(), Optional.of(view));
        var response = ProgramStatusResponse.fromAnswer(1, UUID.randomUUID(), 1, answer, LIMITS);
        assertEquals("ok", response.code());
        var decoded = (DataValue.RecordValue)ProgramEditorWire.decodeValue(response.status(), LIMITS);
        assertEquals(Set.of("periodic"), decoded.fields().keySet());
        assertEquals(schedule.inputs(), ((DataValue.RecordValue)decoded.field("periodic")).field("inputs"));
        assertFalse(((DataValue.RecordValue)ComputerStatusView.of(snapshot).field("periodic")).fields().containsKey("inputs"));
    }
}
