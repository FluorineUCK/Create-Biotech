package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import java.util.*;

/** Compile-time proof that MOV needs no conversion, scan or value-dependent type check. */
public final class RegisterAssignment {
    private RegisterAssignment() {}
    public static boolean permits(DataType expected, DataType actual) {
        record Pair(DataType expected, DataType actual) {}
        var pending = new ArrayDeque<Pair>(); pending.push(new Pair(expected, actual));
        var visited = new IdentityHashMap<DataType, Set<DataType>>();
        while (!pending.isEmpty()) {
            var pair = pending.pop(); var to = pair.expected(); var from = pair.actual();
            if (to == from || to == DataType.Scalar.ANY && !com.nobodiiiii.createbiotech.content.biocomputing.computation.LocalReferences.contains(from)) continue;
            if (!visited.computeIfAbsent(to, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(from)) continue;
            switch (to) {
                case DataType.RefType ref -> { if(!ref.equals(from))return false; }
                case DataType.Semantic ignored -> { return false; }
                case DataType.Scalar ignored -> { return false; }
                case DataType.OptionalType optional -> {
                    if (from != DataType.Scalar.NULL) pending.push(new Pair(optional.value(), from instanceof DataType.OptionalType other ? other.value() : from));
                }
                case DataType.ListType list -> {
                    if (!(from instanceof DataType.ListType other)) return false;
                    pending.push(new Pair(list.element(), other.element()));
                }
                case DataType.MapType map -> {
                    if (!(from instanceof DataType.MapType other)) return false;
                    pending.push(new Pair(map.element(), other.element()));
                }
                case DataType.RecordType record -> {
                    if (!(from instanceof DataType.RecordType other) || !record.fields().keySet().equals(other.fields().keySet())) return false;
                    record.fields().forEach((key, type) -> pending.push(new Pair(type, other.fields().get(key))));
                }
            }
        }
        return true;
    }
}
