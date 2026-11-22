package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramCompiler.failure;
import java.util.*;
import java.util.function.LongConsumer;

/** Stable merge sort of frozen source indices. One advance moves one index/item or compares one code point. */
public final class SortTask {
    private enum Phase { INITIALIZE, MERGE, EMIT, DONE }
    private final DataValue.ListValue source;
    private final DataValue.ListValue keys;
    private final boolean descending;
    private final String location;
    private Phase phase = Phase.INITIALIZE;
    private int width = 1, start, left, right, offset;
    private ArrayList<Integer> order = new ArrayList<>(), scratch = new ArrayList<>();
    private final ArrayList<DataValue> output = new ArrayList<>();

    private SortTask(DataValue.ListValue source, DataValue.ListValue keys, boolean descending, String location) {
        this.source = Objects.requireNonNull(source);
        this.keys = Objects.requireNonNull(keys);
        this.descending = descending;
        this.location = Objects.requireNonNull(location);
        if (source.values().size() != keys.values().size())
            throw failure(INVALID_ARGUMENT, location, "Sort requires one key per source item");
    }

    public static SortTask start(DataValue.ListValue source, DataValue.ListValue keys, boolean descending,
        String location, LongConsumer reserve) {
        var task = new SortTask(source, keys, descending, location);
        reserve.accept(3); // Two index-list headers and the output header; source and keys are borrowed.
        return task;
    }

    public static void requireKeyType(DataType type, String location) {
        if (type != DataType.Scalar.ANY && type != DataType.Scalar.INTEGER && type != DataType.Scalar.DECIMAL
            && type != DataType.Scalar.TEXT && type != DataType.Scalar.BOOLEAN)
            throw failure(TYPE_MISMATCH, location, "Sort key must be integer, decimal, text or boolean");
    }

    static void requireKey(DataValue key, DataValue first, String location) {
        if (!(key instanceof DataValue.IntegerValue || key instanceof DataValue.DecimalValue
            || key instanceof DataValue.TextValue || key instanceof DataValue.BooleanValue)
            || first != null && first.getClass() != key.getClass())
            throw failure(TYPE_MISMATCH, location, "Sort keys must have one comparable scalar type");
    }

    public boolean complete() { return phase == Phase.DONE; }

    public void advance(LongConsumer reserve) {
        Objects.requireNonNull(reserve);
        int size = source.values().size();
        switch (phase) {
            case INITIALIZE -> {
                int index = order.size();
                if (index == size) {
                    if (size < 2) phase = Phase.EMIT;
                    else { phase = Phase.MERGE; right = width; }
                } else {
                    requireKey(keys.values().get(index), index == 0 ? null : keys.values().getFirst(), location);
                    reserve.accept(1);
                    order.add(index);
                }
            }
            case MERGE -> merge(reserve);
            case EMIT -> {
                if (output.size() == size) phase = Phase.DONE;
                else {
                    var value = source.values().get(order.get(output.size()));
                    reserve.accept(value.storageUnits());
                    output.add(value);
                }
            }
            case DONE -> { }
        }
    }

    private int middle() { return (int) Math.min((long) start + width, order.size()); }
    private int end() { return (int) Math.min((long) start + 2L * width, order.size()); }

    private void merge(LongConsumer reserve) {
        int middle = middle(), end = end();
        if (left == middle && right == end) {
            if (end == order.size()) {
                reserve.accept(1); // A new scratch header; previously allocated data remains arena-charged.
                order = scratch;
                scratch = new ArrayList<>();
                width = (int) Math.min(2L * width, order.size());
                start = left = offset = 0;
                right = width < order.size() ? width : 0;
                if (width == order.size()) phase = Phase.EMIT;
            } else {
                start = left = end;
                right = middle();
            }
            return;
        }
        if (left == middle) { take(false, reserve); return; }
        if (right == end) { take(true, reserve); return; }
        var a = keys.values().get(order.get(left));
        var b = keys.values().get(order.get(right));
        int comparison;
        if (a instanceof DataValue.TextValue ta && b instanceof DataValue.TextValue tb) {
            String x = ta.value(), y = tb.value();
            if (offset == x.length() || offset == y.length())
                comparison = Integer.compare(x.length() - offset, y.length() - offset);
            else {
                int ca = x.codePointAt(offset), cb = y.codePointAt(offset);
                if (ca == cb) { offset += Character.charCount(ca); return; }
                comparison = Integer.compare(ca, cb);
            }
        } else comparison = scalarCompare(a, b);
        if (descending) comparison = -comparison;
        take(comparison <= 0, reserve); // Equal keys always take the left run, in either direction.
    }

