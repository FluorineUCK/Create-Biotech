package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowInput;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBranches;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension.Shape;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;

/** Immutable executable facts plus editor-only graph layout. */
public record ProgramDocument(ProgramArchive archive, Map<String, Map<String, Position>> positions,
        Optional<com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition> terminal,
        Optional<FlowDocumentBody> flow) {
    public ProgramDocument(ProgramArchive archive, Map<String, Map<String, Position>> positions,
        Optional<com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition> terminal) {
        this(archive, positions, terminal, Optional.empty());
    }
    public ProgramDocument(ProgramArchive archive, Map<String, Map<String, Position>> positions) {
        this(archive, positions, Optional.empty());
    }
    public record Position(double x, double y) {
        public Position {
            if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Non-finite canvas coordinate");
        }
    }
    public ProgramDocument {
        Objects.requireNonNull(archive); Objects.requireNonNull(positions); Objects.requireNonNull(terminal); Objects.requireNonNull(flow);
        var known = new LinkedHashMap<String, Set<String>>();
        known.put(graphId(null), archive.program().graph().nodes().keySet());
        archive.program().entries().forEach((name, entry) -> known.put(entryGraphId(name), entry.graph().nodes().keySet()));
        archive.modules().forEach((key, module) -> known.put(graphId(key), module.graph().nodes().keySet()));
        positions = GraphLayouts.copy(known, positions);
    }
    public static String graphId(ModuleDefinition.Key key) {
        if (key == null) return "root";
        return "module:" + key.id().length() + ":" + key.id() + ":" + key.revision();
    }
    public static String entryGraphId(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name must not be blank");
        return "entry:" + name.length() + ":" + name;
    }
    public static ProgramDocument of(ProgramArchive archive) { return new ProgramDocument(archive, Map.of()); }
    /** Local installation check; portable documents still support validation without a physical target. */
    public void validate(com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits limits,
        com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins pins) {
        validate(limits);
        flow.ifPresent(body -> com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPins.validate(body.program(archive), pins));
    }
    public void validate(com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits limits) {
        validateTerminalTargets();
        validateExtensions();
        if (flow.isPresent()) {
            flow.orElseThrow().program(archive).validate(limits);
        } else archive.compile(limits);
    }
    /** A fixed-version task must be selected explicitly; old same-named event bindings never change meaning. */
    public void validateExtensions() {
        flow.ifPresent(body -> {
            var program = body.program(archive);
            var steps = program.steps();
            ScratchExtensions.validate(body.canvas().extensions(), steps);
            var definitions = steps.stream().filter(FlowProgram.Procedure.class::isInstance).map(FlowProgram.Procedure.class::cast)
                .map(FlowProgram.Procedure::key).collect(java.util.stream.Collectors.toSet());
            var entries = program.expressions().program().entries();
            // A shared expression may be used at many source positions. Inspect its nodes once,
            // then check each use against the one fixed predicate a control header can own.
            record FixedPredicate(String node, ModuleDefinition.Key key, Shape shape) {}
            var predicates = new HashMap<String, FixedPredicate>();
            entries.forEach((id, entry) -> entry.graph().nodes().forEach((nodeId, node) -> {
                if (!(node instanceof ComputationGraph.ProcedureValue call) || !ScratchExtensions.reserved(call.procedure())) return;
                var shape = ScratchExtensions.binding(call.procedure()).map(b -> b.command().shape()).orElse(null);
                if (shape != null && (shape.bodyCount() > 0 || shape == Shape.HAT)) {
                    ScratchExtensions.validateReporter(call.procedure(), definitions, shape);
                    if (predicates.putIfAbsent(id, new FixedPredicate(nodeId, call.procedure(), shape)) != null)
                        throw new IllegalArgumentException("A control header cannot own two fixed predicates");
                } else ScratchExtensions.validateReporter(call.procedure(), definitions, null);
            }));
            var unused = new HashSet<>(predicates.keySet());
            FlowBranches branches = null;
            for (int i = 0; i < steps.size(); i++) {
                var step = steps.get(i);
                var target = step instanceof FlowProgram.Invoke call ? call.procedure()
                    : step instanceof FlowProgram.TaskStart start ? start.procedure()
                    : step instanceof FlowProgram.TryLaunch launch ? launch.procedure() : null;
                if (target != null) ScratchExtensions.validateDirectCall(target, definitions);
                if (predicates.isEmpty()) continue;
                var input = FlowProgram.valueInput(step) instanceof FlowInput.Expression e ? e : null;
                var entry = input != null ? input.entry() : step instanceof FlowProgram.Evaluate eval ? eval.expression()
                    : step instanceof FlowProgram.Invoke call ? call.operands().map(FlowProgram.InvokeOperands::entry).orElse(null) : null;
                var predicate = predicates.get(entry);
                if (predicate == null) continue;
                unused.remove(entry);
                var graph = entries.get(entry).graph();
                String result = input == null ? null : graph.outputs().get(input.output());
                Shape placement = null;
                if (step instanceof FlowProgram.If && predicate.node().equals(result)) {
                    if (branches == null) branches = FlowBranches.structure(steps);
                    placement = branches.hasOtherwise(i) ? Shape.CONDITIONAL_ELSE : Shape.CONDITIONAL;
                } else if (step instanceof FlowProgram.Until && predicate.node().equals(ScratchExtensions.loopPredicate(graph, result))) {
                    placement = Shape.LOOP;
                } else if (step instanceof FlowProgram.Watch && predicate.node().equals(ScratchExtensions.hatPredicate(graph, result))) {
                    placement = Shape.HAT;
                }
                if (predicate.shape() != placement)
                    throw new IllegalArgumentException("Invalid fixed Scratch control placement: " + predicate.key().id());
            }
            if (!unused.isEmpty()) throw new IllegalArgumentException("Fixed control predicate without a control header");
            terminal.ifPresent(layout -> {
                for (var target : layout.targets()) if (target instanceof com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalTarget.Task task)
                    ScratchExtensions.validateDirectCall(task.procedure(), definitions);
            });
        });
    }
    public void validateTerminalTargets() {
        if (terminal.isEmpty()) return;
        var targets = terminal.orElseThrow().targets();
        if (flow.isEmpty()) {
            if (terminal.orElseThrow().usesTasks()) throw new IllegalArgumentException("Task terminal targets require a Flow program");
            return;
        }
        var keys = flow.orElseThrow().program(archive).steps().stream()
            .filter(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure.class::isInstance)
            .map(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure.class::cast)
            .map(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure::key).collect(java.util.stream.Collectors.toSet());
        for (var target : targets) {
            if (!(target instanceof com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalTarget.Task task))
                throw new IllegalArgumentException("Flow terminal controls require an explicitly selected task procedure");
            if (!keys.contains(task.procedure())) throw new IllegalArgumentException("Missing terminal task procedure: " + task.procedure());
        }
    }
}
