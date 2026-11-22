package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblyTypeRequirements;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;

/** A compilation-only schedule of checked concrete visual instances. Never persisted as player source. */
final class FlowSpecializations {
    private record InstanceKey(int definition, List<DataType> arguments) {}
    private record At(int position, FlowProgram.Step step) {}
    private record Instance(ModuleDefinition.Key key, Map<String, DataType> outputs, List<At> body) {}
    private record GraphKey(String name, Map<String, DataType> inputs) {}
    private record GraphView(String name, ProgramCompiler.InstalledProgram compiled) {}
    private static final int MAX_ACTIVE_CHECKS = 64; // Host stack guard, independent of runtime/task budgets.

    private final FlowProgram source;
    private final ResourceLimits limits;
    private final FlowBranches structure;
    private final Optional<ModuleDefinition.Key> task;
    private final ProgramCompiler.InstalledProgram originalExpressions;
    private final Map<String, ProgramDefinition.Entry> entries;
    private final Map<GraphKey, GraphView> graphViews = new HashMap<>();
    private final Map<InstanceKey, Instance> instances = new LinkedHashMap<>();
    private final Set<Integer> instantiated = new HashSet<>();
    private final Set<ModuleDefinition.Key> occupied = new HashSet<>();
    private final Set<ModuleDefinition.Key> taskTargets = new HashSet<>();
    private final List<Map<String, DataType>> schemas;
    private long remainingPositions=ProgramEditorWire.MAX_BYTES;
    private long remainingGraphNodes=ProgramEditorWire.MAX_BYTES;
    private long remainingRoutineWords;
    private final long moduleNodes;
    private static final int ROUTINE_OVERHEAD=new RegisterProgram.Jump(0).words()+new RegisterProgram.Return().words();
    private int depth, keyCounter, entryCounter;

    static boolean needed(List<FlowProgram.Step> steps) {
        return steps.stream().anyMatch(step -> step instanceof FlowProgram.Procedure procedure && candidate(procedure));
    }
    private static boolean candidate(FlowProgram.Procedure procedure) {
        return procedure.assembly().isEmpty() && (procedure.inputs().containsValue(DataType.Scalar.ANY)
            || procedure.outputs().containsValue(DataType.Scalar.ANY));
    }
    private static final class NeedsSoftware extends RuntimeException {}
    static Optional<FlowProgram.Installation> tryClosed(FlowProgram source,ResourceLimits limits,Optional<ModuleDefinition.Key> task) {
        try {return Optional.of(new FlowSpecializations(source,limits,task).compile());}
        catch(NeedsSoftware open) {return Optional.empty();}
    }
    private static boolean openValue(DataType type) {
        // The pre-existing serial receive envelope is opaque, not a software ANY payload.
        return AssemblyTypeRequirements.containsAny(type) && !type.equals(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.RECEIVE);
    }
    private boolean probing() {return source.compilation()==FlowProgram.Compilation.SOFTWARE_V1;}
    static FlowProgram.Installation compile(FlowProgram source, ResourceLimits limits, Optional<ModuleDefinition.Key> task) {
        return new FlowSpecializations(source,limits,task).compile();
    }

