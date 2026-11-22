package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/** Installation validation and resource accounting. Does not evaluate code or access a world. */
public final class ProgramCompiler {
	private ProgramCompiler() {}

	public record Usage(long programUnits, long constantUnits, long initialMemoryUnits) {}
	public record CompiledGraph(ComputationGraph graph, List<String> order, DataType.RecordType outputType,
		Map<String, DataType> nodeTypes) {
		public CompiledGraph {
			order = List.copyOf(order);
			nodeTypes = DataValue.immutableMap(nodeTypes);
		}
	}

	/** Only the compiler can construct an installation; no caller can bypass validation with an IR record. */
	public static final class InstalledProgram {
		private final CompiledGraph root;
		private final Map<String, CompiledGraph> entries;
		private final Map<ModuleDefinition.Key, CompiledGraph> modules;
		private final ProgramDefinition definition;
		private final Map<String, ProgramDefinition.Cell> cells;
		private final List<ProgramDefinition.Write> writes;
		private final List<ProgramDefinition.Send> sends;
		private final ResourceLimits limits;
		private final Usage usage;

		private InstalledProgram(CompiledGraph root, Map<String, CompiledGraph> entries,
			Map<ModuleDefinition.Key, CompiledGraph> modules, ProgramDefinition definition,
			ProgramDefinition.Entry selection, ResourceLimits limits, Usage usage) {
			this.root = root;
			this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
			this.modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
			this.definition = definition;
			this.cells = definition.cells();
			this.writes = selection.writes();
			this.sends = selection.sends();
			this.limits = limits;
			this.usage = usage;
		}
		public CompiledGraph root() { return root; }
		public ProgramDefinition definition() { return definition; }
		public InstalledProgram select(String name) {
			var selected = definition.entry(name);
			var graph = name == null ? entries.get(null) : entries.get(name);
			return new InstalledProgram(graph, entries, modules, definition, selected, limits, usage);
		}
		public Map<ModuleDefinition.Key, CompiledGraph> modules() { return modules; }
		public Map<String, ProgramDefinition.Cell> cells() { return cells; }
		public List<ProgramDefinition.Write> writes() { return writes; }
		public List<ProgramDefinition.Send> sends() { return sends; }
		public ResourceLimits limits() { return limits; }
		public Usage usage() { return usage; }
		/** Re-admit immutable compiler output against this caller's current limits, never reuse permission. */
		public InstalledProgram withLimits(ResourceLimits requested) {
			Objects.requireNonNull(requested);
			try { requireCapacity(usage, requested); }
			catch (ArithmeticException overflow) { throw failure(MEMORY_LIMIT, "installation", "Resource size exceeds supported range"); }
			return limits.equals(requested) ? this : new InstalledProgram(this, requested);
		}
		private InstalledProgram(InstalledProgram source, ResourceLimits limits) {
			root = source.root; entries = source.entries; modules = source.modules; definition = source.definition;
			cells = source.cells; writes = source.writes; sends = source.sends; usage = source.usage;
			this.limits = limits;
		}
	}

