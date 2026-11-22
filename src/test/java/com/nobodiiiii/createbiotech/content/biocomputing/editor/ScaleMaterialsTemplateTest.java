package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ScaleMaterialsTemplateTest {
 @Test void ordinaryPublicTemplateScalesEveryRowAndSurvivesEveryOwnerBudgetBoundary(){
  var template=FlowProcedureTemplates.all().stream().filter(t->t.key().id().equals("scale_materials")).findFirst().orElseThrow();
  assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION,template.assembly().orElseThrow().version());
  long lastWork=-1;
  for(int n:List.of(0,1,17)) {
   var rows=new ArrayList<DataValue>();
   for(int i=0;i<n;i++)rows.add(new DataValue.RecordValue(Map.of("item",i%2==0?new DataValue.TextValue("minecraft:stone"):DataValue.NullValue.INSTANCE,"tag",i%2==1?new DataValue.TextValue("c:stones"):DataValue.NullValue.INSTANCE,"count",new DataValue.IntegerValue(i%3+1),"components",new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("raw",new DataValue.NbtValue(6,new DataValue.IntegerValue(Long.MIN_VALUE))))))));
   var original=new DataValue.ListValue(rows);
   for(long batches:new long[]{3,0,-2,Long.MAX_VALUE}) {
    var initial=new DataValue.RecordValue(Map.of("materials",original,"batches",new DataValue.IntegerValue(batches)));
    var draft=new FlowDraft(new FlowDocumentBody(template.inputs(),List.of(),initial,FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1));
    var id=draft.placeTemplate(template,new FlowDraft.Socket(null,0),new ProgramDocument.Position(1,1));
    var call=(FlowProgram.Invoke)draft.find(id).orElseThrow();draft.replace(id,new FlowProgram.Invoke(id,call.procedure(),new FlowCallBindings(Map.of("materials","materials","batches","batches"),Map.of("result","delivery"))));
    var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(draft.snapshot()));
    var document=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),FoundationRecipeTest.LIMITS),FoundationRecipeTest.LIMITS);
    var limits=new ComputerHostLimits(new ResourceLimits(256,65536,1),FoundationRecipeTest.LIMITS,com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,HardwarePins.DEFAULT,SerialHardware.NONE,1,true,false);
    var program=document.flow().orElseThrow().program(document.archive());assertTrue(program.usage(limits.hardware()).programUnits()<=256);
    var owner=new ComputerBlockHost(()->{});owner.installFlow(program,initial,limits,true);
    int steps=0;long peak=0;
    while(!Set.of(FlowRuntime.Status.COMPLETE,FlowRuntime.Status.FAILED).contains(owner.flowSnapshot().orElseThrow().state().status())) {
     assertTrue(++steps<10000);owner.step(limits,true);
     var measured=owner.flowSnapshot().orElseThrow();peak=Math.max(peak,measured.state().controlUnits()+measured.initialInputs().storageUnits()+measured.control().storageUnits()+program.usage(limits.hardware()).constantUnits());
     var save=owner.write();var next=new ComputerBlockHost(()->{});next.read(save);next.tick(limits,true);assertTrue(next.loaded(),next.diagnostic().toString());assertEquals(save,next.write());owner=next;
    }
    var state=owner.flowSnapshot().orElseThrow().state();assertEquals(original,state.locals().field("materials"));
    if(batches==Long.MAX_VALUE&&n>1){assertEquals(FlowRuntime.Status.FAILED,state.status());assertFalse(state.locals().fields().containsKey("delivery"),state.locals().fields().toString());continue;}
    assertEquals(FlowRuntime.Status.COMPLETE,state.status(),state.diagnostic().toString());
    var result=(DataValue.RecordValue)state.locals().field("delivery");assertEquals(new DataValue.BooleanValue(batches>0),result.field("ok"));
    if(batches<=0){assertEquals(DataValue.NullValue.INSTANCE,result.field("materials"));continue;}
    var out=(DataValue.ListValue)result.field("materials");assertEquals(n,out.values().size());
    for(int i=0;i<n;i++){var expected=new TreeMap<>(((DataValue.RecordValue)rows.get(i)).fields());expected.put("count",new DataValue.IntegerValue(Math.multiplyExact(((DataValue.IntegerValue)((DataValue.RecordValue)rows.get(i)).field("count")).value(),batches)));assertEquals(new DataValue.RecordValue(expected),out.values().get(i));}
    if(batches==3){assertTrue(state.work()>lastWork);lastWork=state.work();System.out.println("SCALE_MATERIALS rows="+n+" words="+program.usage(limits.hardware()).programUnits()+" work="+state.work()+" sourceUnits="+original.storageUnits()+" peakOwnedUnits="+peak);}
   }
  }
 }
 @Test void catalogDraftImportHasExplicitBindingsUndoAndConflictProtection(){
  var entry=FlowBlockCatalog.search(Map.of(),FlowBlockPalette.Category.DATA,"scale_materials").getFirst();var template=entry.procedure();assertEquals("scale_materials",template.key().id());
  var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());var before=draft.snapshot();var id=draft.placeTemplate(template,new FlowDraft.Socket(null,0),new ProgramDocument.Position(1,1));
  var call=(FlowProgram.Invoke)draft.find(id).orElseThrow();assertTrue(call.bindings().arguments().isEmpty());assertTrue(call.bindings().returns().isEmpty());var imported=draft.snapshot();draft.undo();assertEquals(before,draft.snapshot());draft.redo();assertEquals(imported,draft.snapshot());
  var def=FlowProcedureTools.definitions(draft.snapshot()).getFirst();draft.replace(def.sourceId(),new FlowProgram.Procedure(def.sourceId(),def.key(),def.inputs(),def.outputs(),def.returns(),def.rules(),Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource(def.assembly().orElseThrow().text()+"\n; edited"))));var changed=draft.snapshot();
  assertThrows(IllegalArgumentException.class,()->draft.placeTemplate(template,new FlowDraft.Socket(null,0),new ProgramDocument.Position(1,1)));assertEquals(changed,draft.snapshot());
 }
}
