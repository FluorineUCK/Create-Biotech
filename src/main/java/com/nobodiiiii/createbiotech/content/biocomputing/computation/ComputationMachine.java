package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramCompiler.failure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A single-thread-confined computer. tick() is a work slice, not a world tick hook.
 * Values remain frozen across slices; only successful event completion replaces memory.
 */
public final class ComputationMachine {
	public enum Status { IDLE, RUNNING, SUCCEEDED, FAILED }
	public record Diagnostic(ComputationException.Code code, String location, String message) {}
	public record Report(Status status, long workUnits, long peakMemoryUnits,
		Optional<DataValue.RecordValue> output, Optional<Diagnostic> diagnostic) {}

	private final ProgramCompiler.InstalledProgram installation;
	private final Deque<Frame> frames = new ArrayDeque<>();
	private DataValue.RecordValue memory;
	private DataValue.RecordValue output;
	private Diagnostic diagnostic;
	private Status status = Status.IDLE;
	private boolean paused;
	private long work;
	private long arenaUnits;

	public ComputationMachine(ProgramCompiler.InstalledProgram installation) {
		this.installation = Objects.requireNonNull(installation);
		reset();
	}

	public DataValue.RecordValue memory() { return memory; }
	public boolean paused() { return paused; }
	public void setPaused(boolean paused) { this.paused = paused; }
	public Report report() {
		return new Report(status, work, arenaUnits, Optional.ofNullable(output), Optional.ofNullable(diagnostic));
	}

	public MachineCheckpoint checkpoint() {
		var states = new ArrayList<MachineCheckpoint.FrameState>();
		for (var iterator = frames.descendingIterator(); iterator.hasNext();) {
			Frame frame = iterator.next();
			if (frame instanceof GraphFrame graph)
				states.add(new MachineCheckpoint.GraphState(graph.module, graph.cursor, graph.inputs,
					new DataValue.RecordValue(graph.values)));
			else if (frame instanceof LoopFrame loop)
				states.add(new MachineCheckpoint.LoopState(loop.index, new DataValue.ListValue(loop.results), Optional.ofNullable(loop.returned)));
			else if (frame instanceof FoldFrame fold)
				states.add(new MachineCheckpoint.FoldState(fold.index, fold.accumulator, Optional.ofNullable(fold.returned)));
			else if (frame instanceof SortFrame sort)
				states.add(new MachineCheckpoint.SortState(sort.index, new DataValue.ListValue(sort.keys),
					Optional.ofNullable(sort.returned), sort.task == null ? Optional.empty() : Optional.of(sort.task.checkpoint())));
			else if (frame instanceof ValueEqualityFrame equality)
                states.add(equality.task.checkpointState());
            else if (frame instanceof DistinctFrame distinct)
                states.add(distinct.task.checkpointState());
            else if (frame instanceof TextFrame text)
                states.add(new MachineCheckpoint.TextState(text.task.checkpoint()));
			else if (frame instanceof ListFrame list)
				states.add(new MachineCheckpoint.ListState(list.results.size()));
			else if (frame instanceof CommitFrame commit)
				states.add(new MachineCheckpoint.CommitState(commit.cursor, commit.result));
		}
		return new MachineCheckpoint(memory, status, paused, work, arenaUnits, Optional.ofNullable(output),
			Optional.ofNullable(diagnostic), states);
	}

	/** Starts a new idle machine with a persisted RAM image, not an in-flight event. */
	public static ComputationMachine withMemory(ProgramCompiler.InstalledProgram installation, DataValue.RecordValue memory) {
		Objects.requireNonNull(installation);
		Objects.requireNonNull(memory);
		try {
			var machine = new ComputationMachine(installation);
			machine.validateMemory(memory);
			machine.memory = memory;
			requireCheckpoint(machine.baseUnits() <= installation.limits().memoryUnits(), "Persisted memory exceeds hardware capacity");
			return machine;
		} catch (ArithmeticException overflow) {
			throw failure(INVALID_CHECKPOINT, "checkpoint", "Persisted memory size exceeds supported range");
		}
	}

	/** Validates and reconstructs a continuation without executing instructions or reserving work. */
	public static ComputationMachine restore(ProgramCompiler.InstalledProgram installation, MachineCheckpoint checkpoint) {
		Objects.requireNonNull(installation);
		Objects.requireNonNull(checkpoint);
		try {
			var machine = new ComputationMachine(installation);
			machine.validateMemory(checkpoint.memory());
			machine.memory = checkpoint.memory();
			machine.status = checkpoint.status();
			machine.paused = checkpoint.paused();
			machine.work = checkpoint.workUnits();
			machine.arenaUnits = checkpoint.arenaUnits();
			machine.output = checkpoint.output().orElse(null);
			machine.diagnostic = checkpoint.diagnostic().orElse(null);
			machine.validateHeader(checkpoint);
			long liveUnits = switch (machine.status) {
				case IDLE -> {
					requireCheckpoint(machine.baseUnits() <= installation.limits().memoryUnits(), "Persisted memory exceeds hardware capacity");
					yield 0;
				}
				case RUNNING -> Math.addExact(machine.baseUnits(), machine.restoreFrames(checkpoint.frames()));
				case SUCCEEDED -> {
					requireCheckpoint(installation.root().outputType().accepts(machine.output), "Invalid successful output schema");
					for (var write : installation.writes())
						requireCheckpoint(machine.memory.field(write.cell()).equals(machine.output.field(write.output())),
							"Successful output does not match committed memory");
					// The saved image is already the NEW memory. Old-memory copies cannot be charged at its size.
					yield Math.addExact(machine.baseUnits(), machine.output.storageUnits());
				}
				case FAILED -> machine.work == 0
					? Math.addExact(installation.usage().constantUnits(), installation.usage().initialMemoryUnits())
					: machine.baseUnits();
			};
			requireCheckpoint(liveUnits <= machine.arenaUnits, "Arena underreports live charged data");
			return machine;
		} catch (ArithmeticException overflow) {
			throw failure(INVALID_CHECKPOINT, "checkpoint", "Checkpoint size exceeds supported range");
		} catch (ComputationException error) {
			if (error.code() == INVALID_CHECKPOINT) throw error;
			throw failure(INVALID_CHECKPOINT, "checkpoint", error.getMessage());
		}
	}

