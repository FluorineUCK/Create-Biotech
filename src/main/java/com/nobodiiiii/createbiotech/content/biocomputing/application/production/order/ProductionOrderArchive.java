package com.nobodiiiii.createbiotech.content.biocomputing.application.production.order;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;

/** Versioned value document. Unknown/corrupt documents must be retained by the owning store, not reset. */
public final class ProductionOrderArchive {
    public static final int VERSION = 1;
    private static final int MATERIAL_VERSION = 2;
    private ProductionOrderArchive() {}
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    public static DataValue.RecordValue encode(Snapshot snapshot) {
        List<DataValue> rows = new ArrayList<>();
        for (var order : snapshot.orders().values()) {
            Map<String, DataValue> row = new LinkedHashMap<>();
            row.put("source", text(order.key().source().toString())); row.put("request_id", text(order.key().request().toString()));
            row.put("request", order.request()); row.put("extension", text(order.plan().extension()));
            row.put("extension_version", integer(order.plan().version()));
            row.put("steps", new DataValue.ListValue(new ArrayList<>(order.plan().steps())));
            row.put("step", integer(order.step())); row.put("phase", text(order.phase().name()));
            row.put("receipt", order.receipt().<DataValue>map(value -> value).orElse(DataValue.NullValue.INSTANCE));
            row.put("result", order.result()); row.put("diagnostic", text(order.diagnostic()));
            rows.add(new DataValue.RecordValue(row));
        }
        var root = new LinkedHashMap<>(Map.<String, DataValue>of("version", integer(snapshot.materialGrant().isPresent() ? MATERIAL_VERSION : VERSION),
            "revision", integer(snapshot.revision()), "cursor", integer(snapshot.cursor()), "orders", new DataValue.ListValue(rows)));
        snapshot.materialGrant().ifPresent(grant -> root.put("material_grant", integer(grant)));
        return new DataValue.RecordValue(root);
    }
    public static Snapshot decode(DataValue.RecordValue archive) {
        long version = number(archive.field("version"));
        OptionalLong materialGrant;
        if (version == VERSION) {
            fields(archive, "version", "revision", "cursor", "orders");
            materialGrant = OptionalLong.empty();
        } else if (version == MATERIAL_VERSION) {
            fields(archive, "version", "revision", "cursor", "orders", "material_grant");
            materialGrant = OptionalLong.of(number(archive.field("material_grant")));
        } else throw invalid();
        var orders = new LinkedHashMap<Key, Order>();
        for (var item : list(archive.field("orders"))) {
            var row = record(item);
            fields(row, "source", "request_id", "request", "extension", "extension_version", "steps", "step", "phase", "receipt", "result", "diagnostic");
            var key = new Key(uuid(row.field("source")), uuid(row.field("request_id")));
            var plan = new Plan(string(row.field("extension")), Math.toIntExact(number(row.field("extension_version"))),
                list(row.field("steps")).stream().map(ProductionOrderArchive::record).toList());
            var receipt = row.field("receipt") == DataValue.NullValue.INSTANCE ? Optional.<DataValue.RecordValue>empty()
                : Optional.of(record(row.field("receipt")));
            var order = new Order(key, record(row.field("request")), plan, Math.toIntExact(number(row.field("step"))),
                Phase.valueOf(string(row.field("phase"))), receipt, row.field("result"), string(row.field("diagnostic")));
            if (orders.putIfAbsent(key, order) != null) throw invalid();
        }
        return new Snapshot(number(archive.field("revision")), Math.toIntExact(number(archive.field("cursor"))), orders, materialGrant);
    }
    private static void fields(DataValue.RecordValue value, String... names) {
        if (!value.fields().keySet().equals(Set.of(names))) throw invalid();
    }
    private static UUID uuid(DataValue value) {
        String encoded = string(value); UUID id = UUID.fromString(encoded);
        if (!id.toString().equals(encoded)) throw invalid(); return id;
    }
    private static DataValue.RecordValue record(DataValue value) {
        if (!(value instanceof DataValue.RecordValue record)) throw invalid(); return record;
    }
    private static List<DataValue> list(DataValue value) {
        if (!(value instanceof DataValue.ListValue list)) throw invalid(); return list.values();
    }
    private static long number(DataValue value) {
        if (!(value instanceof DataValue.IntegerValue integer)) throw invalid(); return integer.value();
    }
    private static String string(DataValue value) {
        if (!(value instanceof DataValue.TextValue text)) throw invalid(); return text.value();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_order_archive"); }
}