    private FlowSpecializations(FlowProgram source, ResourceLimits limits, Optional<ModuleDefinition.Key> task) {
        this.source=source; this.limits=limits; this.task=task;
        structure=source.checkedStructure(task,true,limits.programUnits());
        originalExpressions=source.compileExpressions(new ResourceLimits(Long.MAX_VALUE,limits.memoryUnits(),1),limits.memoryUnits());
        entries=new TreeMap<>(source.expressions().program().entries());
        schemas=new ArrayList<>(Collections.nCopies(source.steps().size()+1,null));
        remainingRoutineWords=limits.programUnits();
        moduleNodes=originalExpressions.modules().values().stream().mapToLong(graph->graph.order().size()).sum();
        for (var step : source.steps()) {
            if (step instanceof FlowProgram.Procedure procedure) occupied.add(procedure.key());
            if (step instanceof FlowProgram.Procedure p && (p.event().isPresent() || p.terminalKey().isPresent() || p.startup())) taskTargets.add(p.key());
            if (step instanceof FlowProgram.TryLaunch launch) taskTargets.add(launch.procedure());
            if (step instanceof FlowProgram.Watch watch) watch.handler().ifPresent(taskTargets::add);
            if (step instanceof FlowProgram.TaskStart launch) taskTargets.add(launch.procedure());
        }
        task.ifPresent(key->{
            var definition=(FlowProgram.Procedure)source.steps().get(structure.procedure(key));
            if (candidate(definition) && !taskTargets.contains(key))
                throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH,definition.sourceId(),
                    "Closed synchronous ANY signature does not provide a task entry ABI");
        });
    }

    private FlowProgram.Installation compile() {
        var main=new ArrayList<At>();
        var fields=new LinkedHashMap<>(source.inputs());
        var scopes=new ArrayDeque<FlowProgram.Scope>();
        for (int pos=0;pos<source.steps().size();pos++) {
            if (source.steps().get(pos) instanceof FlowProgram.Procedure) {
                // A skipped definition still has a source position, possibly the main entry.
                // Its enclosing schema must not be mistaken for an empty input contract.
                remember(pos,fields);
                pos=structure.end(pos); continue;
            }
            reserve(1,source.steps().get(pos).sourceId());
            main.add(check(pos,fields,scopes,false));
        }
        schemas.set(source.steps().size(),Map.copyOf(fields));
        // Keep fixed/native definitions, including dormant ones, so their existing instruction
        // families and continuation envelope features do not disappear on the new policy.
        for (int pos=0;pos<source.steps().size();pos++) if (source.steps().get(pos) instanceof FlowProgram.Procedure procedure) {
            if (!instantiated.contains(pos) && (!candidate(procedure) || taskTargets.contains(procedure.key())))
                instance(pos,procedure.inputs(),false);
        }
        for (int pos=0;pos<source.steps().size();pos++) if (source.steps().get(pos) instanceof FlowProgram.Procedure procedure) {
            if (!instantiated.contains(pos)) instance(pos,procedure.inputs(),false);
        }
        var scheduled=new ArrayList<>(main);
        instances.values().forEach(value->scheduled.addAll(value.body()));
        var steps=scheduled.stream().map(At::step).toList();
        int[] positions=scheduled.stream().mapToInt(At::position).toArray();
        var projected=FlowBranches.compile(steps,task);
        var compiled=originalExpressions;
        var loweredGraphs=new HashMap<String,ProgramCompiler.CompiledGraph>();
        graphViews.values().forEach(view->loweredGraphs.put(view.name(),view.compiled().select(view.name()).root()));
        var display=schemas.stream().map(types->new DataType.RecordType(types==null?Map.of():types)).toList();
        var signals=FlowSignalModes.compile(source.steps(),structure);
        var checked=new FlowProgram.Installation(compiled,display,projected,signals);
        var image=FlowAssemblyCompiler.compileProjected(source,checked,limits.programUnits(),steps,positions,loweredGraphs);
        if (task.isPresent()) image=image.forTask(task.orElseThrow().id(),task.orElseThrow().revision());
        return new FlowProgram.Installation(compiled,display,structure,signals,Optional.of(image));
    }

    private Instance instance(int pos, Map<String,DataType> types, boolean specialize) {
        var key=new InstanceKey(pos,new ArrayList<>(new TreeMap<>(types).values()));
        var existing=instances.get(key);
        if (existing!=null) return existing;
        var original=(FlowProgram.Procedure)source.steps().get(pos);
        if(probing() && candidate(original) && types.containsValue(DataType.Scalar.ANY))throw new NeedsSoftware();
        if (depth>=MAX_ACTIVE_CHECKS)
            throw FlowProgram.error(ComputationException.Code.PROGRAM_LIMIT,original.sourceId(),"Closed specialization call depth exceeds compiler limit");
        depth++;
        try {
            int end=structure.end(pos);
            reserve(end-pos+1,original.sourceId());
            remainingRoutineWords-=ROUTINE_OVERHEAD;
            if (remainingRoutineWords<0)
                throw FlowProgram.error(ComputationException.Code.PROGRAM_LIMIT,original.sourceId(),"Closed routine overhead exceeds code capacity");
            if (specialize && types.values().stream().anyMatch(AssemblyTypeRequirements::containsAny))
                throw open(original.sourceId());
            var body=new ArrayList<At>();
            var fields=new LinkedHashMap<>(types);
            var scopes=new ArrayDeque<FlowProgram.Scope>();
            var name=specialize?generatedKey():original.key();
            Map<String,DataType> outputs=new TreeMap<>(original.outputs());
            for(int i=pos+1;i<end;i++) body.add(check(i,fields,scopes,specialize));
            remember(end,fields);
            if(original.assembly().isEmpty()) outputs.replaceAll((port,declared)->{
                var local=original.returns().get(port); var actual=fields.get(local);
                if(actual==null||!declared.compatibleWith(actual))
                    throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH,source.steps().get(end).sourceId(),"Procedure return does not match local: "+port);
                if(specialize && declared==DataType.Scalar.ANY) {
                    if(AssemblyTypeRequirements.containsAny(actual))
                        throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH,source.steps().get(end).sourceId(),"Closed specialization cannot prove concrete return: "+port);
                    if(probing() && actual!=DataType.Scalar.INTEGER && actual!=DataType.Scalar.BOOLEAN)throw new NeedsSoftware();
                    return actual;
                }
                return declared;
            });
            body.addFirst(new At(pos,new FlowProgram.Procedure(original.sourceId(),name,types,outputs,original.returns(),original.rules(),original.assembly(),original.event(),original.terminalKey(),original.startup())));
            body.add(new At(end,source.steps().get(end)));
            var result=new Instance(name,Map.copyOf(outputs),List.copyOf(body));
            instances.put(key,result); instantiated.add(pos);
            return result;
        } finally { depth--; }
    }

    private At check(int pos, Map<String,DataType> fields, Deque<FlowProgram.Scope> scopes, boolean closedBody) {
        var step=source.steps().get(pos);
        remember(pos,fields);
        if (step instanceof FlowProgram.Invoke call) {
            int target=structure.procedure(call.procedure());
            var procedure=(FlowProgram.Procedure)source.steps().get(target);
            var args=new TreeMap<>(procedure.inputs());
            boolean specialize=candidate(procedure)&&!taskTargets.contains(procedure.key());
            var operands = call.operands();
            var compiledOperands = originalExpressions;
            if (operands.isPresent()) {
                var current = operands.orElseThrow();
                var view = graph(current.entry(), current.inputs(), fields, call.sourceId());
                if (view != null) {
                    operands = Optional.of(new FlowProgram.InvokeOperands(view.name(), current.inputs()));
                    compiledOperands = view.compiled();
                }
                if ((probing() || closedBody) && compiledOperands.definition().entries().containsKey(operands.orElseThrow().entry())) {
                    var types = compiledOperands.select(operands.orElseThrow().entry()).root().nodeTypes().values();
                    if (probing() && types.stream().anyMatch(FlowSpecializations::openValue)) throw new NeedsSoftware();
                    if (closedBody && types.stream().anyMatch(AssemblyTypeRequirements::containsAny)) throw open(call.sourceId());
                }
            }
            var projectedCall = new FlowProgram.Invoke(call.sourceId(), call.procedure(), call.bindings(), operands);
            var actuals = FlowProgram.argumentTypes(projectedCall, procedure.inputs(), fields, compiledOperands);
            args.replaceAll((port,declared)->{
                var actual=actuals.get(port);
                if(specialize && declared==DataType.Scalar.ANY) {
                    if(probing() && actual!=DataType.Scalar.INTEGER&&actual!=DataType.Scalar.BOOLEAN)throw new NeedsSoftware();
                    if(actual!=DataType.Scalar.INTEGER&&actual!=DataType.Scalar.BOOLEAN)
                        throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH,call.sourceId(),
                            "Closed ANY requires a proved INTEGER or BOOLEAN argument: "+port);
                    return actual;
                }
                return declared;
            });
            var checked=instance(target,args,specialize);
            if(probing())call.bindings().returns().forEach((port,local)->{
                if(fields.containsKey(local)&&!fields.get(local).equals(checked.outputs().get(port)))throw new NeedsSoftware();
            });
            call.bindings().validateReturns(checked.outputs(),fields,call.sourceId());
            call.bindings().returns().forEach((port,local)->fields.put(local,checked.outputs().get(port)));
            if (probing() && fields.values().stream().anyMatch(FlowSpecializations::openValue)) throw new NeedsSoftware();
            if (closedBody && fields.values().stream().anyMatch(AssemblyTypeRequirements::containsAny)) throw open(call.sourceId());
            return new At(pos,new FlowProgram.Invoke(call.sourceId(),checked.key(),call.bindings(),operands));
        }
        var compiled=originalExpressions;
        if (step instanceof FlowProgram.Evaluate evaluate && evaluate.bindings().isPresent()) {
            var view=graph(evaluate.expression(),evaluate.bindings().orElseThrow().arguments(),fields,evaluate.sourceId());
            if(view!=null) {
                compiled=view.compiled();
                step=new FlowProgram.Evaluate(evaluate.sourceId(),view.name(),evaluate.bindings());
            }
        } else if (!(step instanceof FlowProgram.Shared)
                && FlowProgram.valueInput(step) instanceof FlowInput.Expression expression && expression.arguments().isPresent()) {
            var view=graph(expression.entry(),expression.arguments().orElseThrow(),fields,step.sourceId());
            if(view!=null) {
                compiled=view.compiled();
                var input=new FlowInput.Expression(view.name(),expression.output(),expression.arguments());
                step=switch(step) {
                    case FlowProgram.If branch -> new FlowProgram.If(branch.sourceId(),input);
                    case FlowProgram.Repeat loop -> new FlowProgram.Repeat(loop.sourceId(),input);
                    case FlowProgram.Ask ask -> new FlowProgram.Ask(ask.sourceId(),input);
                    case FlowProgram.Watch watch -> new FlowProgram.Watch(watch.sourceId(),watch.handler(),input);
                    case FlowProgram.Until loop -> new FlowProgram.Until(loop.sourceId(),input);
                    case FlowProgram.WaitUntil wait -> new FlowProgram.WaitUntil(wait.sourceId(), input);
                    default -> step;
                };
            }
        }
        if(probing() && step instanceof FlowProgram.Evaluate evaluate) {
            var outputs=compiled.select(evaluate.expression()).root().outputType().fields();
            evaluate.returns(outputs.keySet()).forEach((port,local)->{
                if(fields.containsKey(local)&&!fields.get(local).equals(outputs.get(port)))throw new NeedsSoftware();
            });
        }
        source.checkStep(pos,step,fields,scopes,structure,compiled.definition(),compiled,true);
        if(probing()) {
            String expression=step instanceof FlowProgram.Evaluate evaluate?evaluate.expression()
                :FlowProgram.valueInput(step) instanceof FlowInput.Expression input?input.entry():null;
            if(fields.values().stream().anyMatch(FlowSpecializations::openValue)
                    || expression!=null && compiled.select(expression).root().nodeTypes().values().stream().anyMatch(FlowSpecializations::openValue))
                throw new NeedsSoftware();
        }
        if (closedBody) {
            if (fields.values().stream().anyMatch(AssemblyTypeRequirements::containsAny)) throw open(step.sourceId());
            String expression=step instanceof FlowProgram.Evaluate evaluate ? evaluate.expression()
                : FlowProgram.valueInput(step) instanceof FlowInput.Expression input ? input.entry() : null;
            if (expression!=null && compiled.select(expression).root().nodeTypes().values().stream().anyMatch(AssemblyTypeRequirements::containsAny))
                throw open(step.sourceId());
            String cell=step instanceof FlowProgram.Shared shared ? shared.cell() : step instanceof FlowProgram.Memory memory
                && (memory.kind()==FlowProgram.MemoryKind.READ||memory.kind()==FlowProgram.MemoryKind.WRITE) ? memory.cell() : null;
            if (cell!=null && AssemblyTypeRequirements.containsAny(compiled.cells().get(cell).type())) throw open(step.sourceId());
        }
        return new At(pos,step);
    }

    private GraphView graph(String name, Map<String,String> bindings, Map<String,DataType> fields, String location) {
        var authored=source.expressions().program().entries().get(name);
        if(authored==null) return null;
        var graph=authored.graph();
        var types=new TreeMap<>(graph.inputs());
        if(!types.keySet().equals(bindings.keySet())) return null;
        types.replaceAll((port,declared)->{
            var actual=fields.get(bindings.get(port));
            return declared==DataType.Scalar.ANY && actual!=null&&!AssemblyTypeRequirements.containsAny(actual)?actual:declared;
        });
        if(types.equals(graph.inputs())) return null;
        var key=new GraphKey(name,Map.copyOf(types));
        var existing=graphViews.get(key);
        if(existing!=null) return existing;
        remainingGraphNodes-=1L+graph.nodes().size()+moduleNodes;
        if(remainingGraphNodes<0) throw FlowProgram.error(ComputationException.Code.PROGRAM_LIMIT,location,"Closed expression views exceed compiler limit");
        String view;
        do { view="_closed_expression_"+(++entryCounter); } while(entries.containsKey(view));
        var entry=new ProgramDefinition.Entry(new ComputationGraph(types,graph.nodes(),graph.outputs()),authored.writes(),authored.sends());
        var program=source.expressions().program();
        var archive=new ProgramArchive(new ProgramDefinition(program.graph(),program.cells(),program.writes(),program.sends(),Map.of(view,entry)),
            source.expressions().modules());
        ProgramCompiler.InstalledProgram compiled;
        try { compiled=ProgramCompiler.compileFlow(archive.program(),archive.modules(),new ResourceLimits(Long.MAX_VALUE,Long.MAX_VALUE,1),source.valueProcedures()); }
        catch(ComputationException e) { throw FlowProgram.error(e.code(),location,"Closed expression: "+e.getMessage()); }
        entries.put(view,entry);
        var result=new GraphView(view,compiled); graphViews.put(key,result);
        return result;
    }

    private void remember(int pos,Map<String,DataType> fields) {
        var old=schemas.get(pos);
        if(old==null) {schemas.set(pos,Map.copyOf(fields));return;}
        var common=new TreeMap<>(old);
        common.keySet().retainAll(fields.keySet());
        common.replaceAll((name,type)->type.equals(fields.get(name))?type:DataType.Scalar.ANY);
        schemas.set(pos,Map.copyOf(common));
    }
    private ModuleDefinition.Key generatedKey() {
        ModuleDefinition.Key key;
        do { key=new ModuleDefinition.Key("_closed_procedure_"+(++keyCounter),1); } while(!occupied.add(key));
        return key;
    }
    private RuntimeException open(String location) {
        if(probing())return new NeedsSoftware();
        return FlowProgram.error(ComputationException.Code.TYPE_MISMATCH,location,
            "Closed specialization cannot lower an open ANY value in this body");
    }
    private void reserve(int count,String location) {
        remainingPositions-=count;
        if(remainingPositions<0) throw FlowProgram.error(ComputationException.Code.PROGRAM_LIMIT,location,"Closed procedure instances exceed compiler capacity");
    }
}
