package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Stack-safe DAG traversal: immutable sharing saves traversal work, never logical copy charges. */
final class ValueTraversal {
	private ValueTraversal() {}
	private record Visit(DataValue value, boolean expanded) {}

	static <T> T fold(DataValue root, BiFunction<DataValue, Function<DataValue, T>, T> reduce) {
		var results = new IdentityHashMap<DataValue, T>();
		var pending = new ArrayDeque<Visit>();
		pending.push(new Visit(root, false));
		while (!pending.isEmpty()) {
			var visit = pending.pop();
			if (results.containsKey(visit.value())) continue;
			if (visit.expanded()) {
				results.put(visit.value(), reduce.apply(visit.value(), results::get));
			} else {
				pending.push(new Visit(visit.value(), true));
				Iterable<DataValue> children = switch (visit.value()) {
					case DataValue.RecipeValue recipe -> java.util.List.of(recipe.projection());
					case DataValue.NbtValue nbt -> java.util.List.of(nbt.value());
					case DataValue.ListValue list -> list.values();
					case DataValue.RecordValue record -> record.fields().values();
					case DataValue.MapValue map -> map.entries().values();
					default -> java.util.List.of();
				};
				for (DataValue child : children)
					if (!results.containsKey(child)) pending.push(new Visit(child, false));
			}
		}
		return results.get(root);
	}

	static int hash(DataValue root) {
		return fold(root, (value, hashes) -> {
			if (value instanceof DataValue.RecipeValue recipe) return 97 + (recipe.version()-1) + hashes.apply(recipe.projection());
			if (value instanceof DataValue.NbtValue nbt) return 31 * nbt.tagId() + hashes.apply(nbt.value());
			if (value instanceof DataValue.ListValue list) {
				int hash = 1;
				for (DataValue child : list.values()) hash = 31 * hash + hashes.apply(child);
				return hash;
			}
			var fields = value instanceof DataValue.RecordValue record ? record.fields()
				: value instanceof DataValue.MapValue map ? map.entries() : null;
			if (fields != null) {
				int hash = 0;
				for (var field : fields.entrySet()) hash += field.getKey().hashCode() ^ hashes.apply(field.getValue());
				return hash;
			}
			return value.hashCode();
		});
	}

	static boolean equal(DataValue a, DataValue b) {
		record Pair(DataValue a, DataValue b) {}
		var pending = new ArrayDeque<Pair>();
		var visited = new IdentityHashMap<DataValue, java.util.Set<DataValue>>();
		pending.push(new Pair(a, b));
		while (!pending.isEmpty()) {
			var pair = pending.pop();
			if (pair.a() == pair.b()) continue;
			if (pair.a().getClass() != pair.b().getClass()) return false;
			if (!visited.computeIfAbsent(pair.a(), ignored ->
				java.util.Collections.newSetFromMap(new IdentityHashMap<>())).add(pair.b())) continue;
			if (pair.a() instanceof DataValue.RecipeValue left) {
                var right=(DataValue.RecipeValue)pair.b();
                if (left.version()!=right.version()) return false;
                pending.push(new Pair(left.projection(), right.projection()));
            } else if (pair.a() instanceof DataValue.NbtValue left) {
				var right = (DataValue.NbtValue) pair.b();
				if (left.tagId() != right.tagId()) return false;
				pending.push(new Pair(left.value(), right.value()));
			} else if (pair.a() instanceof DataValue.ListValue left) {
				var right = (DataValue.ListValue) pair.b();
				if (left.values().size() != right.values().size()) return false;
				for (int i = 0; i < left.values().size(); i++)
					pending.push(new Pair(left.values().get(i), right.values().get(i)));
			} else if (pair.a() instanceof DataValue.RecordValue || pair.a() instanceof DataValue.MapValue) {
				var left = pair.a() instanceof DataValue.RecordValue r ? r.fields() : ((DataValue.MapValue) pair.a()).entries();
				var right = pair.b() instanceof DataValue.RecordValue r ? r.fields() : ((DataValue.MapValue) pair.b()).entries();
				if (!left.keySet().equals(right.keySet())) return false;
				left.forEach((key, value) -> pending.push(new Pair(value, right.get(key))));
			} else if (!pair.a().equals(pair.b())) return false;
		}
		return true;
	}
}
