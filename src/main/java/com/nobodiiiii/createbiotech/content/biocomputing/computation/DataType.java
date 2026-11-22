package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Structural port types. ANY is an explicit dynamic boundary, checked again by consuming operations. */
public sealed interface DataType {
	/** Nominal schemas; adding a layout never reinterprets an installed recipe@1 image. */
	enum Semantic implements DataType {
		RECIPE(1), RECIPE_V2(2);
		private final int version;
		Semantic(int version) { this.version = version; }
		public int version() { return version; }
		public static Semantic recipe(String fmt, int version) {
			if ("recipe".equals(fmt)) {
				if (version == 1) return RECIPE;
				if (version == 2) return RECIPE_V2;
			}
			throw new IllegalArgumentException("Unknown semantic format: " + fmt + "@" + version);
		}
	}
	record RefType(Scalar target, boolean writable) implements DataType {
        public RefType { if(target!=Scalar.INTEGER && target!=Scalar.BOOLEAN && target!=Scalar.TEXT && target!=Scalar.NBT) throw new IllegalArgumentException("Unsupported local reference target"); }
    }
    enum Scalar implements DataType { ANY, BOOLEAN, INTEGER, DECIMAL, TEXT, NULL, ERROR, NBT }
	record ListType(DataType element) implements DataType {
		public ListType { Objects.requireNonNull(element); }
		@Override public boolean equals(Object other) { return other instanceof ListType type && relate(this, type, false); }
		@Override public int hashCode() { return structuralHash(this); }
		@Override public String toString() { return "ListType"; }
	}
	record MapType(DataType element) implements DataType {
		public MapType { Objects.requireNonNull(element); }
		@Override public boolean equals(Object other) { return other instanceof MapType type && relate(this, type, false); }
		@Override public int hashCode() { return structuralHash(this); }
		@Override public String toString() { return "MapType"; }
	}
	record RecordType(Map<String, DataType> fields) implements DataType {
		public RecordType { fields = DataValue.immutableMap(fields); }
		@Override public boolean equals(Object other) { return other instanceof RecordType type && relate(this, type, false); }
		@Override public int hashCode() { return structuralHash(this); }
		@Override public String toString() { return "RecordType"; }
	}
	record OptionalType(DataType value) implements DataType {
		public OptionalType { Objects.requireNonNull(value); }
		@Override public boolean equals(Object other) { return other instanceof OptionalType type && relate(this, type, false); }
		@Override public int hashCode() { return structuralHash(this); }
		@Override public String toString() { return "OptionalType"; }
	}

	default boolean accepts(DataValue value) {
		record Check(DataType type, DataValue value) {}
		var pending = new ArrayDeque<Check>();
		var visited = new IdentityHashMap<DataType, Set<DataValue>>();
		pending.push(new Check(this, Objects.requireNonNull(value)));
		while (!pending.isEmpty()) {
			var check = pending.pop();
			if (!visited.computeIfAbsent(check.type(), ignored ->
				Collections.newSetFromMap(new IdentityHashMap<>())).add(check.value())) continue;
			DataValue actual = check.value();
			switch (check.type()) {
				case RefType ref -> { if (!(actual instanceof DataValue.LocalReference handle) || !ref.equals(handle.type())) return false; }
                case Semantic s -> { if (!(actual instanceof DataValue.RecipeValue r) || r.version() != s.version()) return false; }
				case Scalar scalar -> {
					boolean matches = switch (scalar) {
						case ANY -> actual.localReferences() == 0;
						case NBT -> actual instanceof DataValue.NbtValue;
						case BOOLEAN -> actual instanceof DataValue.BooleanValue;
						case INTEGER -> actual instanceof DataValue.IntegerValue;
						case DECIMAL -> actual instanceof DataValue.DecimalValue;
						case TEXT -> actual instanceof DataValue.TextValue;
						case NULL -> actual == DataValue.NullValue.INSTANCE;
						case ERROR -> actual instanceof DataValue.ErrorValue;
					};
					if (!matches) return false;
				}
				case OptionalType optional -> {
					if (actual != DataValue.NullValue.INSTANCE) pending.push(new Check(optional.value(), actual));
				}
				case ListType list -> {
					if (!(actual instanceof DataValue.ListValue items)) return false;
					for (DataValue item : items.values()) pending.push(new Check(list.element(), item));
				}
				case MapType map -> {
					if (!(actual instanceof DataValue.MapValue items)) return false;
					for (DataValue item : items.entries().values()) pending.push(new Check(map.element(), item));
				}
				case RecordType record -> {
					if (!(actual instanceof DataValue.RecordValue fields)
						|| !record.fields().keySet().equals(fields.fields().keySet())) return false;
					record.fields().forEach((key, type) -> pending.push(new Check(type, fields.fields().get(key))));
				}
			}
		}
		return true;
	}

	/** Checks known structure; dynamic leaves are checked by the consuming operation at runtime. */
	default boolean compatibleWith(DataType actual) {
		return relate(this, Objects.requireNonNull(actual), true);
	}

