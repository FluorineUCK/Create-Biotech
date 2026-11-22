package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SelectMaterialTemplateTest {
 @Test void actualPaletteDropBindsEditablePureSourceAndRunsWithOnlyISA1aAtEveryOwnerRestoreBoundary() {
  var template=FlowProcedureTemplates.all().stream().filter(t->t.key().id().equals("select_material")).findFirst().orElseThrow();
  var entry=FlowBlockCatalog.search(Map.of(),FlowBlockPalette.Category.DATA,"select_material").getFirst();assertEquals(template,entry.procedure());
  for(boolean tag:List.of(false,true))for(boolean chosen:List.of(false,true)) {
   var patch=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("raw",new DataValue.NbtValue(6,new DataValue.IntegerValue(Long.MIN_VALUE)))));
   DataValue nil=DataValue.NullValue.INSTANCE;var item=new DataValue.TextValue("minecraft:stone");var requested=new DataValue.TextValue("c:stones");
   var descriptor=new DataValue.RecordValue(Map.of("slot",new DataValue.IntegerValue(7),"item",item,"tags",new DataValue.ListValue(List.of(requested))));
   var initial=new DataValue.RecordValue(Map.of("item",tag?nil:item,"tag",tag?requested:nil,"count",new DataValue.IntegerValue(2),"components",patch,"option",chosen?descriptor:nil));
   var body=new FlowDocumentBody(template.inputs(),List.of(),initial,FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1);
   var draft=new FlowDraft(body);var canvas=new FlowBlockCanvas(draft,()->{});canvas.bounds(0,0,800,500);canvas.takeTemplate(template,-10,-10);canvas.release(24,50,0);
   var imported=draft.snapshot();draft.undo();assertEquals(body,draft.snapshot());draft.redo();assertEquals(imported,draft.snapshot());
   var call=(FlowProgram.Invoke)imported.steps().getFirst();assertTrue(call.bindings().arguments().isEmpty());
   var args=new LinkedHashMap<String,String>();template.inputs().keySet().forEach(n->args.put(n,n));
   draft.replace(call.sourceId(),new FlowProgram.Invoke(call.sourceId(),call.procedure(),new FlowCallBindings(args,Map.of("result","selected"))));
   var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(draft.snapshot()));
   var document=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),FoundationRecipeTest.LIMITS),FoundationRecipeTest.LIMITS);
   var limits=new ComputerHostLimits(new ResourceLimits(256,65536,1),FoundationRecipeTest.LIMITS,com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,HardwarePins.DEFAULT,SerialHardware.NONE,1,true,false);
   var program=document.flow().orElseThrow().program(document.archive());var owner=new ComputerBlockHost(()->{});owner.installFlow(program,initial,limits,true);
   var denied=new ComputerHostLimits(limits.hardware(),limits.archive(),limits.mailbox(),limits.pins(),limits.serial(),1,false,false);
   assertThrows(FlowPins.Unavailable.class,()->new ComputerBlockHost(()->{}).installFlow(program,initial,denied,true));
   int steps=0;while(owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE) {
    assertTrue(++steps<4000);owner.step(limits,true);assertNotEquals(FlowRuntime.Status.FAILED,owner.flowSnapshot().orElseThrow().state().status(),owner.diagnostic().toString());
    var saved=owner.write();var cold=new ComputerBlockHost(()->{});cold.read(saved);cold.tick(limits,true);assertTrue(cold.loaded(),cold.diagnostic().toString());assertEquals(saved,cold.write());owner=cold;
   }
   var state=owner.flowSnapshot().orElseThrow().state();var result=(DataValue.RecordValue)state.locals().field("selected");
   assertEquals(new DataValue.BooleanValue(!tag||chosen),result.field("ok"));
   if(tag&&!chosen){assertEquals(new DataValue.TextValue("selection_required"),result.field("code"));assertEquals(nil,result.field("item"));}
   else assertEquals(new DataValue.RecordValue(Map.of("item",item,"count",new DataValue.IntegerValue(2),"components",patch)),result.field("item"));
   initial.fields().forEach((k,v)->assertEquals(v,state.locals().field(k)));
   System.out.println("SELECT_PURE tag="+tag+" chosen="+chosen+" words="+program.usage(limits.hardware()).programUnits()+" work="+state.work());
  }
 }
}