	private void validateHeader(MachineCheckpoint checkpoint) {
        requireCheckpoint(output==null||output.localReferences()==0,"Local references require their typed owner, not graph output");
		requireCheckpoint(work >= 0 && arenaUnits >= 0 && arenaUnits <= installation.limits().memoryUnits(), "Invalid resource counters");
		requireCheckpoint((status == Status.RUNNING) == !checkpoint.frames().isEmpty(), "Invalid stack for machine status");
		requireCheckpoint((status == Status.SUCCEEDED) == (output != null), "Output is required only for success");
		requireCheckpoint((status == Status.FAILED) == (diagnostic != null), "Diagnostic is required only for failure");
		if (diagnostic != null)
			requireCheckpoint(diagnostic.code() != null && diagnostic.location() != null && diagnostic.message() != null, "Incomplete diagnostic");
		if (status == Status.IDLE) requireCheckpoint(work == 0 && arenaUnits == 0, "Idle machine has execution charges");
	}

	private void validateMemory(DataValue.RecordValue image) {
        requireCheckpoint(image.localReferences()==0,"Local references cannot enter graph memory");
		requireCheckpoint(image.fields().keySet().equals(installation.cells().keySet()), "Memory cell schema differs from installation");
		installation.cells().forEach((name, cell) ->
			requireCheckpoint(cell.type().accepts(image.field(name)), "Memory cell type differs from installation: " + name));
	}

	private long baseUnits() {
		return Math.addExact(Math.addExact(installation.usage().constantUnits(), installation.usage().initialMemoryUnits()), memory.storageUnits());
	}