	private static boolean relate(DataType expected, DataType actual, boolean dynamic) {
		record Pair(DataType expected, DataType actual) {}
		var pending = new ArrayDeque<Pair>();
		var visited = new IdentityHashMap<DataType, Set<DataType>>();
		pending.push(new Pair(expected, actual));
		while (!pending.isEmpty()) {
			var pair = pending.pop();
			DataType left = pair.expected(), right = pair.actual();
            if(dynamic && (left==Scalar.ANY && LocalReferences.contains(right)||right==Scalar.ANY && LocalReferences.contains(left)))return false;
			if (left == right || dynamic && !(left instanceof RefType) && !(right instanceof RefType) && (left == Scalar.ANY || right == Scalar.ANY)) continue;
			if (!visited.computeIfAbsent(left, ignored ->
				Collections.newSetFromMap(new IdentityHashMap<>())).add(right)) continue;
			switch (left) {
				case RefType ref -> { if (!ref.equals(right)) return false; }
                case Semantic ignored -> { return false; }
				case Scalar ignored -> { return false; }
				case OptionalType optional -> {
					if (dynamic && right == Scalar.NULL) continue;
					if (right instanceof OptionalType other) pending.push(new Pair(optional.value(), other.value()));
					else if (dynamic) pending.push(new Pair(optional.value(), right));
					else return false;
				}
				case ListType list -> {
					if (!(right instanceof ListType other)) return false;
					pending.push(new Pair(list.element(), other.element()));
				}
				case MapType map -> {
					if (!(right instanceof MapType other)) return false;
					pending.push(new Pair(map.element(), other.element()));
				}
				case RecordType record -> {
					if (!(right instanceof RecordType other)
						|| !record.fields().keySet().equals(other.fields().keySet())) return false;
					record.fields().forEach((key, type) -> pending.push(new Pair(type, other.fields().get(key))));
				}
			}
		}
		return true;
	}

	static DataType typeOf(DataValue value) {
		return ValueTraversal.<DataType>fold(value, (item, types) -> switch (item) {
			case DataValue.LocalReference ref -> ref.type();
            case DataValue.RecipeValue r -> Semantic.recipe(r.fmt(), r.version());
			case DataValue.NbtValue ignored -> Scalar.NBT;
			case DataValue.BooleanValue ignored -> Scalar.BOOLEAN;
			case DataValue.IntegerValue ignored -> Scalar.INTEGER;
			case DataValue.DecimalValue ignored -> Scalar.DECIMAL;
			case DataValue.TextValue ignored -> Scalar.TEXT;
			case DataValue.NullValue ignored -> Scalar.NULL;
			case DataValue.ErrorValue ignored -> Scalar.ERROR;
			case DataValue.ListValue list -> new ListType(commonType(list.values(), types));
			case DataValue.MapValue map -> new MapType(commonType(map.entries().values(), types));
			case DataValue.RecordValue record -> {
				var fields = new LinkedHashMap<String, DataType>();
				record.fields().forEach((name, child) -> fields.put(name, types.apply(child)));
				yield new RecordType(fields);
			}
		});
	}

	private static DataType commonType(Iterable<DataValue> values, Function<DataValue, DataType> types) {
		DataType common = null;
		for (DataValue value : values) {
			DataType next = types.apply(value);
			if (common != null && !relate(common, next, false)) return Scalar.ANY;
			common = next;
		}
		return common == null ? Scalar.ANY : common;
	}
	private static int structuralHash(DataType root) {
		record Visit(DataType type, boolean expanded) {}
		var pending = new ArrayDeque<Visit>();
		var hashes = new IdentityHashMap<DataType, Integer>();
		pending.push(new Visit(root, false));
		while (!pending.isEmpty()) {
			var visit = pending.pop();
			DataType type = visit.type();
			if (hashes.containsKey(type)) continue;
			if (!visit.expanded()) {
				pending.push(new Visit(type, true));
				Iterable<DataType> children = switch (type) {
					case ListType list -> java.util.List.of(list.element());
					case MapType map -> java.util.List.of(map.element());
					case OptionalType optional -> java.util.List.of(optional.value());
					case RecordType record -> record.fields().values();
					case RefType ignored -> java.util.List.of();
                    case Semantic ignored -> java.util.List.of();
					case Scalar ignored -> java.util.List.of();
				};
				for (DataType child : children)
					if (!hashes.containsKey(child)) pending.push(new Visit(child, false));
			} else {
				int hash = switch (type) {
					case RefType ref -> ref.hashCode();
                    case Semantic semantic -> semantic.hashCode();
					case Scalar scalar -> scalar.hashCode();
					case ListType list -> 31 + hashes.get(list.element());
					case MapType map -> 37 + hashes.get(map.element());
					case OptionalType optional -> 41 + hashes.get(optional.value());
					case RecordType record -> {
						int sum = 0;
						for (var field : record.fields().entrySet()) sum += field.getKey().hashCode() ^ hashes.get(field.getValue());
						yield sum;
					}
				};
				hashes.put(type, hash);
			}
		}
		return hashes.get(root);
	}

}
