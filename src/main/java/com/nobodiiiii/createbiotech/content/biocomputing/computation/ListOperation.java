package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramCompiler.failure;
import java.util.*;
import java.util.function.IntFunction;

/** Pure edits of immutable data. Selection borrows source values; the machine owns copying and accounting. */
public enum ListOperation {
    REPLACE("index", INTEGER, "value", ANY),
    INSERT("index", INTEGER, "value", ANY),
    REMOVE("index", INTEGER),
    SLICE("start", INTEGER, "end", INTEGER),
    APPEND("value", ANY);

    private final Map<String, DataType> inputs;
    ListOperation(Object... signature) {
        var fields = new LinkedHashMap<String, DataType>();
        fields.put("source", new DataType.ListType(ANY));
        for (int i = 0; i < signature.length; i += 2) fields.put((String)signature[i], (DataType)signature[i + 1]);
        inputs = Collections.unmodifiableMap(fields);
    }
    public Map<String, DataType> inputs() { return inputs; }
    void requireNames(Map<?, ?> arguments, String location) {
        if (arguments == null || !arguments.keySet().equals(inputs.keySet()))
            throw failure(INVALID_GRAPH, location, "List edit arguments do not match the operation signature");
    }
    public DataType outputType(Map<String, DataType> arguments, String location) {
        requireNames(arguments, location);
        inputs.forEach((name, type) -> {
            if (arguments.get(name) == null || !type.compatibleWith(arguments.get(name)))
                throw failure(TYPE_MISMATCH, location, "Invalid list edit input: " + name);
        });
        DataType element = arguments.get("source") instanceof DataType.ListType list ? list.element() : ANY;
        if (inputs.containsKey("value") && !element.equals(arguments.get("value"))) element = ANY;
        return new DataType.ListType(element);
    }
    /** Immutable positional projection; the caller owns metering and materialization. */
    public record Selection(int size, IntFunction<DataValue> element) {}
    Selection select(Map<String, DataValue> arguments, String location) {
        requireNames(arguments, location);
        inputs.forEach((name, type) -> {
            // A list's elements may have any type; do not rescan a large input in this instruction.
            boolean valid = name.equals("source") ? arguments.get(name) instanceof DataValue.ListValue
                : arguments.get(name) != null && type.accepts(arguments.get(name));
            if (!valid)
                throw failure(TYPE_MISMATCH, location, "Invalid list edit input: " + name);
        });
        long index = inputs.containsKey("index") ? ((DataValue.IntegerValue)arguments.get("index")).value()
            : inputs.containsKey("start") ? ((DataValue.IntegerValue)arguments.get("start")).value() : 0;
        long end = inputs.containsKey("end") ? ((DataValue.IntegerValue)arguments.get("end")).value() : 0;
        return project((DataValue.ListValue)arguments.get("source"), index, end, arguments.get("value"), location);
    }

    /** Operands of this path have already been type-checked by the caller. */
    public Selection project(DataValue.ListValue input, long index, long end, DataValue value, String location) {
        var source = input.values();
        if (this == APPEND) {
            if (source.size() == Integer.MAX_VALUE) throw failure(MEMORY_LIMIT, location, "List exceeds supported representation");
            return new Selection(source.size() + 1, i -> i == source.size() ? value : source.get(i));
        }
        if (this == SLICE) {
            if (index < 0 || index > end || end > source.size())
                throw failure(INDEX_OUT_OF_BOUNDS, location, "List slice requires 0 <= start <= end <= size");
            return new Selection((int)(end - index), i -> source.get((int)index + i));
        }
        if (index < 0 || index > source.size() || (this != INSERT && index == source.size()))
            throw failure(INDEX_OUT_OF_BOUNDS, location, "List edit index is out of bounds");
        return switch (this) {
            case REPLACE -> new Selection(source.size(), i -> i == index ? value : source.get(i));
            case INSERT -> {
                if (source.size() == Integer.MAX_VALUE) throw failure(MEMORY_LIMIT, location, "List exceeds supported representation");
                yield new Selection(source.size() + 1, i -> i == index ? value : source.get(i < index ? i : i - 1));
            }
            case REMOVE -> new Selection(source.size() - 1, i -> source.get(i < index ? i : i + 1));
            case SLICE, APPEND -> throw new IllegalStateException("Non-index edit already handled");
        };
    }
}