	private long restoreFrames(List<MachineCheckpoint.FrameState> states) {
		long units = 0;
		for (var state : states) {
			Frame parent = frames.peek();
			switch (state) {
				case MachineCheckpoint.GraphState graph -> {
					var restored = restoreGraph(graph, parent);
					units = Math.addExact(units, graph.inputs().storageUnits());
					// The execution map has no charged record header or node-name keys.
					for (var value : graph.values().fields().values()) units = Math.addExact(units, value.storageUnits());
					frames.push(restored);
				}
				case MachineCheckpoint.LoopState loop -> {
					requireCheckpoint(parent instanceof GraphFrame, "Loop must follow a pending graph iteration");
					var graph = (GraphFrame) parent;
					var node = pendingNode(graph);
					requireCheckpoint(node instanceof ComputationGraph.Iterate, "Loop parent is not an iteration");
					var operation = (ComputationGraph.Iterate) node;
					var source = graph.values.get(operation.source());
					requireCheckpoint(source instanceof DataValue.ListValue, "Iteration source is not a list");
					var restored = new LoopFrame(operation, (DataValue.ListValue) source, bind(operation.captures(), graph.values), graph.location());
					validateLoop(loop, restored, graph);
					restored.index = loop.index();
					restored.results.addAll(loop.results().values());
					restored.returned = loop.returned().orElse(null);
					units = Math.addExact(units, loop.results().storageUnits());
					if (restored.returned != null) units = Math.addExact(units, restored.returned.storageUnits());
					frames.push(restored);
				}
				case MachineCheckpoint.FoldState fold -> {
					requireCheckpoint(parent instanceof GraphFrame, "Fold must follow a pending graph operation");
					var graph = (GraphFrame) parent;
					var node = pendingNode(graph);
					requireCheckpoint(node instanceof ComputationGraph.Fold, "Fold parent is not a fold operation");
					var operation = (ComputationGraph.Fold) node;
					var source = graph.values.get(operation.source());
					requireCheckpoint(source instanceof DataValue.ListValue, "Fold source is not a list");
					var initial = graph.values.get(operation.initial());
					var restored = new FoldFrame(operation, (DataValue.ListValue) source, initial,
						bind(operation.captures(), graph.values), graph.location());
					requireCheckpoint(fold.index() >= 0 && fold.index() <= restored.source.values().size(), "Invalid fold index");
					requireCheckpoint(restored.accumulatorType.accepts(fold.accumulator()), "Invalid fold accumulator type");
					requireCheckpoint(fold.index() != 0 || fold.accumulator().equals(initial), "Initial fold accumulator differs from frozen initial value");
					restored.index = fold.index();
					restored.accumulator = fold.accumulator();
					restored.returned = fold.returned().orElse(null);
					if (restored.returned != null) {
						requireCheckpoint(restored.index < restored.source.values().size(), "Completed fold has a pending return");
						requireCheckpoint(installation.modules().get(operation.module()).outputType().accepts(restored.returned),
							"Invalid fold return record");
					}
					units = Math.addExact(units, restored.accumulator.storageUnits());
					if (restored.returned != null) units = Math.addExact(units, restored.returned.storageUnits());
					frames.push(restored);
				}
				case MachineCheckpoint.SortState sort -> {
					requireCheckpoint(parent instanceof GraphFrame, "Sort must follow a pending graph operation");
					var graph = (GraphFrame) parent;
					requireCheckpoint(pendingNode(graph) instanceof ComputationGraph.Sort, "Sort parent is not a sort operation");
					var operation = (ComputationGraph.Sort) pendingNode(graph);
					var source = graph.values.get(operation.source());
					requireCheckpoint(source instanceof DataValue.ListValue, "Sort source is not a list");
					var restored = new SortFrame(operation, (DataValue.ListValue) source, bind(operation.captures(), graph.values), graph.location());
					requireCheckpoint(sort.index() >= 0 && sort.index() <= restored.source.values().size()
						&& sort.keys().values().size() == sort.index(), "Sort key count does not match progress");
					var module = installation.modules().get(operation.module());
					var keyType = module.outputType().fields().get(operation.resultPort());
					for (var key : sort.keys().values()) {
						requireCheckpoint(keyType.accepts(key), "Invalid sort key type");
						SortTask.requireKey(key, sort.keys().values().getFirst(), graph.location());
					}
					restored.index = sort.index(); restored.keys.addAll(sort.keys().values());
					restored.returned = sort.returned().orElse(null);
					units = Math.addExact(units, sort.keys().storageUnits());
					if (restored.returned != null) {
						requireCheckpoint(sort.index() < restored.source.values().size()
							&& module.outputType().accepts(restored.returned), "Invalid sort key return");
						units = Math.addExact(units, restored.returned.storageUnits());
					}
					if (sort.progress().isPresent()) {
						requireCheckpoint(sort.index() == restored.source.values().size() && restored.returned == null,
							"Sort cannot merge before all key calls have returned");
						restored.task = SortTask.restore(restored.source, sort.keys(), operation.descending(), sort.progress().orElseThrow(), graph.location());
						units = Math.addExact(units, restored.task.liveUnits());
					}
					frames.push(restored);
				}
				case MachineCheckpoint.ValueEqualityState equality -> {
                    requireCheckpoint(parent instanceof GraphFrame, "Value equality must follow a pending graph operation");
                    var graph = (GraphFrame) parent;
                    requireCheckpoint(pendingNode(graph) instanceof ComputationGraph.ValueEquals, "Value equality parent is not value equality");
                    var node = (ComputationGraph.ValueEquals) pendingNode(graph);
                    var task = ValueEqualityTask.restore(graph.values.get(node.left()), graph.values.get(node.right()), equality, graph.location());
                    units = Math.addExact(units, task.liveUnits());
                    frames.push(new ValueEqualityFrame(task, graph.location()));
                }
                case MachineCheckpoint.DistinctState distinct -> {
                    requireCheckpoint(parent instanceof GraphFrame, "Distinct must follow a pending graph operation");
                    var graph = (GraphFrame) parent;
                    requireCheckpoint(pendingNode(graph) instanceof ComputationGraph.Distinct, "Distinct parent is not distinct");
                    var node = (ComputationGraph.Distinct) pendingNode(graph);
                    var source = graph.values.get(node.source());
                    requireCheckpoint(source instanceof DataValue.ListValue, "Distinct source is not a list");
                    var task = DistinctTask.restore((DataValue.ListValue) source, distinct, graph.location());
                    units = Math.addExact(units, task.liveUnits());
                    frames.push(new DistinctFrame(task, graph.location()));
                }
                case MachineCheckpoint.ListState list -> {
					requireCheckpoint(parent instanceof GraphFrame, "List must follow a pending graph operation");
					var graph = (GraphFrame) parent;
					requireCheckpoint(pendingNode(graph) instanceof ComputationGraph.MakeList
						|| pendingNode(graph) instanceof ComputationGraph.ConcatLists
						|| pendingNode(graph) instanceof ComputationGraph.Keys
                        || pendingNode(graph) instanceof ComputationGraph.ListEdit, "List parent is not list composition");
					var restored = listFrame(pendingNode(graph), graph.values, graph.location());
					requireCheckpoint(list.index() >= 0 && list.index() <= restored.size, "Invalid list composition cursor");
					units = Math.addExact(units, 1);
					for (int index = 0; index < list.index(); index++) {
						var value = restored.element.apply(index);
						units = Math.addExact(units, value.storageUnits());
						requireCheckpoint(units <= arenaUnits, "List prefix exceeds saved arena");
						restored.results.add(value);
					}
					frames.push(restored);
				}
				case MachineCheckpoint.TextState text -> {
                    requireCheckpoint(parent instanceof GraphFrame, "Text must follow a pending graph operation");
                    var graph=(GraphFrame)parent;
                    requireCheckpoint(pendingNode(graph) instanceof ComputationGraph.Text, "Text parent is not a text operation");
                    var node=(ComputationGraph.Text)pendingNode(graph);
                    var task=TextTask.restore(node.operation(),bind(node.arguments(),graph.values),text.progress(),graph.location());
                    units=Math.addExact(units,task.liveUnits());
                    frames.push(new TextFrame(task,graph.location()));
                }
                case MachineCheckpoint.CommitState commit -> {
					requireCheckpoint(parent == null && states.size() == 1, "Commit must be the sole frame");
					requireCheckpoint(commit.cursor() >= 0 && commit.cursor() <= installation.writes().size(), "Invalid commit cursor");
					requireCheckpoint(installation.root().outputType().accepts(commit.result()), "Invalid pending output schema");
					var restored = new CommitFrame(commit.result());
					restored.cursor = commit.cursor();
					units = Math.addExact(units, Math.addExact(commit.result().storageUnits(), memory.storageUnits()));
					for (int index = 0; index < commit.cursor(); index++) {
						var write = installation.writes().get(index);
						var value = commit.result().field(write.output());
						requireCheckpoint(installation.cells().get(write.cell()).type().accepts(value), "Invalid prepared memory write");
						restored.staged.put(write.cell(), value);
						units = Math.addExact(units, Math.addExact(1L + write.cell().length(), value.storageUnits()));
					}
					frames.push(restored);
				}
			}
		}
		return units;
	}

