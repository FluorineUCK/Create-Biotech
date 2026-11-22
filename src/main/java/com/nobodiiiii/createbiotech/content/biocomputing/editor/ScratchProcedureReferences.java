package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension.Shape;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalTarget;
import java.util.*;
import java.util.function.BiConsumer;

/** Read-only index over the visible source model, never the installed program. */
final class ScratchProcedureReferences {
    enum Kind { CALL, START, JOIN, VALUE, PARKED }
    record Reference(Kind kind, String source, String node, int parkedIndex) {}

    record ExtensionRevision(String id, long revision) {}

    private ScratchProcedureReferences() {}

    static Optional<ModuleDefinition.Key> direct(FlowProgram.Step step) {
        return switch (step) {
            case FlowProgram.Invoke call -> Optional.of(call.procedure());
            case FlowProgram.Watch watch -> watch.handler();
            case FlowProgram.TryLaunch launch -> Optional.of(launch.procedure());
            case FlowProgram.TaskStart start -> Optional.of(start.procedure());
            case FlowProgram.TaskJoin join -> Optional.of(join.procedure());
            default -> Optional.empty();
        };
    }

    static List<Reference> find(FlowDraft draft, ModuleDefinition.Key key) {
        var references = new ArrayList<Reference>();
        visit(draft, (found, ref) -> { if (key.equals(found)) references.add(ref); });
        return List.copyOf(references);
    }

    static Map<ExtensionRevision, List<Reference>> extensions(FlowDraft draft) {
        var references = new TreeMap<ExtensionRevision, List<Reference>>(Comparator.comparing(ExtensionRevision::id)
            .thenComparingLong(ExtensionRevision::revision));
        visit(draft, (key, ref) -> ScratchExtensions.extensionId(key).ifPresent(id -> references
            .computeIfAbsent(new ExtensionRevision(id.toString(), key.revision()), ignored -> new ArrayList<>()).add(ref)));
        references.replaceAll((version, uses) -> List.copyOf(uses));
        return Collections.unmodifiableMap(references);
    }

    /** An explicit deletion may release a fixed template the draft no longer references. */
    static FlowDocumentBody releaseNewlyUnusedTemplates(FlowDocumentBody before, FlowDocumentBody after,
            ProgramWorkspace workspace) {
        return releaseLostBindings(before, after, workspace, false);
    }

    /** Parked values hold a descriptor reference and link again when they snap into an expression. */
    static FlowDocumentBody releaseNewlyDormantReporters(FlowDocumentBody before, FlowDocumentBody after,
            ProgramWorkspace workspace) {
        return releaseLostBindings(before, after, workspace, true);
    }

