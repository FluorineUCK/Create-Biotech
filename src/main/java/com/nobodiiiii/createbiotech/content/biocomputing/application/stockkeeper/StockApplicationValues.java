package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplication.*;

/** Canonical public value layout, also used for exact occurrence-based retained-value accounting. */
final class StockApplicationValues {
    private StockApplicationValues() {}
    // Representation ceilings, NOT hardware/gameplay capacities. Hosts must supply smaller balance limits.
    static final ArchiveLimits FORMAT = new ArchiveLimits(262144, 1048576, 4194304);
    static final int MAX_MEMBERS = 4096, MAX_TEXT = 65536;
    static DataValue.RecordValue record(Object... pairs) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i=0; i<pairs.length; i+=2) fields.put((String)pairs[i], (DataValue)pairs[i+1]);
        return new DataValue.RecordValue(fields);
    }
    static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
    static DataValue.IntegerValue number(long n) { return new DataValue.IntegerValue(n); }
    static DataValue.RecordValue application(Application a) { return record("computer",text(a.computer().toString()),"program",text(a.program().toString())); }
    static DataValue.RecordValue fence(Fence f) { return record("device",text(f.device().toString()),"session",text(f.session().toString()),"revision",number(f.revision())); }
    static DataValue.RecordValue entry(Entry e) { return record("key",text(e.key()),"title",text(e.title()),"unit",text(e.unit()),"icon",e.icon(),"data",e.data()); }
    static DataValue.ListValue entries(List<Entry> entries) { return new DataValue.ListValue(entries.stream().map(e -> (DataValue)entry(e)).toList()); }
    static DataValue.RecordValue request(Request r) {
        return record("fence",fence(r.fence()),"sequence",number(r.sequence()),"address",text(r.address()),
            "kind",text(r.kind()),"order",r.nativeOrder().<DataValue>map(v -> v).orElse(DataValue.NullValue.INSTANCE),"selections",
            new DataValue.ListValue(r.selections().stream().map(s -> (DataValue)record("key",text(s.key()),"quantity",number(s.quantity()))).toList()));
    }
    static DataValue.RecordValue input(Input i) {
        return record("device",text(i.device().toString()),"sequence",number(i.sequence()),"application",application(i.application()),
            "player",text(i.player().toString()),"request",request(i.request()),"items",new DataValue.ListValue(i.items().stream()
                .map(item -> (DataValue)record("entry",entry(item.entry()),"quantity",number(item.quantity()))).toList()));
    }
    static DataValue.RecordValue state(State s) {
        return record("version",number(2),"device",text(s.device().toString()),"session",text(s.session().toString()),"revision",number(s.revision()),
            "application",s.application().<DataValue>map(StockApplicationValues::application).orElse(DataValue.NullValue.INSTANCE),
            "open",new DataValue.BooleanValue(s.open()),"next_sequence",number(s.nextSequence()),"catalog",entries(s.catalog()),
            "inputs",new DataValue.ListValue(s.inputs().stream().map(i -> (DataValue)input(i)).toList()));
    }
    static void validateEntries(List<Entry> entries, int maxText) {
        var keys = new HashSet<String>();
        for (var e : entries) {
            if (e.key().isBlank() || e.title().isBlank() || e.unit().isBlank() || !keys.add(e.key())) throw new IllegalArgumentException("invalid_catalog");
            var icon = e.icon().fields();
            if (!icon.keySet().equals(Set.of("item","count","components"))
                || !(icon.get("item") instanceof DataValue.TextValue item) || !item.value().matches("[a-z0-9_.-]+:[a-z0-9/._-]+")
                || !(icon.get("count") instanceof DataValue.IntegerValue count) || count.value() < 1
                || !(icon.get("components") instanceof DataValue.NbtValue nbt) || nbt.tagId() != 10) throw new IllegalArgumentException("invalid_icon");
            var value = entry(e);
            if (value.localReferences() != 0) throw new IllegalArgumentException("nonportable");
            checkText(value, maxText);
        }
    }
    static void checkText(DataValue root, int max) {
        var pending = new ArrayDeque<DataValue>(); pending.push(root);
        var seen = Collections.newSetFromMap(new IdentityHashMap<DataValue,Boolean>());
        while (!pending.isEmpty()) {
            var v = pending.pop(); if (!seen.add(v)) continue;
            switch (v) {
                case DataValue.LocalReference ignored -> throw new IllegalArgumentException("nonportable");
                case DataValue.TextValue t -> textLength(t.value(),max);
                case DataValue.ErrorValue e -> { textLength(e.code(),max); textLength(e.message(),max); }
                case DataValue.NbtValue n -> pending.push(n.value());
                case DataValue.RecipeValue r -> pending.push(r.projection());
                case DataValue.ListValue l -> l.values().forEach(pending::push);
                case DataValue.RecordValue r -> r.fields().forEach((k,val)->{textLength(k,max);pending.push(val);});
                case DataValue.MapValue m -> m.entries().forEach((k,val)->{textLength(k,max);pending.push(val);});
                default -> { }
            }
        }
    }
    static void textLength(String s,int max) { if (s.length() > max) throw new IllegalArgumentException("capacity"); }
}