	private GraphFrame restoreGraph(MachineCheckpoint.GraphState state, Frame parent) {
		ProgramCompiler.CompiledGraph compiled;
		String scope;
		if (parent == null) {
			requireCheckpoint(state.module().isEmpty(), "Bottom graph must be the event root");
			compiled = installation.root();
			scope = "event";
		} else {
			ModuleDefinition.Key key;
			DataValue.RecordValue arguments;
			if (parent instanceof GraphFrame graph) {
				var pending = pendingNode(graph);
				if (pending instanceof ComputationGraph.Call call) {
					key = call.module();
					arguments = new DataValue.RecordValue(bind(call.arguments(), graph.values));
				} else if (pending instanceof ComputationGraph.Branch branch) {
					var condition = graph.values.get(branch.condition());
					requireCheckpoint(condition instanceof DataValue.BooleanValue, "Branch condition is not boolean");
					key = ((DataValue.BooleanValue) condition).value() ? branch.whenTrue() : branch.whenFalse();
					arguments = new DataValue.RecordValue(bind(branch.arguments(), graph.values));
				} else throw failure(INVALID_CHECKPOINT, "checkpoint", "Child graph must follow a pending call or branch");
				scope = key.toString();
			} else if (parent instanceof FoldFrame fold) {
				requireCheckpoint(fold.returned == null && fold.index < fold.source.values().size(),
					"Fold cannot have a child after return or completion");
				key = fold.fold.module();
				arguments = fold.arguments();
				scope = key + "[" + fold.index + "]";
			} else if (parent instanceof SortFrame sort) {
				requireCheckpoint(sort.returned == null && sort.task == null && sort.index < sort.source.values().size(),
					"Sort cannot have a child after return or while merging");
				key = sort.sort.module();
				arguments = sort.arguments();
				scope = key + "[" + sort.index + "]";
			} else {
				requireCheckpoint(parent instanceof LoopFrame, "Invalid graph parent");
				var loop = (LoopFrame) parent;
				requireCheckpoint(loop.returned == null && loop.index < loop.source.values().size(), "Loop cannot have a child after return or completion");
				key = loop.loop.module();
				arguments = loop.arguments();
				scope = key + "[" + loop.index + "]";
			}
			requireCheckpoint(state.module().equals(Optional.of(key)), "Child graph does not match the exact pinned module");
			requireCheckpoint(state.inputs().equals(arguments), "Child graph arguments do not match the pending operation");
			compiled = installation.modules().get(key);
		}
		requireCheckpoint(state.cursor() >= 0 && state.cursor() <= compiled.order().size(), "Invalid graph cursor");
		requireCheckpoint(state.values().fields().size() == state.cursor(), "Graph values do not match completed prefix");
		requireCheckpoint(state.values().localReferences()==0,"Local references cannot enter graph checkpoints");
		var restored = new GraphFrame(compiled, state.inputs(), scope, state.module());
		for (int index = 0; index < state.cursor(); index++) {
			String name = compiled.order().get(index);
			var value = state.values().fields().get(name);
			requireCheckpoint(value != null && compiled.nodeTypes().get(name).accepts(value), "Invalid or missing graph prefix value: " + name);
			restored.values.put(name, value);
		}
		restored.cursor = state.cursor();
		return restored;
	}

	private void validateLoop(MachineCheckpoint.LoopState state, LoopFrame loop, GraphFrame parent) {
		requireCheckpoint(state.index() >= 0 && state.index() <= loop.source.values().size(), "Invalid loop index");
		int count = state.results().values().size();
		requireCheckpoint(loop.loop.mode() == ComputationGraph.Iteration.MAP ? count == state.index() : count <= state.index(),
			"Loop result count does not match progress");
		var type = parent.compiled.nodeTypes().get(parent.compiled.order().get(parent.cursor));
		requireCheckpoint(type.accepts(state.results()), "Invalid loop result element types");
		if (loop.loop.mode() == ComputationGraph.Iteration.FILTER) {
			int matched = 0;
			for (int index = 0; index < state.index() && matched < count; index++)
				if (loop.source.values().get(index).equals(state.results().values().get(matched))) matched++;
			requireCheckpoint(matched == count, "Filter results are not an ordered subsequence of processed items");
		}
		if (state.returned().isPresent()) {
			requireCheckpoint(state.index() < loop.source.values().size(), "Completed loop has a pending return");
			requireCheckpoint(installation.modules().get(loop.loop.module()).outputType().accepts(state.returned().orElseThrow()),
				"Invalid loop return record");
		}
	}

	private ComputationGraph.Node pendingNode(GraphFrame graph) {
		requireCheckpoint(graph.cursor < graph.compiled.order().size(), "Completed graph cannot have a child");
		return graph.compiled.graph().nodes().get(graph.compiled.order().get(graph.cursor));
	}

	private static void requireCheckpoint(boolean condition, String message) {
		if (!condition) throw failure(INVALID_CHECKPOINT, "checkpoint", message);
	}

