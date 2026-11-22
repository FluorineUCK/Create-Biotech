package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class FlowAuthoredDevicesTest {
 static final ResourceLimits LIMITS=new ResourceLimits(2000,100000,1);
 static final ModuleDefinition.Key KEY=new ModuleDefinition.Key("native",1);
 static FlowProgram program(String source){return new FlowProgram(Map.of(),List.of(
  new FlowProgram.Invoke("call",KEY,new FlowCallBindings(Map.of(),Map.of("result","answer"))),
  new FlowProgram.Procedure("native",KEY,Map.of(),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","value"),FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),
  new FlowProgram.EndProcedure("end"))).withAssembly();}
 @Test void ordinaryScratchInvokeCopiesSampledInputToOutputAndReturnWithRealIsaCosts(){
  var p=program("REG value INTEGER\nSIO.READ value p0\nSIO.WRITE p1 value\nRET");
  var r=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,new DataValue.RecordValue(Map.of()),LIMITS);
  for(int i=0;r.checkpoint().status()!=FlowRuntime.Status.COMPLETE;i++){assertTrue(i<20);r.advanceRound(Map.of("p0",12));}
  assertEquals(new DataValue.IntegerValue(12),r.checkpoint().locals().field("answer"));
  assertEquals(Map.of("p1",12),r.checkpoint().signals().outputs());
  assertEquals(9,r.checkpoint().work()); assertEquals(27,p.usage(LIMITS).programUnits());
 }

 @Test void actualOwnerRejectsNativePinsMissingFromItsOwnHardware(){
  var p=program("REG value INTEGER\nSIO.READ value full.input\nSIO.WRITE full.output value\nRET ; SIO.READ fake comment.pin");
  var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(LIMITS,new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000,1000000,1000000),com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins(List.of("bus.actual"),List.of("full.input")));
  var host=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->fail("Rejected install cannot publish"));
  var error=assertThrows(FlowPins.Unavailable.class,()->host.installFlow(p,new DataValue.RecordValue(Map.of()),limits,true));
  assertTrue(error.getMessage().contains("full.output"),error.getMessage());assertFalse(error.getMessage().contains("comment.pin"));assertNull(host.write());
 }

 @Test void directAndIndirectDeclarationsCatchAuthoredSignalEffectsButStatusIsNotAWait(){
  var nativeProgram=program("REG value INTEGER\nSIO.READ value p0\nSIO.WRITE p1 value\nRET");
  var original=(FlowProgram.Procedure)nativeProgram.steps().get(1);
  for(String selector:List.of("signal_read","signal_write")){
   var steps=new ArrayList<>(nativeProgram.steps());steps.set(1,new FlowProgram.Procedure(original.sourceId(),KEY,original.inputs(),original.outputs(),original.returns(),new FlowBlockRules(Set.of(selector),Set.of()),original.assembly()));
   var direct=new FlowProgram(Map.of(),steps).withAssembly();assertThrows(ComputationException.class,()->direct.validate(LIMITS));
   var wrapper=new ModuleDefinition.Key("wrapper",1);steps=new ArrayList<>();
   steps.add(new FlowProgram.Invoke("entry",wrapper,new FlowCallBindings(Map.of(),Map.of("result","answer"))));
   steps.add(new FlowProgram.Procedure("wrapper",wrapper,Map.of(),original.outputs(),Map.of("result","value"),new FlowBlockRules(Set.of(selector),Set.of())));
   steps.add(new FlowProgram.Invoke("indirect",KEY,new FlowCallBindings(Map.of(),Map.of("result","value"))));steps.add(new FlowProgram.EndProcedure("wrapper_end"));steps.addAll(nativeProgram.steps().subList(1,3));
   var p=new FlowProgram(Map.of(),steps).withAssembly();assertThrows(ComputationException.class,()->p.validate(LIMITS));
  }
  var p=program("REG value INTEGER\nXBUS.STATUS value x0\nRET");var status=(FlowProgram.Procedure)p.steps().get(1);var steps=new ArrayList<>(p.steps());
  steps.set(1,new FlowProgram.Procedure(status.sourceId(),KEY,status.inputs(),status.outputs(),status.returns(),new FlowBlockRules(Set.of("device_wait","signal_write","signal_read"),Set.of()),status.assembly()));
  assertDoesNotThrow(()->new FlowProgram(Map.of(),steps).withAssembly().validate(LIMITS));
 }

 static final com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits HOST_LIMITS=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(LIMITS,new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000,1000000,1000000));
 @Test void nativeSignalOwnerReloadsEveryInstructionBoundaryWithoutLosingCodeOrModes(){
  var p=program("REG value INTEGER\nSIO.READ value p0\nSIO.WRITE p1 value\nRET");
  var host=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});host.installFlow(p,new DataValue.RecordValue(Map.of()),HOST_LIMITS,true);
  for(int i=0;i<9;i++){
   host.step(HOST_LIMITS,true,Map.of("p0",12));var before=host.flowSnapshot().orElseThrow();var raw=host.write();
   var restored=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});restored.read(raw);restored.tick(HOST_LIMITS,true);
   assertTrue(restored.loaded(),restored.diagnostic().toString());assertEquals(before,restored.flowSnapshot().orElseThrow());assertEquals(raw,restored.write());host=restored;
  }
  assertEquals(FlowRuntime.Status.COMPLETE,host.flowSnapshot().orElseThrow().state().status());assertEquals(Map.of("p1",12),host.flowSnapshot().orElseThrow().state().signals().outputs());
 }

 static com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits pins(com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins pins,long words){return new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(words,100000,1),HOST_LIMITS.archive(),HOST_LIMITS.mailbox(),pins);}
 @Test void eachActualComputerKeepsItsOwnCodeCapacityPinsAndHardwareChangeCheckpoint(){
  var p=program("REG value INTEGER\nSIO.READ value p0\nSIO.WRITE p1 value\nXBUS.STATUS value x0\nRET");
  var enough=HOST_LIMITS;var host=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});host.installFlow(p,new DataValue.RecordValue(Map.of()),enough,true);
  host.step(enough,true,Map.of("p0",12));host.step(enough,true,Map.of("p0",12));host.step(enough,true,Map.of("p0",12));
  var before=host.flowSnapshot().orElseThrow();var raw=host.write();assertEquals(Map.of("p1",12),before.state().signals().outputs());
  var absentBus=pins(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins(List.of(),List.of("p0","p1")),2000);
  var second=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});
  assertThrows(FlowPins.Unavailable.class,()->second.installFlow(p,new DataValue.RecordValue(Map.of()),absentBus,true));assertNull(second.write());
  var smaller=pins(HOST_LIMITS.pins(),p.usage(LIMITS).programUnits()-1);
  assertEquals(ComputationException.Code.PROGRAM_LIMIT,assertThrows(ComputationException.class,()->second.installFlow(p,new DataValue.RecordValue(Map.of()),smaller,true)).code());assertNull(second.write());assertEquals(before,host.flowSnapshot().orElseThrow());
  for(var changed:List.of(absentBus,pins(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins(List.of("x0"),List.of("p0")),2000),smaller)){
   host.tick(changed,true);assertFalse(host.loaded());assertEquals(raw,host.write());assertEquals(before,host.flowSnapshot().orElseThrow());
   var restored=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});restored.read(raw);restored.tick(changed,true);assertFalse(restored.loaded());assertEquals(raw,restored.write());
   host.tick(enough,true);assertTrue(host.loaded());assertEquals(before,host.flowSnapshot().orElseThrow());
  }
 }
 @Test void nativeDevicesKeepIntegerOperandsInitializationAndClosedVersions(){
  for(String source:List.of("REG value BOOLEAN\nSIO.READ value p0\nRET","REG value DECIMAL\nXBUS.STATUS value x0\nRET","REG value INTEGER\nSIO.WRITE p0 value\nRET","REG value INTEGER\nSIO.READ value p0 extra\nRET"))assertThrows(ComputationException.class,()->program(source).validate(LIMITS));
  var p=program("REG value INTEGER\nSIO.READ value p0\nRET");var definition=(FlowProgram.Procedure)p.steps().get(1);var steps=new ArrayList<>(p.steps());
  steps.set(1,new FlowProgram.Procedure(definition.sourceId(),KEY,definition.inputs(),definition.outputs(),definition.returns(),definition.rules(),Optional.of(new AssemblySource(definition.assembly().orElseThrow().text()))));
  assertDoesNotThrow(()->new FlowProgram(Map.of(),steps).withAssembly().validate(LIMITS));
  assertThrows(IllegalArgumentException.class,()->new AssemblySource(0,"RET"));assertThrows(IllegalArgumentException.class,()->new AssemblySource(AssemblySource.VERSION+1,"RET"));
 }
 @Test void invalidNativeSampleRejectsBeforePublishingRegisterOrOutputChanges(){
  var p=program("REG value INTEGER\nSIO.READ value p0\nSIO.WRITE p1 value\nRET");
  for(int sample:List.of(-1,16)){
   var r=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,new DataValue.RecordValue(Map.of()),LIMITS);r.advanceRound(Map.of("p0",12));var before=r.checkpoint();
   assertThrows(IllegalArgumentException.class,()->r.advanceRound(Map.of("p0",sample)));assertEquals(before,r.checkpoint());
   assertEquals(before,FlowRuntime.restore(p,before,LIMITS).checkpoint());
   r.advanceRound(Map.of("p0",12));assertEquals(before.work()+1,r.checkpoint().work());assertEquals(new DataValue.IntegerValue(12),r.checkpoint().locals().field("value"));

  }
 }
 @Test void nativeDriveReadAndStatusLoopRestoresActualModesWithoutSelfSampling(){
  var p=program("REG value INTEGER\nCONST value 99\nagain:\nSIO.WRITE p0 value\nXBUS.STATUS value x0\nSIO.READ value p0\nJMP again");
  var host=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});host.installFlow(p,new DataValue.RecordValue(Map.of()),HOST_LIMITS,true);
  for(int i=0;i<24;i++){
   host.step(HOST_LIMITS,true,Map.of("p0",8,"x0",15));var saved=host.flowSnapshot().orElseThrow();var restored=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});restored.read(host.write());restored.tick(HOST_LIMITS,true);assertTrue(restored.loaded(),restored.diagnostic().toString());assertEquals(saved,restored.flowSnapshot().orElseThrow());
   if(i==2)assertEquals(Map.of("p0",15),saved.state().signals().outputs());if(i==4){assertTrue(saved.state().signals().outputs().isEmpty());assertEquals(new DataValue.IntegerValue(8),saved.state().locals().field("value"));}host=restored;
  }
 }
 @Test void nativeSioWriterCannotHideInsideTaskWrapperAndStatusOnlyTaskStartRemainsAllowed(){
  var base=program("REG value INTEGER\nCONST value 15\nSIO.WRITE p0 value\nRET");var wrapper=new ModuleDefinition.Key("wrapper",1);var steps=new ArrayList<FlowProgram.Step>();
  steps.add(new FlowProgram.TaskStart("launch",wrapper,Map.of(),"h"));steps.add(new FlowProgram.Procedure("wrapper",wrapper,Map.of(),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","value")));
  steps.add(new FlowProgram.Invoke("indirect",KEY,new FlowCallBindings(Map.of(),Map.of("result","value"))));steps.add(new FlowProgram.EndProcedure("wrapper_end"));steps.addAll(base.steps().subList(1,3));
  var p=new FlowProgram(Map.of(),steps).withAssembly();var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(LIMITS,HOST_LIMITS.archive(),HOST_LIMITS.mailbox(),HOST_LIMITS.pins(),HOST_LIMITS.serial(),2);
  var host=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});host.installFlow(p,new DataValue.RecordValue(Map.of()),limits,true);host.step(limits,true);var failed=host.flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.FAILED,failed.state().status());assertEquals(ComputationException.Code.INVALID_ARGUMENT,failed.state().diagnostic().orElseThrow().code());assertTrue(failed.tasks().contexts().isEmpty());assertTrue(failed.state().signals().outputs().isEmpty());
  base=program("REG value INTEGER\nXBUS.STATUS value x0\nRET");steps=new ArrayList<>();steps.add(new FlowProgram.TaskStart("launch",KEY,Map.of(),"h"));steps.add(new FlowProgram.TaskJoin("join",KEY,"h","outcome"));steps.addAll(base.steps().subList(1,3));p=new FlowProgram(Map.of(),steps).withAssembly();
  host=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});host.installFlow(p,new DataValue.RecordValue(Map.of()),limits,true);host.setPaused(false,limits,true);for(int i=0;i<30;i++)host.tick(limits,true,Map.of("x0",15));var done=host.flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.COMPLETE,done.state().status());var outcome=(DataValue.RecordValue)done.state().locals().field("outcome");assertEquals(new DataValue.BooleanValue(true),outcome.field("ok"));assertEquals(new DataValue.RecordValue(Map.of("result",new DataValue.IntegerValue(15))),outcome.field("value"));assertTrue(done.state().signals().outputs().isEmpty());assertTrue(done.pinClaims().entries().isEmpty());assertTrue(done.receipts().isEmpty());
 }
 @Test void installedDeviceSourceCannotBeDowngradedOrReplacedBehindItsCheckpointIdentity(){
  var p=program("REG value INTEGER\nSIO.READ value p0\nRET");var host=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});host.installFlow(p,new DataValue.RecordValue(Map.of()),HOST_LIMITS,true);host.step(HOST_LIMITS,true);
  var raw=(net.minecraft.nbt.CompoundTag)host.write();var data=(DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(raw.getCompound("Data"),HOST_LIMITS.archive());
  for(int version:List.of(23,3)){
   var flow=(DataValue.RecordValue)data.field("flow");var encoded=(DataValue.RecordValue)flow.field("program");var list=new ArrayList<>(((DataValue.ListValue)encoded.field("steps")).values());var declaration=(DataValue.RecordValue)list.get(1);var assembly=(DataValue.RecordValue)declaration.field("assembly");list.set(1,patch(declaration,"assembly",patch(assembly,"assembly_version",new DataValue.IntegerValue(version))));encoded=patch(encoded,"steps",new DataValue.ListValue(list));var forged=raw.copy();forged.put("Data",com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(patch(data,"flow",patch(flow,"program",encoded)),HOST_LIMITS.archive()));
   var restored=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});restored.read(forged);restored.tick(HOST_LIMITS,true);assertFalse(restored.loaded());assertEquals(forged,restored.write());
  }
  assertThrows(ComputationException.class,()->FlowRuntime.restore(program("REG value INTEGER\nSIO.READ value p0\nRET ; new identity"),host.flowSnapshot().orElseThrow().state(),LIMITS));
 }
 static DataValue.RecordValue patch(DataValue.RecordValue value,String name,DataValue replacement){var fields=new TreeMap<>(value.fields());fields.put(name,replacement);return new DataValue.RecordValue(fields);}
 @Test void unchangedDefaultSignatureThreeOpcodePlayerDemoFitsReal32WordHardware(){
  for(var source:List.of("SIO.WRITE p0 value\nXBUS.STATUS value x0\nRET","SIO.READ value p0\nSIO.WRITE p1 value\nRET")){
   var p=new FlowProgram(Map.of("seed",DataType.Scalar.INTEGER),List.of(new FlowProgram.Invoke("call",KEY,new FlowCallBindings(Map.of("value","seed"),Map.of("result","answer"))),new FlowProgram.Procedure("native",KEY,Map.of("value",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","value"),FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),new FlowProgram.EndProcedure("end"))).withAssembly();
   var limits=new ResourceLimits(32,100000,1);assertEquals(32,p.usage(limits).programUnits());var r=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(12))),limits);for(int i=0;r.checkpoint().status()!=FlowRuntime.Status.COMPLETE;i++){assertTrue(i<20);r.advanceRound(Map.of("p0",12,"x0",0));}assertEquals(11,r.checkpoint().work());assertEquals(new DataValue.IntegerValue(source.startsWith("SIO.WRITE")?0:12),r.checkpoint().locals().field("answer"));
  }
 }
}