    private void take(boolean fromLeft, LongConsumer reserve) {
        reserve.accept(1);
        scratch.add(order.get(fromLeft ? left : right));
        if (fromLeft) left++; else right++;
        offset = 0;
    }

    private static int scalarCompare(DataValue a, DataValue b) {
        return switch (a) {
            case DataValue.IntegerValue value -> Long.compare(value.value(), ((DataValue.IntegerValue) b).value());
            case DataValue.DecimalValue value -> {
                double right = ((DataValue.DecimalValue) b).value();
                yield value.value() == right ? 0 : Double.compare(value.value(), right);
            }
            case DataValue.BooleanValue value -> Boolean.compare(value.value(), ((DataValue.BooleanValue) b).value());
            default -> throw new IllegalStateException("Unvalidated sort keys");
        };
    }

    public long liveUnits() {
        long units = 3L + order.size() + scratch.size();
        for (var value : output) units = Math.addExact(units, value.storageUnits());
        return units;
    }

    public DataValue.ListValue result() {
        if (!complete()) throw new IllegalStateException("Sort is unfinished");
        return new DataValue.ListValue(output);
    }

    public DataValue.RecordValue checkpoint() {
        return new DataValue.RecordValue(Map.of("phase", new DataValue.TextValue(phase.name()),
            "width", number(width), "start", number(start), "left", number(left), "right", number(right),
            "offset", number(offset), "order", indices(order), "scratch", indices(scratch),
            "output", new DataValue.ListValue(output)));
    }

    public static SortTask restore(DataValue.ListValue source, DataValue.ListValue keys, boolean descending,
        DataValue.RecordValue state, String location) {
        try {
            require(state.fields().keySet().equals(Set.of("phase", "width", "start", "left", "right", "offset", "order", "scratch", "output")));
            var task = new SortTask(source, keys, descending, location);
            task.phase = Phase.valueOf(((DataValue.TextValue) state.field("phase")).value());
            task.width = readNumber(state.field("width")); task.start = readNumber(state.field("start"));
            task.left = readNumber(state.field("left")); task.right = readNumber(state.field("right"));
            task.offset = readNumber(state.field("offset"));
            task.order = readIndices(state.field("order")); task.scratch = readIndices(state.field("scratch"));
            task.output.addAll(((DataValue.ListValue) state.field("output")).values());
            task.validate();
            return task;
        } catch (IllegalArgumentException | ClassCastException | ArithmeticException | ComputationException invalid) {
            throw failure(INVALID_CHECKPOINT, location, "Invalid sort continuation: " + invalid.getMessage());
        }
    }

