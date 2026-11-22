package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical executable IR, independent of block shapes, comments and canvas coordinates. */
public record ComputationGraph(Map<String, DataType> inputs, Map<String, Node> nodes, Map<String, String> outputs) {
	public ComputationGraph {
		inputs = named(inputs);
		nodes = named(nodes);
		outputs = bindings(outputs);
	}

	public sealed interface Node {
		default List<String> dependencies() {
			return switch (this) {
				case Constant ignored -> List.of();
				case Input ignored -> List.of();
				case ReadMemory ignored -> List.of();
				case Binary op -> List.of(op.left(), op.right());
				case Text op -> op.operation().inputs().keySet().stream().map(op.arguments()::get).toList();
				case Scalar op -> op.operation().inputs().keySet().stream().map(op.arguments()::get).toList();
				case Field field -> List.of(field.source());
				case DynamicField field -> List.of(field.source(), field.name());
				case HasField field -> List.of(field.source(), field.name());
				case Keys keys -> List.of(keys.source());
				case Index index -> List.of(index.source());
				case DynamicIndex index -> List.of(index.source(), index.index());
				case ListEdit edit -> List.copyOf(edit.arguments().values());
                case Distinct distinct -> List.of(distinct.source());
                case RandomRange range -> List.of(range.from(), range.through());
                case Timer ignored -> List.of();
                case Answer ignored -> List.of();
                case KeyDown ignored -> List.of();
                case ListPosition position -> List.of(position.source());
                case ListSearch search -> search.contains() ? List.of(search.source(), search.value()) : List.of(search.value(), search.source());
                case ValueEquals equality -> List.of(equality.left(), equality.right());
				case MakeRecord record -> List.copyOf(record.fields().values());
				case MakeList list -> list.elements();
				case ConcatLists list -> List.of(list.left(), list.right());
				case Call call -> List.copyOf(call.arguments().values());
                case ProcedureValue call -> List.copyOf(call.arguments().values());
				case Branch branch -> {
					var dependencies = new ArrayList<String>();
					dependencies.add(branch.condition());
					dependencies.addAll(branch.arguments().values());
					yield List.copyOf(dependencies);
				}
				case Iterate loop -> {
					var dependencies = new ArrayList<>(loop.captures().values());
					dependencies.add(loop.source());
					yield List.copyOf(dependencies);
				}
				case Fold fold -> {
					var dependencies = new ArrayList<String>();
					dependencies.add(fold.source());
					dependencies.add(fold.initial());
					dependencies.addAll(fold.captures().values());
					yield List.copyOf(dependencies);
				}
				case Sort sort -> {
					var dependencies = new ArrayList<String>();
					dependencies.add(sort.source());
					dependencies.addAll(sort.captures().values());
					yield List.copyOf(dependencies);
				}
			};
		}
	}
	public record Constant(DataValue value) implements Node {
		public Constant { Objects.requireNonNull(value); LocalReferences.portable(value); }
	}
	public record Input(String name) implements Node {
		public Input { requireName(name); }
	}
	public record ReadMemory(String cell) implements Node {
		public ReadMemory { requireName(cell); }
	}
	public enum BinaryOp { ADD_INTEGER, LESS_INTEGER, EQUAL_INTEGER }
	public record Binary(BinaryOp operation, String left, String right) implements Node {
		public Binary {
			Objects.requireNonNull(operation);
			requireName(left);
			requireName(right);
		}
	}
	public record Scalar(ScalarOperation operation, Map<String, String> arguments) implements Node {
		public Scalar {
			Objects.requireNonNull(operation);
			if (!arguments.keySet().equals(operation.inputs().keySet()))
				throw new ComputationException(ComputationException.Code.INVALID_GRAPH, "scalar", "Scalar argument names do not match operation inputs");
			for (String reference : arguments.values()) requireName(reference);
			var ordered = new java.util.LinkedHashMap<String, String>();
			for (String name : operation.inputs().keySet()) ordered.put(name, arguments.get(name));
			arguments = DataValue.immutableMap(ordered);
		}
	}
	public record Text(TextOperation operation, Map<String, String> arguments) implements Node {
        public Text {
            Objects.requireNonNull(operation);
            operation.requireNames(arguments, "text");
            var ordered=new java.util.LinkedHashMap<String,String>();
            for(String name:operation.inputs().keySet()){requireName(arguments.get(name));ordered.put(name,arguments.get(name));}
            arguments=DataValue.immutableMap(ordered);
        }
    }
	public record Field(String source, String name) implements Node {
		public Field { requireName(source); Objects.requireNonNull(name); }
	}
	/** Both fields are dependencies; the resolved text is a literal data key, not a path. */
	public record DynamicField(String source, String name) implements Node {
		public DynamicField { requireName(source); requireName(name); }
	}
	/** Presence of an exact data key, independent of the value stored under it. */
	public record HasField(String source, String name) implements Node {
		public HasField { requireName(source); requireName(name); }
	}
	/** Literal names in the current immutable record/map order, not paths or live object fields. */
	public record Keys(String source) implements Node {
		public Keys { requireName(source); }
	}
	public record Index(String source, int index) implements Node {
		public Index {
			requireName(source);
			if (index < 0)
				throw new ComputationException(ComputationException.Code.INVALID_GRAPH, source, "List index must not be negative");
		}
	}
	/** Zero-based index supplied by another node; both fields are references, not literal parameters. */
	public record DynamicIndex(String source, String index) implements Node {
		public DynamicIndex { requireName(source); requireName(index); }
	}
	public record ListEdit(ListOperation operation, Map<String, String> arguments) implements Node {
        public ListEdit {
            Objects.requireNonNull(operation);
            operation.requireNames(arguments, "list_edit");
            var ordered = new java.util.LinkedHashMap<String, String>();
            for (String name : operation.inputs().keySet()) {
                requireName(arguments.get(name)); ordered.put(name, arguments.get(name));
            }
            arguments = DataValue.immutableMap(ordered);
        }
    }
    /** First one-based exact typed match (zero on miss), or the corresponding boolean test. */
    public record ListPosition(String source, Choice choice, boolean insertion) implements Node {
        public enum Choice { LAST, RANDOM }
        public ListPosition { requireName(source); Objects.requireNonNull(choice); }
    }
    public record ListSearch(String source, String value, boolean contains) implements Node {
        public ListSearch { requireName(source); requireName(value); }
        public DataType.Scalar resultType() { return contains ? DataType.Scalar.BOOLEAN : DataType.Scalar.INTEGER; }
    }
    public record RandomRange(String from, String through, boolean decimal) implements Node {
        public RandomRange { requireName(from); requireName(through); }
        public DataType.Scalar type() { return decimal ? DataType.Scalar.DECIMAL : DataType.Scalar.INTEGER; }
    }
    /** Exact complete typed equality, without coercion. */
    public record Timer() implements Node {}
    public record Answer() implements Node {}
    public record KeyDown(String selector) implements Node {
        public KeyDown { selector = com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalKey.selector(selector); }
    }
    public record ValueEquals(String left, String right) implements Node {
        public ValueEquals { requireName(left); requireName(right); }
    }

