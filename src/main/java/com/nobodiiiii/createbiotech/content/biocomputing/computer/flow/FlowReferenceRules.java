package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
final class FlowReferenceRules {
 private FlowReferenceRules(){}
 static void source(FlowProgram source,boolean assembly){
  if(source.inputs().values().stream().anyMatch(LocalReferences::contains))bad("flow","References cannot enter through external inputs");
  for(var step:source.steps()){
   if(step instanceof FlowProgram.Reference&&!assembly)bad(step.sourceId(),"References require typed ISA execution");
   if(step instanceof FlowProgram.Procedure p){
    var types=new ArrayList<>(p.inputs().values());types.addAll(p.outputs().values());
    if(types.stream().anyMatch(t->LocalReferences.contains(t)&&(!assembly||!(t instanceof DataType.RefType))))bad(p.sourceId(),"References require concrete top-level typed ISA parameters/results");
   }
  }
 }
 static void step(FlowProgram source,FlowProgram.Step step,Map<String,DataType> fields){
  String external=step instanceof FlowProgram.Write w?w.local():step instanceof FlowProgram.SerialSend s?s.local():null;
  if(external!=null&&fields.containsKey(external)&&LocalReferences.contains(fields.get(external)))bad(step.sourceId(),"Reference cannot cross a device boundary; read a snapshot first");
  if(step instanceof FlowProgram.Read r&&LocalReferences.contains(r.type()))bad(step.sourceId(),"References cannot enter from devices");
  ModuleDefinition.Key task=step instanceof FlowProgram.TaskStart t?t.procedure():step instanceof FlowProgram.TaskJoin t?t.procedure():step instanceof FlowProgram.TryLaunch t?t.procedure():null;
  if(task!=null)for(var entry:source.steps())if(entry instanceof FlowProgram.Procedure p&&p.key().equals(task)&& (p.inputs().values().stream().anyMatch(LocalReferences::contains)||p.outputs().values().stream().anyMatch(LocalReferences::contains)))bad(step.sourceId(),"Reference task transfer is not supported");
 }
 private static void bad(String source,String message){throw FlowProgram.error(ComputationException.Code.TYPE_MISMATCH,source,message);}
}