	public static InstalledProgram compile(ProgramDefinition program,
		Map<ModuleDefinition.Key, ModuleDefinition> library, ResourceLimits limits) {
        requireLegacyCompatible(program.graph());
        program.entries().values().forEach(entry -> requireLegacyCompatible(entry.graph()));
        return compileProgram(program, library, limits, Map.of());
    }
    /** Named expressions for ISA lowering only. Modules and the general graph VM never acquire a procedure scope. */
    public static InstalledProgram compileFlow(ProgramDefinition program,
        Map<ModuleDefinition.Key, ModuleDefinition> library, ResourceLimits limits,
        Map<ModuleDefinition.Key, ProcedureSignature> procedures) {
        requireLegacyCompatible(program.graph());
        return compileProgram(program, library, limits, procedures);
    }
    private static void requireLegacyCompatible(ComputationGraph graph) {
        graph.nodes().forEach((id, node) -> {
            if (node instanceof ComputationGraph.Scalar s && s.operation().numericEdge()) throw failure(INVALID_GRAPH, id, "Numeric edges require typed ISA lowering");
            if (node instanceof ComputationGraph.RandomRange) throw failure(INVALID_GRAPH, id, "Random range requires typed ISA lowering");
            if (node instanceof ComputationGraph.Timer) throw failure(INVALID_GRAPH, id, "Timer requires typed ISA lowering");
            if (node instanceof ComputationGraph.Answer) throw failure(INVALID_GRAPH, id, "Terminal answer requires typed ISA lowering");
            if (node instanceof ComputationGraph.KeyDown) throw failure(INVALID_GRAPH, id, "Terminal keyboard requires typed ISA lowering");
            if (node instanceof ComputationGraph.ListSearch || node instanceof ComputationGraph.ListPosition)
                throw failure(INVALID_GRAPH, id, "List search and special positions require typed ISA lowering");
        });
    }
    private static InstalledProgram compileProgram(ProgramDefinition program,
        Map<ModuleDefinition.Key, ModuleDefinition> library, ResourceLimits limits,
        Map<ModuleDefinition.Key, ProcedureSignature> procedures) {
		Objects.requireNonNull(program);
		Objects.requireNonNull(library);
		Objects.requireNonNull(limits);
		try {
			return compileChecked(program, library, limits, Map.copyOf(procedures));
		} catch (ArithmeticException overflow) {
			throw failure(MEMORY_LIMIT, "installation", "Resource size exceeds supported range");
		}
	}

    public record ProcedureSignature(Map<String, DataType> inputs, Map<String, DataType> outputs) {
        public ProcedureSignature { inputs = DataValue.immutableMap(inputs); outputs = DataValue.immutableMap(outputs); }
    }

	private static InstalledProgram compileChecked(ProgramDefinition program,
		Map<ModuleDefinition.Key, ModuleDefinition> library, ResourceLimits limits, Map<ModuleDefinition.Key, ProcedureSignature> procedures) {
		ComputationGraph root = canonical(program.graph());
		var entryGraphs = new LinkedHashMap<String, ComputationGraph>();
        program.entries().forEach((name, entry) -> {
            var graph = canonical(entry.graph());
            entryGraphs.put(name, graph);
        });
		var definitions = new LinkedHashMap<ModuleDefinition.Key, ComputationGraph>();
		var pending = new ArrayDeque<>(moduleReferences(root));
		entryGraphs.values().forEach(graph -> pending.addAll(moduleReferences(graph)));
		while (!pending.isEmpty()) {
			var key = pending.removeFirst();
			if (definitions.containsKey(key))
				continue;
			var definition = library.get(key);
			if (definition == null || !key.equals(definition.key()))
				throw failure(UNKNOWN_MODULE, key.toString(), "Exact module revision not found");
			var graph = canonical(definition.graph());
			definitions.put(key, graph);
			pending.addAll(moduleReferences(graph));
		}

		var compiled = compileModules(definitions);
		var compiledRoot = typeGraph(root, program.cells(), compiled, false);
		var requestEntries = requestEntries(program);
		validateEffects(null, compiledRoot, program.cells(), program.writes(), program.sends(), program.entries(), requestEntries);
		var compiledEntries = new LinkedHashMap<String, CompiledGraph>();
		compiledEntries.put(null, compiledRoot);
		program.entries().forEach((name, entry) -> {
			var graph = typeGraph(entryGraphs.get(name), program.cells(), compiled, false, procedures);
			validateEffects(name, graph, program.cells(), entry.writes(), entry.sends(), program.entries(), requestEntries);
			compiledEntries.put(name, graph);
		});

		long instructions = Math.addExact(Math.addExact(root.nodes().size(), program.writes().size()), program.sends().size());
		long constants = constants(root);
		for (var entry : program.entries().entrySet()) {
			var graph = entryGraphs.get(entry.getKey());
			instructions = Math.addExact(instructions, Math.addExact(Math.addExact(graph.nodes().size(),
				entry.getValue().writes().size()), entry.getValue().sends().size()));
			constants = Math.addExact(constants, constants(graph));
		}
		for (var module : compiled.values()) {
			instructions = Math.addExact(instructions, module.order().size());
			constants = Math.addExact(constants, constants(module.graph()));
		}
		long initial = initialMemory(program.cells()).storageUnits();
		var usage = new Usage(instructions, constants, initial);
		requireCapacity(usage, limits);
		var canonicalEntries = new LinkedHashMap<String, ProgramDefinition.Entry>();
		program.entries().forEach((name, entry) -> canonicalEntries.put(name,
			new ProgramDefinition.Entry(entryGraphs.get(name), entry.writes(), entry.sends())));
		var canonicalDefinition = new ProgramDefinition(root, program.cells(), program.writes(), program.sends(), canonicalEntries);
		return new InstalledProgram(compiledRoot, compiledEntries, compiled, canonicalDefinition,
			canonicalDefinition.entry(null), limits, usage);
	}
	private static void requireCapacity(Usage usage, ResourceLimits limits) {
		if (usage.programUnits() > limits.programUnits())
			throw failure(PROGRAM_LIMIT, "installation", "Program requires " + usage.programUnits() + " units");
		// The reset image remains installed alongside the current memory image.
		if (Math.addExact(usage.constantUnits(), Math.multiplyExact(usage.initialMemoryUnits(), 2)) > limits.memoryUnits())
			throw failure(MEMORY_LIMIT, "installation", "Literal and initial memory exceed capacity");
	}