	public void begin(DataValue.RecordValue inputs) {
		Objects.requireNonNull(inputs);
		if (status == Status.RUNNING)
			throw failure(BUSY, "event", "This computer already has an active event");
		output = null;
		diagnostic = null;
		work = 0;
		arenaUnits = 0;
		status = Status.RUNNING;
		try {
			reserve(installation.usage().constantUnits());
			reserve(installation.usage().initialMemoryUnits());
			reserve(memory.storageUnits());
			reserve(inputs.storageUnits());
			frames.push(new GraphFrame(installation.root(), inputs, "event", Optional.empty()));
		} catch (ComputationException error) {
			fail(error);
		} catch (ArithmeticException overflow) {
			fail(failure(MEMORY_LIMIT, "event", "Input size exceeds supported range"));
		}
	}

	public Report tick() {
		return runSlice(installation.limits().workPerSlice());
	}

	/** Runs part of the current event, without an activity clock or an implicit event restart.
	 * The caller owns the total budget across slices; one slice cannot exceed the hardware allowance. */
	public Report runSlice(long workAllowance) {
		if (workAllowance < 0) throw new IllegalArgumentException("Negative work allowance");
		if (!paused) advance(Math.min(workAllowance, installation.limits().workPerSlice()));
		return report();
	}

	/** Executes exactly one work unit even while paused; does not resume automatic execution. */
	public Report step() {
		advance(1);
		return report();
	}

	public void reset() {
		frames.clear();
		memory = ProgramCompiler.initialMemory(installation.cells());
		status = Status.IDLE;
		output = null;
		diagnostic = null;
		work = 0;
		arenaUnits = 0;
	}

	private void advance(long budget) {
		for (long consumed = 0; consumed < budget && status == Status.RUNNING; consumed++) {
			try {
				work = Math.incrementExact(work);
				frames.peek().step();
			} catch (ComputationException error) {
				fail(error);
			} catch (ArithmeticException overflow) {
				fail(failure(ARITHMETIC_OVERFLOW, location(), "Integer arithmetic exceeds supported range"));
			}
		}
	}

	private void fail(ComputationException error) {
		diagnostic = new Diagnostic(error.code(), error.location(), error.getMessage());
		status = Status.FAILED;
		output = null;
		frames.clear();
	}

	/**
	 * Conservative event arena: every materialized value occurrence stays charged until completion.
	 * Sharing immutable Java references does not provide free logical storage to a program.
	 */
	private void reserve(long units) {
		if (units < 0 || units > installation.limits().memoryUnits() - arenaUnits)
			throw failure(MEMORY_LIMIT, location(), "Event working memory exceeds hardware capacity");
		arenaUnits += units;
	}

	private String location() {
		return frames.isEmpty() ? "event" : frames.peek().location();
	}

	private interface Frame {
		void step();
		String location();
		default void accept(DataValue value) { throw new IllegalStateException("Unexpected child result"); }
	}

	private final class GraphFrame implements Frame {
		private final ProgramCompiler.CompiledGraph compiled;
		private final DataValue.RecordValue inputs;
		private final String scope;
		private final Optional<ModuleDefinition.Key> module;
		private final Map<String, DataValue> values = new LinkedHashMap<>();
		private int cursor;

		private GraphFrame(ProgramCompiler.CompiledGraph compiled, DataValue.RecordValue inputs, String scope,
			Optional<ModuleDefinition.Key> module) {
			this.compiled = compiled;
			this.inputs = inputs;
			this.scope = scope;
			this.module = module;
			requireValue(new DataType.RecordType(compiled.graph().inputs()), inputs, scope);
		}

		@Override
		public String location() {
			return scope + "/" + (cursor < compiled.order().size() ? compiled.order().get(cursor) : "outputs");
		}

