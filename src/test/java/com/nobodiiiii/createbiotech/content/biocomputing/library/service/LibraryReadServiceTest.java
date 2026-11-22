package com.nobodiiiii.createbiotech.content.biocomputing.library.service;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import java.util.*;
import org.junit.jupiter.api.Test;

class LibraryReadServiceTest {
    static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
    static DataValue.RecordValue obj(Object... pairs) {
        var map = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String)pairs[i], pairs[i+1] instanceof String s ? text(s) : (DataValue)pairs[i+1]);
        return new DataValue.RecordValue(map);
    }
    static LibraryServiceConfiguration config() {
        return new LibraryServiceConfiguration(Optional.of(new EndpointConfiguration("books", true)),
            Map.of("日志", LibraryServiceConfiguration.Access.READ_ONLY, "writable", LibraryServiceConfiguration.Access.READ_WRITE));
    }
    static String code(DataValue.RecordValue value) { return ((DataValue.TextValue)value.field("code")).value(); }
    static final class Source implements LibraryReadService.Source, LibraryReadService.Directory {
        int snapshots, reads; String failure;
        Map<String, LibraryEntry> entries = Map.of("reading", new LibraryEntry(LibraryEntry.Kind.DATA,
            obj("name", "sample", "count", new DataValue.IntegerValue(43))));
        public LibraryReadService.Directory snapshot() { snapshots++; if (failure != null) throw new LibraryBookAccess.Failure(failure); return this; }
        public String status(String name) { return switch (name) { case "日志" -> "ok"; case "writable" -> "duplicate_volume"; default -> "missing_volume"; }; }
        public Map<String, LibraryEntry> read(String name) { reads++; return entries; }
        public DataValue.RecordValue stat(String name, String entry) { throw new LibraryBookAccess.Failure("unversioned_medium"); }
    }
    @Test void defaultClosedAndUnconfiguredDoNotTouchTheWorld() {
        var source = new Source();
        assertEquals("closed", code(LibraryReadService.execute(LibraryServiceConfiguration.closed(), obj("op", "volumes"), source)));
        assertEquals(0, source.snapshots);
    }
    @Test void grantsRoundTripExactlyAndAreNotMutable() {
        var grants = new HashMap<String, LibraryServiceConfiguration.Access>(); grants.put(" A ", LibraryServiceConfiguration.Access.READ_ONLY);
        var c = new LibraryServiceConfiguration(config().endpoint(), grants); grants.clear();
        assertEquals(LibraryServiceConfiguration.Access.READ_ONLY, c.access(" A "));
        assertEquals(LibraryServiceConfiguration.Access.CLOSED, c.access("A"));
        assertEquals(c, LibraryServiceConfiguration.decode(c.value()));
        assertEquals(LibraryServiceConfiguration.closed(), LibraryServiceConfiguration.decode(LibraryServiceConfiguration.closed().value()));
        assertThrows(UnsupportedOperationException.class, () -> c.grants().clear());
        assertThrows(IllegalArgumentException.class, () -> LibraryServiceConfiguration.decode(obj("endpoint", DataValue.NullValue.INSTANCE, "grants", obj(), "extra", text("x"))));
    }
    @Test void listingShowsOnlyExplicitGrantsAndReportsDuplicateNotWinner() {
        var source = new Source();
        var result = LibraryReadService.execute(config(), obj("op", "volumes"), source);
        assertEquals("ok", code(result)); assertEquals(1, source.snapshots); assertEquals(0, source.reads);
        var rows = ((DataValue.ListValue)result.field("data")).values();
        assertEquals(2, rows.size());
        assertTrue(rows.contains(obj("name", "writable", "access", "READ_WRITE", "status", "duplicate_volume")));
        assertTrue(rows.contains(obj("name", "日志", "access", "READ_ONLY", "status", "ok")));
    }
    @Test void unauthorizedVolumeAndMalformedRequestsDoNotProbeTheWorld() {
        var source = new Source();
        assertEquals("denied", code(LibraryReadService.execute(config(), obj("op", "read", "volume", "private", "entry", "reading"), source)));
        assertEquals("invalid_request", code(LibraryReadService.execute(config(), obj("op", "read", "volume", "日志"), source)));
        assertEquals("invalid_request", code(LibraryReadService.execute(config(), obj("op", "volumes", "nbt", obj()), source)));
        assertEquals("unsupported", code(LibraryReadService.execute(config(), obj("op", "configure", "volume", "日志"), source)));
        assertEquals(0, source.snapshots);
    }
    @Test void valuesRemainTypedAndEntriesAreListedWithoutInstallingAnything() {
        var source = new Source();
        var result = LibraryReadService.execute(config(), obj("op", "read", "volume", "日志", "entry", "reading"), source);
        assertEquals(obj("code", "ok", "data", obj("kind", "DATA", "value", source.entries.get("reading").value())), result);
        assertEquals(obj("code", "ok", "data", new DataValue.ListValue(List.of(obj("name", "reading", "kind", "DATA")))),
            LibraryReadService.execute(config(), obj("op", "list", "volume", "日志"), source));
        assertThrows(UnsupportedOperationException.class, () -> ((DataValue.RecordValue)result.field("data")).fields().clear());
        assertEquals(obj("code", "denied", "data", DataValue.NullValue.INSTANCE),
            LibraryReadService.execute(config(), obj("op", "list", "volume", "private"), source));
    }
    @Test void missingEntryDuplicateAndUnavailableAreNotEmptySuccess() {
        var source = new Source();
        assertEquals("missing_entry", code(LibraryReadService.execute(config(), obj("op", "read", "volume", "日志", "entry", "no"), source)));
        assertEquals("duplicate_volume", code(LibraryReadService.execute(config(), obj("op", "list", "volume", "writable"), source)));
        assertEquals(1, source.reads);
        source.failure = "unloaded";
        assertEquals("unloaded", code(LibraryReadService.execute(config(), obj("op", "volumes"), source)));
    }
    @Test void evenWritableGrantDoesNotEnableWorldOrStorageMutation() {
        var source = new Source();
        for (var operation : List.of("put", "delete", "set_nbt", "install"))
            assertEquals("unsupported", code(LibraryReadService.execute(config(), obj("op", operation, "volume", "writable", "entry", "reading"), source)));
        assertEquals(0, source.snapshots); assertEquals(0, source.reads);
    }
    @Test void statChecksAuthorizationAndShapeBeforePhysicalLookupAndPreservesFailureReasons() {
        var source = new Source();
        var request = obj("op", "stat", "volume", "日志", "entry", "reading");
        assertEquals("closed", code(LibraryReadService.execute(LibraryServiceConfiguration.closed(), request, source)));
        assertEquals("denied", code(LibraryReadService.execute(config(), obj("op", "stat", "volume", "private", "entry", "reading"), source)));
        for (var invalid : List.of(obj("op", "stat", "volume", "日志"),
                obj("op", "stat", "volume", "日志", "entry", " "),
                obj("op", "stat", "volume", "日志", "entry", "x".repeat(129)),
                obj("op", "stat", "volume", "日志", "entry", "reading", "extra", "x")))
            assertEquals("invalid_request", code(LibraryReadService.execute(config(), invalid, source)));
        assertEquals(0, source.snapshots);
        assertEquals("unversioned_medium", code(LibraryReadService.execute(config(), request, source)));
        assertEquals("duplicate_volume", code(LibraryReadService.execute(config(), obj("op", "stat", "volume", "writable", "entry", "reading"), source)));
        source.failure = "unloaded";
        assertEquals("unloaded", code(LibraryReadService.execute(config(), request, source)));
        assertEquals(0, source.reads);
    }
}
