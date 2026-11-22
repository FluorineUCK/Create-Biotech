package com.nobodiiiii.createbiotech.content.biocomputing.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import org.junit.jupiter.api.Test;
import java.util.*;

class RecentEventWireTest {    @Test void malformedHistoryCountsAreRejectedInsteadOfPresentingAnApparentlyValidEmptyLog() {
        var root = new DataValue.RecordValue(java.util.Map.of("entries", new DataValue.ListValue(java.util.List.of()),
            "omitted", new DataValue.IntegerValue(9), "sequence", new DataValue.IntegerValue(0)));
        assertThrows(IllegalArgumentException.class, () -> RecentEventWire.readStatus(new DataValue.RecordValue(java.util.Map.of("recent_events", root))));
    }
    @Test void externalWiringLogsOnlyFaultChangesAndRealBindingRecoveryWithinTheExistingCapacity() {
        var log = new RecentEventLog(() -> 123L, () -> 3);
        log.observeWiring(Map.of("wire", "ready"));
        assertTrue(((DataValue.ListValue)log.value().field("entries")).values().isEmpty());
        log.observeWiring(Map.of("wire", "unreachable")); var fault = log.value();
        for (int i = 0; i < 100; i++) log.observeWiring(Map.of("wire", "unreachable"));
        assertEquals(fault, log.value(), "A persistent cable fault does not consume the log");
        log.observeWiring(Map.of("wire", "closed"));
        log.observeWiring(Map.of("wire", "ready")); log.observeWiring(Map.of("wire", "ready"));
        var entries = ((DataValue.ListValue)log.value().field("entries")).values().stream().map(DataValue.RecordValue.class::cast).toList();
        assertEquals(List.of("wiring_unreachable", "wiring_closed", "wiring_restored"), entries.stream().map(e -> ((DataValue.TextValue)e.field("code")).value()).toList());
        assertEquals(new DataValue.TextValue("recovered"), entries.getLast().field("kind"));
        log.observeWiring(Map.of("wire", "target_unloaded"));
        assertEquals(new DataValue.IntegerValue(1), log.value().field("omitted"));
        assertEquals(3, ((DataValue.ListValue)log.value().field("entries")).values().size());
    }
    @Test void removedDisabledOrReloadedDeclarationsCannotManufactureARecoveryOrRetainStaleDeduplication() {
        var log = new RecentEventLog(() -> 5L, () -> 32);
        log.observeWiring(Map.of("wire", "unreachable"));
        log.observeWiring(Map.of()); log.observeWiring(Map.of("wire", "ready"));
        assertEquals(1, ((DataValue.ListValue)log.value().field("entries")).values().size());
        log.observeWiring(Map.of("wire", "unreachable"));
        log.observeWiring(Map.of()); log.observeWiring(Map.of("wire", "unreachable"));
        assertEquals(3, ((DataValue.ListValue)log.value().field("entries")).values().size());
        log.clear(); log.observeWiring(Map.of("wire", "ready"));
        assertEquals(new DataValue.IntegerValue(0), log.value().field("sequence"));
        log.observeWiring(Map.of("bad§c\nname", "provider_failure"));
        var entry = (DataValue.RecordValue)((DataValue.ListValue)log.value().field("entries")).values().getFirst();
        assertEquals(new DataValue.TextValue("error"), entry.field("kind"));
        assertEquals(new DataValue.TextValue("badcname"), entry.field("subject"));
    }
    @Test void aLargeUnicodeLogReturnsTheNewestPacketSizedSuffixWithAnExplicitOmissionCount() {
        var limits = new ArchiveLimits(100000, 1000000, 10000000);
        var log = new RecentEventLog(() -> 123L, () -> 4096);
        for (int i = 0; i < 256; i++) log.append(RecentEventLog.Kind.ERROR, "error_" + i, new String(Character.toChars(0x4e00 + i)).repeat(96));
        var before = log.value();
        var status = RecentEventWire.statusValue(before, limits);
        var bytes = ProgramEditorWire.encodeValue(status, limits);
        assertTrue(bytes.length <= ProgramEditorWire.MAX_BYTES);
        var received = (DataValue.RecordValue)((DataValue.RecordValue)ProgramEditorWire.decodeValue(bytes, limits)).field("recent_events");
        var entries = ((DataValue.ListValue)received.field("entries")).values();
        assertFalse(entries.isEmpty()); assertTrue(entries.size() < 256);
        assertEquals(new DataValue.IntegerValue(256 - entries.size()), received.field("omitted"));
        assertEquals(new DataValue.IntegerValue(256), ((DataValue.RecordValue)entries.getLast()).field("sequence"));
        assertEquals(before, log.value(), "Packet cropping must not remove retained diagnostics or protocol state");
    }
}
