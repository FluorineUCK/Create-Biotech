package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import java.util.*;

/** Validates declarations across lexical scopes and the already checked nonrecursive call DAG. */
final class FlowNestingValidator {
    private FlowNestingValidator() {}
    private static final class Summary {
        final Map<String, String> used = new TreeMap<>(), excluded = new TreeMap<>();
        void add(FlowProgram.Step step, FlowProgram program, Map<com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition.Key,com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.ProcedureAssembler.Image> authored) {
            if (FlowBlockDeclarations.of(step).delimiter()) return;
            FlowBlockDeclarations.selectors(step).forEach(id -> used.putIfAbsent(id, step.sourceId()));
            if (!program.reporterTargets(step).isEmpty()) FlowBlockDeclarations.Kind.INVOKE.selectors()
                .forEach(id -> used.putIfAbsent(id, step.sourceId()));
            if(step instanceof FlowProgram.Procedure p && p.assembly().isPresent()) {
                var image=authored.get(p.key());
                if(image.waits()){used.putIfAbsent("sleep",p.sourceId());used.putIfAbsent("device_wait",p.sourceId());}
                if(image.loops())used.putIfAbsent("loop",p.sourceId());
                for(var entry:image.program().code().subList(0,image.authoredInstructions())) {
                    if(entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterDevices.Instruction d)
                        FlowDevices.selectors(d.kind()).forEach(id -> used.putIfAbsent(id,p.sourceId()+"/assembly/"+entry.location()));
                    if(entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Constant
                        || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.RandomRange
                        || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Alu
                    || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Move
                    || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Drop
                    || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterNbt.Instruction
                    || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.TextNext
                    || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterListEdit
                    || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.AppendList
                    || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.MakeList
                    || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.MakeRecord)used.putIfAbsent("evaluate", p.sourceId());
                    if (entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterTimer timer) {
                        var kind = timer.resets() ? FlowBlockDeclarations.Kind.TIMER_RESET : FlowBlockDeclarations.Kind.EVALUATE;
                        kind.selectors().forEach(id -> used.putIfAbsent(id, p.sourceId()));
                        kind.rules().forbidContexts().forEach(id -> excluded.putIfAbsent(id, p.sourceId()));
                    }
                    if(entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterXBus.Instruction d) {
                        var kind=switch(d.kind()){case READ->FlowBlockDeclarations.Kind.READ;case WRITE->FlowBlockDeclarations.Kind.WRITE;case WAIT_READABLE->FlowBlockDeclarations.Kind.READABLE;};kind.selectors().forEach(id->used.putIfAbsent(id,p.sourceId()));
                    }
                    if(entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Instruction d){var kind=switch(d.kind()){case SEND->FlowBlockDeclarations.Kind.SERIAL_SEND;case RECEIVE->FlowBlockDeclarations.Kind.SERIAL_RECEIVE;case STATUS->FlowBlockDeclarations.Kind.SERIAL_STATUS;};kind.selectors().forEach(id->used.putIfAbsent(id,p.sourceId()));}
                    if(entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterEvent event)
                        (event.await() ? FlowBlockDeclarations.Kind.BROADCAST_WAIT : FlowBlockDeclarations.Kind.BROADCAST).selectors().forEach(id -> used.putIfAbsent(id,p.sourceId()));
                    if(entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Correlation)FlowBlockDeclarations.Kind.CORRELATION.selectors().forEach(id->used.putIfAbsent(id,p.sourceId()));
                    if(entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.TextConcat || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.TextLetter || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.TextContains || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.FormatCheck || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.FormatProject || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.HasField || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.ValueEquals || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.ValueCheck || entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Field)used.putIfAbsent("evaluate",p.sourceId());
                    if(entry.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.JumpFalse)used.putIfAbsent("if",p.sourceId());
                }
            }
            String entry = step instanceof FlowProgram.Evaluate evaluate ? evaluate.expression()
                : step instanceof FlowProgram.Invoke call && call.operands().isPresent() ? call.operands().orElseThrow().entry()
                : FlowProgram.valueInput(step) instanceof FlowInput.Expression expression ? expression.entry() : null;
            if (entry != null && program.expressions().program().entries().containsKey(entry)
                    && FlowProgram.publicReader(program.expressions().program().entries().get(entry).graph()))
                used.putIfAbsent("public_memory", step.sourceId());
            FlowBlockDeclarations.rules(step).forbidContexts().forEach(id -> excluded.putIfAbsent(id, step.sourceId()));
            // Launch uses the target declaration, not its asynchronously executed body or CALL DAG.
            var target = step instanceof FlowProgram.TaskStart launch ? launch.procedure()
                : step instanceof FlowProgram.TryLaunch launch ? launch.procedure()
                : step instanceof FlowProgram.Watch watch ? watch.handler().orElse(null) : null;
            if (target != null) program.steps().stream()
                .filter(s -> s instanceof FlowProgram.Procedure p && p.key().equals(target))
                .map(FlowProgram.Procedure.class::cast).findFirst().ifPresent(p ->
                    p.rules().forbidContexts().forEach(id -> excluded.putIfAbsent(id, p.sourceId())));
        }
        void merge(Summary other) { other.used.forEach(used::putIfAbsent); other.excluded.forEach(excluded::putIfAbsent); }
    }
    static void validate(FlowProgram program, FlowBranches branches, Map<com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition.Key,com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.ProcedureAssembler.Image> authored) {
        var steps = program.steps();
        if (steps.stream().allMatch(step -> FlowBlockDeclarations.rules(step).equals(FlowBlockRules.NONE))) return;
        var known = new HashSet<String>(); var contextKinds = new HashSet<String>();
        for (var kind : FlowBlockDeclarations.Kind.values()) {
            if (!kind.delimiter()) known.addAll(kind.selectors());
            if (kind.header()) contextKinds.addAll(kind.selectors());
        }
        steps.stream().filter(FlowProgram.Procedure.class::isInstance).forEach(step -> {
            known.addAll(FlowBlockDeclarations.selectors(step)); contextKinds.addAll(FlowBlockDeclarations.selectors(step));
        });
        var summaries = new TreeMap<Integer, Summary>();
        var dependencies = new TreeMap<Integer, Set<Integer>>(); var callers = new HashMap<Integer, Set<Integer>>();
        for (int i = 0; i < steps.size(); i++) if (steps.get(i) instanceof FlowProgram.Procedure) {
            var summary = new Summary(); summary.add(steps.get(i), program, authored);
            var definition=(FlowProgram.Procedure)steps.get(i);
            if(definition.assembly().isPresent()) for(String selector:definition.rules().forbidInside())
                if(summary.used.containsKey(selector) && !FlowBlockDeclarations.selectors(definition).contains(selector))
                    throw invalid(definition.sourceId(),"Assembly body forbids "+selector);
            summaries.put(i, summary); dependencies.put(i, new TreeSet<>());
        }
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i); var rules = FlowBlockDeclarations.rules(step);
            for (var selectors : List.of(rules.forbidInside(), rules.forbidContexts())) for (String selector : selectors)
                if (!known.contains(selector)) throw invalid(step.sourceId(), "Unknown block rule selector: " + selector);
            for (String selector : rules.forbidContexts()) if (!contextKinds.contains(selector))
                throw invalid(step.sourceId(), "Block rule selector is not a context: " + selector);
            int owner = branches.owner(i);
            if (owner < 0) continue;
            summaries.get(owner).add(step, program, authored);
            for (var called : program.synchronousTargets(step)) {
                int target = branches.procedure(called); dependencies.get(owner).add(target);
                callers.computeIfAbsent(target, ignored -> new TreeSet<>()).add(owner);
            }
        }
        // Each fixed procedure is summarized once; diamond calls do not expand paths or Java stack depth.
        var ready = new ArrayDeque<Integer>(); dependencies.forEach((at, needs) -> { if (needs.isEmpty()) ready.add(at); });
        while (!ready.isEmpty()) {
            int target = ready.removeFirst();
            for (int owner : callers.getOrDefault(target, Set.of())) {
                summaries.get(owner).merge(summaries.get(target)); dependencies.get(owner).remove(target);
                if (dependencies.get(owner).isEmpty()) ready.addLast(owner);
            }
        }
        var scopes = new ArrayDeque<FlowProgram.Step>();
        var bans = new HashMap<String, Deque<String>>(); var contexts = new HashMap<String, Deque<String>>();
        for (var step : steps) {
            var declaration = FlowBlockDeclarations.of(step);
            if (declaration.delimiter()) {
                if (declaration.shape() == FlowBlockDeclarations.Shape.CLOSE) {
                    var scope = scopes.pop(); remove(bans, FlowBlockDeclarations.rules(scope).forbidInside());
                    remove(contexts, FlowBlockDeclarations.selectors(scope));
                }
                continue;
            }
            var use = new Summary(); use.add(step, program, authored);
            for (var called : program.synchronousTargets(step)) use.merge(summaries.get(branches.procedure(called)));
            use.used.forEach((id, origin) -> {
                if (bans.containsKey(id)) throw invalid(step.sourceId(), "Block " + bans.get(id).peek() + " forbids " + id + " inside (via " + origin + ")");
            });
            use.excluded.forEach((id, origin) -> {
                if (contexts.containsKey(id)) throw invalid(step.sourceId(), "Block " + origin + " forbids context " + id + " (inside " + contexts.get(id).peek() + ")");
            });
            if (declaration.header()) {
                scopes.push(step); add(bans, FlowBlockDeclarations.rules(step).forbidInside(), step.sourceId() + " (" + declaration.id() + ")");
                add(contexts, FlowBlockDeclarations.selectors(step), step.sourceId());
            }
        }
    }
    private static void add(Map<String, Deque<String>> map, Set<String> keys, String source) {
        keys.forEach(key -> map.computeIfAbsent(key, ignored -> new ArrayDeque<>()).push(source));
    }
    private static void remove(Map<String, Deque<String>> map, Set<String> keys) {
        keys.forEach(key -> { var stack = map.get(key); stack.pop(); if (stack.isEmpty()) map.remove(key); });
    }
    private static ComputationException invalid(String source, String message) {
        return FlowProgram.error(ComputationException.Code.INVALID_GRAPH, source, message);
    }
}