		@Override
		public void step() {
			if (cursor == compiled.order().size()) {
				var result = new DataValue.RecordValue(bind(compiled.graph().outputs(), values));
				reserve(result.storageUnits());
				frames.pop();
				if (frames.isEmpty()) {
					reserve(memory.storageUnits());
					frames.push(new CommitFrame(result));
				} else
					frames.peek().accept(result);
				return;
			}

			var node = compiled.graph().nodes().get(compiled.order().get(cursor));
			switch (node) {
				case ComputationGraph.Constant constant -> accept(constant.value());
				case ComputationGraph.Input input -> accept(inputs.field(input.name()));
				case ComputationGraph.ReadMemory read -> accept(memory.field(read.cell()));
				case ComputationGraph.Binary binary -> {
					accept(ScalarOperation.forLegacy(binary.operation()).evaluate(bind(Map.of("left", binary.left(), "right", binary.right()), values), location()));
				}
				case ComputationGraph.Text text -> frames.push(new TextFrame(
                    TextTask.start(text.operation(),bind(text.arguments(),values),location(),ComputationMachine.this::reserve),location()));
                case ComputationGraph.Scalar scalar -> accept(scalar.operation().evaluate(bind(scalar.arguments(), values), location()));
				case ComputationGraph.Field field -> {
					var source = values.get(field.source());
					DataValue value;
					if (source instanceof DataValue.RecordValue record) value = record.fields().get(field.name());
					else if (source instanceof DataValue.MapValue map) value = map.entries().get(field.name());
					else throw failure(TYPE_MISMATCH, location(), "Field extraction requires a record or map");
					if (value == null) throw failure(MISSING_FIELD, location(), "No field named " + field.name());
					accept(value);
				}
				case ComputationGraph.Index index -> {
					if (!(values.get(index.source()) instanceof DataValue.ListValue list))
						throw failure(TYPE_MISMATCH, location(), "Index extraction requires a list");
					if (index.index() >= list.values().size())
						throw failure(INDEX_OUT_OF_BOUNDS, location(), "List index " + index.index() + " is out of bounds");
					accept(list.values().get(index.index()));
				}
				case ComputationGraph.DynamicField field -> {
					if (!(values.get(field.name()) instanceof DataValue.TextValue name))
						throw failure(TYPE_MISMATCH, location(), "Field name requires text");
					var source = values.get(field.source());
					DataValue value;
					if (source instanceof DataValue.RecordValue record) value = record.fields().get(name.value());
					else if (source instanceof DataValue.MapValue map) value = map.entries().get(name.value());
					else throw failure(TYPE_MISMATCH, location(), "Field extraction requires a record or map");
					if (value == null) throw failure(MISSING_FIELD, location(), "Requested field does not exist");
					accept(value);
				}
				case ComputationGraph.HasField field -> {
					if (!(values.get(field.name()) instanceof DataValue.TextValue name))
						throw failure(TYPE_MISMATCH, location(), "Field name requires text");
					var source = values.get(field.source());
					boolean present;
					if (source instanceof DataValue.RecordValue record) present = record.fields().containsKey(name.value());
					else if (source instanceof DataValue.MapValue map) present = map.entries().containsKey(name.value());
					else throw failure(TYPE_MISMATCH, location(), "Field presence requires a record or map");
					accept(new DataValue.BooleanValue(present));
				}
				case ComputationGraph.MakeRecord record -> accept(new DataValue.RecordValue(bind(record.fields(), values)));
				case ComputationGraph.DynamicIndex index -> {
					if (!(values.get(index.source()) instanceof DataValue.ListValue list))
						throw failure(TYPE_MISMATCH, location(), "Index extraction requires a list");
					if (!(values.get(index.index()) instanceof DataValue.IntegerValue offset))
						throw failure(TYPE_MISMATCH, location(), "List index requires an integer");
					long position = offset.value();
					if (position < 0 || position >= list.values().size())
						throw failure(INDEX_OUT_OF_BOUNDS, location(), "List index " + position + " is out of bounds");
					accept(list.values().get((int) position));
				}
				case ComputationGraph.Keys keys -> {
					var frame = listFrame(keys, values, location());
					reserve(1); frames.push(frame);
				}
                case ComputationGraph.ListEdit edit -> {
                    var frame = listFrame(edit, values, location());
                    reserve(1); frames.push(frame);
                }
				case ComputationGraph.MakeList list -> {
					reserve(1);
					frames.push(listFrame(list, values, location()));
				}
				case ComputationGraph.ConcatLists list -> {
					var frame = listFrame(list, values, location());
					reserve(1);
					frames.push(frame);
				}
				case ComputationGraph.ProcedureValue ignored -> throw new IllegalStateException("Procedure value requires ISA lowering");
                case ComputationGraph.ListPosition ignored -> throw new IllegalStateException("Special list position requires ISA lowering");
                case ComputationGraph.ListSearch ignored -> throw new IllegalStateException("List search requires ISA lowering");
                case ComputationGraph.Call call -> {
					var arguments = new DataValue.RecordValue(bind(call.arguments(), values));
					reserve(arguments.storageUnits());
					frames.push(new GraphFrame(installation.modules().get(call.module()), arguments, call.module().toString(), Optional.of(call.module())));
				}
				case ComputationGraph.Branch branch -> {
					var condition = values.get(branch.condition());
					if (!(condition instanceof DataValue.BooleanValue choice))
						throw failure(TYPE_MISMATCH, location(), "Branch condition must be boolean");
					var key = choice.value() ? branch.whenTrue() : branch.whenFalse();
					var arguments = new DataValue.RecordValue(bind(branch.arguments(), values));
					reserve(arguments.storageUnits());
					frames.push(new GraphFrame(installation.modules().get(key), arguments, key.toString(), Optional.of(key)));
				}
				case ComputationGraph.Fold fold -> {
					if (!(values.get(fold.source()) instanceof DataValue.ListValue list))
						throw failure(TYPE_MISMATCH, location(), "Fold requires a list");
					var initial = values.get(fold.initial());
					var frame = new FoldFrame(fold, list, initial, bind(fold.captures(), values), location());
					reserve(initial.storageUnits());
					frames.push(frame);
				}
				case ComputationGraph.Iterate loop -> {
					if (!(values.get(loop.source()) instanceof DataValue.ListValue list))
						throw failure(TYPE_MISMATCH, location(), "Iteration requires a list");
					reserve(1); // Output list header; restored frames must not reserve it again.
					frames.push(new LoopFrame(loop, list, bind(loop.captures(), values), location()));
				}
                case ComputationGraph.RandomRange ignored -> throw new IllegalStateException("Random range requires ISA lowering");
                case ComputationGraph.Timer ignored -> throw new IllegalStateException("Timer requires ISA lowering");
                case ComputationGraph.Answer ignored -> throw new IllegalStateException("Terminal answer requires ISA lowering");
                case ComputationGraph.KeyDown ignored -> throw new IllegalStateException("Terminal keyboard requires ISA lowering");
				case ComputationGraph.ValueEquals equality -> {
                    frames.push(new ValueEqualityFrame(ValueEqualityTask.start(values.get(equality.left()), values.get(equality.right()),
                        location(), ComputationMachine.this::reserve), location()));
                }
                case ComputationGraph.Distinct distinct -> {
                    if (!(values.get(distinct.source()) instanceof DataValue.ListValue list))
                        throw failure(TYPE_MISMATCH, location(), "Distinct requires a list");
                    frames.push(new DistinctFrame(DistinctTask.start(list, location(), ComputationMachine.this::reserve), location()));
                }
                case ComputationGraph.Sort sort -> {
					if (!(values.get(sort.source()) instanceof DataValue.ListValue list))
						throw failure(TYPE_MISMATCH, location(), "Sort requires a list");
					reserve(1); // The retained key-list header.
					frames.push(new SortFrame(sort, list, bind(sort.captures(), values), location()));
				}
			}
		}

