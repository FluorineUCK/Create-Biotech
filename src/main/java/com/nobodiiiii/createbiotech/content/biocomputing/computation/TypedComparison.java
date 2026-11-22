package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.INVALID_CHECKPOINT;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramCompiler.failure;
import java.util.*;
import java.util.function.LongConsumer;

/** Internal typed comparison cursor. One advance visits one value, one entry, or one UTF-16 unit.
 * Named values deliberately search keys incrementally instead of doing an uncharged hash/equals lookup.
 * Pairs borrow immutable values; every continuation frame is reserved before it is pushed. */
final class TypedComparison {
    private static final long FRAME_UNITS = 8;
    private enum Mode { ENTER, TEXT, ERROR_CODE, ERROR_MESSAGE, LIST, WAIT_LIST, NAMED_LEFT, NAMED_RIGHT, KEY, WAIT_NAMED, WAIT_NBT, NBT_DONE }
    private static final class Frame {
        final DataValue a, b;
        Mode mode = Mode.ENTER;
        int index, scan, offset;
        Map.Entry<String, DataValue> aEntry, bEntry;
        Frame(DataValue a, DataValue b) { this.a = a; this.b = b; }
    }
    private final DataValue a, b;
    private final ArrayList<Frame> stack = new ArrayList<>();
    private boolean unequal;
    private TypedComparison(DataValue a, DataValue b) { this.a = a; this.b = b; }
    static TypedComparison start(DataValue a, DataValue b, LongConsumer reserve) {
        reserve.accept(1 + FRAME_UNITS);
        var result = new TypedComparison(a, b); result.stack.add(new Frame(a, b)); return result;
    }
    boolean complete() { return stack.isEmpty(); }
    boolean equal() { if (!complete()) throw new IllegalStateException("Comparison is unfinished"); return !unequal; }
    static long checkpointUnits(DataValue.RecordValue progress) {
        if (!(progress.fields().get("frames") instanceof DataValue.ListValue frames)) throw failure(INVALID_CHECKPOINT, "equality", "Invalid comparison frames");
        return Math.addExact(1, Math.multiplyExact(FRAME_UNITS, frames.values().size()));
    }
    long liveUnits() { return 1 + FRAME_UNITS * stack.size(); }
    void advance(LongConsumer reserve) {
        if (stack.isEmpty()) return;
        if (unequal) { stack.removeLast(); return; } // False unwinds one frame per work unit, never a free deep clear.
        var frame = stack.getLast();
        switch (frame.mode) {
            case ENTER -> {
                if (frame.a.getClass() != frame.b.getClass()) { finish(false); return; }
                switch (frame.a) {
                    case DataValue.LocalReference ignored -> throw new ComputationException(ComputationException.Code.TYPE_MISMATCH,"reference","Use explicit reference identity comparison");
                    case DataValue.RecipeValue r -> {
                        var other=(DataValue.RecipeValue)frame.b;
                        if (r.version()!=other.version()) finish(false);
                        else push(frame, Mode.WAIT_NBT, r.projection(), other.projection(), reserve);
                    }
                    case DataValue.NbtValue n -> {
                        var other = (DataValue.NbtValue)frame.b;
                        if (n.tagId() != other.tagId()) finish(false);
                        else push(frame, Mode.WAIT_NBT, n.value(), other.value(), reserve);
                    }
                    case DataValue.IntegerValue v -> finish(v.value() == ((DataValue.IntegerValue) frame.b).value());
                    case DataValue.DecimalValue v -> finish(Double.doubleToLongBits(v.value()) == Double.doubleToLongBits(((DataValue.DecimalValue) frame.b).value()));
                    case DataValue.BooleanValue v -> finish(v.value() == ((DataValue.BooleanValue) frame.b).value());
                    case DataValue.NullValue ignored -> finish(true);
                    case DataValue.TextValue ignored -> frame.mode = Mode.TEXT;
                    case DataValue.ErrorValue ignored -> frame.mode = Mode.ERROR_CODE;
                    case DataValue.ListValue list -> {
                        if (list.values().size() != ((DataValue.ListValue) frame.b).values().size()) finish(false);
                        else frame.mode = Mode.LIST;
                    }
                    case DataValue.RecordValue ignored -> enterNamed(frame);
                    case DataValue.MapValue ignored -> enterNamed(frame);
                }
            }
            case TEXT -> text(frame, ((DataValue.TextValue)frame.a).value(), ((DataValue.TextValue)frame.b).value(), false);
            case ERROR_CODE -> text(frame, ((DataValue.ErrorValue)frame.a).code(), ((DataValue.ErrorValue)frame.b).code(), true);
            case ERROR_MESSAGE -> text(frame, ((DataValue.ErrorValue)frame.a).message(), ((DataValue.ErrorValue)frame.b).message(), false);
            case LIST -> {
                var left = ((DataValue.ListValue)frame.a).values(); var right = ((DataValue.ListValue)frame.b).values();
                if (frame.index == left.size()) finish(true);
                else push(frame, Mode.WAIT_LIST, left.get(frame.index), right.get(frame.index), reserve);
            }
            case NAMED_LEFT -> {
                if (frame.index == indexed(frame.a).size()) finish(true);
                else {
                    frame.aEntry = indexed(frame.a).get(frame.index);
                    frame.scan = frame.offset = 0; frame.mode = Mode.NAMED_RIGHT;
                }
            }
            case NAMED_RIGHT -> {
                if (frame.scan == indexed(frame.b).size()) finish(false);
                else { frame.bEntry = indexed(frame.b).get(frame.scan++); frame.offset = 0; frame.mode = Mode.KEY; }
            }
            case KEY -> {
                String left = frame.aEntry.getKey(), right = frame.bEntry.getKey();
                if (left.length() != right.length()) { frame.mode = Mode.NAMED_RIGHT; frame.bEntry = null; frame.offset = 0; }
                else if (frame.offset == left.length()) push(frame, Mode.WAIT_NAMED, frame.aEntry.getValue(), frame.bEntry.getValue(), reserve);
                else if (left.charAt(frame.offset) == right.charAt(frame.offset)) frame.offset++;
                else { frame.mode = Mode.NAMED_RIGHT; frame.bEntry = null; frame.offset = 0; }
            }
            case NBT_DONE -> finish(true);
            case WAIT_LIST, WAIT_NAMED, WAIT_NBT -> throw new IllegalStateException("Waiting comparison has no child");
        }
    }
    private void enterNamed(Frame frame) {
        if (named(frame.a).size() != named(frame.b).size()) finish(false);
        else frame.mode = Mode.NAMED_LEFT;
    }
    private void text(Frame frame, String left, String right, boolean errorCode) {
        if (left.length() != right.length()) finish(false);
        else if (frame.offset == left.length()) {
            if (errorCode) { frame.offset = 0; frame.mode = Mode.ERROR_MESSAGE; }
            else finish(true);
        } else if (left.charAt(frame.offset) == right.charAt(frame.offset)) frame.offset++;
        else finish(false);
    }
    private void push(Frame parent, Mode waiting, DataValue a, DataValue b, LongConsumer reserve) {
        reserve.accept(FRAME_UNITS); stack.add(new Frame(a, b)); parent.mode = waiting;
    }
    private void finish(boolean equal) {
        stack.removeLast();
        if (!equal) { unequal = true; return; }
        if (!stack.isEmpty()) {
            var parent = stack.getLast(); parent.index++;
            if (parent.mode == Mode.WAIT_LIST) parent.mode = Mode.LIST;
            else if (parent.mode == Mode.WAIT_NBT) parent.mode = Mode.NBT_DONE;
            else {
                parent.mode = Mode.NAMED_LEFT; parent.aEntry = parent.bEntry = null;
                parent.scan = parent.offset = 0;
            }
        }
    }
    private record FrozenFrame(DataValue a, DataValue b, Mode mode, int index, int scan, int offset) {}
    /** Opaque immutable owned controls; never serialized and never contains an iterator or mutable frame. */
    static final class Snapshot {
        private final DataValue a, b;
        private final List<FrozenFrame> frames;
        private final boolean unequal;
        private Snapshot(TypedComparison task) {
            a = task.a; b = task.b; unequal = task.unequal;
            frames = task.stack.stream().map(f -> new FrozenFrame(f.a, f.b, f.mode, f.index, f.scan, f.offset)).toList();
        }
    }
    Snapshot snapshot() { return new Snapshot(this); }
    static TypedComparison copy(Snapshot snapshot) {
        var task = new TypedComparison(snapshot.a, snapshot.b); task.unequal = snapshot.unequal;
        for (var saved : snapshot.frames) {
            var frame = new Frame(saved.a(), saved.b()); frame.mode = saved.mode();
            frame.index = saved.index(); frame.scan = saved.scan(); frame.offset = saved.offset();
            // Indexed immutable entries make positioning constant-time; no replay/iterator seeks.
            if (frame.mode == Mode.NAMED_RIGHT || frame.mode == Mode.KEY || frame.mode == Mode.WAIT_NAMED)
                frame.aEntry = indexed(frame.a).get(frame.index);
            if (frame.mode == Mode.KEY || frame.mode == Mode.WAIT_NAMED)
                frame.bEntry = indexed(frame.b).get(frame.scan - 1);
            task.stack.add(frame);
        }
        return task;
    }
    /** Exact serialized-control footprint without allocating values, maps, lists or an iterator.
     * Root fields contribute 18 units; each frame's fixed keys/scalars contribute 28 plus its mode text. */
    long checkpointStorageUnits() {
        long units = 18;
        for (int i = 0; i < stack.size(); i++) units = Math.addExact(units, 28L + stack.get(i).mode.name().length());
        return units;
    }
    DataValue.RecordValue checkpoint() {
        return new DataValue.RecordValue(Map.of("unequal", new DataValue.BooleanValue(unequal), "frames", new DataValue.ListValue(stack.stream()
            .map(f -> (DataValue)new DataValue.RecordValue(Map.of("mode", new DataValue.TextValue(f.mode.name()),
                "index", number(f.index), "scan", number(f.scan), "offset", number(f.offset)))).toList())));
    }
    static TypedComparison restore(DataValue a, DataValue b, DataValue.RecordValue state) {
        require(state.fields().keySet().equals(Set.of("unequal", "frames")));
        var task = new TypedComparison(a, b); task.unequal = ((DataValue.BooleanValue)state.field("unequal")).value();
        var frames = ((DataValue.ListValue)state.field("frames")).values();
        // Depth is bounded by immutable source logical storage, not by the untrusted saved work counter.
        require(frames.size() <= Math.min(a.storageUnits(), b.storageUnits()));
        for (var value : frames) {
            var record = (DataValue.RecordValue)value;
            require(record.fields().keySet().equals(Set.of("mode", "index", "scan", "offset")));
            Frame parent = task.stack.isEmpty() ? null : task.stack.getLast();
            DataValue left = a, right = b;
            if (parent != null) {
                if (parent.mode == Mode.WAIT_LIST) {
                    left = ((DataValue.ListValue)parent.a).values().get(parent.index);
                    right = ((DataValue.ListValue)parent.b).values().get(parent.index);
                } else if (parent.mode == Mode.WAIT_NBT) {
                    left = wrapped(parent.a);
                    right = wrapped(parent.b);
                } else {
                    require(parent.mode == Mode.WAIT_NAMED);
                    left = parent.aEntry.getValue(); right = parent.bEntry.getValue();
                }
            }
            var frame = new Frame(left, right); frame.mode = Mode.valueOf(((DataValue.TextValue)record.field("mode")).value());
            frame.index = read(record.field("index")); frame.scan = read(record.field("scan")); frame.offset = read(record.field("offset"));
            task.validate(frame); task.stack.add(frame);
        }
        if (!task.unequal && !task.stack.isEmpty()) {
            var top = task.stack.getLast(); require(top.mode != Mode.WAIT_LIST && top.mode != Mode.WAIT_NAMED && top.mode != Mode.WAIT_NBT);
        }
        if (task.unequal || frames.isEmpty()) require(a.equals(b) != task.unequal);
        return task;
    }
    /** Decoder-only invariant and completed-prefix checks; never runs in advance(). No work-count replay. */
    private void validate(Frame f) {
        require(f.index >= 0 && f.scan >= 0 && f.offset >= 0);
        if (f.mode == Mode.ENTER) { require(f.index == 0 && f.scan == 0 && f.offset == 0); return; }
        require(f.a.getClass() == f.b.getClass());
        switch (f.mode) {
            case WAIT_NBT, NBT_DONE -> {
                require(f.a instanceof DataValue.RecipeValue || f.a instanceof DataValue.NbtValue);
                if(f.a instanceof DataValue.RecipeValue left) require(left.version() == ((DataValue.RecipeValue)f.b).version());
                if(f.a instanceof DataValue.NbtValue left) require(left.tagId() == ((DataValue.NbtValue)f.b).tagId());
                require(f.scan == 0 && f.offset == 0);
                require(f.index == (f.mode == Mode.WAIT_NBT ? 0 : 1));
                if (f.mode == Mode.NBT_DONE) require(wrapped(f.a).equals(wrapped(f.b)));
            }
            case TEXT, ERROR_CODE, ERROR_MESSAGE -> {
                require(f.index == 0 && f.scan == 0);
                String x, y;
                if (f.mode == Mode.TEXT) { x = ((DataValue.TextValue)f.a).value(); y = ((DataValue.TextValue)f.b).value(); }
                else {
                    var left = (DataValue.ErrorValue)f.a; var right = (DataValue.ErrorValue)f.b;
                    if (f.mode == Mode.ERROR_MESSAGE) require(left.code().equals(right.code()));
                    x = f.mode == Mode.ERROR_CODE ? left.code() : left.message(); y = f.mode == Mode.ERROR_CODE ? right.code() : right.message();
                }
                DistinctTask.observeValidation(f.offset);
                require(f.offset <= Math.min(x.length(), y.length()) && x.regionMatches(0, y, 0, f.offset));
            }
            case LIST, WAIT_LIST -> {
                var left = ((DataValue.ListValue)f.a).values(); var right = ((DataValue.ListValue)f.b).values();
                require(left.size() == right.size() && f.index <= left.size() && f.scan == 0 && f.offset == 0);
                require(f.mode != Mode.WAIT_LIST || f.index < left.size());
                for (int i = 0; i < f.index; i++) require(left.get(i).equals(right.get(i)));
            }
            case NAMED_LEFT, NAMED_RIGHT, KEY, WAIT_NAMED -> {
                var left = named(f.a); var right = named(f.b);
                require(left.size() == right.size() && f.index <= left.size());
                for (int i = 0; i < f.index; i++) {
                    var entry = indexed(f.a).get(i); var child = right.get(entry.getKey());
                    require(child != null && entry.getValue().equals(child));
                }
                if (f.mode == Mode.NAMED_LEFT) { require(f.scan == 0 && f.offset == 0); break; }
                require(f.index < left.size()); f.aEntry = indexed(f.a).get(f.index);
                require(f.scan <= right.size());
                for (int i = 0; i < f.scan; i++) {
                    var entry = indexed(f.b).get(i);
                    if (i != f.scan - 1 || f.mode == Mode.NAMED_RIGHT) require(!f.aEntry.getKey().equals(entry.getKey()));
                    if (i == f.scan - 1 && f.mode != Mode.NAMED_RIGHT) f.bEntry = entry;
                }
                if (f.mode == Mode.NAMED_RIGHT) require(f.offset == 0);
                else {
                    require(f.scan > 0);
                    String x = f.aEntry.getKey(), y = f.bEntry.getKey();
                    DistinctTask.observeValidation(f.offset);
                    require(f.offset <= Math.min(x.length(), y.length()) && x.regionMatches(0, y, 0, f.offset));
                    if (f.mode == Mode.WAIT_NAMED) require(f.offset == x.length() && x.equals(y));
                }
            }
            case ENTER -> throw new IllegalStateException();
        }
    }
    private static DataValue wrapped(DataValue value) {
        return value instanceof DataValue.RecipeValue r ? r.projection() : ((DataValue.NbtValue)value).value();
    }
    private static List<Map.Entry<String, DataValue>> indexed(DataValue value) {
        if (value instanceof DataValue.RecordValue record) return record.indexedFields();
        if (value instanceof DataValue.MapValue map) return map.indexedEntries();
        throw failure(INVALID_CHECKPOINT, "distinct", "Named comparison requires record or map");
    }
    private static Map<String, DataValue> named(DataValue value) {
        if (value instanceof DataValue.RecordValue record) return record.fields();
        if (value instanceof DataValue.MapValue map) return map.entries();
        throw failure(INVALID_CHECKPOINT, "distinct", "Named comparison requires record or map");
    }
    private static DataValue.IntegerValue number(int value) { return new DataValue.IntegerValue(value); }
    private static int read(DataValue value) { return Math.toIntExact(((DataValue.IntegerValue)value).value()); }
    private static void require(boolean valid) { if (!valid) throw failure(INVALID_CHECKPOINT, "distinct", "Invalid typed comparison cursor"); }
}
