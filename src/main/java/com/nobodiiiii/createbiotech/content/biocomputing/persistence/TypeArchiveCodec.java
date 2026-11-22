package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Structural types also use a flat table, including deeply nested user-authored types. */
public final class TypeArchiveCodec {
	private TypeArchiveCodec() {}
	public static DataValue encode(DataType type) {
		var ids = new IdentityHashMap<DataType, Integer>();
		var rows = new ArrayList<DataValue>();
		record Visit(DataType type, boolean expanded) {}
		var stack = new ArrayDeque<Visit>();
		stack.push(new Visit(type, false));
		while (!stack.isEmpty()) {
			var visit = stack.pop();
			if (ids.containsKey(visit.type())) continue;
			if (!visit.expanded()) {
				stack.push(new Visit(visit.type(), true));
				List<DataType> children = switch (visit.type()) {
					case DataType.RefType ref -> List.of(ref.target());
                    case DataType.Semantic ignored -> List.of();
					case DataType.Scalar ignored -> List.of();
					case DataType.ListType list -> List.of(list.element());
					case DataType.MapType map -> List.of(map.element());
					case DataType.OptionalType optional -> List.of(optional.value());
					case DataType.RecordType record -> List.copyOf(record.fields().values());
				};
				for (int i = children.size() - 1; i >= 0; i--) stack.push(new Visit(children.get(i), false));
				continue;
			}
			DataValue row = switch (visit.type()) {
				case DataType.RefType ref -> obj("kind","ref","child",ids.get(ref.target()),"writable",ref.writable());
                case DataType.Semantic s -> obj("kind", "semantic", "fmt", "recipe", "version", s.version());
				case DataType.Scalar scalar -> obj("kind", "scalar", "name", scalar.name());
				case DataType.ListType list -> obj("kind", "list", "child", ids.get(list.element()));
				case DataType.MapType map -> obj("kind", "map", "child", ids.get(map.element()));
				case DataType.OptionalType optional -> obj("kind", "optional", "child", ids.get(optional.value()));
				case DataType.RecordType record -> obj("kind", "record", "fields", named(record.fields(), child -> new DataValue.IntegerValue(ids.get(child))));
			};
			ids.put(visit.type(), rows.size()); rows.add(row);
		}
		return obj("nodes", new DataValue.ListValue(rows), "root", ids.get(type));
	}
	public static DataType decode(DataValue encoded) {
		var nodes = list(at(encoded, "nodes"));
		if (nodes.isEmpty() || index(at(encoded, "root")) != nodes.size() - 1) throw ArchiveException.invalid("Invalid type root");
		var types = new ArrayList<DataType>();
		for (var node : nodes) types.add(switch (string(at(node, "kind"))) {
			case "ref" -> { if(!fields(node).keySet().equals(Set.of("kind","child","writable")))throw ArchiveException.invalid("Invalid reference type");yield new DataType.RefType((DataType.Scalar)ref(types,at(node,"child")),bool(at(node,"writable"))); }
            case "semantic" -> { try { yield DataType.Semantic.recipe(string(at(node, "fmt")),index(at(node, "version"))); }
                catch (IllegalArgumentException invalid) { throw ArchiveException.invalid("Unknown semantic type format/version"); } }
			case "scalar" -> enumeration(DataType.Scalar.class, at(node, "name"));
			case "list" -> new DataType.ListType(ref(types, at(node, "child")));
			case "map" -> new DataType.MapType(ref(types, at(node, "child")));
			case "optional" -> new DataType.OptionalType(ref(types, at(node, "child")));
			case "record" -> new DataType.RecordType(readNamed(at(node, "fields"), child -> ref(types, child)));
			default -> throw ArchiveException.invalid("Unknown type kind");
		});
		return types.getLast();
	}
	private static DataType ref(List<DataType> types, DataValue value) {
		int ref = index(value);
		if (ref >= types.size()) throw ArchiveException.invalid("Invalid or forward type reference");
		return types.get(ref);
	}
}
