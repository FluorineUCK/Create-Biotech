package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SelectMaterialsCapacityTest {
 @Test void completePublicPureCallableFitsDefaultHardware() {
  var t=FlowProcedureTemplates.all().stream().filter(p->p.key().id().equals("select_material")).findFirst().orElseThrow();
  var args=new LinkedHashMap<String,String>();t.inputs().keySet().forEach(n->args.put(n,n));
  var p=new FlowProgram(t.inputs(),List.of(new FlowProgram.Invoke("call",t.key(),new FlowCallBindings(args,Map.of("result","selected"))),t,new FlowProgram.EndProcedure("end"))).withAssembly();
  assertEquals(232,p.usage(new ResourceLimits(256,65536,1)).programUnits());
  assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION,t.assembly().orElseThrow().version());
 }
}