		@Override
		public void accept(DataValue value) {
			reserve(value.storageUnits());
			values.put(compiled.order().get(cursor), value);
			cursor++;
		}
	}

	private ListFrame listFrame(ComputationGraph.Node node, Map<String, DataValue> values, String scope) {
		if (node instanceof ComputationGraph.Keys keys) {
			var source = values.get(keys.source());
			List<Map.Entry<String, DataValue>> fields;
			if (source instanceof DataValue.RecordValue record) fields = record.indexedFields();
			else if (source instanceof DataValue.MapValue map) fields = map.indexedEntries();
			else throw failure(TYPE_MISMATCH, scope, "Field enumeration requires a record or map");
			return new ListFrame(fields.size(), index -> new DataValue.TextValue(fields.get(index).getKey()), scope);
		}
		if (node instanceof ComputationGraph.MakeList list)
			return new ListFrame(list.elements().size(), index -> values.get(list.elements().get(index)), scope);
        if (node instanceof ComputationGraph.ListEdit edit) {
            var selected = edit.operation().select(bind(edit.arguments(), values), scope);
            return new ListFrame(selected.size(), selected.element(), scope);
        }
		var concat = (ComputationGraph.ConcatLists) node;
		if (!(values.get(concat.left()) instanceof DataValue.ListValue left)
			|| !(values.get(concat.right()) instanceof DataValue.ListValue right))
			throw failure(TYPE_MISMATCH, scope, "List concatenation requires two lists");
		long size = (long) left.values().size() + right.values().size();
		if (size > Integer.MAX_VALUE) throw failure(MEMORY_LIMIT, scope, "List exceeds supported representation");
		return new ListFrame((int) size, index -> index < left.values().size()
			? left.values().get(index) : right.values().get(index - left.values().size()), scope);
	}

	/** Frozen sources are borrowed; each new output occurrence is charged before retaining it. */
	private final class ListFrame implements Frame {
		private final int size;
		private final java.util.function.IntFunction<DataValue> element;
		private final String scope;
		private final ArrayList<DataValue> results = new ArrayList<>();
		private ListFrame(int size, java.util.function.IntFunction<DataValue> element, String scope) {
			this.size = size; this.element = element; this.scope = scope;
		}
		public String location() { return scope; }
		public void step() {
			if (results.size() == size) {
				var value = new DataValue.ListValue(results);
				frames.pop(); frames.peek().accept(value);
			} else {
				var value = element.apply(results.size());
				reserve(value.storageUnits());
				results.add(value);
			}
		}
	}

	/** TextTask alone owns the algorithm, schema and output accounting. */
    private final class TextFrame implements Frame {
        private final TextTask task;
        private final String scope;
        private TextFrame(TextTask task,String scope){this.task=task;this.scope=scope;}
        public String location(){return scope;}
        public void step(){
            if(task.complete()){frames.pop();frames.peek().accept(task.result());}
            else task.advance(ComputationMachine.this::reserve);
        }
    }

	private final class LoopFrame implements Frame {
		private final ComputationGraph.Iterate loop;
		private final DataValue.ListValue source;
		private final Map<String, DataValue> captures;
		private final String scope;
		private final ArrayList<DataValue> results = new ArrayList<>();
		private int index;
		private DataValue.RecordValue returned;

		private LoopFrame(ComputationGraph.Iterate loop, DataValue.ListValue source,
			Map<String, DataValue> captures, String scope) {
			this.loop = loop;
			this.source = source;
			this.captures = captures;
			this.scope = scope;
		}

		@Override
		public String location() { return scope + "[" + index + "]"; }

		@Override
		public void step() {
			if (returned != null) {
				var result = returned.field(loop.resultPort());
				if (loop.mode() == ComputationGraph.Iteration.MAP) {
					reserve(result.storageUnits());
					results.add(result);
				} else {
					if (!(result instanceof DataValue.BooleanValue keep))
						throw failure(TYPE_MISMATCH, location(), "Filter result must be boolean");
					if (keep.value()) {
						var item = source.values().get(index);
						reserve(item.storageUnits());
						results.add(item);
					}
				}
				returned = null;
				index++;
			} else if (index == source.values().size()) {
				var result = new DataValue.ListValue(results);
				frames.pop();
				frames.peek().accept(result);
			} else {
				var arguments = arguments();
				reserve(arguments.storageUnits());
				frames.push(new GraphFrame(installation.modules().get(loop.module()), arguments,
					loop.module() + "[" + index + "]", Optional.of(loop.module())));
			}
		}

		@Override
		public void accept(DataValue value) { returned = (DataValue.RecordValue) value; }

		private DataValue.RecordValue arguments() {
			var fields = new LinkedHashMap<>(captures);
			fields.put(loop.itemPort(), source.values().get(index));
			return new DataValue.RecordValue(fields);
		}
	}

	/** One step calls a child, consumes its return, or completes; never traverses the source synchronously. */
	private final class FoldFrame implements Frame {
		private final ComputationGraph.Fold fold;
		private final DataValue.ListValue source;
		private final Map<String, DataValue> captures;
		private final DataType accumulatorType;
		private final String scope;
		private int index;
		private DataValue accumulator;
		private DataValue.RecordValue returned;