	/** Validate a closed set of portable pure modules, including preserved disconnected editor nodes. */
	public static Map<ModuleDefinition.Key, CompiledGraph> validateModules(Map<ModuleDefinition.Key, ModuleDefinition> library) {
		Objects.requireNonNull(library);
		var definitions = new LinkedHashMap<ModuleDefinition.Key, ComputationGraph>();
		library.forEach((key, definition) -> {
			if (definition == null || !key.equals(definition.key()))
				throw failure(UNKNOWN_MODULE, String.valueOf(key), "Exact module revision not found");
			var graph = definition.graph();
			if (graph.outputs().isEmpty()) throw failure(INVALID_GRAPH, key.toString(), "Module has no named output");
			for (var dependency : moduleReferences(graph))
				if (!library.containsKey(dependency)) throw failure(UNKNOWN_MODULE, dependency.toString(), "Exact module revision not found");
			definitions.put(key, ordered(graph, graph.nodes().keySet()));
		});
		return Collections.unmodifiableMap(compileModules(definitions));
	}

	private static Map<ModuleDefinition.Key, CompiledGraph> compileModules(Map<ModuleDefinition.Key, ComputationGraph> definitions) {
        // Module graph evaluation has no private history register. A caller's ISA target does not supply one.
        definitions.values().forEach(graph -> graph.nodes().forEach((id, node) -> {
            if (node instanceof ComputationGraph.Scalar s && s.operation().numericEdge())
                throw failure(INVALID_GRAPH, id, "Numeric edges require typed ISA lowering");
        }));
		// Topological module compilation also rejects recursion without recursive Java calls.
		var dependencies = new LinkedHashMap<ModuleDefinition.Key, Set<ModuleDefinition.Key>>();
		definitions.forEach((key, graph) -> dependencies.put(key, moduleReferences(graph)));
		var ready = new ArrayDeque<ModuleDefinition.Key>();
		var remaining = new HashMap<ModuleDefinition.Key, Integer>();
		var dependents = new HashMap<ModuleDefinition.Key, List<ModuleDefinition.Key>>();
		dependencies.forEach((key, refs) -> {
			remaining.put(key, refs.size());
			if (refs.isEmpty()) ready.add(key);
			refs.forEach(ref -> dependents.computeIfAbsent(ref, ignored -> new ArrayList<>()).add(key));
		});
		var compiled = new LinkedHashMap<ModuleDefinition.Key, CompiledGraph>();
		while (!ready.isEmpty()) {
			var key = ready.removeFirst();
			compiled.put(key, typeGraph(definitions.get(key), Map.of(), compiled, true));
			for (var dependent : dependents.getOrDefault(key, List.of()))
				if (remaining.compute(dependent, (ignored, count) -> count - 1) == 0)
					ready.add(dependent);
		}
		if (compiled.size() != definitions.size())
			throw failure(RECURSIVE_MODULE, "modules", "Pure modules must not call themselves recursively");

		return compiled;
	}
	private static Set<String> requestEntries(ProgramDefinition program) {
		var reachable = new HashSet<String>();
		var queue = new ArrayDeque<String>();
		if (program.entries().containsKey("request")) queue.add("request");
		while (!queue.isEmpty()) {
			var name = queue.removeFirst();
			if (!reachable.add(name)) continue;
			var entry = program.entries().get(name);
			if (entry != null) entry.sends().stream().filter(send -> send.continuation().isPresent())
				.forEach(send -> queue.add(send.replyEntry()));
		}
		return reachable;
	}
	private static void validateEffects(String entryName, CompiledGraph graph, Map<String, ProgramDefinition.Cell> cells,
		List<ProgramDefinition.Write> writes, List<ProgramDefinition.Send> sends,
		Map<String, ProgramDefinition.Entry> entries, Set<String> requestEntries) {
		var written = new HashSet<String>();
		for (var write : writes) {
			if (!written.add(write.cell()))
				throw failure(WRITE_CONFLICT, write.cell(), "Multiple writes in the same event");
			var cell = required(cells, write.cell());
			checkType(cell.type(), required(graph.outputType().fields(), write.output()), write.cell());
		}
		var requestSlots = new HashSet<String>();
		int replies = 0;
		for (var send : sends) {
			send.conditionOutput().ifPresent(name -> checkType(BOOLEAN, required(graph.outputType().fields(), name), name));
			if (send.dispatch() != ProgramDefinition.Dispatch.REPLY)
				checkType(TEXT, required(graph.outputType().fields(), send.endpointOutput()), send.endpointOutput());
			var payload = required(graph.outputType().fields(), send.payloadOutput());
			if (!(payload instanceof DataType.RecordType))
				throw failure(TYPE_MISMATCH, send.payloadOutput(), "Send payload must be a record");
			if (send.dispatch() == ProgramDefinition.Dispatch.REQUEST) {
				if (!requestSlots.add(send.requestSlot()))
					throw failure(WRITE_CONFLICT, send.requestSlot(), "Duplicate request slot in the same event");
				if (!entries.containsKey(send.replyEntry()))
					throw failure(MISSING_REFERENCE, send.replyEntry(), "Reply entry does not exist");
			}
			if (send.continuation().isPresent()) {
				var binding = send.continuation().orElseThrow();
				var capture = required(graph.outputType().fields(), binding.captureOutput());
				if (!(capture instanceof DataType.RecordType))
					throw failure(TYPE_MISMATCH, binding.captureOutput(), "Continuation capture must be a record");
				checkType(required(entries.get(send.replyEntry()).graph().inputs(), binding.captureInput()),
					capture, binding.captureInput());
			}
			if (send.requiresRequest()) {
				if (!requestEntries.contains(entryName))
					throw failure(INVALID_GRAPH, "reply", "Reply or continuation requires a request-reachable entry");
				if (send.conditionOutput().isEmpty() && ++replies > 1)
					throw failure(INVALID_GRAPH, "reply", "An event may not have multiple unconditional reply or continuation effects");
			}
		}
	}

