package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;

class ProductionPortProtocolTest {
    private static final UUID DEVICE = new UUID(1, 2), SESSION = new UUID(3, 4), SOURCE = new UUID(5, 6);
    private static final DataValue.RecordValue TARGET = record(Map.of("x", integer(1), "y", integer(2), "z", integer(3)));
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    static DataValue.RecordValue record(Map<String, DataValue> value) { return new DataValue.RecordValue(value); }
    static DataValue.RecordValue frame(String request, String operation, DataValue.RecordValue arguments) {
        return record(Map.of("kind", text("production_command"), "version", integer(1), "target", TARGET,
            "request", text(request), "operation", text(operation), "arguments", arguments));
    }
    @Test void publicSubmitAndPollUseOneDurableIdentityAndNeverTreatDispatchAsProductionCompletion() {
        var store = new Journal() {
            Snapshot state = Snapshot.empty();
            public Snapshot load() { return state; }
            public boolean commit(long expected, Snapshot next) { if (state.revision() != expected) return false; state = next; return true; }
        };
        var backend = new Backend() {
            public boolean authorized(UUID id) { return id.equals(SOURCE); }
            public boolean supports(String extension, int version) { return extension.equals("test") && version == 1; }
            public Preparation prepare(UUID source, DataValue.RecordValue request) { return Preparation.ready(new Plan("test", 1, List.of(request))); }
            public Dispatch dispatch(Operation operation, DataValue.RecordValue command) { return Dispatch.accepted(record(Map.of("native", integer(12)))); }
            public Observation observe(Operation operation, DataValue.RecordValue receipt) { return Observation.pending(); }
        };
        var service = new ProductionOrderService(store, backend, new Limits(4, 4, 4096));
        var protocol = new ProductionPortProtocol(DEVICE, SESSION, 1, TARGET, service);
        var data = record(Map.of("items", new DataValue.ListValue(List.of())));
        var command = frame("submit-attempt-1", "submit", record(Map.of("device", text(DEVICE.toString()), "session", text(SESSION.toString()), "order", text("job-1"), "data", data)));
        assertEquals(text("denied"), protocol.respond(null, command).orElseThrow().field("code"));
        assertTrue(store.state.orders().isEmpty());
        var denied = protocol.respond(null, frame("q", "status", record(Map.of()))).orElseThrow();
        assertFalse(denied.fields().containsKey("state"));
        var invalid = new LinkedHashMap<>(command.fields()); invalid.put("source", text(SOURCE.toString()));
        assertEquals(text("invalid_command"), protocol.respond(SOURCE, record(invalid)).orElseThrow().field("code"));
        assertTrue(store.state.orders().isEmpty());
        var accepted = protocol.respond(SOURCE, command).orElseThrow();
        assertEquals(text("accepted"), accepted.field("code"));
        assertEquals(text("queued"), accepted.field("phase"));
        assertEquals(text("existing"), protocol.respond(SOURCE, command).orElseThrow().field("code"));
        var repeated = new LinkedHashMap<>(command.fields()); repeated.put("request", text("submit-attempt-2"));
        var second = protocol.respond(SOURCE, record(repeated)).orElseThrow();
        assertEquals(text("existing"), second.field("code"));
        assertEquals(text("submit-attempt-2"), second.field("request"));
        assertEquals(text("job-1"), second.field("order"));
        assertEquals(1, store.state.orders().size());
        service.advance();
        var query = frame("poll-1", "order", record(Map.of("order", text("job-1"))));
        var result = protocol.respond(SOURCE, query).orElseThrow();
        assertEquals(text("waiting"), result.field("phase"));
        assertEquals(text("poll-1"), result.field("request"));
        assertEquals(text("job-1"), result.field("order"));
        var restored = new ProductionPortProtocol(DEVICE, UUID.randomUUID(), 1, TARGET,
            new ProductionOrderService(store, backend, new Limits(4, 4, 4096)));
        assertEquals(text("session_changed"), restored.respond(SOURCE, command).orElseThrow().field("code"));
        assertEquals(text("waiting"), restored.respond(SOURCE, query).orElseThrow().field("phase"));
        assertEquals(1, store.state.orders().size());
    }
    @Test void archiveFailureIsNotAnEmptySuccessfulReplyAndDoesNotLeakToUnverifiedSenders() {
        var request = frame("poll", "order", record(Map.of("order", text("job-1"))));
        var failed = ProductionPortProtocol.unavailable(SOURCE, TARGET, request, "storage_unavailable").orElseThrow();
        assertEquals(text("storage_unavailable"), failed.field("code"));
        assertEquals(text("poll"), failed.field("request"));
        assertFalse(failed.fields().containsKey("phase"));
        assertEquals(text("denied"), ProductionPortProtocol.unavailable(null, TARGET, request, "storage_unavailable").orElseThrow().field("code"));
        assertTrue(ProductionPortProtocol.unavailable(SOURCE, record(Map.of()), request, "storage_unavailable").isEmpty());
        assertTrue(ProductionPortProtocol.unavailable(SOURCE, TARGET, frame("bad\uD800", "status", record(Map.of())), "storage_unavailable").isEmpty());
    }
}
