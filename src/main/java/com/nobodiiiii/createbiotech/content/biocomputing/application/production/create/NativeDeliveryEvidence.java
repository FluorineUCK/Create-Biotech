package com.nobodiiiii.createbiotech.content.biocomputing.application.production.create;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.Order;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionMaterials;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import com.simibubi.create.content.logistics.box.PackageItem;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** Versioned, bounded immutable evidence. Native package metadata is read, never rewritten. */
public final class NativeDeliveryEvidence {
    private final HolderLookup.Provider registries;
    private final NativeNbtCodec.Limits nbt;
    private final int entries, fragments;
    public NativeDeliveryEvidence(HolderLookup.Provider registries, NativeNbtCodec.Limits nbt, int entries, int fragments) {
        this.registries = registries; this.nbt = nbt; this.entries = entries; this.fragments = fragments;
        if (entries < 1 || fragments < 1) throw new IllegalArgumentException("invalid_evidence_limits");
    }
    public static Map<Integer, Set<Integer>> orders(Order order) {
        var receipt = order.receipt().orElseThrow();
        exact(receipt, "native_orders");
        var result = new LinkedHashMap<Integer, Set<Integer>>();
        for (var value : list(receipt.field("native_orders"))) {
            var row = (DataValue.RecordValue)value; exact(row, "id", "links");
            var links = new LinkedHashSet<Integer>();
            for (var link : list(row.field("links"))) if (!links.add(number(link))) throw invalid();
            if (links.isEmpty() || result.put(number(row.field("id")), Set.copyOf(links)) != null) throw invalid();
        }
        if (result.isEmpty()) throw invalid();
        return Map.copyOf(result);
    }
    public DeliveryLedger<StackKey> restore(Order order) {
        if (!order.plan().extension().equals("create.packaging") || order.plan().version() != 1
            || order.plan().steps().size() != 1 || order.step() != 0) throw invalid();
        var expected = ProductionMaterials.decode((DataValue.RecordValue)order.plan().steps().getFirst().field("data"), registries, nbt, entries);
        var ledger = new DeliveryLedger<>(expected, orders(order), fragments);
        if (order.result() == DataValue.NullValue.INSTANCE) return ledger;
        var root = (DataValue.RecordValue)order.result(); exact(root, "kind", "version", "fragments");
        if (!new DataValue.TextValue("create.delivery").equals(root.field("kind")) || !integer(1).equals(root.field("version"))) throw invalid();
        var saved = list(root.field("fragments")); if (saved.size() > fragments) throw invalid();
        for (var value : saved) {
            var row = (DataValue.RecordValue)value; exact(row, "order", "link", "index", "final_link", "last", "context", "items");
            var context = row.field("context") == DataValue.NullValue.INSTANCE ? Optional.<Map<StackKey, Long>>empty() : Optional.of(decodeCounts(row.field("context")));
            var fragment = new DeliveryLedger.Fragment<>(number(row.field("order")), number(row.field("link")), number(row.field("index")),
                bool(row.field("final_link")), bool(row.field("last")), context, decodeCounts(row.field("items")));
            if (ledger.accept(fragment) != DeliveryLedger.Outcome.ACCEPTED) throw invalid();
        }
        return ledger;
    }
    /** Called before UnpackingHandler can mutate the material list. */
    public DeliveryLedger.Fragment<StackKey> capture(ItemStack box) {
        if (!PackageItem.isPackage(box) || !PackageItem.hasOrderData(box)) throw invalid();
        var stacks = new LinkedHashMap<StackKey, Long>(); var contents = PackageItem.getContents(box);
        for (int slot = 0; slot < contents.getSlots(); slot++) {
            var stack = contents.getStackInSlot(slot);
            if (!stack.isEmpty()) stacks.merge(new StackKey(stack), (long)stack.getCount(), Math::addExact);
            if (stacks.size() > entries) throw invalid();
        }
        var nativeContext = PackageItem.getOrderContext(box);
        Optional<Map<StackKey, Long>> context = Optional.empty();
        if (nativeContext != null) {
            var counts = new LinkedHashMap<StackKey, Long>();
            if (nativeContext.stacks().size() > entries) throw invalid();
            for (var item : nativeContext.stacks()) {
                if (item.stack.isEmpty() || item.count <= 0) throw invalid();
                counts.merge(new StackKey(item.stack), (long)item.count, Math::addExact);
            }
            context = Optional.of(counts);
        }
        var fragment = new DeliveryLedger.Fragment<>(PackageItem.getOrderId(box), PackageItem.getLinkIndex(box), PackageItem.getIndex(box),
            PackageItem.isFinalLink(box), PackageItem.isFinal(box), context, stacks);
        encodeFragment(fragment); // Bound native components before retaining them across the effect.
        return fragment;
    }
    public DataValue.RecordValue encode(DeliveryLedger<StackKey> ledger) {
        if (ledger.fragments().size() > fragments) throw invalid();
        var rows = new ArrayList<DataValue>(); long units = 0;
        for (var fragment : ledger.fragments()) {
            var row = encodeFragment(fragment); units = Math.addExact(units, row.storageUnits());
            if (units > nbt.values().maxValueUnits()) throw invalid();
            rows.add(row);
        }
        return bounded(new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("create.delivery"), "version", integer(1),
            "fragments", new DataValue.ListValue(rows))));
    }
    private DataValue.RecordValue encodeFragment(DeliveryLedger.Fragment<StackKey> fragment) {
        return bounded(new DataValue.RecordValue(Map.of("order", integer(fragment.order()), "link", integer(fragment.link()), "index", integer(fragment.index()),
            "final_link", new DataValue.BooleanValue(fragment.finalLink()), "last", new DataValue.BooleanValue(fragment.last()),
            "context", fragment.context().<DataValue>map(this::encodeCounts).orElse(DataValue.NullValue.INSTANCE), "items", encodeCounts(fragment.items()))));
    }
    private DataValue.ListValue encodeCounts(Map<StackKey, Long> counts) {
        if (counts.size() > entries) throw invalid();
        var rows = new ArrayList<DataValue>(); long units = 0;
        for (var entry : counts.entrySet()) {
            var row = new DataValue.RecordValue(Map.of("stack", NativeNbtCodec.snapshot(entry.getKey().stack().save(registries), nbt), "count", integer(entry.getValue())));
            units = Math.addExact(units, row.storageUnits()); if (units > nbt.values().maxValueUnits()) throw invalid();
            rows.add(row);
        }
        return bounded(new DataValue.ListValue(rows));
    }
    private <T extends DataValue> T bounded(T value) { if (value.storageUnits() > nbt.values().maxValueUnits()) throw invalid(); return value; }
    private Map<StackKey, Long> decodeCounts(DataValue value) {
        var rows = list(value); if (rows.size() > entries) throw invalid();
        var result = new LinkedHashMap<StackKey, Long>();
        for (var item : rows) {
            var row = (DataValue.RecordValue)item; exact(row, "stack", "count");
            var stack = ItemStack.parse(registries, NativeNbtCodec.toTag((DataValue.NbtValue)row.field("stack"), nbt)).orElseThrow(NativeDeliveryEvidence::invalid);
            long count = ((DataValue.IntegerValue)row.field("count")).value();
            if (count <= 0 || stack.getCount() != 1 || result.put(new StackKey(stack), count) != null) throw invalid();
        }
        return Map.copyOf(result);
    }
    private static List<DataValue> list(DataValue value) { return ((DataValue.ListValue)value).values(); }
    private static int number(DataValue value) { return Math.toIntExact(((DataValue.IntegerValue)value).value()); }
    private static boolean bool(DataValue value) { return ((DataValue.BooleanValue)value).value(); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    private static void exact(DataValue.RecordValue record, String... fields) { if (!record.fields().keySet().equals(Set.of(fields))) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("invalid_delivery_evidence"); }
}