	static DataValue.RecordValue initialMemory(Map<String, ProgramDefinition.Cell> cells) {
		var values = new LinkedHashMap<String, DataValue>();
		cells.forEach((name, cell) -> values.put(name, cell.initial()));
		return new DataValue.RecordValue(values);
	}

	private static long constants(ComputationGraph graph) {
		long units = 0;
		for (var node : graph.nodes().values())
			if (node instanceof ComputationGraph.Constant constant)
				units = Math.addExact(units, constant.value().storageUnits());
		return units;
	}

	public static Set<ModuleDefinition.Key> moduleReferences(ComputationGraph graph) {
		var refs = new LinkedHashSet<ModuleDefinition.Key>();
		for (var node : graph.nodes().values()) {
			if (node instanceof ComputationGraph.Call call) refs.add(call.module());
			if (node instanceof ComputationGraph.Branch branch) { refs.add(branch.whenTrue()); refs.add(branch.whenFalse()); }
			if (node instanceof ComputationGraph.Iterate loop) refs.add(loop.module());
			if (node instanceof ComputationGraph.Fold fold) refs.add(fold.module());
			if (node instanceof ComputationGraph.Sort sort) refs.add(sort.module());
		}
		return refs;
	}

	/** Only nodes reachable from named outputs are installed; disconnected editor drafts retain no data. */
	private static ComputationGraph canonical(ComputationGraph graph) {
		return ordered(graph, graph.outputs().values());
	}
	private static ComputationGraph ordered(ComputationGraph graph, java.util.Collection<String> roots) {
		var live = new HashSet<String>();
		var pending = new ArrayDeque<>(roots);
		while (!pending.isEmpty()) {
			String id = pending.removeFirst();
			if (live.add(id))
				pending.addAll(required(graph.nodes(), id).dependencies());
		}
		var remaining = new HashMap<String, Integer>();
		var dependents = new HashMap<String, List<String>>();
		var ready = new PriorityQueue<String>();
		for (String id : live) {
			var refs = new HashSet<>(graph.nodes().get(id).dependencies());
			remaining.put(id, refs.size());
			if (refs.isEmpty()) ready.add(id);
			refs.forEach(ref -> dependents.computeIfAbsent(ref, ignored -> new ArrayList<>()).add(id));
		}
		var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
		while (!ready.isEmpty()) {
			String id = ready.remove();
			nodes.put(id, graph.nodes().get(id));
			for (String dependent : dependents.getOrDefault(id, List.of()))
				if (remaining.compute(dependent, (ignored, count) -> count - 1) == 0)
					ready.add(dependent);
		}
		if (nodes.size() != live.size())
			throw failure(GRAPH_CYCLE, "graph", "Feedback must pass through event memory");
		return new ComputationGraph(graph.inputs(), nodes, graph.outputs());
	}

