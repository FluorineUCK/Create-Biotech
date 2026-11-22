package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import java.util.*;

/** Client presentation of a complete server publication, never a routing authority. */
public record WiringStatusView(long view, long revision, String discovery, List<String> localPins, List<Pin> peers, List<Entry> entries) {
    public WiringStatusView { localPins = List.copyOf(localPins); peers = List.copyOf(peers); entries = List.copyOf(entries); }
    public record Pin(String endpoint, String domain, String pin, long x, long y, long z) {}
    public record Entry(String name, String localPin, Pin peer, String availability, boolean enabled) {}
    public static WiringStatusView decode(DataValue.RecordValue root) {
        var value = record(root.fields().get("wiring"), "view", "revision", "mode", "discovery", "local_pins", "peer_pins", "entries");
        long view = number(value.field("view")), revision = number(value.field("revision"));
        if (view <= 0 || revision < 0 || !text(value.field("mode")).equals("declarations_only")) throw invalid();
        var locals = list(value.field("local_pins")).stream().map(WiringStatusView::text).toList();
        new HardwarePins(locals, List.of());
        var peers = list(value.field("peer_pins")).stream().map(WiringStatusView::pin).toList();
        var entries = new ArrayList<Entry>(); var names = new HashSet<String>();
        for (var raw : list(value.field("entries"))) {
            if (!(raw instanceof DataValue.RecordValue candidate)) throw invalid();
            boolean legacy = !candidate.fields().containsKey("enabled");
            var entry = legacy ? record(raw, "name", "local_pin", "peer", "availability")
                : record(raw, "name", "local_pin", "peer", "availability", "enabled");
            if (!legacy && !(entry.field("enabled") instanceof DataValue.BooleanValue)) throw invalid();
            boolean enabled = !legacy && ((DataValue.BooleanValue)entry.field("enabled")).value();
            var name = text(entry.field("name")); new EndpointConfiguration(name, true);
            if (!names.add(name)) throw invalid();
            var local = text(entry.field("local_pin")); new HardwarePins(List.of(local), List.of());
            entries.add(new Entry(name, local, pin(entry.field("peer")), text(entry.field("availability")), enabled));
        }
        return new WiringStatusView(view, revision, text(value.field("discovery")), locals, peers, entries);
    }
    private static Pin pin(DataValue raw) {
        var value = record(raw, "endpoint", "domain", "pin", "position");
        String name = text(value.field("endpoint")), domain = text(value.field("domain")), pin = text(value.field("pin"));
        new EndpointConfiguration(name, true); new HardwarePins(List.of(pin), List.of());
        if (domain.isBlank()) throw invalid();
        var pos = record(value.field("position"), "x", "y", "z");
        return new Pin(name, domain, pin, coordinate(pos.field("x")), coordinate(pos.field("y")), coordinate(pos.field("z")));
    }
    private static long coordinate(DataValue value) {
        long number = number(value); if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw invalid(); return number;
    }
    private static DataValue.RecordValue record(DataValue value, String... fields) {
        if (!(value instanceof DataValue.RecordValue record) || !record.fields().keySet().equals(Set.of(fields))) throw invalid(); return record;
    }
    private static List<DataValue> list(DataValue value) { if (!(value instanceof DataValue.ListValue list)) throw invalid(); return list.values(); }
    private static long number(DataValue value) { if (!(value instanceof DataValue.IntegerValue number)) throw invalid(); return number.value(); }
    private static String text(DataValue value) { if (!(value instanceof DataValue.TextValue text)) throw invalid(); return text.value(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_wiring_snapshot"); }
}
