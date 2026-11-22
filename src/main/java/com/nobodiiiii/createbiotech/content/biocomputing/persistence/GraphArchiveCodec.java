package com.nobodiiiii.createbiotech.content.biocomputing.persistence;

import static com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveData.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Single graph/typed-node interchange shared by programs and standalone subgraph documents. */
public final class GraphArchiveCodec {
	public static final int VERSION = 34;
	private GraphArchiveCodec() {}
	/** Keep the previous writer schema unless any bundled graph requires new operations. */
	public static int encodingVersion(Collection<ComputationGraph> graphs) {
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream())
            .anyMatch(node -> node instanceof ComputationGraph.Scalar scalar && scalar.operation().retainedEdge())) return 34;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream())
            .anyMatch(node -> node instanceof ComputationGraph.Scalar scalar && scalar.operation().numericEdge())) return 33;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.KeyDown.class::isInstance)) return 32;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.Answer.class::isInstance)) return 31;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.Timer.class::isInstance)) return 30;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream())
            .anyMatch(node -> node instanceof ComputationGraph.Scalar scalar && scalar.operation().extendedDecimalMath())) return 29;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.RandomRange.class::isInstance)) return 28;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream())
            .anyMatch(node -> node instanceof ComputationGraph.Scalar scalar && minimumScalarVersion(scalar.operation()) == 27)) return 27;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(node -> node instanceof ComputationGraph.Text t && t.operation() == TextOperation.LETTER_OF)) return 26;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.ListPosition.class::isInstance)) return 25;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.ListSearch.class::isInstance)) return 24;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(node -> node instanceof ComputationGraph.ListEdit edit && edit.operation() == ListOperation.APPEND)) return 23;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.ProcedureValue.class::isInstance)) return 22;
		if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(node -> node instanceof ComputationGraph.Scalar scalar && scalar.operation() == ScalarOperation.REQUIRE_VALUE)) return 21;
		if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(node -> node instanceof ComputationGraph.Scalar scalar && scalar.operation() == ScalarOperation.LIST_LENGTH)) return 20;
		if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.Keys.class::isInstance)) return 19;
		if (graphs.stream().flatMap(graph -> graph.nodes().values().stream())
			.anyMatch(node -> node instanceof ComputationGraph.Scalar scalar
				&& minimumScalarVersion(scalar.operation()) >= 18)) return 18;
		if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.HasField.class::isInstance)) return 17;
		if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.DynamicField.class::isInstance)) return 16;
		if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.ValueEquals.class::isInstance)) return 15;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.Distinct.class::isInstance)) return 14;
        if (graphs.stream().flatMap(graph -> graph.nodes().values().stream()).anyMatch(ComputationGraph.ListEdit.class::isInstance)) return 13;
		return graphs.stream().flatMap(graph -> graph.nodes().values().stream())
			.anyMatch(node -> node instanceof ComputationGraph.Scalar scalar
				&& minimumScalarVersion(scalar.operation()) >= 12) ? 12 : 11;
	}
	static int minimumScalarVersion(ScalarOperation operation) {
		if (operation.retainedEdge()) return 34;
		if (operation.numericEdge()) return 33;
		if (operation.extendedDecimalMath()) return 29;
		return switch (operation) {
			case MOD_INTEGER, MOD_DECIMAL -> 27;
			case REQUIRE_VALUE -> 21;
			case LIST_LENGTH -> 20;
			case IS_ERROR, ERROR_CODE, ERROR_MESSAGE -> 18;
			case SQRT_DECIMAL, POWER_DECIMAL, REMAINDER_DECIMAL, ROUND_DECIMAL_TO_INTEGER,
				CEIL_DECIMAL_TO_INTEGER, FLOOR_DECIMAL_TO_INTEGER -> 12;
			default -> 5;
		};
	}
	public static DataValue key(ModuleDefinition.Key key) { return obj("id", key.id(), "revision", key.revision()); }
	public static ModuleDefinition.Key readKey(DataValue value) { return new ModuleDefinition.Key(string(at(value, "id")), number(at(value, "revision"))); }
	public static DataValue encode(ComputationGraph graph) {
		return obj("inputs", named(graph.inputs(), TypeArchiveCodec::encode), "nodes", named(graph.nodes(), GraphArchiveCodec::node),
			"outputs", bindings(graph.outputs()));
	}
	public static ComputationGraph decode(DataValue value, long version) {
		if (version < 1 || version > VERSION) throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported graph schema version");
		return new ComputationGraph(readNamed(at(value, "inputs"), TypeArchiveCodec::decode),
			readNamed(at(value, "nodes"), node -> readNode(node, version)), readBindings(at(value, "outputs")));
	}
	private static DataValue bindings(Map<String, String> values) { return named(values, DataValue.TextValue::new); }
	private static Map<String, String> readBindings(DataValue value) { return readNamed(value, ArchiveData::string); }
	private static DataValue node(ComputationGraph.Node node) {
		return switch (node) {
			case ComputationGraph.Constant constant -> obj("kind", "constant", "value", constant.value());
			case ComputationGraph.Input input -> obj("kind", "input", "name", input.name());
			case ComputationGraph.ReadMemory memory -> obj("kind", "memory", "cell", memory.cell());
			case ComputationGraph.Binary binary -> obj("kind", "binary", "operation", binary.operation().name(), "left", binary.left(), "right", binary.right());
			case ComputationGraph.Text text -> obj("kind", "text", "operation", text.operation().name(), "arguments", bindings(text.arguments()));
            case ComputationGraph.Scalar scalar -> obj("kind", "scalar", "operation", scalar.operation().name(), "arguments", bindings(scalar.arguments()));
			case ComputationGraph.Field field -> obj("kind", "field", "source", field.source(), "name", field.name());
			case ComputationGraph.DynamicField field -> obj("kind", "dynamic_field", "source", field.source(), "name", field.name());
			case ComputationGraph.HasField field -> obj("kind", "has_field", "source", field.source(), "name", field.name());
			case ComputationGraph.Keys keys -> obj("kind", "keys", "source", keys.source());
			case ComputationGraph.Index index -> obj("kind", "index", "source", index.source(), "index", index.index());
			case ComputationGraph.DynamicIndex index -> obj("kind", "dynamic_index", "source", index.source(), "index", index.index());
            case ComputationGraph.RandomRange range -> obj("kind", "random_range", "from", range.from(), "through", range.through(), "decimal", range.decimal());
            case ComputationGraph.Timer ignored -> obj("kind", "timer");
            case ComputationGraph.Answer ignored -> obj("kind", "answer");
			case ComputationGraph.KeyDown key -> obj("kind", "key_down", "selector", key.selector());
            case ComputationGraph.ValueEquals equality -> obj("kind", "value_equals", "left", equality.left(), "right", equality.right());
            case ComputationGraph.Distinct distinct -> obj("kind", "distinct", "source", distinct.source());
            case ComputationGraph.ListPosition position -> obj("kind", "list_position", "source", position.source(),
                "choice", position.choice().name(), "insertion", position.insertion());
            case ComputationGraph.ListSearch search -> obj("kind", "list_search", "source", search.source(), "value", search.value(), "contains", search.contains());
            case ComputationGraph.ListEdit edit -> obj("kind", "list_edit", "operation", edit.operation().name(), "arguments", bindings(edit.arguments()));
			case ComputationGraph.MakeRecord record -> obj("kind", "record", "fields", bindings(record.fields()));
			case ComputationGraph.MakeList list -> obj("kind", "list", "elements", sequence(list.elements(), DataValue.TextValue::new));
			case ComputationGraph.ConcatLists list -> obj("kind", "concat_lists", "left", list.left(), "right", list.right());
            case ComputationGraph.ProcedureValue call -> obj("kind", "procedure_value", "procedure", key(call.procedure()),
                "result", call.result(), "arguments", bindings(call.arguments()));
			case ComputationGraph.Call call -> obj("kind", "call", "module", key(call.module()), "arguments", bindings(call.arguments()));
			case ComputationGraph.Branch branch -> obj("kind", "branch", "condition", branch.condition(),
				"whenTrue", key(branch.whenTrue()), "whenFalse", key(branch.whenFalse()), "arguments", bindings(branch.arguments()));
			case ComputationGraph.Fold fold -> obj("kind", "fold", "source", fold.source(), "initial", fold.initial(),
				"module", key(fold.module()), "itemPort", fold.itemPort(), "accumulatorPort", fold.accumulatorPort(),
				"captures", bindings(fold.captures()), "resultPort", fold.resultPort());
			case ComputationGraph.Iterate loop -> obj("kind", "iterate", "mode", loop.mode().name(), "source", loop.source(), "module", key(loop.module()),
				"itemPort", loop.itemPort(), "captures", bindings(loop.captures()), "resultPort", loop.resultPort());
			case ComputationGraph.Sort sort -> obj("kind", "sort", "source", sort.source(), "module", key(sort.module()),
				"itemPort", sort.itemPort(), "captures", bindings(sort.captures()), "resultPort", sort.resultPort(), "descending", sort.descending());
		};
	}
	private static ComputationGraph.Node readNode(DataValue value, long version) {
		return switch (string(at(value, "kind"))) {
			case "constant" -> new ComputationGraph.Constant(at(value, "value"));
            case "procedure_value" -> {
                if (version < 22 || !record(value).fields().keySet().equals(Set.of("kind", "procedure", "result", "arguments")))
                    throw ArchiveException.invalid("Invalid procedure value node");
                yield new ComputationGraph.ProcedureValue(readKey(at(value, "procedure")), string(at(value, "result")), readBindings(at(value, "arguments")));
            }
			case "input" -> new ComputationGraph.Input(string(at(value, "name")));
			case "memory" -> new ComputationGraph.ReadMemory(string(at(value, "cell")));
			case "binary" -> new ComputationGraph.Binary(enumeration(ComputationGraph.BinaryOp.class, at(value, "operation")), string(at(value, "left")), string(at(value, "right")));
			case "text" -> {
                if(version<8)throw ArchiveException.invalid("Text nodes are unavailable in this archive schema");
                var operation = enumeration(TextOperation.class,at(value,"operation"));
                if(operation == TextOperation.LETTER_OF && version < 26)throw ArchiveException.invalid("Letter reporter requires graph schema 26");
                yield new ComputationGraph.Text(operation,readBindings(at(value,"arguments")));
            }
            case "scalar" -> {
				var operation = enumeration(ScalarOperation.class, at(value, "operation"));
				if (version < minimumScalarVersion(operation))
					throw ArchiveException.invalid("Scalar operation is unavailable in this archive schema");
				yield new ComputationGraph.Scalar(operation, readBindings(at(value, "arguments")));
			}
			case "field" -> new ComputationGraph.Field(string(at(value, "source")), string(at(value, "name")));
			case "dynamic_field" -> {
				if (version < 16) throw ArchiveException.invalid("Dynamic fields are unavailable in this archive schema");
				if (!record(value).fields().keySet().equals(Set.of("kind", "source", "name")))
					throw ArchiveException.invalid("Invalid dynamic field bindings");
				yield new ComputationGraph.DynamicField(string(at(value, "source")), string(at(value, "name")));
			}
			case "keys" -> {
				if (version < 19) throw ArchiveException.invalid("Field enumeration is unavailable in this archive schema");
				if (!record(value).fields().keySet().equals(Set.of("kind", "source")))
					throw ArchiveException.invalid("Invalid field enumeration bindings");
				yield new ComputationGraph.Keys(string(at(value, "source")));
			}
			case "has_field" -> {
				if (version < 17) throw ArchiveException.invalid("Field presence is unavailable in this archive schema");
				if (!record(value).fields().keySet().equals(Set.of("kind", "source", "name")))
					throw ArchiveException.invalid("Invalid field presence bindings");
				yield new ComputationGraph.HasField(string(at(value, "source")), string(at(value, "name")));
			}
			case "index" -> new ComputationGraph.Index(string(at(value, "source")), index(at(value, "index")));
			case "dynamic_index" -> {
				if (version < 11) throw ArchiveException.invalid("Dynamic index is unavailable in this archive schema");
				if (!record(value).fields().keySet().equals(Set.of("kind", "source", "index")))
					throw ArchiveException.invalid("Invalid dynamic index fields");
				yield new ComputationGraph.DynamicIndex(string(at(value, "source")), string(at(value, "index")));
			}
            case "random_range" -> {
                if (version < 28 || !record(value).fields().keySet().equals(Set.of("kind", "from", "through", "decimal")))
                    throw ArchiveException.invalid("Invalid random range node (requires graph schema 28)");
                yield new ComputationGraph.RandomRange(string(at(value, "from")), string(at(value, "through")), bool(at(value, "decimal")));
            }
            case "timer" -> {
                if (version < 30 || !record(value).fields().keySet().equals(Set.of("kind"))) throw ArchiveException.invalid("Timer requires graph schema 30");
                yield new ComputationGraph.Timer();
            }
            case "answer" -> {
                if (version < 31 || !record(value).fields().keySet().equals(Set.of("kind"))) throw ArchiveException.invalid("Answer requires graph schema 31");
                yield new ComputationGraph.Answer();
            }
            case "key_down" -> {
                if (version < 32 || !record(value).fields().keySet().equals(Set.of("kind", "selector"))) throw ArchiveException.invalid("Key down requires graph schema 32");
                yield new ComputationGraph.KeyDown(string(at(value, "selector")));
            }
            case "value_equals" -> {
                if (version < 15) throw ArchiveException.invalid("Value equality is unavailable in this archive schema");
                if (!record(value).fields().keySet().equals(Set.of("kind", "left", "right"))) throw ArchiveException.invalid("Invalid value equality fields");
                yield new ComputationGraph.ValueEquals(string(at(value, "left")), string(at(value, "right")));
            }
            case "distinct" -> {
                if (version < 14) throw ArchiveException.invalid("Distinct is unavailable in this archive schema");
                if (!record(value).fields().keySet().equals(Set.of("kind", "source"))) throw ArchiveException.invalid("Invalid distinct fields");
                yield new ComputationGraph.Distinct(string(at(value, "source")));
            }
            case "list_position" -> {
                if (version < 25) throw ArchiveException.invalid("Special positions are unavailable in this archive schema");
                if (!fields(value).keySet().equals(Set.of("kind", "source", "choice", "insertion")))
                    throw ArchiveException.invalid("Invalid list position fields");
                yield new ComputationGraph.ListPosition(string(at(value, "source")),
                    enumeration(ComputationGraph.ListPosition.Choice.class, at(value, "choice")), bool(at(value, "insertion")));
            }
            case "list_search" -> {
                if (version < 24) throw ArchiveException.invalid("List search is unavailable in this archive schema");
                if (!record(value).fields().keySet().equals(Set.of("kind", "source", "value", "contains")))
                    throw ArchiveException.invalid("Invalid list search fields");
                yield new ComputationGraph.ListSearch(string(at(value, "source")), string(at(value, "value")), bool(at(value, "contains")));
            }
            case "list_edit" -> {
                if (version < 13) throw ArchiveException.invalid("List edits are unavailable in this archive schema");
                if (!record(value).fields().keySet().equals(Set.of("kind", "operation", "arguments")))
                    throw ArchiveException.invalid("Invalid list edit fields");
                var operation = enumeration(ListOperation.class, at(value, "operation"));
                if (operation == ListOperation.APPEND && version < 23) throw ArchiveException.invalid("List append requires graph schema 23");
                yield new ComputationGraph.ListEdit(operation, readBindings(at(value, "arguments")));
            }
			case "record" -> new ComputationGraph.MakeRecord(readBindings(at(value, "fields")));
			case "list" -> {
				if (version < 10) throw ArchiveException.invalid("List composition is unavailable in this archive schema");
				yield new ComputationGraph.MakeList(list(at(value, "elements")).stream().map(ArchiveData::string).toList());
			}
			case "concat_lists" -> {
				if (version < 10) throw ArchiveException.invalid("List composition is unavailable in this archive schema");
				yield new ComputationGraph.ConcatLists(string(at(value, "left")), string(at(value, "right")));
			}
			case "call" -> new ComputationGraph.Call(readKey(at(value, "module")), readBindings(at(value, "arguments")));
			case "branch" -> {
				if (version < 6) throw ArchiveException.invalid("Branch nodes are unavailable in this archive schema");
				yield new ComputationGraph.Branch(string(at(value, "condition")), readKey(at(value, "whenTrue")),
					readKey(at(value, "whenFalse")), readBindings(at(value, "arguments")));
			}
			case "fold" -> {
				if (version < 7) throw ArchiveException.invalid("Fold nodes are unavailable in this archive schema");
				yield new ComputationGraph.Fold(string(at(value, "source")), string(at(value, "initial")), readKey(at(value, "module")),
					string(at(value, "itemPort")), string(at(value, "accumulatorPort")), readBindings(at(value, "captures")), string(at(value, "resultPort")));
			}
			case "iterate" -> new ComputationGraph.Iterate(enumeration(ComputationGraph.Iteration.class, at(value, "mode")), string(at(value, "source")),
				readKey(at(value, "module")), string(at(value, "itemPort")), readBindings(at(value, "captures")), string(at(value, "resultPort")));
			case "sort" -> {
				if (version < 9) throw ArchiveException.invalid("Sort nodes are unavailable in this archive schema");
				yield new ComputationGraph.Sort(string(at(value, "source")), readKey(at(value, "module")), string(at(value, "itemPort")),
					readBindings(at(value, "captures")), string(at(value, "resultPort")), bool(at(value, "descending")));
			}
			default -> throw ArchiveException.invalid("Unknown node kind");
		};
	}
}
