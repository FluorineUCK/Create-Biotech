package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
public class StockNativeCompletionDocuments {
 static final List<String> ROLES=List.of("supply_followup","finish","guard","ack");
 static ProgramDocument build(String role) throws Exception {
  var inputs=switch(role){case "supply_followup"->Map.<String,DataType>of("target",SerialExchangeDriverDocuments.TARGET);case "finish"->Map.<String,DataType>of("poll_ticks",INTEGER,"poll_limit",INTEGER);case "guard"->Map.<String,DataType>of();case "ack"->Map.<String,DataType>of("output",SerialExchangeDriverDocuments.TARGET);default->throw new IllegalArgumentException(role);};
  var outputs=Set.of("finish","guard").contains(role)?Map.<String,DataType>of():role.equals("ack")?Map.<String,DataType>of("reply",ANY):Map.<String,DataType>of("job",TEXT,"reply",ANY);
  var in=new TreeMap<String,String>();inputs.keySet().forEach(k->in.put(k,k));var out=new TreeMap<String,String>();outputs.keySet().forEach(k->out.put(k,k));
  var key=new ModuleDefinition.Key("run",1);var program=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("start",key,new FlowCallBindings(in,out)),new FlowProgram.Procedure("implementation",key,inputs,outputs,out,FlowBlockRules.NONE,Optional.of(new AssemblySource(Files.readString(Path.of("examples/biocomputing/stock_native_"+role+".asm"))))),new FlowProgram.EndProcedure("end"))).withAssembly();
  var initial=switch(role){case "supply_followup"->record("target",record("x",0,"y",0,"z",0));case "finish"->record("poll_ticks",20,"poll_limit",80);case "guard"->record();default->record("output",record("x",0,"y",0,"z",0));};
  var draft=new ProgramWorkspace();draft.setFlow(Optional.of(FlowDocumentBody.fromProgram(program,initial)));if(!outputs.isEmpty())draft.setTerminal(Optional.of(NativeLibraryResultLayouts.fields(outputs.keySet().stream().sorted().toArray(String[]::new))));return draft.snapshot();
 }
 @Test void completionProgramsFitAndPublishWithoutHardwareChanges() throws Exception {
  var limits=StockApplicationDriverDocuments.hardware();
  for(var role:ROLES){var d=build(role);System.out.println("COMPLETION_"+role+" "+d.flow().orElseThrow().program(d.archive()).usage(new ResourceLimits(4096,65536,8)));}
  for(var role:ROLES){var d=build(role);PreparedProgram.prepare(d,limits);
   var name="stock_native_"+role+".bcp";var doc=Path.of("examples/biocomputing",name);var asset=Path.of("src/main/resources/assets/create_biotech/biocomputing/examples",name);
   if("1".equals(System.getenv("GENERATE_STOCK_NATIVE_COMPLETION"))){var encoded=Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(d,limits.archive()))+"\n";Files.writeString(doc,encoded);Files.writeString(asset,encoded);}else{assertEquals(Files.readString(doc),Files.readString(asset));assertEquals(d,ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(asset).strip()),limits.archive()));}
  }
 }
 @Test void onlyExactDeliveredReceiptCanAcknowledgeAndPendingAckSurvivesHostRestore() throws Exception {
  var delivered=record("code","ok","phase","complete","milestone","materials_delivered","order","job-1","address","destination");
  var cases=new ArrayList<DataValue.RecordValue>();cases.add(delivered);
  for(var mismatch:List.of(record("phase","waiting"),record("milestone","dispatch_accepted"),record("order","old-job"),record("address","other"),record("code","denied"))){var fields=new LinkedHashMap<>(delivered.fields());fields.putAll(mismatch.fields());cases.add(new DataValue.RecordValue(fields));}
  cases.add(record("code","receive_timeout"));
  for(var receipt:cases){
   var limits=StockApplicationDriverDocuments.hardware();
   var completion=new DataType.RecordType(Map.of("s",ANY,"j",TEXT,"r",ANY));
   var guard=new ComputerBlockHost(()->{});
   guard.installProgram(PreparedProgram.prepare(build("guard"),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
   var worker=new ComputerBlockHost(()->{});var caller=new ComputerBlockHost(()->{});var gateway=new ComputerBlockHost(()->{});
   worker.installProgram(PreparedProgram.prepare(build("ack"),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
   var original=record("state",record("device","device-1","session","session-1","revision",7),"head",record("sequence",3,"address","destination"));
   caller.installFlow(new FlowProgram(Map.of("input",completion),List.of(new FlowProgram.Write("send","x0","input"))).withAssembly(),record("input",record("s",original,"j","job-1","r",receipt)),limits,true);
   var replyType=new DataType.RecordType(Map.of("r",ANY));
   gateway.installFlow(new FlowProgram(Map.of("response",replyType),List.of(new FlowProgram.Read("read","x0","call",new DataType.RecordType(Map.of("p",ANY,"e",TEXT))),new FlowProgram.Write("reply","x0","response"))).withAssembly(),record("response",record("r",record("code","acknowledged"))),limits,true);
   for(var host:List.of(guard,worker,caller,gateway))host.setPaused(false,limits,true);
   boolean restored=false;
   for(int tick=1;tick<10000;tick++){
    guard.tick(tick,limits,true,Map.of("p0",1));worker.tick(tick,limits,true,Map.of());caller.tick(tick,limits,true,Map.of());gateway.tick(tick,limits,true,Map.of());
    guard.exchange("x0",limits,true,caller,"x0",limits,true);guard.exchange("x1",limits,true,worker,"x0",limits,true);
    var state=worker.flowSnapshot().orElseThrow().state();
    if(!restored && state.status()==FlowRuntime.Status.WAITING_WRITE && FlowXBus.pin(worker.flowSnapshot().orElseThrow().program(),state).equals("x1")){
     var saved=worker.write();worker=new ComputerBlockHost(()->{});worker.read(saved);worker.setPaused(false,limits,true);restored=true;
    }
    worker.exchange("x1",limits,true,gateway,"x0",limits,true);
    state=worker.flowSnapshot().orElseThrow().state();assertNotEquals(FlowRuntime.Status.FAILED,state.status(),state.diagnostic().toString());
    if(state.status()==FlowRuntime.Status.COMPLETE)break;
   }
   var done=worker.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,done.status());
   var result=gateway.flowSnapshot().orElseThrow().state().locals();
   if(receipt==delivered){
    assertTrue(restored);var call=(DataValue.RecordValue)result.field("call");var packet=(DataValue.RecordValue)call.field("p");
    assertEquals(new DataValue.TextValue("stock_application_reply"),call.field("e"));
    assertEquals(new DataValue.TextValue("stock_application_command"),packet.field("kind"));
    assertEquals(new DataValue.TextValue("ack"),packet.field("operation"));
    assertEquals(record("x",0,"y",0,"z",0),packet.field("target"));
    assertEquals(record("device","device-1","session","session-1","revision",7,"sequence",3),packet.field("arguments"));
    assertEquals(record("code","acknowledged"),done.locals().field("reply"));
   }else{assertFalse(restored);assertFalse(result.fields().containsKey("call"),"No ACK for "+receipt);assertEquals(record("code","delivery_not_verified","receipt",receipt),done.locals().field("reply"));}
  }
 }
 @Test void pollingKeepsIdentityAcrossWaitingRestoreAndStopsAtTheExplicitLimit() throws Exception {
  for(boolean delivered:List.of(false,true)){
   var limits=StockApplicationDriverDocuments.hardware();var wire=new SerialExchangeDriverDocuments.Wire();
   var gateway=new ComputerBlockHost(()->{},pin->{},wire.source);var worker=new ComputerBlockHost(()->{});var caller=new ComputerBlockHost(()->{});
   gateway.installProgram(PreparedProgram.prepare(SerialExchangeDriverDocuments.build(),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
   var draft=new ProgramWorkspace(build("finish"));var flow=draft.flow().orElseThrow();draft.setFlow(Optional.of(new FlowDocumentBody(flow.inputs(),flow.steps(),record("poll_ticks",1,"poll_limit",3),flow.canvas(),flow.inline(),flow.execution(),flow.compilation())));
   worker.installProgram(PreparedProgram.prepare(draft.snapshot(),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
   var context=new DataType.RecordType(Map.of("c",ANY,"j",TEXT,"i",ANY,"e",ANY,"s",ANY));var inputType=new DataType.RecordType(Map.of("c",context,"t",SerialExchangeDriverDocuments.TARGET));
   var outputType=new DataType.RecordType(Map.of("s",ANY,"j",TEXT,"r",ANY));
   var source=record("head",record("sequence",7,"address","destination"));var target=record("x",2,"y",0,"z",0);
   caller.installFlow(new FlowProgram(Map.of("input",inputType),List.of(new FlowProgram.Write("send","x0","input"),new FlowProgram.Read("finish","x0","result",outputType))).withAssembly(),record("input",record("c",record("c","ok","j","job-1","i",DataValue.NullValue.INSTANCE,"e","destination","s",source),"t",target)),limits,true);
   for(var host:List.of(gateway,worker,caller))host.setPaused(false,limits,true);
   int queries=0;boolean restored=false;var correlations=new HashSet<DataValue>();
   for(int tick=1;tick<30000;tick++){
    gateway.tick(tick,limits,true,Map.of("x0",SerialExchangeDriverDocuments.signal(worker,"x1")));
    worker.tick(tick,limits,true,Map.of("p0",1));caller.tick(tick,limits,true,Map.of());
    worker.exchange("x0",limits,true,caller,"x0",limits,true);worker.exchange("x1",limits,true,gateway,"x0",limits,true);wire.tick();
    var frame=wire.peer.poll("s0");
    if(frame.isPresent()){
     var command=(DataValue.RecordValue)frame.orElseThrow();queries++;
     assertEquals(new DataValue.TextValue("order"),command.field("operation"),"Recovery may query, never submit or ACK");
     assertEquals(record("order","job-1"),command.field("arguments"));assertTrue(correlations.add(command.field("request")),"Fresh communication correlation, stable business identity");
     boolean complete=delivered&&queries==2;
     assertEquals("queued",wire.peer.send("s0",record("kind","production_reply","version",1,"target",target,"request",command.field("request"),"code","ok","phase",complete?"complete":"waiting","milestone",complete?"materials_delivered":"dispatch_accepted","order","job-1","address","destination")));
     if(!restored){var saved=worker.write();worker=new ComputerBlockHost(()->{});worker.read(saved);worker.setPaused(false,limits,true);restored=true;}
    }
    var state=worker.flowSnapshot().orElseThrow().state();assertNotEquals(FlowRuntime.Status.FAILED,state.status(),state.diagnostic().toString());
    if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
   }
   var done=caller.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,done.status());assertTrue(restored);assertEquals(delivered?2:3,queries);
   var result=(DataValue.RecordValue)done.locals().field("result");assertEquals(source,result.field("s"));assertEquals(new DataValue.TextValue("job-1"),result.field("j"));
   assertEquals(new DataValue.TextValue(delivered?"complete":"waiting"),((DataValue.RecordValue)result.field("r")).field("phase"),"Exhaustion returns the observed pending receipt, never invented delivery");
  }
 }
}
