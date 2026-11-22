package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
/** Ref-1: bounded capability checks plus the existing indexed public-memory contract. */
public record RegisterReference(Kind kind,int target,int value,int output) implements RegisterProgram.Instruction {
 public enum Kind { ADDRESS,READ,WRITE,READONLY,SAME }
 public RegisterReference{Objects.requireNonNull(kind);if(target<0||output< -1||value< -1)throw new IllegalArgumentException("Invalid reference operands");}
 public int words(){return switch(kind){case ADDRESS,READ,READONLY->3;case WRITE->3;case SAME->4;};}
 public List<Integer> reads(){return switch(kind){case ADDRESS->List.of();case READ,READONLY->List.of(target);case WRITE,SAME->List.of(target,value);};}
 public RegisterReference remap(Map<Integer,Integer> mapping){return new RegisterReference(kind,kind==Kind.ADDRESS?target:mapping.get(target),value<0?-1:mapping.get(value),output<0?-1:mapping.get(output));}
 public void validate(List<DataType> types,Map<String,ProgramDefinition.Cell> cells){
  if(kind==Kind.ADDRESS){var declared=new ArrayList<>(new TreeMap<>(cells).values());if(target>=declared.size()||!(declared.get(target).type() instanceof DataType.Scalar scalar)||!types.get(output).equals(new DataType.RefType(scalar,true))||value!=-1)bad();return;}
  if(!(types.get(target) instanceof DataType.RefType))bad();var ref=(DataType.RefType)types.get(target);
  switch(kind){
   case READ->{if(types.get(output)!=ref.target()||value!=-1)bad();}
   case READONLY->{if(!types.get(output).equals(new DataType.RefType(ref.target(),false))||value!=-1)bad();}
   case WRITE->{if(!ref.writable()||types.get(value)!=ref.target()||output!=-1)bad();}
   case SAME->{if(!(types.get(value) instanceof DataType.RefType)||types.get(output)!=DataType.Scalar.BOOLEAN)bad();}
   default->throw new AssertionError();
  }
 }
 private static void bad(){throw new IllegalArgumentException("Invalid reference instruction types");}
 public static void validate(DataValue value,UUID computer,long generation,String image,UUID context,RegisterMemory memory){
  if(value.localReferences()==0)return;
  if(!(value instanceof DataValue.LocalReference r)||!r.computer().equals(computer)||r.generation()!=generation||!r.image().equals(image)||!r.context().equals(context)||r.cell()>=memory.size()||!r.type().target().equals(memory.cellType(r.cell())))throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT,"reference","Foreign, stale, nested or invalid local reference");
 }
 public RegisterMemory execute(Map<Integer,DataValue> registers,RegisterMemory memory,UUID computer,long generation,String image,UUID context){
  if(kind==Kind.ADDRESS){if(memory.active(context))throw new ComputationException(ComputationException.Code.INVALID_ARGUMENT,"reference","Public address access during transaction");registers.put(output,new DataValue.LocalReference(computer,generation,image,context,target,new DataType.RefType((DataType.Scalar)memory.cellType(target),true)));return memory;}
  if(!(registers.get(target) instanceof DataValue.LocalReference r))throw new ComputationException(ComputationException.Code.TYPE_MISMATCH,"reference","Expected nominal local reference");validate(r,computer,generation,image,context,memory);
  switch(kind){
   case READ->registers.put(output,memory.publicLoad(context,r.cell()));
   case WRITE->{if(!r.type().writable())throw new ComputationException(ComputationException.Code.TYPE_MISMATCH,"reference","Read-only reference");var replacement=registers.get(value);LocalReferences.portable(replacement);if(!r.type().target().accepts(replacement))throw new ComputationException(ComputationException.Code.TYPE_MISMATCH,"reference","Reference target type mismatch");return memory.publicStoreValidated(context,r.cell(),replacement);}
   case READONLY->registers.put(output,r.readonly());
   case SAME->{if(!(registers.get(value) instanceof DataValue.LocalReference other))throw new ComputationException(ComputationException.Code.TYPE_MISMATCH,"reference","Expected nominal local reference");validate(other,computer,generation,image,context,memory);registers.put(output,new DataValue.BooleanValue(r.sameTarget(other)));}
   default->throw new AssertionError();
  }return memory;
 }
}
