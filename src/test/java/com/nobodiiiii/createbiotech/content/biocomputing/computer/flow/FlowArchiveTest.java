package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusFlowTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowArchiveTest {
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(100000, 1000000, 1000000);
    @Test void everyReadComputeWriteBoundaryRoundTripsWithoutAdvancingOrRepeatingTheTransfer() {
        var sender = writer(); var receiver = runtime(calculator(), EMPTY);
        sender = roundTrip(sender); receiver = roundTrip(receiver);
        sender.advance(1); receiver.advance(1);
        sender = roundTrip(sender); receiver = roundTrip(receiver);
        assertEquals(XBusExchange.Status.EXCHANGED, exchange(sender, receiver).commit());
        sender = roundTrip(sender); receiver = roundTrip(receiver);
        assertEquals(XBusExchange.Status.NOT_READY, exchange(sender, receiver).status());
        for (int i = 0; receiver.checkpoint().status() != FlowRuntime.Status.WAITING_WRITE; i++) {
            assertTrue(i < 50); receiver.advance(1); receiver = roundTrip(receiver);
        }
        assertEquals(packet(19), receiver.checkpoint().locals().field("out"));
        assertEquals("return", receiver.sourceId().orElseThrow());
    }
    static FlowRuntime roundTrip(FlowRuntime runtime) {
        var before = new FlowNbtCodec.Snapshot(runtime.program(), runtime.checkpoint());
        var decoded = FlowNbtCodec.decode(FlowNbtCodec.encode(before, ARCHIVE), ARCHIVE);
        assertEquals(before, decoded);
        var restored = FlowRuntime.restore(decoded.program(), decoded.state(), LIMITS);
        assertFalse(restored.loaded()); assertEquals(0, restored.advance(1)); restored.load();
        return restored;
    }
    @Test void pauseAndReadableWaitSurviveTheArchiveAndNeitherConsumesData() {
        var receiver = runtime(new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("slx", "x1"))), EMPTY);
        receiver.advance(1); receiver.setPaused(true); receiver = roundTrip(receiver);
        assertEquals(FlowRuntime.Status.WAITING_READABLE, receiver.checkpoint().status());
        assertTrue(receiver.checkpoint().paused()); assertEquals(0, receiver.advance(1));
        var sender = writer(); sender.advance(1);
        assertEquals(XBusExchange.Status.PAUSED, exchange(sender, receiver).status());
        receiver.setPaused(false);
        assertEquals(XBusExchange.Status.READABLE, exchange(sender, receiver).commit());
        receiver = roundTrip(receiver);
        assertEquals(FlowRuntime.Status.COMPLETE, receiver.checkpoint().status());
        assertEquals(FlowRuntime.Status.WAITING_WRITE, sender.checkpoint().status());
        assertEquals(EMPTY, receiver.checkpoint().locals());
    }
    @Test void unsupportedSchemaUnknownFieldsAndNonBooleanPauseAreRejected() {
        var flow = reader();
        var encoded = FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(flow.program(), flow.checkpoint()), ARCHIVE);
        var data = (DataValue.RecordValue) ValueNbtCodec.decode(encoded, ARCHIVE);
        var fields = new LinkedHashMap<>(data.fields()); fields.put("version", new DataValue.IntegerValue(3));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE)).code(),
            "Time-aware schema requires its sleep counter even when zero");
        fields.put("version", new DataValue.IntegerValue(4));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE)).code(),
            "Signal-aware schema requires its output state and sleep counter");
        fields.put("version", new DataValue.IntegerValue(5));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE)).code(),
            "Operation-aware schema requires its independent operation and inherited state fields");
        fields.put("version", new DataValue.IntegerValue(6));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE)).code());
        fields.put("version", new DataValue.IntegerValue(7));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE)).code());
        fields.put("version", new DataValue.IntegerValue(8));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE)).code());
        fields.put("version", new DataValue.IntegerValue(14));
        assertEquals(ArchiveException.Code.INVALID_FORMAT, assertThrows(ArchiveException.class,
            () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE)).code());
        fields.put("version", new DataValue.IntegerValue(Integer.MAX_VALUE));
        assertEquals(ArchiveException.Code.UNSUPPORTED_VERSION, assertThrows(ArchiveException.class,
            () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE)).code());
        fields.put("version", new DataValue.IntegerValue(1)); fields.put("buffer", EMPTY);
        assertThrows(ArchiveException.class, () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE));
        fields.remove("buffer");
        var state = new LinkedHashMap<>(((DataValue.RecordValue) data.field("state")).fields());
        state.put("paused", new DataValue.IntegerValue(1)); fields.put("state", new DataValue.RecordValue(state));
        assertThrows(ArchiveException.class, () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields), ARCHIVE), ARCHIVE));
    }
}
