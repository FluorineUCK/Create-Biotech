package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReaderSamplingProtocolTest {
    private static final DataValue.RecordValue ADDRESS = record(Map.of("x", integer(1), "y", integer(64), "z", integer(2)));
    private static final UUID DEVICE = UUID.randomUUID(), SESSION = UUID.randomUUID();
    private static final Set<String> ALLOWED = Set.of(BuiltinReadComponents.BLOCK);
    @Test void onlyAnAuthorizedCurrentSessionSamplesExplicitlyAllowedComponents() {
        var calls = new AtomicInteger();
        var protocol = new ReaderSamplingProtocol(DEVICE, SESSION, ADDRESS, ALLOWED);
        var sample = command("sample", record(Map.of("device", text(DEVICE.toString()), "session", text(SESSION.toString()),
            "components", new DataValue.ListValue(List.of(text(BuiltinReadComponents.BLOCK))))));
        var denied = protocol.respond(false, sample, ids -> { calls.incrementAndGet(); return record(Map.of()); }).orElseThrow();
        assertEquals(text("denied"), denied.field("code")); assertFalse(denied.fields().containsKey("state")); assertEquals(0, calls.get());
        var reply = protocol.respond(true, sample, ids -> {
            assertEquals(ALLOWED, ids); calls.incrementAndGet(); return record(Map.of("frozen", integer(15)));
        }).orElseThrow();
        assertEquals(text("ok"), reply.field("code")); assertEquals(integer(15), ((DataValue.RecordValue)reply.field("value")).field("frozen"));
        assertEquals(1, calls.get());
    }
    static DataValue.RecordValue command(String operation, DataValue.RecordValue args) {
        return record(Map.of("kind", text("reader_command"), "version", integer(1), "target", ADDRESS,
            "request", text("sample-1"), "operation", text(operation), "arguments", args));
    }
    @Test void staleSessionsUnopenedComponentsAndMalformedCommandsNeverSample() {
        var protocol = new ReaderSamplingProtocol(DEVICE, SESSION, ADDRESS, ALLOWED);
        var base = Map.<String, DataValue>of("device", text(DEVICE.toString()), "session", text(SESSION.toString()),
            "components", new DataValue.ListValue(List.of(text(BuiltinReadComponents.BLOCK))));
        var stale = new LinkedHashMap<>(base); stale.put("session", text(UUID.randomUUID().toString()));
        var forbidden = new LinkedHashMap<>(base); forbidden.put("components", new DataValue.ListValue(List.of(text(BuiltinReadComponents.SERIALIZED_DATA))));
        var repeated = new LinkedHashMap<>(base); repeated.put("components", new DataValue.ListValue(List.of(text(BuiltinReadComponents.BLOCK), text(BuiltinReadComponents.BLOCK))));
        for (var entry : Map.of("stale_session", stale, "component_denied", forbidden, "invalid_command", repeated).entrySet()) {
            var result = protocol.respond(true, command("sample", record(entry.getValue())), ids -> { fail("Must not read target"); return null; }).orElseThrow();
            assertEquals(text(entry.getKey()), result.field("code")); assertFalse(result.fields().containsKey("value"));
        }
        var status = protocol.respond(true, command("status", record(Map.of())), ids -> { fail("Query is not a sample"); return null; }).orElseThrow();
        assertEquals(text("ok"), status.field("code")); assertFalse(status.fields().containsKey("value"));
        var oversized = ReaderSamplingProtocol.failure(status, "payload_too_large");
        assertEquals(status.field("request"), oversized.field("request")); assertEquals(text("payload_too_large"), oversized.field("code"));
        assertFalse(oversized.fields().containsKey("state"));
    }
    @Test void newlyRegisteredTagFactsAreNeverImplicitlyAddedToAnOldGrant() {
        var old = new ReaderSamplingProtocol(DEVICE,SESSION,ADDRESS,ALLOWED);
        var args = record(Map.of("device",text(DEVICE.toString()),"session",text(SESSION.toString()),
            "components",new DataValue.ListValue(List.of(text(BuiltinReadComponents.INVENTORY_ITEM_TAGS)))));
        var sample=command("sample",args);
        assertEquals(text("component_denied"),old.respond(true,sample,ids->{fail("Old grant cannot read new component");return null;}).orElseThrow().field("code"));
        var explicit=new ReaderSamplingProtocol(DEVICE,SESSION,ADDRESS,Set.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
        var copied=new DataValue.ListValue(List.of(record(Map.of("slot",integer(0),"item",text("minecraft:stone"),
            "tags",new DataValue.ListValue(List.of(text("c:stones")))))));
        var reply=explicit.respond(true,sample,ids->{assertEquals(Set.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS),ids);return record(Map.of(BuiltinReadComponents.INVENTORY_ITEM_TAGS,copied));}).orElseThrow();
        assertEquals(text("ok"),reply.field("code"));assertEquals(copied,((DataValue.RecordValue)reply.field("value")).field(BuiltinReadComponents.INVENTORY_ITEM_TAGS));
    }

    static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
