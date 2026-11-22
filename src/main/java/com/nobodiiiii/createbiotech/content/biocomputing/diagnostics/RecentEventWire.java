package com.nobodiiiii.createbiotech.content.biocomputing.diagnostics;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;

/** A separate bounded status envelope: unrelated RAM cannot consume or enter the log response. */
public final class RecentEventWire {
    private RecentEventWire() {}
    public record Entry(long sequence, long tick, RecentEventLog.Kind kind, String code, String subject) {}
    public record View(List<Entry> entries, long omitted, long sequence) {
        public View { entries = List.copyOf(entries); }
    }
    public static DataValue.RecordValue statusValue(DataValue.RecordValue log, ArchiveLimits limits) {
        view(log); // Reject malformed metadata rather than presenting an apparently empty log.
        var entries = ((DataValue.ListValue)log.field("entries")).values();
        long omitted = integer(log.field("omitted"));
        var complete = suffix(log, entries, entries.size(), omitted);
        try { ProgramEditorWire.encodeValue(complete, limits); return complete; }
        catch (IllegalArgumentException tooLarge) { /* Only oversized logs need the bounded suffix search. */ }
        var empty = suffix(log, entries, 0, omitted);
        ProgramEditorWire.encodeValue(empty, limits); // Even the omission notice must fit.
        int low = 0, high = entries.size();
        while (low < high) {
            int count = low + (high - low + 1) / 2;
            try { ProgramEditorWire.encodeValue(suffix(log, entries, count, omitted), limits); low = count; }
            catch (IllegalArgumentException tooLarge) { high = count - 1; }
        }
        return suffix(log, entries, low, omitted);
    }
    private static DataValue.RecordValue suffix(DataValue.RecordValue log, List<DataValue> entries, int count, long omitted) {
        var cropped = new DataValue.RecordValue(Map.of("entries", new DataValue.ListValue(entries.subList(entries.size() - count, entries.size())),
            "omitted", new DataValue.IntegerValue(Math.addExact(omitted, entries.size() - count)), "sequence", log.field("sequence")));
        return new DataValue.RecordValue(Map.of("recent_events", cropped));
    }
    public static View readStatus(DataValue value) {
        var status = record(value); exact(status, "recent_events"); return view(record(status.field("recent_events")));
    }
    private static View view(DataValue.RecordValue log) {
        exact(log, "entries", "omitted", "sequence");
        long omitted = integer(log.field("omitted")), sequence = integer(log.field("sequence")), previous = 0;
        if (!(log.field("entries") instanceof DataValue.ListValue list) || list.values().size() > RecentEventLog.MAX_CAPACITY) throw invalid();
        if (omitted > sequence || sequence - omitted != list.values().size()) throw invalid();
        previous = omitted;
        var entries = new ArrayList<Entry>();
        for (var value : list.values()) {
            var entry = record(value); exact(entry, "tick", "sequence", "kind", "code", "subject");
            long id = integer(entry.field("sequence"));
            if (id != previous + 1 || id > sequence) throw invalid(); previous = id;
            RecentEventLog.Kind kind;
            try { kind = RecentEventLog.Kind.valueOf(text(entry.field("kind")).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException error) { throw invalid(); }
            entries.add(new Entry(id, integer(entry.field("tick")), kind, text(entry.field("code")), text(entry.field("subject"))));
        }
        return new View(entries, omitted, sequence);
    }
    private static long integer(DataValue value) {
        if (value instanceof DataValue.IntegerValue number && number.value() >= 0) return number.value(); throw invalid();
    }
    private static String text(DataValue value) {
        if (value instanceof DataValue.TextValue text && text.value().codePointCount(0, text.value().length()) <= RecentEventLog.TEXT_CODE_POINTS + 1
            && !text.value().matches("(?s).*[\\p{Cntrl}\\u00a7].*")) return text.value(); throw invalid();
    }
    private static DataValue.RecordValue record(DataValue value) { if (value instanceof DataValue.RecordValue record) return record; throw invalid(); }
    private static void exact(DataValue.RecordValue record, String... keys) { if (!record.fields().keySet().equals(Set.of(keys))) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid recent event status"); }
}
