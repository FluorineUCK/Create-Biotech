package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Checks resolved authored types, not source substrings or compiler-generated transport temporaries. */
public final class AssemblyTypeRequirements {
    private AssemblyTypeRequirements() {}

    static boolean dynamic(DataType type) { return requires(type, true, true); }
    public static boolean semantic(DataType type) { return requires(type, false, true); }
    public static boolean containsAny(DataType type) { return requires(type, true, false); }
    /** Native containers, not compiler-generated software ABI or opaque device envelopes. */
    public static boolean structured(DataType type) {
        while (type instanceof DataType.OptionalType optional) type = optional.value();
        return type instanceof DataType.RecordType || type instanceof DataType.ListType || type instanceof DataType.MapType;
    }
    /** Admission-only scan of authored immutable values. Inference loses nominal presence in
     * heterogeneous containers; runtime ANY/native transport values are not scanned by admission. */
    public static boolean semantic(DataValue value) {
        var pending = new ArrayDeque<DataValue>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<DataValue, Boolean>());
        pending.push(Objects.requireNonNull(value));
        while (!pending.isEmpty()) {
            var next = pending.pop();
            if (!visited.add(next)) continue;
            switch (next) {
                case DataValue.RecipeValue ignored -> { return true; }
                case DataValue.ListValue list -> list.values().forEach(pending::push);
                case DataValue.MapValue map -> map.entries().values().forEach(pending::push);
                case DataValue.RecordValue record -> record.fields().values().forEach(pending::push);
                case DataValue.NbtValue nativeValue -> pending.push(nativeValue.value());
                default -> { }
            }
        }
        return false;
    }
    private static boolean requires(DataType type, boolean dynamic, boolean semantic) {
        var pending = new ArrayDeque<DataType>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<DataType, Boolean>());
        pending.push(type);
        while (!pending.isEmpty()) {
            var next = pending.pop();
            if (!visited.add(next)) continue;
            switch (next) {
                case DataType.RefType ref -> pending.push(ref.target());
                case DataType.Semantic ignored -> { if (semantic) return true; }
                case DataType.Scalar scalar -> { if (dynamic && scalar == DataType.Scalar.ANY) return true; }
                case DataType.ListType list -> pending.push(list.element());
                case DataType.MapType map -> pending.push(map.element());
                case DataType.OptionalType optional -> pending.push(optional.value());
                case DataType.RecordType record -> record.fields().values().forEach(pending::push);
            }
        }
        return false;
    }
}
