package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.*;
import java.util.function.LongConsumer;

/** Incremental structural type inspection. Borrowed immutable values; no conversion or world access. */
public final class ValueTypeTask {
    private static final long FRAME_UNITS = 8;
    private enum Mode { ENTER, SEQUENCE, RECORD, CANDIDATE, KEY, WAIT_SEQUENCE, WAIT_RECORD, WAIT_OPTIONAL, DONE }
    private record Frame(DataType type, DataValue value, Mode mode, int index, int scan, int offset, Frame parent, int depth) {
        Frame at(Mode mode, int index, int scan, int offset) { return new Frame(type, value, mode, index, scan, offset, parent, depth); }
    }
    /** Installed type metadata, not per-task RAM. Indexed record names avoid unmetered map lookups. */
    public static final class Schema {
        private final DataType root;
        private final IdentityHashMap<DataType, List<Map.Entry<String, DataType>>> fields = new IdentityHashMap<>();
        private final long units;
        public Schema(DataType root) {
            this.root = Objects.requireNonNull(root);
            var seen = Collections.newSetFromMap(new IdentityHashMap<DataType, Boolean>());
            var pending = new ArrayDeque<DataType>(); pending.push(root); long units = 0;
            while (!pending.isEmpty()) {
                var type = pending.pop(); if (!seen.add(type)) continue;
                units = Math.addExact(units, 1);
                switch (type) {
                    case DataType.RecordType record -> {
                        // The ISA type identity sorts names too. Reconstructed built-in aliases can
                        // have a different Map iteration order in another JVM, but not another cost/cursor.
                        var entries = List.copyOf(new TreeMap<>(record.fields()).entrySet()); fields.put(type, entries);
                        for (var entry : entries) { units = Math.addExact(units, entry.getKey().length() + 1L); pending.push(entry.getValue()); }
                    }
                    case DataType.ListType list -> pending.push(list.element());
                    case DataType.MapType map -> pending.push(map.element());
                    case DataType.OptionalType optional -> pending.push(optional.value());
                    case DataType.RefType ref -> pending.push(ref.target());
                    case DataType.Semantic ignored -> { }
                    case DataType.Scalar ignored -> { }
                }
            }
            this.units = units;
        }
        public long workBound(DataValue value) {
            try { return Math.addExact(2, Math.multiplyExact(8, Math.multiplyExact(Math.addExact(units, 1), Math.addExact(value.storageUnits(), 1)))); }
            catch (ArithmeticException enormous) { return Long.MAX_VALUE; }
        }
    }
    private final Schema schema;
    private final DataValue source;
    private final Frame top;
    private final boolean mismatch;
    private ValueTypeTask(Schema schema, DataValue source, Frame top, boolean mismatch) {
        this.schema = schema; this.source = source; this.top = top; this.mismatch = mismatch;
    }
    public static ValueTypeTask start(Schema schema, DataValue value, LongConsumer reserve) {
        reserve.accept(1 + FRAME_UNITS);
        return new ValueTypeTask(schema, value, new Frame(schema.root, value, Mode.ENTER, 0, 0, 0, null, 1), false);
    }
    public void validateSource(DataValue source) {
        if (this.source != source) throw new IllegalArgumentException("Type-check source changed");
    }
    public boolean complete() { return top == null; }
    public boolean matches() { if (!complete()) throw new IllegalStateException("Type check incomplete"); return !mismatch; }
    public long liveUnits() { return 1 + FRAME_UNITS * (top == null ? 0 : top.depth); }
    public ValueTypeTask advance(LongConsumer reserve) {
        if (complete()) throw new IllegalStateException("Type check complete");
        if (mismatch) return next(top.parent, true); // Unwind a failed deep check one paid frame at a time.
        var f = top;
        switch (f.mode) {
            case ENTER -> {
                return switch (f.type) {
                    case DataType.RefType ref -> finish(ref.accepts(f.value));
                    case DataType.Semantic s -> finish(f.value instanceof DataValue.RecipeValue r && r.version()==s.version());
                    case DataType.Scalar scalar -> finish(switch (scalar) {
                        case ANY -> f.value.localReferences()==0;
                        case BOOLEAN -> f.value instanceof DataValue.BooleanValue;
                        case INTEGER -> f.value instanceof DataValue.IntegerValue;
                        case DECIMAL -> f.value instanceof DataValue.DecimalValue;
                        case TEXT -> f.value instanceof DataValue.TextValue;
                        case ERROR -> f.value instanceof DataValue.ErrorValue;
                        case NBT -> f.value instanceof DataValue.NbtValue;
                        case NULL -> f.value == DataValue.NullValue.INSTANCE;
                    });
                    case DataType.OptionalType optional -> f.value == DataValue.NullValue.INSTANCE ? finish(true)
                        : push(optional.value(), f.value, Mode.WAIT_OPTIONAL, reserve);
                    case DataType.ListType ignored -> f.value instanceof DataValue.ListValue
                        ? next(f.at(Mode.SEQUENCE, 0, 0, 0), false) : finish(false);
                    case DataType.MapType ignored -> f.value instanceof DataValue.MapValue
                        ? next(f.at(Mode.SEQUENCE, 0, 0, 0), false) : finish(false);
                    case DataType.RecordType record -> f.value instanceof DataValue.RecordValue actual && record.fields().size() == actual.fields().size()
                        ? next(f.at(Mode.RECORD, 0, 0, 0), false) : finish(false);
                };
            }
            case SEQUENCE -> {
                int size = f.value instanceof DataValue.ListValue list ? list.values().size() : ((DataValue.MapValue)f.value).indexedEntries().size();
                if (f.index == size) return finish(true);
                DataValue value = f.value instanceof DataValue.ListValue list ? list.values().get(f.index) : ((DataValue.MapValue)f.value).indexedEntries().get(f.index).getValue();
                DataType type = f.type instanceof DataType.ListType list ? list.element() : ((DataType.MapType)f.type).element();
                return push(type, value, Mode.WAIT_SEQUENCE, reserve);
            }
            case RECORD -> {
                if (f.index == schema.fields.get(f.type).size()) return finish(true);
                return next(f.at(Mode.CANDIDATE, f.index, 0, 0), false);
            }
            case CANDIDATE -> {
                if (f.scan == ((DataValue.RecordValue)f.value).indexedFields().size()) return finish(false);
                return next(f.at(Mode.KEY, f.index, f.scan, 0), false);
            }
            case KEY -> {
                var expected = schema.fields.get(f.type).get(f.index);
                var actual = ((DataValue.RecordValue)f.value).indexedFields().get(f.scan);
                if (f.offset == expected.getKey().length() && f.offset == actual.getKey().length())
                    return push(expected.getValue(), actual.getValue(), Mode.WAIT_RECORD, reserve);
                if (f.offset < expected.getKey().length() && f.offset < actual.getKey().length()
                        && expected.getKey().charAt(f.offset) == actual.getKey().charAt(f.offset))
                    return next(f.at(Mode.KEY, f.index, f.scan, f.offset + 1), false);
                return next(f.at(Mode.CANDIDATE, f.index, f.scan + 1, 0), false);
            }
            case DONE -> { return finish(true); }
            case WAIT_OPTIONAL, WAIT_SEQUENCE, WAIT_RECORD -> throw new IllegalStateException("Type check waiting without a child");
        }
        throw new AssertionError();
    }
    private ValueTypeTask next(Frame top, boolean mismatch) { return new ValueTypeTask(schema, source, top, mismatch); }
    private ValueTypeTask push(DataType type, DataValue value, Mode waiting, LongConsumer reserve) {
        reserve.accept(FRAME_UNITS);
        var parent = top.at(waiting, top.index, top.scan, top.offset);
        return next(new Frame(type, value, Mode.ENTER, 0, 0, 0, parent, Math.addExact(top.depth, 1)), false);
    }
    private ValueTypeTask finish(boolean matches) {
        var parent = top.parent;
        if (!matches) return next(parent, true);
        if (parent == null) return next(null, false);
        return next(switch (parent.mode) {
            case WAIT_OPTIONAL -> parent.at(Mode.DONE, 0, 0, 0);
            case WAIT_SEQUENCE -> parent.at(Mode.SEQUENCE, parent.index + 1, 0, 0);
            case WAIT_RECORD -> parent.at(Mode.RECORD, parent.index + 1, 0, 0);
            default -> throw new IllegalStateException("Invalid type-check parent");
        }, false);
    }
    private static DataValue.RecordValue frame(Mode mode, int index, int scan, int offset) {
        return new DataValue.RecordValue(Map.of("mode", number(mode.ordinal()), "index", number(index), "scan", number(scan), "offset", number(offset)));
    }
    private static final long FRAME_SNAPSHOT_UNITS = frame(Mode.ENTER, 0, 0, 0).storageUnits();
    private static final long SNAPSHOT_BASE_UNITS = checkpoint(List.of(), false).storageUnits();
    public long checkpointStorageUnits() { return Math.addExact(SNAPSHOT_BASE_UNITS, Math.multiplyExact(FRAME_SNAPSHOT_UNITS, top == null ? 0 : top.depth)); }
    public DataValue.RecordValue checkpoint() {
        var frames = new ArrayList<DataValue>();
        for (var f = top; f != null; f = f.parent) frames.add(frame(f.mode, f.index, f.scan, f.offset));
        return checkpoint(frames, mismatch);
    }
    private static DataValue.RecordValue checkpoint(List<DataValue> frames, boolean mismatch) {
        return new DataValue.RecordValue(Map.of("typing", new DataValue.BooleanValue(true), "mismatch", new DataValue.BooleanValue(mismatch), "frames", new DataValue.ListValue(frames)));
    }
    public static long checkpointUnits(DataValue.RecordValue progress) {
        if (!progress.fields().keySet().equals(Set.of("typing", "mismatch", "frames"))
                || !new DataValue.BooleanValue(true).equals(progress.field("typing"))
                || !(progress.field("mismatch") instanceof DataValue.BooleanValue)
                || !(progress.field("frames") instanceof DataValue.ListValue list)) throw new IllegalArgumentException("Invalid type-check checkpoint");
        return Math.addExact(1, Math.multiplyExact(FRAME_UNITS, list.values().size()));
    }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
}