    private static FlowDocumentBody releaseLostBindings(FlowDocumentBody before, FlowDocumentBody after,
            ProgramWorkspace workspace, boolean reporterOnly) {
        var counts = new HashMap<ModuleDefinition.Key, Integer>();
        var candidates = new HashMap<ModuleDefinition.Key, FlowBlockLayout.Stack>();
        var layout = after.canvas();
        if (layout.extensions().isEmpty()) return after;
        for (var stack : layout.detached()) {
            if (!(stack.steps().getFirst() instanceof FlowProgram.Procedure procedure)
                    || !ScratchExtensions.reserved(procedure.key())) continue;
            var key = procedure.key();
            counts.merge(key, 1, Integer::sum);
            if (stack.steps().size() != 2 || !(stack.steps().getLast() instanceof FlowProgram.EndProcedure)
                    || layout.procedureLabels().containsKey(procedure.sourceId())) continue;
            ScratchExtensions.binding(key).filter(binding ->
                    (!reporterOnly || binding.command().shape() == Shape.REPORTER || binding.command().shape() == Shape.BOOLEAN)
                        && Objects.equals(layout.extensions().get(binding.extension().id().toString()), key.revision())
                        && binding.template(procedure.sourceId()).equals(procedure))
                .ifPresent(binding -> candidates.put(key, stack));
        }
        if (candidates.isEmpty()) return after;
        var usedBefore = sourceKeys(before, !reporterOnly);
        var usedAfter = sourceKeys(after, !reporterOnly);
        candidates.keySet().removeIf(key -> counts.get(key) != 1 || !usedBefore.contains(key) || usedAfter.contains(key));
        if (candidates.isEmpty()) return after;

        ProgramDocument context;
        try {
            context = workspace.snapshot();
        } catch (IllegalArgumentException | IllegalStateException incomplete) {
            return after; // Other drafts can be incomplete; retain templates when references are uncertain.
        }
        var archive = context.archive();
        var outside = new HashSet<ModuleDefinition.Key>();
        graphKeys(archive.program().graph(), outside);
        archive.program().entries().values().forEach(entry -> graphKeys(entry.graph(), outside));
        archive.modules().values().forEach(module -> graphKeys(module.graph(), outside));
        context.terminal().ifPresent(terminal -> terminal.targets().forEach(target -> {
            if (target instanceof TerminalTarget.Task call) outside.add(call.procedure());
        }));
        candidates.keySet().removeAll(outside);
        if (candidates.isEmpty()) return after;
        var released = new HashSet<>(candidates.values());
        var retained = layout.detached().stream().filter(stack -> !released.contains(stack)).toList();
        return after.edited(after.inputs(), after.steps(), after.initial(), layout.arranged(layout.main(), retained), after.inline());
    }

    private static Set<ModuleDefinition.Key> sourceKeys(FlowDocumentBody body, boolean includeParkedValues) {
        var keys = new HashSet<ModuleDefinition.Key>();
        body.steps().forEach(step -> direct(step).ifPresent(keys::add));
        body.canvas().detached().forEach(stack -> stack.steps().forEach(step -> direct(step).ifPresent(keys::add)));
        body.inline().values().forEach(graph -> graphKeys(graph, keys));
        if (includeParkedValues) body.canvas().values().parked().forEach(reporter -> graphKeys(reporter.graph(), keys));
        return keys;
    }

    private static void graphKeys(ComputationGraph graph, Set<ModuleDefinition.Key> keys) {
        for (var node : graph.nodes().values())
            if (node instanceof ComputationGraph.ProcedureValue call) keys.add(call.procedure());
    }

    private static void visit(FlowDraft draft, BiConsumer<ModuleDefinition.Key, Reference> add) {
        var body = draft.snapshot();
        for (var step : draft.allSteps()) {
            direct(step).ifPresent(key -> {
                var kind = step instanceof FlowProgram.Invoke ? Kind.CALL : step instanceof FlowProgram.TaskJoin ? Kind.JOIN : Kind.START;
                add.accept(key, new Reference(kind, step.sourceId(), null, -1));
            });
            var graph = body.inline().get(step.sourceId());
            if (graph != null) collect(graph, step.sourceId(), -1, add);
        }
        var parked = body.canvas().values().parked();
        for (int i = 0; i < parked.size(); i++) {
            var reporter = parked.get(i);
            collect(reporter.graph(), Integer.toString(i + 1), i, add);
        }
    }

    private static void collect(ComputationGraph graph, String source,
            int parkedIndex, BiConsumer<ModuleDefinition.Key, Reference> add) {
        var pending = new ArrayDeque<>(new TreeMap<>(graph.outputs()).values());
        var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            String node = pending.removeFirst();
            if (!visited.add(node)) continue;
            var value = graph.nodes().get(node);
            if (value == null) continue; // Incomplete drafts remain navigable.
            if (value instanceof ComputationGraph.ProcedureValue call)
                add.accept(call.procedure(), new Reference(parkedIndex < 0 ? Kind.VALUE : Kind.PARKED, source, node, parkedIndex));
            pending.addAll(value.dependencies());
        }
    }
}