		private FoldFrame(ComputationGraph.Fold fold, DataValue.ListValue source, DataValue initial,
			Map<String, DataValue> captures, String scope) {
			this.fold = fold;
			this.source = source;
			this.captures = captures;
			this.scope = scope;
			this.accumulatorType = installation.modules().get(fold.module()).graph().inputs().get(fold.accumulatorPort());
			requireValue(accumulatorType, initial, scope);
			this.accumulator = initial;
		}

		@Override
		public String location() { return scope + "[" + index + "]"; }

		@Override
		public void step() {
			if (returned != null) {
				var next = returned.field(fold.resultPort());
				requireValue(accumulatorType, next, location());
				reserve(next.storageUnits());
				accumulator = next;
				returned = null;
				index++;
			} else if (index == source.values().size()) {
				frames.pop();
				frames.peek().accept(accumulator);
			} else {
				var arguments = arguments();
				reserve(arguments.storageUnits());
				frames.push(new GraphFrame(installation.modules().get(fold.module()), arguments,
					fold.module() + "[" + index + "]", Optional.of(fold.module())));
			}
		}

		@Override
		public void accept(DataValue value) { returned = (DataValue.RecordValue) value; }

		private DataValue.RecordValue arguments() {
			var fields = new LinkedHashMap<>(captures);
			fields.put(fold.itemPort(), source.values().get(index));
			fields.put(fold.accumulatorPort(), accumulator);
			return new DataValue.RecordValue(fields);
		}
	}

	/** Pure key calls precede a separately metered merge continuation. Neither phase touches RAM. */
	private final class SortFrame implements Frame {
		private final ComputationGraph.Sort sort;
		private final DataValue.ListValue source;
		private final Map<String, DataValue> captures;
		private final String scope;
		private final ArrayList<DataValue> keys = new ArrayList<>();
		private int index;
		private DataValue.RecordValue returned;
		private SortTask task;

		private SortFrame(ComputationGraph.Sort sort, DataValue.ListValue source, Map<String, DataValue> captures, String scope) {
			this.sort = sort; this.source = source; this.captures = captures; this.scope = scope;
		}
		@Override public String location() { return task == null ? scope + "[" + index + "]" : scope; }
		@Override public void step() {
			if (task != null) {
				if (task.complete()) { frames.pop(); frames.peek().accept(task.result()); }
				else task.advance(ComputationMachine.this::reserve);
			} else if (returned != null) {
				var key = returned.field(sort.resultPort());
				SortTask.requireKey(key, keys.isEmpty() ? null : keys.getFirst(), location());
				reserve(key.storageUnits()); keys.add(key); index++; returned = null;
			} else if (index == source.values().size()) {
				task = SortTask.start(source, new DataValue.ListValue(keys), sort.descending(), scope, ComputationMachine.this::reserve);
			} else {
				var arguments = arguments(); reserve(arguments.storageUnits());
				frames.push(new GraphFrame(installation.modules().get(sort.module()), arguments,
					sort.module() + "[" + index + "]", Optional.of(sort.module())));
			}
		}
		@Override public void accept(DataValue value) { returned = (DataValue.RecordValue) value; }
		private DataValue.RecordValue arguments() {
			var fields = new LinkedHashMap<>(captures); fields.put(sort.itemPort(), source.values().get(index));
			return new DataValue.RecordValue(fields);
		}
	}

    private final class ValueEqualityFrame implements Frame {
        private final ValueEqualityTask task;
        private final String scope;
        private ValueEqualityFrame(ValueEqualityTask task, String scope) { this.task = task; this.scope = scope; }
        @Override public String location() { return scope; }
        @Override public void step() {
            if (task.complete()) { frames.pop(); frames.peek().accept(task.result()); }
            else task.advance(ComputationMachine.this::reserve);
        }
    }

	private final class DistinctFrame implements Frame {
        private final DistinctTask task;
        private final String scope;
        private DistinctFrame(DistinctTask task, String scope) { this.task = task; this.scope = scope; }
        @Override public String location() { return scope; }
        @Override public void step() {
            if (task.complete()) { frames.pop(); frames.peek().accept(task.result()); }
            else task.advance(ComputationMachine.this::reserve);
        }
    }

	private final class CommitFrame implements Frame {
		private final DataValue.RecordValue result;
		private final Map<String, DataValue> staged;
		private int cursor;

		private CommitFrame(DataValue.RecordValue result) {
			this.result = result;
			this.staged = new LinkedHashMap<>(memory.fields());
		}

		@Override
		public String location() { return "commit/" + cursor; }

		@Override
		public void step() {
			if (cursor < installation.writes().size()) {
				var write = installation.writes().get(cursor);
				var value = result.field(write.output());
				requireValue(installation.cells().get(write.cell()).type(), value, write.cell());
				reserve(Math.addExact(1L + write.cell().length(), value.storageUnits()));
				staged.put(write.cell(), value);
				cursor++;
				return;
			}
			var nextMemory = new DataValue.RecordValue(staged);
			// No code which can fail runs after this assignment: this is the atomic event boundary.
			memory = nextMemory;
			output = result;
			status = Status.SUCCEEDED;
			frames.clear();
		}
	}

	private static Map<String, DataValue> bind(Map<String, String> bindings, Map<String, DataValue> values) {
		var result = new LinkedHashMap<String, DataValue>();
		bindings.forEach((name, ref) -> result.put(name, Objects.requireNonNull(values.get(ref))));
		return result;
	}
	private static void requireValue(DataType type, DataValue value, String location) {
		if (value.localReferences()!=0 || !type.accepts(value))
			throw failure(TYPE_MISMATCH, location, "Value does not match the declared port or cell type");
	}
}
