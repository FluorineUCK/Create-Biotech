package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import java.util.stream.IntStream;

/** Frozen tree, with iterative lazy traversal of expanded branches and only page-sized row storage. */
public final class ValueTree {
    public record Row(ValuePath path, DataValue value, int depth, boolean expandable, boolean expanded) {}
    public record Page(int index, int count, List<Row> rows) { public Page { rows = List.copyOf(rows); } }
    private final DataValue.RecordValue inputs;
    private final Set<ValuePath> expanded = new HashSet<>();
    public ValueTree(DataValue.RecordValue inputs) { this.inputs = Objects.requireNonNull(inputs); }
    public DataValue.RecordValue inputs() { return inputs; }
    public void toggle(ValuePath path) {
        if (childCount(path.resolve(inputs)) == 0) return;
        if (!expanded.remove(path)) expanded.add(path);
    }
    public Page page(int requested, int size) {
        if (size < 1) throw new IllegalArgumentException("Page size must be positive");
        int index = Math.max(0, requested);
        long from = (long)index * size, seen = 0;
        var rows = new ArrayList<Row>();
        var pending = new ArrayDeque<Iterator<Row>>();
        pending.push(inputs.fields().entrySet().stream().map(e -> row(new ValuePath(e.getKey(), List.of()), e.getValue())).iterator());
        while (!pending.isEmpty()) {
            var iterator = pending.peek();
            if (!iterator.hasNext()) { pending.pop(); continue; }
            Row row = iterator.next();
            if (seen >= from && seen < from + size) rows.add(row);
            seen++;
            if (row.expanded()) pending.push(children(row));
        }
        int count = Math.toIntExact(Math.max(1, (seen + size - 1) / size));
        if (index >= count) return page(count - 1, size); // one bounded re-scan for a clamped last page
        return new Page(index, count, rows);
    }
    private Row row(ValuePath path, DataValue value) {
        boolean expandable = childCount(value) > 0;
        return new Row(path, value, path.steps().size(), expandable, expandable && expanded.contains(path));
    }
    private Iterator<Row> children(Row row) {
        var path = row.path();
        return switch (row.value()) {
            case DataValue.RecipeValue recipe -> List.of(row(path.key("projection"), recipe.projection())).iterator();
            case DataValue.RecordValue record -> record.fields().entrySet().stream().map(e -> row(path.key(e.getKey()), e.getValue())).iterator();
            case DataValue.MapValue map -> map.entries().entrySet().stream().map(e -> row(path.key(e.getKey()), e.getValue())).iterator();
            case DataValue.ListValue list -> IntStream.range(0, list.values().size()).mapToObj(i -> row(path.index(i), list.values().get(i))).iterator();
            default -> Collections.emptyIterator();
        };
    }
    public static int childCount(DataValue value) {
        return switch (value) {
            case DataValue.RecipeValue ignored -> 1;
            case DataValue.RecordValue record -> record.fields().size();
            case DataValue.MapValue map -> map.entries().size();
            case DataValue.ListValue list -> list.values().size();
            default -> 0;
        };
    }
    /** Shallow kind: inspecting a huge container never traverses all its children to infer a type. */
    public static String kind(DataValue value) {
        return switch (value) {
            case DataValue.LocalReference ignored -> "local_reference";
            case DataValue.RecipeValue r -> "recipe@"+r.version();
            case DataValue.NbtValue ignored -> "nbt";
            case DataValue.BooleanValue ignored -> "boolean";
            case DataValue.IntegerValue ignored -> "integer";
            case DataValue.DecimalValue ignored -> "decimal";
            case DataValue.TextValue ignored -> "text";
            case DataValue.NullValue ignored -> "null";
            case DataValue.ErrorValue ignored -> "error";
            case DataValue.RecordValue ignored -> "record";
            case DataValue.MapValue ignored -> "map";
            case DataValue.ListValue ignored -> "list";
        };
    }
}