	private static CompiledGraph typeGraph(ComputationGraph graph, Map<String, ProgramDefinition.Cell> cells,
		Map<ModuleDefinition.Key, CompiledGraph> modules, boolean pure) {
        return typeGraph(graph, cells, modules, pure, Map.of());
    }
    private static CompiledGraph typeGraph(ComputationGraph graph, Map<String, ProgramDefinition.Cell> cells,
        Map<ModuleDefinition.Key, CompiledGraph> modules, boolean pure, Map<ModuleDefinition.Key, ProcedureSignature> procedures) {
		var types = new LinkedHashMap<String, DataType>();
		for (var entry : graph.nodes().entrySet())
			types.put(entry.getKey(), inferNode(entry.getValue(), types, graph.inputs(), cells, modules, pure, entry.getKey(), procedures));
		return new CompiledGraph(graph, List.copyOf(graph.nodes().keySet()),
			new DataType.RecordType(boundTypes(graph.outputs(), types)), types);
	}

	/** Advisory type inspection shares installation rules; it never evaluates or constructs an installation. */
	public static DataType inferNode(ComputationGraph.Node node, Map<String, DataType> types,
		Map<String, DataType> inputs, Map<String, ProgramDefinition.Cell> cells,
		Map<ModuleDefinition.Key, CompiledGraph> modules, boolean pure, String id) {
        return inferNode(node, types, inputs, cells, modules, pure, id, Map.of());
    }
    public static DataType inferNode(ComputationGraph.Node node, Map<String, DataType> types,
        Map<String, DataType> inputs, Map<String, ProgramDefinition.Cell> cells, Map<ModuleDefinition.Key, CompiledGraph> modules,
        boolean pure, String id, Map<ModuleDefinition.Key, ProcedureSignature> procedures) {
		return switch (node) {
				case ComputationGraph.Constant constant -> DataType.typeOf(constant.value());
				case ComputationGraph.Input input -> required(inputs, input.name());
				case ComputationGraph.ReadMemory read -> {
					if (pure) throw failure(INVALID_GRAPH, id, "Pure module cannot access computer memory");
					yield required(cells, read.cell()).type();
				}
				case ComputationGraph.Binary binary -> {
					var operation = ScalarOperation.forLegacy(binary.operation());
					yield operation.outputType(boundTypes(Map.of("left", binary.left(), "right", binary.right()), types), id);
				}
				case ComputationGraph.Text text -> text.operation().outputType(boundTypes(text.arguments(), types), id);
				case ComputationGraph.ListEdit edit -> edit.operation().outputType(boundTypes(edit.arguments(), types), id);
				case ComputationGraph.Scalar scalar -> scalar.operation().outputType(boundTypes(scalar.arguments(), types), id);
				case ComputationGraph.Field field -> fieldType(types.get(field.source()), field.name(), id);
				case ComputationGraph.DynamicField field -> {
					if (!TEXT.compatibleWith(required(types, field.name())))
						throw failure(TYPE_MISMATCH, id, "Field name requires text");
					var source = required(types, field.source());
					if (source == ANY || source instanceof DataType.RecordType) yield ANY;
					if (source instanceof DataType.MapType map) yield map.element();
					throw failure(TYPE_MISMATCH, id, "Field extraction requires a record or map");
				}
				case ComputationGraph.Keys keys -> {
					var source = types.get(keys.source());
					if (source != DataType.Scalar.ANY && !(source instanceof DataType.RecordType) && !(source instanceof DataType.MapType))
						throw failure(TYPE_MISMATCH, id, "Field enumeration requires a record or map");
					yield new DataType.ListType(DataType.Scalar.TEXT);
				}
				case ComputationGraph.HasField field -> {
					if (!TEXT.compatibleWith(required(types, field.name())))
						throw failure(TYPE_MISMATCH, id, "Field name requires text");
					var source = required(types, field.source());
					if (source == ANY || source instanceof DataType.RecordType || source instanceof DataType.MapType) yield BOOLEAN;
					throw failure(TYPE_MISMATCH, id, "Field presence requires a record or map");
				}
				case ComputationGraph.Index index -> {
					var source = types.get(index.source());
					if (source == ANY) yield ANY;
					if (source instanceof DataType.ListType list) yield list.element();
					throw failure(TYPE_MISMATCH, id, "Index extraction requires a list");
				}
				case ComputationGraph.MakeRecord record -> new DataType.RecordType(boundTypes(record.fields(), types));
				case ComputationGraph.DynamicIndex index -> {
					if (!INTEGER.compatibleWith(required(types, index.index())))
						throw failure(TYPE_MISMATCH, id, "List index requires an integer");
					var source = required(types, index.source());
					if (source == ANY) yield ANY;
					if (source instanceof DataType.ListType list) yield list.element();
					throw failure(TYPE_MISMATCH, id, "Index extraction requires a list");
				}
				case ComputationGraph.MakeList list -> {
					DataType common = null;
					for (String reference : list.elements()) {
						var next = required(types, reference);
						common = common == null ? next : common.equals(next) ? common : ANY;
					}
					yield new DataType.ListType(common == null ? ANY : common);
				}
				case ComputationGraph.ConcatLists list -> {
					var left = required(types, list.left());
					var right = required(types, list.right());
					if (left != ANY && !(left instanceof DataType.ListType)
						|| right != ANY && !(right instanceof DataType.ListType))
						throw failure(TYPE_MISMATCH, id, "List concatenation requires two lists");
					yield new DataType.ListType(left instanceof DataType.ListType a && right instanceof DataType.ListType b
						&& a.element().equals(b.element()) ? a.element() : ANY);
				}
                case ComputationGraph.ProcedureValue call -> {
                    var signature = procedures.get(call.procedure());
                    if (pure || signature == null) throw failure(MISSING_REFERENCE, id, "Unknown ISA procedure revision: " + call.procedure());
                    if (!signature.inputs().keySet().equals(call.arguments().keySet()))
                        throw failure(TYPE_MISMATCH, id, "Procedure argument slots do not match its signature");
                    var actual = boundTypes(call.arguments(), types);
                    signature.inputs().forEach((name, expected) -> {
                        if (!com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterAssignment.permits(expected, actual.get(name)))
                            throw failure(TYPE_MISMATCH, id, "Procedure argument " + name + " requires " + expected);
                    });
                    var result = signature.outputs().get(call.result());
                    if (result == null) throw failure(MISSING_REFERENCE, id, "Unknown procedure return: " + call.result());
                    yield result;
                }
				case ComputationGraph.Call call -> {
					var module = required(modules, call.module());
					checkArguments(module, boundTypes(call.arguments(), types), id);
					yield module.outputType();
				}
				case ComputationGraph.Branch branch -> {
					checkType(BOOLEAN, required(types, branch.condition()), id);
					var whenTrue = required(modules, branch.whenTrue());
					var whenFalse = required(modules, branch.whenFalse());
					if (!whenTrue.graph().inputs().equals(whenFalse.graph().inputs()))
						throw failure(TYPE_MISMATCH, id, "Branch module input declarations differ");
					var arguments = boundTypes(branch.arguments(), types);
					checkArguments(whenTrue, arguments, id);
					checkArguments(whenFalse, arguments, id);
					if (!whenTrue.outputType().fields().keySet().equals(whenFalse.outputType().fields().keySet()))
						throw failure(TYPE_MISMATCH, id, "Branch module output fields differ");
					var fields = new LinkedHashMap<String, DataType>();
					for (String field : whenTrue.outputType().fields().keySet())
						fields.put(field, ScalarOperation.SELECT.outputType(Map.of("condition", BOOLEAN,
							"when_true", whenTrue.outputType().fields().get(field),
							"when_false", whenFalse.outputType().fields().get(field)), id));
					yield new DataType.RecordType(fields);
				}
				case ComputationGraph.Fold fold -> {
					var source = required(types, fold.source());
					if (!(source instanceof DataType.ListType) && source != ANY)
						throw failure(TYPE_MISMATCH, id, "Fold requires a list");
					DataType element = source instanceof DataType.ListType list ? list.element() : ANY;
					var module = required(modules, fold.module());
					var accumulator = required(module.graph().inputs(), fold.accumulatorPort());
					var arguments = boundTypes(fold.captures(), types);
					arguments.put(fold.itemPort(), element);
					arguments.put(fold.accumulatorPort(), required(types, fold.initial()));
					checkArguments(module, arguments, id);
					checkType(accumulator, required(module.outputType().fields(), fold.resultPort()), id);
					yield accumulator;
				}
				case ComputationGraph.Iterate loop -> {
					var source = types.get(loop.source());
					if (!(source instanceof DataType.ListType) && source != ANY)
						throw failure(TYPE_MISMATCH, id, "Iteration requires a list");
					DataType element = source instanceof DataType.ListType list ? list.element() : ANY;
					var arguments = boundTypes(loop.captures(), types);
					arguments.put(loop.itemPort(), element);
					var module = required(modules, loop.module());
					checkArguments(module, arguments, id);
					DataType output = required(module.outputType().fields(), loop.resultPort());
					if (loop.mode() == ComputationGraph.Iteration.FILTER) checkType(BOOLEAN, output, id);
					yield new DataType.ListType(loop.mode() == ComputationGraph.Iteration.FILTER ? element : output);
				}
                case ComputationGraph.ListPosition position -> {
                    if (pure) throw failure(INVALID_GRAPH, id, "Special list position requires typed ISA lowering");
                    if (!(required(types, position.source()) instanceof DataType.ListType))
                        throw failure(TYPE_MISMATCH, id, "Special position requires a typed list");
                    yield INTEGER;
                }
				case ComputationGraph.ListSearch search -> {
                    if (pure) throw failure(INVALID_GRAPH, id, "List search requires typed ISA lowering");
                    if (!(required(types, search.source()) instanceof DataType.ListType))
                        throw failure(TYPE_MISMATCH, id, "List search requires a typed list");
                    yield search.resultType();
                }
                case ComputationGraph.RandomRange range -> {
                    if (pure) throw failure(INVALID_GRAPH, id, "Random range requires typed ISA lowering");
                    if (!range.type().compatibleWith(required(types, range.from()))
                        || !range.type().compatibleWith(required(types, range.through())))
                        throw failure(TYPE_MISMATCH, id, "Random bounds must match the declared numeric type");
                    yield range.type();
                }
                case ComputationGraph.Timer ignored -> Scalar.DECIMAL;
                case ComputationGraph.Answer ignored -> Scalar.TEXT;
                case ComputationGraph.KeyDown ignored -> BOOLEAN;
                case ComputationGraph.ValueEquals ignored -> BOOLEAN;
                case ComputationGraph.Distinct distinct -> {
                    var source = required(types, distinct.source());
                    if (!(source instanceof DataType.ListType) && source != ANY)
                        throw failure(TYPE_MISMATCH, id, "Distinct requires a list");
                    yield source instanceof DataType.ListType ? source : new DataType.ListType(ANY);
                }
                case ComputationGraph.Sort sort -> {
					var source = types.get(sort.source());
					if (!(source instanceof DataType.ListType) && source != ANY)
						throw failure(TYPE_MISMATCH, id, "Sort requires a list");
					DataType element = source instanceof DataType.ListType list ? list.element() : ANY;
					var module = required(modules, sort.module());
					var arguments = boundTypes(sort.captures(), types);
					arguments.put(sort.itemPort(), element);
					checkArguments(module, arguments, id);
					SortTask.requireKeyType(required(module.outputType().fields(), sort.resultPort()), id);
					yield new DataType.ListType(element);
				}
		};
	}

