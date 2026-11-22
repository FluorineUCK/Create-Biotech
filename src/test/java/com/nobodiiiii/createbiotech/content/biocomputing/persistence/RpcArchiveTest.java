package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.obj;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RpcArchiveTest {
    private static final UUID OWNER = new UUID(0, 1), EVENT = new UUID(0, 2);
    private static final RequestCorrelation KEY = new RequestCorrelation(OWNER, EVENT, 0);
    private static final RpcState.Pending PENDING = new RpcState.Pending(KEY, "results");
    private static final DataValue NULL = DataValue.NullValue.INSTANCE;

    @Test void literalSchemaPinsWaitingAndActiveRequestFieldsWithoutCurrentEncoder() {
        var literal = obj("version", 1, "pending", obj("search", literalPending()), "staged", obj(),
            "request", obj("correlation", literalCorrelation(), "return_endpoint", "source"), "reply", NULL);
        var expected = new RpcState(Map.of("search", PENDING), Map.of(), Optional.of(new RequestContext(KEY, "source")), Optional.empty());
        assertEquals(expected, RpcArchiveCodec.decode(literal));
        var current = replace(replace(literal, "version", new DataValue.IntegerValue(2)),
            "pending", obj("search", replace(literalPending(), "continuation", NULL)));
        assertEquals(current, RpcArchiveCodec.encode(expected));
        assertEquals(expected.storageUnits(), current.storageUnits());
    }

    @Test void pendingStagedAndActiveReplyRoundTripThroughBoundedValueNbt() {
        var next = new RpcState.Pending(new RequestCorrelation(OWNER, new UUID(0, 3), 1), "details");
        var running = new RpcState(Map.of("search", PENDING), Map.of("search", next),
            Optional.empty(), Optional.of(new ReplyContext("search", PENDING)));
        var encoded = RpcArchiveCodec.encode(running);
        var tag = ValueNbtCodec.encode(encoded, ValueNbtCodecTest.LIMITS);
        assertEquals(running, RpcArchiveCodec.decode(ValueNbtCodec.decode(tag, ValueNbtCodecTest.LIMITS)));
        assertEquals(RpcState.empty(), RpcArchiveCodec.decode(RpcArchiveCodec.encode(RpcState.empty())));
        assertEquals(Map.of("search", next), RpcArchiveCodec.decode(encoded).commit().pending());
    }

    @Test void malformedVersionsFieldsCorrelationsAndContextsRejectRatherThanReset() {
        var base = obj("version", 1, "pending", obj(), "staged", obj(), "request", NULL, "reply", NULL);
        assertEquals(ArchiveException.Code.UNSUPPORTED_VERSION,
            assertThrows(ArchiveException.class, () -> RpcArchiveCodec.decode(replace(base, "version", new DataValue.IntegerValue(3)))).code());
        assertThrows(ArchiveException.class, () -> RpcArchiveCodec.decode(replace(base, "unknown", NULL)));
        assertThrows(ArchiveException.class, () -> RpcArchiveCodec.decode(replace(base, "pending", obj("a", literalPending(), "b", literalPending()))));
        var malformed = obj("correlation", replace(literalCorrelation(), "ordinal", new DataValue.IntegerValue(-1)), "reply_entry", "results");
        assertThrows(ArchiveException.class, () -> RpcArchiveCodec.decode(replace(base, "pending", obj("search", malformed))));
        var request = obj("correlation", literalCorrelation(), "return_endpoint", "source");
        var reply = obj("slot", "search", "pending", literalPending());
        assertThrows(ArchiveException.class, () -> RpcArchiveCodec.decode(replace(replace(base, "request", request), "reply", reply)));
        assertThrows(ArchiveException.class, () -> RpcArchiveCodec.decode(replace(base, "pending", new DataValue.ListValue(List.of()))));
    }

    @Test void literalCrossMapCollisionIsRejectedBeforeItCanBeRestoredForExecution() {
        var malformed = obj("version", 1, "pending", obj("a", literalPending()), "staged", obj("b", literalPending()),
            "request", NULL, "reply", NULL);
        assertEquals(ArchiveException.Code.INVALID_FORMAT,
            assertThrows(ArchiveException.class, () -> RpcArchiveCodec.decode(malformed)).code());
    }

    private static DataValue.RecordValue literalCorrelation() {
        return obj("requester", "00000000-0000-0000-0000-000000000001", "event_id", "00000000-0000-0000-0000-000000000002", "ordinal", 0);
    }
    private static DataValue.RecordValue literalPending() { return obj("correlation", literalCorrelation(), "reply_entry", "results"); }
    private static DataValue.RecordValue replace(DataValue.RecordValue original, String key, DataValue value) {
        var fields = new LinkedHashMap<>(original.fields()); fields.put(key, value); return new DataValue.RecordValue(fields);
    }
}