	/** Stable typed first-occurrence deduplication of a frozen list. */
    public record Distinct(String source) implements Node {
        public Distinct { requireName(source); }
    }
	public record MakeRecord(Map<String, String> fields) implements Node {
		public MakeRecord {
			// Data keys, unlike graph IDs and port names, may be empty (e.g. serialized external data).
			fields.values().forEach(ComputationGraph::requireName);
			fields = DataValue.immutableMap(fields);
		}
	}
	/** Ordered values, not a list of world references. Empty and repeated inputs are valid. */
	public record MakeList(List<String> elements) implements Node {
		public MakeList { elements = List.copyOf(elements); elements.forEach(ComputationGraph::requireName); }
	}
	public record ConcatLists(String left, String right) implements Node {
		public ConcatLists { requireName(left); requireName(right); }
	}
    /** ISA-only synchronous value call to a fixed procedure in the same Flow document. */
    public record ProcedureValue(ModuleDefinition.Key procedure, String result, Map<String, String> arguments) implements Node {
        public ProcedureValue {
            Objects.requireNonNull(procedure); requireName(result);
            arguments = bindings(new java.util.TreeMap<>(arguments));
        }
    }
	public record Call(ModuleDefinition.Key module, Map<String, String> arguments) implements Node {
		public Call { Objects.requireNonNull(module); arguments = bindings(arguments); }
	}
	public record Branch(String condition, ModuleDefinition.Key whenTrue, ModuleDefinition.Key whenFalse,
		Map<String, String> arguments) implements Node {
		public Branch {
			requireName(condition);
			Objects.requireNonNull(whenTrue);
			Objects.requireNonNull(whenFalse);
			arguments = bindings(arguments);
		}
	}
	public enum Iteration { MAP, FILTER }
	/** Each pinned pure subgraph call computes one key; sorting returns the original items. */
	public record Sort(String source, ModuleDefinition.Key module, String itemPort,
		Map<String, String> captures, String resultPort, boolean descending) implements Node {
		public Sort {
			requireName(source);
			Objects.requireNonNull(module);
			requireName(itemPort);
			requireName(resultPort);
			captures = bindings(captures);
			if (captures.containsKey(itemPort))
				throw new ComputationException(ComputationException.Code.INVALID_GRAPH, itemPort, "Capture shadows sort item port");
		}
	}
	/** The per-item port plus named captures form the complete module input record. */
	public record Iterate(Iteration mode, String source, ModuleDefinition.Key module,
		String itemPort, Map<String, String> captures, String resultPort) implements Node {
		public Iterate {
			Objects.requireNonNull(mode);
			Objects.requireNonNull(module);
			requireName(source);
			requireName(itemPort);
			requireName(resultPort);
			captures = bindings(captures);
			if (captures.containsKey(itemPort))
				throw new ComputationException(ComputationException.Code.INVALID_GRAPH, itemPort, "Capture shadows item port");
		}
	}

	/** A pure left fold; the accumulator input declaration determines its result type. */
	public record Fold(String source, String initial, ModuleDefinition.Key module, String itemPort,
		String accumulatorPort, Map<String, String> captures, String resultPort) implements Node {
		public Fold {
			requireName(source);
			requireName(initial);
			Objects.requireNonNull(module);
			requireName(itemPort);
			requireName(accumulatorPort);
			requireName(resultPort);
			captures = bindings(captures);
			if (itemPort.equals(accumulatorPort))
				throw new ComputationException(ComputationException.Code.INVALID_GRAPH, itemPort, "Fold input ports must be different");
			if (captures.containsKey(itemPort) || captures.containsKey(accumulatorPort))
				throw new ComputationException(ComputationException.Code.INVALID_GRAPH, itemPort, "Capture shadows a fold input port");
		}
	}

	static void requireName(String name) {
		if (name == null || name.isBlank())
			throw new ComputationException(ComputationException.Code.INVALID_GRAPH, "", "Names must not be blank");
	}
	static <T> Map<String, T> named(Map<String, T> values) {
		values.keySet().forEach(ComputationGraph::requireName);
		return DataValue.immutableMap(values);
	}
	static Map<String, String> bindings(Map<String, String> values) {
		values.values().forEach(ComputationGraph::requireName);
		return named(values);
	}
}
