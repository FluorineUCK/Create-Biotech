package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A literal path within one named input, never a world address or an executable expression. */
public record ValuePath(String input, List<Step> steps) {
    public enum Failure { TYPE_MISMATCH, INDEX_OUT_OF_BOUNDS }
    /** Typed path failure preserving existing IllegalArgumentException catch sites. */
    public static final class PathException extends IllegalArgumentException {
        private final Failure failure;
        public PathException(Failure failure, String message) {
            super(message);
            this.failure = Objects.requireNonNull(failure);
        }
        public Failure failure() { return failure; }
    }
    public sealed interface Step {}
    public record Key(String name) implements Step { public Key { Objects.requireNonNull(name); } }
    public record Index(int index) implements Step {
        public Index { if (index < 0) throw new IllegalArgumentException("Negative list index"); }
    }
    public ValuePath {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Input name must not be blank");
        steps = List.copyOf(steps);
    }
    public ValuePath key(String name) { return append(new Key(name)); }
    public ValuePath index(int index) { return append(new Index(index)); }
    private ValuePath append(Step step) {
        var next = new ArrayList<>(steps); next.add(step); return new ValuePath(input, next);
    }
    /** UI validation against immutable values only; execution uses the canonical graph nodes. */
    public DataValue resolve(DataValue.RecordValue inputs) {
        DataValue value = inputs.field(input);
        for (Step step : steps) {
            if (step instanceof Key key) {
                if (value instanceof DataValue.RecipeValue recipe && key.name().equals("projection")) value = recipe.projection();
                else if (value instanceof DataValue.RecordValue record) value = record.field(key.name());
                else if (value instanceof DataValue.MapValue map) value = map.field(key.name());
                else throw new PathException(Failure.TYPE_MISMATCH, "Key requires a record or map");
            } else {
                int index = ((Index)step).index();
                if (!(value instanceof DataValue.ListValue list)) throw new PathException(Failure.TYPE_MISMATCH, "Index requires a list");
                if (index >= list.values().size()) throw new PathException(Failure.INDEX_OUT_OF_BOUNDS, "List index out of bounds");
                value = list.values().get(index);
            }
        }
        return value;
    }
    public String display() {
        var text = new StringBuilder("$[").append(BrowserLiteralDisplay.quote(input)).append(']');
        for (Step step : steps) {
            text.append('[');
            if (step instanceof Key key) text.append(BrowserLiteralDisplay.quote(key.name()));
            else text.append(((Index)step).index());
            text.append(']');
        }
        return text.toString();
    }
}

