package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ListAppendTest {
 @Test void authoredTypedAppendAliasesWithoutChangingOriginal(){
  var type=new DataType.ListType(DataType.Scalar.INTEGER);
  var inputs=Map.of("values",type,"element",DataType.Scalar.INTEGER);
  var source=new AssemblySource("LIST.APPEND values values element\nRET");
  ProcedureAssembler.assemble(source,inputs,Map.of("result",type),Map.of("result","values"),65536,256);
  var key=new ModuleDefinition.Key("append",1);
  var p=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("values","values","element","element"),Map.of("result","out"))),new FlowProgram.Procedure("def",key,inputs,Map.of("result",type),Map.of("result","values"),FlowBlockRules.NONE,Optional.of(source)),new FlowProgram.EndProcedure("end"))).withAssembly();
  var old=new DataValue.ListValue(List.of(new DataValue.IntegerValue(7)));
  var r=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,new DataValue.RecordValue(Map.of("values",old,"element",new DataValue.IntegerValue(9))),new ResourceLimits(256,65536,1));
  int cycles=0;while(r.checkpoint().status()!=FlowRuntime.Status.COMPLETE){r.advance(1);assertTrue(++cycles<100,r.checkpoint().diagnostic().toString());}
  assertEquals(List.of(new DataValue.IntegerValue(7)),old.values());
  assertEquals(new DataValue.ListValue(List.of(new DataValue.IntegerValue(7),new DataValue.IntegerValue(9))),r.checkpoint().locals().field("out"));
 }
}

