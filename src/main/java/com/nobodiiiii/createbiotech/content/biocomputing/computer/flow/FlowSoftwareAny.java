package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblyTypeRequirements;
import java.util.*;

/** Frozen source contracts for software values. This planner never sees runtime inputs. */
final class FlowSoftwareAny {
    record Plan(Map<Integer,Map<String,DataType>> contracts) {
        DataType contract(int owner, String name, DataType inferred) {
            return contracts.getOrDefault(owner,Map.of()).getOrDefault(name,inferred);
        }
    }
    static FlowProgram.Installation compile(FlowProgram source, ResourceLimits limits, Optional<ModuleDefinition.Key> task) {
        if(source.inputs().values().stream().anyMatch(AssemblyTypeRequirements::containsAny))
            throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH,"flow input","Software ANY external input ABI is not supported");
        if(source.expressions().program().cells().values().stream().anyMatch(c->AssemblyTypeRequirements.containsAny(c.type())))
            throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH,"flow memory","Software ANY memory boundary is not supported");
        if(FlowSpecializations.needed(source.steps())) {
            var closed=FlowSpecializations.tryClosed(source,limits,task);
            if(closed.isPresent())return closed.orElseThrow();
        }
        var branches=source.checkedStructure(task,true,limits.programUnits());
        var compiled=source.compileExpressions(new ResourceLimits(Long.MAX_VALUE,limits.memoryUnits(),1),limits.memoryUnits());
        var contracts=new HashMap<Integer,Map<String,DataType>>();
        contracts.put(-1,new TreeMap<>(source.inputs()));
        for(int pos=0;pos<source.steps().size();pos++) if(source.steps().get(pos) instanceof FlowProgram.Procedure p) {
            if(p.assembly().isPresent() && (p.inputs().values().stream().anyMatch(AssemblyTypeRequirements::containsAny)||p.outputs().values().stream().anyMatch(AssemblyTypeRequirements::containsAny)))
                throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH,p.sourceId(),"Software ANY cannot cross authored native ANY ABI");
            contracts.put(pos,new TreeMap<>(p.inputs()));
        }
        for(int pos=0;pos<source.steps().size();pos++) {
            var step=source.steps().get(pos); Map<String,DataType> outputs=Map.of(); Map<String,String> bindings=Map.of();
            if(step instanceof FlowProgram.Invoke call) {
                outputs=((FlowProgram.Procedure)source.steps().get(branches.procedure(call.procedure()))).outputs();
                bindings=call.bindings().returns();
            } else if(step instanceof FlowProgram.Evaluate call) {
                outputs=compiled.select(call.expression()).root().outputType().fields(); bindings=call.returns(outputs.keySet());
            }
            var owner=contracts.get(branches.owner(pos));
            for(var entry:bindings.entrySet()) if(outputs.get(entry.getKey())==DataType.Scalar.ANY)
                owner.putIfAbsent(entry.getValue(),DataType.Scalar.ANY);
        }
        var plan=new Plan(contracts);
        var fields=new LinkedHashMap<>(source.inputs()); var scopes=new ArrayDeque<FlowProgram.Scope>();
        var schemas=new ArrayList<DataType.RecordType>(); schemas.add(new DataType.RecordType(fields));
        for(int pos=0;pos<source.steps().size();pos++) {
            var step=source.steps().get(pos);
            if(step instanceof FlowProgram.TaskStart call) {
                var p=(FlowProgram.Procedure)source.steps().get(branches.procedure(call.procedure()));
                if(p.inputs().values().stream().anyMatch(AssemblyTypeRequirements::containsAny)||p.outputs().values().stream().anyMatch(AssemblyTypeRequirements::containsAny)
                        ||call.arguments().values().stream().map(fields::get).filter(Objects::nonNull).anyMatch(AssemblyTypeRequirements::containsAny))
                    throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH,step.sourceId(),"Software ANY task ABI is not supported");
            }
            if(step instanceof FlowProgram.Read read&&AssemblyTypeRequirements.containsAny(read.type()))
                throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH,step.sourceId(),"Software ANY device input boundary is not supported");
            String external=step instanceof FlowProgram.Shared shared&&shared.value() instanceof FlowInput.Local local?local.name():step instanceof FlowProgram.SerialSend s?s.local():step instanceof FlowProgram.Write w?w.local():step instanceof FlowProgram.Memory m&&m.kind()==FlowProgram.MemoryKind.WRITE?m.local():null;
            if(external!=null&&fields.get(external)==DataType.Scalar.ANY)
                throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH,step.sourceId(),"Software ANY cannot cross device or memory boundary");
            source.checkStep(pos,step,fields,scopes,branches,source.expressions().program(),compiled,true);
            int owner=step instanceof FlowProgram.Procedure?pos:step instanceof FlowProgram.EndProcedure?-1:branches.owner(pos);
            fields.replaceAll((name,type)->plan.contract(owner,name,type));
            schemas.add(new DataType.RecordType(fields));
        }
        var checked=new FlowProgram.Installation(compiled,List.copyOf(schemas),branches,FlowSignalModes.compile(source.steps(),branches));
        var image=FlowAssemblyCompiler.compileSoftware(source,checked,limits.programUnits(),plan);
        if(task.isPresent()) image=image.forTask(task.orElseThrow().id(),task.orElseThrow().revision());
        return new FlowProgram.Installation(compiled,checked.locals(),branches,checked.signals(),Optional.of(image));
    }
}