    /** Checks cursor/permutation invariants without replaying the sorting algorithm or key modules. */
    private void validate() {
        int size = source.values().size();
        require(width > 0 && order.size() <= size && scratch.size() <= size && output.size() <= size);
        int validated = phase == Phase.INITIALIZE ? order.size() : size;
        for (int i = 0; i < validated; i++) requireKey(keys.values().get(i), keys.values().getFirst(), location);
        if (phase == Phase.INITIALIZE) {
            require(width == 1 && start == 0 && left == 0 && right == 0 && offset == 0 && scratch.isEmpty() && output.isEmpty());
            for (int i = 0; i < order.size(); i++) require(order.get(i) == i);
            return;
        }
        require(order.size() == size);
        var seen = new BitSet(size);
        for (int index : order) { require(index >= 0 && index < size && !seen.get(index)); seen.set(index); }
        if (phase == Phase.MERGE) {
            require(size >= 2 && width < size && (width & (width - 1)) == 0);
            require(start < size && start % (2L * width) == 0);
            int middle = middle(), end = end();
            require(left >= start && left <= middle && right >= middle && right <= end && output.isEmpty());
            require(scratch.size() == left + right - middle);
            validateRuns(order, width);
            var consumed = new BitSet(size);
            for (int i = 0; i < start; i++) consumed.set(order.get(i));
            for (int i = start; i < left; i++) consumed.set(order.get(i));
            for (int i = middle; i < right; i++) consumed.set(order.get(i));
            for (int index : scratch) { require(index >= 0 && index < size && consumed.get(index)); consumed.clear(index); }
            require(consumed.isEmpty());
            validateRuns(scratch, 2L * width);
            if (scratch.size() > start) {
                int last = scratch.getLast();
                if (left < middle) require(compareIndices(last, order.get(left)) <= 0);
                if (right < end) require(compareIndices(last, order.get(right)) <= 0);
            }
            if (offset != 0) {
                require(left < middle && right < end);
                var a = (DataValue.TextValue) keys.values().get(order.get(left));
                var b = (DataValue.TextValue) keys.values().get(order.get(right));
                require(offset <= a.value().length() && offset <= b.value().length());
                require(a.value().regionMatches(0, b.value(), 0, offset));
                require(codePointBoundary(a.value(), offset) && codePointBoundary(b.value(), offset));
            }
        } else {
            require(width == Math.max(1, size) && start == 0 && left == 0 && right == 0 && offset == 0 && scratch.isEmpty());
            validateRuns(order, Math.max(1, size));
            for (int i = 0; i < output.size(); i++) require(output.get(i).equals(source.values().get(order.get(i))));
            if (phase == Phase.DONE) require(output.size() == size);
        }
    }

    private void validateRuns(List<Integer> indices, long runLength) {
        for (int i = 0; i < indices.size(); i++) {
            require(indices.get(i) / runLength == i / runLength);
            if (i % runLength != 0) require(compareIndices(indices.get(i - 1), indices.get(i)) <= 0);
        }
    }

    private int compareIndices(int a, int b) {
        var x = keys.values().get(a); var y = keys.values().get(b);
        int comparison;
        if (x instanceof DataValue.TextValue tx && y instanceof DataValue.TextValue ty) {
            String left = tx.value(), right = ty.value(); int i = 0;
            while (i < left.length() && i < right.length() && left.codePointAt(i) == right.codePointAt(i))
                i += Character.charCount(left.codePointAt(i));
            comparison = i == left.length() || i == right.length()
                ? Integer.compare(left.length() - i, right.length() - i)
                : Integer.compare(left.codePointAt(i), right.codePointAt(i));
        } else comparison = scalarCompare(x, y);
        if (descending) comparison = -comparison;
        return comparison == 0 ? Integer.compare(a, b) : comparison;
    }

    private static boolean codePointBoundary(String text, int offset) {
        return offset == 0 || offset == text.length() || !Character.isHighSurrogate(text.charAt(offset - 1))
            || !Character.isLowSurrogate(text.charAt(offset));
    }
    private static DataValue.IntegerValue number(int value) { return new DataValue.IntegerValue(value); }
    private static DataValue.ListValue indices(List<Integer> indices) {
        return new DataValue.ListValue(indices.stream().map(i -> (DataValue) number(i)).toList());
    }
    private static int readNumber(DataValue value) {
        int result = Math.toIntExact(((DataValue.IntegerValue) value).value());
        require(result >= 0); return result;
    }
    private static ArrayList<Integer> readIndices(DataValue value) {
        var result = new ArrayList<Integer>();
        for (var index : ((DataValue.ListValue) value).values()) result.add(readNumber(index));
        return result;
    }
    private static void require(boolean valid) {
        if (!valid) throw failure(INVALID_CHECKPOINT, "sort", "Invalid sort state");
    }
}