	private static DataType fieldType(DataType source, String name, String id) {
		if (source == ANY) return ANY;
		if (source instanceof DataType.RecordType record) {
			DataType type = record.fields().get(name);
			if (type == null) throw failure(MISSING_FIELD, id, "No field named " + name);
			return type;
		}
		if (source instanceof DataType.MapType map) return map.element();
		throw failure(TYPE_MISMATCH, id, "Field extraction requires a record or map");
	}

	private static LinkedHashMap<String, DataType> boundTypes(Map<String, String> bindings, Map<String, DataType> types) {
		var result = new LinkedHashMap<String, DataType>();
		bindings.forEach((name, ref) -> result.put(name, required(types, ref)));
		return result;
	}
	private static void checkArguments(CompiledGraph module, Map<String, DataType> arguments, String id) {
		checkType(new DataType.RecordType(module.graph().inputs()), new DataType.RecordType(arguments), id);
	}
	static void checkType(DataType expected, DataType actual, String location) {
		if (!expected.compatibleWith(actual))
			throw failure(TYPE_MISMATCH, location, "Port type does not match its declaration");
	}
	private static <K, V> V required(Map<K, V> values, K key) {
		V value = values.get(key);
		if (value == null) throw failure(MISSING_REFERENCE, key.toString(), "Reference does not exist");
		return value;
	}
	static ComputationException failure(ComputationException.Code code, String location, String message) {
		return new ComputationException(code, location, message);
	}
}
