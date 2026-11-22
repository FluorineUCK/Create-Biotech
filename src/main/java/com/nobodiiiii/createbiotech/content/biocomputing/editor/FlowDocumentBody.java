package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import java.util.*;

/** Portable control and reset values, never a runtime checkpoint or Computer identity. */
public record FlowDocumentBody(Map<String, DataType> inputs, List<FlowProgram.Step> steps, DataValue.RecordValue initial,
        FlowBlockLayout canvas, Map<String, ComputationGraph> inline, FlowProgram.Execution execution, FlowProgram.Compilation compilation) {
    public FlowDocumentBody(Map<String, DataType> inputs, List<FlowProgram.Step> steps, DataValue.RecordValue initial,
            FlowBlockLayout canvas, Map<String, ComputationGraph> inline, FlowProgram.Execution execution) {
        this(inputs,steps,initial,canvas,inline,execution,FlowProgram.Compilation.ORIGINAL);
    }
    /** Existing documents retain their backend until an explicit migration can preserve their continuations. */
    public FlowDocumentBody(Map<String, DataType> inputs, List<FlowProgram.Step> steps, DataValue.RecordValue initial,
            FlowBlockLayout canvas, Map<String, ComputationGraph> inline) {
        this(inputs, steps, initial, canvas, inline, FlowProgram.Execution.LEGACY_GRAPH);
    }
    public FlowDocumentBody(Map<String, DataType> inputs, List<FlowProgram.Step> steps, DataValue.RecordValue initial, FlowBlockLayout canvas) {
        this(inputs, steps, initial, canvas, Map.of());
    }
    public FlowDocumentBody(Map<String, DataType> inputs, List<FlowProgram.Step> steps, DataValue.RecordValue initial) {
        this(inputs, steps, initial, FlowBlockLayout.DEFAULT);
    }
    public FlowDocumentBody {
        inputs = DataValue.immutableMap(inputs); steps = List.copyOf(steps); Objects.requireNonNull(initial); LocalReferences.portable(initial); Objects.requireNonNull(execution); Objects.requireNonNull(compilation);
        if (compilation != FlowProgram.Compilation.ORIGINAL && execution != FlowProgram.Execution.REGISTER_V1)
            throw new IllegalArgumentException("Compilation policy requires register execution");
        new FlowProgram(inputs, steps); // Stable source/name validation, not installation or execution.
        Objects.requireNonNull(canvas).validate(steps);
        if (!canvas.terminalHats().isEmpty() && execution != FlowProgram.Execution.REGISTER_V1)
            throw new IllegalArgumentException("Terminal event hats require register execution");
        if (!canvas.extensions().isEmpty() && execution != FlowProgram.Execution.REGISTER_V1)
            throw new IllegalArgumentException("Scratch extensions require register execution");
        inline = DataValue.immutableMap(inline);
        var all = new ArrayList<>(steps); canvas.detached().forEach(stack -> all.addAll(stack.steps()));
        if ((FlowProgram.hasTryLaunch(all) || FlowProgram.hasWatch(all)) && execution != FlowProgram.Execution.REGISTER_V1) throw new IllegalArgumentException("Try-launch and edge hats require typed ISA execution");
        if (execution != FlowProgram.Execution.REGISTER_V1 && FlowProgram.hasStopScript(all))
            throw new IllegalArgumentException("Stop script requires typed ISA execution");
        if (execution != FlowProgram.Execution.REGISTER_V1 && FlowProgram.hasWaitUntil(all))
            throw new IllegalArgumentException("Wait until requires typed ISA execution");
        if (execution != FlowProgram.Execution.REGISTER_V1 && FlowProgram.hasTimerReset(all))
            throw new IllegalArgumentException("Timer reset requires typed ISA execution");
        if (execution != FlowProgram.Execution.REGISTER_V1 && FlowProgram.hasAsk(all))
            throw new IllegalArgumentException("Terminal prompt requires typed ISA execution");
        if (FlowProgram.hasMonitor(all) && execution != FlowProgram.Execution.REGISTER_V1)
            throw new IllegalArgumentException("Monitors require typed ISA execution");
        if (FlowProgram.hasInvokeOperands(all) && execution != FlowProgram.Execution.REGISTER_V1)
            throw new IllegalArgumentException("Inline procedure operands require typed ISA execution");
        if (FlowProgram.hasWatch(steps)) throw new IllegalArgumentException("An edge hat must own a separate script");
        if (steps.stream().anyMatch(step -> step instanceof FlowProgram.Procedure || step instanceof FlowProgram.EndProcedure))
            throw new IllegalArgumentException("Procedure definitions belong to separate definition hats");
        for (var id : inline.keySet()) {
            if (all.stream().noneMatch(step -> step.sourceId().equals(id) && FlowInlineValues.owned(step)))
                throw new IllegalArgumentException("Inline graph must belong to an explicitly bound call: " + id);
        }
        if (!canvas.values().equals(canvas.values().retain(inline))) throw new IllegalArgumentException("Unattached socket shadow");
        if (!new DataType.RecordType(inputs).accepts(initial)) throw new IllegalArgumentException("Flow initial values do not match declared inputs");
    }
    /** Link separate definitions once; ordinary parked drafts still add no executable instructions or graphs. */
    public FlowProgram program(ProgramArchive expressions) {
        var scripts = new ArrayList<>(steps);
        canvas.detached().stream().filter(stack -> stack.steps().getFirst() instanceof FlowProgram.Procedure).forEach(stack -> scripts.addAll(stack.steps()));
        var executable = ScratchEdgeHats.link(scripts);
        if (inline.isEmpty()) return new FlowProgram(inputs, executable, expressions, execution, compilation);
        var entries = new LinkedHashMap<>(expressions.program().entries());
        var lowered = new ArrayList<FlowProgram.Step>();
        for (var step : executable) {
            var graph = inline.get(step.sourceId());
            if (graph == null) { lowered.add(step); continue; }
            String name = "inline_" + step.sourceId();
            for (int suffix = 2; entries.containsKey(name); suffix++) name = "inline_" + step.sourceId() + "_" + suffix;
            entries.put(name, new ProgramDefinition.Entry(graph, List.of(), List.of()));
            lowered.add(FlowInlineValues.lower(step, name));
        }
        var p = expressions.program();
        return new FlowProgram(inputs, lowered, new ProgramArchive(new ProgramDefinition(p.graph(), p.cells(), p.writes(), p.sends(), entries), expressions.modules()), execution, compilation);
    }
    /** Presentation names keyed by fixed execution identity, never used to route a terminal action. */
    public Map<ModuleDefinition.Key, String> terminalHatNames() {
        if (canvas.terminalHats().isEmpty()) return Map.of();
        var names = new LinkedHashMap<ModuleDefinition.Key, String>();
        for (var stack : canvas.detached()) if (stack.steps().getFirst() instanceof FlowProgram.Procedure procedure) {
            String name = canvas.terminalHats().get(procedure.sourceId());
            if (name != null) names.put(procedure.key(), name);
        }
        return Map.copyOf(names);
    }
    FlowDocumentBody edited(Map<String, DataType> types, List<FlowProgram.Step> steps, DataValue.RecordValue initial,
            FlowBlockLayout canvas, Map<String, ComputationGraph> graphs) {
        var keep = new HashSet<String>();
        steps.forEach(step -> keep.add(step.sourceId())); canvas.detached().forEach(stack -> stack.steps().forEach(step -> keep.add(step.sourceId())));
        var retained = new LinkedHashMap<>(graphs); retained.keySet().retainAll(keep);
        return new FlowDocumentBody(types, steps, initial, canvas.withValues(canvas.values().retain(retained)), retained, execution, compilation);
    }
    public static FlowDocumentBody empty() { return new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of())); }
    public static FlowDocumentBody emptyAssembly() {
        return new FlowDocumentBody(Map.of(), List.of(), new DataValue.RecordValue(Map.of()), FlowBlockLayout.DEFAULT, Map.of(), FlowProgram.Execution.REGISTER_V1);
    }
    public static FlowDocumentBody emptySoftwareAssembly() {
        return new FlowDocumentBody(Map.of(),List.of(),new DataValue.RecordValue(Map.of()),FlowBlockLayout.DEFAULT,Map.of(),
            FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1);
    }
    /** Explicit draft opt-in only; never applied by load or installed-state restoration. */
    public FlowDocumentBody withSoftwareAny() {
        return new FlowDocumentBody(inputs,steps,initial,canvas,inline,FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1);
    }
    public static FlowDocumentBody emptyClosedAssembly() {
        return new FlowDocumentBody(Map.of(),List.of(),new DataValue.RecordValue(Map.of()),FlowBlockLayout.DEFAULT,Map.of(),
            FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.CLOSED_V1);
    }
    /** Recover an editable draft when only installed code remains; never changes that code or its continuation. */
    public static FlowDocumentBody fromProgram(FlowProgram program, DataValue.RecordValue initial) {
        var main = new ArrayList<FlowProgram.Step>(); var definitions = new ArrayList<FlowBlockLayout.Stack>();
        var structure = com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowBranches.structure(program.steps());
        for (int index = 0; index < program.steps().size(); index++) {
            if (program.steps().get(index) instanceof FlowProgram.Procedure) {
                int end = structure.end(index);
                definitions.add(new FlowBlockLayout.Stack(new ProgramDocument.Position(280,16 + definitions.size()*80),
                    program.steps().subList(index,end+1)));
                index = end;
            } else main.add(program.steps().get(index));
        }
        return new FlowDocumentBody(program.inputs(),main,initial,new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(),definitions), Map.of(), program.execution(), program.compilation());
    }
}
